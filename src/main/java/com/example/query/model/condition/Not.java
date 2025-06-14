package com.example.query.model.condition;

import java.util.Objects;
import java.util.Set;

import com.example.query.binding.VariableRegistry;

/**
 * Represents a logical negation (NOT) of a condition.
 */
public record Not(
    Condition condition
) implements Condition {

    /**
     * Creates a new NOT condition with validation.
     */
    public Not {
        Objects.requireNonNull(condition, "condition cannot be null");
    }

    @Override
    public String getType() {
        return "NOT";
    }

    @Override
    public Set<String> getConsumedVariables() {
        return condition.getConsumedVariables();
    }

    @Override
    public void registerVariables(VariableRegistry registry) {
        // Register variables from inner condition
        condition.registerVariables(registry);
    }

    @Override
    public String toString() {
        return "NOT " + condition.toString();
    }

    /**
     * Creates a new Not condition with the nested condition having its variables requalified.
     * This is used during subquery processing when variable names need to be updated
     * from one alias scope to another (e.g., from "$main.*" to "q2.*").
     *
     * @param oldPrefix The old prefix to replace (e.g., "$main.")
     * @param newPrefix The new prefix to use (e.g., "q2.")
     * @return A new Not condition with the requalified nested condition
     */
    public Not requalifyVariables(String oldPrefix, String newPrefix) {
        Condition requalifiedCondition = requalifyCondition(this.condition, oldPrefix, newPrefix);
        return new Not(requalifiedCondition);
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
            case StitchedCondition stitched -> stitched.requalifyVariables(oldPrefix, newPrefix);
            default -> throw new IllegalArgumentException("Unsupported condition type: " + condition.getClass().getSimpleName());
        };
    }
}