/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.segment.incremental;

import com.google.common.base.Supplier;
import com.google.common.collect.Maps;
import io.druid.collections.ResourceHolder;
import io.druid.collections.StupidPool;
import io.druid.data.input.InputRow;
import io.druid.java.util.common.IAE;
import io.druid.java.util.common.ISE;
import io.druid.java.util.common.logger.Logger;
import io.druid.java.util.common.parsers.ParseException;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.BufferAggregator;
import io.druid.segment.ColumnSelectorFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 */
public class OffheapIncrementalIndex extends IncrementalIndex<BufferAggregator>
{
  private static final Logger log = new Logger(OffheapIncrementalIndex.class);

  private final StupidPool<ByteBuffer> bufferPool;

  private final List<ResourceHolder<ByteBuffer>> aggBuffers = new ArrayList<>();
  private final List<int[]> indexAndOffsets = new ArrayList<>();

  private final FactsHolder facts;

  private final AtomicInteger indexIncrement = new AtomicInteger(0);

  protected final int maxRowCount;

  private volatile Map<String, ColumnSelectorFactory> selectors;

  //given a ByteBuffer and an offset where all aggregates for a row are stored
  //offset + aggOffsetInBuffer[i] would give position in ByteBuffer where ith aggregate
  //is stored
  private volatile int[] aggOffsetInBuffer;
  private volatile int aggsTotalSize;

  private String outOfRowsReason = null;

  public OffheapIncrementalIndex(
      IncrementalIndexSchema incrementalIndexSchema,
      boolean deserializeComplexMetrics,
      boolean reportParseExceptions,
      boolean concurrentEventAdd,
      boolean sortFacts,
      int maxRowCount,
      StupidPool<ByteBuffer> bufferPool
  )
  {
    super(incrementalIndexSchema, deserializeComplexMetrics, reportParseExceptions, concurrentEventAdd);
    this.maxRowCount = maxRowCount;
    this.bufferPool = bufferPool;

    this.facts = incrementalIndexSchema.isRollup() ? new RollupFactsHolder(sortFacts, dimsComparator(), getDimensions())
                                                   : new PlainFactsHolder(sortFacts);

    //check that stupid pool gives buffers that can hold at least one row's aggregators
    ResourceHolder<ByteBuffer> bb = bufferPool.take();
    if (bb.get().capacity() < aggsTotalSize) {
      bb.close();
      throw new IAE("bufferPool buffers capacity must be >= [%s]", aggsTotalSize);
    }
    aggBuffers.add(bb);
  }

  public static class Builder
  {
    private IncrementalIndexSchema incrementalIndexSchema;
    private boolean deserializeComplexMetrics;
    private boolean reportParseExceptions;
    private boolean concurrentEventAdd;
    private boolean sortFacts;
    private int maxRowCount;
    private StupidPool<ByteBuffer> bufferPool;

    public Builder()
    {
      incrementalIndexSchema = null;
      deserializeComplexMetrics = true;
      reportParseExceptions = true;
      concurrentEventAdd = false;
      sortFacts = true;
      maxRowCount = 0;
      bufferPool = null;
    }

    public Builder setIncrementalIndexSchema(final IncrementalIndexSchema incrementalIndexSchema)
    {
      this.incrementalIndexSchema = incrementalIndexSchema;
      return this;
    }

    public Builder setDeserializeComplexMetrics(final boolean deserializeComplexMetrics)
    {
      this.deserializeComplexMetrics = deserializeComplexMetrics;
      return this;
    }

    public Builder setReportParseExceptions(final boolean reportParseExceptions)
    {
      this.reportParseExceptions = reportParseExceptions;
      return this;
    }

    public Builder setConcurrentEventAdd(final boolean concurrentEventAdd)
    {
      this.concurrentEventAdd = concurrentEventAdd;
      return this;
    }

    public Builder setSortFacts(final boolean sortFacts)
    {
      this.sortFacts = sortFacts;
      return this;
    }

    public Builder setMaxRowCount(final int maxRowCount)
    {
      this.maxRowCount = maxRowCount;
      return this;
    }

    public Builder setBufferPool(final StupidPool<ByteBuffer> bufferPool)
    {
      this.bufferPool = bufferPool;
      return this;
    }

    public OffheapIncrementalIndex build()
    {
      if (maxRowCount <= 0) {
        throw new IllegalArgumentException("Invalid max row count: " + maxRowCount);
      }

      return new OffheapIncrementalIndex(
          Objects.requireNonNull(incrementalIndexSchema, "incrementalIndexSchema is null"),
          deserializeComplexMetrics,
          reportParseExceptions,
          concurrentEventAdd,
          sortFacts,
          maxRowCount,
          Objects.requireNonNull(bufferPool, "bufferPool is null")
      );
    }
  }

  @Override
  public FactsHolder getFacts()
  {
    return facts;
  }

  @Override
  protected BufferAggregator[] initAggs(
      final AggregatorFactory[] metrics,
      final Supplier<InputRow> rowSupplier,
      final boolean deserializeComplexMetrics,
      final boolean concurrentEventAdd
  )
  {
    selectors = Maps.newHashMap();
    aggOffsetInBuffer = new int[metrics.length];

    for (int i = 0; i < metrics.length; i++) {
      AggregatorFactory agg = metrics[i];

      ColumnSelectorFactory columnSelectorFactory = makeColumnSelectorFactory(
          agg,
          rowSupplier,
          deserializeComplexMetrics
      );

      selectors.put(
          agg.getName(),
          new OnheapIncrementalIndex.ObjectCachingColumnSelectorFactory(columnSelectorFactory, concurrentEventAdd)
      );

      if (i == 0) {
        aggOffsetInBuffer[i] = 0;
      } else {
        aggOffsetInBuffer[i] = aggOffsetInBuffer[i-1] + metrics[i-1].getMaxIntermediateSize();
      }
    }

    aggsTotalSize = aggOffsetInBuffer[metrics.length - 1] + metrics[metrics.length - 1].getMaxIntermediateSize();

    return new BufferAggregator[metrics.length];
  }

  @Override
  protected Integer addToFacts(
      AggregatorFactory[] metrics,
      boolean deserializeComplexMetrics,
      boolean reportParseExceptions,
      InputRow row,
      AtomicInteger numEntries,
      TimeAndDims key,
      ThreadLocal<InputRow> rowContainer,
      Supplier<InputRow> rowSupplier
  ) throws IndexSizeExceededException
  {
    ByteBuffer aggBuffer;
    int bufferIndex;
    int bufferOffset;

    synchronized (this) {
      final int priorIndex = facts.getPriorIndex(key);
      if (TimeAndDims.EMPTY_ROW_INDEX != priorIndex) {
        final int[] indexAndOffset = indexAndOffsets.get(priorIndex);
        bufferIndex = indexAndOffset[0];
        bufferOffset = indexAndOffset[1];
        aggBuffer = aggBuffers.get(bufferIndex).get();
      } else {
        if (metrics.length > 0 && getAggs()[0] == null) {
          // note: creation of Aggregators is done lazily when at least one row from input is available
          // so that FilteredAggregators could be initialized correctly.
          rowContainer.set(row);
          for (int i = 0; i < metrics.length; i++) {
            final AggregatorFactory agg = metrics[i];
            getAggs()[i] = agg.factorizeBuffered(selectors.get(agg.getName()));
          }
          rowContainer.set(null);
        }

        bufferIndex = aggBuffers.size() - 1;
        ByteBuffer lastBuffer = aggBuffers.isEmpty() ? null : aggBuffers.get(aggBuffers.size() - 1).get();
        int[] lastAggregatorsIndexAndOffset = indexAndOffsets.isEmpty()
                                              ? null
                                              : indexAndOffsets.get(indexAndOffsets.size() - 1);

        if (lastAggregatorsIndexAndOffset != null && lastAggregatorsIndexAndOffset[0] != bufferIndex) {
          throw new ISE("last row's aggregate's buffer and last buffer index must be same");
        }

        bufferOffset = aggsTotalSize + (lastAggregatorsIndexAndOffset != null ? lastAggregatorsIndexAndOffset[1] : 0);
        if (lastBuffer != null &&
            lastBuffer.capacity() - bufferOffset >= aggsTotalSize) {
          aggBuffer = lastBuffer;
        } else {
          ResourceHolder<ByteBuffer> bb = bufferPool.take();
          aggBuffers.add(bb);
          bufferIndex = aggBuffers.size() - 1;
          bufferOffset = 0;
          aggBuffer = bb.get();
        }

        for (int i = 0; i < metrics.length; i++) {
          getAggs()[i].init(aggBuffer, bufferOffset + aggOffsetInBuffer[i]);
        }

        // Last ditch sanity checks
        if (numEntries.get() >= maxRowCount && facts.getPriorIndex(key) == TimeAndDims.EMPTY_ROW_INDEX) {
          throw new IndexSizeExceededException("Maximum number of rows [%d] reached", maxRowCount);
        }

        final Integer rowIndex = indexIncrement.getAndIncrement();

        // note that indexAndOffsets must be updated before facts, because as soon as we update facts
        // concurrent readers get hold of it and might ask for newly added row
        indexAndOffsets.add(new int[]{bufferIndex, bufferOffset});
        final int prev = facts.putIfAbsent(key, rowIndex);
        if (TimeAndDims.EMPTY_ROW_INDEX == prev) {
          numEntries.incrementAndGet();
        } else {
          throw new ISE("WTF! we are in sychronized block.");
        }
      }
    }

    rowContainer.set(row);

    for (int i = 0; i < metrics.length; i++) {
      final BufferAggregator agg = getAggs()[i];

      synchronized (agg) {
        try {
          agg.aggregate(aggBuffer, bufferOffset + aggOffsetInBuffer[i]);
        } catch (ParseException e) {
          // "aggregate" can throw ParseExceptions if a selector expects something but gets something else.
          if (reportParseExceptions) {
            throw new ParseException(e, "Encountered parse error for aggregator[%s]", getMetricAggs()[i].getName());
          } else {
            log.debug(e, "Encountered parse error, skipping aggregator[%s].", getMetricAggs()[i].getName());
          }
        }
      }
    }
    rowContainer.set(null);
    return numEntries.get();
  }

  @Override
  public int getLastRowIndex()
  {
    return indexIncrement.get() - 1;
  }

  @Override
  public boolean canAppendRow()
  {
    final boolean canAdd = size() < maxRowCount;
    if (!canAdd) {
      outOfRowsReason = String.format("Maximum number of rows [%d] reached", maxRowCount);
    }
    return canAdd;
  }

  @Override
  public String getOutOfRowsReason()
  {
    return outOfRowsReason;
  }

  @Override
  protected BufferAggregator[] getAggsForRow(int rowOffset)
  {
    return getAggs();
  }

  @Override
  protected Object getAggVal(BufferAggregator agg, int rowOffset, int aggPosition)
  {
    int[] indexAndOffset = indexAndOffsets.get(rowOffset);
    ByteBuffer bb = aggBuffers.get(indexAndOffset[0]).get();
    return agg.get(bb, indexAndOffset[1] + aggOffsetInBuffer[aggPosition]);
  }

  @Override
  public float getMetricFloatValue(int rowOffset, int aggOffset)
  {
    BufferAggregator agg = getAggs()[aggOffset];
    int[] indexAndOffset = indexAndOffsets.get(rowOffset);
    ByteBuffer bb = aggBuffers.get(indexAndOffset[0]).get();
    return agg.getFloat(bb, indexAndOffset[1] + aggOffsetInBuffer[aggOffset]);
  }

  @Override
  public long getMetricLongValue(int rowOffset, int aggOffset)
  {
    BufferAggregator agg = getAggs()[aggOffset];
    int[] indexAndOffset = indexAndOffsets.get(rowOffset);
    ByteBuffer bb = aggBuffers.get(indexAndOffset[0]).get();
    return agg.getLong(bb, indexAndOffset[1] + aggOffsetInBuffer[aggOffset]);
  }

  @Override
  public Object getMetricObjectValue(int rowOffset, int aggOffset)
  {
    BufferAggregator agg = getAggs()[aggOffset];
    int[] indexAndOffset = indexAndOffsets.get(rowOffset);
    ByteBuffer bb = aggBuffers.get(indexAndOffset[0]).get();
    return agg.get(bb, indexAndOffset[1] + aggOffsetInBuffer[aggOffset]);
  }

  /**
   * NOTE: This is NOT thread-safe with add... so make sure all the adding is DONE before closing
   */
  @Override
  public void close()
  {
    super.close();
    facts.clear();
    indexAndOffsets.clear();

    if (selectors != null) {
      selectors.clear();
    }

    RuntimeException ex = null;
    for (ResourceHolder<ByteBuffer> buffHolder : aggBuffers) {
      buffHolder.close();
    }
    aggBuffers.clear();
  }
}
