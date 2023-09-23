// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.starrocks.connector.iceberg.cost;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.types.Comparators;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

/**
 * Aggregated statistics for a collection of files.
 */
public class IcebergFileStats {
    private Map<Integer, Type.PrimitiveType> idToTypeMapping;
    private List<Types.NestedField> nonPartitionPrimitiveColumns;
    private StructLike values;
    private long recordCount;
    private long fileCount;
    private long size;
    private Map<Integer, Object> minValues;
    private Map<Integer, Object> maxValues;
    private Map<Integer, Long> nullCounts;
    private Map<Integer, Long> columnSizes;
    private Set<Integer> corruptedStats;
    private boolean hasValidColumnMetrics;

    public IcebergFileStats(
            Map<Integer, Type.PrimitiveType> idToTypeMapping,
            List<Types.NestedField> nonPartitionPrimitiveColumns,
            StructLike values,
            long recordCount,
            long size,
            Map<Integer, Object> minValues,
            Map<Integer, Object> maxValues,
            Map<Integer, Long> nullCounts,
            Map<Integer, Long> columnSizes) {
        this.idToTypeMapping = ImmutableMap.copyOf(requireNonNull(idToTypeMapping, "idToTypeMapping is null"));
        this.nonPartitionPrimitiveColumns = ImmutableList.copyOf(
                requireNonNull(nonPartitionPrimitiveColumns, "nonPartitionPrimitiveColumns is null"));
        this.values = requireNonNull(values, "values is null");
        this.recordCount = recordCount;
        this.fileCount = 1;
        this.size = size;
        if (minValues == null || maxValues == null || nullCounts == null) {
            this.minValues = null;
            this.maxValues = null;
            this.nullCounts = null;
            this.columnSizes = null;
            corruptedStats = null;
        } else {
            this.minValues = new HashMap<>(minValues);
            this.maxValues = new HashMap<>(maxValues);
            // we are assuming if minValues is not present, max will be not be present either.
            this.corruptedStats = nonPartitionPrimitiveColumns.stream()
                    .map(Types.NestedField::fieldId)
                    .filter(id -> !minValues.containsKey(id) &&
                            (!nullCounts.containsKey(id) || nullCounts.get(id) != recordCount))
                    .collect(toSet());
            this.nullCounts = new HashMap<>(nullCounts);
            this.columnSizes = columnSizes != null ? new HashMap<>(columnSizes) : null;
            hasValidColumnMetrics = true;
        }
    }

    public IcebergFileStats(long recordCount) {
        this.recordCount = recordCount;
    }

    public Map<Integer, Type.PrimitiveType> getIdToTypeMapping() {
        return idToTypeMapping;
    }

    public List<Types.NestedField> getNonPartitionPrimitiveColumns() {
        return nonPartitionPrimitiveColumns;
    }

    public StructLike getValues() {
        return values;
    }

    public long getRecordCount() {
        return recordCount;
    }

    public long getFileCount() {
        return fileCount;
    }

    public long getSize() {
        return size;
    }

    public Map<Integer, Object> getMinValues() {
        return minValues;
    }

    public Optional<Double> getMinValue(Integer fieldId) {
        return getBoundStatistic(fieldId, minValues);
    }

    public boolean canUseStats(Integer fieldId, Map<Integer, Object> values) {
        if (idToTypeMapping == null || values == null) {
            return false;
        }

        if (idToTypeMapping.get(fieldId) == null || values.get(fieldId) == null) {
            return false;
        }

        return true;
    }

    private Optional<Double> getBoundStatistic(Integer fieldId, Map<Integer, Object> boundValues) {
        if (idToTypeMapping == null || boundValues == null) {
            return Optional.empty();
        }
        if (idToTypeMapping.get(fieldId) == null || boundValues.get(fieldId) == null) {
            return Optional.empty();
        }
        Type.PrimitiveType type = idToTypeMapping.get(fieldId);
        Object value = boundValues.get(fieldId);
        return convertObjectToOptionalDouble(type, value);
    }


    public Optional<Double> getMaxValue(Integer fieldId) {
        return getBoundStatistic(fieldId, maxValues);
    }

    public Map<Integer, Object> getMaxValues() {
        return maxValues;
    }

    public Map<Integer, Long> getNullCounts() {
        return nullCounts;
    }

    public Map<Integer, Long> getColumnSizes() {
        return columnSizes;
    }

    public Set<Integer> getCorruptedStats() {
        return corruptedStats;
    }

    public boolean hasValidColumnMetrics() {
        return hasValidColumnMetrics;
    }

    public void incrementRecordCount(long count) {
        this.recordCount += count;
    }

    public void incrementFileCount() {
        this.fileCount++;
    }

    public void incrementSize(long numberOfBytes) {
        this.size += numberOfBytes;
    }

    public void updateStats(Map<Integer, Object> current,
                            Map<Integer, Object> newStat,
                            Map<Integer, Long> nullCounts,
                            long recordCount,
                            Predicate<Integer> predicate) {
        if (!hasValidColumnMetrics) {
            return;
        }
        if (newStat == null || nullCounts == null) {
            hasValidColumnMetrics = false;
            return;
        }
        for (Types.NestedField column : nonPartitionPrimitiveColumns) {
            int id = column.fieldId();

            if (corruptedStats.contains(id)) {
                continue;
            }

            Object newValue = newStat.get(id);
            // it is expected to not have min/max if all values are null for a column in the datafile and it is not a case of corrupted stats.
            if (newValue == null) {
                Long nullCount = nullCounts.get(id);
                if ((nullCount == null) || (nullCount != recordCount)) {
                    current.remove(id);
                    corruptedStats.add(id);
                }
                continue;
            }

            Object oldValue = current.putIfAbsent(id, newValue);
            if (oldValue != null) {
                Comparator<Object> comparator = Comparators.forType(idToTypeMapping.get(id));
                if (predicate.test(comparator.compare(oldValue, newValue))) {
                    current.put(id, newValue);
                }
            }
        }
    }

    public void updateNullCount(Map<Integer, Long> nullCounts) {
        if (!hasValidColumnMetrics) {
            return;
        }
        if (nullCounts == null) {
            hasValidColumnMetrics = false;
            return;
        }
        nullCounts.forEach((key, counts) ->
                this.nullCounts.merge(key, counts, Long::sum));
    }

    public static Map<Integer, Object> toMap(Map<Integer, Type.PrimitiveType> idToTypeMapping,
                                             Map<Integer, ByteBuffer> idToMetricMap) {
        if (idToMetricMap == null) {
            return null;
        }
        ImmutableMap.Builder<Integer, Object> map = ImmutableMap.builder();
        idToMetricMap.forEach((id, value) -> {
            // SR on Iceberg only support primitive type now, idToTypeMapping do not contains the corresponding id
            // for complex types like struct, map etc.
            Type.PrimitiveType type = idToTypeMapping.get(id);
            if (type != null) {
                map.put(id, Conversions.fromByteBuffer(type, value));
            }
        });
        return map.build();
    }

    public static Optional<Double> convertObjectToOptionalDouble(Type.PrimitiveType type, Object value) {
        double valueConvert = 0;
        if (type instanceof Types.BooleanType) {
            valueConvert = (boolean) value ? 1 : 0;
        } else if (type instanceof Types.IntegerType) {
            valueConvert = (int) value;
        } else if (type instanceof Types.LongType) {
            valueConvert = (long) value;
        } else if (type instanceof Types.FloatType) {
            valueConvert = (float) value;
        } else if (type instanceof Types.DoubleType) {
            valueConvert = (double) value;
        } else if (type instanceof Types.TimestampType) {
            // we deal iceberg TimestampType as seconds in columnstatistics
            // in iceberg it's microsecond
            valueConvert = ((long) value) / 1000000;
        } else if (type instanceof Types.DateType) {
            // we deal iceberg DateType as seconds in columnstatistics
            // in iceberg it's num of day from 1970-01-01
            valueConvert = ((long) ((int) value)) * 86400;
        } else if (type instanceof Types.DecimalType) {
            valueConvert = ((BigDecimal) value).doubleValue();
        } else {
            return Optional.empty();
        }

        return Optional.of(valueConvert);
    }
}
