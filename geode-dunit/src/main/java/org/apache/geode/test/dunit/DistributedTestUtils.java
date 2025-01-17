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
package org.apache.geode.test.dunit;

import static org.apache.geode.distributed.ConfigurationProperties.DISABLE_AUTO_RECONNECT;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Map;
import java.util.Properties;

import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.distributed.internal.membership.api.MembershipManagerHelper;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.InternalInstantiator;
import org.apache.geode.test.dunit.rules.DistributedRule;

/**
 * {@code DistributedTestUtils} provides static utility methods that affect the runtime environment
 * or artifacts generated by a DistributedTest.
 *
 * <p>
 * These methods can be used directly: {@code DistributedTestUtils.crashDistributedSystem(...)},
 * however, they are intended to be referenced through static import:
 *
 * <pre>
 * import static org.apache.geode.test.dunit.DistributedTestUtils.crashDistributedSystem;
 *    ...
 *    crashDistributedSystem(...);
 * </pre>
 *
 * Extracted from DistributedTestCase.
 */
public class DistributedTestUtils {

  protected DistributedTestUtils() {
    // nothing
  }

  /**
   * Crash the cache in the given VM in such a way that it immediately stops communicating with
   * peers. This forces the VM's membership manager to throw a ForcedDisconnectException by forcibly
   * terminating the JGroups protocol stack with a fake EXIT event.
   *
   * <p>
   * NOTE: if you use this method be sure that you clean up the VM before the end of your test with
   * disconnectFromDS() or disconnectAllFromDS().
   */
  public static void crashDistributedSystem(final DistributedSystem system) {
    MembershipManagerHelper.crashDistributedSystem(system);
  }

  /**
   * Crash the cache in the given VM in such a way that it immediately stops communicating with
   * peers. This forces the VM's membership manager to throw a ForcedDisconnectException by forcibly
   * terminating the JGroups protocol stack with a fake EXIT event.
   *
   * <p>
   * NOTE: if you use this method be sure that you clean up the VM before the end of your test with
   * disconnectFromDS() or disconnectAllFromDS().
   */
  public static void crashDistributedSystem(final VM... vms) {
    for (VM vm : vms) {
      vm.invoke(() -> {
        DistributedSystem system = InternalDistributedSystem.getAnyInstance();
        crashDistributedSystem(system);
      });
    }
  }

  /**
   * Delete locator state files. Use this after getting a random port to ensure that an old locator
   * state file isn't picked up by the new locator you're starting.
   */
  public static void deleteLocatorStateFile(final int... ports) {
    for (int port : ports) {
      File stateFile = new File("locator" + port + "view.dat");
      if (stateFile.exists()) {
        stateFile.delete();
      }
    }
  }

  public static Properties getAllDistributedSystemProperties(final Properties properties) {
    Properties dsProperties = DUnitEnv.get().getDistributedSystemProperties();

    // our tests do not expect auto-reconnect to be on by default
    if (!dsProperties.contains(DISABLE_AUTO_RECONNECT)) {
      dsProperties.setProperty(DISABLE_AUTO_RECONNECT, "true");
    }

    for (Map.Entry<Object, Object> entry : properties.entrySet()) {
      String key = (String) entry.getKey();
      Object value = entry.getValue();
      dsProperties.put(key, value);
    }
    System.out.println("distributed system properties: " + dsProperties);
    return dsProperties;
  }

  /**
   * @deprecated Please use {@link DistributedRule#getLocatorPort()} instead.
   */
  @Deprecated
  public static int getDUnitLocatorPort() {
    return DistributedRule.getLocatorPort();
  }

  /**
   * @deprecated Please use {@link DistributedRule#getLocatorPort()} instead.
   */
  @Deprecated
  public static int getLocatorPort() {
    return DistributedRule.getLocatorPort();
  }

  /**
   * @deprecated Please use {@link DistributedRule#getLocators()} instead.
   */
  @Deprecated
  public static String getLocators() {
    return DistributedRule.getLocators();
  }

  public static void unregisterAllDataSerializersFromAllVms() {
    unregisterDataSerializerInThisVM();
    Invoke.invokeInEveryVM(DistributedTestUtils::unregisterDataSerializerInThisVM);
    Invoke.invokeInLocator(DistributedTestUtils::unregisterDataSerializerInThisVM);
  }

  public static void unregisterInstantiatorsInThisVM() {
    // unregister all the instantiators
    InternalInstantiator.reinitialize();
    assertThat(InternalInstantiator.getInstantiators()).isEmpty();
  }

  private static void unregisterDataSerializerInThisVM() {
    // TODO: delete DataSerializerPropogationDUnitTest.successfullyLoadedTestDataSerializer = false;
    // unregister all the Dataserializers
    InternalDataSerializer.reinitialize();
    // ensure that all are unregistered
    assertThat(InternalDataSerializer.getSerializers()).isEmpty();
  }
}
