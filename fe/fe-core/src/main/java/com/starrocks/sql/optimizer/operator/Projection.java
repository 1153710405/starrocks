// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.
package com.starrocks.sql.optimizer.operator;

import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.physical.AddDecodeNodeForDictStringRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class Projection {
    private final Map<ColumnRefOperator, ScalarOperator> columnRefMap;
    // Used for common operator compute result reuse, we need to compute
    // common sub operators firstly in BE
    private final Map<ColumnRefOperator, ScalarOperator> commonSubOperatorMap;

    public Projection(Map<ColumnRefOperator, ScalarOperator> columnRefMap) {
        this.columnRefMap = columnRefMap;
        this.commonSubOperatorMap = new HashMap<>();
    }

    public Projection(Map<ColumnRefOperator, ScalarOperator> columnRefMap,
                      Map<ColumnRefOperator, ScalarOperator> commonSubOperatorMap) {
        this.columnRefMap = columnRefMap;
        if (commonSubOperatorMap == null) {
            this.commonSubOperatorMap = new HashMap<>();
        } else {
            this.commonSubOperatorMap = commonSubOperatorMap;
        }
    }

    public List<ColumnRefOperator> getOutputColumns() {
        return new ArrayList<>(columnRefMap.keySet());
    }

    public ColumnRefSet getUsedColumns() {
        final ColumnRefSet usedColumns = new ColumnRefSet();
        columnRefMap.values().stream().forEach(e -> usedColumns.union(e.getUsedColumns()));
        return usedColumns;
    }

    public Map<ColumnRefOperator, ScalarOperator> getColumnRefMap() {
        return columnRefMap;
    }

    public Map<ColumnRefOperator, ScalarOperator> getCommonSubOperatorMap() {
        return commonSubOperatorMap;
    }

    // For sql: select *, to_bitmap(S_SUPPKEY) from table, we needn't apply global dict optimization
    // This method differ from `couldApplyStringDict` method is for ColumnRefOperator, we return false.
    public boolean needApplyStringDict(Set<Integer> childDictColumns) {
        ColumnRefSet dictSet = ColumnRefSet.createByIds(childDictColumns);

        for (ScalarOperator operator : columnRefMap.values()) {
            if (!operator.isColumnRef() && couldApplyStringDict(operator, dictSet, childDictColumns)) {
                return true;
            }
        }

        return false;
    }

    public static boolean couldApplyDictOptimize(ScalarOperator operator, Set<Integer> sids) {
        return AddDecodeNodeForDictStringRule.DecodeVisitor.couldApplyDictOptimize(operator, sids);
    }

    public static boolean cannotApplyDictOptimize(ScalarOperator operator, Set<Integer> sids) {
        return AddDecodeNodeForDictStringRule.DecodeVisitor.cannotApplyDictOptimize(operator, sids);
    }

    private boolean couldApplyStringDict(ScalarOperator operator, ColumnRefSet dictSet, Set<Integer> sids) {
        ColumnRefSet usedColumns = operator.getUsedColumns();
        if (usedColumns.isIntersect(dictSet)) {
            return couldApplyDictOptimize(operator, sids);
        }
        return false;
    }

    public void fillDisableDictOptimizeColumns(ColumnRefSet columnRefSet, Set<Integer> sids) {
        columnRefMap.forEach((k, v) -> {
            if (columnRefSet.contains(k.getId())) {
                columnRefSet.union(v.getUsedColumns());
            } else {
                fillDisableDictOptimizeColumns(v, columnRefSet, sids);
            }
        });
    }

    private void fillDisableDictOptimizeColumns(ScalarOperator operator, ColumnRefSet columnRefSet, Set<Integer> sids) {
        if (cannotApplyDictOptimize(operator, sids)) {
            columnRefSet.union(operator.getUsedColumns());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Projection that = (Projection) o;
        return columnRefMap.keySet().equals(that.columnRefMap.keySet());
    }

    @Override
    public int hashCode() {
        return Objects.hash(columnRefMap.keySet());
    }

    @Override
    public String toString() {
        return columnRefMap.values().toString();
    }
}
