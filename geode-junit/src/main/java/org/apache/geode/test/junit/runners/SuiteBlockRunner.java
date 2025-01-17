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
package org.apache.geode.test.junit.runners;

import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;

/**
 * used by SuiteRunner to override the test method name
 */
public class SuiteBlockRunner extends BlockJUnit4ClassRunner {

  private final Class<?> suiteClass;

  /**
   * Creates a BlockJUnit4ClassRunner to run {@code testClass}
   *
   * @param testClass the test class
   * @throws InitializationError if the test class is malformed.
   */
  public SuiteBlockRunner(final Class parentClass, final Class<?> testClass)
      throws InitializationError {
    super(testClass);
    suiteClass = parentClass;
  }

  @Override
  protected String testName(FrameworkMethod method) {
    // Some tests use the test name as the region name, so make sure we have valid chars
    return method.getName() + "-" + suiteClass.getName().replace(".", "-");
  }
}
