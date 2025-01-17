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
/*
 * PutAllGlobalDUnitTest.java
 *
 * Created on September 16, 2005, 3:02 PM
 */
package org.apache.geode.internal.cache;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.Test;

import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.CacheTransactionManager;
import org.apache.geode.cache.CacheWriter;
import org.apache.geode.cache.EntryEvent;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.TimeoutException;
import org.apache.geode.cache.util.CacheWriterAdapter;
import org.apache.geode.cache30.CacheSerializableRunnable;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.distributed.internal.locks.DLockGrantor;
import org.apache.geode.test.dunit.Assert;
import org.apache.geode.test.dunit.AsyncInvocation;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.Invoke;
import org.apache.geode.test.dunit.LogWriterUtils;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.ThreadUtils;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.internal.JUnit4DistributedTestCase;


public class PutAllGlobalDUnitTest extends JUnit4DistributedTestCase { // TODO: reformat
  /**
   * timeout period for the put() operation, when it is run concurrent with a conflicting putAll()
   * operation
   */
  static final int TIMEOUT_PERIOD = 1000;

  private static ServerSocket serverSocket;

  static Cache cache;
  static Properties props = new Properties();
  static Properties propsWork = new Properties();
  static DistributedSystem ds = null;
  static Region region;
  static CacheTransactionManager cacheTxnMgr;
  static int beforeCreateputAllcounter = 0;

  static boolean flag = false;

  @Override
  public final void postSetUp() throws Exception {
    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);
    vm0.invoke(PutAllGlobalDUnitTest::createCacheForVM0);
    vm1.invoke(PutAllGlobalDUnitTest::createCacheForVM1);
    LogWriterUtils.getLogWriter().fine("Cache created successfully");
  }

  @Override
  public final void preTearDown() throws Exception {
    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);
    vm0.invoke(PutAllGlobalDUnitTest::closeCache);
    vm1.invoke(PutAllGlobalDUnitTest::closeCache);
    cache = null;
    Invoke.invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        cache = null;
      }
    });
  }

  public static void createCacheForVM0() {
    try {
      ds = (new PutAllGlobalDUnitTest()).getSystem(props);
      cache = CacheFactory.create(ds);
      AttributesFactory factory = new AttributesFactory();
      factory.setScope(Scope.GLOBAL);
      RegionAttributes attr = factory.create();
      region = cache.createRegion("map", attr);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public static void createCacheForVM1() {
    try {
      CacheWriter aWriter = new BeforeCreateCallback();
      ds = (new PutAllGlobalDUnitTest()).getSystem(props);
      cache = CacheFactory.create(ds);
      cache.setLockTimeout(TIMEOUT_PERIOD / 1000);
      AttributesFactory factory = new AttributesFactory();
      factory.setScope(Scope.GLOBAL);
      factory.setCacheWriter(aWriter);
      RegionAttributes attr = factory.create();
      region = cache.createRegion("map", attr);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public static void closeCache() {
    try {
      // getLogWriter().fine("closing cache cache cache cache cache 33333333");
      cache.close();
      ds.disconnect();
      // getLogWriter().fine("closed cache cache cache cache cache 44444444");
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  /** open a socket to be used in synchronizing two asyncInvocations */
  public static int openSocket() throws IOException {
    serverSocket = new ServerSocket(0, 10, InetAddress.getLocalHost());
    return serverSocket.getLocalPort();
  }

  // test methods

  @Test
  public void testputAllGlobalRemoteVM() throws Throwable {
    // Test Fails: AssertionError: Should have thrown TimeoutException
    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);

    final int socketPort = vm0.invoke(PutAllGlobalDUnitTest::openSocket);

    AsyncInvocation async1 = vm0.invokeAsync(PutAllGlobalDUnitTest::putAllMethod);

    AsyncInvocation async2 = vm1.invokeAsync(new CacheSerializableRunnable("put from another vm") {
      @Override
      public void run2() throws CacheException {
        long endTime = System.currentTimeMillis() + 5000;
        boolean connected = false;
        while (!connected && (System.currentTimeMillis() < endTime)) {
          try {
            Socket sock = new Socket(InetAddress.getLocalHost(), socketPort);
            connected = true;
            sock.close();
          } catch (IOException ioe) {
            // ignored - will time out using 'endTime'
            try {
              Thread.sleep(500);
            } catch (InterruptedException ie) {
              fail("Interrupted while waiting for async1 invocation");
            }
          }
        }
        if (!connected) {
          fail("unable to connect to async1 invocation");
        }
        long startTime = 0;
        try {
          Thread.sleep(500);
          LogWriterUtils.getLogWriter().info("async2 proceeding with put operation");
          startTime = System.currentTimeMillis();
          region.put(1, "mapVal");
          LogWriterUtils.getLogWriter().info("async2 done with put operation");
          fail("Should have thrown TimeoutException");
        } catch (TimeoutException Tx) {
          // Tx.printStackTrace();
          LogWriterUtils.getLogWriter().info("PASS: As expected Caught TimeoutException ");
          if (startTime + TIMEOUT_PERIOD
              + DLockGrantor.GRANTOR_THREAD_MAX_WAIT /* slop of grantor max wait ms */ < System
                  .currentTimeMillis()) {
            LogWriterUtils.getLogWriter()
                .warning("though this test passed, the put() timed out in "
                    + (System.currentTimeMillis() - startTime) + " instead of the expected "
                    + TIMEOUT_PERIOD + " milliseconds");
          }
        } catch (Exception ex) {
          Assert.fail("async2 threw unexpected exception", ex);
          // ex.printStackTrace();
        }
      }
    });

    ThreadUtils.join(async2, 30 * 1000);
    if (async2.exceptionOccurred()) {
      ThreadUtils.join(async1, 30 * 1000);
      Assert.fail("async2 failed", async2.getException());
    }

    ThreadUtils.join(async1, 30 * 1000);
    if (async1.exceptionOccurred()) {
      Assert.fail("async1 failed", async1.getException());
    }

  }// end of test case1



  public static void putAllMethod() throws Exception {
    Map m = new HashMap();
    serverSocket.accept();
    LogWriterUtils.getLogWriter()
        .info("async1 connection received - continuing with putAll operation");
    serverSocket.close();
    try {
      for (int i = 1; i < 2; i++) {
        m.put(i, String.valueOf(i));
      }
      region.putAll(m);
      LogWriterUtils.getLogWriter().info("async1 done with putAll operation");

    } catch (Exception ex) {
      // ex.printStackTrace();
      Assert.fail("Failed while region.putAll", ex);
    }
  }// end of putAllMethod


  public static Object getMethod(Object ob) {
    Object obj = null;
    try {
      obj = region.get(ob);
    } catch (Exception ex) {
      fail("Failed while region.get");
    }
    return obj;
  }

  public static boolean containsValueMethod(Object ob) {
    boolean result = false;
    try {
      result = region.containsValue(ob);
    } catch (Exception ex) {
      fail("Failed while region.containsValueMethod");
    }
    return result;
  }

  public static int sizeMethod() {
    int i = 0;
    try {
      i = region.size();
    } catch (Exception ex) {
      fail("Failed while region.size");
    }
    return i;
  }

  public static void clearMethod() {
    try {
      region.clear();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  static class BeforeCreateCallback extends CacheWriterAdapter {
    @Override
    public void beforeCreate(EntryEvent event) {
      LogWriterUtils.getLogWriter().info("beforeCreate invoked for " + event.getKey());
      try {
        Thread.sleep(5000);
      } catch (InterruptedException ex) {
        fail("interrupted");
      }
      LogWriterUtils.getLogWriter().info("beforeCreate done for " + event.getKey());

    }
  }// end of BeforeCreateCallback


}// endof class
