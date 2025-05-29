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
}