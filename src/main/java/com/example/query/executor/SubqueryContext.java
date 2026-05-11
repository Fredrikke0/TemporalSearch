package com.example.query.executor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains the execution context for subqueries, including
 * intermediate results as CellResult objects.
 * This class serves as a container for subquery results during
 * the recursive execution of queries with subqueries.
 */
public class SubqueryContext {
    private static final Logger logger = LoggerFactory.getLogger(SubqueryContext.class);
    private final Map<String, CellResult> queryResults;

    /**
     * Creates an empty subquery context.
     */
    public SubqueryContext() {
        this.queryResults = new HashMap<>();
    }

    /**
     * Adds a query result directly using an alias.
     * Used for storing the result of the main query part when handling joins.
     *
     * @param alias  The alias to associate with the result.
     * @param result The CellResult.
     */
    public void addQueryResult(String alias, CellResult result) {
        Objects.requireNonNull(alias, "alias cannot be null");
        Objects.requireNonNull(result, "CellResult cannot be null");
        if (queryResults.containsKey(alias)) {
            logger.debug("Overwriting existing CellResult for alias: {}", alias);
        }
        queryResults.put(alias, result);
    }

    /**
     * Gets the CellResult for a subquery by its alias.
     *
     * @param alias The subquery alias
     * @return The CellResult object, or null if not found
     */
    public CellResult getQueryResult(String alias) {
        return queryResults.get(alias);
    }

    /**
     * Checks if a subquery has results stored.
     *
     * @param alias The subquery alias
     * @return true if the subquery has a CellResult stored, false otherwise
     */
    public boolean hasResults(String alias) {
        return queryResults.containsKey(alias);
    }

    /**
     * Gets the set of all subquery aliases with results.
     *
     * @return The set of subquery aliases that have CellResult stored
     */
    public Set<String> getAliases() {
        return new HashSet<>(queryResults.keySet());
    }

    /**
     * Creates a join variable name in the format expected by the TemporalExecutor.
     *
     * @param leftAlias  The left subquery alias
     * @param rightAlias The right subquery alias
     * @return A variable name in the format "join.leftAlias.rightAlias"
     */
    public static String createJoinVariableName(String leftAlias, String rightAlias) {
        Objects.requireNonNull(leftAlias, "Left alias cannot be null");
        Objects.requireNonNull(rightAlias, "Right alias cannot be null");

        return "join." + leftAlias + "." + rightAlias;
    }
}
