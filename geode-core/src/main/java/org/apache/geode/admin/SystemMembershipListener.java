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
package org.apache.geode.admin;

/**
 * A listener whose callback methods are invoked when members join or leave the GemFire distributed
 * system.
 *
 * @see AdminDistributedSystem#addMembershipListener
 *
 * @since GemFire 3.5
 * @deprecated as of 7.0 use the <code><a href=
 *             "{@docRoot}/org/apache/geode/management/package-summary.html">management</a></code>
 *             package instead
 */
@Deprecated
public interface SystemMembershipListener {

  /**
   * Invoked when a member has joined the distributed system
   */
  void memberJoined(SystemMembershipEvent event);

  /**
   * Invoked when a member has gracefully left the distributed system. This occurs when the member
   * took action to remove itself from the distributed system.
   */
  void memberLeft(SystemMembershipEvent event);

  /**
   * Invoked when a member has unexpectedly left the distributed system. This occurs when a member
   * is forcibly removed from the distributed system by another process, such as from <a
   * href=../distributed/DistributedSystem.html#member-timeout> failure detection</a>, or <a
   * href=../distributed/DistributedSystem.html#enable-network-partition-detection> network
   * partition detection</a> processing.
   */
  void memberCrashed(SystemMembershipEvent event);
}
