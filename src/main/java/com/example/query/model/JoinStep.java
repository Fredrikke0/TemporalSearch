package com.example.query.model;

import java.util.Objects;

/**
 * Represents a single step in a chain of join operations.
 */
public record JoinStep(
    String rightSourceAlias,    // Alias of the subquery/table being joined in this step
    Query subquery,             // The Query object for the subquery
    JoinCondition onCondition,  // The ON condition for this specific step
    JoinCondition.JoinType joinType, // INNER, LEFT, RIGHT for this step
    String leftSourceAlias      // Alias of the table/intermediate result this step joins TO
) {
    public JoinStep {
        Objects.requireNonNull(rightSourceAlias, "Right source alias cannot be null");
        Objects.requireNonNull(subquery, "Subquery cannot be null");
        Objects.requireNonNull(onCondition, "Join condition cannot be null");
        Objects.requireNonNull(joinType, "Join type cannot be null");
        Objects.requireNonNull(leftSourceAlias, "Left source alias cannot be null");
    }
}