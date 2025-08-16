package com.example.query.binding;

/**
 * Represents a variable that produces values through extraction.
 * For example, NER(PERSON) BIND person produces person entities.
 */
public record ProducerVariable(
    String name,
    VariableType type,
    String sourceConditionType
) implements Variable {

    /**
     * Creates a producer variable with validation.
     */
    public ProducerVariable {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Variable name cannot be null or blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Variable type cannot be null");
        }
        if (sourceConditionType == null || sourceConditionType.isBlank()) {
            throw new IllegalArgumentException("Source condition type cannot be null or blank");
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