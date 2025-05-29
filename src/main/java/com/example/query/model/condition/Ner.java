package com.example.query.model.condition;

import java.util.Collections;
import java.util.Objects;
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
    String target,
    String qualifiedVariableName,
    boolean isVariable
) implements Condition {

    /**
     * Creates a new NER condition with validation.
     */
    public Ner {
        Objects.requireNonNull(entityType, "entityType cannot be null");

        if (isVariable) {
            Objects.requireNonNull(qualifiedVariableName, "qualifiedVariableName cannot be null when isVariable is true");
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
     * Creates a new NER condition with variable binding.
     *
     * @param entityType The entity type to match
     * @param variableName The variable to bind the entities to
     * @param isVariable Flag indicating if binding occurs (always true for this constructor)
     */
    public Ner(String entityType, String variableName, boolean isVariable) {
        this(entityType, null, variableName, isVariable);
    }

    /**
     * Creates a new NER condition without variable binding.
     * This is a static factory method for backward compatibility.
     *
     * @param entityType The entity type to match
     * @return A new NER condition
     */
    public static Ner of(String entityType) {
        return new Ner(entityType);
    }

    /**
     * Creates a new NER condition with variable binding.
     * This is a static factory method for backward compatibility.
     *
     * @param entityType The entity type to match
     * @param variableName The variable name to bind entities to (with ? prefix)
     * @return A new NER condition
     */
    @Deprecated // Use builder which handles qualification
    public static Ner withVariable(String entityType, String variableName) {
        if (!variableName.startsWith("?")) {
            throw new IllegalArgumentException("Variable name must start with ?");
        }
        // This method is deprecated as qualification ($main.var) should happen in builder
        return new Ner(entityType, null, "$main." + variableName.substring(1), true);
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
        if (isVariable) {
            // Format: NER(Type) BIND alias.var
            return String.format("NER(%s) BIND %s", entityType, qualifiedVariableName);
        } else if (target != null) {
            // Format: NER(Type, Target)
            return String.format("NER(%s, %s)", entityType, target);
        } else {
            // Format: NER(Type)
            return String.format("NER(%s)", entityType);
        }
    }

    /**
     * Returns the variable name if this is a variable binding condition.
     *
     * @return The qualified variable name, or null if not bound
     */
    public String variableName() {
        return qualifiedVariableName;
    }
}