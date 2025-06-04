package com.example.query.executor;

import java.util.Map;

import com.example.core.IndexAccessInterface;
import com.example.query.model.Query;
import com.example.query.model.condition.Temporal;

/**
 * Interface for different strategies to execute temporal conditions.
 */
public interface TemporalExecutionStrategy {

    /**
     * Gets the unique name of this strategy (e.g., "nash", "naive").
     * @return The strategy name.
     */
    String getName();

    /**
     * Executes the temporal condition using this strategy.
     *
     * @param condition The temporal condition to execute.
     * @param indexes Map of available indexes.
     * @param granularity The desired granularity (DOCUMENT or SENTENCE).
     * @param granularitySize The window size for sentence granularity.
     * @param corpusName The name of the corpus being queried.
     * @param temporalExecutor The parent TemporalExecutor (for context).
     * @param requirements The attribute requirements for the query result.
     * @return A QueryResultSoA containing the matches.
     * @throws QueryExecutionException If an error occurs during execution.
     */
    QueryResultSoA execute(
        Temporal condition,
        Map<String, IndexAccessInterface> indexes,
        Query.Granularity granularity,
        int granularitySize,
        String corpusName,
        TemporalExecutor temporalExecutor,
        AttributeRequirements requirements)
        throws QueryExecutionException;
}