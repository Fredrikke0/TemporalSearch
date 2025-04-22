package com.example.query.executor;

import com.example.core.IndexAccessInterface;
import com.example.query.binding.MatchDetail;
import com.example.query.model.Query;
import com.example.query.model.condition.Temporal;
import java.util.List;
import java.util.Map;

/**
 * Interface for different strategies to execute temporal conditions.
 */
interface TemporalExecutionStrategy {

    /**
     * Gets the unique name of this strategy (e.g., "nash", "naive").
     * @return The strategy name.
     */
    String getName();

    /**
     * Performs any necessary initialization for this strategy for a specific corpus.
     * @param corpusName The name of the corpus being initialized.
     * @param temporalExecutor The parent TemporalExecutor instance (provides access to shared resources like Nash index).
     * @return true if initialization was successful, false otherwise.
     */
    boolean initializeForCorpus(String corpusName, TemporalExecutor temporalExecutor);

    /**
     * Executes the temporal condition using this strategy.
     *
     * @param condition The temporal condition to execute.
     * @param indexes Map of available indexes.
     * @param granularity The desired granularity (DOCUMENT or SENTENCE).
     * @param granularitySize The window size for sentence granularity.
     * @param corpusName The name of the corpus being queried.
     * @param temporalExecutor The parent TemporalExecutor (for context).
     * @return A list of MatchDetail objects representing the matches.
     * @throws QueryExecutionException If an error occurs during execution.
     */
    List<MatchDetail> execute(
        Temporal condition,
        Map<String, IndexAccessInterface> indexes,
        Query.Granularity granularity,
        int granularitySize,
        String corpusName,
        TemporalExecutor temporalExecutor)
        throws QueryExecutionException;
}