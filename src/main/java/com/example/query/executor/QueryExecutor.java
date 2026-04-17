package com.example.query.executor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.binding.Variable;
import com.example.query.index.IndexManager;
import com.example.query.model.JoinCondition;
import com.example.query.model.Query;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Logical.LogicalOperator;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Not;
import com.example.query.result.TableResultService;

/**
 * Executes queries against the provided indexes.
 * Responsible for coordinating the execution of all conditions in a query
 * and combining their results according to the query's logical structure.
 *
 * Supports the execution of subqueries and joins between result sets.
 */
public class QueryExecutor {
    private static final Logger logger = LoggerFactory.getLogger(QueryExecutor.class);
    private static final int NER_PUSHDOWN_SYNONYM_THRESHOLD = 100_000; // Threshold for NER pushdown. Causes slowdowns for large synonym sets.

    private ConditionExecutorFactory executorFactory;
    private PushdownStrategy pushdownStrategy = PushdownStrategy.NONE;
    private final String stitchStrategy;
    private final SynonymManager synonymManager;
    private final ConditionExecutorFactory injectedExecutorFactory;

    /**
     * Full constructor for QueryExecutor, allowing injection of ConditionExecutorFactory for testing.
     *
     * @param tableResultService The TableResultService instance.
     * @param stitchStrategy The stitch execution strategy.
     * @param synonymManager The SynonymManager instance.
     * @param injectedExecutorFactory An optional ConditionExecutorFactory to inject for testing.
     */
    public QueryExecutor(TableResultService tableResultService, String stitchStrategy, SynonymManager synonymManager, ConditionExecutorFactory injectedExecutorFactory) {
        this.synonymManager = synonymManager;
        this.stitchStrategy = (stitchStrategy == null || stitchStrategy.isBlank()) ? "none" : stitchStrategy;
        this.injectedExecutorFactory = injectedExecutorFactory;
        logger.debug("Initialized QueryExecutor with stitch strategy: {}, provided SynonymManager, and {}injected factory. Default pushdown strategy: {}.",
                this.stitchStrategy,
                this.injectedExecutorFactory == null ? "no " : "",
                this.pushdownStrategy);
    }

    /**
     * Executes a query using the provided IndexManager.
     *
     * @param query The query to execute
     * @param indexManager The IndexManager providing access to indexes and SynonymManager
     * @return QueryResultSoA representing the result of the query
     * @throws QueryExecutionException if execution fails
     */
    public QueryResultSoA execute(Query query, IndexManager indexManager)
            throws QueryExecutionException {

        long startTime = System.nanoTime();
        Map<String, IndexAccessInterface> indexes = indexManager.getAllIndexes();

        // Analyze query to determine attribute requirements for SoA optimization
        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);
        logger.info("=== Query Execution Started ===");
        logger.info("SoA Attribute Requirements: {}", requirements.getRequiredSoAAttributes());
        logger.trace("Full requirements: {}", requirements);

        try {
            QueryResultSoA result = executeWithRequirements(query, indexes, requirements, new SubqueryContext());

            // Resolve any remaining unresolved IDs in the final result
            resolveSynonymIdsInSoA(result, query);
            // Centralize sorting: ensure final SoA is sorted once before returning
            result.ensureSorted();

            long executionTime = System.nanoTime() - startTime;
            logger.info("=== Query Execution Completed Successfully ===");
            logger.info("Total execution time: {} ms", executionTime / 1_000_000.0);

            logger.info("Result type: QueryResultSoA, matches: {}, granularity: {}",
                           result.size(), result.getGranularity());

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
     * The SynonymManager is taken from the instance field.
     *
     * @param query The query to execute
     * @param indexes Map of index name to IndexAccessInterface
     * @param subqueryContext Context containing results of previously executed subqueries
     * @return QueryResultSoA representing the result of the query
     * @throws QueryExecutionException if execution fails
     */
    public QueryResultSoA executeWithContext(Query query, Map<String, IndexAccessInterface> indexes, SubqueryContext subqueryContext)
            throws QueryExecutionException {
        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);
        logger.debug("Query requires attributes: {}", requirements.getRequiredSoAAttributes());
        return executeWithContext(query, indexes, subqueryContext, requirements);
    }

    /**
     * Executes a query with an existing subquery context and attribute requirements.
     * This is the core execution method that supports SoA optimization.
     * The SynonymManager is taken from the instance field.
     *
     * @param query The query to execute
     * @param indexes Map of index name to IndexAccessInterface
     * @param subqueryContext Context containing results of previously executed subqueries
     * @param requirements Attribute requirements for SoA optimization
     * @return QueryResultSoA representing the result of the query
     * @throws QueryExecutionException if execution fails
     */
    private QueryResultSoA executeWithContext(Query query, Map<String, IndexAccessInterface> indexes,
                                     SubqueryContext subqueryContext, AttributeRequirements requirements)
            throws QueryExecutionException {
        Query.Granularity granularity = query.granularity();
        int granularitySize = query.granularitySize().orElse(0);
        if (granularitySize < 0 || granularitySize > 10) {
            throw new IllegalArgumentException("Granularity size must be between 0 and 10, got: " + granularitySize);
        }
        String source = query.source();
        logger.debug("Using source: {}", source);
        // Always execute main conditions first if they exist.
        QueryResultSoA mainConditionsResult = null;
        List<Condition> mainConditions = query.conditions();

        // Initial context for the main conditions is empty (will become unrestricted in LogicalExecutor if needed)
        Optional<FilteringContext> initialContextForMainConditions = Optional.empty();

        if (!mainConditions.isEmpty()) {
            logger.debug("Executing main query conditions...");
            List<Condition> orderedMainConditions = optimizeExecutionOrder(mainConditions);
            if (orderedMainConditions.size() == 1) {
                mainConditionsResult = executeCondition(orderedMainConditions.get(0), indexes, granularity, granularitySize, source, requirements, initialContextForMainConditions);
            } else {
                Logical implicitAnd = new Logical(LogicalOperator.AND, orderedMainConditions);
                mainConditionsResult = executeCondition(implicitAnd, indexes, granularity, granularitySize, source, requirements, initialContextForMainConditions);
            }
            logger.debug("Main query conditions executed, {} matches found.", mainConditionsResult.size());
            // Resolve synonyms after main conditions
            resolveSynonymIdsInSoA(mainConditionsResult, query);
        } else {
            logger.debug("No main query conditions found.");
            mainConditionsResult = new QueryResultSoA(granularity, granularitySize, requirements);
        }

        // --- NEW ITERATIVE JOIN LOGIC ---
        if (!query.joinSteps().isEmpty()) {
            logger.info("Starting chained join execution with {} steps.", query.joinSteps().size());
            QueryResultSoA currentLhsSoA = mainConditionsResult;
            String currentLhsAlias = query.mainAlias().orElse(com.example.query.parser.QueryModelBuilder.DEFAULT_MAIN_ALIAS);
            subqueryContext.addQueryResult(currentLhsAlias, currentLhsSoA); // Add initial LHS to context

            logger.debug("Initial LHS for join chain: alias='{}', size={}", currentLhsAlias, currentLhsSoA.size());

            for (com.example.query.model.JoinStep step : query.joinSteps()) {
                logger.debug("Processing JoinStep: Left='{}' ({}), Right='{}', Type='{}', ON='{}'",
                             step.leftSourceAlias(), currentLhsAlias, step.rightSourceAlias(), step.joinType(), step.onCondition());

                if (!step.leftSourceAlias().equals(currentLhsAlias)) {
                    throw new QueryExecutionException(
                        String.format("Join chain integrity error: Expected LHS alias '%s' for current step, but JoinStep specifies '%s'. This indicates a mismatch between the accumulated join result alias and the next step's expectation.",
                                      currentLhsAlias, step.leftSourceAlias()),
                        query.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR
                    );
                }

                Query rhsQuery = step.subquery();
                String rhsAlias = step.rightSourceAlias();
                AttributeRequirements rhsRequirements = QueryAttributeAnalyzer.analyze(rhsQuery, requirements);
                QueryResultSoA rhsSoA;

                // --- Pushdown Logic for this step ---
                boolean eligibleForTemporalPushdown = this.pushdownStrategy == PushdownStrategy.OPTIMIZED &&
                                              step.joinType() == JoinCondition.JoinType.INNER &&
                                              step.onCondition().operatorType() == JoinCondition.JoinOperatorType.TEMPORAL;

                boolean eligibleForNerPushdown = this.pushdownStrategy == PushdownStrategy.OPTIMIZED &&
                                              step.joinType() == JoinCondition.JoinType.INNER &&
                                              step.onCondition().operatorType() == JoinCondition.JoinOperatorType.EQUALITY &&
                                              isNerEqualityJoin(step.onCondition(), currentLhsSoA, rhsQuery);

                if (eligibleForTemporalPushdown) {
                    logger.info("Attempting OPTIMIZED pushdown for JoinStep: LHS='{}', RHS Query Source='{}' (alias '{}'), ON {}", currentLhsAlias, rhsQuery.source(), rhsAlias, step.onCondition());
                    try {
                        // Pass parentRequirements (overall query requirements) for context, but executed subquery will have its own.
                        rhsSoA = executeSingleDependentStep(currentLhsSoA, currentLhsAlias, rhsQuery, rhsAlias, step.onCondition(), indexes, subqueryContext, requirements, query.granularity(), query.granularitySize().orElse(0));
                        resolveSynonymIdsInSoA(rhsSoA, rhsQuery); // Resolve for dependent step result
                    } catch (QueryExecutionException qe) {
                        logger.warn("Dependent execution for step (LHS: '{}', RHS: '{}') failed with QueryExecutionException: {}. Falling back to independent execution for this step.", currentLhsAlias, rhsAlias, qe.getMessage());
                        rhsSoA = executeWithRequirements(rhsQuery, indexes, rhsRequirements, new SubqueryContext()); // Execute independently with fresh context
                        resolveSynonymIdsInSoA(rhsSoA, rhsQuery); // Resolve for independent step result
                    } catch (Exception e) {
                        logger.warn("Dependent execution for step (LHS: '{}', RHS: '{}') failed with generic Exception: {}. Falling back to independent execution for this step.", currentLhsAlias, rhsAlias, e.getMessage(), e);
                        rhsSoA = executeWithRequirements(rhsQuery, indexes, rhsRequirements, new SubqueryContext()); // Execute independently with fresh context
                        resolveSynonymIdsInSoA(rhsSoA, rhsQuery);
                    }
                } else if (eligibleForNerPushdown) {
                    logger.info("Attempting OPTIMIZED NER pushdown for JoinStep: LHS='{}', RHS Query Source='{}' (alias '{}'), ON {}", currentLhsAlias, rhsQuery.source(), rhsAlias, step.onCondition());
                    try {
                        rhsSoA = executeSingleDependentStepForNer(currentLhsSoA, currentLhsAlias, rhsQuery, rhsAlias, step.onCondition(), indexes, subqueryContext, requirements, query.granularity(), query.granularitySize().orElse(0));
                        resolveSynonymIdsInSoA(rhsSoA, rhsQuery);
                    } catch (QueryExecutionException qe) {
                        logger.warn("NER dependent execution for step (LHS: '{}', RHS: '{}') failed with QueryExecutionException: {}. Falling back to independent execution for this step.", currentLhsAlias, rhsAlias, qe.getMessage());
                        rhsSoA = executeWithRequirements(rhsQuery, indexes, rhsRequirements, new SubqueryContext());
                        resolveSynonymIdsInSoA(rhsSoA, rhsQuery);
                    } catch (Exception e) {
                        logger.warn("NER dependent execution for step (LHS: '{}', RHS: '{}') failed with generic Exception: {}. Falling back to independent execution for this step.", currentLhsAlias, rhsAlias, e.getMessage(), e);
                        rhsSoA = executeWithRequirements(rhsQuery, indexes, rhsRequirements, new SubqueryContext());
                        resolveSynonymIdsInSoA(rhsSoA, rhsQuery);
                    }
                } else {
                    logger.info("Executing RHS subquery '{}' (source: '{}') independently for JoinStep.", rhsAlias, rhsQuery.source());
                    rhsSoA = executeWithRequirements(rhsQuery, indexes, rhsRequirements, new SubqueryContext()); // Execute independently with fresh context for RHS
                    resolveSynonymIdsInSoA(rhsSoA, rhsQuery); // Resolve after independent RHS execution
                }

                subqueryContext.addQueryResult(rhsAlias, rhsSoA);
                logger.debug("Executed RHS '{}' for JoinStep, size={}", rhsAlias, rhsSoA.size());

                // Perform binary join
                JoinHandler joinHandler = new JoinHandler();
                logger.debug("Calling JoinHandler.performBinaryJoin: LHS='{}'({}), RHS='{}'({}), Type={}, Condition={}",
                    currentLhsAlias, currentLhsSoA.size(), rhsAlias, rhsSoA.size(), step.joinType(), step.onCondition());

                currentLhsSoA = joinHandler.performBinaryJoin(
                    currentLhsSoA,          // lhsSoA
                    currentLhsAlias,        // lhsAlias
                    rhsSoA,                 // rhsSoA
                    rhsAlias,               // rhsAlias (which is step.rightSourceAlias())
                    step.onCondition(),     // condition
                    step.joinType(),        // joinType
                    query.granularity(),    // outputGranularity (from main query)
                    query.granularitySize().orElse(0), // outputGranularitySize (from main query)
                    requirements            // outputRequirements (overall requirements for the main query)
                );
                logger.debug("JoinHandler.performBinaryJoin completed. Result size: {}", currentLhsSoA.size());

                currentLhsAlias = rhsAlias; // The result of the join is now identified by the RHS alias for the next step
                subqueryContext.addQueryResult(currentLhsAlias, currentLhsSoA); // Update context with the new intermediate result
                logger.debug("JoinStep completed. New currentLhsAlias='{}', size={}", currentLhsAlias, currentLhsSoA.size());
            }
            logger.info("Chained join execution completed. Final result alias: '{}', size: {}", currentLhsAlias, currentLhsSoA.size());
            return currentLhsSoA; // This is the final result after all joins
        } else {
            // No JOIN steps
            logger.debug("No join steps in query. Returning main conditions result.");
            return mainConditionsResult;
        }
    }

    /**
     * Optimizes the execution order of conditions based on variable dependencies.
     * Conditions that produce variables should be executed before conditions that consume them.
     *
     * @param conditions The original list of conditions
     * @return A new list with optimized execution order
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
                logger.warn("Potential dependency cycle or missing variable producer. Adding condition {} to maintain progress.", remaining.get(0));
                Condition next = remaining.remove(0);
                ordered.add(next);
                producedVariables.addAll(next.getProducedVariables());
            }
        }

        logger.debug("Optimized execution order: {}", ordered.stream().map(Condition::getType).collect(Collectors.toList()));
        return ordered;
    }

    /**
     * Executes a single condition against the indexes.
     *
     * @param condition The condition to execute
     * @param indexes Map of index name to IndexAccessInterface
     * @param granularity The query granularity
     * @param granularitySize The window size for sentence granularity
     * @param source The corpus name
     * @param requirements Attribute requirements for SoA optimization
     * @param context The filtering context for the condition
     * @return QueryResultSoA containing matches at the specified granularity level
     * @throws QueryExecutionException if execution fails
     */
    private QueryResultSoA executeCondition(
            Condition condition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String source,
            AttributeRequirements requirements,
            Optional<FilteringContext> context)
            throws QueryExecutionException {
        logger.debug("Executing condition: type={}, varName={}, contextIsPresent={}, granularity={}",
                condition.getType(),
                condition.getProducedVariables().stream().findFirst().orElse("N/A"),
                context.isPresent(),
                granularity);

        ConditionExecutor<Condition> executor = executorFactory.getExecutor(condition);
        return executor.execute(condition, indexes, granularity, granularitySize, source, requirements, context);
    }

    /**
     * Sets the pushdown strategy for this executor.
     * @param strategy The pushdown strategy to use
     */
    public void setPushdownStrategy(PushdownStrategy strategy) {
        if (strategy != null) {
            this.pushdownStrategy = strategy;
            logger.debug("Pushdown strategy set to: {}", this.pushdownStrategy);
        } else {
            logger.warn("Attempted to set null pushdown strategy. Retaining current: {}", this.pushdownStrategy);
        }
    }

    private QueryResultSoA executeWithRequirements(Query query, Map<String, IndexAccessInterface> indexes,
                                        AttributeRequirements requirements, SubqueryContext subqueryContext)
            throws QueryExecutionException {
        // Initialize the executor factory here, as it depends on the current query's granularity.
        if (this.injectedExecutorFactory != null) {
            this.executorFactory = this.injectedExecutorFactory;
            logger.debug("Using injected ConditionExecutorFactory for query: {}", query.source());
        } else {
            this.executorFactory = new ConditionExecutorFactory(this.synonymManager, this.stitchStrategy, query.granularity());
            logger.debug("Created new ConditionExecutorFactory for query: {} with stitchStrategy: {} and granularity: {}", query.source(), this.stitchStrategy, query.granularity());
        }

        return executeWithContext(query, indexes, subqueryContext, requirements);
    }

    /**
     * Executes a single step of a dependent join, specifically for temporal pushdown.
     * Modifies the dependent (RHS) query based on date ranges from the leading (LHS) SoA.
     *
     * @param leadingSoA The QueryResultSoA from the leading (LHS) side of the join.
     * @param leadingAlias The alias of the leadingSoA.
     * @param dependentQuery The original Query object for the dependent (RHS) side.
     * @param dependentAlias The alias for the RHS subquery.
     * @param condition The JoinCondition for this specific step.
     * @param indexes Map of available indexes.
     * @param overallSubqueryContext The main subquery context (used for logging, not for execution of modified query).
     * @param parentRequirements Attribute requirements from the parent query (for context).
     * @param granularity Granularity of the parent query.
     * @param granularitySize Granularity size of the parent query.
     * @return QueryResultSoA of the (potentially modified) dependent query.
     * @throws QueryExecutionException if errors occur during dependent execution setup or sub-execution.
     */
    private QueryResultSoA executeSingleDependentStep(
            QueryResultSoA leadingSoA,
            String leadingAlias,
            Query dependentQuery,
            String dependentAlias,
            JoinCondition condition,
            Map<String, IndexAccessInterface> indexes,
            SubqueryContext overallSubqueryContext, // Avoid using this for execution, use new SubqueryContext()
            AttributeRequirements parentRequirements,
            Query.Granularity granularity, // Not directly used for subquery, but for context
            int granularitySize) // Not directly used for subquery, but for context
            throws QueryExecutionException {
        logger.debug("Executing single dependent step: LeadingSoA (alias: '{}', size: {}) -> DependentQuery (alias: '{}', source: '{}') ON {}",
                     leadingAlias, leadingSoA.size(), dependentAlias, dependentQuery.source(), condition);

        String leftColumn = condition.leftColumn();
        // Extract dependent-side alias/key names
        String rightColumn = condition.rightColumn(); // used below when extracting dependent key name

        String leadingDateKeyName;    // Key name (e.g., $date, q1.date) within the leadingSoA
        String dependentDateKeyName;  // Key name (e.g., $date, q2.eventDate) within the dependentQuery that needs filtering

        String leftColAliasPart = JoinHandler.extractAliasFromColumnName(leftColumn);
        String rightColAliasPart = JoinHandler.extractAliasFromColumnName(rightColumn);

        if (leftColAliasPart.equals(leadingAlias)) {
            leadingDateKeyName = JoinHandler.extractKeyFromColumnName(leftColumn);
            // The dependentDateKeyName is the one on the *other* side of the condition, belonging to the subquery being modified.
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
        logger.debug("Dependent Step Details: LeadingDateKey='{}' (from alias '{}'), DependentDateKeyToFilter='{}' (within query for alias '{}')",
                     leadingDateKeyName, leadingAlias, dependentDateKeyName, dependentAlias);

        Set<java.time.LocalDate> leadingDatesSet = new HashSet<>();
        if (leadingSoA != null && leadingSoA.size() > 0) {
            for (int i = 0; i < leadingSoA.size(); i++) {
                String varNameInLhsSoA = leadingSoA.getVariableNameAt(i);
                // Check if varNameInLhsSoA (e.g., $date, main.date) matches leadingDateKeyName (e.g., $date, main.date)
                boolean nameMatch = (varNameInLhsSoA != null && varNameInLhsSoA.equals(leadingDateKeyName)) ||
                                    (varNameInLhsSoA != null && leadingDateKeyName.startsWith("$") && varNameInLhsSoA.equals(leadingDateKeyName.substring(1))) ||
                                    (varNameInLhsSoA != null && !leadingDateKeyName.startsWith("$") && varNameInLhsSoA.equals("$" + leadingDateKeyName)) ||
                                    (varNameInLhsSoA == null && com.example.query.executor.SoAJoinOptimizer.isStructuralDateKey(leadingDateKeyName)); // Handle structural keys if varName is null

                if (nameMatch && leadingSoA.getValueTypeAt(i) == com.example.query.binding.ValueType.DATE) {
                    Object value = leadingSoA.getValueAt(i);
                    if (value instanceof java.time.LocalDate) {
                        leadingDatesSet.add((java.time.LocalDate) value);
                    }
                } else if (com.example.query.executor.SoAJoinOptimizer.isStructuralDateKey(leadingDateKeyName) &&
                           varNameInLhsSoA == null && // ensure it's a structural key column in SoA
                           leadingSoA.getValueTypeAt(i) == com.example.query.binding.ValueType.DATE) {
                     Object value = leadingSoA.getValueAt(i);
                     if (value instanceof java.time.LocalDate) {
                         leadingDatesSet.add((java.time.LocalDate) value);
                     }
                }
            }
        }

        if (leadingDatesSet.isEmpty()) {
            logger.info("Leading side (alias: '{}', key: '{}') for dependent step provided no usable dates. Dependent subquery '{}' (source: '{}') will execute without temporal pre-filter for this step. Join may yield few/no results if INNER.",
                        leadingAlias, leadingDateKeyName, dependentAlias, dependentQuery.source());
            return executeWithRequirements(dependentQuery, indexes, QueryAttributeAnalyzer.analyze(dependentQuery), new SubqueryContext());
        }

        java.time.LocalDate minLeadingDate = java.util.Collections.min(leadingDatesSet);
        java.time.LocalDate maxLeadingDate = java.util.Collections.max(leadingDatesSet);
        logger.debug("Leading date range for dependent filter (alias '{}'): {} to {}", dependentAlias, minLeadingDate, maxLeadingDate);

        com.example.query.model.TemporalPredicate originalPredicate = condition.temporalPredicate().orElseThrow(() ->
            new QueryExecutionException("Temporal predicate missing in a temporal join eligible for dependent step.", dependentQuery.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR)
        );

        // The qualifiedVariableName for the new filter condition should be the dependentDateKeyName.
        // This name must be recognizable *within* the dependentQuery (e.g., subAlias.$date or just $date if no alias there).
        // For now, using it directly. If dependentDateKeyName is like "q2.date", Temporal condition will use that.
        // If it's just "$date", it applies to the default alias within the subquery.
        String keyForNewFilterInDependent = dependentDateKeyName;

        com.example.query.model.condition.Temporal newFilterCondition;
        switch (originalPredicate) {
             case BEFORE: newFilterCondition = new com.example.query.model.condition.Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.empty(), Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.AFTER); break;
             case AFTER: newFilterCondition = new com.example.query.model.condition.Temporal(Optional.of(maxLeadingDate.atStartOfDay()), Optional.empty(), Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.BEFORE); break;
             case EQUAL: newFilterCondition = new com.example.query.model.condition.Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.of(maxLeadingDate.atStartOfDay()), Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.INTERSECT); break;
             case INTERSECT: newFilterCondition = new com.example.query.model.condition.Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.of(maxLeadingDate.atStartOfDay()), Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.INTERSECT); break;
             case CONTAINS: case CONTAINED_BY: newFilterCondition = new com.example.query.model.condition.Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.of(maxLeadingDate.atStartOfDay()), Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.INTERSECT); logger.debug("Mapped original predicate {} to INTERSECT for dependent filter.", originalPredicate); break;
             case BEFORE_EQUAL: newFilterCondition = new com.example.query.model.condition.Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.empty(), Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.AFTER_EQUAL); break;
             case AFTER_EQUAL: newFilterCondition = new com.example.query.model.condition.Temporal(Optional.of(maxLeadingDate.atStartOfDay()), Optional.empty(), Optional.of(keyForNewFilterInDependent), com.example.query.model.TemporalPredicate.BEFORE_EQUAL); break;
             default:
                logger.warn("Dependent join step does not support temporal predicate '{}' for precise pre-filtering. Executing dependent query '{}' (source: '{}') without this specific temporal modification.", originalPredicate, dependentAlias, dependentQuery.source());
                return executeWithRequirements(dependentQuery, indexes, QueryAttributeAnalyzer.analyze(dependentQuery), new SubqueryContext());
        }
        logger.debug("Constructed new temporal filter for dependent query '{}' (key to filter: '{}'): {}", dependentAlias, keyForNewFilterInDependent, newFilterCondition);

        List<Condition> newConditionsForDependent = new ArrayList<>(dependentQuery.conditions());
        boolean merged = false;
        // The dependentDateKeyName might be qualified (e.g. sq2.date) or unqualified (e.g. $date)
        // The merging logic needs to compare against the qualifiedVariableName in existing Temporal conditions of the subquery.

        for (int i = 0; i < newConditionsForDependent.size(); i++) {
            Condition currentCond = newConditionsForDependent.get(i);
            if (currentCond instanceof com.example.query.model.condition.Temporal existingTemporalCond) {
                Optional<String> existingVarNameOpt = existingTemporalCond.qualifiedVariableName();
                if (existingVarNameOpt.isPresent() && existingVarNameOpt.get().equals(keyForNewFilterInDependent)) {
                    Optional<com.example.query.model.condition.Temporal> mergedTemporalOpt = existingTemporalCond.intersectWith(newFilterCondition);
                    if (mergedTemporalOpt.isPresent()) {
                        newConditionsForDependent.set(i, mergedTemporalOpt.get());
                        logger.debug("Merged new temporal filter with existing one on key '{}' in dependent query '{}'", keyForNewFilterInDependent, dependentAlias);
                        merged = true; break;
                    }
                }
            } // TODO: Consider merging into Logical.AND conditions if necessary
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
            dependentQuery.variableRegistry(), // Use original registry, new filter is on existing or new vars
            List.of(), // No further chained joins within this modified execution
            Optional.empty(), // No explicit main alias for this internal modified query
            dependentQuery.groupByColumns()
        );

        logger.debug("Executing modified dependent query for alias '{}' (source '{}'): {}", dependentAlias, modifiedDependentQuery.source(), modifiedDependentQuery.toString());
        // Execute with a new SubqueryContext to isolate this modified execution
        QueryResultSoA result = executeWithRequirements(modifiedDependentQuery, indexes, QueryAttributeAnalyzer.analyze(modifiedDependentQuery), new SubqueryContext());
        // Resolve synonyms for the result of the modified query
        resolveSynonymIdsInSoA(result, modifiedDependentQuery); // Use modifiedDependentQuery for VariableRegistry
        logger.info("Modified dependent query for alias '{}' executed. Found {} matches.", dependentAlias, result.size());
        return result;
    }

    /**
     * Resolves synonym IDs to terms within a QueryResultSoA for NER and POS types.
     * Modifies the QueryResultSoA in-place.
     *
     * @param soa The QueryResultSoA to process.
     * @param queryContext The Query object that provides context (e.g., VariableRegistry) for the SoA.
     * @throws QueryExecutionException if synonym resolution fails.
     */
    private void resolveSynonymIdsInSoA(QueryResultSoA soa, Query queryContext) throws QueryExecutionException {
        if (soa == null || soa.isEmpty() || synonymManager == null) {
            return;
        }

        long startTime = System.nanoTime();
        int resolvedCount = 0;

        Map<Integer, String> idsToResolveForNer = new HashMap<>();
        Map<Integer, String> idsToResolveForPos = new HashMap<>();
        List<Integer> indicesToUpdateNer = new ArrayList<>();
        List<Integer> indicesToUpdatePos = new ArrayList<>();

        for (int i = 0; i < soa.size(); i++) {
            ValueType currentType = soa.getValueTypeAt(i);
            if (currentType == ValueType.UNRESOLVED_NER_ID) {
                Object value = soa.getValueAt(i);
                if (value instanceof Integer) {
                    idsToResolveForNer.put((Integer) value, null); // Value will be filled by getTerms
                    indicesToUpdateNer.add(i);
                }
            } else if (currentType == ValueType.UNRESOLVED_POS_ID) {
                Object value = soa.getValueAt(i);
                if (value instanceof Integer) {
                    idsToResolveForPos.put((Integer) value, null);
                    indicesToUpdatePos.add(i);
                }
            }
        }

        if (idsToResolveForNer.isEmpty() && idsToResolveForPos.isEmpty()) {
            return; // Nothing to resolve
        }

        try {
            if (!idsToResolveForNer.isEmpty()) {
                logger.debug("Batch resolving {} UNRESOLVED_NER_ID entries.", idsToResolveForNer.size());
                Map<Integer, String> resolvedNerTerms = synonymManager.getTerms(idsToResolveForNer.keySet());
                for (int originalIndex : indicesToUpdateNer) {
                    Integer unresolvedId = (Integer) soa.getValueAt(originalIndex);
                    String resolvedTerm = resolvedNerTerms.get(unresolvedId);
                    if (resolvedTerm != null) {
                        // Assuming QueryResultSoA has/will have updateValueAndTypeAt
                        soa.updateValueAndTypeAt(originalIndex, resolvedTerm, ValueType.ENTITY);
                        resolvedCount++;
                    } else {
                        logger.warn("Could not resolve NER synonym ID: {} for SoA entry at index {}. Keeping as unresolved ID.", unresolvedId, originalIndex);
                        // Optionally, could change type to a generic STRING or keep as UNRESOLVED if update fails/not found.
                        // For now, it remains UNRESOLVED_NER_ID if term not found.
                    }
                }
            }
            if (!idsToResolveForPos.isEmpty()) {
                logger.debug("Batch resolving {} UNRESOLVED_POS_ID entries.", idsToResolveForPos.size());
                Map<Integer, String> resolvedPosTerms = synonymManager.getTerms(idsToResolveForPos.keySet());
                for (int originalIndex : indicesToUpdatePos) {
                    Integer unresolvedId = (Integer) soa.getValueAt(originalIndex);
                    String resolvedTerm = resolvedPosTerms.get(unresolvedId);
                    String variableName = soa.getVariableNameAt(originalIndex);
                    ValueType targetType = ValueType.POS_TERM; // Default

                    if (variableName != null && queryContext != null && queryContext.variableRegistry() != null) {
                         Optional<Variable> varOpt = queryContext.variableRegistry().getVariable(variableName);
                         if (varOpt.isPresent()) {
                             // This can be used if we need to distinguish between POS_TERM and POS_TAG_TYPE,
                             // but for now, UNRESOLVED_POS_ID usually implies the term associated with a tag.
                             // targetType = varOpt.get().valueType(); // This might give the original intended type.
                         }
                    }

                    if (resolvedTerm != null) {
                        soa.updateValueAndTypeAt(originalIndex, resolvedTerm, targetType);
                        resolvedCount++;
                    } else {
                        logger.warn("Could not resolve POS synonym ID: {} for SoA entry at index {}. Keeping as unresolved ID.", unresolvedId, originalIndex);
                    }
                }
            }
        } catch (Exception e) { // Catching generic Exception from synonymManager.getTerms()
            throw new QueryExecutionException("Failed to resolve synonym IDs in QueryResultSoA", e, queryContext.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        long endTime = System.nanoTime();
        if (resolvedCount > 0) {
            logger.debug("Resolved {} synonym IDs in QueryResultSoA (size: {}) in {} ms.", resolvedCount, soa.size(), (endTime - startTime) / 1_000_000.0);
        }
    }

    /**
     * Determines if a join condition represents an NER equality join suitable for pushdown.
     * Checks if one side of the join involves NER variables that could be filtered.
     */
    private boolean isNerEqualityJoin(JoinCondition condition, QueryResultSoA lhsSoA, Query rhsQuery) {
        // Basic criteria: equality join and at least one side involves NER
        if (condition.operatorType() != JoinCondition.JoinOperatorType.EQUALITY) {
            return false;
        }

        // Check if LHS has NER entities in the join column and RHS has NER conditions that could benefit from filtering
        String leftColumn = condition.leftColumn();

        // Simplified heuristic: check if LHS has ENTITY values and RHS contains NER conditions
        boolean lhsHasEntities = hasEntityValues(lhsSoA, leftColumn);
        boolean rhsHasNerConditions = hasNerConditions(rhsQuery);

        return lhsHasEntities && rhsHasNerConditions;
    }

    /**
     * Checks if the LHS QueryResultSoA has entity values in the specified column.
     */
    private boolean hasEntityValues(QueryResultSoA soa, String columnName) {
        if (soa == null || soa.isEmpty()) {
            return false;
        }

        for (int i = 0; i < soa.size(); i++) {
            String varName = soa.getVariableNameAt(i);
            if (varName != null && varName.endsWith("." + extractColumnBaseName(columnName))) {
                ValueType valueType = soa.getValueTypeAt(i);
                if (valueType == ValueType.ENTITY || valueType == ValueType.UNRESOLVED_NER_ID) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if the RHS query contains NER conditions that could benefit from pushdown.
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
     * Modifies the dependent (RHS) query based on entity values from the leading (LHS) SoA.
     */
    private QueryResultSoA executeSingleDependentStepForNer(
            QueryResultSoA leadingSoA,
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

        logger.debug("Executing single dependent step for NER: LeadingSoA (alias: '{}', size: {}) -> DependentQuery (alias: '{}', source: '{}') ON {}",
                     leadingAlias, leadingSoA.size(), dependentAlias, dependentQuery.source(), condition);

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

        // Extract unique synonym IDs from the leading SoA
        Set<Integer> leadingSynonymIds = new HashSet<>();
        if (leadingSoA != null && leadingSoA.size() > 0 && leadingSoA.getRequirements().needsSynonymIds) {
            for (int i = 0; i < leadingSoA.size(); i++) {
                String varNameInLhsSoA = leadingSoA.getVariableNameAt(i);
                boolean nameMatch = (varNameInLhsSoA != null && varNameInLhsSoA.endsWith("." + extractColumnBaseName(leadingEntityKeyName)));

                if (nameMatch && (leadingSoA.getValueTypeAt(i) == ValueType.ENTITY || leadingSoA.getValueTypeAt(i) == ValueType.UNRESOLVED_NER_ID)) {
                    int synonymId = leadingSoA.getSynonymIdAt(i);
                    if (synonymId >= 0) {
                        leadingSynonymIds.add(synonymId);
                    }
                }
            }
        }

        // Check if NER pushdown should be skipped due to too many synonyms
        if (leadingSynonymIds.size() > NER_PUSHDOWN_SYNONYM_THRESHOLD) {
            logger.info("NER pushdown for dependent step (LHS alias: '{}', key: '{}'; RHS alias: '{}', source: '{}') skipped. Number of leading synonym IDs ({}) exceeds threshold ({}). Dependent subquery will execute without NER pre-filter for this step.",
                        leadingAlias, leadingEntityKeyName, dependentAlias, dependentQuery.source(), leadingSynonymIds.size(), NER_PUSHDOWN_SYNONYM_THRESHOLD);
            return executeWithRequirements(dependentQuery, indexes, QueryAttributeAnalyzer.analyze(dependentQuery, parentRequirements), new SubqueryContext()); // Use parentRequirements for analysis consistency
        }

        if (leadingSynonymIds.isEmpty()) {
            logger.info("Leading side (alias: '{}', key: '{}') for NER dependent step provided no usable synonym IDs. Dependent subquery '{}' (source: '{}') will execute without NER pre-filter for this step.",
                        leadingAlias, leadingEntityKeyName, dependentAlias, dependentQuery.source());
            return executeWithRequirements(dependentQuery, indexes, QueryAttributeAnalyzer.analyze(dependentQuery), new SubqueryContext());
        }

        // Convert synonym IDs to terms
        List<String> leadingTerms;
        try {
            Map<Integer, String> synonymIdToTerm = synonymManager.getTerms(leadingSynonymIds);
            leadingTerms = new ArrayList<>(synonymIdToTerm.values());
            leadingTerms.removeIf(java.util.Objects::isNull); // Remove any null values
        } catch (Exception e) {
            logger.warn("Failed to resolve synonym IDs {} to terms for NER pushdown. Executing without filter.", leadingSynonymIds, e);
            return executeWithRequirements(dependentQuery, indexes, QueryAttributeAnalyzer.analyze(dependentQuery), new SubqueryContext());
        }

        if (leadingTerms.isEmpty()) {
            logger.info("No valid terms found for synonym IDs {} from leading side. Executing dependent query without NER filter.", leadingSynonymIds);
            return executeWithRequirements(dependentQuery, indexes, QueryAttributeAnalyzer.analyze(dependentQuery), new SubqueryContext());
        }

        logger.debug("NER pushdown: Leading terms from '{}': {}", leadingAlias, leadingTerms);

        // Modify NER conditions in the dependent query to include the leading terms
        List<Condition> modifiedConditions = modifyConditionsForNerPushdown(dependentQuery.conditions(), dependentEntityKeyName, leadingTerms);

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
            dependentQuery.groupByColumns()
        );

        logger.debug("Executing modified dependent query for NER pushdown: alias '{}' (source '{}')", dependentAlias, modifiedDependentQuery.source());
        QueryResultSoA result = executeWithRequirements(modifiedDependentQuery, indexes, QueryAttributeAnalyzer.analyze(modifiedDependentQuery, parentRequirements), new SubqueryContext()); // Use parentRequirements
        resolveSynonymIdsInSoA(result, modifiedDependentQuery);
        logger.info("Modified dependent query for NER pushdown (alias '{}') executed. Found {} matches.", dependentAlias, result.size());
        return result;
    }

    /**
     * Modifies conditions to add NER pushdown filters.
     */
    private List<Condition> modifyConditionsForNerPushdown(List<Condition> originalConditions, String targetEntityKey, List<String> leadingTerms) {
        List<Condition> modifiedConditions = new ArrayList<>();
        boolean foundTargetNerCondition = false;

        for (Condition condition : originalConditions) {
            Condition modifiedCondition = modifyConditionForNerPushdown(condition, targetEntityKey, leadingTerms);
            if (modifiedCondition != condition) {
                foundTargetNerCondition = true;
            }
            modifiedConditions.add(modifiedCondition);
        }

        // If no existing NER condition was found to modify, we could add a new one, but this is complex
        // For now, just log and return the original conditions
        if (!foundTargetNerCondition) {
            logger.debug("No suitable NER condition found to modify for pushdown with key '{}'. Using original conditions.", targetEntityKey);
        }

        return modifiedConditions;
    }

    /**
     * Recursively modifies a single condition for NER pushdown.
     */
    private Condition modifyConditionForNerPushdown(Condition condition, String targetEntityKey, List<String> leadingTerms) {
        if (condition instanceof Ner nerCondition) {
            String qualifiedVarName = nerCondition.qualifiedVariableName();
            if (qualifiedVarName != null && qualifiedVarName.endsWith("." + extractColumnBaseName(targetEntityKey))) {
                // This is the NER condition we want to modify
                List<String> newTargets = new ArrayList<>(leadingTerms);
                logger.debug("Modifying NER condition for pushdown: original targets={}, new targets={}", nerCondition.targets(), newTargets);
                return new Ner(nerCondition.entityType(), newTargets, nerCondition.qualifiedVariableName(), nerCondition.isVariable());
            }
        } else if (condition instanceof Logical logical) {
            List<Condition> modifiedSubConditions = modifyConditionsForNerPushdown(logical.conditions(), targetEntityKey, leadingTerms);
            return new Logical(logical.operator(), modifiedSubConditions);
        } else if (condition instanceof Not not) {
            Condition modifiedSubCondition = modifyConditionForNerPushdown(not.condition(), targetEntityKey, leadingTerms);
            return new Not(modifiedSubCondition);
        }

        return condition; // Return unchanged if not applicable
    }
}