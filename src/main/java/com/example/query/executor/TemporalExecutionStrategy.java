package com.example.query.executor;

import com.example.core.IndexAccessInterface;
import com.example.query.binding.MatchDetail;
import com.example.query.model.Query;
import com.example.query.model.condition.Temporal;
import java.util.List;
import java.util.Map;

/**
 * Interface for different strategies to execute a Temporal condition.
 */
public interface TemporalExecutionStrategy {

    /**
     * Executes the temporal condition using a specific strategy.
     *
     * @param condition The temporal condition to execute.
     * @param indexes A map of available indexes.
     * @param granularity The desired granularity of the results.
     * @param granularitySize The size parameter for the granularity.
     * @param corpusName The name of the corpus being queried.
     * @param temporalExecutor The parent executor (potentially needed for accessing shared resources like Nash index).
     * @return A list of MatchDetail objects representing the results.
     * @throws QueryExecutionException If execution fails.
     */
    List<MatchDetail> execute(
            Temporal condition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            TemporalExecutor temporalExecutor) // Pass TemporalExecutor for context
            throws QueryExecutionException;

    /**
     * Indicates whether this strategy requires direct index access
     * (e.g., for variable binding, which requires the specific date value from the index).
     * This helps the main executor potentially override the active strategy choice
     * if the active strategy cannot fulfill the requirement (like variable binding).
     * Note: Sentence granularity is no longer a mandatory reason for direct access if the strategy supports it.
     * @param condition The temporal condition.
     * @param granularity The query granularity.
     * @return true if direct index access is mandatory for this condition/granularity, false otherwise.
     */
     boolean requiresDirectIndexAccess(Temporal condition, Query.Granularity granularity);

     /**
      * Gets the name of this strategy (e.g., "nash", "index_scan").
      * @return The strategy name.
      */
     String getName();

     /**
      * Opportunity for the strategy to initialize itself, potentially using
      * resources from the TemporalExecutor (like the Nash index map).
      * @param corpusName The name of the corpus.
      * @param temporalExecutor The parent executor containing shared resources.
      * @return true if initialization was successful and the strategy is ready for the corpus, false otherwise.
      */
      boolean initializeForCorpus(String corpusName, TemporalExecutor temporalExecutor);
} 