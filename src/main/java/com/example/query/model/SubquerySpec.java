package com.example.query.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a subquery within a parent query.
 * Subqueries are used to join data between different queries.
 */
public record SubquerySpec(
    Query subquery,
    String alias,
    Optional<List<String>> projectedColumns
) {
    /**
     * Creates a subquery specification with validation.
     */
    public SubquerySpec {
        Objects.requireNonNull(subquery, "Subquery cannot be null");
        Objects.requireNonNull(alias, "Alias cannot be null");
        Objects.requireNonNull(projectedColumns, "Projected columns cannot be null");

        if (alias.isEmpty()) {
            throw new IllegalArgumentException("Alias cannot be empty");
        }

        // Make defensive copy of projected columns if present
        if (projectedColumns.isPresent()) {
            projectedColumns = Optional.of(List.copyOf(projectedColumns.get()));
        }
    }

    /**
     * Creates a subquery specification without column projection specifications.
     * This means all columns from the subquery will be available for joining.
     */
    public SubquerySpec(Query subquery, String alias) {
        this(subquery, alias, Optional.empty());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("(").append(subquery).append(") BIND ").append(alias);

        projectedColumns.ifPresent(columns -> {
            sb.append(" (");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(columns.get(i));
            }
            sb.append(")");
        });

        return sb.toString();
    }
}