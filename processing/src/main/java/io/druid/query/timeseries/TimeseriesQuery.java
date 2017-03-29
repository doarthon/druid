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

package io.druid.query.timeseries;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.druid.java.util.common.granularity.Granularity;
import io.druid.query.BaseQuery;
import io.druid.query.DataSource;
import io.druid.query.Druids;
import io.druid.query.Queries;
import io.druid.query.Query;
import io.druid.query.QueryMetrics;
import io.druid.query.Result;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.PostAggregator;
import io.druid.query.filter.DimFilter;
import io.druid.query.spec.QuerySegmentSpec;
import io.druid.segment.VirtualColumns;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 */
@JsonTypeName("timeseries")
public class TimeseriesQuery extends BaseQuery<Result<TimeseriesResultValue>>
{
  private final VirtualColumns virtualColumns;
  private final DimFilter dimFilter;
  private final Granularity granularity;
  private final List<AggregatorFactory> aggregatorSpecs;
  private final List<PostAggregator> postAggregatorSpecs;

  @JsonCreator
  public TimeseriesQuery(
      @JsonProperty("dataSource") DataSource dataSource,
      @JsonProperty("intervals") QuerySegmentSpec querySegmentSpec,
      @JsonProperty("descending") boolean descending,
      @JsonProperty("virtualColumns") VirtualColumns virtualColumns,
      @JsonProperty("filter") DimFilter dimFilter,
      @JsonProperty("granularity") Granularity granularity,
      @JsonProperty("aggregations") List<AggregatorFactory> aggregatorSpecs,
      @JsonProperty("postAggregations") List<PostAggregator> postAggregatorSpecs,
      @JsonProperty("context") Map<String, Object> context
  )
  {
    this(
        dataSource,
        querySegmentSpec,
        descending,
        virtualColumns,
        dimFilter,
        granularity,
        aggregatorSpecs,
        postAggregatorSpecs,
        context,
        null
    );
  }

  /**
   * This constructor is public only because {@link Druids.TimeseriesQueryBuilder} needs to access this constructor, and
   * it is defined in Druids rather than in as an inner class of TimeseriesQuery.
   */
  public TimeseriesQuery(
      final DataSource dataSource,
      final QuerySegmentSpec querySegmentSpec,
      final boolean descending,
      final VirtualColumns virtualColumns,
      final DimFilter dimFilter,
      final Granularity granularity,
      final List<AggregatorFactory> aggregatorSpecs,
      final List<PostAggregator> postAggregatorSpecs,
      final Map<String, Object> context,
      final QueryMetrics<?> queryMetrics
  )
  {
    super(dataSource, querySegmentSpec, descending, context, queryMetrics);
    TimeseriesQueryMetrics.class.cast(queryMetrics); // ClassCastException if not

    this.virtualColumns = VirtualColumns.nullToEmpty(virtualColumns);
    this.dimFilter = dimFilter;
    this.granularity = granularity;
    this.aggregatorSpecs = aggregatorSpecs == null ? ImmutableList.of() : aggregatorSpecs;
    this.postAggregatorSpecs = Queries.prepareAggregations(
        this.aggregatorSpecs,
        postAggregatorSpecs == null ? ImmutableList.of() : postAggregatorSpecs
    );
  }

  @Override
  public boolean hasFilters()
  {
    return dimFilter != null;
  }

  @Override
  public DimFilter getFilter()
  {
    return dimFilter;
  }

  @Override
  public String getType()
  {
    return Query.TIMESERIES;
  }

  @JsonProperty
  public VirtualColumns getVirtualColumns()
  {
    return virtualColumns;
  }

  @JsonProperty("filter")
  public DimFilter getDimensionsFilter()
  {
    return dimFilter;
  }

  @JsonProperty
  public Granularity getGranularity()
  {
    return granularity;
  }

  @JsonProperty("aggregations")
  public List<AggregatorFactory> getAggregatorSpecs()
  {
    return aggregatorSpecs;
  }

  @JsonProperty("postAggregations")
  public List<PostAggregator> getPostAggregatorSpecs()
  {
    return postAggregatorSpecs;
  }

  public boolean isSkipEmptyBuckets()
  {
    return getContextBoolean("skipEmptyBuckets", false);
  }

  public TimeseriesQuery withQuerySegmentSpec(QuerySegmentSpec querySegmentSpec)
  {
    return Druids.TimeseriesQueryBuilder.copy(this).intervals(querySegmentSpec).build();
  }

  @Override
  public Query<Result<TimeseriesResultValue>> withDataSource(DataSource dataSource)
  {
    return Druids.TimeseriesQueryBuilder.copy(this).dataSource(dataSource).build();
  }

  public TimeseriesQuery withOverriddenContext(Map<String, Object> contextOverrides)
  {
    Map<String, Object> newContext = computeOverriddenContext(getContext(), contextOverrides);
    return Druids.TimeseriesQueryBuilder.copy(this).context(newContext).build();
  }

  public TimeseriesQuery withDimFilter(DimFilter dimFilter)
  {
    return Druids.TimeseriesQueryBuilder.copy(this).filters(dimFilter).build();
  }

  @Override
  public Query<Result<TimeseriesResultValue>> withQueryMetrics(QueryMetrics<?> queryMetrics)
  {
    Preconditions.checkNotNull(queryMetrics);
    return Druids.TimeseriesQueryBuilder.copy(this).queryMetrics(queryMetrics).build();
  }

  @Override
  public String toString()
  {
    return "TimeseriesQuery{" +
           "dataSource='" + getDataSource() + '\'' +
           ", querySegmentSpec=" + getQuerySegmentSpec() +
           ", descending=" + isDescending() +
           ", virtualColumns=" + virtualColumns +
           ", dimFilter=" + dimFilter +
           ", granularity='" + granularity + '\'' +
           ", aggregatorSpecs=" + aggregatorSpecs +
           ", postAggregatorSpecs=" + postAggregatorSpecs +
           ", context=" + getContext() +
           '}';
  }

  @Override
  public boolean equals(final Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    final TimeseriesQuery that = (TimeseriesQuery) o;
    return Objects.equals(virtualColumns, that.virtualColumns) &&
           Objects.equals(dimFilter, that.dimFilter) &&
           Objects.equals(granularity, that.granularity) &&
           Objects.equals(aggregatorSpecs, that.aggregatorSpecs) &&
           Objects.equals(postAggregatorSpecs, that.postAggregatorSpecs);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(super.hashCode(), virtualColumns, dimFilter, granularity, aggregatorSpecs, postAggregatorSpecs);
  }
}
