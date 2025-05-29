package com.example.query.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Defines how two result sets should be joined.
 * Supports both temporal joins (based on predicates like CONTAINS, INTERSECT)
 * and equality joins (based on = or ==).
 */
public record JoinCondition(
    String leftColumn,
    String rightColumn,
    JoinType type,
    JoinOperatorType operatorType, // New field: TEMPORAL or EQUALITY
    Optional<TemporalPredicate> temporalPredicate, // Optional, only for TEMPORAL
    Optional<Integer> proximityWindow
) {
    /**
     * Type of join to perform.
     */
    public enum JoinType {
        INNER,
        LEFT,
        RIGHT
    }

    /**
     * Type of operator used in the ON clause.
     */
    public enum JoinOperatorType {
        TEMPORAL, // CONTAINS, INTERSECT, etc.
        EQUALITY  // =, ==
    }

    /**
     * Creates a join condition with validation.
     */
    public JoinCondition {
        Objects.requireNonNull(leftColumn, "Left column cannot be null");
        Objects.requireNonNull(rightColumn, "Right column cannot be null");
        Objects.requireNonNull(type, "Join type cannot be null");
        Objects.requireNonNull(operatorType, "Operator type cannot be null");
        Objects.requireNonNull(temporalPredicate, "Temporal predicate Optional cannot be null");
        Objects.requireNonNull(proximityWindow, "Proximity window cannot be null");

        // Temporal predicate must be present if operatorType is TEMPORAL
        if (operatorType == JoinOperatorType.TEMPORAL && temporalPredicate.isEmpty()) {
            throw new IllegalArgumentException("Temporal predicate must be provided for TEMPORAL joins");
        }
        // Temporal predicate must NOT be present if operatorType is EQUALITY
        if (operatorType == JoinOperatorType.EQUALITY && temporalPredicate.isPresent()) {
            throw new IllegalArgumentException("Temporal predicate must not be provided for EQUALITY joins");
        }

        // For PROXIMITY joins, the window size must be provided
        if (temporalPredicate.isPresent() && temporalPredicate.get() == TemporalPredicate.PROXIMITY && proximityWindow.isEmpty()) {
            throw new IllegalArgumentException("Proximity window must be specified for PROXIMITY joins");
        }

        // For non-PROXIMITY joins, window should not be specified
        if (proximityWindow.isPresent() && (temporalPredicate.isEmpty() || temporalPredicate.get() != TemporalPredicate.PROXIMITY)) {
            System.err.println("[DEBUG] Throwing exception: temporalPredicate=" + temporalPredicate + ", proximityWindow=" + proximityWindow);
            throw new IllegalArgumentException("Proximity window should not be specified for non-PROXIMITY joins");
        }
    }

    /**
     * Creates a standard temporal join condition (without proximity).
     */
    public static JoinCondition createTemporalJoin(String leftColumn, String rightColumn, JoinType type, TemporalPredicate temporalPredicate) {
        return new JoinCondition(leftColumn, rightColumn, type, JoinOperatorType.TEMPORAL,
                                 Optional.of(temporalPredicate), Optional.empty());
    }

    /**
     * Creates an equality join condition.
     */
    public static JoinCondition createEqualityJoin(String leftColumn, String rightColumn, JoinType type) {
        return new JoinCondition(leftColumn, rightColumn, type, JoinOperatorType.EQUALITY,
                                 Optional.empty(), Optional.empty());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(leftColumn).append(" ");
        if (operatorType == JoinOperatorType.TEMPORAL) {
            sb.append(temporalPredicate.orElseThrow()); // Should be present if TEMPORAL
        } else {
            sb.append("=="); // Represent equality
        }
        sb.append(" ");
        sb.append(rightColumn);

        proximityWindow.ifPresent(window ->
            sb.append(" WINDOW ").append(window));

        return sb.toString();
    }
}