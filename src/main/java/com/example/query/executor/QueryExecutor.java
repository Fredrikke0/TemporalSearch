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

import tech.tablesaw.api.Table;

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
        // Call executeWithContext, which now returns QueryResult or List<JoinedMatch>
        return executeWithContext(query, indexes, new SubqueryContext());
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
        logger.debug("Executing query: {}", query);
        
        Query.Granularity granularity = query.granularity();
        int granularitySize = query.granularitySize().orElse(0);
        
        if (granularitySize < 0 || granularitySize > 10) {
            throw new IllegalArgumentException("Granularity size must be between 0 and 10, got: " + granularitySize);
        }
        
        String source = query.source();
        logger.debug("Using source: {}", source);

        // Execute subqueries first and store results in context
        if (query.hasSubqueries()) {
            logger.debug("Executing {} subqueries", query.subqueries().size());
            executeSubqueries(query.subqueries(), indexes, subqueryContext);
        }

        // Always execute main conditions first if they exist.
        QueryResult mainConditionsResult = null;
        List<Condition> mainConditions = query.conditions();
        if (!mainConditions.isEmpty()) {
            logger.debug("Executing main query conditions...");
            List<Condition> orderedMainConditions = optimizeExecutionOrder(mainConditions);
            if (orderedMainConditions.size() == 1) {
                mainConditionsResult = executeCondition(orderedMainConditions.get(0), indexes, granularity, granularitySize, source);
            } else {
                Logical implicitAnd = new Logical(LogicalOperator.AND, orderedMainConditions);
                mainConditionsResult = executeCondition(implicitAnd, indexes, granularity, granularitySize, source);
            }
            logger.debug("Main query conditions executed, {} details found.", mainConditionsResult.getAllDetails().size());
        } else {
            logger.debug("No main query conditions found.");
            // If no main conditions, create an empty result. Join logic might need to handle this (e.g., cross join?).
             mainConditionsResult = new QueryResult(granularity, granularitySize, Collections.emptyList());
        }

        // Handle JOIN if present
        if (query.joinCondition().isPresent()) {
            // If there's a join, store the result of the main conditions under the main query alias
            String mainAlias = query.mainAlias().orElse("$main"); // Use "$main" if no explicit AS for FROM
            logger.debug("Storing main condition results under alias '{}' for join.", mainAlias);
            subqueryContext.addQueryResult(mainAlias, mainConditionsResult);
            
            logger.debug("Delegating JOIN execution to JoinHandler.");
            JoinHandler joinHandler = new JoinHandler(); 
            List<com.example.query.binding.JoinedMatch> joinResults = joinHandler.handleJoin(query, subqueryContext); 
            logger.debug("Join completed. Returning List<JoinedMatch> with {} pairs.", joinResults.size());
            // Return as Object for now; downstream consumers must handle List<JoinedMatch> for join queries
            return joinResults;
        } else {
            // If no JOIN, return the result from executing the main conditions directly.
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
     * @return Set of matches at the specified granularity level
     * @throws QueryExecutionException if execution fails
     */
    @SuppressWarnings("unchecked")
    private QueryResult executeCondition(
            Condition condition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String source) 
            throws QueryExecutionException {
        logger.debug("Executing condition: {} with granularity: {} and size: {}", 
                condition, granularity, granularitySize);
        
        try {
            ConditionExecutor executor = executorFactory.getExecutor(condition);
            
            return executor.execute(condition, indexes, granularity, granularitySize, source);
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
     * Extracts document IDs from a QueryResult.
     * TODO: Implement this if needed.
     *
     * @param result The QueryResult to process
     * @return Set of document IDs
     */
    // public Set<Integer> getDocumentIds(QueryResult result) { ... }
} 