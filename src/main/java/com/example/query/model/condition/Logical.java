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

    /**
     * Creates a new Logical condition with all nested conditions having their variables requalified.
     * This is used during subquery processing when variable names need to be updated
     * from one alias scope to another (e.g., from "$main.*" to "q2.*").
     *
     * @param oldPrefix The old prefix to replace (e.g., "$main.")
     * @param newPrefix The new prefix to use (e.g., "q2.")
     * @return A new Logical condition with requalified nested conditions
     */
    public Logical requalifyVariables(String oldPrefix, String newPrefix) {
        List<Condition> requalifiedConditions = conditions.stream()
            .map(condition -> requalifyCondition(condition, oldPrefix, newPrefix))
            .toList();

        return new Logical(this.operator, requalifiedConditions);
    }

    /**
     * Helper method to requalify a single condition based on its type.
     */
    private static Condition requalifyCondition(Condition condition, String oldPrefix, String newPrefix) {
        return switch (condition) {
            case Temporal temporal -> temporal.requalifyVariable(oldPrefix, newPrefix);
            case Ner ner -> ner.requalifyVariable(oldPrefix, newPrefix);
            case Pos pos -> pos.requalifyVariable(oldPrefix, newPrefix);
            case Contains contains -> contains.requalifyVariable(oldPrefix, newPrefix);
            case Dependency dependency -> dependency.requalifyVariable(oldPrefix, newPrefix);
            case Logical logical -> logical.requalifyVariables(oldPrefix, newPrefix);
            case Not not -> not.requalifyVariables(oldPrefix, newPrefix);
        };
    }
}