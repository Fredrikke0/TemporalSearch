package com.example.query.model.condition;

import java.util.Collections;
import java.util.Set;

import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;

/**
 * Represents a Named Entity Recognition (NER) condition in the query language.
 * This condition matches sentences containing specific entity types.
 *
 * Supported entity types (as per CoreNLP):
 * - PERSON: Person names
 * - ORGANIZATION: Organization names
 * - LOCATION: Location names
 * - DATE: Date expressions
 * - TIME: Time expressions
 * - DURATION: Duration expressions
 * - MONEY: Monetary amounts
 * - NUMBER: Numeric values
 * - ORDINAL: Ordinal numbers
 * - PERCENT: Percentage values
 * - SET: Set expressions (e.g., "weekly", "monthly")
 *
 * Usage examples:
 * - NER("PERSON", "?person") - Binds person entities to variable
 * - NER("ORGANIZATION") - Matches any organization
 * - NER("DATE", "?date") - Binds date expressions to variable
 * - NER("MONEY") - Matches any monetary amount
 */
public record Ner(
    String entityType,
    String target,            // Specific entity text to match, or null
    String qualifiedVariableName, // Variable to bind entities to (e.g., $main.person), or null
    boolean isVariable
) implements Condition {

    /**
     * Creates a new NER condition with validation. This is the compact constructor.
     */
    public Ner { // Compact constructor
        java.util.Objects.requireNonNull(entityType, "entityType cannot be null");

        if (isVariable) {
            java.util.Objects.requireNonNull(qualifiedVariableName, "qualifiedVariableName cannot be null when isVariable is true");
            if (qualifiedVariableName.isBlank()) {
                throw new IllegalArgumentException("qualifiedVariableName cannot be blank when isVariable is true");
            }
        } else {
            if (qualifiedVariableName != null) {
                throw new IllegalArgumentException("qualifiedVariableName must be null when isVariable is false");
            }
        }
    }

    /**
     * Creates a new NER condition without variable binding.
     *
     * @param entityType The entity type to match (e.g., "PERSON", "ORGANIZATION")
     */
    public Ner(String entityType) {
        this(entityType, null, null, false);
    }

    /**
     * Creates a new NER condition with a target but without variable binding.
     *
     * @param entityType The entity type to match
     * @param target The specific entity text to match
     */
    public Ner(String entityType, String target) {
        this(entityType, target, null, false);
    }

    /**
     * Creates a new NER condition without variable binding.
     * This is a static factory method for backward compatibility or simple cases.
     *
     * @param entityType The entity type to match
     * @return A new NER condition
     */
    public static Ner of(String entityType) {
        return new Ner(entityType);
    }

    @Override
    public String getType() {
        return "NER";
    }

    @Override
    public Set<String> getProducedVariables() {
        return isVariable ? Set.of(qualifiedVariableName) : Collections.emptySet();
    }

    @Override
    public VariableType getProducedVariableType() {
        return VariableType.ENTITY;
    }

    @Override
    public void registerVariables(VariableRegistry registry) {
        if (isVariable) {
            registry.registerProducer(qualifiedVariableName, getProducedVariableType(), getType());
        }
        // Consumption of 'target' if it were a variable would also be handled in builder
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("NER(").append(entityType);

        if (target != null) {
            sb.append(", ").append(target);
        }
        sb.append(")");

        if (isVariable && qualifiedVariableName != null) {
            sb.append(" BIND ").append(qualifiedVariableName);
        }
        return sb.toString();
    }
}