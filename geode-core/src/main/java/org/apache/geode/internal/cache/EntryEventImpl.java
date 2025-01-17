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
package org.apache.geode.internal.cache;

import static org.apache.geode.internal.offheap.annotations.OffHeapIdentifier.ENTRY_EVENT_NEW_VALUE;
import static org.apache.geode.internal.offheap.annotations.OffHeapIdentifier.ENTRY_EVENT_OLD_VALUE;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.function.Function;

import org.apache.logging.log4j.Logger;

import org.apache.geode.CopyHelper;
import org.apache.geode.DataSerializer;
import org.apache.geode.Delta;
import org.apache.geode.DeltaSerializationException;
import org.apache.geode.GemFireIOException;
import org.apache.geode.InvalidDeltaException;
import org.apache.geode.SerializationException;
import org.apache.geode.SystemFailure;
import org.apache.geode.annotations.VisibleForTesting;
import org.apache.geode.cache.EntryNotFoundException;
import org.apache.geode.cache.EntryOperation;
import org.apache.geode.cache.Operation;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.SerializedCacheValue;
import org.apache.geode.cache.TransactionId;
import org.apache.geode.cache.query.IndexMaintenanceException;
import org.apache.geode.cache.query.QueryException;
import org.apache.geode.cache.query.internal.index.IndexManager;
import org.apache.geode.cache.query.internal.index.IndexProtocol;
import org.apache.geode.cache.query.internal.index.IndexUtils;
import org.apache.geode.cache.util.TimestampedEntryEvent;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.distributed.internal.DistributionMessage;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.internal.Assert;
import org.apache.geode.internal.DSFIDFactory;
import org.apache.geode.internal.HeapDataOutputStream;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.Sendable;
import org.apache.geode.internal.cache.FilterRoutingInfo.FilterInfo;
import org.apache.geode.internal.cache.entries.OffHeapRegionEntry;
import org.apache.geode.internal.cache.partitioned.PartitionMessage;
import org.apache.geode.internal.cache.partitioned.PutMessage;
import org.apache.geode.internal.cache.tier.sockets.CacheServerHelper;
import org.apache.geode.internal.cache.tier.sockets.ClientProxyMembershipID;
import org.apache.geode.internal.cache.tx.DistTxKeyInfo;
import org.apache.geode.internal.cache.tx.RemoteOperationMessage;
import org.apache.geode.internal.cache.tx.RemotePutMessage;
import org.apache.geode.internal.cache.versions.VersionTag;
import org.apache.geode.internal.cache.wan.GatewaySenderEventCallbackArgument;
import org.apache.geode.internal.lang.StringUtils;
import org.apache.geode.internal.logging.log4j.LogMarker;
import org.apache.geode.internal.offheap.OffHeapHelper;
import org.apache.geode.internal.offheap.OffHeapRegionEntryHelper;
import org.apache.geode.internal.offheap.ReferenceCountHelper;
import org.apache.geode.internal.offheap.Releasable;
import org.apache.geode.internal.offheap.StoredObject;
import org.apache.geode.internal.offheap.annotations.Released;
import org.apache.geode.internal.offheap.annotations.Retained;
import org.apache.geode.internal.offheap.annotations.Unretained;
import org.apache.geode.internal.serialization.ByteArrayDataInput;
import org.apache.geode.internal.serialization.DataSerializableFixedID;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.internal.size.Sizeable;
import org.apache.geode.internal.util.ArrayUtils;
import org.apache.geode.internal.util.BlobHelper;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.pdx.internal.PeerTypeRegistration;
import org.apache.geode.util.internal.GeodeGlossary;

/**
 * Implementation of an entry event
 *
 * must be public for DataSerializableFixedID
 */
public class EntryEventImpl implements InternalEntryEvent, InternalCacheEvent,
    DataSerializableFixedID, EntryOperation, Releasable {
  private static final Logger logger = LogService.getLogger();

  // PACKAGE FIELDS //
  private transient InternalRegion region;

  private transient RegionEntry re;

  protected KeyInfo keyInfo;

  /** the event's id. Scoped by distributedMember. */
  protected EventID eventID;

  private Object newValue = null;

  /**
   * If we ever serialize the new value then it should be stored in this field in case we need the
   * serialized form again later. This was added to fix bug 43781. Note that we also have the
   * "newValueBytes" field. But it is only non-null if setSerializedNewValue was called.
   */
  private byte[] cachedSerializedNewValue = null;

  @Retained(ENTRY_EVENT_OLD_VALUE)
  private Object oldValue = null;

  protected short eventFlags = 0x0000;

  protected TXId txId = null;

  protected Operation op;

  /* To store the operation/modification type */
  private transient EnumListenerEvent eventType;

  /**
   * This field will be null unless this event is used for a putAll operation.
   *
   * @since GemFire 5.0
   */
  protected transient DistributedPutAllOperation putAllOp;

  /**
   * This field will be null unless this event is used for a removeAll operation.
   *
   * @since GemFire 8.1
   */
  protected transient DistributedRemoveAllOperation removeAllOp;

  /**
   * The member that originated this event
   *
   * @since GemFire 5.0
   */
  protected DistributedMember distributedMember;

  /**
   * transient storage for the message that caused the event
   */
  transient DistributionMessage causedByMessage;

  /**
   * The originating membershipId of this event.
   *
   * @since GemFire 5.1
   */
  protected ClientProxyMembershipID context = null;

  /**
   * this holds the bytes representing the change in value effected by this event. It is used when
   * the value implements the Delta interface.
   */
  private byte[] deltaBytes = null;

  /** routing information for cache clients for this event */
  private FilterInfo filterInfo;

  /** new value stored in serialized form */
  protected byte[] newValueBytes;

  /** old value stored in serialized form */
  private byte[] oldValueBytes;

  /** version tag for concurrency checks */
  protected VersionTag versionTag;

  /** boolean to indicate that the RegionEntry for this event has been evicted */
  private transient boolean isEvicted = false;

  private transient boolean isPendingSecondaryExpireDestroy = false;

  private transient boolean hasRetried = false;

  public static final Object SUSPECT_TOKEN = new Object();

  public EntryEventImpl() {
    offHeapLock = null;
  }

  /**
   * Reads the contents of this message from the given input.
   */
  @Override
  public void fromData(DataInput in,
      DeserializationContext context) throws IOException, ClassNotFoundException {
    eventID = context.getDeserializer().readObject(in);
    Object key = context.getDeserializer().readObject(in);
    Object value = context.getDeserializer().readObject(in);
    keyInfo = new KeyInfo(key, value, null);
    op = Operation.fromOrdinal(in.readByte());
    eventFlags = in.readShort();
    keyInfo.setCallbackArg(context.getDeserializer().readObject(in));
    txId = context.getDeserializer().readObject(in);

    if (in.readBoolean()) { // isDelta
      assert false : "isDelta should never be true";
    } else {
      // OFFHEAP Currently values are never deserialized to off heap memory. If that changes then
      // this code needs to change.
      if (in.readBoolean()) { // newValueSerialized
        newValueBytes = DataSerializer.readByteArray(in);
        cachedSerializedNewValue = newValueBytes;
        newValue = null; // set later in generateNewValueFromBytesIfNeeded
      } else {
        newValueBytes = null;
        cachedSerializedNewValue = null;
        newValue = context.getDeserializer().readObject(in);
      }
    }

    // OFFHEAP Currently values are never deserialized to off heap memory. If that changes then this
    // code needs to change.
    if (in.readBoolean()) { // oldValueSerialized
      oldValueBytes = DataSerializer.readByteArray(in);
      oldValue = null; // set later in basicGetOldValue
    } else {
      oldValueBytes = null;
      oldValue = context.getDeserializer().readObject(in);
    }
    distributedMember = DSFIDFactory.readInternalDistributedMember(in);
    this.context = ClientProxyMembershipID.readCanonicalized(in);
    tailKey = DataSerializer.readLong(in);
  }

  @Retained
  protected EntryEventImpl(InternalRegion region, Operation op, Object key, boolean originRemote,
      DistributedMember distributedMember, boolean generateCallbacks, boolean fromRILocalDestroy) {
    this.region = region;
    InternalDistributedSystem ds =
        (InternalDistributedSystem) region.getCache().getDistributedSystem();
    if (ds.getOffHeapStore() != null) {
      offHeapLock = new Object();
    } else {
      offHeapLock = null;
    }
    this.op = op;
    keyInfo = region.getKeyInfo(key);
    setOriginRemote(originRemote);
    setGenerateCallbacks(generateCallbacks);
    this.distributedMember = distributedMember;
    setFromRILocalDestroy(fromRILocalDestroy);
  }

  /**
   * Doesn't specify oldValue as this will be filled in later as part of an operation on the region,
   * or lets it default to null.
   */
  @Retained
  protected EntryEventImpl(final InternalRegion region, Operation op, Object key,
      @Retained(ENTRY_EVENT_NEW_VALUE) Object newVal, Object callbackArgument, boolean originRemote,
      DistributedMember distributedMember, boolean generateCallbacks, boolean initializeId) {
    this.region = region;
    InternalDistributedSystem ds =
        (InternalDistributedSystem) region.getCache().getDistributedSystem();
    if (ds.getOffHeapStore() != null) {
      offHeapLock = new Object();
    } else {
      offHeapLock = null;
    }
    this.op = op;
    keyInfo = region.getKeyInfo(key, newVal, callbackArgument);

    if (!Token.isInvalid(newVal)) {
      basicSetNewValue(newVal, false);
    }

    txId = region.getTXId();
    /*
     * this might set txId for events done from a thread that has a tx even though the op is non-tx.
     * For example region ops.
     */
    if (newVal == Token.LOCAL_INVALID) {
      setLocalInvalid(true);
    }
    setOriginRemote(originRemote);
    setGenerateCallbacks(generateCallbacks);
    this.distributedMember = distributedMember;
  }

  /**
   * Called by BridgeEntryEventImpl to use existing EventID
   */
  @Retained
  protected EntryEventImpl(InternalRegion region, Operation op, Object key,
      @Retained(ENTRY_EVENT_NEW_VALUE) Object newValue, Object callbackArgument,
      boolean originRemote, DistributedMember distributedMember, boolean generateCallbacks,
      EventID eventID) {
    this(region, op, key, newValue, callbackArgument, originRemote, distributedMember,
        generateCallbacks, true /* initializeId */);
    Assert.assertTrue(eventID != null || !(region instanceof PartitionedRegion));
    setEventId(eventID);
  }

  /**
   * create an entry event from another entry event
   */
  @Retained
  public EntryEventImpl(
      @Retained({ENTRY_EVENT_NEW_VALUE, ENTRY_EVENT_OLD_VALUE}) EntryEventImpl other) {
    this(other, true);
  }

  @Retained
  public EntryEventImpl(
      @Retained({ENTRY_EVENT_NEW_VALUE, ENTRY_EVENT_OLD_VALUE}) EntryEventImpl other,
      boolean setOldValue) {
    setRegion(other.getRegion());
    if (other.offHeapLock != null) {
      offHeapLock = new Object();
    } else {
      offHeapLock = null;
    }

    eventID = other.eventID;
    basicSetNewValue(other.basicGetNewValue(), false);
    newValueBytes = other.newValueBytes;
    cachedSerializedNewValue = other.cachedSerializedNewValue;
    re = other.re;
    if (setOldValue) {
      retainAndSetOldValue(other.basicGetOldValue());
      oldValueBytes = other.oldValueBytes;
    }
    eventFlags = other.eventFlags;
    setEventFlag(EventFlags.FLAG_CALLBACKS_INVOKED, false);
    txId = other.txId;
    op = other.op;
    distributedMember = other.distributedMember;
    filterInfo = other.filterInfo;
    keyInfo = other.keyInfo.isDistKeyInfo() ? new DistTxKeyInfo((DistTxKeyInfo) other.keyInfo)
        : new KeyInfo(other.keyInfo);
    if (other.getRawCallbackArgument() instanceof GatewaySenderEventCallbackArgument) {
      keyInfo.setCallbackArg((new GatewaySenderEventCallbackArgument(
          (GatewaySenderEventCallbackArgument) other.getRawCallbackArgument())));
    }
    context = other.context;
    deltaBytes = other.deltaBytes;
    tailKey = other.tailKey;
    versionTag = other.versionTag;
    // set possible duplicate
    setPossibleDuplicate(other.isPossibleDuplicate());
  }

  @Retained
  public EntryEventImpl(Object key2, boolean isOffHeap) {
    keyInfo = new KeyInfo(key2, null, null);
    if (isOffHeap) {
      offHeapLock = new Object();
    } else {
      offHeapLock = null;
    }
  }

  /**
   * Creates and returns an EntryEventImpl. Generates and assigns a bucket id to the EntryEventImpl
   * if the region parameter is a PartitionedRegion.
   */
  @Retained
  public static EntryEventImpl create(InternalRegion region, Operation op, Object key,
      @Retained(ENTRY_EVENT_NEW_VALUE) Object newValue, Object callbackArgument,
      boolean originRemote, DistributedMember distributedMember) {
    return create(region, op, key, newValue, callbackArgument, originRemote, distributedMember,
        true, true);
  }

  /**
   * Creates and returns an EntryEventImpl. Generates and assigns a bucket id to the EntryEventImpl
   * if the region parameter is a PartitionedRegion.
   */
  @Retained
  public static EntryEventImpl create(InternalRegion region, Operation op, Object key,
      @Retained(ENTRY_EVENT_NEW_VALUE) Object newValue, Object callbackArgument,
      boolean originRemote, DistributedMember distributedMember, boolean generateCallbacks) {
    return create(region, op, key, newValue, callbackArgument, originRemote, distributedMember,
        generateCallbacks, true);
  }

  /**
   * Creates and returns an EntryEventImpl. Generates and assigns a bucket id to the EntryEventImpl
   * if the region parameter is a PartitionedRegion.
   *
   * Called by BridgeEntryEventImpl to use existing EventID
   */
  @Retained
  public static EntryEventImpl create(InternalRegion region, Operation op, Object key,
      @Retained(ENTRY_EVENT_NEW_VALUE) Object newValue, Object callbackArgument,
      boolean originRemote, DistributedMember distributedMember, boolean generateCallbacks,
      EventID eventID) {
    return new EntryEventImpl(region, op, key, newValue, callbackArgument, originRemote,
        distributedMember, generateCallbacks, eventID);
  }

  /**
   * Creates and returns an EntryEventImpl. Generates and assigns a bucket id to the EntryEventImpl
   * if the region parameter is a PartitionedRegion.
   */
  @Retained
  public static EntryEventImpl create(InternalRegion region, Operation op, Object key,
      boolean originRemote, DistributedMember distributedMember, boolean generateCallbacks,
      boolean fromRILocalDestroy) {
    return new EntryEventImpl(region, op, key, originRemote, distributedMember, generateCallbacks,
        fromRILocalDestroy);
  }

  /**
   * Creates and returns an EntryEventImpl. Generates and assigns a bucket id to the EntryEventImpl
   * if the region parameter is a PartitionedRegion.
   *
   * This creator does not specify the oldValue as this will be filled in later as part of an
   * operation on the region, or lets it default to null.
   */
  @Retained
  public static EntryEventImpl create(final InternalRegion region, Operation op, Object key,
      @Retained(ENTRY_EVENT_NEW_VALUE) Object newVal, Object callbackArgument, boolean originRemote,
      DistributedMember distributedMember, boolean generateCallbacks, boolean initializeId) {
    return new EntryEventImpl(region, op, key, newVal, callbackArgument, originRemote,
        distributedMember, generateCallbacks, initializeId);
  }

  /**
   * Creates a PutAllEvent given the distributed operation, the region, and the entry data.
   *
   * @since GemFire 5.0
   */
  @Retained
  static EntryEventImpl createPutAllEvent(DistributedPutAllOperation putAllOp,
      InternalRegion region, Operation entryOp, Object entryKey,
      @Retained(ENTRY_EVENT_NEW_VALUE) Object entryNewValue) {
    @Retained
    EntryEventImpl e;
    if (putAllOp != null) {
      EntryEventImpl event = putAllOp.getBaseEvent();
      if (event.isBridgeEvent()) {
        e = EntryEventImpl.create(region, entryOp, entryKey, entryNewValue,
            event.getRawCallbackArgument(), false, event.distributedMember,
            event.isGenerateCallbacks());
        e.setContext(event.getContext());
      } else {
        e = EntryEventImpl.create(region, entryOp, entryKey, entryNewValue,
            event.getCallbackArgument(), false, region.getMyId(), event.isGenerateCallbacks());
      }
      e.setPossibleDuplicate(event.isPossibleDuplicate());

    } else {
      e = EntryEventImpl.create(region, entryOp, entryKey, entryNewValue, null, false,
          region.getMyId(), true);
    }

    e.putAllOp = putAllOp;
    return e;
  }

  @Retained
  protected static EntryEventImpl createRemoveAllEvent(DistributedRemoveAllOperation op,
      InternalRegion region, Object entryKey) {
    @Retained
    EntryEventImpl e;
    final Operation entryOp = Operation.REMOVEALL_DESTROY;
    if (op != null) {
      EntryEventImpl event = op.getBaseEvent();
      if (event.isBridgeEvent()) {
        e = EntryEventImpl.create(region, entryOp, entryKey, null, event.getRawCallbackArgument(),
            false, event.distributedMember, event.isGenerateCallbacks());
        e.setContext(event.getContext());
      } else {
        e = EntryEventImpl.create(region, entryOp, entryKey, null, event.getCallbackArgument(),
            false, region.getMyId(), event.isGenerateCallbacks());
      }
      e.setPossibleDuplicate(event.isPossibleDuplicate());

    } else {
      e = EntryEventImpl.create(region, entryOp, entryKey, null, null, false, region.getMyId(),
          true);
    }

    e.removeAllOp = op;
    return e;
  }

  public boolean isBulkOpInProgress() {
    return getPutAllOperation() != null || getRemoveAllOperation() != null;
  }

  /** return the putAll operation for this event, if any */
  public DistributedPutAllOperation getPutAllOperation() {
    return putAllOp;
  }

  public DistributedPutAllOperation setPutAllOperation(DistributedPutAllOperation nv) {
    DistributedPutAllOperation result = putAllOp;
    if (nv != null && nv.getBaseEvent() != null) {
      setCallbackArgument(nv.getBaseEvent().getCallbackArgument());
    }
    putAllOp = nv;
    return result;
  }

  public DistributedRemoveAllOperation getRemoveAllOperation() {
    return removeAllOp;
  }

  public DistributedRemoveAllOperation setRemoveAllOperation(DistributedRemoveAllOperation nv) {
    DistributedRemoveAllOperation result = removeAllOp;
    if (nv != null && nv.getBaseEvent() != null) {
      setCallbackArgument(nv.getBaseEvent().getCallbackArgument());
    }
    removeAllOp = nv;
    return result;
  }

  private boolean testEventFlag(short mask) {
    return EventFlags.isSet(eventFlags, mask);
  }

  private void setEventFlag(short mask, boolean on) {
    eventFlags = EventFlags.set(eventFlags, mask, on);
  }

  @Override
  public DistributedMember getDistributedMember() {
    return distributedMember;
  }

  /////////////////////// INTERNAL BOOLEAN SETTERS
  public void setOriginRemote(boolean b) {
    setEventFlag(EventFlags.FLAG_ORIGIN_REMOTE, b);
  }

  public void setLocalInvalid(boolean b) {
    setEventFlag(EventFlags.FLAG_LOCAL_INVALID, b);
  }

  public void setGenerateCallbacks(boolean b) {
    setEventFlag(EventFlags.FLAG_GENERATE_CALLBACKS, b);
  }

  /** set the the flag telling whether callbacks should be invoked for a partitioned region */
  public void setInvokePRCallbacks(boolean b) {
    setEventFlag(EventFlags.FLAG_INVOKE_PR_CALLBACKS, b);
  }

  /** get the flag telling whether callbacks should be invoked for a partitioned region */
  public boolean getInvokePRCallbacks() {
    return testEventFlag(EventFlags.FLAG_INVOKE_PR_CALLBACKS);
  }

  public boolean getInhibitDistribution() {
    return testEventFlag(EventFlags.FLAG_INHIBIT_DISTRIBUTION);
  }

  public void setInhibitDistribution(boolean b) {
    setEventFlag(EventFlags.FLAG_INHIBIT_DISTRIBUTION, b);
  }

  /** was the entry destroyed or missing and allowed to be destroyed again? */
  public boolean getIsRedestroyedEntry() {
    return testEventFlag(EventFlags.FLAG_REDESTROYED_TOMBSTONE);
  }

  public void setIsRedestroyedEntry(boolean b) {
    setEventFlag(EventFlags.FLAG_REDESTROYED_TOMBSTONE, b);
  }

  public void isConcurrencyConflict(boolean b) {
    setEventFlag(EventFlags.FLAG_CONCURRENCY_CONFLICT, b);
  }

  public boolean isConcurrencyConflict() {
    return testEventFlag(EventFlags.FLAG_CONCURRENCY_CONFLICT);
  }

  /** set the DistributionMessage that caused this event */
  public void setCausedByMessage(DistributionMessage msg) {
    causedByMessage = msg;
  }

  /**
   * get the PartitionMessage that caused this event, or null if the event was not caused by a
   * PartitionMessage
   */
  public PartitionMessage getPartitionMessage() {
    if (causedByMessage != null && causedByMessage instanceof PartitionMessage) {
      return (PartitionMessage) causedByMessage;
    }
    return null;
  }

  /**
   * get the RemoteOperationMessage that caused this event, or null if the event was not caused by a
   * RemoteOperationMessage
   */
  public RemoteOperationMessage getRemoteOperationMessage() {
    if (causedByMessage != null && causedByMessage instanceof RemoteOperationMessage) {
      return (RemoteOperationMessage) causedByMessage;
    }
    return null;
  }

  /////////////// BOOLEAN GETTERS
  public boolean isLocalLoad() {
    return op.isLocalLoad();
  }

  public boolean isNetSearch() {
    return op.isNetSearch();
  }

  public boolean isNetLoad() {
    return op.isNetLoad();
  }

  public boolean isDistributed() {
    return op.isDistributed();
  }

  public boolean isExpiration() {
    return op.isExpiration();
  }

  public boolean isEviction() {
    return op.isEviction();
  }

  public void setEvicted() {
    isEvicted = true;
  }

  public boolean isEvicted() {
    return isEvicted;
  }

  public boolean hasRetried() {
    return hasRetried;
  }

  public void setRetried(boolean retried) {
    hasRetried = retried;
  }

  public boolean isPendingSecondaryExpireDestroy() {
    return isPendingSecondaryExpireDestroy;
  }

  public void setPendingSecondaryExpireDestroy(boolean value) {
    isPendingSecondaryExpireDestroy = value;
  }

  // Note that isOriginRemote is sometimes set to false even though the event
  // was received from a peer. This is done to force distribution of the
  // message to peers and to cause concurrency version stamping to be performed.
  // This is done by all one-hop operations, like RemoteInvalidateMessage.
  @Override
  public boolean isOriginRemote() {
    return testEventFlag(EventFlags.FLAG_ORIGIN_REMOTE);
  }

  /* return whether this event originated from a WAN gateway and carries a WAN version tag */
  public boolean isFromWANAndVersioned() {
    return (versionTag != null && versionTag.isGatewayTag());
  }

  /* return whether this event originated in a client and carries a version tag */
  public boolean isFromBridgeAndVersioned() {
    return (context != null) && (versionTag != null);
  }

  @Override
  public boolean isGenerateCallbacks() {
    return testEventFlag(EventFlags.FLAG_GENERATE_CALLBACKS);
  }

  public void setNewEventId(DistributedSystem sys) {
    Assert.assertTrue(eventID == null, "Double setting event id");
    EventID newID = new EventID(sys);
    if (eventID != null) {
      if (logger.isTraceEnabled(LogMarker.BRIDGE_SERVER_VERBOSE)) {
        logger.trace(LogMarker.BRIDGE_SERVER_VERBOSE, "Replacing event ID with {} in event {}",
            newID, this);
      }
    }
    eventID = newID;
  }

  public void reserveNewEventId(DistributedSystem sys, int count) {
    Assert.assertTrue(eventID == null, "Double setting event id");
    eventID = new EventID(sys);
    if (count > 1) {
      eventID.reserveSequenceId(count - 1);
    }
  }

  public void setEventId(EventID id) {
    eventID = id;
  }

  /**
   * Return the event id, if any
   *
   * @return null if no event id has been set
   */
  @Override
  public EventID getEventId() {
    return eventID;
  }

  @Override
  public boolean isBridgeEvent() {
    return hasClientOrigin();
  }

  @Override
  public boolean hasClientOrigin() {
    return getContext() != null;
  }

  /**
   * sets the ID of the client that initiated this event
   */
  public void setContext(ClientProxyMembershipID contx) {
    Assert.assertTrue(contx != null);
    context = contx;
  }

  /**
   * gets the ID of the client that initiated this event. Null if a server-initiated event
   */
  @Override
  public ClientProxyMembershipID getContext() {
    return context;
  }

  public boolean isLocalInvalid() {
    return testEventFlag(EventFlags.FLAG_LOCAL_INVALID);
  }

  /////////////////////////////////////////////////

  /**
   * Returns the key.
   *
   * @return the key.
   */
  @Override
  public Object getKey() {
    return keyInfo.getKey();
  }

  /**
   * Returns the value in the cache prior to this event. When passed to an event handler after an
   * event occurs, this value reflects the value that was in the cache in this VM, not necessarily
   * the value that was in the cache VM that initiated the operation.
   *
   * @return the value in the cache prior to this event.
   */
  @Override
  public Object getOldValue() {
    try {
      if (isOriginRemote() && getRegion().isProxy()) {
        return null;
      }
      @Unretained
      Object ov = handleNotAvailableOldValue();
      if (ov != null) {
        boolean doCopyOnRead = getRegion().isCopyOnRead();
        if (ov instanceof CachedDeserializable) {
          return callWithOffHeapLock((CachedDeserializable) ov, oldValueCD -> {
            if (doCopyOnRead) {
              return oldValueCD.getDeserializedWritableCopy(getRegion(), re);
            } else {
              return oldValueCD.getDeserializedValue(getRegion(), re);
            }
          });
        } else {
          if (doCopyOnRead) {
            return CopyHelper.copy(ov);
          } else {
            return ov;
          }
        }
      }
      return null;
    } catch (IllegalArgumentException i) {
      IllegalArgumentException iae = new IllegalArgumentException(String.format("%s",
          "Error while deserializing value for key=" + getKey()), i);
      throw iae;
    }
  }

  /**
   * returns the old value after handling one this is NOT_AVAILABLE. If the old value is
   * NOT_AVAILABLE then it may try to read it from disk. If it can't read an unavailable old value
   * from disk then it will return null instead of NOT_AVAILABLE.
   */
  @Unretained(ENTRY_EVENT_OLD_VALUE)
  private Object handleNotAvailableOldValue() {
    @Unretained
    Object result = basicGetOldValue();
    if (result != Token.NOT_AVAILABLE) {
      return result;
    }
    if (getReadOldValueFromDisk()) {
      try {
        result = getRegion().getValueInVMOrDiskWithoutFaultIn(getKey());
      } catch (EntryNotFoundException ex) {
        result = null;
      }
    }
    result = AbstractRegion.handleNotAvailable(result);
    return result;
  }

  /**
   * If true then when getOldValue is called if the NOT_AVAILABLE is found then an attempt will be
   * made to read the old value from disk without faulting it in. Should only be set to true when
   * product is calling a method on a CacheWriter.
   */
  private boolean readOldValueFromDisk;

  public boolean getReadOldValueFromDisk() {
    return readOldValueFromDisk;
  }

  public void setReadOldValueFromDisk(boolean v) {
    readOldValueFromDisk = v;
  }

  /**
   * Like getRawNewValue except that if the result is an off-heap reference then copy it to the
   * heap. Note: to prevent the heap copy use getRawNewValue instead
   */
  public Object getRawNewValueAsHeapObject() {
    Object result = basicGetNewValue();
    if (mayHaveOffHeapReferences()) {
      result = OffHeapHelper.copyIfNeeded(result, getRegion().getCache());
    }
    return result;
  }

  /**
   * If new value is off-heap return the StoredObject form (unretained OFF_HEAP_REFERENCE). Its
   * refcount is not inced by this call and the returned object can only be safely used for the
   * lifetime of the EntryEventImpl instance that returned the value. Else return the raw form.
   */
  @Unretained(ENTRY_EVENT_NEW_VALUE)
  public Object getRawNewValue() {
    return basicGetNewValue();
  }

  @Unretained(ENTRY_EVENT_NEW_VALUE)
  public Object getValue() {
    return basicGetNewValue();
  }

  @Released(ENTRY_EVENT_NEW_VALUE)
  protected void basicSetNewValue(@Retained(ENTRY_EVENT_NEW_VALUE) Object v,
      boolean clearCachedSerializedAndBytes) {
    if (v == newValue) {
      return;
    }
    if (mayHaveOffHeapReferences()) {
      if (offHeapOk) {
        OffHeapHelper.releaseAndTrackOwner(newValue, this);
      }
      if (StoredObject.isOffHeapReference(v)) {
        ReferenceCountHelper.setReferenceCountOwner(this);
        if (!((StoredObject) v).retain()) {
          ReferenceCountHelper.setReferenceCountOwner(null);
          newValue = null;
          return;
        }
        ReferenceCountHelper.setReferenceCountOwner(null);
      }
    }
    newValue = v;
    if (clearCachedSerializedAndBytes) {
      newValueBytes = null;
      cachedSerializedNewValue = null;
    }
  }

  private void generateNewValueFromBytesIfNeeded() {
    if (newValue != null) {
      // no need to generate a new value
      return;
    }
    byte[] bytes = newValueBytes;
    if (bytes != null) {
      newValue = CachedDeserializableFactory.create(bytes, getRegion().getCache());
    }
  }

  @Override
  @Unretained
  public Object basicGetNewValue() {
    generateNewValueFromBytesIfNeeded();
    Object result = newValue;
    if (!offHeapOk && isOffHeapReference(result)) {
      // this.region.getCache().getLogger().info("DEBUG new value already freed " +
      // System.identityHashCode(result));
      throw new IllegalStateException(
          "Attempt to access off heap value after the EntryEvent was released.");
    }
    return result;
  }

  private boolean isOffHeapReference(Object ref) {
    return mayHaveOffHeapReferences() && StoredObject.isOffHeapReference(ref);
  }

  private class OldValueOwner {
    private EntryEventImpl getEvent() {
      return EntryEventImpl.this;
    }

    @Override
    public int hashCode() {
      return getEvent().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof OldValueOwner) {
        return getEvent().equals(((OldValueOwner) obj).getEvent());
      } else {
        return false;
      }
    }

    @Override
    public String toString() {
      return "OldValueOwner " + getEvent().toString();
    }
  }

  /**
   * Note if v might be an off-heap reference that you did not retain for this EntryEventImpl then
   * call retainsAndSetOldValue instead of this method.
   *
   * @param v the caller should have already retained this off-heap reference.
   */
  @Released(ENTRY_EVENT_OLD_VALUE)
  void basicSetOldValue(@Unretained(ENTRY_EVENT_OLD_VALUE) Object v) {
    @Released
    final Object curOldValue = oldValue;
    if (v == curOldValue) {
      return;
    }
    if (offHeapOk && mayHaveOffHeapReferences()) {
      if (ReferenceCountHelper.trackReferenceCounts()) {
        OffHeapHelper.releaseAndTrackOwner(curOldValue, new OldValueOwner());
      } else {
        OffHeapHelper.release(curOldValue);
      }
    }

    oldValue = v;
    oldValueBytes = null;
  }

  @Released(ENTRY_EVENT_OLD_VALUE)
  private void retainAndSetOldValue(@Retained(ENTRY_EVENT_OLD_VALUE) Object v) {
    if (v == oldValue) {
      return;
    }
    if (isOffHeapReference(v)) {
      StoredObject so = (StoredObject) v;
      if (ReferenceCountHelper.trackReferenceCounts()) {
        ReferenceCountHelper.setReferenceCountOwner(new OldValueOwner());
        boolean couldNotRetain = (!so.retain());
        ReferenceCountHelper.setReferenceCountOwner(null);
        if (couldNotRetain) {
          oldValue = null;
          oldValueBytes = null;
          return;
        }
      } else {
        if (!so.retain()) {
          oldValue = null;
          oldValueBytes = null;
          return;
        }
      }
    }
    basicSetOldValue(v);
  }

  @Unretained(ENTRY_EVENT_OLD_VALUE)
  Object basicGetOldValue() {
    @Unretained(ENTRY_EVENT_OLD_VALUE)
    Object result = oldValue;
    if (result == null) {
      byte[] bytes = oldValueBytes;
      if (bytes != null) {
        result = CachedDeserializableFactory.create(bytes, getRegion().getCache());
        oldValue = result;
      }
    }
    if (!offHeapOk && isOffHeapReference(result)) {
      // this.region.getCache().getLogger().info("DEBUG old value already freed " +
      // System.identityHashCode(result));
      throw new IllegalStateException(
          "Attempt to access off heap value after the EntryEvent was released.");
    }
    return result;
  }

  /**
   * Like getRawOldValue except that if the result is an off-heap reference then copy it to the
   * heap. To avoid the heap copy use getRawOldValue instead.
   */
  public Object getRawOldValueAsHeapObject() {
    Object result = basicGetOldValue();
    if (mayHaveOffHeapReferences()) {
      result = OffHeapHelper.copyIfNeeded(result, getRegion().getCache());
    }
    return result;
  }

  /*
   * If the old value is off-heap return the StoredObject form (unretained OFF_HEAP_REFERENCE). Its
   * refcount is not inced by this call and the returned object can only be safely used for the
   * lifetime of the EntryEventImpl instance that returned the value. Else return the raw form.
   */
  @Unretained
  public Object getRawOldValue() {
    return basicGetOldValue();
  }

  /**
   * Just like getRawOldValue except if the raw old value is off-heap deserialize it.
   */
  @Unretained(ENTRY_EVENT_OLD_VALUE)
  public Object getOldValueAsOffHeapDeserializedOrRaw() {
    Object result = basicGetOldValue();
    if (mayHaveOffHeapReferences() && result instanceof StoredObject) {
      result = ((CachedDeserializable) result).getDeserializedForReading();
    }
    return AbstractRegion.handleNotAvailable(result); // fixes 49499
  }

  /**
   * Added this function to expose isCopyOnRead function to the child classes of EntryEventImpl
   *
   */
  protected boolean isRegionCopyOnRead() {
    return getRegion().isCopyOnRead();
  }

  /**
   * Returns the value in the cache after this event.
   *
   * @return the value in the cache after this event.
   */
  @Override
  public Object getNewValue() {

    boolean doCopyOnRead = getRegion().isCopyOnRead();
    Object nv = basicGetNewValue();
    if (nv != null) {
      if (nv == Token.NOT_AVAILABLE) {
        // I'm not sure this can even happen
        return AbstractRegion.handleNotAvailable(nv);
      }
      if (nv instanceof CachedDeserializable) {
        return callWithOffHeapLock((CachedDeserializable) nv, newValueCD -> {
          Object v = null;
          if (doCopyOnRead) {
            v = newValueCD.getDeserializedWritableCopy(getRegion(), re);
          } else {
            v = newValueCD.getDeserializedValue(getRegion(), re);
          }
          assert !(v instanceof CachedDeserializable) : "for key " + getKey()
              + " found nested CachedDeserializable";
          return v;
        });
      } else {
        if (doCopyOnRead) {
          return CopyHelper.copy(nv);
        } else {
          return nv;
        }
      }
    }
    return null;
  }

  /**
   * Invoke the given function with a lock if the given value is offheap.
   *
   * @return the value returned from invoking the function
   */
  private <T, R> R callWithOffHeapLock(T value, Function<T, R> function) {
    if (isOffHeapReference(value)) {
      synchronized (offHeapLock) {
        if (!offHeapOk) {
          throw new IllegalStateException(
              "Attempt to access off heap value after the EntryEvent was released.");
        }
        return function.apply(value);
      }
    } else {
      return function.apply(value);
    }
  }

  private final Object offHeapLock;

  public String getNewValueStringForm() {
    return StringUtils.forceToString(basicGetNewValue());
  }

  public String getOldValueStringForm() {
    return StringUtils.forceToString(basicGetOldValue());
  }

  /** Set a deserialized value */
  public void setNewValue(@Retained(ENTRY_EVENT_NEW_VALUE) Object obj) {
    basicSetNewValue(obj, true);
  }

  @Override
  public TransactionId getTransactionId() {
    return txId;
  }

  public void setTransactionId(TransactionId txId) {
    this.txId = (TXId) txId;
  }

  /**
   * Answer true if this event resulted from a loader.
   *
   * @return true if isLocalLoad or isNetLoad
   */
  public boolean isLoad() {
    return op.isLoad();
  }

  public void setRegion(InternalRegion r) {
    region = r;
  }

  /**
   * @see org.apache.geode.cache.CacheEvent#getRegion()
   */
  @Override
  public InternalRegion getRegion() {
    return region;
  }

  @Override
  public Operation getOperation() {
    return op;
  }

  public void setOperation(Operation op) {
    this.op = op;
    PartitionMessage prm = getPartitionMessage();
    if (prm != null) {
      prm.setOperation(this.op);
    }
  }

  /**
   * @see org.apache.geode.cache.CacheEvent#getCallbackArgument()
   */
  @Override
  public Object getCallbackArgument() {
    Object result = keyInfo.getCallbackArg();
    while (result instanceof WrappedCallbackArgument) {
      WrappedCallbackArgument wca = (WrappedCallbackArgument) result;
      result = wca.getOriginalCallbackArg();
    }
    if (result == Token.NOT_AVAILABLE) {
      result = AbstractRegion.handleNotAvailable(result);
    }
    return result;
  }

  @Override
  public boolean isCallbackArgumentAvailable() {
    return getRawCallbackArgument() != Token.NOT_AVAILABLE;
  }

  /**
   * Returns the value of the EntryEventImpl field. This is for internal use only. Customers should
   * always call {@link #getCallbackArgument}
   *
   * @since GemFire 5.5
   */
  public Object getRawCallbackArgument() {
    return keyInfo.getCallbackArg();
  }

  /**
   * Sets the value of raw callback argument field.
   */
  public void setRawCallbackArgument(Object newCallbackArgument) {
    keyInfo.setCallbackArg(newCallbackArgument);
  }

  public void setCallbackArgument(Object newCallbackArgument) {
    if (keyInfo.getCallbackArg() instanceof WrappedCallbackArgument) {
      ((WrappedCallbackArgument) keyInfo.getCallbackArg())
          .setOriginalCallbackArgument(newCallbackArgument);
    } else {
      keyInfo.setCallbackArg(newCallbackArgument);
    }
  }

  /**
   * @return null if new value is not serialized; otherwise returns a SerializedCacheValueImpl
   *         containing the new value.
   */
  @Override
  public SerializedCacheValue<?> getSerializedNewValue() {
    // In the case where there is a delta that has not been applied yet,
    // do not apply it here since it would not produce a serialized new
    // value (return null instead to indicate the new value is not
    // in serialized form).
    @Unretained(ENTRY_EVENT_NEW_VALUE)
    final Object tmp = basicGetNewValue();
    if (tmp instanceof CachedDeserializable) {
      CachedDeserializable cd = (CachedDeserializable) tmp;
      if (!cd.isSerialized()) {
        return null;
      }
      byte[] bytes = newValueBytes;
      if (bytes == null) {
        bytes = cachedSerializedNewValue;
      }
      return new SerializedCacheValueImpl(this, getRegion(), re, cd, bytes);
    } else {
      // Note we return null even if cachedSerializedNewValue is not null.
      // This is because some callers of this method use it to indicate
      // that a CacheDeserializable should be created during deserialization.
      return null;
    }
  }

  /**
   * Implement this interface if you want to call {@link #exportNewValue}.
   *
   *
   */
  public interface NewValueImporter {
    /**
     * @return true if the importer prefers the value to be in serialized form.
     */
    boolean prefersNewSerialized();

    /**
     * Only return true if the importer can use the value before the event that exported it is
     * released. If false is returned then off-heap values will be copied to the heap for the
     * importer.
     *
     * @return true if the importer can deal with the value being an unretained OFF_HEAP_REFERENCE.
     */
    boolean isUnretainedNewReferenceOk();

    /**
     * Import a new value that is currently in object form.
     *
     * @param nv the new value to import; unretained if isUnretainedNewReferenceOk returns true
     * @param isSerialized true if the imported new value represents data that needs to be
     *        serialized; false if the imported new value is a simple sequence of bytes.
     */
    void importNewObject(@Unretained(ENTRY_EVENT_NEW_VALUE) Object nv, boolean isSerialized);

    /**
     * Import a new value that is currently in byte array form.
     *
     * @param nv the new value to import
     * @param isSerialized true if the imported new value represents data that needs to be
     *        serialized; false if the imported new value is a simple sequence of bytes.
     */
    void importNewBytes(byte[] nv, boolean isSerialized);
  }

  /**
   * Export the event's new value to the given importer.
   */
  public void exportNewValue(NewValueImporter importer) {
    final boolean prefersSerialized = importer.prefersNewSerialized();
    if (prefersSerialized) {
      byte[] serializedNewValue = getCachedSerializedNewValue();
      if (serializedNewValue == null) {
        serializedNewValue = newValueBytes;
      }
      if (serializedNewValue != null) {
        importer.importNewBytes(serializedNewValue, true);
        return;
      }
    }
    @Unretained(ENTRY_EVENT_NEW_VALUE)
    final Object nv = getRawNewValue();
    if (nv instanceof StoredObject) {
      @Unretained(ENTRY_EVENT_NEW_VALUE)
      final StoredObject so = (StoredObject) nv;
      final boolean isSerialized = so.isSerialized();
      if (importer.isUnretainedNewReferenceOk()) {
        importer.importNewObject(nv, isSerialized);
      } else if (!isSerialized || prefersSerialized) {
        byte[] bytes = so.getValueAsHeapByteArray();
        importer.importNewBytes(bytes, isSerialized);
        if (isSerialized) {
          setCachedSerializedNewValue(bytes);
        }
      } else {
        importer.importNewObject(so.getValueAsDeserializedHeapObject(), true);
      }
    } else if (nv instanceof byte[]) {
      importer.importNewBytes((byte[]) nv, false);
    } else if (nv instanceof CachedDeserializable) {
      CachedDeserializable cd = (CachedDeserializable) nv;
      Object cdV = cd.getValue();
      if (cdV instanceof byte[]) {
        importer.importNewBytes((byte[]) cdV, true);
        setCachedSerializedNewValue((byte[]) cdV);
      } else {
        importer.importNewObject(cdV, true);
      }
    } else {
      importer.importNewObject(nv, true);
    }
  }

  /**
   * Implement this interface if you want to call {@link #exportOldValue}.
   *
   *
   */
  public interface OldValueImporter {
    /**
     * @return true if the importer prefers the value to be in serialized form.
     */
    boolean prefersOldSerialized();

    /**
     * Only return true if the importer can use the value before the event that exported it is
     * released.
     *
     * @return true if the importer can deal with the value being an unretained OFF_HEAP_REFERENCE.
     */
    boolean isUnretainedOldReferenceOk();

    /**
     * @return return true if you want the old value to possibly be an instanceof
     *         CachedDeserializable; false if you want the value contained in a
     *         CachedDeserializable.
     */
    boolean isCachedDeserializableValueOk();

    /**
     * Import an old value that is currently in object form.
     *
     * @param ov the old value to import; unretained if isUnretainedOldReferenceOk returns true
     * @param isSerialized true if the imported old value represents data that needs to be
     *        serialized; false if the imported old value is a simple sequence of bytes.
     */
    void importOldObject(@Unretained(ENTRY_EVENT_OLD_VALUE) Object ov, boolean isSerialized);

    /**
     * Import an old value that is currently in byte array form.
     *
     * @param ov the old value to import
     * @param isSerialized true if the imported old value represents data that needs to be
     *        serialized; false if the imported old value is a simple sequence of bytes.
     */
    void importOldBytes(byte[] ov, boolean isSerialized);
  }

  /**
   * Export the event's old value to the given importer.
   */
  public void exportOldValue(OldValueImporter importer) {
    final boolean prefersSerialized = importer.prefersOldSerialized();
    if (prefersSerialized) {
      if (oldValueBytes != null) {
        importer.importOldBytes(oldValueBytes, true);
        return;
      }
    }
    @Unretained(ENTRY_EVENT_OLD_VALUE)
    final Object ov = getRawOldValue();
    if (ov instanceof StoredObject) {
      final StoredObject so = (StoredObject) ov;
      final boolean isSerialized = so.isSerialized();
      if (importer.isUnretainedOldReferenceOk()) {
        importer.importOldObject(ov, isSerialized);
      } else if (!isSerialized || prefersSerialized) {
        importer.importOldBytes(so.getValueAsHeapByteArray(), isSerialized);
      } else {
        importer.importOldObject(so.getValueAsDeserializedHeapObject(), true);
      }
    } else if (ov instanceof byte[]) {
      importer.importOldBytes((byte[]) ov, false);
    } else if (!importer.isCachedDeserializableValueOk() && ov instanceof CachedDeserializable) {
      CachedDeserializable cd = (CachedDeserializable) ov;
      Object cdV = cd.getValue();
      if (cdV instanceof byte[]) {
        importer.importOldBytes((byte[]) cdV, true);
      } else {
        importer.importOldObject(cdV, true);
      }
    } else {
      importer.importOldObject(AbstractRegion.handleNotAvailable(ov), true);
    }
  }

  /**
   * Just like getRawNewValue(true) except if the raw new value is off-heap deserialize it.
   */
  @Unretained(ENTRY_EVENT_NEW_VALUE)
  public Object getNewValueAsOffHeapDeserializedOrRaw() {
    Object result = getRawNewValue();
    if (mayHaveOffHeapReferences() && result instanceof StoredObject) {
      result = ((CachedDeserializable) result).getDeserializedForReading();
    }
    return AbstractRegion.handleNotAvailable(result); // fixes 49499
  }

  /**
   * If the new value is stored off-heap return a retained OFF_HEAP_REFERENCE (caller must release).
   *
   * @return a retained OFF_HEAP_REFERENCE if the new value is off-heap; otherwise returns null
   */
  @Retained(ENTRY_EVENT_NEW_VALUE)
  public StoredObject getOffHeapNewValue() {
    return convertToStoredObject(basicGetNewValue());
  }

  /**
   * If the old value is stored off-heap return a retained OFF_HEAP_REFERENCE (caller must release).
   *
   * @return a retained OFF_HEAP_REFERENCE if the old value is off-heap; otherwise returns null
   */
  @Retained(ENTRY_EVENT_OLD_VALUE)
  public StoredObject getOffHeapOldValue() {
    return convertToStoredObject(basicGetOldValue());
  }

  private StoredObject convertToStoredObject(final Object tmp) {
    if (!mayHaveOffHeapReferences()) {
      return null;
    }
    if (!(tmp instanceof StoredObject)) {
      return null;
    }
    StoredObject result = (StoredObject) tmp;
    if (!result.retain()) {
      return null;
    }
    return result;
  }

  public Object getDeserializedValue() {
    final Object val = basicGetNewValue();
    if (val instanceof CachedDeserializable) {
      return ((CachedDeserializable) val).getDeserializedForReading();
    } else {
      return val;
    }
  }

  public byte[] getSerializedValue() {
    if (newValueBytes == null) {
      final Object val;
      val = basicGetNewValue();
      if (val instanceof byte[]) {
        return (byte[]) val;
      } else if (val instanceof CachedDeserializable) {
        return ((CachedDeserializable) val).getSerializedValue();
      }
      try {
        return CacheServerHelper.serialize(val);
      } catch (IOException ioe) {
        throw new GemFireIOException("unexpected exception", ioe);
      }
    } else {
      return newValueBytes;
    }
  }

  /**
   * Forces this entry's new value to be in serialized form.
   *
   * @since GemFire 5.0.2
   */
  public void makeSerializedNewValue() {
    makeSerializedNewValue(false);
  }

  /**
   * @param isSynced true if RegionEntry currently under synchronization
   */
  private void makeSerializedNewValue(boolean isSynced) {
    Object obj = basicGetNewValue();

    // ezoerner:20080611 In the case where there is an unapplied
    // delta, do not apply the delta or serialize yet unless entry is
    // under synchronization (isSynced is true)
    if (isSynced) {
      setSerializationDeferred(false);
    }
    basicSetNewValue(getCachedDeserializable(obj, this), false);
  }

  public static Object getCachedDeserializable(Object obj, EntryEventImpl ev) {
    if (obj instanceof byte[] || obj == null || obj instanceof CachedDeserializable
        || obj == Token.NOT_AVAILABLE || Token.isInvalidOrRemoved(obj)
        // don't serialize delta object already serialized
        || obj instanceof Delta) { // internal delta
      return obj;
    }
    final CachedDeserializable cd;
    // avoid unneeded serialization of byte[][] that
    // will end up being deserialized in any case (serialization is cheap
    // for byte[][] anyways)
    if (obj instanceof byte[][]) {
      int objSize = Sizeable.PER_OBJECT_OVERHEAD + 4;
      for (byte[] bytes : (byte[][]) obj) {
        if (bytes != null) {
          objSize += CachedDeserializableFactory.getByteSize(bytes);
        } else {
          objSize += Sizeable.PER_OBJECT_OVERHEAD;
        }
      }
      cd = CachedDeserializableFactory.create(obj, objSize, ev.getRegion().getCache());
    } else {
      final byte[] b = serialize(obj);
      cd = CachedDeserializableFactory.create(b, ev.getRegion().getCache());
      if (ev != null) {
        ev.newValueBytes = b;
        ev.cachedSerializedNewValue = b;
      }
    }
    return cd;
  }

  @Override
  public void setCachedSerializedNewValue(byte[] v) {
    cachedSerializedNewValue = v;
  }

  @Override
  public byte[] getCachedSerializedNewValue() {
    return cachedSerializedNewValue;
  }

  public void setSerializedNewValue(byte[] serializedValue) {
    Object newVal = null;
    if (serializedValue != null) {
      newVal = CachedDeserializableFactory.create(serializedValue, getRegion().getCache());
    }
    basicSetNewValue(newVal, false);
    newValueBytes = serializedValue;
    cachedSerializedNewValue = serializedValue;
  }

  public void setSerializedOldValue(byte[] serializedOldValue) {
    final Object ov;
    if (serializedOldValue != null) {
      ov = CachedDeserializableFactory.create(serializedOldValue, getRegion().getCache());
    } else {
      ov = null;
    }
    retainAndSetOldValue(ov);
    oldValueBytes = serializedOldValue;
  }

  /**
   * If true (the default) then preserve old values in events. If false then mark non-null values as
   * being NOT_AVAILABLE.
   */
  private static final boolean EVENT_OLD_VALUE =
      !Boolean.getBoolean(GeodeGlossary.GEMFIRE_PREFIX + "disable-event-old-value");

  protected boolean areOldValuesEnabled() {
    return EVENT_OLD_VALUE;
  }

  void putExistingEntry(final InternalRegion owner, RegionEntry entry)
      throws RegionClearedException {
    putExistingEntry(owner, entry, false, null);
  }

  /**
   * Put a newValue into the given, write synced, existing, region entry. Sets oldValue in event if
   * hasn't been set yet.
   *
   * @param oldValueForDelta Used by Delta Propagation feature
   */
  public void putExistingEntry(final InternalRegion owner, final RegionEntry reentry,
      boolean requireOldValue, Object oldValueForDelta) throws RegionClearedException {
    makeUpdate();
    // only set oldValue if it hasn't already been set to something
    if (oldValue == null && oldValueBytes == null) {
      if (!reentry.isInvalidOrRemoved()) {
        if (requireOldValue || areOldValuesEnabled() || getRegion() instanceof HARegion) {
          @Retained
          Object ov;
          if (ReferenceCountHelper.trackReferenceCounts()) {
            ReferenceCountHelper.setReferenceCountOwner(new OldValueOwner());
            ov = reentry.getValueRetain(owner, true);
            ReferenceCountHelper.setReferenceCountOwner(null);
          } else {
            ov = reentry.getValueRetain(owner, true);
          }
          if (ov == null) {
            ov = Token.NOT_AVAILABLE;
          }
          // ov has already been retained so call basicSetOldValue instead of retainAndSetOldValue
          basicSetOldValue(ov);
        } else {
          basicSetOldValue(Token.NOT_AVAILABLE);
        }
      }
    }
    if (oldValue == Token.NOT_AVAILABLE) {
      FilterProfile fp = getRegion().getFilterProfile();
      if (op.guaranteesOldValue()
          || (fp != null /* #41532 */ && fp.entryRequiresOldValue(getKey()))) {
        setOldValueForQueryProcessing();
      }
    }

    // setNewValueInRegion(null);
    setNewValueInRegion(owner, reentry, oldValueForDelta);
  }

  /**
   * If we are currently a create op then turn us into an update
   *
   * @since GemFire 5.0
   */
  public void makeUpdate() {
    setOperation(op.getCorrespondingUpdateOp());
  }

  /**
   * If we are currently an update op then turn us into a create
   *
   * @since GemFire 5.0
   */
  public void makeCreate() {
    setOperation(op.getCorrespondingCreateOp());
  }

  /**
   * Put a newValue into the given, write synced, new, region entry.
   */
  public void putNewEntry(final InternalRegion owner, final RegionEntry reentry)
      throws RegionClearedException {
    if (!op.guaranteesOldValue()) { // preserves oldValue for CM ops in clients
      basicSetOldValue(null);
    }
    makeCreate();
    setNewValueInRegion(owner, reentry, null);
  }

  @Override
  public void setRegionEntry(RegionEntry re) {
    this.re = re;
  }

  public RegionEntry getRegionEntry() {
    return re;
  }

  @Retained(ENTRY_EVENT_NEW_VALUE)
  private void setNewValueInRegion(final InternalRegion owner, final RegionEntry reentry,
      Object oldValueForDelta) throws RegionClearedException {

    boolean wasTombstone = reentry.isTombstone();

    // put in newValue

    // If event contains new value, then it may mean that the delta bytes should
    // not be applied. This is possible if the event originated locally.
    if (deltaBytes != null && newValue == null && newValueBytes == null) {
      processDeltaBytes(oldValueForDelta);
    }

    if (owner != null) {
      owner.generateAndSetVersionTag(this, reentry);
    } else {
      getRegion().generateAndSetVersionTag(this, reentry);
    }

    generateNewValueFromBytesIfNeeded();
    Object v = newValue;
    if (v == null) {
      v = isLocalInvalid() ? Token.LOCAL_INVALID : Token.INVALID;
    } else {
      getRegion().setRegionInvalid(false);
    }

    reentry.setValueResultOfSearch(op.isNetSearch());

    // dsmith:20090524
    // This is a horrible hack, but we need to get the size of the object
    // When we store an entry. This code is only used when we do a put
    // in the primary.
    if (v instanceof Delta && getRegion().isUsedForPartitionedRegionBucket()) {
      int vSize;
      Object ov = basicGetOldValue();
      if (ov instanceof CachedDeserializable && !(shouldRecalculateSize((Delta) v))) {
        vSize = ((CachedDeserializable) ov).getValueSizeInBytes();
      } else {
        vSize = CachedDeserializableFactory.calcMemSize(v, getRegion().getObjectSizer(), false);
      }
      v = CachedDeserializableFactory.create(v, vSize, getRegion().getCache());
      basicSetNewValue(v, true);
    }

    Object preparedV = reentry.prepareValueForCache(getRegion(), v, this, false);
    if (preparedV != v) {
      v = preparedV;
      if (v instanceof StoredObject) {
        if (!((StoredObject) v).isCompressed()) { // fix bug 52109
          // If we put it off heap and it is not compressed then remember that value.
          // Otherwise we want to remember the decompressed value in the event.
          basicSetNewValue(v, false);
        }
      }
    }
    boolean isTombstone = (v == Token.TOMBSTONE);
    boolean success = false;
    boolean calledSetValue = false;
    try {
      setNewValueBucketSize(owner, v);

      // ezoerner:20081030
      // last possible moment to do index maintenance with old value in
      // RegionEntry before new value is set.
      // As part of an update, this is a remove operation as prelude to an add that
      // will come after the new value is set.
      // If this is an "update" from INVALID state, treat this as a create instead
      // for the purpose of index maintenance since invalid entries are not
      // indexed.

      if ((op.isUpdate() && !reentry.isInvalid()) || op.isInvalidate()) {
        IndexManager idxManager =
            IndexUtils.getIndexManager(getRegion().getCache(), getRegion(), false);
        if (idxManager != null) {
          try {
            idxManager.updateIndexes(reentry, IndexManager.REMOVE_ENTRY,
                op.isUpdate() ? IndexProtocol.BEFORE_UPDATE_OP : IndexProtocol.OTHER_OP);
          } catch (QueryException e) {
            throw new IndexMaintenanceException(e);
          }
        }
      }
      calledSetValue = true;
      reentry.setValueWithTombstoneCheck(v, this); // already called prepareValueForCache
      success = true;
    } finally {
      if (!success && reentry instanceof OffHeapRegionEntry && v instanceof StoredObject) {
        if (!calledSetValue) {
          OffHeapHelper.release(v);
        } else {
          OffHeapRegionEntryHelper.releaseEntry((OffHeapRegionEntry) reentry, (StoredObject) v);
        }
      }
    }
    if (logger.isTraceEnabled()) {
      if (v instanceof CachedDeserializable) {
        logger.trace("EntryEventImpl.setNewValueInRegion: put CachedDeserializable({},{})",
            getKey(), ((CachedDeserializable) v).getStringForm());
      } else {
        logger.trace("EntryEventImpl.setNewValueInRegion: put({},{})", getKey(),
            StringUtils.forceToString(v));
      }
    }

    if (!isTombstone && wasTombstone) {
      owner.unscheduleTombstone(reentry);
    }
  }

  /**
   * The size the new value contributes to a pr bucket. Note if this event is not on a pr then this
   * value will be 0.
   */
  private transient int newValueBucketSize;

  public int getNewValueBucketSize() {
    return newValueBucketSize;
  }

  private void setNewValueBucketSize(InternalRegion lr, Object v) {
    if (lr == null) {
      lr = getRegion();
    }
    newValueBucketSize = lr.calculateValueSize(v);
  }

  private void processDeltaBytes(Object oldValueInVM) {
    if (!getRegion().hasSeenEvent(this)) {
      if (oldValueInVM == null || Token.isInvalidOrRemoved(oldValueInVM)) {
        getRegion().getCachePerfStats().incDeltaFailedUpdates();
        throw new InvalidDeltaException("Old value not found for key " + keyInfo.getKey());
      }
      FilterProfile fp = getRegion().getFilterProfile();
      // If compression is enabled then we've already gotten a new copy due to the
      // serializaion and deserialization that occurs.
      boolean copy = getRegion().getCompressor() == null && (getRegion().isCopyOnRead()
          || getRegion().getCloningEnabled() || (fp != null && fp.getCqCount() > 0));
      Object value = oldValueInVM;
      boolean wasCD = false;
      if (value instanceof CachedDeserializable) {
        wasCD = true;
        if (copy) {
          value = ((CachedDeserializable) value).getDeserializedWritableCopy(getRegion(), re);
        } else {
          value = ((CachedDeserializable) value).getDeserializedValue(getRegion(), re);
        }
      } else {
        if (copy) {
          value = CopyHelper.copy(value);
        }
      }
      boolean deltaBytesApplied = false;
      try (ByteArrayDataInput in = new ByteArrayDataInput(getDeltaBytes())) {
        long start = getRegion().getCachePerfStats().getTime();
        ((Delta) value).fromDelta(in);
        getRegion().getCachePerfStats().endDeltaUpdate(start);
        deltaBytesApplied = true;
      } catch (RuntimeException rte) {
        throw rte;
      } catch (VirtualMachineError e) {
        SystemFailure.initiateFailure(e);
        throw e;
      } catch (Throwable t) {
        SystemFailure.checkFailure();
        throw new DeltaSerializationException("Exception while deserializing delta bytes.", t);
      } finally {
        if (!deltaBytesApplied) {
          getRegion().getCachePerfStats().incDeltaFailedUpdates();
        }
      }
      if (logger.isDebugEnabled()) {
        logger.debug("Delta has been applied for key {}", getKey());
      }
      // assert event.getNewValue() == null;
      if (wasCD) {
        CachedDeserializable old = (CachedDeserializable) oldValueInVM;
        int valueSize;
        if (shouldRecalculateSize((Delta) value)) {
          valueSize =
              CachedDeserializableFactory.calcMemSize(value, getRegion().getObjectSizer(), false);
        } else {
          valueSize = old.getValueSizeInBytes();
        }
        value = CachedDeserializableFactory.create(value, valueSize, getRegion().getCache());
      }
      setNewValue(value);
      if (causedByMessage != null && causedByMessage instanceof PutMessage) {
        ((PutMessage) causedByMessage).setDeltaValObj(value);
      }
    } else {
      getRegion().getCachePerfStats().incDeltaFailedUpdates();
      throw new InvalidDeltaException(
          "Cache encountered replay of event containing delta bytes for key "
              + keyInfo.getKey());
    }
  }

  @VisibleForTesting
  protected static boolean shouldRecalculateSize(Delta value) {
    return GemFireCacheImpl.DELTAS_RECALCULATE_SIZE
        || value.getForceRecalculateSize();
  }

  void setTXEntryOldValue(Object oldVal, boolean mustBeAvailable) {
    if (Token.isInvalidOrRemoved(oldVal)) {
      oldVal = null;
    } else {
      if (mustBeAvailable || oldVal == null || areOldValuesEnabled()) {
        // set oldValue to oldVal
      } else {
        oldVal = Token.NOT_AVAILABLE;
      }
    }
    retainAndSetOldValue(oldVal);
  }

  void putValueTXEntry(final TXEntryState tx) {
    Object v = basicGetNewValue();
    if (v == null) {
      if (deltaBytes != null) {
        // since newValue is null, and we have deltaBytes
        // there must be a nearSidePendingValue
        processDeltaBytes(tx.getNearSidePendingValue());
        v = basicGetNewValue();
      } else {
        v = isLocalInvalid() ? Token.LOCAL_INVALID : Token.INVALID;
      }
    }

    if (op != Operation.LOCAL_INVALIDATE && op != Operation.LOCAL_DESTROY) {
      // fix for bug 34387
      Object pv = v;
      if (mayHaveOffHeapReferences()) {
        pv = OffHeapHelper.copyIfNeeded(v, getRegion().getCache());
      }
      tx.setPendingValue(pv);
    }
    tx.setCallbackArgument(getCallbackArgument());
  }

  public void setOldValueFromRegion() {
    try {
      RegionEntry re = getRegion().getRegionEntry(getKey());
      if (re == null) {
        return;
      }
      ReferenceCountHelper.skipRefCountTracking();
      Object v = re.getValueRetain(getRegion(), true);
      if (v == null) {
        v = Token.NOT_AVAILABLE;
      }
      ReferenceCountHelper.unskipRefCountTracking();
      try {
        setOldValue(v);
      } finally {
        if (mayHaveOffHeapReferences()) {
          OffHeapHelper.releaseWithNoTracking(v);
        }
      }
    } catch (EntryNotFoundException ignore) {
    }
  }

  /** Return true if old value is the DESTROYED token */
  boolean oldValueIsDestroyedToken() {
    return oldValue == Token.DESTROYED || oldValue == Token.TOMBSTONE;
  }

  public void setOldValueDestroyedToken() {
    basicSetOldValue(Token.DESTROYED);
  }

  public void setOldValue(Object v) {
    setOldValue(v, false);
  }


  /**
   * @param force true if the old value should be forcibly set, methods like putIfAbsent, etc.,
   *        where the old value must be available.
   */
  public void setOldValue(Object v, boolean force) {
    if (v != null) {
      if (Token.isInvalidOrRemoved(v)) {
        v = null;
      } else if (shouldOldValueBeUnavailable(v, force)) {
        v = Token.NOT_AVAILABLE;
      }
    }
    retainAndSetOldValue(v);
  }

  private boolean shouldOldValueBeUnavailable(Object v, boolean force) {
    if (force) {
      return false;
    }
    if (areOldValuesEnabled()) {
      return false;
    }
    return !(getRegion() instanceof HARegion);
  }

  /**
   * sets the old value for concurrent map operation results received from a server.
   */
  public void setConcurrentMapOldValue(Object v) {
    if (Token.isRemoved(v)) {
      return;
    } else {
      if (Token.isInvalid(v)) {
        v = null;
      }
      retainAndSetOldValue(v);
    }
  }

  /** Return true if new value available */
  public boolean hasNewValue() {
    if (newValueBytes != null) {
      return true;
    }
    Object tmp = newValue;
    return tmp != null && tmp != Token.NOT_AVAILABLE;
  }

  public boolean hasOldValue() {
    if (oldValueBytes != null) {
      return true;
    }
    return oldValue != null && oldValue != Token.NOT_AVAILABLE;
  }

  public boolean isOldValueAToken() {
    return oldValue instanceof Token;
  }

  @Override
  public boolean isOldValueAvailable() {
    if (isOriginRemote() && getRegion().isProxy()) {
      return false;
    } else {
      return basicGetOldValue() != Token.NOT_AVAILABLE;
    }
  }

  public void oldValueNotAvailable() {
    basicSetOldValue(Token.NOT_AVAILABLE);
  }

  public static Object deserialize(byte[] bytes) {
    return deserialize(bytes, null, null);
  }

  public static Object deserialize(byte[] bytes, KnownVersion version, ByteArrayDataInput in) {
    if (bytes == null) {
      return null;
    }
    try {
      return BlobHelper.deserializeBlob(bytes, version, in);
    } catch (IOException e) {
      throw new SerializationException(
          "An IOException was thrown while deserializing",
          e);
    } catch (ClassNotFoundException e) {
      // fix for bug 43602
      throw new SerializationException(
          "A ClassNotFoundException was thrown while trying to deserialize cached value.",
          e);
    }
  }

  /**
   * If a PdxInstance is returned then it will have an unretained reference to the StoredObject's
   * off-heap address.
   */
  public static @Unretained Object deserializeOffHeap(StoredObject bytes) {
    if (bytes == null) {
      return null;
    }
    try {
      return BlobHelper.deserializeOffHeapBlob(bytes);
    } catch (IOException e) {
      throw new SerializationException(
          "An IOException was thrown while deserializing",
          e);
    } catch (ClassNotFoundException e) {
      // fix for bug 43602
      throw new SerializationException(
          "A ClassNotFoundException was thrown while trying to deserialize cached value.",
          e);
    }
  }

  /**
   * Serialize an object into a <code>byte[]</code>
   *
   * @throws IllegalArgumentException If <code>obj</code> should not be serialized
   */
  public static byte[] serialize(Object obj) {
    return serialize(obj, null);
  }

  /**
   * Serialize an object into a <code>byte[]</code>
   *
   * @throws IllegalArgumentException If <code>obj</code> should not be serialized
   */
  public static byte[] serialize(Object obj, KnownVersion version) {
    if (obj == null || obj == Token.NOT_AVAILABLE || Token.isInvalidOrRemoved(obj)) {
      throw new IllegalArgumentException(
          String.format("Must not serialize %s in this context.",
              obj));
    }
    try {
      return BlobHelper.serializeToBlob(obj, version);
    } catch (IOException e) {
      throw new SerializationException(
          "An IOException was thrown while serializing.",
          e);
    }
  }


  /**
   * Serialize an object into a <code>byte[]</code> . If the byte array provided by the wrapper is
   * sufficient to hold the data, it is used otherwise a new byte array gets created & its reference
   * is stored in the wrapper. The User Bit is also appropriately set as Serialized
   *
   * @param wrapper Object of type BytesAndBitsForCompactor which is used to fetch the serialized
   *        data. The byte array of the wrapper is used if possible else a the new byte array
   *        containing the data is set in the wrapper.
   * @throws IllegalArgumentException If <code>obj</code> should not be serialized
   */
  public static void fillSerializedValue(BytesAndBitsForCompactor wrapper, Object obj,
      byte userBits) {
    if (obj == null || obj == Token.NOT_AVAILABLE || Token.isInvalidOrRemoved(obj)) {
      throw new IllegalArgumentException(
          String.format("Must not serialize %s in this context.", obj));
    }
    HeapDataOutputStream hdos = null;
    try {
      if (wrapper.getBytes().length < 32) {
        hdos = new HeapDataOutputStream(KnownVersion.CURRENT);
      } else {
        hdos = new HeapDataOutputStream(wrapper.getBytes());
      }
      DataSerializer.writeObject(obj, hdos);
      // return hdos.toByteArray();
      hdos.sendTo(wrapper, userBits);
    } catch (IOException e) {
      RuntimeException e2 = new IllegalArgumentException(
          "An IOException was thrown while serializing.", e);
      throw e2;
    } finally {
      if (hdos != null) {
        hdos.close();
      }
    }
  }

  protected String getShortClassName() {
    String cname = getClass().getName();
    return cname.substring(getClass().getPackage().getName().length() + 1);
  }

  @Override
  public String toString() {
    StringBuilder buf = new StringBuilder();
    buf.append(getShortClassName());
    buf.append("[");

    buf.append("op=");
    buf.append(getOperation());
    buf.append(";region=");
    buf.append(getRegion().getFullPath());
    buf.append(";key=");
    buf.append(getKey());
    if (Boolean.getBoolean("gemfire.insecure-logvalues")) {
      buf.append(";oldValue=");
      if (mayHaveOffHeapReferences()) {
        synchronized (offHeapLock) {
          try {
            ArrayUtils.objectStringNonRecursive(basicGetOldValue(), buf);
          } catch (IllegalStateException ignore) {
            buf.append("OFFHEAP_VALUE_FREED");
          }
        }
      } else {
        ArrayUtils.objectStringNonRecursive(basicGetOldValue(), buf);
      }

      buf.append(";newValue=");
      if (mayHaveOffHeapReferences()) {
        synchronized (offHeapLock) {
          try {
            ArrayUtils.objectStringNonRecursive(basicGetNewValue(), buf);
          } catch (IllegalStateException ignore) {
            buf.append("OFFHEAP_VALUE_FREED");
          }
        }
      } else {
        ArrayUtils.objectStringNonRecursive(basicGetNewValue(), buf);
      }
    }
    buf.append(";callbackArg=");
    buf.append(getRawCallbackArgument());
    buf.append(";originRemote=");
    buf.append(isOriginRemote());
    buf.append(";originMember=");
    buf.append(getDistributedMember());
    if (isPossibleDuplicate()) {
      buf.append(";posDup");
    }
    if (callbacksInvoked()) {
      buf.append(";callbacksInvoked");
    }
    if (inhibitCacheListenerNotification()) {
      buf.append(";inhibitCacheListenerNotification");
    }
    if (versionTag != null) {
      buf.append(";version=").append(versionTag);
    }
    if (getContext() != null) {
      buf.append(";context=");
      buf.append(getContext());
    }
    if (eventID != null) {
      buf.append(";id=");
      buf.append(eventID);
    }
    if (deltaBytes != null) {
      buf.append(";[").append(deltaBytes.length).append(" deltaBytes]");
    }
    if (filterInfo != null) {
      buf.append(";routing=");
      buf.append(filterInfo);
    }
    if (isFromServer()) {
      buf.append(";isFromServer");
    }
    if (isConcurrencyConflict()) {
      buf.append(";isInConflict");
    }
    if (getInhibitDistribution()) {
      buf.append(";inhibitDistribution");
    }
    if (tailKey != -1) {
      buf.append(";tailKey=").append(tailKey);
    }
    buf.append("]");
    return buf.toString();
  }

  @Override
  public int getDSFID() {
    return ENTRY_EVENT;
  }

  @Override
  public void toData(DataOutput out,
      SerializationContext context) throws IOException {
    context.getSerializer().writeObject(eventID, out);
    context.getSerializer().writeObject(getKey(), out);
    context.getSerializer().writeObject(keyInfo.getValue(), out);
    out.writeByte(op.ordinal);
    out.writeShort(eventFlags & EventFlags.FLAG_TRANSIENT_MASK);
    context.getSerializer().writeObject(getRawCallbackArgument(), out);
    context.getSerializer().writeObject(txId, out);

    {
      out.writeBoolean(false);
      {
        Object nv = basicGetNewValue();
        boolean newValueSerialized = nv instanceof CachedDeserializable;
        if (newValueSerialized) {
          newValueSerialized = ((CachedDeserializable) nv).isSerialized();
        }
        out.writeBoolean(newValueSerialized);
        if (newValueSerialized) {
          if (newValueBytes != null) {
            DataSerializer.writeByteArray(newValueBytes, out);
          } else if (cachedSerializedNewValue != null) {
            DataSerializer.writeByteArray(cachedSerializedNewValue, out);
          } else {
            CachedDeserializable cd = (CachedDeserializable) nv;
            DataSerializer.writeObjectAsByteArray(cd.getValue(), out);
          }
        } else {
          context.getSerializer().writeObject(nv, out);
        }
      }
    }

    {
      Object ov = basicGetOldValue();
      boolean oldValueSerialized = ov instanceof CachedDeserializable;
      if (oldValueSerialized) {
        oldValueSerialized = ((CachedDeserializable) ov).isSerialized();
      }
      out.writeBoolean(oldValueSerialized);
      if (oldValueSerialized) {
        if (oldValueBytes != null) {
          DataSerializer.writeByteArray(oldValueBytes, out);
        } else {
          CachedDeserializable cd = (CachedDeserializable) ov;
          DataSerializer.writeObjectAsByteArray(cd.getValue(), out);
        }
      } else {
        ov = AbstractRegion.handleNotAvailable(ov);
        context.getSerializer().writeObject(ov, out);
      }
    }
    InternalDataSerializer.invokeToData(distributedMember, out);
    context.getSerializer().writeObject(getContext(), out);
    DataSerializer.writeLong(tailKey, out);
  }

  private abstract static class EventFlags {
    private static final short FLAG_ORIGIN_REMOTE = 0x01;
    // localInvalid: true if a null new value should be treated as a local
    // invalid.
    private static final short FLAG_LOCAL_INVALID = 0x02;
    private static final short FLAG_GENERATE_CALLBACKS = 0x04;
    private static final short FLAG_POSSIBLE_DUPLICATE = 0x08;
    private static final short FLAG_INVOKE_PR_CALLBACKS = 0x10;
    private static final short FLAG_CONCURRENCY_CONFLICT = 0x20;
    private static final short FLAG_INHIBIT_LISTENER_NOTIFICATION = 0x40;
    private static final short FLAG_CALLBACKS_INVOKED = 0x80;
    private static final short FLAG_ISCREATE = 0x100;
    private static final short FLAG_SERIALIZATION_DEFERRED = 0x200;
    private static final short FLAG_FROM_SERVER = 0x400;
    private static final short FLAG_FROM_RI_LOCAL_DESTROY = 0x800;
    private static final short FLAG_INHIBIT_DISTRIBUTION = 0x1000;
    private static final short FLAG_REDESTROYED_TOMBSTONE = 0x2000;
    private static final short FLAG_INHIBIT_ALL_NOTIFICATIONS = 0x4000;

    /** mask for clearing transient flags when serializing */
    private static final short FLAG_TRANSIENT_MASK = ~(FLAG_CALLBACKS_INVOKED | FLAG_ISCREATE
        | FLAG_INHIBIT_LISTENER_NOTIFICATION | FLAG_SERIALIZATION_DEFERRED | FLAG_FROM_SERVER
        | FLAG_FROM_RI_LOCAL_DESTROY | FLAG_INHIBIT_DISTRIBUTION | FLAG_REDESTROYED_TOMBSTONE);

    protected static boolean isSet(short flags, short mask) {
      return (flags & mask) != 0;
    }

    /** WARNING: Does not set the bit in place, returns new short with bit set */
    protected static short set(short flags, short mask, boolean on) {
      return (short) (on ? (flags | mask) : (flags & ~mask));
    }
  }

  /**
   * @return null if old value is not serialized; otherwise returns a SerializedCacheValueImpl
   *         containing the old value.
   */
  @Override
  public SerializedCacheValue<?> getSerializedOldValue() {
    @Unretained(ENTRY_EVENT_OLD_VALUE)
    final Object tmp = basicGetOldValue();
    if (tmp instanceof CachedDeserializable) {
      CachedDeserializable cd = (CachedDeserializable) tmp;
      if (!cd.isSerialized()) {
        return null;
      }
      return new SerializedCacheValueImpl(this, getRegion(), re, cd, oldValueBytes);
    } else {
      return null;
    }
  }

  /**
   * Compute an estimate of the size of the new value for a PR. Since PR's always store values in a
   * cached deserializable we need to compute its size as a blob.
   *
   * @return the size of serialized bytes for the new value
   */
  public int getNewValSizeForPR() {
    int newSize = 0;
    Object v = basicGetNewValue();
    if (v != null) {
      try {
        newSize = CachedDeserializableFactory.calcSerializedSize(v)
            + CachedDeserializableFactory.overhead();
      } catch (IllegalArgumentException iae) {
        logger.warn("DataStore failed to calculate size of new value",
            iae);
        newSize = 0;
      }
    }
    return newSize;
  }

  /**
   * Compute an estimate of the size of the old value
   *
   * @return the size of serialized bytes for the old value
   */
  public int getOldValSize() {
    int oldSize = 0;
    if (hasOldValue()) {
      try {
        oldSize = CachedDeserializableFactory.calcMemSize(basicGetOldValue());
      } catch (IllegalArgumentException iae) {
        logger.warn("DataStore failed to calculate size of old value",
            iae);
        oldSize = 0;
      }
    }
    return oldSize;
  }

  @Override
  public EnumListenerEvent getEventType() {
    return eventType;
  }

  /**
   * Sets the operation type.
   */
  @Override
  public void setEventType(EnumListenerEvent eventType) {
    this.eventType = eventType;
  }

  /**
   * set this to true after dispatching the event to a cache listener
   */
  public void callbacksInvoked(boolean dispatched) {
    setEventFlag(EventFlags.FLAG_CALLBACKS_INVOKED, dispatched);
  }

  /**
   * has this event been dispatched to a cache listener?
   */
  public boolean callbacksInvoked() {
    return testEventFlag(EventFlags.FLAG_CALLBACKS_INVOKED);
  }

  /**
   * set this to true to inhibit application cache listener notification during event dispatching
   */
  public void inhibitCacheListenerNotification(boolean inhibit) {
    setEventFlag(EventFlags.FLAG_INHIBIT_LISTENER_NOTIFICATION, inhibit);
  }

  /**
   * are events being inhibited from dispatch to application cache listeners for this event?
   */
  public boolean inhibitCacheListenerNotification() {
    return testEventFlag(EventFlags.FLAG_INHIBIT_LISTENER_NOTIFICATION);
  }


  /**
   * dispatch listener events for this event
   *
   * @param notifyGateways pass the event on to WAN queues
   */
  public void invokeCallbacks(InternalRegion rgn, boolean skipListeners, boolean notifyGateways) {
    if (!callbacksInvoked()) {
      callbacksInvoked(true);
      if (op.isUpdate()) {
        rgn.invokePutCallbacks(EnumListenerEvent.AFTER_UPDATE, this, !skipListeners,
            notifyGateways); // gateways are notified in part2 processing
      } else if (op.isCreate()) {
        rgn.invokePutCallbacks(EnumListenerEvent.AFTER_CREATE, this, !skipListeners,
            notifyGateways);
      } else if (op.isDestroy()) {
        rgn.invokeDestroyCallbacks(EnumListenerEvent.AFTER_DESTROY, this, !skipListeners,
            notifyGateways);
      } else if (op.isInvalidate()) {
        rgn.invokeInvalidateCallbacks(EnumListenerEvent.AFTER_INVALIDATE, this, !skipListeners);
      }
    }
  }

  private void setFromRILocalDestroy(boolean on) {
    setEventFlag(EventFlags.FLAG_FROM_RI_LOCAL_DESTROY, on);
  }

  public boolean isFromRILocalDestroy() {
    return testEventFlag(EventFlags.FLAG_FROM_RI_LOCAL_DESTROY);
  }

  protected Long tailKey = -1L;

  /**
   * Used to store next region version generated for a change on this entry by phase-1 commit on the
   * primary.
   *
   * Not to be used in fromData and toData
   */
  protected transient long nextRegionVersion = -1L;

  public void setNextRegionVersion(long regionVersion) {
    nextRegionVersion = regionVersion;
  }

  public long getNextRegionVersion() {
    return nextRegionVersion;
  }

  /**
   * Return true if this event came from a server by the client doing a get.
   *
   * @since GemFire 5.7
   */
  public boolean isFromServer() {
    return testEventFlag(EventFlags.FLAG_FROM_SERVER);
  }

  /**
   * Sets the fromServer flag to v. This must be set to true if an event comes from a server while
   * the affected region entry is not locked. Among other things it causes version conflict checks
   * to be performed to protect against overwriting a newer version of the entry.
   *
   * @since GemFire 5.7
   */
  public void setFromServer(boolean v) {
    setEventFlag(EventFlags.FLAG_FROM_SERVER, v);
  }

  /**
   * If true, the region associated with this event had already applied the operation it
   * encapsulates when an attempt was made to apply the event.
   *
   * @return the possibleDuplicate
   */
  public boolean isPossibleDuplicate() {
    return testEventFlag(EventFlags.FLAG_POSSIBLE_DUPLICATE);
  }

  /**
   * If the operation encapsulated by this event has already been seen by the region to which it
   * pertains, this flag should be set to true.
   *
   * @param possibleDuplicate the possibleDuplicate to set
   */
  public void setPossibleDuplicate(boolean possibleDuplicate) {
    setEventFlag(EventFlags.FLAG_POSSIBLE_DUPLICATE, possibleDuplicate);
  }


  /**
   * are events being inhibited from dispatch to to gateway/async queues, client queues, cache
   * listener and cache write. If set, sending notifications for the data that is read from a
   * persistent store (HDFS) and is being reinserted in the cache is skipped.
   */
  public boolean inhibitAllNotifications() {
    return testEventFlag(EventFlags.FLAG_INHIBIT_ALL_NOTIFICATIONS);

  }

  /**
   * set this to true to inhibit notifications that are sent to gateway/async queues, client queues,
   * cache listener and cache write. This is used to skip sending notifications for the data that is
   * read from a persistent store (HDFS) and is being reinserted in the cache
   */
  public void setInhibitAllNotifications(boolean inhibit) {
    setEventFlag(EventFlags.FLAG_INHIBIT_ALL_NOTIFICATIONS, inhibit);
  }

  /**
   * sets the routing information for cache clients
   */
  @Override
  public void setLocalFilterInfo(FilterInfo info) {
    filterInfo = info;
  }

  /**
   * retrieves the routing information for cache clients in this VM
   */
  @Override
  public FilterInfo getLocalFilterInfo() {
    return filterInfo;
  }

  /**
   * This method returns the delta bytes used in Delta Propagation feature. <B>For internal delta,
   * see getRawNewValue().</B>
   *
   * @return delta bytes
   */
  public byte[] getDeltaBytes() {
    return deltaBytes;
  }

  /**
   * This method sets the delta bytes used in Delta Propagation feature. <B>For internal delta, see
   * setNewValue().</B>
   */
  public void setDeltaBytes(byte[] deltaBytes) {
    this.deltaBytes = deltaBytes;
  }

  // TODO (ashetkar) Can this.op.isCreate() be used instead?
  public boolean isCreate() {
    return testEventFlag(EventFlags.FLAG_ISCREATE);
  }

  /**
   * this is used to distinguish an event that merely has Operation.CREATE from one that originated
   * from Region.create() for delta processing purposes.
   */
  public EntryEventImpl setCreate(boolean isCreate) {
    setEventFlag(EventFlags.FLAG_ISCREATE, isCreate);
    return this;
  }

  /**
   * @return the keyInfo
   */
  public KeyInfo getKeyInfo() {
    return keyInfo;
  }

  public void setKeyInfo(KeyInfo keyInfo) {
    this.keyInfo = keyInfo;
  }

  /**
   * establish the old value in this event as the current cache value, whether in memory or on disk
   */
  public void setOldValueForQueryProcessing() {
    RegionEntry reentry = getRegion().getRegionMap().getEntry(getKey());
    if (reentry != null) {
      @Retained
      Object v = reentry.getValueOffHeapOrDiskWithoutFaultIn(getRegion());
      if (!(v instanceof Token)) {
        // v has already been retained.
        basicSetOldValue(v);
        // this event now owns the retention of v.
      }
    }
  }

  @Override
  public KnownVersion[] getSerializationVersions() {
    return null;
  }

  /**
   * @param versionTag the versionTag to set
   */
  public void setVersionTag(VersionTag versionTag) {
    this.versionTag = versionTag;
  }

  /**
   * @return the concurrency versioning tag for this event, if any
   */
  @Override
  public VersionTag getVersionTag() {
    return versionTag;
  }

  /**
   * @return if there's no valid version tag for this event
   */
  public boolean hasValidVersionTag() {
    return versionTag != null && versionTag.hasValidVersion();
  }

  /**
   * this method joins together version tag timestamps and the "lastModified" timestamps generated
   * and stored in entries. If a change does not already carry a lastModified timestamp
   *
   * @return the timestamp to store in the entry
   */
  public long getEventTime(long suggestedTime) {
    long result = suggestedTime;
    if (versionTag != null && getRegion().getConcurrencyChecksEnabled()) {
      if (suggestedTime != 0) {
        versionTag.setVersionTimeStamp(suggestedTime);
      } else {
        result = versionTag.getVersionTimeStamp();
      }
    }
    if (result <= 0) {
      InternalRegion region = getRegion();
      if (region != null) {
        result = region.cacheTimeMillis();
      } else {
        result = System.currentTimeMillis();
      }
    }
    return result;
  }

  public static class SerializedCacheValueImpl
      implements SerializedCacheValue, CachedDeserializable, Sendable {
    private final EntryEventImpl event;
    @Unretained
    private final CachedDeserializable cd;
    private final Region r;
    private final RegionEntry re;
    private final byte[] serializedValue;

    SerializedCacheValueImpl(EntryEventImpl event, Region r, RegionEntry re,
        @Unretained CachedDeserializable cd, byte[] serializedBytes) {
      if (event.isOffHeapReference(cd)) {
        this.event = event;
      } else {
        this.event = null;
      }
      this.r = r;
      this.re = re;
      this.cd = cd;
      serializedValue = serializedBytes;
    }

    @Override
    public byte[] getSerializedValue() {
      if (serializedValue != null) {
        return serializedValue;
      }
      return callWithOffHeapLock(CachedDeserializable::getSerializedValue);
    }

    private CachedDeserializable getCd() {
      if (event != null && !event.offHeapOk) {
        throw new IllegalStateException(
            "Attempt to access off heap value after the EntryEvent was released.");
      }
      return cd;
    }

    /**
     * The only methods that need to use this method are those on the external SerializedCacheValue
     * interface and any other method that a customer could call that may access the off-heap
     * values. For example if toString was implemented on this class to access the value then it
     * would need to use this method.
     */
    private <R> R callWithOffHeapLock(Function<CachedDeserializable, R> function) {
      if (event != null) {
        // this call does not use getCd() to access this.cd
        // because the check for offHeapOk is done by event.callWithOffHeapLock
        return event.callWithOffHeapLock(cd, function);
      } else {
        return function.apply(getCd());
      }
    }

    @Override
    public Object getDeserializedValue() {
      return getDeserializedValue(r, re);
    }

    @Override
    public Object getDeserializedForReading() {
      return getCd().getDeserializedForReading();
    }

    @Override
    public Object getDeserializedWritableCopy(Region rgn, RegionEntry entry) {
      return getCd().getDeserializedWritableCopy(rgn, entry);
    }

    @Override
    public Object getDeserializedValue(Region rgn, RegionEntry reentry) {
      return callWithOffHeapLock(cd -> {
        return cd.getDeserializedValue(rgn, reentry);
      });
    }

    @Override
    public Object getValue() {
      if (serializedValue != null) {
        return serializedValue;
      }
      return getCd().getValue();
    }

    @Override
    public void writeValueAsByteArray(DataOutput out) throws IOException {
      if (serializedValue != null) {
        DataSerializer.writeByteArray(serializedValue, out);
      } else {
        getCd().writeValueAsByteArray(out);
      }
    }

    @Override
    public void fillSerializedValue(BytesAndBitsForCompactor wrapper, byte userBits) {
      if (serializedValue != null) {
        wrapper.setData(serializedValue, userBits, serializedValue.length,
            false /* Not Reusable as it refers to underlying value */);
      } else {
        getCd().fillSerializedValue(wrapper, userBits);
      }
    }

    @Override
    public int getValueSizeInBytes() {
      return getCd().getValueSizeInBytes();
    }

    @Override
    public int getSizeInBytes() {
      return getCd().getSizeInBytes();
    }

    @Override
    public String getStringForm() {
      return getCd().getStringForm();
    }

    @Override
    public void sendTo(DataOutput out) throws IOException {
      DataSerializer.writeObject(getCd(), out);
    }

    @Override
    public boolean isSerialized() {
      return getCd().isSerialized();
    }

    @Override
    public boolean usesHeapForStorage() {
      return getCd().usesHeapForStorage();
    }
  }
  //////////////////////////////////////////////////////////////////////////////////////////

  public void setTailKey(Long tailKey) {
    this.tailKey = tailKey;
  }

  public Long getTailKey() {
    return tailKey;
  }

  private Thread invokeCallbacksThread;

  /**
   * Mark this event as having its callbacks invoked by the current thread. Note this is done just
   * before the actual invocation of the callbacks.
   */
  public void setCallbacksInvokedByCurrentThread() {
    invokeCallbacksThread = Thread.currentThread();
  }

  /**
   * Return true if this event was marked as having its callbacks invoked by the current thread.
   */
  public boolean getCallbacksInvokedByCurrentThread() {
    if (invokeCallbacksThread == null) {
      return false;
    }
    return Thread.currentThread().equals(invokeCallbacksThread);
  }

  /**
   * Returns whether this event is on the PDX type region.
   *
   * @return whether this event is on the PDX type region
   */
  public boolean isOnPdxTypeRegion() {
    return PeerTypeRegistration.REGION_FULL_PATH.equals(getRegion().getFullPath());
  }

  /**
   * returns true if it is okay to process this event even though it has a null version
   */
  public boolean noVersionReceivedFromServer() {
    return versionTag == null && getRegion().getConcurrencyChecksEnabled()
        && getRegion().getServerProxy() != null && !op.isLocal() && !isOriginRemote();
  }

  /** returns a copy of this event with the additional fields for WAN conflict resolution */
  @Retained
  public TimestampedEntryEvent getTimestampedEvent(final int newDSID, final int oldDSID,
      final long newTimestamp, final long oldTimestamp) {
    return new TimestampedEntryEventImpl(this, newDSID, oldDSID, newTimestamp, oldTimestamp);
  }

  private void setSerializationDeferred(boolean serializationDeferred) {
    setEventFlag(EventFlags.FLAG_SERIALIZATION_DEFERRED, serializationDeferred);
  }

  private boolean isSerializationDeferred() {
    return testEventFlag(EventFlags.FLAG_SERIALIZATION_DEFERRED);
  }

  public boolean isSingleHop() {
    return (causedByMessage != null && causedByMessage instanceof RemoteOperationMessage);
  }

  public boolean isSingleHopPutOp() {
    return (causedByMessage != null && causedByMessage instanceof RemotePutMessage);
  }

  /**
   * True if it is ok to use old/new values that are stored off heap. False if an exception should
   * be thrown if an attempt is made to access old/new offheap values.
   */
  transient boolean offHeapOk = true;

  @Override
  @Released({ENTRY_EVENT_NEW_VALUE, ENTRY_EVENT_OLD_VALUE})
  public void release() {
    // noop if already freed or values can not be off-heap
    if (!offHeapOk) {
      return;
    }
    if (!mayHaveOffHeapReferences()) {
      return;
    }
    synchronized (offHeapLock) {
      // Note that this method does not set the old/new values to null but
      // leaves them set to the off-heap value so that future calls to getOld/NewValue
      // will fail with an exception.
      testHookReleaseInProgress();
      Object ov = basicGetOldValue();
      Object nv = basicGetNewValue();
      offHeapOk = false;

      if (ov instanceof StoredObject) {
        // this.region.getCache().getLogger().info("DEBUG freeing ref to old value on " +
        // System.identityHashCode(ov));
        if (ReferenceCountHelper.trackReferenceCounts()) {
          ReferenceCountHelper.setReferenceCountOwner(new OldValueOwner());
          ((Releasable) ov).release();
          ReferenceCountHelper.setReferenceCountOwner(null);
        } else {
          ((Releasable) ov).release();
        }
      }
      OffHeapHelper.releaseAndTrackOwner(nv, this);
    }
  }

  /**
   * Return true if this EntryEvent may have off-heap references.
   */
  private boolean mayHaveOffHeapReferences() {
    if (offHeapLock == null) {
      return false;
    }

    InternalRegion lr = getRegion();
    if (lr != null) {
      return lr.getOffHeap();
    }
    // if region field is null it is possible that we have off-heap values
    return true;
  }

  void testHookReleaseInProgress() {
    // unit test can mock or override this method
  }

  /**
   * Make sure that this event will never own an off-heap value. Once this is called on an event it
   * does not need to have release called.
   */
  @Override
  public void disallowOffHeapValues() {
    if (isOffHeapReference(newValue) || isOffHeapReference(oldValue)) {
      throw new IllegalStateException("This event already has off-heap values");
    }
    if (mayHaveOffHeapReferences()) {
      synchronized (offHeapLock) {
        offHeapOk = false;
      }
    } else {
      offHeapOk = false;
    }

  }

  /**
   * This copies the off-heap new and/or old value to the heap. As a result the current off-heap
   * new/old will be released.
   */
  @Released({ENTRY_EVENT_NEW_VALUE, ENTRY_EVENT_OLD_VALUE})
  public void copyOffHeapToHeap() {
    if (!mayHaveOffHeapReferences()) {
      offHeapOk = false;
      return;
    }
    synchronized (offHeapLock) {
      Object ov = basicGetOldValue();
      if (StoredObject.isOffHeapReference(ov)) {
        if (ReferenceCountHelper.trackReferenceCounts()) {
          ReferenceCountHelper.setReferenceCountOwner(new OldValueOwner());
          oldValue = OffHeapHelper.copyAndReleaseIfNeeded(ov, getRegion().getCache());
          ReferenceCountHelper.setReferenceCountOwner(null);
        } else {
          oldValue = OffHeapHelper.copyAndReleaseIfNeeded(ov, getRegion().getCache());
        }
      }
      Object nv = basicGetNewValue();
      if (StoredObject.isOffHeapReference(nv)) {
        ReferenceCountHelper.setReferenceCountOwner(this);
        newValue = OffHeapHelper.copyAndReleaseIfNeeded(nv, getRegion().getCache());
        ReferenceCountHelper.setReferenceCountOwner(null);
      }
      if (StoredObject.isOffHeapReference(newValue)
          || StoredObject.isOffHeapReference(oldValue)) {
        throw new IllegalStateException(
            "event's old/new value still off-heap after calling copyOffHeapToHeap");
      }
      offHeapOk = false;
    }
  }

  public boolean isOldValueOffHeap() {
    return isOffHeapReference(oldValue);
  }

  /**
   * If region is currently a bucket
   * then change it to be the partitioned region that owns that bucket.
   * Otherwise do nothing.
   */
  public void changeRegionToBucketsOwner() {
    if (getRegion().isUsedForPartitionedRegionBucket()) {
      setRegion(getRegion().getPartitionedRegion());
    }
  }
}
