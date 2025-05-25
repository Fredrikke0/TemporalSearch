package com.example.query.executor;

import com.example.core.IndexAccessInterface;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Logical.LogicalOperator;
import com.example.query.model.JoinCondition;
import com.example.query.model.Query;
import com.example.query.model.SubquerySpec;
import com.example.query.binding.MatchDetail;
import com.example.query.result.ResultGenerationException;
import com.example.query.result.TableResultService;
import com.example.query.sqlite.SqliteAccessor;
import com.example.query.index.IndexManager;
import com.example.query.executor.JoinOptimizationStrategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Optional;
import java.time.LocalDate;
import java.time.LocalDateTime;

import tech.tablesaw.api.Table;

import com.example.query.model.JoinCondition.JoinOperatorType;
import com.example.query.model.JoinCondition.JoinType;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.condition.Temporal;

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
    private boolean nashInitialized = false;
    private JoinOptimizationStrategy joinStrategy = JoinOptimizationStrategy.INDEPENDENT;
    
    /**
     * Creates a new QueryExecutor with the provided executor factory.
     *
     * @param executorFactory Factory for creating condition executors
     */
    public QueryExecutor(ConditionExecutorFactory executorFactory) {
        this(executorFactory, new TableResultService());
    }

    /**
     * Constructor for testing purposes, allowing injection of mocks.
     *
     * @param executorFactory Factory for creating condition executors
     * @param tableResultService Mocked TableResultService
     */
    public QueryExecutor(ConditionExecutorFactory executorFactory, TableResultService tableResultService) {
        this.executorFactory = executorFactory;
        this.tableResultService = tableResultService;
    }
    
    /**
     * Initializes the Nash temporal index for a specific corpus.
     * This should be called before executing queries with temporal conditions.
     * 
     * @param corpusName The corpus/source name to initialize Nash index for
     * @param indexManager The index manager for accessing indexes
     */
    public void initializeNashIndex(String corpusName, com.example.query.index.IndexManager indexManager) {
        if (nashInitialized) {
            logger.debug("Nash index already initialized, skipping");
            return;
        }
        
        try {
            // Get the configured TemporalExecutor instance directly from the factory
            TemporalExecutor temporalExecutor = executorFactory.getTemporalExecutorInstance();
            
            // Let the TemporalExecutor handle the Nash initialization with the index manager
            boolean success = temporalExecutor.initializeForCorpus(corpusName, indexManager);
            if (success) {
                nashInitialized = true;
                logger.info("Nash index successfully initialized for corpus: {}", corpusName);
            } else {
                logger.warn("Failed to initialize Nash index for corpus: {}", corpusName);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize Nash index: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Executes a query against the provided indexes
     *
     * @param query The query to execute
     * @param indexes Map of index name to IndexAccessInterface
     * @return QueryResult or List<JoinedMatch> depending on query type
     * @throws QueryExecutionException if execution fails
     */
    public Object execute(Query query, Map<String, IndexAccessInterface> indexes) 
            throws QueryExecutionException {
        // Analyze query to determine attribute requirements for SoA optimization
        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);
        logger.debug("Query requires attributes: {}", requirements.getRequiredSoAAttributes());
        
        // Call executeWithContext with requirements
        return executeWithContext(query, indexes, new SubqueryContext(), requirements);
    }
    
    /**
     * Executes a query with an existing subquery context.
     * This allows for recursive execution of subqueries.
     *
     * @param query The query to execute
     * @param indexes Map of index name to IndexAccessInterface
     * @param subqueryContext Context containing results of previously executed subqueries
     * @return QueryResult or List<JoinedMatch> depending on query type
     * @throws QueryExecutionException if execution fails
     */
    public Object executeWithContext(Query query, Map<String, IndexAccessInterface> indexes, SubqueryContext subqueryContext) 
            throws QueryExecutionException {
        // Analyze query to determine attribute requirements for SoA optimization
        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);
        logger.debug("Query requires attributes: {}", requirements.getRequiredSoAAttributes());
        
        return executeWithContext(query, indexes, subqueryContext, requirements);
    }

    /**
     * Executes a query with an existing subquery context and attribute requirements.
     * This is the core execution method that supports SoA optimization.
     *
     * @param query The query to execute
     * @param indexes Map of index name to IndexAccessInterface
     * @param subqueryContext Context containing results of previously executed subqueries
     * @param requirements Attribute requirements for SoA optimization
     * @return QueryResult or List<JoinedMatch> depending on query type
     * @throws QueryExecutionException if execution fails
     */
    private Object executeWithContext(Query query, Map<String, IndexAccessInterface> indexes, 
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
        QueryResult mainConditionsResult = null;
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
            logger.debug("Main query conditions executed, {} details found.", mainConditionsResult.getAllDetails().size());
        } else {
            logger.debug("No main query conditions found.");
            mainConditionsResult = new QueryResult(granularity, granularitySize, Collections.emptyList());
        }
        // --- JOIN STRATEGY BRANCHING ---
        if (query.joinCondition().isPresent()) {
            JoinCondition joinCondition = query.joinCondition().get();
            String mainAlias = query.mainAlias().orElse("$main");
            subqueryContext.addQueryResult(mainAlias, mainConditionsResult);
            if (this.joinStrategy == JoinOptimizationStrategy.DEPENDENT && 
                joinCondition.type() == JoinCondition.JoinType.INNER &&
                joinCondition.operatorType() == JoinCondition.JoinOperatorType.TEMPORAL) {
                logger.info("Using DEPENDENT join strategy flow.");
                return executeDependentJoin(query, indexes, subqueryContext, mainAlias, mainConditionsResult, requirements);
            } else {
                logger.info("Using INDEPENDENT join strategy flow.");
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
            
            QueryResult subqueryResults = (QueryResult) executeWithContext(subquery.subquery(), indexes, subqueryContext);
            
            subqueryContext.addQueryResult(subquery, subqueryResults); 
            logger.debug("Subquery '{}' executed, stored QueryResult with {} details.", 
                    subquery.alias(), subqueryResults.getAllDetails().size());
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
     * @return Set of matches at the specified granularity level
     * @throws QueryExecutionException if execution fails
     */
    @SuppressWarnings("unchecked")
    private QueryResult executeCondition(
            Condition condition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String source,
            AttributeRequirements requirements) 
            throws QueryExecutionException {
        logger.debug("Executing condition: {} with granularity: {} and size: {}", 
                condition, granularity, granularitySize);
        
        try {
            ConditionExecutor executor = executorFactory.getExecutor(condition);
            
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
    Object executeIndependentJoin(Query query, Map<String, IndexAccessInterface> indexes, SubqueryContext subqueryContext, AttributeRequirements requirements) throws QueryExecutionException {
        logger.debug("Executing independent join for query: {}", query);
        // Ensure all subqueries defined in the query are executed and their results are in the context
        executeSubqueries(query.subqueries(), indexes, subqueryContext);
    
        // Now, perform the join using JoinHandler
        JoinHandler joinHandler = new JoinHandler();
        try {
            return joinHandler.handleJoin(query, subqueryContext);
        } catch (Exception e) { // Catch a broader exception if handleJoin throws something not QueryExecutionException
            logger.error("Error during independent join execution: {}", e.getMessage(), e);
            throw new QueryExecutionException("Failed to execute independent join", e, query.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }
    }

    /**
     * Stub for dependent join strategy. To be implemented.
     */
    Object executeDependentJoin(Query query, 
                                    Map<String, IndexAccessInterface> indexes, 
                                    SubqueryContext subqueryContext, 
                                    String mainAlias, 
                                    QueryResult mainConditionsResult,
                                    AttributeRequirements requirements) 
            throws QueryExecutionException {
        logger.info("Attempting DEPENDENT join strategy for query: {}", query.toString());
        JoinCondition joinCondition = query.joinCondition().orElseThrow(() -> 
            new QueryExecutionException("Dependent join called without a JoinCondition.", query.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR)
        );

        try {
            // 1. Identify Sides and Keys
            String leftColumn = joinCondition.leftColumn();
            String rightColumn = joinCondition.rightColumn();

            String leadingAlias;
            String dependentAlias;
            String leadingDateKeyName;
            String dependentDateKeyName;

            String leftColAlias = JoinHandler.extractAliasFromColumnName(leftColumn);
            // If the left column's alias in the JoinCondition matches the mainAlias, then mainConditionsResult is the leading side.
            if (leftColAlias.equals(mainAlias)) {
                leadingAlias = mainAlias; // mainConditionsResult is the leading side
                dependentAlias = JoinHandler.extractAliasFromColumnName(rightColumn);
                leadingDateKeyName = JoinHandler.extractKeyFromColumnName(leftColumn);
                dependentDateKeyName = JoinHandler.extractKeyFromColumnName(rightColumn);
            } else {
                // This means mainAlias was from the right side of the join condition
                leadingAlias = mainAlias; // mainConditionsResult is still the leading side (it's the one already executed)
                dependentAlias = JoinHandler.extractAliasFromColumnName(leftColumn); // dependent is the other one
                leadingDateKeyName = JoinHandler.extractKeyFromColumnName(rightColumn); // leading key from main
                dependentDateKeyName = JoinHandler.extractKeyFromColumnName(leftColumn); // dependent key from the other subquery
            }
            logger.debug("Dependent Join: Leading Alias='{}', Dependent Alias='{}', Leading Date Key='{}', Dependent Date Key='{}'", 
                         leadingAlias, dependentAlias, leadingDateKeyName, dependentDateKeyName);

            // 2. Extract Date Range from Leading Results (mainConditionsResult)
            List<LocalDate> leadingDates = new ArrayList<>();
            for (MatchDetail detail : mainConditionsResult.getAllDetails()) {
                Optional<Object> dateValOpt = JoinHandler.extractValueForKey(detail, leadingDateKeyName);
                if (dateValOpt.isPresent() && dateValOpt.get() instanceof LocalDate) {
                    leadingDates.add((LocalDate) dateValOpt.get());
                } else if (dateValOpt.isPresent()) {
                    logger.warn("Extracted value for key '{}' is not a LocalDate: {} for detail with doc ID {}", leadingDateKeyName, dateValOpt.get().getClass().getName(), detail.getDocumentId());
                }
            }

            if (leadingDates.isEmpty()) {
                logger.info("Leading side for dependent join resulted in no usable dates (or was empty). INNER JOIN will yield no results. Main alias: {}, Key: {}", leadingAlias, leadingDateKeyName);
                return Collections.emptyList(); // Empty list of JoinedMatch
            }

            LocalDate minLeadingDate = Collections.min(leadingDates);
            LocalDate maxLeadingDate = Collections.max(leadingDates);
            logger.debug("Leading date range for dependent filter: {} to {}", minLeadingDate, maxLeadingDate);

            // 3. Construct New Temporal Filter for Dependent Query
            TemporalPredicate originalPredicate = joinCondition.temporalPredicate().orElseThrow(() ->
                new QueryExecutionException("Temporal predicate missing in a temporal join for dependent strategy.", query.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR)
            );
            
            Temporal newFilterCondition;
            // LHS is leading (mainConditionsResult), RHS is dependent (subquery to be filtered)
            switch (originalPredicate) {
                case BEFORE: // LHS.date BEFORE RHS.date  =>  RHS.date AFTER minLeadingDate
                    newFilterCondition = new Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.empty(), Optional.empty(), Optional.empty(), TemporalPredicate.AFTER);
                    break;
                case AFTER:  // LHS.date AFTER RHS.date   =>  RHS.date BEFORE maxLeadingDate
                    newFilterCondition = new Temporal(Optional.of(maxLeadingDate.atStartOfDay()), Optional.empty(), Optional.empty(), Optional.empty(), TemporalPredicate.BEFORE);
                    break;
                case EQUAL:
                    // For EQUAL, treat as INTERSECT with the same start and end date for the dependent side.
                    newFilterCondition = new Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.of(maxLeadingDate.atStartOfDay()), Optional.empty(), Optional.empty(), TemporalPredicate.INTERSECT);
                    break;
                case INTERSECT: // LHS.date INTERSECT RHS.date_range (or date)
                    newFilterCondition = new Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.of(maxLeadingDate.atStartOfDay()), Optional.empty(), Optional.empty(), TemporalPredicate.INTERSECT);
                    break;
                // For CONTAINS/CONTAINED_BY, the design doc suggests INTERSECT with the leading side's overall min/max.
                case CONTAINS: // LHS.date_range CONTAINS RHS.date_range
                case CONTAINED_BY: // LHS.date_range CONTAINED_BY RHS.date_range
                     newFilterCondition = new Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.of(maxLeadingDate.atStartOfDay()), Optional.empty(), Optional.empty(), TemporalPredicate.INTERSECT);
                     logger.debug("Mapped original predicate {} to INTERSECT for dependent filter.", originalPredicate);
                     break;
                case BEFORE_EQUAL: // LHS.date <= RHS.date => RHS.date AFTER_EQUAL minLeadingDate
                    newFilterCondition = new Temporal(Optional.of(minLeadingDate.atStartOfDay()), Optional.empty(), Optional.empty(), Optional.empty(), TemporalPredicate.AFTER_EQUAL);
                    break;
                case AFTER_EQUAL: // LHS.date >= RHS.date => RHS.date BEFORE_EQUAL maxLeadingDate
                    newFilterCondition = new Temporal(Optional.of(maxLeadingDate.atStartOfDay()), Optional.empty(), Optional.empty(), Optional.empty(), TemporalPredicate.BEFORE_EQUAL);
                    break;
                case PROXIMITY: // Fallback for PROXIMITY as per design doc
                default:
                    logger.warn("Dependent join strategy does not support temporal predicate '{}' for precise pre-filtering. Falling back to independent join.", originalPredicate);
                    return executeIndependentJoin(query, indexes, subqueryContext, requirements);
            }
            logger.debug("Constructed new temporal filter for dependent subquery: {}", newFilterCondition);


            // 4. Modify and Execute Dependent Subquery
            SubquerySpec dependentSubquerySpec = query.subqueries().stream()
                .filter(sq -> sq.alias().equals(dependentAlias))
                .findFirst()
                .orElseThrow(() -> new QueryExecutionException("Dependent subquery spec not found for alias: " + dependentAlias, query.source(), QueryExecutionException.ErrorType.INTERNAL_ERROR));

            Query originalDependentQuery = dependentSubquerySpec.subquery();
            List<Condition> newConditions = new ArrayList<>(originalDependentQuery.conditions());
            boolean merged = false;

            String dependentBaseKey = JoinHandler.extractKeyFromColumnName(dependentAlias + "." + dependentDateKeyName);

            for (int i = 0; i < newConditions.size(); i++) {
                Condition currentCond = newConditions.get(i);

                if (currentCond instanceof Temporal existingTemporalCond) {
                    Optional<String> existingVarNameOpt = existingTemporalCond.qualifiedVariableName();
                    if (existingVarNameOpt.isPresent()) {
                        String existingQualifiedVar = existingVarNameOpt.get();
                        String existingBaseKey = existingQualifiedVar.contains(".")
                                               ? existingQualifiedVar.substring(existingQualifiedVar.indexOf('.') + 1)
                                               : existingQualifiedVar;

                        if (dependentBaseKey.equals(existingBaseKey)) {
                            logger.debug("Attempting to merge with top-level Temporal: existingBaseKey='{}', dependentBaseKey='{}'", existingBaseKey, dependentBaseKey);
                            logger.debug("Existing Temporal for merge: {}", existingTemporalCond.toString());
                            logger.debug("Filter Temporal for merge: {}", newFilterCondition.toString());
                            Optional<Temporal> mergedTemporalOpt = existingTemporalCond.intersectWith(newFilterCondition);
                            logger.debug("Top-level merge attempt result: {}", mergedTemporalOpt.isPresent() ? "Success (" + mergedTemporalOpt.get().toString() + ")" : "Failed or no merge");
                            if (mergedTemporalOpt.isPresent()) {
                                newConditions.set(i, mergedTemporalOpt.get());
                                merged = true;
                                break; 
                            }
                        }
                    }
                } else if (currentCond instanceof Logical logicalCond && logicalCond.operator() == Logical.LogicalOperator.AND) {
                    List<Condition> subConditions = new ArrayList<>(logicalCond.conditions()); // Modifiable copy
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

                                if (dependentBaseKey.equals(existingBaseKey)) {
                                    logger.debug("Attempting to merge with nested Temporal: existingBaseKey='{}', dependentBaseKey='{}'", existingBaseKey, dependentBaseKey);
                                    logger.debug("Nested Existing Temporal for merge: {}", existingSubTemporalCond.toString());
                                    logger.debug("Filter Temporal for merge: {}", newFilterCondition.toString());
                                    Optional<Temporal> mergedTemporalOpt = existingSubTemporalCond.intersectWith(newFilterCondition);
                                    logger.debug("Nested merge attempt result: {}", mergedTemporalOpt.isPresent() ? "Success (" + mergedTemporalOpt.get().toString() + ")" : "Failed or no merge");
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
                    if (subMerged) {
                        break; 
                    }
                }
            }

            if (!merged) {
                newConditions.add(newFilterCondition); // Add as a separate AND condition if no merge occurred
                logger.debug("Adding new temporal filter as a separate AND condition: {}", newFilterCondition);
            }

            Query modifiedDependentQuery = new Query(
                originalDependentQuery.source(),
                newConditions,
                originalDependentQuery.orderBy(),
                originalDependentQuery.limit(),
                originalDependentQuery.granularity(),
                originalDependentQuery.granularitySize(),
                originalDependentQuery.selectColumns(),
                originalDependentQuery.variableRegistry(), // Preserve original variable registry
                originalDependentQuery.subqueries(),
                originalDependentQuery.joinCondition(),
                originalDependentQuery.mainAlias(),
                List.of() // Explicitly add empty list for groupByColumns
            );
            
            QueryResult modifiedSubqueryResult;
            try {
                logger.debug("Executing modified dependent subquery for alias '{}': {}", dependentAlias, modifiedDependentQuery.toString());
                // Execute with a *new* SubqueryContext to avoid interference if this subquery itself has joins
                // that might try to use the main subqueryContext prematurely.
                Object result = this.executeWithContext(modifiedDependentQuery, indexes, new SubqueryContext());
                if (result instanceof QueryResult) {
                    modifiedSubqueryResult = (QueryResult) result;
                    logger.info("Modified dependent subquery for alias '{}' executed. Found {} details.", dependentAlias, modifiedSubqueryResult.getAllDetails().size());
                } else {
                     logger.error("Execution of modified dependent subquery for alias '{}' did not return a QueryResult. Got: {}. Falling back.", dependentAlias, result != null ? result.getClass().getName() : "null");
                     return executeIndependentJoin(query, indexes, subqueryContext, requirements); // Fallback
                }
            } catch (Exception e) {
                logger.error("Error executing modified dependent subquery for alias '{}'. Falling back. Error: {}", dependentAlias, e.getMessage(), e);
                return executeIndependentJoin(query, indexes, subqueryContext, requirements); // Fallback
            }

            // 5. Update Main Subquery Context with the filtered result for the dependent side
            subqueryContext.addQueryResult(dependentAlias, modifiedSubqueryResult);
            logger.debug("Updated main subquery context with filtered results for dependent alias '{}'.", dependentAlias);

            // 6. Execute Other Subqueries (if any)
            // Collect subqueries that are NOT the dependentAlias (which we just processed)
            // and also not the mainAlias (which was processed before this method).
            List<SubquerySpec> remainingSubquerySpecs = query.subqueries().stream()
                .filter(sq -> !sq.alias().equals(dependentAlias) && !sq.alias().equals(mainAlias))
                .collect(Collectors.toList());

            if (!remainingSubquerySpecs.isEmpty()) {
                logger.debug("Executing remaining {} subqueries after dependent filtering.", remainingSubquerySpecs.size());
                executeSubqueries(remainingSubquerySpecs, indexes, subqueryContext);
            } else {
                 logger.debug("No other subqueries to execute after dependent filtering.");
            }
            
            // 7. Final Join
            logger.debug("Proceeding to final join with JoinHandler using (potentially) filtered dependent results.");
            return new JoinHandler().handleJoin(query, subqueryContext);

        } catch (QueryExecutionException e) {
            logger.warn("QueryExecutionException during dependent join for query {}. Error: {}. Falling back to independent join.", query.toString(), e.getMessage(), e);
            // Ensure the original mainConditionsResult is in the context if we fall back
            subqueryContext.addQueryResult(mainAlias, mainConditionsResult); 
            return executeIndependentJoin(query, indexes, subqueryContext, requirements);
        } catch (Exception e) { // Catch broader exceptions to ensure fallback
            logger.error("Unexpected exception during dependent join for query {}. Error: {}. Falling back to independent join.", query.toString(), e.getMessage(), e);
            // Ensure the original mainConditionsResult is in the context if we fall back
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
} 