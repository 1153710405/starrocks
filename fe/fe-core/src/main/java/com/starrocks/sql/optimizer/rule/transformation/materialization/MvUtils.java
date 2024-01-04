// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

package com.starrocks.sql.optimizer.rule.transformation.materialization;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.starrocks.analysis.BinaryPredicate;
import com.starrocks.analysis.CompoundPredicate;
import com.starrocks.analysis.DateLiteral;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.IntLiteral;
import com.starrocks.analysis.JoinOperator;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.MvId;
import com.starrocks.catalog.MvPlanContext;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.RangePartitionInfo;
import com.starrocks.catalog.Table;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Pair;
import com.starrocks.common.UserException;
import com.starrocks.common.util.RangeUtils;
import com.starrocks.connector.PartitionUtil;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.Analyzer;
<<<<<<< HEAD
=======
import com.starrocks.sql.analyzer.RelationFields;
import com.starrocks.sql.analyzer.RelationId;
import com.starrocks.sql.analyzer.Scope;
import com.starrocks.sql.analyzer.SemanticException;
>>>>>>> 8fd6a085bf ([BugFix] Add more checks when schema changing has referred materialized views (backport #37388) (#38436))
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.optimizer.MvPlanContextBuilder;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptExpressionVisitor;
import com.starrocks.sql.optimizer.Optimizer;
import com.starrocks.sql.optimizer.OptimizerConfig;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.base.PhysicalPropertySet;
import com.starrocks.sql.optimizer.operator.AggType;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.ScanOperatorPredicates;
import com.starrocks.sql.optimizer.operator.logical.LogicalAggregationOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalFilterOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalHiveScanOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalJoinOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalProjectOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalScanOperator;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.CompoundPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperatorVisitor;
import com.starrocks.sql.optimizer.rewrite.ReplaceColumnRefRewriter;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriter;
import com.starrocks.sql.optimizer.transformer.LogicalPlan;
import com.starrocks.sql.optimizer.transformer.RelationTransformer;
import com.starrocks.sql.optimizer.transformer.SqlToScalarOperatorTranslator;
import com.starrocks.sql.parser.ParsingException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class MvUtils {
    private static final Logger LOG = LogManager.getLogger(MvUtils.class);

    public static Set<MaterializedView> getRelatedMvs(int maxLevel, Set<Table> tablesToCheck) {
        Set<MaterializedView> mvs = Sets.newHashSet();
        getRelatedMvs(maxLevel, 0, tablesToCheck, mvs);
        return mvs;
    }

    public static void getRelatedMvs(int maxLevel, int currentLevel, Set<Table> tablesToCheck, Set<MaterializedView> mvs) {
        if (currentLevel >= maxLevel) {
            return;
        }
        Set<MvId> newMvIds = Sets.newHashSet();
        for (Table table : tablesToCheck) {
            Set<MvId> mvIds = table.getRelatedMaterializedViews();
            if (mvIds != null && !mvIds.isEmpty()) {
                newMvIds.addAll(mvIds);
            }
        }
        if (newMvIds.isEmpty()) {
            return;
        }
        Set<Table> newMvs = Sets.newHashSet();
        for (MvId mvId : newMvIds) {
            Database db = GlobalStateMgr.getCurrentState().getDb(mvId.getDbId());
            if (db == null) {
                continue;
            }
            Table table = db.getTable(mvId.getId());
            if (table == null) {
                continue;
            }
            newMvs.add(table);
            mvs.add((MaterializedView) table);
        }
        getRelatedMvs(maxLevel, currentLevel + 1, newMvs, mvs);
    }

    // get all ref tables within and below root
    public static List<Table> getAllTables(OptExpression root) {
        List<Table> tables = Lists.newArrayList();
        getAllTables(root, tables);
        return tables;
    }

    private static void getAllTables(OptExpression root, List<Table> tables) {
        if (root.getOp() instanceof LogicalScanOperator) {
            LogicalScanOperator scanOperator = (LogicalScanOperator) root.getOp();
            tables.add(scanOperator.getTable());
        } else {
            for (OptExpression child : root.getInputs()) {
                getAllTables(child, tables);
            }
        }
    }

    // get all ref table scan descs within and below root
    // the operator tree must match the rule pattern we define and now we only support SPJG pattern tree rewrite.
    // so here LogicalScanOperator's children must be LogicalScanOperator or LogicalJoinOperator
    public static List<TableScanDesc> getTableScanDescs(OptExpression root) {
        TableScanContext scanContext = new TableScanContext();
        OptExpressionVisitor joinFinder = new OptExpressionVisitor<Void, TableScanContext>() {
            @Override
            public Void visit(OptExpression optExpression, TableScanContext context) {
                for (OptExpression child : optExpression.getInputs()) {
                    child.getOp().accept(this, child, context);
                }
                return null;
            }

            @Override
            public Void visitLogicalTableScan(OptExpression optExpression, TableScanContext context) {
                LogicalScanOperator scanOperator = (LogicalScanOperator) optExpression.getOp();
                Table table = scanOperator.getTable();
                Integer id = scanContext.getTableIdMap().computeIfAbsent(table, t -> 0);
                TableScanDesc tableScanDesc = new TableScanDesc(table, id, scanOperator, null, false);
                context.getTableScanDescs().add(tableScanDesc);
                scanContext.getTableIdMap().put(table, ++id);
                return null;
            }

            @Override
            public Void visitLogicalJoin(OptExpression optExpression, TableScanContext context) {
                for (int i = 0; i < optExpression.getInputs().size(); i++) {
                    OptExpression child = optExpression.inputAt(i);
                    if (child.getOp() instanceof LogicalScanOperator) {
                        LogicalScanOperator scanOperator = (LogicalScanOperator) child.getOp();
                        Table table = scanOperator.getTable();
                        Integer id = scanContext.getTableIdMap().computeIfAbsent(table, t -> 0);
                        LogicalJoinOperator joinOperator = optExpression.getOp().cast();
                        TableScanDesc tableScanDesc = new TableScanDesc(
                                table, id, scanOperator, optExpression, i == 0);
                        context.getTableScanDescs().add(tableScanDesc);
                        scanContext.getTableIdMap().put(table, ++id);
                    } else {
                        child.getOp().accept(this, child, context);
                    }
                }
                return null;
            }
        };

        root.getOp().<Void, TableScanContext>accept(joinFinder, root, scanContext);
        return scanContext.getTableScanDescs();
    }

    public static List<JoinOperator> getAllJoinOperators(OptExpression root) {
        List<JoinOperator> joinOperators = Lists.newArrayList();
        getAllJoinOperators(root, joinOperators);
        return joinOperators;
    }

    private static void getAllJoinOperators(OptExpression root, List<JoinOperator> joinOperators) {
        if (root.getOp() instanceof LogicalJoinOperator) {
            LogicalJoinOperator join = (LogicalJoinOperator) root.getOp();
            joinOperators.add(join.getJoinType());
        }
        for (OptExpression child : root.getInputs()) {
            getAllJoinOperators(child, joinOperators);
        }
    }
    public static List<LogicalScanOperator> getScanOperator(OptExpression root) {
        List<LogicalScanOperator> scanOperators = Lists.newArrayList();
        getScanOperator(root, scanOperators);
        return scanOperators;
    }

    public static void getScanOperator(OptExpression root, List<LogicalScanOperator> scanOperators) {
        if (root.getOp() instanceof LogicalScanOperator) {
            scanOperators.add((LogicalScanOperator) root.getOp());
        } else {
            for (OptExpression child : root.getInputs()) {
                getScanOperator(child, scanOperators);
            }
        }
    }

    public static List<LogicalOlapScanOperator> getOlapScanNode(OptExpression root) {
        List<LogicalOlapScanOperator> olapScanOperators = Lists.newArrayList();
        getOlapScanNode(root, olapScanOperators);
        return olapScanOperators;
    }

    public static void getOlapScanNode(OptExpression root, List<LogicalOlapScanOperator> olapScanOperators) {
        if (root.getOp() instanceof LogicalOlapScanOperator) {
            olapScanOperators.add((LogicalOlapScanOperator) root.getOp());
        } else {
            for (OptExpression child : root.getInputs()) {
                getOlapScanNode(child, olapScanOperators);
            }
        }
    }

    public static boolean isValidMVPlan(OptExpression root) {
        if (root == null) {
            return false;
        }
        if (isLogicalSPJ(root)) {
            return true;
        }
        if (isLogicalSPJG(root)) {
            LogicalAggregationOperator agg = (LogicalAggregationOperator) root.getOp();
            // having is not supported now
            return agg.getPredicate() == null;
        }
        return false;
    }

    public static String getInvalidReason(OptExpression expr) {
        List<Operator> operators = collectOperators(expr);
        if (operators.stream().anyMatch(op -> !isLogicalSPJGOperator(op))) {
            String nonSPJGOperators =
                    operators.stream().filter(x -> !isLogicalSPJGOperator(x))
                            .map(Operator::toString)
                            .collect(Collectors.joining(","));
            return "MV contains non-SPJG operators: " + nonSPJGOperators;
        }
        return "MV is not SPJG structure";
    }

    private static List<Operator> collectOperators(OptExpression expr) {
        List<Operator> operators = Lists.newArrayList();
        collectOperators(expr, operators);
        return operators;
    }

    private static void collectOperators(OptExpression expr, List<Operator> result) {
        result.add(expr.getOp());
        for (OptExpression child : expr.getInputs()) {
            collectOperators(child, result);
        }
    }

    public static boolean isLogicalSPJG(OptExpression root) {
        return isLogicalSPJG(root, 0);
    }

    /**
     * Whether `root` and its children are all Select/Project/Join/Group ops,
     * NOTE: This method requires `root` must be Aggregate op to check whether MV is satisfiable quickly.
     */
    public static boolean isLogicalSPJG(OptExpression root, int level) {
        if (root == null) {
            return false;
        }
        Operator operator = root.getOp();
        if (!(operator instanceof LogicalAggregationOperator)) {
            if (level == 0) {
                return false;
            } else {
                return isLogicalSPJ(root);
            }
        }
        LogicalAggregationOperator agg = (LogicalAggregationOperator) operator;
        if (agg.getType() != AggType.GLOBAL) {
            return false;
        }

        OptExpression child = root.inputAt(0);
        return isLogicalSPJG(child, level + 1);
    }

    /**
     *  Whether `root` and its children are Select/Project/Join ops.
     */
    public static boolean isLogicalSPJ(OptExpression root) {
        if (root == null) {
            return false;
        }
        Operator operator = root.getOp();
        if (!(operator instanceof LogicalOperator)) {
            return false;
        }
        if (!(operator instanceof LogicalScanOperator)
                && !(operator instanceof LogicalProjectOperator)
                && !(operator instanceof LogicalFilterOperator)
                && !(operator instanceof LogicalJoinOperator)) {
            return false;
        }
        for (OptExpression child : root.getInputs()) {
            if (!isLogicalSPJ(child)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isLogicalSPJGOperator(Operator operator) {
        return (operator instanceof LogicalScanOperator)
                || (operator instanceof LogicalProjectOperator)
                || (operator instanceof LogicalFilterOperator)
                || (operator instanceof LogicalJoinOperator)
                || (operator instanceof LogicalAggregationOperator);
    }

    public static Pair<OptExpression, LogicalPlan> getRuleOptimizedLogicalPlan(MaterializedView mv,
                                                                               String sql,
                                                                               ColumnRefFactory columnRefFactory,
                                                                               ConnectContext connectContext,
                                                                               OptimizerConfig optimizerConfig) {
        StatementBase mvStmt;
        try {
            List<StatementBase> statementBases =
                    com.starrocks.sql.parser.SqlParser.parse(sql, connectContext.getSessionVariable());
            Preconditions.checkState(statementBases.size() == 1);
            mvStmt = statementBases.get(0);
        } catch (ParsingException parsingException) {
            LOG.warn("parse mv{}'s sql:{} failed", mv.getName(), sql, parsingException);
            return null;
        }
        Preconditions.checkState(mvStmt instanceof QueryStatement);
        Analyzer.analyze(mvStmt, connectContext);
        QueryRelation query = ((QueryStatement) mvStmt).getQueryRelation();
        LogicalPlan logicalPlan = new RelationTransformer(columnRefFactory, connectContext).transform(query);
        Optimizer optimizer = new Optimizer(optimizerConfig);
        OptExpression optimizedPlan = optimizer.optimize(
                connectContext,
                logicalPlan.getRoot(),
                new PhysicalPropertySet(),
                new ColumnRefSet(logicalPlan.getOutputColumn()),
                columnRefFactory);
        return Pair.create(optimizedPlan, logicalPlan);
    }

    public static List<OptExpression> collectScanExprs(OptExpression expression) {
        List<OptExpression> scanExprs = Lists.newArrayList();
        OptExpressionVisitor scanCollector = new OptExpressionVisitor<Void, Void>() {
            @Override
            public Void visit(OptExpression optExpression, Void context) {
                for (OptExpression input : optExpression.getInputs()) {
                    super.visit(input, context);
                }
                return null;
            }

            @Override
            public Void visitLogicalTableScan(OptExpression optExpression, Void context) {
                scanExprs.add(optExpression);
                return null;
            }
        };
        expression.getOp().accept(scanCollector, expression, null);
        return scanExprs;
    }

    // get all predicates within and below root
    public static List<ScalarOperator> getAllValidPredicates(OptExpression root) {
        List<ScalarOperator> predicates = Lists.newArrayList();
        getAllValidPredicates(root, predicates);
        return predicates;
    }

    // get all predicates within and below root
    public static List<ScalarOperator> getAllPredicates(OptExpression root) {
        List<ScalarOperator> predicates = Lists.newArrayList();
        getAllPredicates(root, x -> true, predicates);
        return predicates;
    }

    // If join is not cross/inner join, MV Rewrite must rewrite, otherwise may cause bad results.
    // eg.
    // mv: select * from customer left outer join orders on c_custkey = o_custkey;
    //
    //query（cannot rewrite）:
    //select count(1) from customer left outer join orders
    //  on c_custkey = o_custkey and o_comment not like '%special%requests%';
    //
    //query（can rewrite）:
    //select count(1)
    //          from customer left outer join orders on c_custkey = o_custkey
    //          where o_comment not like '%special%requests%';
    public static List<ScalarOperator> getJoinOnPredicates(OptExpression root) {
        List<ScalarOperator> predicates = Lists.newArrayList();
        getJoinOnPredicates(root, predicates);
        return predicates;
    }

    private static void getJoinOnPredicates(OptExpression root, List<ScalarOperator> predicates) {
        Operator operator = root.getOp();

        if (operator instanceof LogicalJoinOperator) {
            LogicalJoinOperator joinOperator = (LogicalJoinOperator) operator;
            JoinOperator joinOperatorType = joinOperator.getJoinType();
            // Collect all join on predicates which join type are not inner/cross join.
            if ((joinOperatorType != JoinOperator.INNER_JOIN
                    && joinOperatorType != JoinOperator.CROSS_JOIN) && joinOperator.getOnPredicate() != null) {
                // Now join's on-predicates may be pushed down below join, so use original on-predicates
                // instead of new on-predicates.
                List<ScalarOperator> conjuncts = Utils.extractConjuncts(joinOperator.getOriginalOnPredicate());
                collectValidPredicates(conjuncts, predicates);
            }
        }
        for (OptExpression child : root.getInputs()) {
            getJoinOnPredicates(child, predicates);
        }
    }

    public static ScalarOperator rewriteOptExprCompoundPredicate(OptExpression root,
                                                                 ReplaceColumnRefRewriter columnRefRewriter) {
        List<ScalarOperator> conjuncts = MvUtils.getAllValidPredicates(root);
        ScalarOperator compoundPredicate = null;
        if (!conjuncts.isEmpty()) {
            compoundPredicate = Utils.compoundAnd(conjuncts);
            compoundPredicate = columnRefRewriter.rewrite(compoundPredicate.clone());
        }
        compoundPredicate = MvUtils.canonizePredicateForRewrite(compoundPredicate);
        return compoundPredicate;
    }

    public static ReplaceColumnRefRewriter getReplaceColumnRefWriter(OptExpression root,
                                                                     ColumnRefFactory columnRefFactory) {
        Map<ColumnRefOperator, ScalarOperator> mvLineage = LineageFactory.getLineage(root, columnRefFactory);
        return new ReplaceColumnRefRewriter(mvLineage, true);
    }

    private static void collectPredicates(List<ScalarOperator> conjuncts,
                                          Function<ScalarOperator, Boolean> supplier,
                                          List<ScalarOperator> predicates) {
        conjuncts.stream().filter(p -> supplier.apply(p)).forEach(predicates::add);
    }

    // push-down predicates are excluded when calculating compensate predicates,
    // because they are derived from equivalence class, the original predicates have be considered
    private static void collectValidPredicates(List<ScalarOperator> conjuncts,
                                               List<ScalarOperator> predicates) {
        collectPredicates(conjuncts, MvUtils::isValidPredicate, predicates);
    }

    public static boolean isValidPredicate(ScalarOperator predicate) {
        return !isRedundantPredicate(predicate);
    }

    public static boolean isRedundantPredicate(ScalarOperator predicate) {
        return predicate.isPushdown() || predicate.isRedundant();
    }

    /**
     * Get all predicates by filtering according the input `supplier`.
     */
    private static void getAllPredicates(OptExpression root,
                                         Function<ScalarOperator, Boolean> supplier,
                                         List<ScalarOperator> predicates) {
        Operator operator = root.getOp();

        // Ignore aggregation predicates, because aggregation predicates should be rewritten after
        // aggregation functions' rewrite and should not be pushed down into mv scan operator.
        if (operator.getPredicate() != null && !(operator instanceof LogicalAggregationOperator)) {
            List<ScalarOperator> conjuncts = Utils.extractConjuncts(operator.getPredicate());
            collectPredicates(conjuncts, supplier, predicates);
        }
        if (operator instanceof LogicalJoinOperator) {
            LogicalJoinOperator joinOperator = (LogicalJoinOperator) operator;
            if (joinOperator.getOnPredicate() != null) {
                List<ScalarOperator> conjuncts = Utils.extractConjuncts(joinOperator.getOnPredicate());
                collectPredicates(conjuncts, supplier, predicates);
            }
        }
        for (OptExpression child : root.getInputs()) {
            getAllPredicates(child, supplier, predicates);
        }
    }

    /**
     * Get all valid predicates from input opt expression. `valid` predicate means this predicate is not
     * a pushed-down predicate or redundant predicate among others.
     */
    private static void getAllValidPredicates(OptExpression root, List<ScalarOperator> predicates) {
        getAllPredicates(root, MvUtils::isValidPredicate, predicates);
    }

    /**
     * Canonize the predicate into a normalized form to deduce the redundant predicates.
     */
    public static ScalarOperator canonizePredicate(ScalarOperator predicate) {
        if (predicate == null) {
            return null;
        }
        ScalarOperator cloned = predicate.clone();
        ScalarOperatorRewriter rewrite = new ScalarOperatorRewriter();
        return rewrite.rewrite(cloned, ScalarOperatorRewriter.DEFAULT_REWRITE_SCAN_PREDICATE_RULES);
    }

    /**
     * Canonize the predicate into a more normalized form to be compared better.
     * NOTE:
     * 1. `canonizePredicateForRewrite` will do more optimizations than `canonizePredicate`.
     * 2. if you need to rewrite src predicate to target predicate, should use `canonizePredicateForRewrite`
     *  both rather than one use `canonizePredicate` or `canonizePredicateForRewrite`.
     */
    public static ScalarOperator canonizePredicateForRewrite(ScalarOperator predicate) {
        if (predicate == null) {
            return null;
        }
        ScalarOperator cloned = predicate.clone();
        ScalarOperatorRewriter rewrite = new ScalarOperatorRewriter();
        return rewrite.rewrite(cloned, ScalarOperatorRewriter.MV_SCALAR_REWRITE_RULES);
    }

    public static ScalarOperator getCompensationPredicateForDisjunctive(ScalarOperator src, ScalarOperator target) {
        List<ScalarOperator> srcItems = Utils.extractDisjunctive(src);
        List<ScalarOperator> targetItems = Utils.extractDisjunctive(target);
        if (!new HashSet<>(targetItems).containsAll(srcItems)) {
            return null;
        }
        targetItems.removeAll(srcItems);
        if (targetItems.isEmpty()) {
            // it is the same, so return true constant
            return ConstantOperator.createBoolean(true);
        } else {
            // the target has more or item, so return src
            return src;
        }
    }

    public static boolean isAllEqualInnerOrCrossJoin(OptExpression root) {
        Operator operator = root.getOp();
        if (!(operator instanceof LogicalOperator)) {
            return false;
        }

        if (operator instanceof LogicalJoinOperator) {
            LogicalJoinOperator joinOperator = (LogicalJoinOperator) operator;
            if (!isEqualInnerOrCrossJoin(joinOperator)) {
                return false;
            }
        }
        for (OptExpression child : root.getInputs()) {
            if (!isAllEqualInnerOrCrossJoin(child)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEqualInnerOrCrossJoin(LogicalJoinOperator joinOperator) {
        if (joinOperator.getJoinType() == JoinOperator.CROSS_JOIN && joinOperator.getOnPredicate() == null) {
            return true;
        }

        if (joinOperator.getJoinType() == JoinOperator.INNER_JOIN &&
                isColumnEqualPredicate(joinOperator.getOnPredicate())) {
            return true;
        }
        return false;
    }

    public static boolean isColumnEqualPredicate(ScalarOperator predicate) {
        if (predicate == null) {
            return false;
        }

        ScalarOperatorVisitor<Boolean, Void> checkVisitor = new ScalarOperatorVisitor<Boolean, Void>() {
            @Override
            public Boolean visit(ScalarOperator scalarOperator, Void context) {
                return false;
            }

            @Override
            public Boolean visitCompoundPredicate(CompoundPredicateOperator predicate, Void context) {
                if (!predicate.isAnd()) {
                    return false;
                }
                for (ScalarOperator child : predicate.getChildren()) {
                    Boolean ret = child.accept(this, null);
                    if (!Boolean.TRUE.equals(ret)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public Boolean visitBinaryPredicate(BinaryPredicateOperator predicate, Void context) {
                return predicate.getBinaryType().isEqual()
                        && predicate.getChild(0).isColumnRef()
                        && predicate.getChild(1).isColumnRef();
            }
        };

        return predicate.accept(checkVisitor, null);
    }

    public static Map<ColumnRefOperator, ScalarOperator> getColumnRefMap(
            OptExpression expression, ColumnRefFactory refFactory) {
        Map<ColumnRefOperator, ScalarOperator> columnRefMap = Maps.newHashMap();
        if (expression.getOp().getProjection() != null) {
            columnRefMap.putAll(expression.getOp().getProjection().getColumnRefMap());
        } else {
            if (expression.getOp() instanceof LogicalAggregationOperator) {
                LogicalAggregationOperator agg = (LogicalAggregationOperator) expression.getOp();
                Map<ColumnRefOperator, ScalarOperator> keyMap = agg.getGroupingKeys().stream().collect(Collectors.toMap(
                        java.util.function.Function.identity(),
                        java.util.function.Function.identity()));
                columnRefMap.putAll(keyMap);
                columnRefMap.putAll(agg.getAggregations());
            } else {
                ColumnRefSet refSet = expression.getOutputColumns();
                for (int columnId : refSet.getColumnIds()) {
                    ColumnRefOperator columnRef = refFactory.getColumnRef(columnId);
                    columnRefMap.put(columnRef, columnRef);
                }
            }
        }
        return columnRefMap;
    }

    public static List<ColumnRefOperator> collectScanColumn(OptExpression optExpression) {
        return collectScanColumn(optExpression, Predicates.alwaysTrue());
    }

    public static List<ColumnRefOperator> collectScanColumn(OptExpression optExpression,
                                                            Predicate<LogicalScanOperator> predicate) {

        List<ColumnRefOperator> columnRefOperators = Lists.newArrayList();
        OptExpressionVisitor visitor = new OptExpressionVisitor<Void, Void>() {
            @Override
            public Void visit(OptExpression optExpression, Void context) {
                for (OptExpression input : optExpression.getInputs()) {
                    input.getOp().accept(this, input, null);
                }
                return null;
            }

            @Override
            public Void visitLogicalTableScan(OptExpression optExpression, Void context) {
                LogicalScanOperator scan = (LogicalScanOperator) optExpression.getOp();
                if (predicate.test(scan)) {
                    columnRefOperators.addAll(scan.getColRefToColumnMetaMap().keySet());
                }
                return null;
            }
        };
        optExpression.getOp().accept(visitor, optExpression, null);
        return columnRefOperators;
    }

    public static List<ScalarOperator> convertRanges(
            ScalarOperator partitionScalar,
            List<Range<PartitionKey>> partitionRanges) {
        List<ScalarOperator> rangeParts = Lists.newArrayList();
        for (Range<PartitionKey> range : partitionRanges) {
            if (range.isEmpty()) {
                continue;
            }
            // partition range must have lower bound and upper bound
            Preconditions.checkState(range.hasLowerBound() && range.hasUpperBound());
            LiteralExpr lowerExpr = range.lowerEndpoint().getKeys().get(0);
            if (lowerExpr.isMinValue() && range.upperEndpoint().isMaxValue()) {
                continue;
            } else if (lowerExpr.isMinValue()) {
                ConstantOperator upperBound =
                        (ConstantOperator) SqlToScalarOperatorTranslator.translate(range.upperEndpoint().getKeys().get(0));
                BinaryPredicateOperator upperPredicate = new BinaryPredicateOperator(
                        BinaryPredicateOperator.BinaryType.LT, partitionScalar, upperBound);
                rangeParts.add(upperPredicate);
            } else if (range.upperEndpoint().isMaxValue()) {
                ConstantOperator lowerBound =
                        (ConstantOperator) SqlToScalarOperatorTranslator.translate(range.lowerEndpoint().getKeys().get(0));
                BinaryPredicateOperator lowerPredicate = new BinaryPredicateOperator(
                        BinaryPredicateOperator.BinaryType.GE, partitionScalar, lowerBound);
                rangeParts.add(lowerPredicate);
            } else {
                // close, open range
                ConstantOperator lowerBound =
                        (ConstantOperator) SqlToScalarOperatorTranslator.translate(range.lowerEndpoint().getKeys().get(0));
                BinaryPredicateOperator lowerPredicate = new BinaryPredicateOperator(
                        BinaryPredicateOperator.BinaryType.GE, partitionScalar, lowerBound);

                ConstantOperator upperBound =
                        (ConstantOperator) SqlToScalarOperatorTranslator.translate(range.upperEndpoint().getKeys().get(0));
                BinaryPredicateOperator upperPredicate = new BinaryPredicateOperator(
                        BinaryPredicateOperator.BinaryType.LT, partitionScalar, upperBound);

                CompoundPredicateOperator andPredicate = new CompoundPredicateOperator(
                        CompoundPredicateOperator.CompoundType.AND, lowerPredicate, upperPredicate);
                rangeParts.add(andPredicate);
            }
        }
        return rangeParts;
    }

    public static List<Expr> convertRange(Expr slotRef, List<Range<PartitionKey>> partitionRanges) {
        List<Expr> rangeParts = Lists.newArrayList();
        for (Range<PartitionKey> range : partitionRanges) {
            if (range.isEmpty()) {
                continue;
            }
            // partition range must have lower bound and upper bound
            Preconditions.checkState(range.hasLowerBound() && range.hasUpperBound());
            LiteralExpr lowerExpr = range.lowerEndpoint().getKeys().get(0);
            if (lowerExpr.isMinValue() && range.upperEndpoint().isMaxValue()) {
                continue;
            } else if (lowerExpr.isMinValue()) {
                Expr upperBound = range.upperEndpoint().getKeys().get(0);
                BinaryPredicate upperPredicate = new BinaryPredicate(BinaryPredicate.Operator.LT, slotRef, upperBound);
                rangeParts.add(upperPredicate);
            } else if (range.upperEndpoint().isMaxValue()) {
                Expr lowerBound = range.lowerEndpoint().getKeys().get(0);
                BinaryPredicate lowerPredicate = new BinaryPredicate(BinaryPredicate.Operator.GE, slotRef, lowerBound);
                rangeParts.add(lowerPredicate);
            } else {
                // close, open range
                Expr lowerBound = range.lowerEndpoint().getKeys().get(0);
                BinaryPredicate lowerPredicate = new BinaryPredicate(BinaryPredicate.Operator.GE, slotRef, lowerBound);

                Expr upperBound = range.upperEndpoint().getKeys().get(0);
                BinaryPredicate upperPredicate = new BinaryPredicate(BinaryPredicate.Operator.LT, slotRef, upperBound);

                CompoundPredicate andPredicate = new CompoundPredicate(CompoundPredicate.Operator.AND, lowerPredicate,
                        upperPredicate);
                rangeParts.add(andPredicate);
            }
        }
        return rangeParts;
    }

    public static List<Range<PartitionKey>> mergeRanges(List<Range<PartitionKey>> ranges) {
        ranges.sort(RangeUtils.RANGE_COMPARATOR);
        List<Range<PartitionKey>> mergedRanges = Lists.newArrayList();
        for (Range<PartitionKey> currentRange : ranges) {
            boolean merged = false;
            for (int j = 0; j < mergedRanges.size(); j++) {
                // 1 < r < 10, 10 <= r < 20 => 1 < r < 20
                Range<PartitionKey> resultRange = mergedRanges.get(j);
                if (currentRange.isConnected(currentRange) && currentRange.gap(resultRange).isEmpty()) {
                    mergedRanges.set(j, resultRange.span(currentRange));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                mergedRanges.add(currentRange);
            }
        }
        return mergedRanges;
    }

    private static boolean supportCompensatePartitionPredicateForHiveScan(List<PartitionKey> partitionKeys) {
        for (PartitionKey partitionKey : partitionKeys) {
            // only support one partition column now.
            if (partitionKey.getKeys().size() != 1) {
                return false;
            }
            LiteralExpr e = partitionKey.getKeys().get(0);
            // Only support date/int type
            if (!(e instanceof DateLiteral || e instanceof IntLiteral)) {
                return false;
            }
        }
        return true;
    }

    public static List<ScalarOperator> compensatePartitionPredicateForHiveScan(LogicalHiveScanOperator scanOperator) {
        List<ScalarOperator> partitionPredicates = Lists.newArrayList();
        Preconditions.checkState(scanOperator.getTable().isHiveTable());
        HiveTable hiveTable = (HiveTable) scanOperator.getTable();

        if (hiveTable.isUnPartitioned()) {
            return partitionPredicates;
        }

        ScanOperatorPredicates scanOperatorPredicates = scanOperator.getScanOperatorPredicates();
        if (scanOperatorPredicates.getSelectedPartitionIds().size() ==
                scanOperatorPredicates.getIdToPartitionKey().size()) {
            return partitionPredicates;
        }

        if (!supportCompensatePartitionPredicateForHiveScan(scanOperatorPredicates.getSelectedPartitionKeys())) {
            return partitionPredicates;
        }

        List<Range<PartitionKey>> ranges = Lists.newArrayList();
        for (PartitionKey selectedPartitionKey : scanOperatorPredicates.getSelectedPartitionKeys()) {
            try {
                LiteralExpr expr = PartitionUtil.addOffsetForLiteral(selectedPartitionKey.getKeys().get(0), 1);
                PartitionKey partitionKey = new PartitionKey(ImmutableList.of(expr), selectedPartitionKey.getTypes());
                ranges.add(Range.closedOpen(selectedPartitionKey, partitionKey));
            } catch (AnalysisException e) {
                LOG.warn("Compute partition key range failed. ", e);
                return partitionPredicates;
            }
        }

        List<Range<PartitionKey>> mergedRanges = mergeRanges(ranges);
        ColumnRefOperator partitionColumnRef = scanOperator.getColumnReference(hiveTable.getPartitionColumns().get(0));
        List<ScalarOperator> rangePredicates = MvUtils.convertRanges(partitionColumnRef, mergedRanges);
        ScalarOperator partitionPredicate = Utils.compoundOr(rangePredicates);
        if (partitionPredicate != null) {
            partitionPredicates.add(partitionPredicate);
        }

        return partitionPredicates;
    }

    public static ScalarOperator compensatePartitionPredicate(OptExpression plan, ColumnRefFactory columnRefFactory) {
        List<LogicalScanOperator> scanOperators = MvUtils.getScanOperator(plan);
        if (scanOperators.isEmpty()) {
            return ConstantOperator.createBoolean(true);
        }

        List<ScalarOperator> partitionPredicates = Lists.newArrayList();
        for (LogicalScanOperator scanOperator : scanOperators) {
            List<ScalarOperator> partitionPredicate = null;
            if (scanOperator instanceof LogicalOlapScanOperator) {
                partitionPredicate = ((LogicalOlapScanOperator) scanOperator).getPrunedPartitionPredicates();
            } else if (scanOperator instanceof LogicalHiveScanOperator) {
                partitionPredicate = compensatePartitionPredicateForHiveScan((LogicalHiveScanOperator) scanOperator);
            } else {
                continue;
            }
            if (partitionPredicate == null) {
                return null;
            }
            partitionPredicates.addAll(partitionPredicate);
        }
        return partitionPredicates.isEmpty() ? ConstantOperator.createBoolean(true) :
                Utils.compoundAnd(partitionPredicates);
    }

    // try to get partial partition predicates of partitioned mv.
    // eg, mv1's base partition table is t1, partition column is k1 and has two partition:
    // p1:[2022-01-01, 2022-01-02), p1 is updated(refreshed),
    // p2:[2022-01-02, 2022-01-03), p2 is outdated,
    // then this function will return predicate:
    // k1 >= "2022-01-01" and k1 < "2022-01-02"
    public static ScalarOperator getMvPartialPartitionPredicates(MaterializedView mv,
                                                                 OptExpression mvPlan,
                                                                 Set<String> mvPartitionNamesToRefresh) {
        Pair<Table, Column> partitionTableAndColumns = mv.getPartitionTableAndColumn();
        if (partitionTableAndColumns == null) {
            return null;
        }

        Table partitionByTable = partitionTableAndColumns.first;
        List<Range<PartitionKey>> latestBaseTableRanges =
                getLatestPartitionRangeForTable(partitionByTable, partitionTableAndColumns.second,
                        mv, mvPartitionNamesToRefresh);
        if (latestBaseTableRanges.isEmpty()) {
            // if there isn't an updated partition, do not rewrite
            return null;
        }

        Column partitionColumn = partitionTableAndColumns.second;
        List<OptExpression> scanExprs = MvUtils.collectScanExprs(mvPlan);
        for (OptExpression scanExpr : scanExprs) {
            LogicalScanOperator scanOperator = (LogicalScanOperator) scanExpr.getOp();
            Table scanTable = scanOperator.getTable();
            if ((scanTable.isNativeTable() && !scanTable.equals(partitionTableAndColumns.first))
                    || (!scanTable.isNativeTable()) && !scanTable.getTableIdentifier().equals(
                    partitionTableAndColumns.first.getTableIdentifier())) {
                continue;
            }
            ColumnRefOperator columnRef = scanOperator.getColumnReference(partitionColumn);
            List<ScalarOperator> partitionPredicates = MvUtils.convertRanges(columnRef, latestBaseTableRanges);
            ScalarOperator partialPartitionPredicate = Utils.compoundOr(partitionPredicates);
            return partialPartitionPredicate;
        }
        return null;
    }

    private static List<Range<PartitionKey>> getLatestPartitionRangeForTable(Table partitionByTable,
                                                                      Column partitionColumn,
                                                                      MaterializedView mv,
                                                                      Set<String> mvPartitionNamesToRefresh) {
        Set<String> modifiedPartitionNames = mv.getUpdatedPartitionNamesOfTable(partitionByTable);
        List<Range<PartitionKey>> baseTableRanges = getLatestPartitionRange(partitionByTable, partitionColumn,
                modifiedPartitionNames);
        List<Range<PartitionKey>> mvRanges = getLatestPartitionRangeForNativeTable(mv, mvPartitionNamesToRefresh);
        List<Range<PartitionKey>> latestBaseTableRanges = Lists.newArrayList();
        for (Range<PartitionKey> range : baseTableRanges) {
            if (mvRanges.stream().anyMatch(mvRange -> mvRange.encloses(range))) {
                latestBaseTableRanges.add(range);
            }
        }
        latestBaseTableRanges = MvUtils.mergeRanges(latestBaseTableRanges);
        return latestBaseTableRanges;
    }

    private static List<Range<PartitionKey>> getLatestPartitionRangeForNativeTable(OlapTable partitionTable,
                                                                            Set<String> modifiedPartitionNames) {
        // partitions that will be excluded
        Set<Long> filteredIds = Sets.newHashSet();
        for (Partition p : partitionTable.getPartitions()) {
            if (modifiedPartitionNames.contains(p.getName()) || !p.hasData()) {
                filteredIds.add(p.getId());
            }
        }
        RangePartitionInfo rangePartitionInfo = (RangePartitionInfo) partitionTable.getPartitionInfo();
        return rangePartitionInfo.getRangeList(filteredIds, false);
    }

    private static List<Range<PartitionKey>> getLatestPartitionRange(Table table, Column partitionColumn,
                                                              Set<String> modifiedPartitionNames) {
        if (table.isNativeTable()) {
            return getLatestPartitionRangeForNativeTable((OlapTable) table, modifiedPartitionNames);
        } else {
            Map<String, Range<PartitionKey>> partitionMap;
            try {
                partitionMap = PartitionUtil.getPartitionRange(table, partitionColumn);
            } catch (UserException e) {
                LOG.warn("Materialized view Optimizer compute partition range failed.", e);
                return Lists.newArrayList();
            }
            return partitionMap.entrySet().stream().filter(entry -> !modifiedPartitionNames.contains(entry.getKey())).
                    map(Map.Entry::getValue).collect(Collectors.toList());
        }
    }

    public static String toString(Object o) {
        if (o == null) {
            return "";
        }
        return o.toString();
    }

    public static List<ScalarOperator> collectOnPredicate(OptExpression optExpression) {
        List<ScalarOperator> onPredicates = Lists.newArrayList();
        collectOnPredicate(optExpression, onPredicates, false);
        return onPredicates;
    }

    public static List<ScalarOperator> collectOuterAntiJoinOnPredicate(OptExpression optExpression) {
        List<ScalarOperator> onPredicates = Lists.newArrayList();
        collectOnPredicate(optExpression, onPredicates, true);
        return onPredicates;
    }

    public static void collectOnPredicate(
            OptExpression optExpression, List<ScalarOperator> onPredicates, boolean onlyOuterAntiJoin) {
        for (OptExpression child : optExpression.getInputs()) {
            collectOnPredicate(child, onPredicates, onlyOuterAntiJoin);
        }
        if (optExpression.getOp() instanceof LogicalJoinOperator) {
            LogicalJoinOperator joinOperator = optExpression.getOp().cast();
            if (onlyOuterAntiJoin &&
                    !(joinOperator.getJoinType().isOuterJoin() || joinOperator.getJoinType().isAntiJoin())) {
                return;
            }

            onPredicates.addAll(Utils.extractConjuncts(joinOperator.getOnPredicate()));
        }
    }
<<<<<<< HEAD
=======

    /**
     * Return the max refresh timestamp of all partition infos.
     */
    public static long  getMaxTablePartitionInfoRefreshTime(
            Collection<Map<String, MaterializedView.BasePartitionInfo>> partitionInfos) {
        return partitionInfos.stream()
                .flatMap(x -> x.values().stream())
                .map(x -> x.getLastRefreshTime())
                .max(Long::compareTo)
                .filter(Objects::nonNull)
                .orElse(System.currentTimeMillis());
    }

    public static boolean isSupportViewDelta(OptExpression optExpression) {
        return getAllJoinOperators(optExpression).stream().allMatch(x -> isSupportViewDelta(x));
    }

    public static boolean isSupportViewDelta(JoinOperator joinOperator) {
        return  joinOperator.isLeftOuterJoin() || joinOperator.isInnerJoin();
    }

    /**
     * Inactive related mvs after modified columns have been done. Only inactive mvs after
     * modified columns have done because the modified process may be failed and in this situation
     * should not inactive mvs then.
     */
    public static void inactiveRelatedMaterializedViews(Database db,
                                                        OlapTable olapTable,
                                                        Set<String> modifiedColumns) {
        if (modifiedColumns == null || modifiedColumns.isEmpty()) {
            return;
        }
        // inactive related asynchronous mvs
        for (MvId mvId : olapTable.getRelatedMaterializedViews()) {
            MaterializedView mv = (MaterializedView) db.getTable(mvId.getId());
            if (mv == null) {
                LOG.warn("Ignore materialized view {} does not exists", mvId);
                continue;

            }
            // TODO: support more types for base table's schema change.
            try {
                MvPlanContext mvPlanContext = MvPlanContextBuilder.getPlanContext(mv);
                if (mvPlanContext != null) {
                    OptExpression mvPlan = mvPlanContext.getLogicalPlan();
                    List<ColumnRefOperator> usedColRefs = MvUtils.collectScanColumn(mvPlan, scan -> {
                        if (scan == null) {
                            return false;
                        }
                        Table table = scan.getTable();
                        return table.getId() == olapTable.getId();
                    });
                    Set<String> usedColNames = usedColRefs.stream()
                            .map(x -> x.getName())
                            .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
                    for (String modifiedColumn : modifiedColumns) {
                        if (usedColNames.contains(modifiedColumn)) {
                            LOG.warn("Setting the materialized view {}({}) to invalid because " +
                                            "the column {} of the table {} was modified.", mv.getName(), mv.getId(),
                                    modifiedColumn, olapTable.getName());
                            mv.setInactiveAndReason(
                                    "base table schema changed for columns: " + Joiner.on(",").join(modifiedColumns));
                        }
                    }
                }
            } catch (SemanticException e) {
                LOG.warn("Get related materialized view {} failed:", mv.getName(), e);
                LOG.warn("Setting the materialized view {}({}) to invalid because " +
                                "the columns  of the table {} was modified.", mv.getName(), mv.getId(),
                        olapTable.getName());
                mv.setInactiveAndReason(
                        "base table schema changed for columns: " + Joiner.on(",").join(modifiedColumns));
            } catch (Exception e) {
                LOG.warn("Get related materialized view {} failed:", mv.getName(), e);
                // basic check: may lose some situations
                for (Column mvColumn : mv.getColumns()) {
                    if (modifiedColumns.contains(mvColumn.getName())) {
                        LOG.warn("Setting the materialized view {}({}) to invalid because " +
                                        "the column {} of the table {} was modified.", mv.getName(), mv.getId(),
                                mvColumn.getName(), olapTable.getName());
                        mv.setInactiveAndReason(
                                "base table schema changed for columns: " + Joiner.on(",").join(modifiedColumns));
                        break;
                    }
                }
            }
        }
    }
>>>>>>> 8fd6a085bf ([BugFix] Add more checks when schema changing has referred materialized views (backport #37388) (#38436))
}
