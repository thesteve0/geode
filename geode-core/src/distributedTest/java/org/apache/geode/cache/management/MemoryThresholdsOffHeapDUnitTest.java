/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.cache.management;

import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.apache.geode.distributed.ConfigurationProperties.OFF_HEAP_MEMORY_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.AttributesMutator;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.CacheLoader;
import org.apache.geode.cache.CacheLoaderException;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.LoaderHelper;
import org.apache.geode.cache.LowMemoryException;
import org.apache.geode.cache.PartitionAttributesFactory;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.client.PoolFactory;
import org.apache.geode.cache.client.PoolManager;
import org.apache.geode.cache.client.ServerOperationException;
import org.apache.geode.cache.control.ResourceManager;
import org.apache.geode.cache.management.MemoryThresholdsDUnitTest.Range;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.cache30.CacheSerializableRunnable;
import org.apache.geode.cache30.ClientServerTestCase;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.cache.DistributedRegion;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.internal.cache.PartitionedRegionHelper;
import org.apache.geode.internal.cache.ProxyBucketRegion;
import org.apache.geode.internal.cache.control.InternalResourceManager;
import org.apache.geode.internal.cache.control.InternalResourceManager.ResourceType;
import org.apache.geode.internal.cache.control.MemoryEvent;
import org.apache.geode.internal.cache.control.MemoryThresholds.MemoryState;
import org.apache.geode.internal.cache.control.OffHeapMemoryMonitor;
import org.apache.geode.internal.cache.control.OffHeapMemoryMonitor.OffHeapMemoryMonitorObserver;
import org.apache.geode.internal.cache.control.ResourceAdvisor;
import org.apache.geode.internal.cache.control.ResourceListener;
import org.apache.geode.internal.cache.control.TestMemoryThresholdListener;
import org.apache.geode.internal.cache.partitioned.RegionAdvisor;
import org.apache.geode.test.dunit.Assert;
import org.apache.geode.test.dunit.AsyncInvocation;
import org.apache.geode.test.dunit.DistributedTestUtils;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.IgnoredException;
import org.apache.geode.test.dunit.Invoke;
import org.apache.geode.test.dunit.LogWriterUtils;
import org.apache.geode.test.dunit.NetworkUtils;
import org.apache.geode.test.dunit.SerializableCallable;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.Wait;
import org.apache.geode.test.dunit.WaitCriterion;
import org.apache.geode.test.dunit.internal.JUnit4DistributedTestCase;
import org.apache.geode.test.junit.categories.OffHeapTest;

/**
 * Tests the Off-Heap Memory thresholds of {@link ResourceManager}
 *
 * @since Geode 1.0
 */
@Category({OffHeapTest.class})
public class MemoryThresholdsOffHeapDUnitTest extends ClientServerTestCase {

  final String expectedEx = "Member: .*? above .*? critical threshold";
  final String addExpectedExString =
      "<ExpectedException action=add>" + expectedEx + "</ExpectedException>";
  final String removeExpectedExString =
      "<ExpectedException action=remove>" + expectedEx + "</ExpectedException>";
  final String expectedBelow = "Member: .*? below .*? critical threshold";
  final String addExpectedBelow =
      "<ExpectedException action=add>" + expectedBelow + "</ExpectedException>";
  final String removeExpectedBelow =
      "<ExpectedException action=remove>" + expectedBelow + "</ExpectedException>";

  @Override
  public final void postSetUpClientServerTestCase() throws Exception {
    IgnoredException.addIgnoredException(expectedEx);
    IgnoredException.addIgnoredException(expectedBelow);
  }

  @Override
  protected void preTearDownClientServerTestCase() throws Exception {
    Invoke.invokeInEveryVM(resetResourceManager);
  }

  private final SerializableCallable resetResourceManager = new SerializableCallable() {
    @Override
    public Object call() throws Exception {
      InternalResourceManager irm = getCache().getInternalResourceManager();
      Set<ResourceListener<?>> listeners = irm.getResourceListeners(ResourceType.OFFHEAP_MEMORY);
      for (final ResourceListener<?> l : listeners) {
        if (l instanceof TestMemoryThresholdListener) {
          ((TestMemoryThresholdListener) l).resetThresholdCalls();
        }
      }
      return null;
    }
  };

  /**
   * Make sure appropriate events are delivered when moving between states.
   */
  @Test
  public void testEventDelivery() throws Exception {
    final Host host = Host.getHost(0);
    final VM server1 = host.getVM(0);
    final VM server2 = host.getVM(1);

    final String regionName = "offHeapEventDelivery";

    startCacheServer(server1, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);
    startCacheServer(server2, 70f, 90f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    registerTestMemoryThresholdListener(server1);
    registerTestMemoryThresholdListener(server2);

    // NORMAL -> EVICTION
    setUsageAboveEvictionThreshold(server2, regionName);
    verifyListenerValue(server1, MemoryState.EVICTION, 1, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 1, true);

    // EVICTION -> CRITICAL
    setUsageAboveCriticalThreshold(server2, regionName);
    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server2, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server1, MemoryState.EVICTION, 2, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 2, true);

    // CRITICAL -> CRITICAL
    server2.invoke(new SerializableCallable() {
      private static final long serialVersionUID = 1L;

      @Override
      public Object call() throws Exception {
        getCache().getLogger().fine(addExpectedExString);
        getRootRegion().getSubregion(regionName).destroy("oh3");
        getCache().getLogger().fine(removeExpectedExString);
        return null;
      }
    });
    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server2, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server1, MemoryState.EVICTION, 2, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 2, true);

    // CRITICAL -> EVICTION
    server2.invoke(new SerializableCallable() {
      private static final long serialVersionUID = 1L;

      @Override
      public Object call() throws Exception {
        getCache().getLogger().fine(addExpectedBelow);
        getRootRegion().getSubregion(regionName).destroy("oh2");
        getCache().getLogger().fine(removeExpectedBelow);
        return null;
      }
    });
    verifyListenerValue(server1, MemoryState.EVICTION, 3, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 3, true);

    // EVICTION -> EVICTION
    server2.invoke(new SerializableCallable() {
      private static final long serialVersionUID = 1L;

      @Override
      public Object call() throws Exception {
        getRootRegion().getSubregion(regionName).put("oh6", new byte[20480]);
        return null;
      }
    });
    verifyListenerValue(server1, MemoryState.EVICTION, 3, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 3, true);

    // EVICTION -> NORMAL
    server2.invoke(new SerializableCallable() {
      private static final long serialVersionUID = 1L;

      @Override
      public Object call() throws Exception {
        getRootRegion().getSubregion(regionName).destroy("oh4");
        return null;
      }
    });

    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server1, MemoryState.EVICTION, 3, true);
    verifyListenerValue(server1, MemoryState.NORMAL, 1, true);

    verifyListenerValue(server2, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 3, true);
    verifyListenerValue(server2, MemoryState.NORMAL, 1, true);
  }

  /**
   * test that disabling threshold does not cause remote event and remote DISABLED events are
   * delivered
   */
  @Test
  public void testDisabledThresholds() throws Exception {
    final Host host = Host.getHost(0);
    final VM server1 = host.getVM(0);
    final VM server2 = host.getVM(1);

    final String regionName = "offHeapDisabledThresholds";

    startCacheServer(server1, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);
    startCacheServer(server2, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    registerTestMemoryThresholdListener(server1);
    registerTestMemoryThresholdListener(server2);

    setUsageAboveEvictionThreshold(server1, regionName);
    verifyListenerValue(server1, MemoryState.EVICTION, 0, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 0, true);

    setThresholds(server1, 70f, 0f);
    verifyListenerValue(server1, MemoryState.EVICTION, 1, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 1, true);

    setUsageAboveCriticalThreshold(server1, regionName);
    verifyListenerValue(server1, MemoryState.CRITICAL, 0, true);
    verifyListenerValue(server2, MemoryState.CRITICAL, 0, true);

    setThresholds(server1, 0f, 0f);
    verifyListenerValue(server1, MemoryState.EVICTION_DISABLED, 1, true);
    verifyListenerValue(server2, MemoryState.EVICTION_DISABLED, 1, true);

    setThresholds(server1, 0f, 90f);
    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server2, MemoryState.CRITICAL, 1, true);

    // verify that stats on server2 are not changed by events on server1
    server2.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        InternalResourceManager irm = getCache().getInternalResourceManager();
        assertEquals(0, irm.getStats().getOffHeapEvictionStartEvents());
        assertEquals(0, irm.getStats().getOffHeapCriticalEvents());
        assertEquals(0, irm.getStats().getOffHeapCriticalThreshold());
        assertEquals(0, irm.getStats().getOffHeapEvictionThreshold());
        return null;
      }
    });
  }

  private void setUsageAboveCriticalThreshold(final VM vm, final String regionName) {
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getCache().getLogger().fine(addExpectedExString);
        Region region = getRootRegion().getSubregion(regionName);
        if (!region.containsKey("oh1")) {
          region.put("oh5", new byte[954204]);
        } else {
          region.put("oh5", new byte[122880]);
        }
        getCache().getLogger().fine(removeExpectedExString);
        return null;
      }
    });
  }

  private void setUsageAboveEvictionThreshold(final VM vm, final String regionName) {
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getCache().getLogger().fine(addExpectedBelow);
        Region region = getRootRegion().getSubregion(regionName);
        region.put("oh1", new byte[245760]);
        region.put("oh2", new byte[184320]);
        region.put("oh3", new byte[33488]);
        region.put("oh4", new byte[378160]);
        getCache().getLogger().fine(removeExpectedBelow);
        return null;
      }
    });
  }

  private void setUsageBelowEviction(final VM vm, final String regionName) {
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getCache().getLogger().fine(addExpectedBelow);
        Region region = getRootRegion().getSubregion(regionName);
        region.remove("oh1");
        region.remove("oh2");
        region.remove("oh3");
        region.remove("oh4");
        region.remove("oh5");
        getCache().getLogger().fine(removeExpectedBelow);
        return null;
      }
    });
  }

  private void setThresholds(VM server, final float evictionThreshold,
      final float criticalThreshold) {

    server.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        ResourceManager irm = getCache().getResourceManager();
        irm.setCriticalOffHeapPercentage(criticalThreshold);
        irm.setEvictionOffHeapPercentage(evictionThreshold);
        return null;
      }
    });
  }

  /**
   * test that puts in a client are rejected when a remote VM crosses critical threshold
   */
  @Test
  public void testDistributedRegionRemoteClientPutRejection() throws Exception {
    final Host host = Host.getHost(0);
    final VM server1 = host.getVM(0);
    final VM server2 = host.getVM(1);
    final VM client = host.getVM(2);

    final String regionName = "offHeapDRRemoteClientPutReject";

    final int port1 = startCacheServer(server1, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);
    startCacheServer(server2, 0f, 90f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    startClient(client, server1, port1, regionName);

    registerTestMemoryThresholdListener(server1);
    registerTestMemoryThresholdListener(server2);

    doPuts(client, regionName, false/* catchRejectedException */,
        false/* catchLowMemoryException */);
    doPutAlls(client, regionName, false/* catchRejectedException */,
        false/* catchLowMemoryException */, Range.DEFAULT);

    // make server2 critical
    setUsageAboveCriticalThreshold(server2, regionName);

    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server2, MemoryState.CRITICAL, 1, true);

    // make sure that client puts are rejected
    doPuts(client, regionName, true/* catchRejectedException */,
        false/* catchLowMemoryException */);
    doPutAlls(client, regionName, true/* catchRejectedException */,
        false/* catchLowMemoryException */, new Range(Range.DEFAULT, Range.DEFAULT.width() + 1));

    setUsageBelowEviction(server2, regionName);
  }

  @Test
  public void testDistributedRegionRemotePutRejectionLocalDestroy() throws Exception {
    doDistributedRegionRemotePutRejection(true, false);
  }

  @Test
  public void testDistributedRegionRemotePutRejectionCacheClose() throws Exception {
    doDistributedRegionRemotePutRejection(false, true);
  }

  @Test
  public void testDistributedRegionRemotePutRejectionBelowThreshold() throws Exception {
    doDistributedRegionRemotePutRejection(false, false);
  }

  @Test
  public void testGettersAndSetters() {
    getSystem(getOffHeapProperties());
    ResourceManager rm = getCache().getResourceManager();
    assertEquals(0.0f, rm.getCriticalOffHeapPercentage(), 0);
    assertEquals(0.0f, rm.getEvictionOffHeapPercentage(), 0);

    rm.setEvictionOffHeapPercentage(50);
    rm.setCriticalOffHeapPercentage(90);

    // verify
    assertEquals(50.0f, rm.getEvictionOffHeapPercentage(), 0);
    assertEquals(90.0f, rm.getCriticalOffHeapPercentage(), 0);

    getCache().createRegionFactory(RegionShortcut.REPLICATE_HEAP_LRU).create(getName());

    assertEquals(50.0f, rm.getEvictionOffHeapPercentage(), 0);
    assertEquals(90.0f, rm.getCriticalOffHeapPercentage(), 0);
  }

  /**
   * test that puts in a server are rejected when a remote VM crosses critical threshold
   *
   */
  private void doDistributedRegionRemotePutRejection(boolean localDestroy, boolean cacheClose)
      throws Exception {
    final Host host = Host.getHost(0);
    final VM server1 = host.getVM(0);
    final VM server2 = host.getVM(1);

    final String regionName = "offHeapDRRemotePutRejection";

    // set port to 0 in-order for system to pickup a random port.
    startCacheServer(server1, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);
    startCacheServer(server2, 0f, 90f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    registerTestMemoryThresholdListener(server1);
    registerTestMemoryThresholdListener(server2);

    doPuts(server1, regionName, false/* catchRejectedException */,
        false/* catchLowMemoryException */);
    doPutAlls(server1, regionName, false/* catchRejectedException */,
        false/* catchLowMemoryException */, Range.DEFAULT);

    // make server2 critical
    setUsageAboveCriticalThreshold(server2, regionName);

    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server2, MemoryState.CRITICAL, 1, true);

    // make sure that local server1 puts are rejected
    doPuts(server1, regionName, false/* catchRejectedException */,
        true/* catchLowMemoryException */);
    Range r1 = new Range(Range.DEFAULT, Range.DEFAULT.width() + 1);
    doPutAlls(server1, regionName, false/* catchRejectedException */,
        true/* catchLowMemoryException */, r1);

    if (localDestroy) {
      // local destroy the region on sick member
      server2.invoke(new SerializableCallable("local destroy") {
        @Override
        public Object call() throws Exception {
          Region r = getRootRegion().getSubregion(regionName);
          r.localDestroyRegion();
          return null;
        }
      });
    } else if (cacheClose) {
      server2.invoke(new SerializableCallable() {
        @Override
        public Object call() throws Exception {
          getCache().close();
          return null;
        }
      });
    } else {
      setUsageBelowEviction(server2, regionName);
    }

    // wait for remote region destroyed message to be processed
    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        WaitCriterion wc = new WaitCriterion() {
          @Override
          public String description() {
            return "remote localRegionDestroyed message not received";
          }

          @Override
          public boolean done() {
            DistributedRegion dr = (DistributedRegion) getRootRegion().getSubregion(regionName);
            return dr.getAtomicThresholdInfo().getMembersThatReachedThreshold().size() == 0;
          }
        };
        Wait.waitForCriterion(wc, 10000, 10, true);
        return null;
      }
    });

    // make sure puts succeed
    doPuts(server1, regionName, false/* catchRejectedException */,
        false/* catchLowMemoryException */);
    Range r2 = new Range(r1, r1.width() + 1);
    doPutAlls(server1, regionName, false/* catchRejectedException */,
        false/* catchLowMemoryException */, r2);
  }

  /**
   * Test that DistributedRegion cacheLoade and netLoad are passed through to the calling thread if
   * the local VM is in a critical state. Once the VM has moved to a safe state then test that they
   * are allowed.
   */
  @Test
  public void testDRLoadRejection() throws Exception {
    final Host host = Host.getHost(0);
    final VM replicate1 = host.getVM(1);
    final VM replicate2 = host.getVM(2);
    final String rName = getUniqueName();

    // Make sure the desired VMs will have a fresh DS.
    AsyncInvocation d1 = replicate1.invokeAsync(JUnit4DistributedTestCase::disconnectFromDS);
    AsyncInvocation d2 = replicate2.invokeAsync(JUnit4DistributedTestCase::disconnectFromDS);
    d1.join();
    assertFalse(d1.exceptionOccurred());
    d2.join();
    assertFalse(d2.exceptionOccurred());
    CacheSerializableRunnable establishConnectivity =
        new CacheSerializableRunnable("establishcConnectivity") {
          @SuppressWarnings("synthetic-access")
          @Override
          public void run2() throws CacheException {
            getSystem(getOffHeapProperties());
          }
        };
    replicate1.invoke(establishConnectivity);
    replicate2.invoke(establishConnectivity);

    CacheSerializableRunnable createRegion =
        new CacheSerializableRunnable("create DistributedRegion") {
          @Override
          public void run2() throws CacheException {
            // Assert some level of connectivity
            InternalDistributedSystem ds = getSystem(getOffHeapProperties());
            assertTrue(ds.getDistributionManager().getNormalDistributionManagerIds().size() >= 1);

            InternalResourceManager irm = (InternalResourceManager) getCache().getResourceManager();
            irm.setCriticalOffHeapPercentage(90f);
            AttributesFactory af = new AttributesFactory();
            af.setScope(Scope.DISTRIBUTED_ACK);
            af.setDataPolicy(DataPolicy.REPLICATE);
            af.setOffHeap(true);
            Region region = getCache().createRegion(rName, af.create());
          }
        };
    replicate1.invoke(createRegion);
    replicate2.invoke(createRegion);

    replicate1.invoke(addExpectedException);
    replicate2.invoke(addExpectedException);

    final Integer expected =
        (Integer) replicate1.invoke(new SerializableCallable("test Local DistributedRegion Load") {
          @Override
          public Object call() throws Exception {
            final DistributedRegion r = (DistributedRegion) getCache().getRegion(rName);
            AttributesMutator<Integer, String> am = r.getAttributesMutator();
            am.setCacheLoader(new CacheLoader<Integer, String>() {
              final AtomicInteger numLoaderInvocations = new AtomicInteger(0);

              @Override
              public String load(LoaderHelper<Integer, String> helper) throws CacheLoaderException {
                Integer expectedInvocations = (Integer) helper.getArgument();
                final int actualInvocations = numLoaderInvocations.getAndIncrement();
                if (expectedInvocations != actualInvocations) {
                  throw new CacheLoaderException("Expected " + expectedInvocations
                      + " invocations, actual is " + actualInvocations);
                }
                return helper.getKey().toString();
              }

              @Override
              public void close() {}
            });

            int expectedInvocations = 0;
            final OffHeapMemoryMonitor ohmm =
                ((InternalResourceManager) getCache().getResourceManager()).getOffHeapMonitor();
            assertFalse(ohmm.getState().isCritical());
            {
              Integer k = 1;
              assertEquals(k.toString(), r.get(k, expectedInvocations++));
            }

            r.put("oh1", new byte[838860]);
            r.put("oh3", new byte[157287]);

            WaitCriterion wc = new WaitCriterion() {
              @Override
              public String description() {
                return "expected region " + r + " to set memoryThreshold";
              }

              @Override
              public boolean done() {
                return r.isMemoryThresholdReached();
              }
            };
            Wait.waitForCriterion(wc, 30 * 1000, 10, true);
            {
              Integer k = 2;
              assertEquals(k.toString(), r.get(k, expectedInvocations++));
            }

            r.destroy("oh3");
            wc = new WaitCriterion() {
              @Override
              public String description() {
                return "expected region " + r + " to unset memoryThreshold";
              }

              @Override
              public boolean done() {
                return !r.isMemoryThresholdReached();
              }
            };
            Wait.waitForCriterion(wc, 30 * 1000, 10, true);
            {
              Integer k = 3;
              assertEquals(k.toString(), r.get(k, expectedInvocations++));
            }
            return expectedInvocations;
          }
        });

    final CacheSerializableRunnable validateData1 =
        new CacheSerializableRunnable("Validate data 1") {
          @Override
          public void run2() throws CacheException {
            Region r = getCache().getRegion(rName);
            Integer i1 = 1;
            assertTrue(r.containsKey(i1));
            assertNotNull(r.getEntry(i1));
            Integer i2 = 2;
            assertFalse(r.containsKey(i2));
            assertNull(r.getEntry(i2));
            Integer i3 = 3;
            assertTrue(r.containsKey(i3));
            assertNotNull(r.getEntry(i3));
          }
        };
    replicate1.invoke(validateData1);
    replicate2.invoke(validateData1);

    replicate2.invoke(new SerializableCallable("test DistributedRegion netLoad") {
      @Override
      public Object call() throws Exception {
        final DistributedRegion r = (DistributedRegion) getCache().getRegion(rName);
        final OffHeapMemoryMonitor ohmm =
            ((InternalResourceManager) getCache().getResourceManager()).getOffHeapMonitor();
        assertFalse(ohmm.getState().isCritical());

        int expectedInvocations = expected;
        {
          Integer k = 4;
          assertEquals(k.toString(), r.get(k, expectedInvocations++));
        }

        // Place in a critical state for the next test
        r.put("oh3", new byte[157287]);
        WaitCriterion wc = new WaitCriterion() {
          @Override
          public String description() {
            return "expected region " + r + " to set memoryThreshold";
          }

          @Override
          public boolean done() {
            return r.isMemoryThresholdReached();
          }
        };
        Wait.waitForCriterion(wc, 30 * 1000, 10, true);
        {
          Integer k = 5;
          assertEquals(k.toString(), r.get(k, expectedInvocations++));
        }

        r.destroy("oh3");
        wc = new WaitCriterion() {
          @Override
          public String description() {
            return "expected region " + r + " to unset memoryThreshold";
          }

          @Override
          public boolean done() {
            return !r.isMemoryThresholdReached();
          }
        };
        Wait.waitForCriterion(wc, 30 * 1000, 10, true);
        {
          Integer k = 6;
          assertEquals(k.toString(), r.get(k, expectedInvocations++));
        }
        return expectedInvocations;
      }
    });

    replicate1.invoke(removeExpectedException);
    replicate2.invoke(removeExpectedException);

    final CacheSerializableRunnable validateData2 =
        new CacheSerializableRunnable("Validate data 2") {
          @Override
          public void run2() throws CacheException {
            Region<Integer, String> r = getCache().getRegion(rName);
            Integer i4 = 4;
            assertTrue(r.containsKey(i4));
            assertNotNull(r.getEntry(i4));
            Integer i5 = 5;
            assertFalse(r.containsKey(i5));
            assertNull(r.getEntry(i5));
            Integer i6 = 6;
            assertTrue(r.containsKey(i6));
            assertNotNull(r.getEntry(i6));
          }
        };
    replicate1.invoke(validateData2);
    replicate2.invoke(validateData2);
  }


  private final SerializableRunnable addExpectedException =
      new SerializableRunnable("addExpectedEx") {
        @Override
        public void run() {
          getCache().getLogger().fine(addExpectedExString);
          getCache().getLogger().fine(addExpectedBelow);
        }
      };

  private final SerializableRunnable removeExpectedException =
      new SerializableRunnable("removeExpectedException") {
        @Override
        public void run() {
          getCache().getLogger().fine(removeExpectedExString);
          getCache().getLogger().fine(removeExpectedBelow);
        }
      };

  @Test
  public void testPR_RemotePutRejectionLocalDestroy() throws Exception {
    prRemotePutRejection(false, true, false);
  }

  @Test
  public void testPR_RemotePutRejectionCacheClose() throws Exception {
    prRemotePutRejection(true, false, false);
  }

  @Test
  public void testPR_RemotePutRejection() throws Exception {
    prRemotePutRejection(false, false, false);
  }

  @Test
  public void testPR_RemotePutRejectionLocalDestroyWithTx() throws Exception {
    prRemotePutRejection(false, true, true);
  }

  @Test
  public void testPR_RemotePutRejectionCacheCloseWithTx() throws Exception {
    prRemotePutRejection(true, false, true);
  }

  @Test
  public void testPR_RemotePutRejectionWithTx() throws Exception {
    prRemotePutRejection(false, false, true);
  }

  private void prRemotePutRejection(boolean cacheClose, boolean localDestroy, final boolean useTx)
      throws Exception {
    final Host host = Host.getHost(0);
    final VM accessor = host.getVM(0);
    final VM[] servers = new VM[3];
    servers[0] = host.getVM(1);
    servers[1] = host.getVM(2);
    servers[2] = host.getVM(3);

    final String regionName = "offHeapPRRemotePutRejection";
    final int redundancy = 1;

    startCacheServer(servers[0], 0f, 90f, regionName, true/* createPR */,
        false/* notifyBySubscription */, redundancy);
    startCacheServer(servers[1], 0f, 90f, regionName, true/* createPR */,
        false/* notifyBySubscription */, redundancy);
    startCacheServer(servers[2], 0f, 90f, regionName, true/* createPR */,
        false/* notifyBySubscription */, redundancy);
    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getSystem(getOffHeapProperties());
        getCache();
        AttributesFactory factory = new AttributesFactory();
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setRedundantCopies(redundancy);
        paf.setLocalMaxMemory(0);
        paf.setTotalNumBuckets(11);
        factory.setPartitionAttributes(paf.create());
        factory.setOffHeap(true);
        createRegion(regionName, factory.create());
        return null;
      }
    });

    doPuts(accessor, regionName, false, false);
    final Range r1 = Range.DEFAULT;
    doPutAlls(accessor, regionName, false, false, r1);

    servers[0].invoke(addExpectedException);
    servers[1].invoke(addExpectedException);
    servers[2].invoke(addExpectedException);
    setUsageAboveCriticalThreshold(servers[0], regionName);

    final Set<InternalDistributedMember> criticalMembers =
        (Set) servers[0].invoke(new SerializableCallable() {
          @Override
          public Object call() throws Exception {
            final PartitionedRegion pr =
                (PartitionedRegion) getRootRegion().getSubregion(regionName);
            final int hashKey = PartitionedRegionHelper.getHashKey(pr, null, "oh5", null, null);
            return pr.getRegionAdvisor().getBucketOwners(hashKey);
          }
        });

    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        final PartitionedRegion pr = (PartitionedRegion) getRootRegion().getSubregion(regionName);
        WaitCriterion wc = new WaitCriterion() {
          @Override
          public String description() {
            return "remote bucket not marked sick";
          }

          @Override
          public boolean done() {
            boolean keyFoundOnSickMember = false;
            boolean caughtException = false;
            for (int i = 0; i < 20; i++) {
              Integer key = i;
              int hKey = PartitionedRegionHelper.getHashKey(pr, null, key, null, null);
              Set<InternalDistributedMember> owners = pr.getRegionAdvisor().getBucketOwners(hKey);
              final boolean hasCriticalOwners = owners.removeAll(criticalMembers);
              if (hasCriticalOwners) {
                keyFoundOnSickMember = true;
                try {
                  if (useTx) {
                    getCache().getCacheTransactionManager().begin();
                  }
                  pr.getCache().getLogger().fine("SWAP:putting in tx:" + useTx);
                  pr.put(key, "value");
                  if (useTx) {
                    getCache().getCacheTransactionManager().commit();
                  }
                } catch (LowMemoryException ex) {
                  caughtException = true;
                  if (useTx) {
                    getCache().getCacheTransactionManager().rollback();
                  }
                }
              } else {
                // puts on healthy member should continue
                pr.put(key, "value");
              }
            }
            return keyFoundOnSickMember && caughtException;
          }
        };
        Wait.waitForCriterion(wc, 10000, 10, true);
        return null;
      }
    });

    {
      Range r2 = new Range(r1, r1.width() + 1);
      doPutAlls(accessor, regionName, false, true, r2);
    }

    // Find all VMs that have a critical region
    SerializableCallable getMyId = new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        return getCache().getMyId();
      }
    };
    final Set<VM> criticalServers = new HashSet<>();
    for (final VM server : servers) {
      DistributedMember member = (DistributedMember) server.invoke(getMyId);
      if (criticalMembers.contains(member)) {
        criticalServers.add(server);
      }
    }

    if (localDestroy) {
      // local destroy the region on sick members
      for (final VM vm : criticalServers) {
        vm.invoke(new SerializableCallable("local destroy sick member") {
          @Override
          public Object call() throws Exception {
            Region r = getRootRegion().getSubregion(regionName);
            LogWriterUtils.getLogWriter().info("PRLocalDestroy");
            r.localDestroyRegion();
            return null;
          }
        });
      }
    } else if (cacheClose) {
      // close cache on sick members
      for (final VM vm : criticalServers) {
        vm.invoke(new SerializableCallable("close cache sick member") {
          @Override
          public Object call() throws Exception {
            getCache().close();
            return null;
          }
        });
      }
    } else {
      setUsageBelowEviction(servers[0], regionName);
      servers[0].invoke(removeExpectedException);
      servers[1].invoke(removeExpectedException);
      servers[2].invoke(removeExpectedException);
    }

    // do put all in a loop to allow distribution of message
    accessor.invoke(new SerializableCallable("Put in a loop") {
      @Override
      public Object call() throws Exception {
        final Region r = getRootRegion().getSubregion(regionName);
        WaitCriterion wc = new WaitCriterion() {
          @Override
          public String description() {
            return "pr should have gone un-critical";
          }

          @Override
          public boolean done() {
            boolean done = true;
            for (int i = 0; i < 20; i++) {
              try {
                r.put(i, "value");
              } catch (LowMemoryException e) {
                // expected
                done = false;
              }
            }
            return done;
          }
        };
        Wait.waitForCriterion(wc, 10000, 10, true);
        return null;
      }
    });
    doPutAlls(accessor, regionName, false, false, r1);
  }

  /**
   * Test that a Partitioned Region loader invocation is rejected if the VM with the bucket is in a
   * critical state.
   */
  @Test
  public void testPRLoadRejection() throws Exception {
    final Host host = Host.getHost(0);
    final VM accessor = host.getVM(1);
    final VM ds1 = host.getVM(2);
    final String rName = getUniqueName();

    // Make sure the desired VMs will have a fresh DS. TODO: convert these from AsyncInvocation to
    // invoke
    accessor.invoke(JUnit4DistributedTestCase::disconnectFromDS);
    ds1.invoke(JUnit4DistributedTestCase::disconnectFromDS);

    ds1.invoke("establishcConnectivity", () -> {
      getSystem();
      createPR(rName, false);
    });

    accessor.invoke("establishcConnectivity", () -> {
      getSystem();
      createPR(rName, true);
    });

    final AtomicInteger expectedInvocations = new AtomicInteger(0);

    Integer ex = (Integer) accessor
        .invoke(new SerializableCallable("Invoke loader from accessor, non-critical") {
          @Override
          public Object call() throws Exception {
            Region<Integer, String> r = getCache().getRegion(rName);
            Integer k = 1;
            Integer expectedInvocations0 = expectedInvocations.getAndIncrement();
            assertEquals(k.toString(), r.get(k, expectedInvocations0)); // should load for new key
            assertTrue(r.containsKey(k));
            Integer expectedInvocations1 = expectedInvocations.get();
            assertEquals(k.toString(), r.get(k, expectedInvocations1)); // no load
            assertEquals(k.toString(), r.get(k, expectedInvocations1)); // no load
            return expectedInvocations1;
          }
        });
    expectedInvocations.set(ex);

    ex = (Integer) ds1
        .invoke(new SerializableCallable("Invoke loader from datastore, non-critical") {
          @Override
          public Object call() throws Exception {
            Region<Integer, String> r = getCache().getRegion(rName);
            Integer k = 2;
            Integer expectedInvocations1 = expectedInvocations.getAndIncrement();
            assertEquals(k.toString(), r.get(k, expectedInvocations1)); // should load for new key
            assertTrue(r.containsKey(k));
            Integer expectedInvocations2 = expectedInvocations.get();
            assertEquals(k.toString(), r.get(k, expectedInvocations2)); // no load
            assertEquals(k.toString(), r.get(k, expectedInvocations2)); // no load
            String oldVal = r.remove(k);
            assertFalse(r.containsKey(k));
            assertEquals(k.toString(), oldVal);
            return expectedInvocations2;
          }
        });
    expectedInvocations.set(ex);

    accessor.invoke(addExpectedException);
    ds1.invoke(addExpectedException);

    ex = (Integer) ds1
        .invoke(new SerializableCallable("Set critical state, assert local load behavior") {
          @Override
          public Object call() throws Exception {
            final OffHeapMemoryMonitor ohmm =
                ((InternalResourceManager) getCache().getResourceManager()).getOffHeapMonitor();
            final PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(rName);
            final RegionAdvisor advisor = pr.getRegionAdvisor();

            pr.put("oh1", new byte[838860]);
            pr.put("oh3", new byte[157287]);

            WaitCriterion wc = new WaitCriterion() {
              @Override
              public String description() {
                return "verify critical state";
              }

              @Override
              public boolean done() {
                for (final ProxyBucketRegion bucket : advisor.getProxyBucketArray()) {
                  if (bucket.isBucketSick()) {
                    return true;
                  }
                }
                return false;
              }
            };
            Wait.waitForCriterion(wc, 30 * 1000, 10, true);

            final Integer k = 2; // reload with same key again and again
            final Integer expectedInvocations3 = expectedInvocations.getAndIncrement();
            assertEquals(k.toString(), pr.get(k, expectedInvocations3)); // load
            assertFalse(pr.containsKey(k));
            Integer expectedInvocations4 = expectedInvocations.getAndIncrement();
            assertEquals(k.toString(), pr.get(k, expectedInvocations4)); // load
            assertFalse(pr.containsKey(k));
            Integer expectedInvocations5 = expectedInvocations.get();
            assertEquals(k.toString(), pr.get(k, expectedInvocations5)); // load
            assertFalse(pr.containsKey(k));
            return expectedInvocations5;
          }
        });
    expectedInvocations.set(ex);

    ex = (Integer) accessor.invoke(new SerializableCallable(
        "During critical state on datastore, assert accesor load behavior") {
      @Override
      public Object call() throws Exception {
        final Integer k = 2; // reload with same key again and again
        Integer expectedInvocations6 = expectedInvocations.incrementAndGet();
        Region<Integer, String> r = getCache().getRegion(rName);
        assertEquals(k.toString(), r.get(k, expectedInvocations6)); // load
        assertFalse(r.containsKey(k));
        Integer expectedInvocations7 = expectedInvocations.incrementAndGet();
        assertEquals(k.toString(), r.get(k, expectedInvocations7)); // load
        assertFalse(r.containsKey(k));
        return expectedInvocations7;
      }
    });
    expectedInvocations.set(ex);

    ex = (Integer) ds1.invoke(
        new SerializableCallable("Set safe state on datastore, assert local load behavior") {
          @Override
          public Object call() throws Exception {
            final PartitionedRegion r = (PartitionedRegion) getCache().getRegion(rName);
            final OffHeapMemoryMonitor ohmm =
                ((InternalResourceManager) getCache().getResourceManager()).getOffHeapMonitor();

            r.destroy("oh3");
            WaitCriterion wc = new WaitCriterion() {
              @Override
              public String description() {
                return "verify critical state";
              }

              @Override
              public boolean done() {
                return !ohmm.getState().isCritical();
              }
            };
            Wait.waitForCriterion(wc, 30 * 1000, 10, true);

            Integer k = 3; // same key as previously used, this time is should stick
            Integer expectedInvocations8 = expectedInvocations.incrementAndGet();
            assertEquals(k.toString(), r.get(k, expectedInvocations8)); // last load for 3
            assertTrue(r.containsKey(k));
            return expectedInvocations8;
          }
        });
    expectedInvocations.set(ex);

    accessor.invoke(new SerializableCallable(
        "Data store in safe state, assert load behavior, accessor sets critical state, assert load behavior") {
      @Override
      public Object call() throws Exception {
        final OffHeapMemoryMonitor ohmm =
            ((InternalResourceManager) getCache().getResourceManager()).getOffHeapMonitor();
        assertFalse(ohmm.getState().isCritical());
        Integer k = 4;
        Integer expectedInvocations9 = expectedInvocations.incrementAndGet();
        final PartitionedRegion r = (PartitionedRegion) getCache().getRegion(rName);
        assertEquals(k.toString(), r.get(k, expectedInvocations9)); // load for 4
        assertTrue(r.containsKey(k));
        assertEquals(k.toString(), r.get(k, expectedInvocations9)); // no load

        // Go critical in accessor by creating entries in local node
        String localRegionName = "localRegionName";
        AttributesFactory<Integer, String> af = getLocalRegionAttributesFactory();
        final LocalRegion localRegion =
            (LocalRegion) getCache().createRegion(localRegionName, af.create());
        localRegion.put("oh1", new byte[838860]);
        localRegion.put("oh3", new byte[157287]);

        WaitCriterion wc = new WaitCriterion() {
          @Override
          public String description() {
            return "verify critical state";
          }

          @Override
          public boolean done() {
            return ohmm.getState().isCritical();
          }
        };
        Wait.waitForCriterion(wc, 30 * 1000, 10, true);

        k = 5;
        Integer expectedInvocations10 = expectedInvocations.incrementAndGet();
        assertEquals(k.toString(), r.get(k, expectedInvocations10)); // load for key 5
        assertTrue(r.containsKey(k));
        assertEquals(k.toString(), r.get(k, expectedInvocations10)); // no load

        // Clean up critical state
        localRegion.destroy("oh3");
        wc = new WaitCriterion() {
          @Override
          public String description() {
            return "verify critical state";
          }

          @Override
          public boolean done() {
            return !ohmm.getState().isCritical();
          }
        };
        Wait.waitForCriterion(wc, 30 * 1000, 10, true);

        return expectedInvocations10;
      }
    });

    accessor.invoke(removeExpectedException);
    ds1.invoke(removeExpectedException);
  }

  private void createPR(final String rName, final boolean accessor) {
    getSystem(getOffHeapProperties());
    InternalResourceManager irm = (InternalResourceManager) getCache().getResourceManager();
    irm.setCriticalOffHeapPercentage(90f);
    AttributesFactory<Integer, String> af = new AttributesFactory<>();
    if (!accessor) {
      af.setCacheLoader(new CacheLoader<Integer, String>() {
        final AtomicInteger numLoaderInvocations = new AtomicInteger(0);

        @Override
        public String load(LoaderHelper<Integer, String> helper) throws CacheLoaderException {
          Integer expectedInvocations = (Integer) helper.getArgument();
          final int actualInvocations = numLoaderInvocations.getAndIncrement();
          if (expectedInvocations != actualInvocations) {
            throw new CacheLoaderException("Expected " + expectedInvocations
                + " invocations, actual is " + actualInvocations + " for key " + helper.getKey());
          }
          return helper.getKey().toString();
        }

        @Override
        public void close() {}
      });

      af.setPartitionAttributes(new PartitionAttributesFactory().create());
    } else {
      af.setPartitionAttributes(new PartitionAttributesFactory().setLocalMaxMemory(0).create());
    }
    af.setOffHeap(true);
    getCache().createRegion(rName, af.create());
  }

  /**
   * Test that LocalRegion cache Loads are not stored in the Region if the VM is in a critical
   * state, then test that they are allowed once the VM is no longer critical
   */
  @Test
  public void testLRLoadRejection() throws Exception {
    final Host host = Host.getHost(0);
    final VM vm = host.getVM(2);
    final String rName = getUniqueName();

    vm.invoke(JUnit4DistributedTestCase::disconnectFromDS);

    vm.invoke(new CacheSerializableRunnable("test LocalRegion load passthrough when critical") {
      @Override
      public void run2() throws CacheException {
        getSystem(getOffHeapProperties());
        InternalResourceManager irm = (InternalResourceManager) getCache().getResourceManager();
        final OffHeapMemoryMonitor ohmm = irm.getOffHeapMonitor();
        irm.setCriticalOffHeapPercentage(90f);
        AttributesFactory<Integer, String> af = getLocalRegionAttributesFactory();
        final AtomicInteger numLoaderInvocations = new AtomicInteger(0);
        af.setCacheLoader(new CacheLoader<Integer, String>() {
          @Override
          public String load(LoaderHelper<Integer, String> helper) throws CacheLoaderException {
            numLoaderInvocations.incrementAndGet();
            return helper.getKey().toString();
          }

          @Override
          public void close() {}
        });
        final LocalRegion r = (LocalRegion) getCache().createRegion(rName, af.create());

        assertFalse(ohmm.getState().isCritical());
        int expectedInvocations = 0;
        assertEquals(expectedInvocations++, numLoaderInvocations.get());
        {
          Integer k = 1;
          assertEquals(k.toString(), r.get(k));
        }
        assertEquals(expectedInvocations++, numLoaderInvocations.get());
        expectedInvocations++;
        expectedInvocations++;
        r.getAll(createRanges(10, 12));
        assertEquals(expectedInvocations++, numLoaderInvocations.get());

        getCache().getLogger().fine(addExpectedExString);
        r.put("oh1", new byte[838860]);
        r.put("oh3", new byte[157287]);
        getCache().getLogger().fine(removeExpectedExString);
        WaitCriterion wc = new WaitCriterion() {
          @Override
          public String description() {
            return "expected region " + r + " to set memoryThresholdReached";
          }

          @Override
          public boolean done() {
            return r.isMemoryThresholdReached();
          }
        };
        Wait.waitForCriterion(wc, 30 * 1000, 10, true);
        {
          Integer k = 2;
          assertEquals(k.toString(), r.get(k));
        }
        assertEquals(expectedInvocations++, numLoaderInvocations.get());
        expectedInvocations++;
        expectedInvocations++;
        r.getAll(createRanges(13, 15));
        assertEquals(expectedInvocations++, numLoaderInvocations.get());

        getCache().getLogger().fine(addExpectedBelow);
        r.destroy("oh3");
        getCache().getLogger().fine(removeExpectedBelow);
        wc = new WaitCriterion() {
          @Override
          public String description() {
            return "expected region " + r + " to unset memoryThresholdReached";
          }

          @Override
          public boolean done() {
            return !r.isMemoryThresholdReached();
          }
        };
        Wait.waitForCriterion(wc, 30 * 1000, 10, true);

        {
          Integer k = 3;
          assertEquals(k.toString(), r.get(k));
        }
        assertEquals(expectedInvocations++, numLoaderInvocations.get());
        expectedInvocations++;
        expectedInvocations++;
        r.getAll(createRanges(16, 18));
        assertEquals(expectedInvocations, numLoaderInvocations.get());

        // Do extra validation that the entry doesn't exist in the local region
        for (Integer i : createRanges(2, 2, 13, 15)) {
          if (r.containsKey(i)) {
            fail("Expected containsKey return false for key" + i);
          }
          if (r.getEntry(i) != null) {
            fail("Expected getEntry to return null for key" + i);
          }
        }
      }
    });
  }

  private AttributesFactory<Integer, String> getLocalRegionAttributesFactory() {
    AttributesFactory<Integer, String> af = new AttributesFactory<>();
    af.setScope(Scope.LOCAL);
    af.setOffHeap(true);
    return af;
  }

  /**
   * Create a list of integers consisting of the ranges defined by the provided argument e.g..
   * createRanges(1, 4, 10, 12) means create ranges 1 through 4 and 10 through 12 and should yield
   * the list: 1, 2, 3, 4, 10, 11, 12
   */
  public static List<Integer> createRanges(int... startEnds) {
    assert startEnds.length % 2 == 0;
    ArrayList<Integer> ret = new ArrayList<>();
    for (int si = 0; si < startEnds.length; si++) {
      final int start = startEnds[si++];
      final int end = startEnds[si];
      assert end >= start;
      ret.ensureCapacity(ret.size() + ((end - start) + 1));
      for (int i = start; i <= end; i++) {
        ret.add(i);
      }
    }
    return ret;
  }

  @Test
  public void testCleanAdvisorClose() throws Exception {
    final Host host = Host.getHost(0);
    final VM server1 = host.getVM(0);
    final VM server2 = host.getVM(1);
    final VM server3 = host.getVM(2);

    final String regionName = "testEventOrder";

    startCacheServer(server1, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);
    startCacheServer(server2, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    verifyProfiles(server1, 2);
    verifyProfiles(server2, 2);

    server2.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        closeCache();
        return null;
      }
    });

    verifyProfiles(server1, 1);

    startCacheServer(server3, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    verifyProfiles(server1, 2);
    verifyProfiles(server3, 2);
  }

  @Test
  public void testPRClientPutRejection() throws Exception {
    doClientServerTest("parRegReject", true/* createPR */);
  }

  @Test
  public void testDistributedRegionClientPutRejection() throws Exception {
    doClientServerTest("distrReject", false/* createPR */);
  }

  private void doPuts(VM vm, final String regionName, final boolean catchServerException,
      final boolean catchLowMemoryException) {

    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Region r = getRootRegion().getSubregion(regionName);
        try {
          r.put(0, "value-1");
          if (catchServerException || catchLowMemoryException) {
            fail("An expected ResourceException was not thrown");
          }
        } catch (ServerOperationException ex) {
          if (!catchServerException) {
            Assert.fail("Unexpected exception: ", ex);
          }
          if (!(ex.getCause() instanceof LowMemoryException)) {
            Assert.fail("Unexpected exception: ", ex);
          }
        } catch (LowMemoryException low) {
          if (!catchLowMemoryException) {
            Assert.fail("Unexpected exception: ", low);
          }
        }
        return null;
      }
    });
  }

  private void doPutAlls(VM vm, final String regionName, final boolean catchServerException,
      final boolean catchLowMemoryException, final Range rng) {

    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Region r = getRootRegion().getSubregion(regionName);
        Map<Integer, String> temp = new HashMap<>();
        for (int i = rng.start; i < rng.end; i++) {
          Integer k = i;
          temp.put(k, "value-" + i);
        }
        try {
          r.putAll(temp);
          if (catchServerException || catchLowMemoryException) {
            fail("An expected ResourceException was not thrown");
          }
          for (Map.Entry<Integer, String> me : temp.entrySet()) {
            assertEquals(me.getValue(), r.get(me.getKey()));
          }
        } catch (ServerOperationException ex) {
          if (!catchServerException) {
            Assert.fail("Unexpected exception: ", ex);
          }
          if (!(ex.getCause() instanceof LowMemoryException)) {
            Assert.fail("Unexpected exception: ", ex);
          }
          for (Integer me : temp.keySet()) {
            assertFalse("Key " + me + " should not exist", r.containsKey(me));
          }
        } catch (LowMemoryException low) {
          LogWriterUtils.getLogWriter().info("Caught LowMemoryException", low);
          if (!catchLowMemoryException) {
            Assert.fail("Unexpected exception: ", low);
          }
          for (Integer me : temp.keySet()) {
            assertFalse("Key " + me + " should not exist", r.containsKey(me));
          }
        }
        return null;
      }
    });
  }

  private void doClientServerTest(final String regionName, boolean createPR) throws Exception {
    // create region on the server
    final Host host = Host.getHost(0);
    final VM server = host.getVM(0);
    final VM client = host.getVM(1);
    final Object bigKey = -1;
    final Object smallKey = -2;

    final int port = startCacheServer(server, 0f, 90f, regionName, createPR, false, 0);
    startClient(client, server, port, regionName);
    doPuts(client, regionName, false/* catchServerException */, false/* catchLowMemoryException */);
    doPutAlls(client, regionName, false/* catchServerException */,
        false/* catchLowMemoryException */, Range.DEFAULT);


    // make the region sick in the server
    final long bytesUsedAfterSmallKey = (long) server.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        InternalResourceManager irm = getCache().getInternalResourceManager();
        final OffHeapMemoryMonitor ohm = irm.getOffHeapMonitor();
        assertTrue(ohm.getState().isNormal());
        getCache().getLogger().fine(addExpectedExString);
        final LocalRegion r = (LocalRegion) getRootRegion().getSubregion(regionName);
        final long bytesUsedAfterSmallKey;
        {
          OffHeapMemoryMonitorObserverImpl _testHook = new OffHeapMemoryMonitorObserverImpl();
          ohm.testHook = _testHook;
          try {
            r.put(smallKey, "1234567890");
            bytesUsedAfterSmallKey = _testHook.verifyBeginUpdateMemoryUsed(false);
          } finally {
            ohm.testHook = null;
          }
        }
        {
          final OffHeapMemoryMonitorObserverImpl th = new OffHeapMemoryMonitorObserverImpl();
          ohm.testHook = th;
          try {
            r.put(bigKey, new byte[943720]);
            th.verifyBeginUpdateMemoryUsed(bytesUsedAfterSmallKey + 943720 + 8, true);
            WaitCriterion waitForCritical = new WaitCriterion() {
              @Override
              public boolean done() {
                return th.checkUpdateStateAndSendEventBeforeProcess(
                    bytesUsedAfterSmallKey + 943720 + 8, MemoryState.EVICTION_DISABLED_CRITICAL);
              }

              @Override
              public String description() {
                return null;
              }
            };
            Wait.waitForCriterion(waitForCritical, 30 * 1000, 9, false);
            th.validateUpdateStateAndSendEventBeforeProcess(bytesUsedAfterSmallKey + 943720 + 8,
                MemoryState.EVICTION_DISABLED_CRITICAL);
          } finally {
            ohm.testHook = null;
          }
        }
        WaitCriterion wc;
        if (r instanceof PartitionedRegion) {
          final PartitionedRegion pr = (PartitionedRegion) r;
          final int bucketId = PartitionedRegionHelper.getHashKey(pr, null, bigKey, null, null);
          wc = new WaitCriterion() {
            @Override
            public String description() {
              return "Expected to go critical: isCritical=" + ohm.getState().isCritical();
            }

            @Override
            public boolean done() {
              if (!ohm.getState().isCritical()) {
                return false;
              }
              // Only done once the bucket has been marked sick
              try {
                pr.getRegionAdvisor().checkIfBucketSick(bucketId, bigKey);
                return false;
              } catch (LowMemoryException ignore) {
                return true;
              }
            }
          };
        } else {
          wc = new WaitCriterion() {
            @Override
            public String description() {
              return "Expected to go critical: isCritical=" + ohm.getState().isCritical()
                  + " memoryThresholdReached=" + r.isMemoryThresholdReached();
            }

            @Override
            public boolean done() {
              return ohm.getState().isCritical() && r.isMemoryThresholdReached();
            }
          };
        }
        Wait.waitForCriterion(wc, 30000, 9, true);
        getCache().getLogger().fine(removeExpectedExString);
        return bytesUsedAfterSmallKey;
      }
    });

    // make sure client puts are rejected
    doPuts(client, regionName, true/* catchServerException */, false/* catchLowMemoryException */);
    doPutAlls(client, regionName, true/* catchServerException */,
        false/* catchLowMemoryException */, new Range(Range.DEFAULT, Range.DEFAULT.width() + 1));

    // make the region healthy in the server
    server.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        InternalResourceManager irm = getCache().getInternalResourceManager();
        final OffHeapMemoryMonitor ohm = irm.getOffHeapMonitor();
        assertTrue(ohm.getState().isCritical());
        getCache().getLogger().fine(addExpectedBelow);
        OffHeapMemoryMonitorObserverImpl _testHook = new OffHeapMemoryMonitorObserverImpl();
        ohm.testHook = _testHook;
        try {
          getRootRegion().getSubregion(regionName).destroy(bigKey);
          _testHook.verifyBeginUpdateMemoryUsed(bytesUsedAfterSmallKey, true);
        } finally {
          ohm.testHook = null;
        }
        WaitCriterion wc = new WaitCriterion() {
          @Override
          public String description() {
            return "Expected to go normal";
          }

          @Override
          public boolean done() {
            return ohm.getState().isNormal();
          }
        };
        Wait.waitForCriterion(wc, 30000, 9, true);
        getCache().getLogger().fine(removeExpectedBelow);
        return;
      }
    });
  }

  private static class OffHeapMemoryMonitorObserverImpl implements OffHeapMemoryMonitorObserver {
    private boolean beginUpdateMemoryUsed;
    private long beginUpdateMemoryUsed_bytesUsed;
    private boolean beginUpdateMemoryUsed_willSendEvent;

    @Override
    public synchronized void beginUpdateMemoryUsed(long bytesUsed, boolean willSendEvent) {
      beginUpdateMemoryUsed = true;
      beginUpdateMemoryUsed_bytesUsed = bytesUsed;
      beginUpdateMemoryUsed_willSendEvent = willSendEvent;
    }

    @Override
    public synchronized void afterNotifyUpdateMemoryUsed(long bytesUsed) {}

    @Override
    public synchronized void beginUpdateStateAndSendEvent(long bytesUsed, boolean willSendEvent) {}

    private boolean updateStateAndSendEventBeforeProcess;
    private long updateStateAndSendEventBeforeProcess_bytesUsed;
    private MemoryEvent updateStateAndSendEventBeforeProcess_event;

    @Override
    public synchronized void updateStateAndSendEventBeforeProcess(long bytesUsed,
        MemoryEvent event) {
      updateStateAndSendEventBeforeProcess = true;
      updateStateAndSendEventBeforeProcess_bytesUsed = bytesUsed;
      updateStateAndSendEventBeforeProcess_event = event;
    }

    @Override
    public synchronized void updateStateAndSendEventBeforeAbnormalProcess(long bytesUsed,
        MemoryEvent event) {}

    @Override
    public synchronized void updateStateAndSendEventIgnore(long bytesUsed, MemoryState oldState,
        MemoryState newState, long mostRecentBytesUsed, boolean deliverNextAbnormalEvent) {}

    public synchronized void verifyBeginUpdateMemoryUsed(long expected_bytesUsed,
        boolean expected_willSendEvent) {
      if (!beginUpdateMemoryUsed) {
        fail("beginUpdateMemoryUsed was not called");
      }
      assertEquals(expected_bytesUsed, beginUpdateMemoryUsed_bytesUsed);
      assertEquals(expected_willSendEvent, beginUpdateMemoryUsed_willSendEvent);
    }

    /**
     * Verify that beginUpdateMemoryUsed was called, event will be sent, and return the "bytesUsed"
     * it recorded.
     */
    public synchronized long verifyBeginUpdateMemoryUsed(boolean expected_willSendEvent) {
      if (!beginUpdateMemoryUsed) {
        fail("beginUpdateMemoryUsed was not called");
      }
      assertEquals(expected_willSendEvent, beginUpdateMemoryUsed_willSendEvent);
      return beginUpdateMemoryUsed_bytesUsed;
    }

    public synchronized boolean checkUpdateStateAndSendEventBeforeProcess(long expected_bytesUsed,
        MemoryState expected_memoryState) {
      if (!updateStateAndSendEventBeforeProcess) {
        return false;
      }
      if (expected_bytesUsed != updateStateAndSendEventBeforeProcess_bytesUsed) {
        return false;
      }
      return expected_memoryState.equals(updateStateAndSendEventBeforeProcess_event.getState());
    }

    public synchronized void validateUpdateStateAndSendEventBeforeProcess(long expected_bytesUsed,
        MemoryState expected_memoryState) {
      if (!updateStateAndSendEventBeforeProcess) {
        fail("updateStateAndSendEventBeforeProcess was not called");
      }
      assertEquals(expected_bytesUsed, updateStateAndSendEventBeforeProcess_bytesUsed);
      assertEquals(expected_memoryState, updateStateAndSendEventBeforeProcess_event.getState());
    }
  }

  private void registerTestMemoryThresholdListener(VM vm) {
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        TestMemoryThresholdListener listener = new TestMemoryThresholdListener();
        InternalResourceManager irm = getCache().getInternalResourceManager();
        irm.addResourceListener(ResourceType.OFFHEAP_MEMORY, listener);
        assertTrue(irm.getResourceListeners(ResourceType.OFFHEAP_MEMORY).contains(listener));
        return null;
      }
    });
  }

  private int startCacheServer(VM server, final float evictionThreshold,
      final float criticalThreshold, final String regionName, final boolean createPR,
      final boolean notifyBySubscription, final int prRedundancy) throws Exception {

    return (Integer) server.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getSystem(getOffHeapProperties());
        GemFireCacheImpl cache = (GemFireCacheImpl) getCache();

        InternalResourceManager irm = cache.getInternalResourceManager();
        irm.setEvictionOffHeapPercentage(evictionThreshold);
        irm.setCriticalOffHeapPercentage(criticalThreshold);

        AttributesFactory factory = new AttributesFactory();
        if (createPR) {
          PartitionAttributesFactory paf = new PartitionAttributesFactory();
          paf.setRedundantCopies(prRedundancy);
          paf.setTotalNumBuckets(11);
          factory.setPartitionAttributes(paf.create());
          factory.setOffHeap(true);
        } else {
          factory.setScope(Scope.DISTRIBUTED_ACK);
          factory.setDataPolicy(DataPolicy.REPLICATE);
          factory.setOffHeap(true);
        }
        Region region = createRegion(regionName, factory.create());
        if (createPR) {
          assertTrue(region instanceof PartitionedRegion);
        } else {
          assertTrue(region instanceof DistributedRegion);
        }
        CacheServer cacheServer = getCache().addCacheServer();
        cacheServer.setPort(0);
        cacheServer.setNotifyBySubscription(notifyBySubscription);
        cacheServer.start();
        return cacheServer.getPort();
      }
    });
  }

  private void startClient(VM client, final VM server, final int serverPort,
      final String regionName) {

    client.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getSystem(getClientProps());
        getCache();

        PoolFactory pf = PoolManager.createFactory();
        pf.addServer(NetworkUtils.getServerHostName(server.getHost()), serverPort);
        pf.create("pool1");

        AttributesFactory af = new AttributesFactory();
        af.setScope(Scope.LOCAL);
        af.setPoolName("pool1");
        createRegion(regionName, af.create());
        return null;
      }
    });
  }

  /**
   * Verifies that the test listener value on the given vm is what is expected Note that for remote
   * events useWaitCriterion must be true. Note also that since off-heap local events are async
   * local events must also set useWaitCriterion to true.
   *
   * @param vm the vm where verification should take place
   * @param value the expected value
   * @param useWaitCriterion must be true for both local and remote events (see GEODE-138)
   */
  private void verifyListenerValue(VM vm, final MemoryState state, final int value,
      final boolean useWaitCriterion) {
    vm.invoke(new SerializableCallable() {
      private static final long serialVersionUID = 1L;

      @Override
      public Object call() throws Exception {
        WaitCriterion wc = null;
        Set<ResourceListener<?>> listeners = getGemfireCache().getInternalResourceManager()
            .getResourceListeners(ResourceType.OFFHEAP_MEMORY);
        TestMemoryThresholdListener tmp_listener = null;
        for (final ResourceListener<?> l : listeners) {
          if (l instanceof TestMemoryThresholdListener) {
            tmp_listener = (TestMemoryThresholdListener) l;
            break;
          }
        }
        final TestMemoryThresholdListener listener = tmp_listener;
        switch (state) {
          case CRITICAL:
            if (useWaitCriterion) {
              wc = new WaitCriterion() {
                @Override
                public String description() {
                  return "Remote CRITICAL assert failed " + listener.toString();
                }

                @Override
                public boolean done() {
                  return value == listener.getCriticalThresholdCalls();
                }
              };
            } else {
              assertEquals(value, listener.getCriticalThresholdCalls());
            }
            break;
          case CRITICAL_DISABLED:
            if (useWaitCriterion) {
              wc = new WaitCriterion() {
                @Override
                public String description() {
                  return "Remote CRITICAL_DISABLED assert failed " + listener.toString();
                }

                @Override
                public boolean done() {
                  return value == listener.getCriticalDisabledCalls();
                }
              };
            } else {
              assertEquals(value, listener.getCriticalDisabledCalls());
            }
            break;
          case EVICTION:
            if (useWaitCriterion) {
              wc = new WaitCriterion() {
                @Override
                public String description() {
                  return "Remote EVICTION assert failed " + listener.toString();
                }

                @Override
                public boolean done() {
                  return value == listener.getEvictionThresholdCalls();
                }
              };
            } else {
              assertEquals(value, listener.getEvictionThresholdCalls());
            }
            break;
          case EVICTION_DISABLED:
            if (useWaitCriterion) {
              wc = new WaitCriterion() {
                @Override
                public String description() {
                  return "Remote EVICTION_DISABLED assert failed " + listener.toString();
                }

                @Override
                public boolean done() {
                  return value == listener.getEvictionDisabledCalls();
                }
              };
            } else {
              assertEquals(value, listener.getEvictionDisabledCalls());
            }
            break;
          case NORMAL:
            if (useWaitCriterion) {
              wc = new WaitCriterion() {
                @Override
                public String description() {
                  return "Remote NORMAL assert failed " + listener.toString();
                }

                @Override
                public boolean done() {
                  return value == listener.getNormalCalls();
                }
              };
            } else {
              assertEquals(value, listener.getNormalCalls());
            }
            break;
          default:
            throw new IllegalStateException("Unknown memory state");
        }
        if (useWaitCriterion) {
          Wait.waitForCriterion(wc, 5000, 10, true);
        }
        return null;
      }
    });
  }

  private void verifyProfiles(VM vm, final int numberOfProfiles) {
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        InternalResourceManager irm = getCache().getInternalResourceManager();
        final ResourceAdvisor ra = irm.getResourceAdvisor();
        WaitCriterion wc = new WaitCriterion() {
          @Override
          public String description() {
            return "verify profiles failed. Current profiles: " + ra.adviseGeneric();
          }

          @Override
          public boolean done() {
            return numberOfProfiles == ra.adviseGeneric().size();
          }
        };
        Wait.waitForCriterion(wc, 10000, 10, true);
        return null;
      }
    });
  }

  private Properties getOffHeapProperties() {
    Properties p = new Properties();
    p.setProperty(LOCATORS, "localhost[" + DistributedTestUtils.getDUnitLocatorPort() + "]");
    p.setProperty(OFF_HEAP_MEMORY_SIZE, "1m");
    return p;
  }

  protected Properties getClientProps() {
    Properties p = new Properties();
    p.setProperty(MCAST_PORT, "0");
    p.setProperty(LOCATORS, "");
    return p;
  }
}
