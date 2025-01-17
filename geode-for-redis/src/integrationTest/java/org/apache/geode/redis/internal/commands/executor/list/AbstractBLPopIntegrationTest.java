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
package org.apache.geode.redis.internal.commands.executor.list;

import static org.apache.geode.redis.RedisCommandArgumentsTestHelper.assertAtLeastNArgs;
import static org.apache.geode.test.dunit.rules.RedisClusterStartupRule.BIND_ADDRESS;
import static org.apache.geode.test.dunit.rules.RedisClusterStartupRule.REDIS_CLIENT_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.Protocol;

import org.apache.geode.redis.RedisIntegrationTest;
import org.apache.geode.redis.internal.RedisConstants;
import org.apache.geode.test.awaitility.GeodeAwaitility;
import org.apache.geode.test.junit.rules.ExecutorServiceRule;

public abstract class AbstractBLPopIntegrationTest implements RedisIntegrationTest {
  private static final String KEY = "key";

  protected JedisCluster jedis;

  public abstract void awaitEventDistributorSize(int size) throws Exception;

  @ClassRule
  public static ExecutorServiceRule executor = new ExecutorServiceRule();

  @Before
  public void setUp() {
    jedis = new JedisCluster(new HostAndPort(BIND_ADDRESS, getPort()), REDIS_CLIENT_TIMEOUT);
  }

  @After
  public void tearDown() {
    flushAll();
    jedis.close();
  }

  @Test
  public void testInvalidArguments_throwErrors() {
    assertAtLeastNArgs(jedis, Protocol.Command.BLPOP, 2);
  }

  @Test
  public void testInvalidTimeout_throwsError() {
    assertThatThrownBy(() -> jedis.sendCommand("key1", Protocol.Command.BLPOP, "key1",
        "0.A"))
            .hasMessage(RedisConstants.ERROR_TIMEOUT_INVALID);
  }

  @Test
  public void testKeysInDifferentSlots_throwsError() {
    assertThatThrownBy(() -> jedis.sendCommand("key1", Protocol.Command.BLPOP, "key1",
        "key2", "0"))
            .hasMessage(RedisConstants.ERROR_WRONG_SLOT);
  }

  @Test
  public void testNegativeTimeout_throwsError() {
    assertThatThrownBy(() -> jedis.blpop(-1, "key1"))
        .hasMessage(RedisConstants.ERROR_NEGATIVE_TIMEOUT);
  }

  @Test
  public void testBLPopForNonListKey() {
    jedis.set("not-a-list", "value");

    assertThatThrownBy(() -> jedis.blpop(0, "not-a-list"))
        .hasMessage(RedisConstants.ERROR_WRONG_TYPE);
  }

  @Test
  public void testBLPopWhenValueExists() {
    jedis.lpush(KEY, "value1", "value2");

    List<String> result = jedis.blpop(0, KEY);

    assertThat(result).containsExactly(KEY, "value2");
    assertThat(jedis.lpop(KEY)).isEqualTo("value1");
  }

  @Test
  public void testBLPopDoesNotError_whenTimeoutHasExponent() {
    jedis.lpush(KEY, "value1", "value2");

    Object result = jedis.sendCommand(KEY, Protocol.Command.BLPOP, KEY, "1E+3");

    assertThat(result).isNotNull();
  }

  @Test
  public void testBLPopWhenValueDoesNotExist() throws Exception {
    Future<List<String>> future = executor.submit(() -> jedis.blpop(0, KEY));

    awaitEventDistributorSize(1);
    jedis.lpush(KEY, "value1", "value2");

    assertThat(future.get()).containsExactly(KEY, "value2");
    assertThat(jedis.lpop(KEY)).isEqualTo("value1");
  }

  @Test
  public void testRPushFiresEventForBLPop() throws Exception {
    Future<List<String>> future = executor.submit(() -> jedis.blpop(0, KEY));

    awaitEventDistributorSize(1);
    jedis.rpush(KEY, "value1", "value2");

    assertThat(future.get()).containsExactly(KEY, "value1");
    assertThat(jedis.lpop(KEY)).isEqualTo("value2");
  }

  @Test
  public void testBLPopWhenTimeoutIsExceeded() {
    int timeout = 10;
    Future<List<String>> future = executor.submit(() -> jedis.blpop(timeout, KEY));
    GeodeAwaitility.await().atMost(timeout * 2, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(future.get()).isNull());
  }

  @Test
  public void testBLpopFirstKeyDoesNotExistButSecondOneDoes() {
    jedis.lpush("{A}key2", "value2");

    List<String> result = jedis.blpop(0, "{A}key1", "{A}key2");

    assertThat(result).containsExactly("{A}key2", "value2");
  }

  @Test
  public void testBLpopKeyOrderingIsCorrect() {
    jedis.lpush("{A}key2", "value2");
    jedis.lpush("{A}key3", "value3");

    List<String> result = jedis.blpop(0, "{A}key1", "{A}key3", "{A}key2");

    assertThat(result).containsExactly("{A}key3", "value3");
  }

  @Test
  public void testBLPopWhenValueGetsCreated_withRename() throws Exception {
    String initialName = "{tag}initial";
    String changedName = "{tag}changed";
    jedis.lpush(initialName, "value1", "value2");

    Future<List<String>> future = executor.submit(() -> jedis.blpop(10, changedName));
    awaitEventDistributorSize(1);

    jedis.rename(initialName, changedName);

    assertThat(future.get()).containsExactly(changedName, "value2");
    assertThat(jedis.lpop(changedName)).isEqualTo("value1");
  }

  @Test
  public void testBLPopWhenValueGetsCreated_withRestore() throws Exception {
    String key = "key";
    jedis.lpush(key, "value1", "value2");

    byte[] dumpBytes = jedis.dump(key);
    jedis.del(key);

    Future<List<String>> future = executor.submit(() -> jedis.blpop(10, key));
    awaitEventDistributorSize(1);

    jedis.restore(key, 0, dumpBytes);

    assertThat(future.get()).containsExactly(key, "value2");
    assertThat(jedis.lpop(key)).isEqualTo("value1");
  }

  @Test
  public void testConcurrentBLPop() throws Exception {
    int totalElements = 10_000;
    List<Object> accumulated = Collections.synchronizedList(new ArrayList<>(totalElements + 2));
    AtomicBoolean running = new AtomicBoolean(true);

    Future<Void> future1 = executor.submit(() -> doBLPop(running, accumulated));
    Future<Void> future2 = executor.submit(() -> doBLPop(running, accumulated));

    List<String> result = new ArrayList<>();
    for (int i = 0; i < totalElements; i++) {
      jedis.lpush(KEY, "value" + i);
      result.add("value" + i);
    }

    GeodeAwaitility.await()
        .untilAsserted(() -> assertThat(accumulated.size()).isEqualTo(totalElements));

    running.set(false);

    jedis.lpush(KEY, "stop1");
    jedis.lpush(KEY, "stop2");
    result.add("stop1");
    result.add("stop2");
    future1.get();
    future2.get();

    assertThat(accumulated).containsExactlyInAnyOrderElementsOf(result);
  }

  private void doBLPop(AtomicBoolean running, List<Object> accumulated) {
    while (running.get()) {
      accumulated.add(jedis.blpop(0, KEY).get(1));
    }
  }

}
