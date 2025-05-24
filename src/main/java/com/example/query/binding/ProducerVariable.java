package com.example.query.binding;

import java.util.Collections;
import java.util.Set;

/**
 * Represents a variable that produces values through extraction.
 * For example, NER(PERSON) AS person produces person entities.
 */
public record ProducerVariable(
    String name,
    VariableType type,
    String sourceConditionType,
    Set<String> producedBy
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
        
        // Ensure defensive copies
        producedBy = producedBy != null ? 
            Collections.unmodifiableSet(Set.copyOf(producedBy)) : 
            Collections.emptySet();
    }
    
    /**
     * Creates a simple producer variable with a single producing condition.
     */
    public ProducerVariable(String name, VariableType type, String sourceConditionType) {
        this(name, type, sourceConditionType, Set.of(sourceConditionType));
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