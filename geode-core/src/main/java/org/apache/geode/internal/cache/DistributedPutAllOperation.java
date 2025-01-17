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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.apache.logging.log4j.Logger;

import org.apache.geode.DataSerializer;
import org.apache.geode.cache.CacheEvent;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.EntryNotFoundException;
import org.apache.geode.cache.Operation;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.persistence.PersistentReplicatesOfflineException;
import org.apache.geode.cache.query.internal.cq.CqService;
import org.apache.geode.cache.query.internal.cq.ServerCQ;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.internal.DirectReplyProcessor;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.cache.FilterRoutingInfo.FilterInfo;
import org.apache.geode.internal.cache.ha.ThreadIdentifier;
import org.apache.geode.internal.cache.partitioned.PutAllPRMessage;
import org.apache.geode.internal.cache.tier.MessageType;
import org.apache.geode.internal.cache.tier.sockets.ClientProxyMembershipID;
import org.apache.geode.internal.cache.tier.sockets.VersionedObjectList;
import org.apache.geode.internal.cache.tx.RemotePutAllMessage;
import org.apache.geode.internal.cache.versions.DiskVersionTag;
import org.apache.geode.internal.cache.versions.VersionSource;
import org.apache.geode.internal.cache.versions.VersionTag;
import org.apache.geode.internal.logging.log4j.LogMarker;
import org.apache.geode.internal.offheap.annotations.Released;
import org.apache.geode.internal.offheap.annotations.Retained;
import org.apache.geode.internal.offheap.annotations.Unretained;
import org.apache.geode.internal.serialization.ByteArrayDataInput;
import org.apache.geode.internal.serialization.DataSerializableFixedID;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.internal.serialization.StaticSerialization;
import org.apache.geode.logging.internal.log4j.api.LogService;

/**
 * Handles distribution of a Region.putall operation.
 *
 * @since GemFire 5.0
 */
public class DistributedPutAllOperation extends AbstractUpdateOperation {

  private static final Logger logger = LogService.getLogger();

  protected final PutAllEntryData[] putAllData;

  public int putAllDataSize;

  protected boolean isBridgeOp = false;

  static final byte USED_FAKE_EVENT_ID = 0x01;
  static final byte NOTIFY_ONLY = 0x02;
  static final byte FILTER_ROUTING = 0x04;
  static final byte VERSION_TAG = 0x08;
  static final byte POSDUP = 0x10;
  static final byte PERSISTENT_TAG = 0x20;
  static final byte HAS_CALLBACKARG = 0x40;
  static final byte HAS_TAILKEY = (byte) 0x80;

  // flags for CachedDeserializable; additional flags can be combined
  // with these if required
  static final byte IS_CACHED_DESER = 0x1;
  static final byte IS_OBJECT = 0x2;

  // private boolean containsCreate = false;

  public DistributedPutAllOperation(CacheEvent event, int size, boolean isBridgeOp) {
    super(event, ((EntryEventImpl) event).getEventTime(0L));
    putAllData = new PutAllEntryData[size];
    putAllDataSize = 0;
    this.isBridgeOp = isBridgeOp;
  }

  /**
   * return if the operation is bridge operation
   */
  public boolean isBridgeOperation() {
    return isBridgeOp;
  }

  public PutAllEntryData[] getPutAllEntryData() {
    return putAllData;
  }

  public void setPutAllEntryData(PutAllEntryData[] putAllEntryData) {
    for (int i = 0; i < putAllEntryData.length; i++) {
      putAllData[i] = putAllEntryData[i];
    }
    putAllDataSize = putAllEntryData.length;
  }

  /**
   * Add an entry that this putall operation should distribute.
   */
  public void addEntry(PutAllEntryData putAllEntry) {
    putAllData[putAllDataSize] = putAllEntry;
    putAllDataSize += 1;
    // cachedEvents.add(ev);
  }

  /**
   * Add an entry that this putall operation should distribute.
   */
  public void addEntry(EntryEventImpl ev) {
    putAllData[putAllDataSize] = new PutAllEntryData(ev);
    putAllDataSize += 1;
    // cachedEvents.add(ev);
  }

  /**
   * Add an entry that this putall operation should distribute. This method is for a special case:
   * the callback will be called after this in hasSeenEvent() case, so we should change the status
   * beforehand
   */
  public void addEntry(EntryEventImpl ev, boolean newCallbackInvoked) {
    putAllData[putAllDataSize] = new PutAllEntryData(ev);
    putAllData[putAllDataSize].setCallbacksInvoked(newCallbackInvoked);
    putAllDataSize += 1;
    // cachedEvents.add(ev);
  }

  /**
   * Add an entry for PR bucket's msg.
   *
   * @param ev event to be added
   * @param bucketId message is for this bucket
   */
  public void addEntry(EntryEventImpl ev, Integer bucketId) {
    putAllData[putAllDataSize] = new PutAllEntryData(ev);
    putAllData[putAllDataSize].setBucketId(bucketId);
    putAllDataSize += 1;
    // cachedEvents.add(ev);
  }

  /**
   * set using fake thread id
   *
   * @param status whether the entry is using fake event id
   */
  public void setUseFakeEventId(boolean status) {
    for (int i = 0; i < putAllDataSize; i++) {
      putAllData[i].setUsedFakeEventId(status);
    }
  }

  /**
   * In the originating cache, this returns an iterator on the list of events caused by the putAll
   * operation. This is cached for listener notification purposes. The iterator is guaranteed to
   * return events in the order they are present in putAllData[]
   */
  public Iterator eventIterator() {
    return new Iterator() {
      int position = 0;

      @Override
      public boolean hasNext() {
        return putAllDataSize > position;
      }

      @Override
      @Unretained
      public Object next() {
        @Unretained
        EntryEventImpl ev = getEventForPosition(position);
        position++;
        return ev;
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException();
      }
    };
  }

  public void freeOffHeapResources() {
    // I do not use eventIterator here because it forces the lazy creation of EntryEventImpl by
    // calling getEventForPosition.
    for (int i = 0; i < putAllDataSize; i++) {
      PutAllEntryData entry = putAllData[i];
      if (entry != null && entry.event != null) {
        entry.event.release();
      }
    }
  }

  @Unretained
  public EntryEventImpl getEventForPosition(int position) {
    PutAllEntryData entry = putAllData[position];
    if (entry == null) {
      return null;
    }
    if (entry.event != null) {
      return entry.event;
    }
    LocalRegion region = (LocalRegion) event.getRegion();
    @Retained
    EntryEventImpl ev = EntryEventImpl.create(region, entry.getOp(), entry.getKey(),
        null/* value */, event.getCallbackArgument(), false /* originRemote */,
        event.getDistributedMember(), event.isGenerateCallbacks(), entry.getEventID());
    boolean returnedEv = false;
    try {
      ev.setPossibleDuplicate(entry.isPossibleDuplicate());
      if (entry.versionTag != null && region.getConcurrencyChecksEnabled()) {
        VersionSource id = entry.versionTag.getMemberID();
        if (id != null) {
          entry.versionTag.setMemberID(ev.getRegion().getVersionVector().getCanonicalId(id));
        }
        ev.setVersionTag(entry.versionTag);
      }

      entry.event = ev;
      returnedEv = true;
      Object entryValue = entry.getValue(region.getCache());
      if (entryValue == null
          && ev.getRegion().getAttributes().getDataPolicy() == DataPolicy.NORMAL) {
        ev.setLocalInvalid(true);
      }
      ev.setNewValue(entryValue);
      ev.setOldValue(entry.getOldValue());
      CqService cqService = region.getCache().getCqService();
      if (cqService.isRunning() && !entry.getOp().isCreate() && !ev.hasOldValue()) {
        ev.setOldValueForQueryProcessing();
      }
      ev.setInvokePRCallbacks(!entry.isNotifyOnly());
      if (getBaseEvent().getContext() != null) {
        ev.setContext(getBaseEvent().getContext());
      }
      ev.callbacksInvoked(entry.isCallbacksInvoked());
      ev.setTailKey(entry.getTailKey());
      return ev;
    } finally {
      if (!returnedEv) {
        ev.release();
      }
    }
  }

  public EntryEventImpl getBaseEvent() {
    return getEvent();
  }

  /**
   * Data that represents a single entry being putall'd.
   */
  public static class PutAllEntryData {

    final Object key;

    private Object value;

    private final Object oldValue;

    private final Operation op;

    private EventID eventID;

    transient EntryEventImpl event;

    private Integer bucketId = -1;

    protected transient boolean callbacksInvoked = false;

    public FilterRoutingInfo filterRouting;

    // One flags byte for all booleans
    protected byte flags = 0x00;

    // TODO: Yogesh, this should be intialized and sent on wire only when
    // parallel wan is enabled
    private Long tailKey = 0L;

    public VersionTag versionTag;

    transient boolean inhibitDistribution;

    /**
     * Constructor to use when preparing to send putall data out
     */
    public PutAllEntryData(EntryEventImpl event) {

      key = event.getKey();
      value = event.getRawNewValueAsHeapObject();
      Object oldValue = event.getRawOldValueAsHeapObject();

      if (oldValue == Token.NOT_AVAILABLE || Token.isRemoved(oldValue)) {
        this.oldValue = null;
      } else {
        this.oldValue = oldValue;
      }

      op = event.getOperation();
      eventID = event.getEventId();
      tailKey = event.getTailKey();
      versionTag = event.getVersionTag();

      setNotifyOnly(!event.getInvokePRCallbacks());
      setCallbacksInvoked(event.callbacksInvoked());
      setPossibleDuplicate(event.isPossibleDuplicate());
      setInhibitDistribution(event.getInhibitDistribution());
    }

    /**
     * Constructor to use when receiving a putall from someone else
     */
    public PutAllEntryData(DataInput in, DeserializationContext context, EventID baseEventID,
        int idx) throws IOException, ClassNotFoundException {
      key = context.getDeserializer().readObject(in);
      byte flgs = in.readByte();
      if ((flgs & IS_OBJECT) != 0) {
        value = context.getDeserializer().readObject(in);
      } else {
        byte[] bb = DataSerializer.readByteArray(in);
        if ((flgs & IS_CACHED_DESER) != 0) {
          value = new FutureCachedDeserializable(bb);
        } else {
          value = bb;
        }
      }
      oldValue = null;
      op = Operation.fromOrdinal(in.readByte());
      flags = in.readByte();
      if ((flags & FILTER_ROUTING) != 0) {
        filterRouting = context.getDeserializer().readObject(in);
      }
      if ((flags & VERSION_TAG) != 0) {
        boolean persistentTag = (flags & PERSISTENT_TAG) != 0;
        versionTag = VersionTag.create(persistentTag, in);
      }
      if (isUsedFakeEventId()) {
        eventID = new EventID();
        InternalDataSerializer.invokeFromData(eventID, in);
      } else {
        eventID = new EventID(baseEventID, idx);
      }
      if ((flags & HAS_TAILKEY) != 0) {
        tailKey = DataSerializer.readLong(in);
      }
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(50);
      sb.append("(").append(getKey()).append(",").append(value).append(",")
          .append(getOldValue());
      if (bucketId > 0) {
        sb.append(", b").append(bucketId);
      }
      if (versionTag != null) {
        sb.append(versionTag);
        // sb.append(",v").append(versionTag.getEntryVersion()).append(",rv"+versionTag.getRegionVersion());
      }
      if (filterRouting != null) {
        sb.append(", ").append(filterRouting);
      }
      sb.append(")");
      return sb.toString();
    }

    void setSender(InternalDistributedMember sender) {
      if (versionTag != null) {
        versionTag.replaceNullIDs(sender);
      }
    }

    /**
     * Used to serialize this instances data to <code>out</code>. If changes are made to this method
     * make sure that it is backwards compatible by creating toDataPreXX methods. Also make sure
     * that the callers to this method are backwards compatible by creating toDataPreXX methods for
     * them even if they are not changed. <br>
     * Callers for this method are: <br>
     * {@link DataSerializableFixedID#toData(DataOutput, SerializationContext)} <br>
     * {@link DataSerializableFixedID#toData(DataOutput, SerializationContext)} <br>
     * {@link DataSerializableFixedID#toData(DataOutput, SerializationContext)} <br>
     */
    public void toData(final DataOutput out,
        SerializationContext context) throws IOException {
      Object key = this.key;
      final Object v = value;
      context.getSerializer().writeObject(key, out);

      if (v instanceof byte[] || v == null) {
        out.writeByte(0);
        DataSerializer.writeByteArray((byte[]) v, out);
      } else if (v instanceof CachedDeserializable) {
        CachedDeserializable cd = (CachedDeserializable) v;
        out.writeByte(IS_CACHED_DESER);
        DataSerializer.writeByteArray(cd.getSerializedValue(), out);
      } else {
        out.writeByte(IS_CACHED_DESER);
        DataSerializer.writeObjectAsByteArray(v, out);
      }
      out.writeByte(op.ordinal);
      byte bits = flags;
      if (filterRouting != null) {
        bits |= FILTER_ROUTING;
      }
      if (versionTag != null) {
        bits |= VERSION_TAG;
        if (versionTag instanceof DiskVersionTag) {
          bits |= PERSISTENT_TAG;
        }
      }
      // TODO: Yogesh, this should be conditional,
      // make sure that we sent it on wire only
      // when parallel wan is enabled
      bits |= HAS_TAILKEY;
      out.writeByte(bits);

      if (filterRouting != null) {
        context.getSerializer().writeObject(filterRouting, out);
      }
      if (versionTag != null) {
        InternalDataSerializer.invokeToData(versionTag, out);
      }
      if (isUsedFakeEventId()) {
        // fake event id should be serialized
        InternalDataSerializer.invokeToData(eventID, out);
      }
      // TODO: Yogesh, this should be conditional,
      // make sure that we sent it on wire only
      // when parallel wan is enabled
      DataSerializer.writeLong(tailKey, out);
    }

    /**
     * Returns the key
     */
    public Object getKey() {
      return key;
    }

    /**
     * Returns the value
     */
    public Object getValue(InternalCache cache) {
      Object result = value;
      if (result instanceof FutureCachedDeserializable) {
        FutureCachedDeserializable future = (FutureCachedDeserializable) result;
        result = future.create(cache);
        value = result;
      }
      return result;
    }

    /**
     * Returns the old value
     */
    public Object getOldValue() {
      return oldValue;
    }

    public Long getTailKey() {
      return tailKey;
    }

    public void setTailKey(Long key) {
      tailKey = key;
    }

    /**
     * Returns the operation
     */
    public Operation getOp() {
      return op;
    }

    public EventID getEventID() {
      return eventID;
    }

    /**
     * change event id for the entry
     *
     * @param eventId new event id
     */
    public void setEventId(EventID eventId) {
      eventID = eventId;
    }

    /**
     * change bucket id for the entry
     *
     * @param bucketId new bucket id
     */
    public void setBucketId(Integer bucketId) {
      this.bucketId = bucketId;
    }

    /**
     * get bucket id for the entry
     *
     * @return bucket id
     */
    public Integer getBucketId() {
      return bucketId;
    }

    /**
     * change event id into fake event id The algorithm is to change the threadid into
     * bucketid*MAX_THREAD_PER_CLIENT+oldthreadid. So from the log, we can derive the original
     * thread id.
     *
     * @return wether current event id is fake or not new bucket id
     */
    public boolean setFakeEventID() {
      if (bucketId < 0) {
        return false;
      }

      if (!isUsedFakeEventId()) {
        // assign a fake big thread id. bucket id starts from 0. In order to distinguish
        // with other read thread, let bucket id starts from 1 in fake thread id
        long threadId = ThreadIdentifier.createFakeThreadIDForBulkOp(bucketId,
            eventID.getThreadID());
        eventID = new EventID(eventID.getMembershipID(), threadId, eventID.getSequenceID());
        setUsedFakeEventId(true);
      }
      return true;
    }

    public boolean isUsedFakeEventId() {
      return (flags & USED_FAKE_EVENT_ID) != 0;
    }

    public void setUsedFakeEventId(boolean usedFakeEventId) {
      if (usedFakeEventId) {
        flags |= USED_FAKE_EVENT_ID;
      } else {
        flags &= ~(USED_FAKE_EVENT_ID);
      }
    }

    public boolean isNotifyOnly() {
      return (flags & NOTIFY_ONLY) != 0;
    }

    public void setNotifyOnly(boolean notifyOnly) {
      if (notifyOnly) {
        flags |= NOTIFY_ONLY;
      } else {
        flags &= ~(NOTIFY_ONLY);
      }
    }

    boolean isPossibleDuplicate() {
      return (flags & POSDUP) != 0;
    }

    public void setPossibleDuplicate(boolean possibleDuplicate) {
      if (possibleDuplicate) {
        flags |= POSDUP;
      } else {
        flags &= ~(POSDUP);
      }
    }

    public boolean isInhibitDistribution() {
      return inhibitDistribution;
    }

    public void setInhibitDistribution(boolean inhibitDistribution) {
      this.inhibitDistribution = inhibitDistribution;
    }

    public boolean isCallbacksInvoked() {
      return callbacksInvoked;
    }

    public void setCallbacksInvoked(boolean callbacksInvoked) {
      this.callbacksInvoked = callbacksInvoked;
    }
  }

  public static class EntryVersionsList extends ArrayList<VersionTag>
      implements DataSerializableFixedID, Externalizable {

    public EntryVersionsList() {
      // Do nothing
    }

    public EntryVersionsList(int size) {
      super(size);
    }

    public static EntryVersionsList create(DataInput in)
        throws IOException, ClassNotFoundException {
      EntryVersionsList newList = new EntryVersionsList();
      InternalDataSerializer.invokeFromData(newList, in);
      return newList;
    }

    private boolean extractVersion(PutAllEntryData entry) {

      VersionTag versionTag = entry.versionTag;
      // version tag can be null if only keys are sent in InitialImage.
      if (versionTag != null) {
        add(versionTag);
        // Add entry without version tag in entries array.
        entry.versionTag = null;
        return true;
      }

      return false;
    }

    private VersionTag<VersionSource> getVersionTag(int index) {
      VersionTag tag = null;
      if (size() > 0) {
        tag = get(index);
      }
      return tag;
    }

    /**
     * replace null membership IDs in version tags with the given member ID. VersionTags received
     * from a server may have null IDs because they were operations performed by that server. We
     * transmit them as nulls to cut costs, but have to do the swap on the receiving end (in the
     * client)
     *
     */
    public void replaceNullIDs(DistributedMember sender) {
      for (VersionTag versionTag : this) {
        if (versionTag != null) {
          versionTag.replaceNullIDs((InternalDistributedMember) sender);
        }
      }
    }

    @Override
    public int getDSFID() {
      return DataSerializableFixedID.PUTALL_VERSIONS_LIST;
    }

    static final byte FLAG_NULL_TAG = 0;
    static final byte FLAG_FULL_TAG = 1;
    static final byte FLAG_TAG_WITH_NEW_ID = 2;
    static final byte FLAG_TAG_WITH_NUMBER_ID = 3;

    @Override
    public void toData(DataOutput out,
        SerializationContext context) throws IOException {
      int flags = 0;
      boolean hasTags = false;

      if (size() > 0) {
        flags |= 0x04;
        hasTags = true;
        for (VersionTag tag : this) {
          if (tag != null) {
            if (tag instanceof DiskVersionTag) {
              flags |= 0x20;
            }
            break;
          }
        }
      }

      if (logger.isTraceEnabled(LogMarker.INITIAL_IMAGE_VERSIONED_VERBOSE)) {
        logger.trace(LogMarker.INITIAL_IMAGE_VERSIONED_VERBOSE, "serializing {} with flags 0x{}",
            this, Integer.toHexString(flags));
      }

      out.writeByte(flags);

      if (hasTags) {
        InternalDataSerializer.writeUnsignedVL(size(), out);
        Object2IntOpenHashMap ids = new Object2IntOpenHashMap(size());
        int idCount = 0;
        for (VersionTag tag : this) {
          if (tag == null) {
            out.writeByte(FLAG_NULL_TAG);
          } else {
            VersionSource id = tag.getMemberID();
            if (id == null) {
              out.writeByte(FLAG_FULL_TAG);
              InternalDataSerializer.invokeToData(tag, out);
            } else {
              int idNumber = ids.getInt(id);
              if (idNumber == 0) {
                out.writeByte(FLAG_TAG_WITH_NEW_ID);
                idNumber = ++idCount;
                ids.put(id, idNumber);
                InternalDataSerializer.invokeToData(tag, out);
              } else {
                out.writeByte(FLAG_TAG_WITH_NUMBER_ID);
                tag.toData(out, false);
                tag.setMemberID(id);
                InternalDataSerializer.writeUnsignedVL(idNumber - 1, out);
              }
            }
          }
        }
      }
    }

    @Override
    public void fromData(DataInput in,
        DeserializationContext context) throws IOException, ClassNotFoundException {
      int flags = in.readByte();
      boolean hasTags = (flags & 0x04) == 0x04;
      boolean persistent = (flags & 0x20) == 0x20;

      if (logger.isTraceEnabled(LogMarker.INITIAL_IMAGE_VERSIONED_VERBOSE)) {
        logger.trace(LogMarker.INITIAL_IMAGE_VERSIONED_VERBOSE,
            "deserializing a InitialImageVersionedObjectList with flags 0x{}",
            Integer.toHexString(flags));
      }

      if (hasTags) {
        int size = (int) InternalDataSerializer.readUnsignedVL(in);
        if (logger.isTraceEnabled(LogMarker.INITIAL_IMAGE_VERSIONED_VERBOSE)) {
          logger.trace(LogMarker.INITIAL_IMAGE_VERSIONED_VERBOSE, "reading {} version tags", size);
        }
        List<VersionSource> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
          byte entryType = in.readByte();
          switch (entryType) {
            case FLAG_NULL_TAG:
              add(null);
              break;
            case FLAG_FULL_TAG:
              add(VersionTag.create(persistent, in));
              break;
            case FLAG_TAG_WITH_NEW_ID:
              VersionTag tag = VersionTag.create(persistent, in);
              ids.add(tag.getMemberID());
              add(tag);
              break;
            case FLAG_TAG_WITH_NUMBER_ID:
              tag = VersionTag.create(persistent, in);
              int idNumber = (int) InternalDataSerializer.readUnsignedVL(in);
              tag.setMemberID(ids.get(idNumber));
              add(tag);
              break;
          }
        }
      }
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      toData(out, InternalDataSerializer.createSerializationContext(out));
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
      fromData(in, InternalDataSerializer.createDeserializationContext(in));
    }

    @Override
    public KnownVersion[] getSerializationVersions() {
      return null;
    }
  }

  @Override
  protected FilterRoutingInfo getRecipientFilterRouting(Set cacheOpRecipients) {
    // for putAll, we need to determine the routing information for each event and
    // create a consolidated routing object representing all events that can be
    // used for distribution
    CacheDistributionAdvisor advisor;
    LocalRegion region = (LocalRegion) event.getRegion();
    if (region instanceof PartitionedRegion) {
      advisor = ((PartitionedRegion) region).getCacheDistributionAdvisor();
    } else if (region.isUsedForPartitionedRegionBucket()) {
      advisor = region.getPartitionedRegion().getCacheDistributionAdvisor();
    } else {
      advisor = ((DistributedRegion) region).getCacheDistributionAdvisor();
    }
    FilterRoutingInfo consolidated = new FilterRoutingInfo();
    for (int i = 0; i < putAllData.length; i++) {
      @Unretained
      EntryEventImpl ev = getEventForPosition(i);
      if (ev != null) {
        FilterRoutingInfo eventRouting = advisor.adviseFilterRouting(ev, cacheOpRecipients);
        if (eventRouting != null) {
          consolidated.addFilterInfo(eventRouting);
        }
        putAllData[i].filterRouting = eventRouting;
      }
    }
    // we need to create routing information for each PUT event
    return consolidated;
  }

  @Override
  void doRemoveDestroyTokensFromCqResultKeys(FilterInfo filterInfo, ServerCQ cq) {
    for (Map.Entry<Long, Integer> e : filterInfo.getCQs().entrySet()) {
      Long cqID = e.getKey();
      // For the CQs satisfying the event with destroy CQEvent, remove
      // the entry from CQ cache.
      for (int i = 0; i < putAllData.length; i++) {
        @Unretained
        EntryEventImpl entryEvent = getEventForPosition(i);
        if (entryEvent != null && entryEvent.getKey() != null && cq != null
            && cq.getFilterID() != null && cq.getFilterID().equals(cqID)
            && e.getValue() != null && e.getValue().equals(MessageType.LOCAL_DESTROY)) {
          cq.removeFromCqResultKeys(entryEvent.getKey(), true);
        }
      }
    }
  }

  @Override
  protected FilterInfo getLocalFilterRouting(FilterRoutingInfo frInfo) {
    // long start = NanoTimer.getTime();
    FilterProfile fp = getRegion().getFilterProfile();
    if (fp == null) {
      return null;
    }

    // this will set the local FilterInfo in the events
    if (putAllData != null && putAllData.length > 0) {
      fp.getLocalFilterRoutingForPutAllOp(this, putAllData);
    }

    // long finish = NanoTimer.getTime();
    // InternalDistributedSystem.getDMStats().incjChannelUpTime(finish-start);
    return null;
  }

  @Override
  protected CacheOperationMessage createMessage() {
    EntryEventImpl event = getBaseEvent();
    PutAllMessage msg = new PutAllMessage();
    msg.eventId = event.getEventId();
    msg.context = event.getContext();
    return msg;
  }

  /**
   * Create PutAllPRMessage for notify only (to adjunct nodes)
   *
   * @param bucketId create message to send to this bucket
   */
  public PutAllPRMessage createPRMessagesNotifyOnly(int bucketId) {
    final EntryEventImpl event = getBaseEvent();
    PutAllPRMessage prMsg = new PutAllPRMessage(bucketId, putAllDataSize, true,
        event.isPossibleDuplicate(), !event.isGenerateCallbacks(), event.getCallbackArgument());
    if (event.getContext() != null) {
      prMsg.setBridgeContext(event.getContext());
    }

    // will not recover event id here
    for (int i = 0; i < putAllDataSize; i++) {
      prMsg.addEntry(putAllData[i]);
    }

    return prMsg;
  }

  /**
   * Create PutAllPRMessages for primary buckets out of dpao
   *
   * @return a HashMap contain PutAllPRMessages, key is bucket id
   */
  public HashMap createPRMessages() {
    // getFilterRecipients(Collections.EMPTY_SET); // establish filter recipient routing information
    HashMap prMsgMap = new HashMap();
    final EntryEventImpl event = getBaseEvent();

    for (int i = 0; i < putAllDataSize; i++) {
      Integer bucketId = putAllData[i].bucketId;
      PutAllPRMessage prMsg = (PutAllPRMessage) prMsgMap.get(bucketId);
      if (prMsg == null) {
        prMsg = new PutAllPRMessage(bucketId, putAllDataSize, false,
            event.isPossibleDuplicate(), !event.isGenerateCallbacks(), event.getCallbackArgument());
        prMsg
            .setTransactionDistributed(event.getRegion().getCache().getTxManager().isDistributed());

        // set dpao's context(original sender) into each PutAllMsg
        // dpao's event's context could be null if it's P2P putAll in PR
        if (event.getContext() != null) {
          prMsg.setBridgeContext(event.getContext());
        }
      }

      // Modify the event id, assign new thread id and new sequence id
      // We have to set fake event id here, because we cannot derive old event id from baseId+idx as
      // we
      // did in DR's PutAllMessage.
      putAllData[i].setFakeEventID();
      // we only save the reference in prMsg. No duplicate copy
      prMsg.addEntry(putAllData[i]);
      prMsgMap.put(bucketId, prMsg);
    }
    return prMsgMap;
  }

  @Override
  protected void initMessage(CacheOperationMessage msg, DirectReplyProcessor proc) {
    super.initMessage(msg, proc);
    PutAllMessage m = (PutAllMessage) msg;

    // if concurrency checks are enabled and this is not a replicated
    // region we need to see if any of the entries have no versions and,
    // if so, cull them out and send a 1-hop message to a replicate that
    // can generate a version for the operation

    RegionAttributes attr = event.getRegion().getAttributes();
    if (attr.getConcurrencyChecksEnabled() && !attr.getDataPolicy().withReplication()
        && attr.getScope() != Scope.GLOBAL) {
      if (attr.getDataPolicy() == DataPolicy.EMPTY) {
        // all entries are without version tags
        boolean success = RemotePutAllMessage.distribute((EntryEventImpl) event,
            putAllData, putAllDataSize);
        if (success) {
          m.callbackArg = event.getCallbackArgument();
          m.putAllData = new PutAllEntryData[0];
          m.putAllDataSize = 0;
          m.skipCallbacks = !event.isGenerateCallbacks();

          return;

        } else if (!getRegion().getGenerateVersionTag()) {
          // Fix for #45934. We can't continue if we need versions and we failed
          // to distribute versionless entries.
          throw new PersistentReplicatesOfflineException();
        }
      } else {
        // some entries may have Create ops - these will not have version tags
        PutAllEntryData[] versionless = selectVersionlessEntries();
        if (logger.isTraceEnabled()) {
          logger.trace("Found these versionless entries: {}", Arrays.toString(versionless));
        }
        if (versionless.length > 0) {
          boolean success = RemotePutAllMessage.distribute((EntryEventImpl) event, versionless,
              versionless.length);
          if (success) {
            versionless = null;
            PutAllEntryData[] versioned = selectVersionedEntries();
            if (logger.isTraceEnabled()) {
              logger.trace("Found these remaining versioned entries: {}",
                  Arrays.toString(versioned));
            }
            m.callbackArg = event.getCallbackArgument();
            m.putAllData = versioned;
            m.putAllDataSize = versioned.length;
            m.skipCallbacks = !event.isGenerateCallbacks();
            return;

          } else if (!getRegion().getGenerateVersionTag()) {
            // Fix for #45934. We can't continue if we need versions and we failed
            // to distribute versionless entries.
            throw new PersistentReplicatesOfflineException();
          }
        } else {
          if (logger.isDebugEnabled()) {
            logger.debug("All entries have versions, so using normal DPAO message");
          }
        }
      }
    }
    m.callbackArg = event.getCallbackArgument();
    m.putAllData = putAllData;
    m.putAllDataSize = putAllDataSize;
    m.skipCallbacks = !event.isGenerateCallbacks();
  }


  @Override
  protected boolean shouldAck() {
    // bug #45704 - RemotePutAllOp's DPAO in another server conflicts with lingering DPAO from same
    // thread, so
    // we require an ACK if concurrency checks are enabled to make sure that the previous op has
    // finished first.
    return super.shouldAck() || getRegion().getConcurrencyChecksEnabled();
  }

  private PutAllEntryData[] selectVersionlessEntries() {
    int resultSize = putAllData.length;
    for (PutAllEntryData p : putAllData) {
      if (p == null || p.isInhibitDistribution()) {
        resultSize--;
      } else if (p.versionTag != null && p.versionTag.hasValidVersion()) {
        resultSize--;
      }
    }
    PutAllEntryData[] result = new PutAllEntryData[resultSize];
    int ri = 0;
    for (PutAllEntryData p : putAllData) {
      if (p == null || p.isInhibitDistribution()) {
        continue; // skip blanks
      } else if (p.versionTag != null && p.versionTag.hasValidVersion()) {
        continue; // skip versioned
      }
      // what remains is versionless
      result[ri++] = p;
    }
    return result;
  }

  private PutAllEntryData[] selectVersionedEntries() {
    int resultSize = 0;
    for (PutAllEntryData p : putAllData) {
      if (p == null || p.isInhibitDistribution()) {
        continue; // skip blanks
      } else if (p.versionTag != null && p.versionTag.hasValidVersion()) {
        resultSize++;
      }
    }
    PutAllEntryData[] result = new PutAllEntryData[resultSize];
    int ri = 0;
    for (PutAllEntryData p : putAllData) {
      if (p == null || p.isInhibitDistribution()) {
        continue; // skip blanks
      } else if (p.versionTag != null && p.versionTag.hasValidVersion()) {
        result[ri++] = p;
      }
    }
    return result;
  }

  /**
   * version tags are gathered from local operations and remote operation responses. This method
   * gathers all of them and stores them in the given list.
   *
   */
  protected void fillVersionedObjectList(VersionedObjectList list) {
    for (PutAllEntryData entry : putAllData) {
      if (entry.versionTag != null) {
        list.addKeyAndVersion(entry.key, entry.versionTag);
      }
    }
  }


  public static class PutAllMessage extends AbstractUpdateMessage {

    protected PutAllEntryData[] putAllData;

    protected int putAllDataSize;

    protected transient ClientProxyMembershipID context;

    protected boolean skipCallbacks;

    protected EventID eventId = null;

    protected static final short HAS_BRIDGE_CONTEXT = UNRESERVED_FLAGS_START;
    protected static final short SKIP_CALLBACKS = (short) (HAS_BRIDGE_CONTEXT << 1);

    /** test to see if this message holds any data */
    public boolean isEmpty() {
      return putAllData.length == 0;
    }

    /**
     * Note this this is a "dummy" event since this message contains a list of entries each one of
     * which has its own event. The key thing needed in this event is the region. This is the event
     * that gets passed to basicOperateOnRegion
     */
    @Override
    @Retained
    protected InternalCacheEvent createEvent(DistributedRegion rgn) throws EntryNotFoundException {
      // Gester: We have to specify eventId for the message of MAP
      @Retained
      EntryEventImpl event = EntryEventImpl.create(rgn, Operation.PUTALL_UPDATE /* op */,
          null /* key */, null/* value */, callbackArg, true /* originRemote */, getSender());
      if (context != null) {
        event.context = context;
      }
      event.setPossibleDuplicate(possibleDuplicate);
      event.setEventId(eventId);
      return event;
    }

    @Override
    public void appendFields(StringBuilder sb) {
      super.appendFields(sb);
      if (eventId != null) {
        sb.append("; eventId=").append(eventId);
      }
      sb.append("; entries=").append(putAllDataSize);
      if (putAllDataSize <= 20) {
        // 20 is a size for test
        sb.append("; entry values=").append(Arrays.toString(putAllData));
      }
    }

    /**
     * Does the "put" of one entry for a "putall" operation. Note it calls back to
     * AbstractUpdateOperation.UpdateMessage#basicOperationOnRegion
     *
     * @param entry the entry being put
     * @param rgn the region the entry is put in
     */
    public void doEntryPut(PutAllEntryData entry, DistributedRegion rgn) {
      @Released
      EntryEventImpl ev = PutAllMessage.createEntryEvent(entry, getSender(), context, rgn,
          possibleDuplicate, needsRouting, callbackArg, true, skipCallbacks);
      // we don't need to set old value here, because the msg is from remote. local old value will
      // get from next step
      try {
        super.basicOperateOnRegion(ev, rgn);
      } finally {
        if (ev.hasValidVersionTag() && !ev.getVersionTag().isRecorded()) {
          if (rgn.getVersionVector() != null) {
            rgn.getVersionVector().recordVersion(getSender(), ev.getVersionTag());
          }
        }
        ev.release();
      }
    }

    /**
     * create an event for a PutAllEntryData element
     *
     * @return the event to be used in applying the element
     */
    @Retained
    public static EntryEventImpl createEntryEvent(PutAllEntryData entry,
        InternalDistributedMember sender, ClientProxyMembershipID context, DistributedRegion rgn,
        boolean possibleDuplicate, boolean needsRouting, Object callbackArg, boolean originRemote,
        boolean skipCallbacks) {
      final Object key = entry.getKey();
      EventID evId = entry.getEventID();
      @Retained
      EntryEventImpl ev = EntryEventImpl.create(rgn, entry.getOp(), key, null/* value */,
          callbackArg, originRemote, sender, !skipCallbacks, evId);
      boolean returnedEv = false;
      try {
        if (context != null) {
          ev.context = context;
        }
        Object entryValue = entry.getValue(rgn.getCache());
        if (entryValue == null && rgn.getDataPolicy() == DataPolicy.NORMAL) {
          ev.setLocalInvalid(true);
        }
        ev.setNewValue(entryValue);
        ev.setPossibleDuplicate(possibleDuplicate);
        ev.setVersionTag(entry.versionTag);
        // if (needsRouting) {
        // FilterProfile fp = rgn.getFilterProfile();
        // if (fp != null) {
        // FilterInfo fi = fp.getLocalFilterRouting(ev);
        // ev.setLocalFilterInfo(fi);
        // }
        // }
        if (entry.filterRouting != null) {
          InternalDistributedMember id = rgn.getMyId();
          ev.setLocalFilterInfo(entry.filterRouting.getFilterInfo(id));
        }
        /*
         * Setting tailKey for the secondary bucket here. Tail key was update by the primary.
         */
        ev.setTailKey(entry.getTailKey());
        returnedEv = true;
        return ev;
      } finally {
        if (!returnedEv) {
          ev.release();
        }
      }
    }

    @Override
    protected void basicOperateOnRegion(EntryEventImpl ev, final DistributedRegion rgn) {
      for (int i = 0; i < putAllDataSize; ++i) {
        if (putAllData[i].versionTag != null) {
          checkVersionTag(rgn, putAllData[i].versionTag);
        }
      }

      rgn.syncBulkOp(() -> {
        final boolean isDebugEnabled = logger.isDebugEnabled();
        for (int i = 0; i < putAllDataSize; ++i) {
          if (isDebugEnabled) {
            logger.debug("putAll processing {} with {} sender={}", putAllData[i],
                putAllData[i].versionTag, sender);
          }
          putAllData[i].setSender(sender);
          doEntryPut(putAllData[i], rgn);
        }
      }, ev.getEventId());
    }

    @Override
    public int getDSFID() {
      return PUT_ALL_MESSAGE;
    }

    @Override
    public void fromData(DataInput in,
        DeserializationContext context) throws IOException, ClassNotFoundException {

      super.fromData(in, context);
      eventId = context.getDeserializer().readObject(in);
      putAllDataSize = (int) InternalDataSerializer.readUnsignedVL(in);
      putAllData = new PutAllEntryData[putAllDataSize];
      if (putAllDataSize > 0) {
        final KnownVersion version = StaticSerialization.getVersionForDataStreamOrNull(in);
        final ByteArrayDataInput bytesIn = new ByteArrayDataInput();
        for (int i = 0; i < putAllDataSize; i++) {
          putAllData[i] = new PutAllEntryData(in, context, eventId, i);
        }

        boolean hasTags = in.readBoolean();
        if (hasTags) {
          EntryVersionsList versionTags = EntryVersionsList.create(in);
          for (int i = 0; i < putAllDataSize; i++) {
            putAllData[i].versionTag = versionTags.get(i);
          }
        }
      }

      if ((flags & HAS_BRIDGE_CONTEXT) != 0) {
        this.context = context.getDeserializer().readObject(in);
      }
      skipCallbacks = (flags & SKIP_CALLBACKS) != 0;
    }

    @Override
    public void toData(DataOutput out,
        SerializationContext context) throws IOException {
      super.toData(out, context);
      context.getSerializer().writeObject(eventId, out);
      InternalDataSerializer.writeUnsignedVL(putAllDataSize, out);
      if (putAllDataSize > 0) {
        EntryVersionsList versionTags = new EntryVersionsList(putAllDataSize);

        boolean hasTags = false;
        for (int i = 0; i < putAllDataSize; i++) {
          if (!hasTags && putAllData[i].versionTag != null) {
            hasTags = true;
          }
          VersionTag<?> tag = putAllData[i].versionTag;
          versionTags.add(tag);
          putAllData[i].versionTag = null;
          putAllData[i].toData(out, context);
          putAllData[i].versionTag = tag;
        }

        out.writeBoolean(hasTags);
        if (hasTags) {
          InternalDataSerializer.invokeToData(versionTags, out);
        }
      }
      if (this.context != null) {
        context.getSerializer().writeObject(this.context, out);
      }
    }

    @Override
    protected short computeCompressedShort(short s) {
      s = super.computeCompressedShort(s);
      if (context != null) {
        s |= HAS_BRIDGE_CONTEXT;
      }
      if (skipCallbacks) {
        s |= SKIP_CALLBACKS;
      }
      return s;
    }

    public ClientProxyMembershipID getContext() {
      return context;
    }

    public PutAllEntryData[] getPutAllEntryData() {
      return putAllData;
    }

  }
}
