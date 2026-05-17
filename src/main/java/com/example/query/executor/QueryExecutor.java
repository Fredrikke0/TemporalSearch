package com.example.query.executor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.index.IndexManager;
import com.example.query.model.JoinCondition;
import com.example.query.model.Query;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Logical.LogicalOperator;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Not;

/**
 * Executes queries against the provided indexes.
 * Responsible for coordinating the execution of all conditions in a query
 * and combining their results according to the query's logical structure.
 *
 * Supports the execution of subqueries and joins between result sets.
 */
public class QueryExecutor {
    private static final Logger logger = LoggerFactory.getLogger(QueryExecutor.class);
    private static final int NER_PUSHDOWN_SYNONYM_THRESHOLD = 100_000;

    private ConditionExecutorFactory executorFactory;
    private PushdownStrategy pushdownStrategy = PushdownStrategy.NONE;
    private final String stitchStrategy;
    private final SynonymManager synonymManager;
    private final ConditionExecutorFactory injectedExecutorFactory;

    /**
     * Full constructor for QueryExecutor, allowing injection of
     * ConditionExecutorFactory for testing.
     *
     * @param stitchStrategy          The stitch execution strategy.
     * @param synonymManager          The SynonymManager instance.
     * @param injectedExecutorFactory An optional ConditionExecutorFactory to inject
     *                                for testing.
     */
    public QueryExecutor(String stitchStrategy, SynonymManager synonymManager,
            ConditionExecutorFactory injectedExecutorFactory) {
        this.synonymManager = synonymManager;
        this.stitchStrategy = (stitchStrategy == null || stitchStrategy.isBlank()) ? "none" : stitchStrategy;
        this.injectedExecutorFactory = injectedExecutorFactory;
        logger.debug(
                "Initialized QueryExecutor with stitch strategy: {}, provided SynonymManager, and {}injected factory. Default pushdown strategy: {}.",
                this.stitchStrategy,
                this.injectedExecutorFactory == null ? "no " : "",
                this.pushdownStrategy);
    }

    /**
     * Executes a query using the provided IndexManager.
     *
     * @param query        The query to execute
     * @param indexManager The IndexManager providing access to indexes and
     *                     SynonymManager
     * @return CellResult representing the result of the query
     * @throws QueryExecutionException if execution fails
     */
    public CellResult execute(Query query, IndexManager indexManager)
            throws QueryExecutionException {

        long startTime = System.nanoTime();
        Map<String, IndexAccessInterface> indexes = indexManager.getAllIndexes();

        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);
        logger.info("=== Query Execution Started ===");
        logger.info("Attribute Requirements: {}", requirements.getRequiredSoAAttributes());
        logger.trace("Full requirements: {}", requirements);

        try {
            CellResult result = executeWithRequirements(query, indexes, requirements, new SubqueryContext());

            long executionTime = System.nanoTime() - startTime;
            logger.info("=== Query Execution Completed Successfully ===");
            logger.info("Total execution time: {} ms", executionTime / 1_000_000.0);
            logger.info("Result: CellResult, cells: {}, granularity: {}",
                    result.cellCount(), result.granularity());
            return result;

        } catch (QueryExecutionException e) {
            long executionTime = System.nanoTime() - startTime;
            logger.error("=== Query Execution Failed ===");
            logger.error("Execution time before failure: {} ms", executionTime / 1_000_000.0);
            logger.error("Error: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Executes a query with an existing subquery context.
     * This allows for recursive execution of subqueries.
     *
     * @param query           The query to execute
     * @param indexes         Map of index name to IndexAccessInterface
     * @param subqueryContext Context containing results of previously executed
     *                        subqueries
     * @return CellResult representing the result of the query
     * @throws QueryExecutionException if execution fails
     */
    public CellResult executeWithContext(Query query, Map<String, IndexAccessInterface> indexes,
            SubqueryContext subqueryContext)
            throws QueryExecutionException {
        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);
        logger.debug("Query requires attributes: {}", requirements.getRequiredSoAAttributes());
        return executeWithContext(query, indexes, subqueryContext, requirements);
    }

    /**
     * Executes a query with an existing subquery context and attribute
     * requirements.
     * This is the core execution method.
     *
     * @param query           The query to execute
     * @param indexes         Map of index name to IndexAccessInterface
     * @param subqueryContext Context containing results of previously executed
     *                        subqueries
     * @param requirements    Attribute requirements for optimization
     * @return CellResult representing the result of the query
     * @throws QueryExecutionException if execution fails
     */
    private CellResult executeWithContext(Query query, Map<String, IndexAccessInterface> indexes,
            SubqueryContext subqueryContext, AttributeRequirements requirements)
            throws QueryExecutionException {
        Query.Granularity granularity = query.granularity();
        int granularitySize = query.granularitySize().orElse(0);
        if (granularitySize < 0 || granularitySize > 10) {
            throw new IllegalArgumentException("Granularity size must be between 0 and 10, got: " + granularitySize);
        }
        String source = query.source();
        logger.debug("Using source: {}", source);

        CellResult mainConditionsResult = null;
        List<Condition> mainConditions = query.conditions();

        Optional<Roaring64NavigableMap> initialAllowedCells = Optional.empty();

        if (!mainConditions.isEmpty()) {
            logger.debug("Executing main query conditions...");
            List<Condition> orderedMainConditions = optimizeExecutionOrder(mainConditions);
            if (orderedMainConditions.size() == 1) {
                mainConditionsResult = executeCondition(orderedMainConditions.get(0), indexes, granularity,
                        granularitySize, source, requirements, initialAllowedCells);
            } else {
                Logical implicitAnd = new Logical(LogicalOperator.AND, orderedMainConditions);
                mainConditionsResult = executeCondition(implicitAnd, indexes, granularity, granularitySize, source,
                        requirements, initialAllowedCells);
            }
            logger.debug("Main query conditions executed, {} cells found.", mainConditionsResult.cellCount());
        } else {
            logger.debug("No main query conditions found.");
            mainConditionsResult = CellResult.empty(granularity);
        }

        // --- JOIN LOGIC ---
        if (!query.joinSteps().isEmpty()) {
            logger.info("Starting chained join execution with {} steps.", query.joinSteps().size());
            CellResult currentLhsResult = mainConditionsResult;
            String currentLhsAlias = query.mainAlias()
                    .orElse(com.example.query.parser.QueryModelBuilder.DEFAULT_MAIN_ALIAS);
            subqueryContext.addQueryResult(currentLhsAlias, currentLhsResult);

            logger.debug("Initial LHS for join chain: alias='{}', cells={}", currentLhsAlias,
                    currentLhsResult.cellCount());

            for (com.example.query.model.JoinStep step : query.joinSteps()) {
                logger.debug("Processing JoinStep: Left='{}' ({}), Right='{}', Type='{}', ON='{}'",
                        step.leftSourceAlias(), currentLhsAlias, step.rightSourceAlias(), step.joinType(),
                        step.onCondition());

                if (!step.leftSourceAlias().equals(currentLhsAlias)) {
                    throw new QueryExecutionException(
                            String.format(
                                    "Join chain integrity error: Expected LHS alias '%s' for current step, but JoinStep specifies '%s'.",
                                    currentLhsAlias, step.leftSourceAlias()),
                            query.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
                }

                Query rhsQuery = step.subquery();
                String rhsAlias = step.rightSourceAlias();
                AttributeRequirements rhsRequirements = QueryAttributeAnalyzer.analyze(rhsQuery, requirements);
                CellResult rhsResult;

                boolean eligibleForTemporalPushdown = this.pushdownStrategy == PushdownStrategy.OPTIMIZED &&
                        step.joinType() == JoinCondition.JoinType.INNER &&
                        step.onCondition().operatorType() == JoinCondition.JoinOperatorType.TEMPORAL;

                boolean eligibleForNerPushdown = this.pushdownStrategy == PushdownStrategy.OPTIMIZED &&
                        step.joinType() == JoinCondition.JoinType.INNER &&
                        step.onCondition().operatorType() == JoinCondition.JoinOperatorType.EQUALITY &&
                        isNerEqualityJoin(step.onCondition(), currentLhsResult, rhsQuery);

                if (eligibleForTemporalPushdown) {
                    logger.info(
                            "Attempting OPTIMIZED pushdown for JoinStep: LHS='{}', RHS Query Source='{}' (alias '{}'), ON {}",
                            currentLhsAlias, rhsQuery.source(), rhsAlias, step.onCondition());
                    try {
                        rhsResult = executeSingleDependentStep(currentLhsResult, currentLhsAlias, rhsQuery, rhsAlias,
                                step.onCondition(), indexes, subqueryContext, requirements, query.granularity(),
                                query.granularitySize().orElse(0));
                    } catch (QueryExecutionException qe) {
                        logger.warn(
                                "Dependent execution for step (LHS: '{}', RHS: '{}') failed with QueryExecutionException: {}. Falling back to independent execution.",
                                currentLhsAlias, rhsAlias, qe.getMessage());
                        rhsResult = executeWithRequirements(rhsQuery, indexes, rhsRequirements, new SubqueryContext());
                    } catch (Exception e) {
                        logger.warn(
                                "Dependent execution for step (LHS: '{}', RHS: '{}') failed with generic Exception: {}. Falling back to independent execution.",
                                currentLhsAlias, rhsAlias, e.getMessage(), e);
                        rhsResult = executeWithRequirements(rhsQuery, indexes, rhsRequirements, new SubqueryContext());
                    }
                } else if (eligibleForNerPushdown) {
                    logger.info(
                            "Attempting OPTIMIZED NER pushdown for JoinStep: LHS='{}', RHS Query Source='{}' (alias '{}'), ON {}",
                            currentLhsAlias, rhsQuery.source(), rhsAlias, step.onCondition());
                    try {
                        rhsResult = executeSingleDependentStepForNer(currentLhsResult, currentLhsAlias, rhsQuery,
                                rhsAlias, step.onCondition(), indexes, subqueryContext, requirements,
                                query.granularity(), query.granularitySize().orElse(0));
                    } catch (QueryExecutionException qe) {
                        logger.warn(
                                "NER dependent execution for step (LHS: '{}', RHS: '{}') failed with QueryExecutionException: {}. Falling back to independent execution.",
                                currentLhsAlias, rhsAlias, qe.getMessage());
                        rhsResult = executeWithRequirements(rhsQuery, indexes, rhsRequirements, new SubqueryContext());
                    } catch (Exception e) {
                        logger.warn(
                                "NER dependent execution for step (LHS: '{}', RHS: '{}') failed with generic Exception: {}. Falling back to independent execution.",
                                currentLhsAlias, rhsAlias, e.getMessage(), e);
                        rhsResult = executeWithRequirements(rhsQuery, indexes, rhsRequirements, new SubqueryContext());
                    }
                } else {
                    logger.info("Executing RHS subquery '{}' (source: '{}') independently for JoinStep.", rhsAlias,
                            rhsQuery.source());
                    rhsResult = executeWithRequirements(rhsQuery, indexes, rhsRequirements, new SubqueryContext());
                }

                subqueryContext.addQueryResult(rhsAlias, rhsResult);
                logger.debug("Executed RHS '{}' for JoinStep, cells={}", rhsAlias, rhsResult.cellCount());

                JoinHandler joinHandler = new JoinHandler();
                logger.debug(
                        "Calling JoinHandler.performBinaryJoin: LHS='{}'({}), RHS='{}'({}), Type={}, Condition={}",
                        currentLhsAlias, currentLhsResult.cellCount(), rhsAlias, rhsResult.cellCount(),
                        step.joinType(), step.onCondition());

                currentLhsResult = joinHandler.performBinaryJoin(
                        currentLhsResult,
                        currentLhsAlias,
                        rhsResult,
                        rhsAlias,
                        step.onCondition(),
                        step.joinType(),
                        query.granularity(),
                        query.granularitySize().orElse(0),
                        requirements);

                logger.debug("JoinHandler.performBinaryJoin completed. Result cells: {}",
                        currentLhsResult.cellCount());

                currentLhsAlias = rhsAlias;
                subqueryContext.addQueryResult(currentLhsAlias, currentLhsResult);
                logger.debug("JoinStep completed. New currentLhsAlias='{}', cells={}", currentLhsAlias,
                        currentLhsResult.cellCount());
            }
            logger.info("Chained join execution completed. Final result alias: '{}', cells: {}", currentLhsAlias,
                    currentLhsResult.cellCount());
            return currentLhsResult;
        } else {
            logger.debug("No join steps in query. Returning main conditions result.");
            return mainConditionsResult;
        }
    }

    /**
     * Optimizes the execution order of conditions based on variable dependencies.
     */
    private List<Condition> optimizeExecutionOrder(List<Condition> conditions) {
        if (conditions.size() <= 1) {
            return conditions;
        }

        List<Condition> remaining = new ArrayList<>(conditions);
        List<Condition> ordered = new ArrayList<>();
        Set<String> producedVariables = new HashSet<>();

        while (!remaining.isEmpty()) {
            boolean progress = false;

            for (int i = 0; i < remaining.size(); i++) {
                Condition condition = remaining.get(i);

                boolean canExecute = true;
                for (String var : condition.getConsumedVariables()) {
                    if (!producedVariables.contains(var)) {
                        canExecute = false;
                        break;
                    }
                }

                if (canExecute) {
                    ordered.add(condition);
                    remaining.remove(i);
                    producedVariables.addAll(condition.getProducedVariables());
                    progress = true;
                    break;
                }
            }

            if (!progress && !remaining.isEmpty()) {
                logger.warn(
                        "Potential dependency cycle or missing variable producer. Adding condition {} to maintain progress.",
                        remaining.get(0));
                Condition next = remaining.remove(0);
                ordered.add(next);
                producedVariables.addAll(next.getProducedVariables());
            }
        }

        logger.debug("Optimized execution order: {}",
                ordered.stream().map(Condition::getType).collect(Collectors.toList()));
        return ordered;
    }

    /**
     * Executes a single condition against the indexes.
     *
     * @param condition       The condition to execute
     * @param indexes         Map of index name to IndexAccessInterface
     * @param granularity     The query granularity
     * @param granularitySize The window size for sentence granularity
     * @param source          The corpus name
     * @param requirements    Attribute requirements for optimization
     * @param allowedCells    Optional set of allowed cell keys for filtering
     * @return CellResult containing matching cells
     * @throws QueryExecutionException if execution fails
     */
    private CellResult executeCondition(
            Condition condition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String source,
            AttributeRequirements requirements,
            Optional<Roaring64NavigableMap> allowedCells)
            throws QueryExecutionException {
        logger.debug("Executing condition: type={}, varName={}, allowedCellsIsPresent={}, granularity={}",
                condition.getType(),
                condition.getProducedVariables().stream().findFirst().orElse("N/A"),
                allowedCells.isPresent(),
                granularity);

        ConditionExecutor<Condition> executor = executorFactory.getExecutor(condition);
        return executor.execute(condition, indexes, granularity, granularitySize, source, requirements, allowedCells);
    }

    /**
     * Sets the pushdown strategy for this executor.
     */
    public void setPushdownStrategy(PushdownStrategy strategy) {
        if (strategy != null) {
            this.pushdownStrategy = strategy;
            logger.debug("Pushdown strategy set to: {}", this.pushdownStrategy);
        } else {
            logger.warn("Attempted to set null pushdown strategy. Retaining current: {}", this.pushdownStrategy);
        }
    }

    private CellResult executeWithRequirements(Query query, Map<String, IndexAccessInterface> indexes,
            AttributeRequirements requirements, SubqueryContext subqueryContext)
            throws QueryExecutionException {
        if (this.injectedExecutorFactory != null) {
            this.executorFactory = this.injectedExecutorFactory;
            logger.debug("Using injected ConditionExecutorFactory for query: {}", query.source());
        } else {
            this.executorFactory = new ConditionExecutorFactory(this.synonymManager, this.stitchStrategy,
                    query.granularity());
            logger.debug(
                    "Created new ConditionExecutorFactory for query: {} with stitchStrategy: {} and granularity: {}",
                    query.source(), this.stitchStrategy, query.granularity());
        }

        return executeWithContext(query, indexes, subqueryContext, requirements);
    }

    /**
     * Executes a single step of a dependent join, specifically for temporal
     * pushdown.
     */
    private CellResult executeSingleDependentStep(
            CellResult leadingResult,
            String leadingAlias,
            Query dependentQuery,
            String dependentAlias,
            JoinCondition condition,
            Map<String, IndexAccessInterface> indexes,
            SubqueryContext overallSubqueryContext,
            AttributeRequirements parentRequirements,
            Query.Granularity granularity,
            int granularitySize)
            throws QueryExecutionException {
        logger.debug(
                "Executing single dependent step: LeadingResult (alias: '{}', cells: {}) -> DependentQuery (alias: '{}', source: '{}') ON {}",
                leadingAlias, leadingResult.cellCount(), dependentAlias, dependentQuery.source(), condition);

        String leftColumn = condition.leftColumn();
        String rightColumn = condition.rightColumn();

        String leadingDateKeyName;
        String dependentDateKeyName;

        String leftColAliasPart = JoinHandler.extractAliasFromColumnName(leftColumn);
        String rightColAliasPart = JoinHandler.extractAliasFromColumnName(rightColumn);

        if (leftColAliasPart.equals(leadingAlias)) {
            leadingDateKeyName = JoinHandler.extractKeyFromColumnName(leftColumn);
            dependentDateKeyName = JoinHandler.extractKeyFromColumnName(rightColumn);
        } else if (rightColAliasPart.equals(leadingAlias)) {
            leadingDateKeyName = JoinHandler.extractKeyFromColumnName(rightColumn);
            dependentDateKeyName = JoinHandler.extractKeyFromColumnName(leftColumn);
        } else {
            throw new QueryExecutionException(String.format(
                    "Could not determine leading/dependent side for dependent join step. Leading alias: '%s', Left col: '%s' (alias '%s'), Right col: '%s' (alias '%s'). Condition: %s",
                    leadingAlias, leftColumn, leftColAliasPart, rightColumn, rightColAliasPart, condition),
                    dependentQuery.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }
        logger.debug(
                "Dependent Step Details: LeadingDateKey='{}' (from alias '{}'), DependentDateKeyToFilter='{}' (within query for alias '{}')",
                leadingDateKeyName, leadingAlias, dependentDateKeyName, dependentAlias);

        // Build leading dates set from bindings
        Set<java.time.LocalDate> leadingDatesSet = new HashSet<>();
        Bindings bindings = leadingResult.bindings();
        if (bindings != null && bindings.size() > 0) {
            for (int i = 0; i < bindings.size(); i++) {
                String varNameInBinding = bindings.variableNameAt(i);
                boolean isStructuralDateKey = leadingDateKeyName.startsWith("$");

                boolean nameMatch = (varNameInBinding != null && varNameInBinding.equals(leadingDateKeyName)) ||
                        (varNameInBinding != null && leadingDateKeyName.startsWith("$")
                                && varNameInBinding.equals(leadingDateKeyName.substring(1)))
                        ||
                        (varNameInBinding != null && !leadingDateKeyName.startsWith("$")
                                && varNameInBinding.equals("$" + leadingDateKeyName))
                        ||
                        (varNameInBinding == null && isStructuralDateKey);

                if (nameMatch && bindings.valueTypeAt(i) == ValueType.DATE) {
                    Object value = bindings.valueAt(i);
                    if (value instanceof java.time.LocalDate) {
                        leadingDatesSet.add((java.time.LocalDate) value);
                    }
                } else if (isStructuralDateKey && varNameInBinding == null
                        && bindings.valueTypeAt(i) == ValueType.DATE) {
                    Object value = bindings.valueAt(i);
                    if (value instanceof java.time.LocalDate) {
                        leadingDatesSet.add((java.time.LocalDate) value);
                    }
                }
            }
        }

        if (leadingDatesSet.isEmpty()) {
            logger.info(
                    "Leading side (alias: '{}', key: '{}') for dependent step provided no usable dates. Dependent subquery '{}' (source: '{}') will execute without temporal pre-filter.",
                    leadingAlias, leadingDateKeyName, dependentAlias, dependentQuery.source());
            return executeWithRequirements(dependentQuery, indexes, QueryAttributeAnalyzer.analyze(dependentQuery),
                    new SubqueryContext());
        }

        java.time.LocalDate minLeadingDate = java.util.Collections.min(leadingDatesSet);
        java.time.LocalDate maxLeadingDate = java.util.Collections.max(leadingDatesSet);
        logger.debug("Leading date range for dependent filter (alias '{}'): {} to {}", dependentAlias, minLeadingDate,
                maxLeadingDate);

        com.example.query.model.TemporalPredicate originalPredicate = condition.temporalPredicate().orElseThrow(
                () -> new QueryExecutionException(
                        "Temporal predicate missing in a temporal join eligible for dependent step.",
                        dependentQuery.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR));

        String keyForNewFilterInDependent = dependentDateKeyName;

        com.example.query.model.condition.Temporal newFilterCondition;
        switch (originalPredicate) {
            case BEFORE:
                newFilterCondition = new com.example.query.model.condition.Temporal(
                        Optional.of(minLeadingDate.atStartOfDay()), Optional.empty(),
                        Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.AFTER);
                break;
            case AFTER:
                newFilterCondition = new com.example.query.model.condition.Temporal(
                        Optional.of(maxLeadingDate.atStartOfDay()), Optional.empty(),
                        Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.BEFORE);
                break;
            case EQUAL:
                newFilterCondition = new com.example.query.model.condition.Temporal(
                        Optional.of(minLeadingDate.atStartOfDay()), Optional.of(maxLeadingDate.atStartOfDay()),
                        Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.INTERSECT);
                break;
            case INTERSECT:
                newFilterCondition = new com.example.query.model.condition.Temporal(
                        Optional.of(minLeadingDate.atStartOfDay()), Optional.of(maxLeadingDate.atStartOfDay()),
                        Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.INTERSECT);
                break;
            case CONTAINS:
            case CONTAINED_BY:
                newFilterCondition = new com.example.query.model.condition.Temporal(
                        Optional.of(minLeadingDate.atStartOfDay()), Optional.of(maxLeadingDate.atStartOfDay()),
                        Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.INTERSECT);
                logger.debug("Mapped original predicate {} to INTERSECT for dependent filter.", originalPredicate);
                break;
            case BEFORE_EQUAL:
                newFilterCondition = new com.example.query.model.condition.Temporal(
                        Optional.of(minLeadingDate.atStartOfDay()), Optional.empty(),
                        Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.AFTER_EQUAL);
                break;
            case AFTER_EQUAL:
                newFilterCondition = new com.example.query.model.condition.Temporal(
                        Optional.of(maxLeadingDate.atStartOfDay()), Optional.empty(),
                        Optional.of(keyForNewFilterInDependent),
                        com.example.query.model.TemporalPredicate.BEFORE_EQUAL);
                break;
            default:
                logger.warn(
                        "Dependent join step does not support temporal predicate '{}' for precise pre-filtering. Executing dependent query '{}' (source: '{}') without modification.",
                        originalPredicate, dependentAlias, dependentQuery.source());
                return executeWithRequirements(dependentQuery, indexes, QueryAttributeAnalyzer.analyze(dependentQuery),
                        new SubqueryContext());
        }
        logger.debug("Constructed new temporal filter for dependent query '{}' (key to filter: '{}'): {}",
                dependentAlias, keyForNewFilterInDependent, newFilterCondition);

        List<Condition> newConditionsForDependent = new ArrayList<>(dependentQuery.conditions());
        boolean merged = false;

        for (int i = 0; i < newConditionsForDependent.size(); i++) {
            Condition currentCond = newConditionsForDependent.get(i);
            if (currentCond instanceof com.example.query.model.condition.Temporal existingTemporalCond) {
                Optional<String> existingVarNameOpt = existingTemporalCond.qualifiedVariableName();
                if (existingVarNameOpt.isPresent() && existingVarNameOpt.get().equals(keyForNewFilterInDependent)) {
                    Optional<com.example.query.model.condition.Temporal> mergedTemporalOpt = existingTemporalCond
                            .intersectWith(newFilterCondition);
                    if (mergedTemporalOpt.isPresent()) {
                        newConditionsForDependent.set(i, mergedTemporalOpt.get());
                        logger.debug("Merged new temporal filter with existing one on key '{}' in dependent query '{}'",
                                keyForNewFilterInDependent, dependentAlias);
                        merged = true;
                        break;
                    }
                }
            }
        }
        if (!merged) {
            newConditionsForDependent.add(newFilterCondition);
            logger.debug("Added new temporal filter to dependent query '{}' conditions list.", dependentAlias);
        }

        Query modifiedDependentQuery = new Query(
                dependentQuery.source(),
                newConditionsForDependent,
                dependentQuery.orderBy(),
                dependentQuery.limit(),
                dependentQuery.granularity(),
                dependentQuery.granularitySize(),
                dependentQuery.selectColumns(),
                dependentQuery.variableRegistry(),
                List.of(),
                Optional.empty(),
                dependentQuery.groupByColumns());

        logger.debug("Executing modified dependent query for alias '{}' (source '{}'): {}", dependentAlias,
                modifiedDependentQuery.source(), modifiedDependentQuery);
        CellResult result = executeWithRequirements(modifiedDependentQuery, indexes,
                QueryAttributeAnalyzer.analyze(modifiedDependentQuery), new SubqueryContext());
        logger.info("Modified dependent query for alias '{}' executed. Found {} cells.", dependentAlias,
                result.cellCount());
        return result;
    }

    /**
     * Resolves synonym IDs within a CellResult. Currently a no-op since NER/POS
     * executors handle synonym resolution at execution time in the CellResult
     * world.
     */
    @SuppressWarnings("unused")
    private void resolveSynonymIds(CellResult result) {
        if (result == null || result.bindings() == null) {
            return;
        }
        // Synonym resolution is now handled by NER/POS executors at execution time.
        logger.trace("resolveSynonymIds: resolution handled at execution time, cells={}", result.cellCount());
    }

    /**
     * Determines if a join condition represents an NER equality join suitable for
     * pushdown.
     */
    private boolean isNerEqualityJoin(JoinCondition condition, CellResult lhsResult, Query rhsQuery) {
        if (condition.operatorType() != JoinCondition.JoinOperatorType.EQUALITY) {
            return false;
        }

        String leftColumn = condition.leftColumn();
        boolean lhsHasEntities = hasEntityValues(lhsResult, leftColumn);
        boolean rhsHasNerConditions = hasNerConditions(rhsQuery);

        return lhsHasEntities && rhsHasNerConditions;
    }

    /**
     * Checks if the LHS CellResult has entity values in the specified column.
     */
    private boolean hasEntityValues(CellResult result, String columnName) {
        if (result == null) {
            return false;
        }
        Bindings bindings = result.bindings();
        if (bindings == null || bindings.size() == 0) {
            return false;
        }

        for (int i = 0; i < bindings.size(); i++) {
            String varName = bindings.variableNameAt(i);
            if (varName != null && varName.endsWith("." + extractColumnBaseName(columnName))) {
                ValueType valueType = bindings.valueTypeAt(i);
                if (valueType == ValueType.ENTITY || valueType == ValueType.UNRESOLVED_NER_ID) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if the RHS query contains NER conditions.
     */
    private boolean hasNerConditions(Query query) {
        return containsNerConditions(query.conditions());
    }

    /**
     * Recursively checks if a list of conditions contains NER conditions.
     */
    private boolean containsNerConditions(List<Condition> conditions) {
        for (Condition condition : conditions) {
            if (condition instanceof Ner) {
                return true;
            } else if (condition instanceof Logical logical) {
                if (containsNerConditions(logical.conditions())) {
                    return true;
                }
            } else if (condition instanceof Not not) {
                if (containsNerConditions(List.of(not.condition()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Extracts the base column name from a qualified column name.
     */
    private String extractColumnBaseName(String qualifiedColumnName) {
        int dotIndex = qualifiedColumnName.lastIndexOf('.');
        return dotIndex >= 0 ? qualifiedColumnName.substring(dotIndex + 1) : qualifiedColumnName;
    }

    /**
     * Executes a single step of a dependent join, specifically for NER pushdown.
     */
    private CellResult executeSingleDependentStepForNer(
            CellResult leadingResult,
            String leadingAlias,
            Query dependentQuery,
            String dependentAlias,
            JoinCondition condition,
            Map<String, IndexAccessInterface> indexes,
            SubqueryContext overallSubqueryContext,
            AttributeRequirements parentRequirements,
            Query.Granularity granularity,
            int granularitySize)
            throws QueryExecutionException {

        logger.debug(
                "Executing single dependent step for NER: LeadingResult (alias: '{}', cells: {}) -> DependentQuery (alias: '{}', source: '{}') ON {}",
                leadingAlias, leadingResult.cellCount(), dependentAlias, dependentQuery.source(), condition);

        String leftColumn = condition.leftColumn();
        String rightColumn = condition.rightColumn();

        String leadingEntityKeyName;
        String dependentEntityKeyName;

        String leftColAliasPart = JoinHandler.extractAliasFromColumnName(leftColumn);
        String rightColAliasPart = JoinHandler.extractAliasFromColumnName(rightColumn);

        if (leftColAliasPart.equals(leadingAlias)) {
            leadingEntityKeyName = JoinHandler.extractKeyFromColumnName(leftColumn);
            dependentEntityKeyName = JoinHandler.extractKeyFromColumnName(rightColumn);
        } else if (rightColAliasPart.equals(leadingAlias)) {
            leadingEntityKeyName = JoinHandler.extractKeyFromColumnName(rightColumn);
            dependentEntityKeyName = JoinHandler.extractKeyFromColumnName(leftColumn);
        } else {
            throw new QueryExecutionException(String.format(
                    "Could not determine leading/dependent side for NER dependent join step. Leading alias: '%s', Left col: '%s' (alias '%s'), Right col: '%s' (alias '%s'). Condition: %s",
                    leadingAlias, leftColumn, leftColAliasPart, rightColumn, rightColAliasPart, condition),
                    dependentQuery.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        // Extract unique entity terms from the leading result bindings
        Set<String> leadingEntityTerms = new HashSet<>();
        Bindings bindings = leadingResult.bindings();
        if (bindings != null && bindings.size() > 0) {
            for (int i = 0; i < bindings.size(); i++) {
                String varNameInBinding = bindings.variableNameAt(i);
                boolean nameMatch = (varNameInBinding != null
                        && varNameInBinding.endsWith("." + extractColumnBaseName(leadingEntityKeyName)));

                if (nameMatch && (bindings.valueTypeAt(i) == ValueType.ENTITY
                        || bindings.valueTypeAt(i) == ValueType.UNRESOLVED_NER_ID)) {
                    Object value = bindings.valueAt(i);
                    if (value instanceof String) {
                        leadingEntityTerms.add((String) value);
                    }
                }
            }
        }

        if (leadingEntityTerms.size() > NER_PUSHDOWN_SYNONYM_THRESHOLD) {
            logger.info(
                    "NER pushdown for dependent step (LHS alias: '{}', key: '{}'; RHS alias: '{}', source: '{}') skipped. Number of leading entity terms ({}) exceeds threshold ({}).",
                    leadingAlias, leadingEntityKeyName, dependentAlias, dependentQuery.source(),
                    leadingEntityTerms.size(), NER_PUSHDOWN_SYNONYM_THRESHOLD);
            return executeWithRequirements(dependentQuery, indexes,
                    QueryAttributeAnalyzer.analyze(dependentQuery, parentRequirements), new SubqueryContext());
        }

        if (leadingEntityTerms.isEmpty()) {
            logger.info(
                    "Leading side (alias: '{}', key: '{}') for NER dependent step provided no usable entity terms. Dependent subquery '{}' (source: '{}') will execute without NER pre-filter.",
                    leadingAlias, leadingEntityKeyName, dependentAlias, dependentQuery.source());
            return executeWithRequirements(dependentQuery, indexes, QueryAttributeAnalyzer.analyze(dependentQuery),
                    new SubqueryContext());
        }

        logger.debug("NER pushdown: Leading entity terms from '{}': {}", leadingAlias, leadingEntityTerms);

        List<Condition> modifiedConditions = modifyConditionsForNerPushdown(dependentQuery.conditions(),
                dependentEntityKeyName, new ArrayList<>(leadingEntityTerms));

        Query modifiedDependentQuery = new Query(
                dependentQuery.source(),
                modifiedConditions,
                dependentQuery.orderBy(),
                dependentQuery.limit(),
                dependentQuery.granularity(),
                dependentQuery.granularitySize(),
                dependentQuery.selectColumns(),
                dependentQuery.variableRegistry(),
                List.of(),
                Optional.empty(),
                dependentQuery.groupByColumns());

        logger.debug("Executing modified dependent query for NER pushdown: alias '{}' (source '{}')", dependentAlias,
                modifiedDependentQuery.source());
        CellResult result = executeWithRequirements(modifiedDependentQuery, indexes,
                QueryAttributeAnalyzer.analyze(modifiedDependentQuery, parentRequirements), new SubqueryContext());
        logger.info("Modified dependent query for NER pushdown (alias '{}') executed. Found {} cells.", dependentAlias,
                result.cellCount());
        return result;
    }

    /**
     * Modifies conditions to add NER pushdown filters.
     */
    private List<Condition> modifyConditionsForNerPushdown(List<Condition> originalConditions, String targetEntityKey,
            List<String> leadingTerms) {
        List<Condition> modifiedConditions = new ArrayList<>();
        boolean foundTargetNerCondition = false;

        for (Condition condition : originalConditions) {
            Condition modifiedCondition = modifyConditionForNerPushdown(condition, targetEntityKey, leadingTerms);
            if (modifiedCondition != condition) {
                foundTargetNerCondition = true;
            }
            modifiedConditions.add(modifiedCondition);
        }

        if (!foundTargetNerCondition) {
            logger.debug(
                    "No suitable NER condition found to modify for pushdown with key '{}'. Using original conditions.",
                    targetEntityKey);
        }

        return modifiedConditions;
    }

    /**
     * Recursively modifies a single condition for NER pushdown.
     */
    private Condition modifyConditionForNerPushdown(Condition condition, String targetEntityKey,
            List<String> leadingTerms) {
        if (condition instanceof Ner nerCondition) {
            String qualifiedVarName = nerCondition.qualifiedVariableName();
            if (qualifiedVarName != null && qualifiedVarName.endsWith("." + extractColumnBaseName(targetEntityKey))) {
                List<String> newTargets = new ArrayList<>(leadingTerms);
                logger.debug("Modifying NER condition for pushdown: original targets={}, new targets={}",
                        nerCondition.targets(), newTargets);
                return new Ner(nerCondition.entityType(), newTargets, nerCondition.qualifiedVariableName(),
                        nerCondition.isVariable());
            }
        } else if (condition instanceof Logical logical) {
            List<Condition> modifiedSubConditions = modifyConditionsForNerPushdown(logical.conditions(),
                    targetEntityKey, leadingTerms);
            return new Logical(logical.operator(), modifiedSubConditions);
        } else if (condition instanceof Not not) {
            Condition modifiedSubCondition = modifyConditionForNerPushdown(not.condition(), targetEntityKey,
                    leadingTerms);
            return new Not(modifiedSubCondition);
        }

        return condition;
    }
}
