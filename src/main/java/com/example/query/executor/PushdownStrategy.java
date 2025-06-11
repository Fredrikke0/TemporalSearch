package com.example.query.executor;

/**
 * Defines the levels for predicate pushdown optimization strategy.
 */
public enum PushdownStrategy {
    /**
     * No pushdown optimizations. All parts of a query (conditions, subqueries)
     * are executed independently before results are merged or joined.
     */
    NONE,
    /**
     * Enables integrated join pushdown and intra-query AND condition pushdown.
     * Document IDs and sentence IDs from earlier conditions/query parts are used
     * to filter the execution of later ones.
     */
    OPTIMIZED;

    /**
     * Converts a string to the corresponding PushdownStrategy enum constant.
     * The conversion is case-insensitive.
     *
     * @param strategy The string representation of the strategy (e.g., "none", "optimized").
     * @return The corresponding PushdownStrategy enum constant.
     * @throws IllegalArgumentException if the provided string does not match any strategy.
     */
    public static PushdownStrategy fromString(String strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("Strategy string cannot be null");
        }
        for (PushdownStrategy ps : values()) {
            if (ps.name().equalsIgnoreCase(strategy)) {
                return ps;
            }
        }
        throw new IllegalArgumentException("Unknown pushdown strategy: " + strategy);
    }
}
