package com.example.query.model.condition;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.example.query.binding.VariableRegistry;

/**
 * Represents a logical operation (AND, OR) between multiple conditions.
 */
public record Logical(
    LogicalOperator operator,
    List<Condition> conditions
) implements Condition {

    /**
     * The type of logical operation.
     */
    public enum LogicalOperator {
        AND,
        OR
    }

    /**
     * Creates a logical condition with validation.
     */
    public Logical {
        Objects.requireNonNull(operator, "operator cannot be null");
        Objects.requireNonNull(conditions, "conditions cannot be null");
        // Make defensive copy of conditions
        conditions = List.copyOf(conditions);
    }

    /**
     * Creates a new logical condition with the specified operator and exactly two conditions.
     *
     * @param operator The logical operator (AND, OR)
     * @param left The left condition
     * @param right The right condition
     */
    public Logical(LogicalOperator operator, Condition left, Condition right) {
        this(operator, List.of(
            Objects.requireNonNull(left, "left condition cannot be null"),
            Objects.requireNonNull(right, "right condition cannot be null")
        ));
    }

    @Override
    public String getType() {
        return operator.name();
    }

    @Override
    public Set<String> getProducedVariables() {
        Set<String> producedVariables = new HashSet<>();
        for (Condition condition : conditions) {
            producedVariables.addAll(condition.getProducedVariables());
        }
        return producedVariables;
    }

    @Override
    public Set<String> getConsumedVariables() {
        Set<String> consumedVariables = new HashSet<>();
        for (Condition condition : conditions) {
            consumedVariables.addAll(condition.getConsumedVariables());
        }
        return consumedVariables;
    }

    @Override
    public void registerVariables(VariableRegistry registry) {
        // Register variables from all child conditions
        for (Condition condition : conditions) {
            condition.registerVariables(registry);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("(");

        boolean first = true;
        for (Condition condition : conditions) {
            if (!first) {
                sb.append(" ").append(operator.name()).append(" ");
            }
            sb.append(condition.toString());
            first = false;
        }

        sb.append(")");
        return sb.toString();
    }
}