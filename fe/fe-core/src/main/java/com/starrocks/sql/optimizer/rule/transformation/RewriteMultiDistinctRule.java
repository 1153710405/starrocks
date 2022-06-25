// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.
package com.starrocks.sql.optimizer.rule.transformation;

import com.google.common.collect.Lists;
import com.starrocks.analysis.FunctionName;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.Type;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.operator.AggType;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalAggregationOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalFilterOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalProjectOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriteRule;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriter;
import com.starrocks.sql.optimizer.rewrite.scalar.ImplicitCastRule;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.starrocks.catalog.Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF;

public class RewriteMultiDistinctRule extends TransformationRule {
    private static final List<ScalarOperatorRewriteRule> DEFAULT_TYPE_CAST_RULE = Lists.newArrayList(
            new ImplicitCastRule()
    );
    private final ScalarOperatorRewriter scalarRewriter = new ScalarOperatorRewriter();

    public RewriteMultiDistinctRule() {
        super(RuleType.TF_REWRITE_MULTI_DISTINCT,
                Pattern.create(OperatorType.LOGICAL_AGGR).addChildren(Pattern.create(
                        OperatorType.PATTERN_LEAF)));
    }

    @Override
    public boolean check(OptExpression input, OptimizerContext context) {
        LogicalAggregationOperator agg = (LogicalAggregationOperator) input.getOp();
        boolean hasNoGroup = agg.getGroupingKeys().isEmpty();
        // check cbo is enabled and hasNoGroup is true
        if (context.getSessionVariable().isCboCteReuse()) {
            return false;
        }

        List<CallOperator> distinctAggOperatorList = agg.getAggregations().values().stream()
                .filter(CallOperator::isDistinct).collect(Collectors.toList());

        boolean hasMultiColumns = distinctAggOperatorList.stream().anyMatch(f -> f.getChildren().size() > 1);
        return (distinctAggOperatorList.size() > 1 || agg.getAggregations().values().stream()
                .anyMatch(call -> call.isDistinct() && call.getFnName().equals(FunctionSet.AVG))) && !hasMultiColumns;
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalAggregationOperator aggregationOperator = (LogicalAggregationOperator) input.getOp();

        Map<ColumnRefOperator, CallOperator> newAggMap = new HashMap<>();
        for (Map.Entry<ColumnRefOperator, CallOperator> aggregation : aggregationOperator.getAggregations()
                .entrySet()) {
            CallOperator oldFunctionCall = aggregation.getValue();
            if (oldFunctionCall.isDistinct()) {
                CallOperator newAggOperator = oldFunctionCall;
                if (oldFunctionCall.getFnName().equalsIgnoreCase(FunctionSet.COUNT)) {
                    newAggOperator = buildMultiCountDistinct(oldFunctionCall);
                } else if (oldFunctionCall.getFnName().equalsIgnoreCase(FunctionSet.SUM)) {
                    newAggOperator = buildMultiSumDistinct(oldFunctionCall);
                }
                newAggMap.put(aggregation.getKey(), newAggOperator);
            } else {
                newAggMap.put(aggregation.getKey(), aggregation.getValue());
            }
        }

        /*
         * Repeat the loop once, because avg can use the newly generated aggregate function last time,
         * so that the expression can be reused. such as: count(distinct v1), avg(distinct v1), sum(distinct v1),
         * avg can use multi_distinct_x generated by count or sum
         */
        boolean hasAvg = false;
        Map<ColumnRefOperator, ScalarOperator> projections = new HashMap<>();
        Map<ColumnRefOperator, CallOperator> newAggMapWithAvg = new HashMap<>();
        for (Map.Entry<ColumnRefOperator, CallOperator> aggMap : newAggMap.entrySet()) {
            CallOperator oldFunctionCall = aggMap.getValue();
            if (oldFunctionCall.isDistinct() && oldFunctionCall.getFnName().equals(FunctionSet.AVG)) {
                hasAvg = true;
                CallOperator count = buildMultiCountDistinct(oldFunctionCall);
                ColumnRefOperator countColRef = null;
                for (Map.Entry<ColumnRefOperator, CallOperator> entry : newAggMap.entrySet()) {
                    if (entry.getValue().equals(count)) {
                        countColRef = entry.getKey();
                        break;
                    }
                }
                countColRef = countColRef == null ?
                        context.getColumnRefFactory().create(count, count.getType(), count.isNullable()) : countColRef;
                newAggMapWithAvg.put(countColRef, count);

                CallOperator sum = buildMultiSumDistinct(oldFunctionCall);
                ColumnRefOperator sumColRef = null;
                for (Map.Entry<ColumnRefOperator, CallOperator> entry : newAggMap.entrySet()) {
                    if (entry.getValue().equals(sum)) {
                        sumColRef = entry.getKey();
                        break;
                    }
                }
                sumColRef = sumColRef == null ?
                        context.getColumnRefFactory().create(sum, sum.getType(), sum.isNullable()) : sumColRef;
                newAggMapWithAvg.put(sumColRef, sum);

                CallOperator multiAgv = (CallOperator) scalarRewriter.rewrite(
                        new CallOperator("divide", oldFunctionCall.getType(),
                                Lists.newArrayList(sumColRef, countColRef)),
                        DEFAULT_TYPE_CAST_RULE);
                projections.put(aggMap.getKey(), multiAgv);
            } else {
                projections.put(aggMap.getKey(), aggMap.getKey());
                newAggMapWithAvg.put(aggMap.getKey(), aggMap.getValue());
            }
        }

        OptExpression result;
        if (hasAvg) {
            OptExpression aggOpt = OptExpression
                    .create(new LogicalAggregationOperator(AggType.GLOBAL, aggregationOperator.getGroupingKeys(),
                                    newAggMapWithAvg),
                            input.getInputs());
            aggregationOperator.getGroupingKeys().forEach(c -> projections.put(c, c));
            result = OptExpression.create(new LogicalProjectOperator(projections), Lists.newArrayList(aggOpt));
        } else {
            result = OptExpression
                    .create(new LogicalAggregationOperator(AggType.GLOBAL, aggregationOperator.getGroupingKeys(),
                                    newAggMap),
                            input.getInputs());
        }

        if (aggregationOperator.getPredicate() != null) {
            result = OptExpression.create(new LogicalFilterOperator(aggregationOperator.getPredicate()), result);
        }

        return Lists.newArrayList(result);
    }

    private CallOperator buildMultiCountDistinct(CallOperator oldFunctionCall) {
        Function searchDesc = new Function(new FunctionName(FunctionSet.MULTI_DISTINCT_COUNT),
                oldFunctionCall.getFunction().getArgs(), Type.INVALID, false);
        Function fn = GlobalStateMgr.getCurrentState().getFunction(searchDesc, IS_NONSTRICT_SUPERTYPE_OF);

        return (CallOperator) scalarRewriter.rewrite(
                new CallOperator(FunctionSet.MULTI_DISTINCT_COUNT, fn.getReturnType(), oldFunctionCall.getChildren(),
                        fn),
                DEFAULT_TYPE_CAST_RULE);
    }

    private CallOperator buildMultiSumDistinct(CallOperator oldFunctionCall) {
        Function searchDesc = new Function(new FunctionName(FunctionSet.MULTI_DISTINCT_SUM),
                oldFunctionCall.getFunction().getArgs(), Type.INVALID, false);
        Function fn = GlobalStateMgr.getCurrentState().getFunction(searchDesc, IS_NONSTRICT_SUPERTYPE_OF);

        return (CallOperator) scalarRewriter.rewrite(
                new CallOperator(FunctionSet.MULTI_DISTINCT_SUM, fn.getReturnType(), oldFunctionCall.getChildren(), fn),
                DEFAULT_TYPE_CAST_RULE);
    }
}
