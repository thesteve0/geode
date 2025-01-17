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

import static org.apache.geode.redis.internal.RedisConstants.ERROR_NEGATIVE_TIMEOUT;

import java.util.ArrayList;
import java.util.List;

import org.apache.geode.redis.internal.RedisConstants;
import org.apache.geode.redis.internal.commands.Command;
import org.apache.geode.redis.internal.commands.executor.CommandExecutor;
import org.apache.geode.redis.internal.commands.executor.RedisResponse;
import org.apache.geode.redis.internal.data.RedisKey;
import org.apache.geode.redis.internal.data.RedisList;
import org.apache.geode.redis.internal.netty.Coder;
import org.apache.geode.redis.internal.netty.ExecutionHandlerContext;

public class BLPopExecutor implements CommandExecutor {

  @Override
  public RedisResponse executeCommand(Command command, ExecutionHandlerContext context) {
    List<byte[]> arguments = command.getCommandArguments();
    double timeoutSeconds;
    try {
      timeoutSeconds = Coder.bytesToDouble(arguments.get(arguments.size() - 1));
    } catch (NumberFormatException e) {
      return RedisResponse.error(RedisConstants.ERROR_TIMEOUT_INVALID);
    }

    if (timeoutSeconds < 0) {
      return RedisResponse.error(ERROR_NEGATIVE_TIMEOUT);
    }

    int keyCount = arguments.size() - 1;
    List<RedisKey> keys = new ArrayList<>(keyCount);
    for (int i = 0; i < keyCount; i++) {
      keys.add(new RedisKey(arguments.get(i)));
    }
    // The order of keys is important and, since locking may alter the order passed into
    // lockedExecute, we create a copy here.
    List<RedisKey> keysForLocking = new ArrayList<>(keys);

    List<byte[]> popped = context.lockedExecute(keys.get(0), keysForLocking,
        () -> RedisList.blpop(context, command, keys, timeoutSeconds));

    return popped == null ? RedisResponse.BLOCKED : RedisResponse.array(popped, true);
  }
}
