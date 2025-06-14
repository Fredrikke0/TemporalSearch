package com.example.query.executor;

import java.util.Map;
import java.util.Optional;

import com.example.core.IndexAccessInterface;
import com.example.query.model.Query;
import com.example.query.model.condition.Condition;

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
            TemporalExecutor,
            StitchedExecutor {



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
     * @param context Optional filtering context
     * @return QueryResultSoA representing the matches.
     * @throws QueryExecutionException if execution fails
     */
    QueryResultSoA execute(T condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements,
                               Optional<FilteringContext> context)
        throws QueryExecutionException;
}