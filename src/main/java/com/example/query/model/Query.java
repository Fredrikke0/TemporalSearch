package com.example.query.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;
import com.example.query.model.condition.Condition;

/**
 * Represents a query in the query language.
 * A query consists of:
 * - A source (e.g. "wikipedia")
 * - A list of conditions
 * - Optional order by specifications (column names, prefix with "-" for descending)
 * - Optional limit
 * - Granularity settings
 * - Optional granularity size
 * - List of columns to select
 * - Variable binding metadata
 * - A list of join steps for chained joins
 * - Optional group by columns
 */
public record Query(
    String source,
    List<Condition> conditions,
    List<String> orderBy,
    Optional<Integer> limit,
    Granularity granularity,
    Optional<Integer> granularitySize,
    List<SelectColumn> selectColumns,
    VariableRegistry variableRegistry,
    List<JoinStep> joinSteps,
    Optional<String> mainAlias,
    List<String> groupByColumns
) {
    public enum Granularity {
        DOCUMENT,
        SENTENCE
    }

    /**
     * Creates a query with validation.
     */
    public Query {
        Objects.requireNonNull(source, "Source cannot be null");
        Objects.requireNonNull(conditions, "Conditions cannot be null");
        Objects.requireNonNull(orderBy, "Order by specifications cannot be null");
        Objects.requireNonNull(limit, "Limit cannot be null");
        Objects.requireNonNull(granularity, "Granularity cannot be null");
        Objects.requireNonNull(granularitySize, "Granularity size cannot be null");
        Objects.requireNonNull(selectColumns, "Select columns cannot be null");
        Objects.requireNonNull(variableRegistry, "Variable registry cannot be null");
        Objects.requireNonNull(joinSteps, "Join steps cannot be null");
        Objects.requireNonNull(mainAlias, "Main alias cannot be null");
        Objects.requireNonNull(groupByColumns, "Group by columns cannot be null");

        // Make defensive copies
        conditions = List.copyOf(conditions);
        orderBy = List.copyOf(orderBy);
        selectColumns = List.copyOf(selectColumns);
        joinSteps = List.copyOf(joinSteps);
        groupByColumns = List.copyOf(groupByColumns);
    }

    /**
     * Creates a query with just a source.
     */
    public Query(String source) {
        this(source, List.of(), List.of(), Optional.empty(), Granularity.DOCUMENT, Optional.empty(), List.of(), new VariableRegistry(), List.of(), Optional.empty(), List.of());
    }

    /**
     * Creates a query with source and conditions.
     */
    public Query(String source, List<Condition> conditions) {
        this(source, conditions, List.of(), Optional.empty(), Granularity.DOCUMENT, Optional.empty(), List.of(), new VariableRegistry(), List.of(), Optional.empty(), List.of());
    }

    /**
     * Creates a query with source, conditions, and granularity.
     */
    public Query(String source, List<Condition> conditions, Granularity granularity) {
        this(source, conditions, List.of(), Optional.empty(), granularity, Optional.empty(), List.of(), new VariableRegistry(), List.of(), Optional.empty(), List.of());
    }

    /**
     * Creates a query with all parameters except variable registry, join steps.
     */
    public Query(
        String source,
        List<Condition> conditions,
        List<String> orderBy,
        Optional<Integer> limit,
        Granularity granularity,
        Optional<Integer> granularitySize,
        List<SelectColumn> selectColumns
    ) {
        this(source, conditions, orderBy, limit, granularity, granularitySize, selectColumns, new VariableRegistry(), List.of(), Optional.empty(), List.of());
    }

    /**
     * Creates a query with all parameters except join steps.
     */
    public Query(
        String source,
        List<Condition> conditions,
        List<String> orderBy,
        Optional<Integer> limit,
        Granularity granularity,
        Optional<Integer> granularitySize,
        List<SelectColumn> selectColumns,
        VariableRegistry variableRegistry
    ) {
        this(source, conditions, orderBy, limit, granularity, granularitySize, selectColumns, variableRegistry, List.of(), Optional.empty(), List.of());
    }

    /**
     * Registers a producer variable in the registry.
     *
     * @param name The variable name
     * @param type The variable type
     * @param conditionType The condition type that produces the variable
     */
    public void registerProducer(String name, VariableType type, String conditionType) {
        variableRegistry.registerProducer(name, type, conditionType);
    }

    /**
     * Registers a consumer variable in the registry.
     *
     * @param name The variable name
     * @param type The variable type
     * @param conditionType The condition type that consumes the variable
     */
    public void registerConsumer(String name, VariableType type, String conditionType) {
        variableRegistry.registerConsumer(name, type, conditionType);
    }

    /**
     * Checks if a variable with the given name is produced in this query.
     *
     * @param name The variable name
     * @return true if the variable is produced, false otherwise
     */
    public boolean isVariableProduced(String name) {
        return variableRegistry.isProduced(name);
    }

    /**
     * Gets the inferred type for a variable, based on all its producers and consumers.
     *
     * @param name The variable name
     * @return The inferred variable type
     */
    public VariableType getVariableType(String name) {
        return variableRegistry.getInferredType(name);
    }

    /**
     * Gets all variable names declared in this query.
     *
     * @return Set of all variable names
     */
    public Set<String> getAllVariableNames() {
        return variableRegistry.getAllVariableNames();
    }

    /**
     * Validates the variable registry for this query.
     *
     * @return Set of validation error messages, empty if valid
     */
    public Set<String> validateVariables() {
        return variableRegistry.validate();
    }

    /**
     * Checks if this query has subqueries (now interpreted as join steps).
     *
     * @return true if the query has one or more join steps, false otherwise
     */
    public boolean hasSubqueries() { // Renamed conceptually, method name kept for compatibility if used widely
        return !joinSteps.isEmpty();
    }

    /**
     * Determines if select column qualification is required.
     * Qualification is required if the query has an explicit main alias or involves joins.
     *
     * @return true if qualification is required, false otherwise.
     */
    public boolean isQualificationRequired() {
        return mainAlias.isPresent() || !joinSteps.isEmpty();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        // Start with SELECT clause
        if (!selectColumns.isEmpty()) {
            sb.append("SELECT ");
            for (int i = 0; i < selectColumns.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(selectColumns.get(i));
            }
        } else {
            // Handle case with no select columns explicitly, maybe SELECT * or COUNT(*)?
            // For now, let's assume valid queries always have select columns based on validator
            sb.append("SELECT DOCUMENT_ID");
        }

        // Add FROM clause
        sb.append(" FROM ").append(source);
        mainAlias.ifPresent(alias -> sb.append(" BIND ").append(alias));

        // Add JOIN clauses (if any)
        if (!joinSteps.isEmpty()) {
            for (JoinStep step : joinSteps) {
                sb.append(" ").append(step.joinType()).append(" JOIN (").append(step.subquery().toString())
                  .append(") BIND ").append(step.rightSourceAlias())
                  .append(" ON ").append(step.onCondition().toString());
            }
        }

        // Add WHERE clause
        if (!conditions.isEmpty()) {
            sb.append(" WHERE ");
            // Note: If multiple top-level conditions are present in this list, they will be rendered as ANDed.
            // In normal parsing, the WHERE clause is represented as a single Condition tree (Logical with AND/OR/NOT),
            // and that single root's toString() expresses the intended logical structure.
            for (int i = 0; i < conditions.size(); i++) {
                if (i > 0) sb.append(" AND ");
                sb.append(conditions.get(i)); // Relies on Condition.toString()
            }
        }

        // Add GRANULARITY clause
        if (granularity != Granularity.DOCUMENT || granularitySize.isPresent()) {
            sb.append(" GRANULARITY ").append(granularity.name());
            granularitySize.ifPresent(size -> sb.append(" ").append(size));
        }

        // Add ORDER BY clause
        if (!orderBy.isEmpty()) {
            sb.append(" ORDER BY ");
            for (int i = 0; i < orderBy.size(); i++) {
                if (i > 0) sb.append(", ");
                String column = orderBy.get(i);
                // Assuming format "column" or "-column"
                if (column.startsWith("-")) {
                    sb.append(column.substring(1)).append(" DESC");
                } else {
                    sb.append(column).append(" ASC"); // Default ASC optional?
                }
            }
        }

        // Add LIMIT clause
        limit.ifPresent(l -> sb.append(" LIMIT ").append(l));

        return sb.toString();
    }
}