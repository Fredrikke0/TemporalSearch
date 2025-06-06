package com.example.query.executor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
import com.example.query.index.IndexManager;
import com.example.query.model.JoinCondition;
import com.example.query.model.Query;
import com.example.query.model.SubquerySpec;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Logical.LogicalOperator;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Temporal;
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

    private final ConditionExecutorFactory executorFactory;
    private TableResultService tableResultService;
    private JoinOptimizationStrategy joinStrategy = JoinOptimizationStrategy.INDEPENDENT;
    private final String stitchStrategy;
    private Query currentQuery;
    private final SynonymManager synonymManager;

    /**
     * Creates a new QueryExecutor.
     *
     * @param executorFactory The factory to use for obtaining condition executors.
     * @param stitchStrategy The stitch execution strategy ("none" or "optimized")
     * @param synonymManager The SynonymManager instance for this query execution context.
     */
    public QueryExecutor(ConditionExecutorFactory executorFactory, String stitchStrategy, SynonymManager synonymManager) {
        this(executorFactory, new TableResultService(), stitchStrategy, synonymManager);
    }

    /**
     * Constructor for testing purposes, allowing injection of mocks and specific SynonymManager.
     *
     * @param executorFactory The factory to use for obtaining condition executors.
     * @param tableResultService Mocked TableResultService or actual instance.
     * @param stitchStrategy The stitch execution strategy ("none" or "optimized")
     * @param synonymManager The SynonymManager instance.
     */
    public QueryExecutor(ConditionExecutorFactory executorFactory, TableResultService tableResultService, String stitchStrategy, SynonymManager synonymManager) {
        this.executorFactory = executorFactory;
        this.synonymManager = synonymManager;
        this.tableResultService = tableResultService;
        this.stitchStrategy = (stitchStrategy == null || stitchStrategy.isBlank()) ? "none" : stitchStrategy;
        logger.debug("Initialized QueryExecutor with stitch strategy: {} and provided SynonymManager and ExecutorFactory", this.stitchStrategy);
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
        this.currentQuery = query;
        Map<String, IndexAccessInterface> indexes = indexManager.getAllIndexes();

        // Analyze query to determine attribute requirements for SoA optimization
        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);
        logger.info("=== Query Execution Started ===");
        logger.info("Query: {}", query);
        logger.info("SoA Attribute Requirements: {}", requirements.getRequiredSoAAttributes());
        logger.trace("Full requirements: {}", requirements);

        try {
            QueryResultSoA result = executeWithRequirements(query, indexes, requirements, new SubqueryContext());

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
        logger.debug("Executing query: {}", query);
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
        if (!mainConditions.isEmpty()) {
            logger.debug("Executing main query conditions...");
            List<Condition> orderedMainConditions = optimizeExecutionOrder(mainConditions);
            if (orderedMainConditions.size() == 1) {
                mainConditionsResult = executeCondition(orderedMainConditions.get(0), indexes, granularity, granularitySize, source, requirements);
            } else {
                Logical implicitAnd = new Logical(LogicalOperator.AND, orderedMainConditions);
                mainConditionsResult = executeCondition(implicitAnd, indexes, granularity, granularitySize, source, requirements);
            }
            logger.debug("Main query conditions executed, {} matches found.", mainConditionsResult.size());
        } else {
            logger.debug("No main query conditions found.");
            mainConditionsResult = new QueryResultSoA(granularity, granularitySize, requirements);
        }
        // --- JOIN STRATEGY BRANCHING ---
        if (query.joinCondition().isPresent()) {
            JoinCondition joinCondition = query.joinCondition().get();
            String mainAlias = query.mainAlias().orElse("$main");
            subqueryContext.addQueryResult(mainAlias, mainConditionsResult);
            if (this.joinStrategy == JoinOptimizationStrategy.DEPENDENT &&
                joinCondition.type() == JoinCondition.JoinType.INNER &&
                (joinCondition.operatorType() == JoinCondition.JoinOperatorType.TEMPORAL)) {
                logger.info("Using DEPENDENT join strategy flow for query: {}", query.toString());
                return executeDependentJoin(query, indexes, subqueryContext, mainAlias, mainConditionsResult, requirements);
            } else {
                logger.info("Using INDEPENDENT join strategy flow for query: {}", query.toString());
                return executeIndependentJoin(query, indexes, subqueryContext, requirements);
            }
        } else {
            // No JOIN
            return mainConditionsResult;
        }
    }

    /**
     * Executes all subqueries and adds their results to the subquery context.
     */
    private void executeSubqueries(List<SubquerySpec> subqueries, Map<String, IndexAccessInterface> indexes, SubqueryContext subqueryContext)
            throws QueryExecutionException {
        for (SubquerySpec subquery : subqueries) {
            if (subqueryContext.hasResults(subquery.alias())) {
                logger.debug("Subquery with alias '{}' already executed, skipping", subquery.alias());
                continue;
            }

            logger.debug("Executing subquery: {}", subquery);

            QueryResultSoA subqueryResults = executeWithContext(subquery.subquery(), indexes, subqueryContext);

            subqueryContext.addQueryResult(subquery, subqueryResults);
            logger.debug("Subquery '{}' executed, stored QueryResultSoA with {} matches.",
                    subquery.alias(), subqueryResults.size());
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
     * @return QueryResultSoA containing matches at the specified granularity level
     * @throws QueryExecutionException if execution fails
     */
    @SuppressWarnings("unchecked")
    private QueryResultSoA executeCondition(
            Condition condition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String source,
            AttributeRequirements requirements)
            throws QueryExecutionException {
        logger.debug("Executing condition: {} with granularity: {} and size: {}",
                condition, granularity, granularitySize);

        // --- STITCH STRATEGY LOGIC --- START ---
        if (this.stitchStrategy.equals("optimized") &&
            this.currentQuery != null && // Ensure currentQuery is set
            this.currentQuery.granularity() == Query.Granularity.SENTENCE &&
            condition instanceof Logical logicalCondition &&
            logicalCondition.operator() == Logical.LogicalOperator.AND) {

            List<Condition> childConditions = logicalCondition.conditions();
            if (childConditions.size() == 2) {
                Condition child1 = childConditions.get(0);
                Condition child2 = childConditions.get(1);

                Contains containsCond = null;
                Condition annotationCond = null;

                if (child1 instanceof Contains && ((Contains) child1).terms().size() == 1) {
                    containsCond = (Contains) child1;
                    if (child2 instanceof Ner || child2 instanceof com.example.query.model.condition.Pos || child2 instanceof com.example.query.model.condition.Temporal) {
                        annotationCond = child2;
                    }
                } else if (child2 instanceof Contains && ((Contains) child2).terms().size() == 1) {
                    containsCond = (Contains) child2;
                    if (child1 instanceof Ner || child1 instanceof com.example.query.model.condition.Pos || child1 instanceof com.example.query.model.condition.Temporal) {
                        annotationCond = child1;
                    }
                }

                if (containsCond != null && annotationCond != null) {
                    logger.debug("Attempting stitch optimization for CONTAINS ({}) AND {} ({})",
                                 containsCond.terms(), annotationCond.getType(), annotationCond);
                    StitchIntersectionExecutor stitchExecutor = new StitchIntersectionExecutor();
                    try {
                        QueryResultSoA stitchResult = stitchExecutor.execute(
                            containsCond,
                            annotationCond,
                            indexes,
                            this.synonymManager,
                            granularity,
                            granularitySize,
                            source,
                            requirements,
                            this.currentQuery
                        );
                        if (stitchResult != null) {
                            logger.info("Stitch optimization successful for CONTAINS + {}. Result count: {}",
                                        containsCond.terms(), stitchResult.size());
                            return stitchResult;
                        } else {
                            logger.warn("Stitch execution did not complete or apply for CONTAINS + {}. Falling back to standard AND execution.", annotationCond.getType());
                        }
                    } catch (QueryExecutionException e) {
                        logger.warn("StitchIntersectionExecutor execution failed: {}. Falling back to standard AND execution.", e.getMessage());
                        // Fall through to standard execution
                    } catch (Exception e) {
                        logger.error("Unexpected error during stitch execution: {}. Falling back to standard AND execution.", e.getMessage(), e);
                        // Fall through for unexpected errors too
                    }
                }
            }
        }
        // --- STITCH STRATEGY LOGIC --- END ---

        try {
            ConditionExecutor<Condition> executor = executorFactory.getExecutor(condition);

            // Executors now directly return QueryResultSoA
            return executor.execute(condition, indexes, granularity, granularitySize, source, requirements);

        } catch (QueryExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new QueryExecutionException(
                "Error executing condition: " + e.getMessage(),
                e,
                condition.toString(),
                QueryExecutionException.ErrorType.INTERNAL_ERROR
            );
        }
    }

    /**
     * Sets the join optimization strategy for this executor.
     * @param strategy The join optimization strategy to use
     */
    public void setJoinOptimizationStrategy(JoinOptimizationStrategy strategy) {
        this.joinStrategy = (strategy != null) ? strategy : JoinOptimizationStrategy.INDEPENDENT;
    }

    /**
     * Executes the current (independent) join logic.
     */
    QueryResultSoA executeIndependentJoin(Query query, Map<String, IndexAccessInterface> indexes, SubqueryContext subqueryContext, AttributeRequirements requirements) throws QueryExecutionException {
        logger.debug("Executing independent join...");
        JoinCondition joinCondition = query.joinCondition().orElseThrow(() ->
                new QueryExecutionException("Join condition missing for independent join strategy",
                                            query.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR));

        // Ensure all subqueries involved in the join are executed
        List<SubquerySpec> allSubqueries = new ArrayList<>(query.subqueries());
        // Add subqueries specified directly in join condition if not already in the main list (e.g. FROM subqueryA JOIN subqueryB)
        // This part might be redundant if Query model ensures all involved subqueries are in query.subqueries()
        // For now, let's assume query.subqueries() is comprehensive or executeSubqueries handles all necessary ones.

        executeSubqueries(allSubqueries, indexes, subqueryContext); // Make sure all defined subqueries are processed

        // Now, perform the join using the JoinHandler, which expects QueryResultSoA from the context
        JoinHandler joinHandler = new JoinHandler();
        AttributeRequirements joinOutputRequirements = new AttributeRequirements(); // JoinHandler will derive its own output reqs
        joinOutputRequirements.merge(subqueryContext.getQueryResult(JoinHandler.extractAliasFromColumnName(joinCondition.leftColumn())).getRequirements());
        joinOutputRequirements.merge(subqueryContext.getQueryResult(JoinHandler.extractAliasFromColumnName(joinCondition.rightColumn())).getRequirements());
        joinOutputRequirements.needsConceptualRowIds = true;

        // The JoinHandler now takes care of creating the final QueryResultSoA
        return joinHandler.handleJoin(query, subqueryContext);
    }

    /**
     * Executes a join where the right-hand side subquery depends on the results of the left-hand side (main query or another subquery).
     * This is typical for certain temporal joins or correlated subqueries, though the latter is not fully supported.
     *
     * @param query The main query.
     * @param indexes Available indexes.
     * @param subqueryContext Context for subquery results.
     * @param mainAlias Alias for the main query part (LHS of the join).
     * @param mainConditionsResult Results of the main query conditions.
     * @param requirements Attribute requirements for the main query.
     * @return QueryResultSoA from the join operation.
     * @throws QueryExecutionException If an error occurs.
     */
    QueryResultSoA executeDependentJoin(Query query,
                                    Map<String, IndexAccessInterface> indexes,
                                    SubqueryContext subqueryContext,
                                    String mainAlias,
                                    QueryResultSoA mainConditionsResult, // Already executed main part
                                    AttributeRequirements requirements)
            throws QueryExecutionException {
        logger.debug("Executing dependent join strategy for mainAlias: {}, query: {}", mainAlias, query.toString());
        JoinCondition joinCondition = query.joinCondition().orElseThrow(() ->
            new QueryExecutionException("Join condition missing for dependent join strategy",
                                        query.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR));

        // 1. Identify Sides and Keys
        String leftColumn = joinCondition.leftColumn();
        String rightColumn = joinCondition.rightColumn();

        String leadingAlias;
        String dependentAlias;
        String leadingDateKeyName;
        String dependentDateKeyName;

        String leftColAlias = JoinHandler.extractAliasFromColumnName(leftColumn);
        String rightColAlias = JoinHandler.extractAliasFromColumnName(rightColumn);

        if (leftColAlias.equals(mainAlias) || (leftColAlias.isEmpty() && JoinHandler.extractKeyFromColumnName(leftColumn).equals(mainAlias))) {
            leadingAlias = mainAlias;
            dependentAlias = rightColAlias;
            if (dependentAlias.isEmpty()) { // If rightColAlias is empty, rightColumn might be the alias itself
                dependentAlias = JoinHandler.extractKeyFromColumnName(rightColumn);
            }
            leadingDateKeyName = JoinHandler.extractKeyFromColumnName(leftColumn);
            dependentDateKeyName = JoinHandler.extractKeyFromColumnName(rightColumn);
        } else if (rightColAlias.equals(mainAlias) || (rightColAlias.isEmpty() && JoinHandler.extractKeyFromColumnName(rightColumn).equals(mainAlias))) {
            leadingAlias = mainAlias;
            dependentAlias = leftColAlias;
            if (dependentAlias.isEmpty()) { // If leftColAlias is empty, leftColumn might be the alias itself
                 dependentAlias = JoinHandler.extractKeyFromColumnName(leftColumn);
            }
            leadingDateKeyName = JoinHandler.extractKeyFromColumnName(rightColumn);
            dependentDateKeyName = JoinHandler.extractKeyFromColumnName(leftColumn);
        } else {
            logger.error("Could not determine leading/dependent side for dependent join. mainAlias: '{}', left: '{}', right: '{}'. Falling back.",
                        mainAlias, leftColumn, rightColumn);
            return executeIndependentJoin(query, indexes, subqueryContext, requirements);
        }

        // Ensure dependentAlias is a valid subquery alias if it was derived from a key
        final String finalDependentAlias = dependentAlias;
        if (query.subqueries().stream().noneMatch(sq -> sq.alias().equals(finalDependentAlias))) {
             logger.warn("Resolved dependentAlias '{}' does not match any known subquery alias. Attempting to find by key '{}' as alias. Join might fail or be incorrect.", finalDependentAlias, dependentAlias );
             // This logic might need to be more robust if dependentAlias can be something other than a subquery alias directly.
        }

        logger.debug("Dependent Join: Leading Alias='{}', Dependent Alias='{}', Leading Date Key='{}', Dependent Date Key='{}'",
            leadingAlias, finalDependentAlias, leadingDateKeyName, dependentDateKeyName);

        try {
            // 2. Extract Date Range from Leading Results (mainConditionsResult)
            Set<LocalDate> leadingDatesSet = new HashSet<>();
            if (mainConditionsResult != null && mainConditionsResult.size() > 0) {
                for (int i = 0; i < mainConditionsResult.size(); i++) {
                    String varName = mainConditionsResult.getVariableNameAt(i);
                    boolean nameMatch = (varName != null &&
                                         (varName.equals(leadingDateKeyName) ||
                                          (leadingDateKeyName.startsWith("$") && varName.equals(leadingDateKeyName.substring(1))) ||
                                          (!leadingDateKeyName.startsWith("$") && varName.equals("$" + leadingDateKeyName))
                                         )
                                        );
                    if (nameMatch) {
                        Object value = mainConditionsResult.getValueAt(i);
                        if (value instanceof LocalDate) {
                            leadingDatesSet.add((LocalDate) value);
                        }
                    } else if (SoAJoinOptimizer.isStructuralDateKey(leadingDateKeyName)) {
                         if (mainConditionsResult.getValueTypeAt(i) == com.example.query.binding.ValueType.DATE) {
                             Object value = mainConditionsResult.getValueAt(i);
                             if (value instanceof LocalDate) {
                                 leadingDatesSet.add((LocalDate) value);
                             }
                         }
                    }
                }
            }
            List<LocalDate> leadingDates = new ArrayList<>(leadingDatesSet);

            if (leadingDates.isEmpty()) {
                logger.info("Leading side for dependent join (alias: '{}', key: '{}') resulted in no usable dates (or was empty). INNER JOIN will yield no results.",
                            leadingAlias, leadingDateKeyName);
                return new QueryResultSoA(query.granularity(), query.granularitySize().orElse(0), AttributeRequirements.forJoinOperations());
            }

            LocalDate minLeadingDate = Collections.min(leadingDates);
            LocalDate maxLeadingDate = Collections.max(leadingDates);
            logger.debug("Leading date range for dependent filter: {} to {}", minLeadingDate, maxLeadingDate);

            // 3. Construct New Temporal Filter for Dependent Query
            TemporalPredicate originalPredicate = joinCondition.temporalPredicate().orElseThrow(() ->
                new QueryExecutionException("Temporal predicate missing in a temporal join for dependent strategy.", query.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR)
            );

            Temporal newFilterCondition;
            // The qualifiedVariableName for the new filter condition should be the dependentDateKeyName
            // It might need to be prefixed with '$' if not already, or handled by Temporal constructor/logic.
            // For now, passing it as is, assuming Temporal or downstream logic handles qualification if needed.
            String qVNDependent = dependentDateKeyName.startsWith("$") ? dependentDateKeyName : "$" + dependentDateKeyName;

            switch (originalPredicate) {
                case BEFORE:
                    newFilterCondition = new Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.empty(), Optional.of(qVNDependent), Optional.empty(), TemporalPredicate.AFTER);
                    break;
                case AFTER:
                    newFilterCondition = new Temporal(Optional.of(maxLeadingDate.atStartOfDay()), Optional.empty(), Optional.of(qVNDependent), Optional.empty(), TemporalPredicate.BEFORE);
                    break;
                case EQUAL:
                    newFilterCondition = new Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.of(maxLeadingDate.atStartOfDay()), Optional.of(qVNDependent), Optional.empty(), TemporalPredicate.INTERSECT);
                    break;
                case INTERSECT:
                    newFilterCondition = new Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.of(maxLeadingDate.atStartOfDay()), Optional.of(qVNDependent), Optional.empty(), TemporalPredicate.INTERSECT);
                    break;
                case CONTAINS:
                case CONTAINED_BY:
                     newFilterCondition = new Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.of(maxLeadingDate.atStartOfDay()), Optional.of(qVNDependent), Optional.empty(), TemporalPredicate.INTERSECT);
                     logger.debug("Mapped original predicate {} to INTERSECT for dependent filter.", originalPredicate);
                     break;
                case BEFORE_EQUAL:
                    newFilterCondition = new Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.empty(), Optional.of(qVNDependent), Optional.empty(), TemporalPredicate.AFTER_EQUAL);
                    break;
                case AFTER_EQUAL:
                    newFilterCondition = new Temporal(Optional.of(maxLeadingDate.atStartOfDay()), Optional.empty(), Optional.of(qVNDependent), Optional.empty(), TemporalPredicate.BEFORE_EQUAL);
                    break;
                case PROXIMITY:
                default:
                    logger.warn("Dependent join strategy does not support temporal predicate '{}' for precise pre-filtering. Falling back to independent join.", originalPredicate);
                    return executeIndependentJoin(query, indexes, subqueryContext, requirements);
            }
            logger.debug("Constructed new temporal filter for dependent subquery (key: '{}'): {}", dependentDateKeyName, newFilterCondition);

            // 4. Modify and Execute Dependent Subquery
            SubquerySpec dependentSubquerySpec = query.subqueries().stream()
                .filter(sq -> sq.alias().equals(finalDependentAlias))
                .findFirst()
                .orElseThrow(() -> new QueryExecutionException("Dependent subquery spec not found for alias: " + finalDependentAlias, query.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR));

            Query originalDependentQuery = dependentSubquerySpec.subquery();
            List<Condition> newConditions = new ArrayList<>(originalDependentQuery.conditions());
            boolean merged = false;

            // The dependentBaseKey should be just the variable name, without alias, for matching inside the subquery conditions.
            String dependentBaseKeyForFilter = dependentDateKeyName.startsWith("$") ? dependentDateKeyName : "$" + dependentDateKeyName;

            for (int i = 0; i < newConditions.size(); i++) {
                Condition currentCond = newConditions.get(i);
                if (currentCond instanceof Temporal existingTemporalCond) {
                    Optional<String> existingVarNameOpt = existingTemporalCond.qualifiedVariableName();
                    if (existingVarNameOpt.isPresent()) {
                        String existingQualifiedVar = existingVarNameOpt.get();
                        // Extract base variable name (e.g., $date from subAlias.$date)
                        String existingBaseKey = existingQualifiedVar.contains(".")
                                               ? existingQualifiedVar.substring(existingQualifiedVar.indexOf('.') + 1)
                                               : existingQualifiedVar;
                        if (!existingBaseKey.startsWith("$")) existingBaseKey = "$" + existingBaseKey;

                        if (dependentBaseKeyForFilter.equals(existingBaseKey)) {
                            Optional<Temporal> mergedTemporalOpt = existingTemporalCond.intersectWith(newFilterCondition);
                            if (mergedTemporalOpt.isPresent()) {
                                newConditions.set(i, mergedTemporalOpt.get());
                                merged = true;
                                break;
                            }
                        }
                    }
                } else if (currentCond instanceof Logical logicalCond && logicalCond.operator() == Logical.LogicalOperator.AND) {
                    List<Condition> subConditions = new ArrayList<>(logicalCond.conditions());
                    boolean subMerged = false;
                    for (int j = 0; j < subConditions.size(); j++) {
                        Condition subCond = subConditions.get(j);
                        if (subCond instanceof Temporal existingSubTemporalCond) {
                            Optional<String> existingVarNameOpt = existingSubTemporalCond.qualifiedVariableName();
                            if (existingVarNameOpt.isPresent()) {
                                String existingQualifiedVar = existingVarNameOpt.get();
                                String existingBaseKey = existingQualifiedVar.contains(".")
                                                       ? existingQualifiedVar.substring(existingQualifiedVar.indexOf('.') + 1)
                                                       : existingQualifiedVar;
                                if (!existingBaseKey.startsWith("$")) existingBaseKey = "$" + existingBaseKey;

                                if (dependentBaseKeyForFilter.equals(existingBaseKey)) {
                                    Optional<Temporal> mergedTemporalOpt = existingSubTemporalCond.intersectWith(newFilterCondition);
                                    if (mergedTemporalOpt.isPresent()) {
                                        subConditions.set(j, mergedTemporalOpt.get());
                                        newConditions.set(i, new Logical(Logical.LogicalOperator.AND, subConditions));
                                        merged = true;
                                        subMerged = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    if (subMerged) break;
                }
            }

            if (!merged) {
                newConditions.add(newFilterCondition);
            }

            Query modifiedDependentQuery = new Query(
                originalDependentQuery.source(), newConditions, originalDependentQuery.orderBy(),
                originalDependentQuery.limit(), originalDependentQuery.granularity(), originalDependentQuery.granularitySize(),
                originalDependentQuery.selectColumns(), originalDependentQuery.variableRegistry(),
                originalDependentQuery.subqueries(), originalDependentQuery.joinCondition(),
                originalDependentQuery.mainAlias(), originalDependentQuery.groupByColumns()
            );

            QueryResultSoA modifiedSubqueryResult;
            logger.debug("Executing modified dependent subquery for alias '{}': {}", finalDependentAlias, modifiedDependentQuery.toString());
            modifiedSubqueryResult = this.executeWithContext(modifiedDependentQuery, indexes, new SubqueryContext()); // Execute with a new context
            logger.info("Modified dependent subquery for alias '{}' executed. Found {} matches.", finalDependentAlias, modifiedSubqueryResult.size());

            subqueryContext.addQueryResult(finalDependentAlias, modifiedSubqueryResult);
            logger.debug("Updated main subquery context with filtered results for dependent alias '{}'.", finalDependentAlias);

            List<SubquerySpec> remainingSubquerySpecs = query.subqueries().stream()
                .filter(sq -> !sq.alias().equals(finalDependentAlias) && !sq.alias().equals(mainAlias))
                .collect(Collectors.toList());

            if (!remainingSubquerySpecs.isEmpty()) {
                executeSubqueries(remainingSubquerySpecs, indexes, subqueryContext);
            }

            return new JoinHandler().handleJoin(query, subqueryContext);

        } catch (QueryExecutionException qe) {
            logger.warn("QueryExecutionException during dependent join for query {}. Error: {}. Falling back to independent join.", query.toString(), qe.getMessage(), qe);
            subqueryContext.addQueryResult(mainAlias, mainConditionsResult);
            return executeIndependentJoin(query, indexes, subqueryContext, requirements);
        } catch (Exception e) {
            logger.error("Unexpected exception during dependent join for query {}. Error: {}. Falling back to independent join.", query.toString(), e.getMessage(), e);
            subqueryContext.addQueryResult(mainAlias, mainConditionsResult);
            return executeIndependentJoin(query, indexes, subqueryContext, requirements);
        }
    }

    /**
     * Extracts document IDs from a QueryResult.
     * TODO: Implement this if needed.
     *
     * @param result The QueryResult to process
     * @return Set of document IDs
     */
    // public Set<Integer> getDocumentIds(QueryResult result) { ... }

    private QueryResultSoA executeWithRequirements(Query query, Map<String, IndexAccessInterface> indexes,
                                        AttributeRequirements requirements, SubqueryContext subqueryContext)
            throws QueryExecutionException {

        logger.trace("Executing query with requirements: Query='{}', Requirements='{}'", query.toString(), requirements);

        // Use existing executeWithContext method but with enhanced logging
        long executionStart = System.nanoTime();

        try {
            QueryResultSoA result = executeWithContext(query, indexes, subqueryContext, requirements);

            long executionTime = System.nanoTime() - executionStart;
            logger.debug("Query execution with SoA optimization completed in {} ms",
                        executionTime / 1_000_000.0);

            return result;

        } catch (QueryExecutionException e) {
            long executionTime = System.nanoTime() - executionStart;
            logger.warn("Query execution with SoA optimization failed after {} ms: {}",
                       executionTime / 1_000_000.0, e.getMessage());
            throw e;
        }
    }
}