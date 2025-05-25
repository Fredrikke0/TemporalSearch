package com.example.query.executor;

import com.example.core.IndexAccessInterface;
import com.example.query.model.Query;
import com.example.query.model.condition.Condition;
import com.example.query.executor.QueryResult;

import java.util.Map;

/**
 * Interface for executing conditions against indexes.
 * Each condition type has a corresponding executor implementation.
 *
 * @param <T> The specific condition type this executor handles
 * @see com.example.query.model.condition.Condition
 */
public sealed interface ConditionExecutor<T extends Condition> 
    permits ContainsExecutor,
            DependencyExecutor,
            LogicalExecutor,
            NerExecutor,
            PosExecutor,
            NotExecutor,
            TemporalExecutor {
    
    /**
     * Executes a specific condition type against the appropriate indexes with a specified granularity window size
     *
     * @param condition The condition to execute
     * @param indexes Map of index name to IndexAccessInterface
     * @param granularity Whether to return document or sentence level matches
     * @param granularitySize Window size for sentence granularity (0 = same sentence only, 1 = adjacent sentences, etc.)
     * @param corpusName The name of the corpus being queried
     * @return QueryResult containing MatchDetail objects representing the matches.
     * @throws QueryExecutionException if execution fails
     */
    QueryResult execute(T condition, Map<String, IndexAccessInterface> indexes,
                         Query.Granularity granularity,
                         int granularitySize,
                         String corpusName)
        throws QueryExecutionException;

    /**
     * Executes a specific condition type with SoA optimization based on attribute requirements.
     * This method enables selective deserialization to improve performance and reduce memory usage.
     *
     * @param condition The condition to execute
     * @param indexes Map of index name to IndexAccessInterface
     * @param granularity Whether to return document or sentence level matches
     * @param granularitySize Window size for sentence granularity (0 = same sentence only, 1 = adjacent sentences, etc.)
     * @param corpusName The name of the corpus being queried
     * @param requirements Specifies which SoA attributes are needed for this execution
     * @return QueryResult containing MatchDetail objects representing the matches.
     * @throws QueryExecutionException if execution fails
     */
    default QueryResult execute(T condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements)
        throws QueryExecutionException {
        // Default implementation falls back to the original method for backward compatibility
        // Individual executors can override this to implement SoA optimizations
        return execute(condition, indexes, granularity, granularitySize, corpusName);
    }
} 