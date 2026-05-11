package com.example.query.executor;

import java.util.Map;
import java.util.Optional;

import org.roaringbitmap.longlong.Roaring64NavigableMap;

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
     * Executes a specific condition type.
     *
     * @param condition       the condition to execute
     * @param indexes         map of index name to IndexAccessInterface
     * @param granularity     whether to return document or sentence level matches
     * @param granularitySize window size for sentence granularity
     * @param corpusName      the corpus being queried
     * @param requirements    which attributes are needed
     * @param allowedCells    optional set of allowed cell keys for filtering; empty
     *                        means unrestricted
     * @return CellResult representing the matches
     * @throws QueryExecutionException if execution fails
     */
    CellResult execute(T condition, Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Optional<Roaring64NavigableMap> allowedCells)
            throws QueryExecutionException;

    /**
     * Convenience overload without attribute requirements (uses defaults).
     */
    default CellResult execute(T condition, Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            Optional<Roaring64NavigableMap> allowedCells)
            throws QueryExecutionException {
        AttributeRequirements reqs = new AttributeRequirements();
        if (granularity == Query.Granularity.SENTENCE) {
            reqs.needsSentenceId = true;
        }
        return execute(condition, indexes, granularity, granularitySize, corpusName, reqs, allowedCells);
    }
}
