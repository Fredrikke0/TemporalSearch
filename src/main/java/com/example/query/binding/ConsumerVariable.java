package com.example.query.binding;

/**
 * Represents a variable that consumes values from producers.
 * For example, CONTAINS(?person, "spoke") uses values from the ?person variable.
 */
public record ConsumerVariable(
    String name,
    VariableType type,
    String consumingConditionType
) implements Variable {

    /**
     * Creates a consumer variable with validation.
     */
    public ConsumerVariable {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Variable name cannot be null or blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Variable type cannot be null");
        }
        if (consumingConditionType == null || consumingConditionType.isBlank()) {
            throw new IllegalArgumentException("Consuming condition type cannot be null or blank");
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public VariableType getType() {
        return type;
    }
}