package com.example.query.model.condition;

import java.util.Collections;
import java.util.List;
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
 */
public record Ner(
    String entityType,
    List<String> targets,        // Specific entity texts to match, or empty list for any
    String qualifiedVariableName, // Variable to bind entities to (e.g., $main.person), or null
    boolean isVariable
) implements Condition {

    /**
     * Creates a new NER condition with validation. This is the compact constructor.
     */
    public Ner { // Compact constructor
        java.util.Objects.requireNonNull(entityType, "entityType cannot be null");
        java.util.Objects.requireNonNull(targets, "targets cannot be null");

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
        this(entityType, List.of(), null, false);
    }

    /**
     * Creates a new NER condition with a single target but without variable binding.
     *
     * @param entityType The entity type to match
     * @param target The specific entity text to match
     */
    public Ner(String entityType, String target) {
        this(entityType, target != null ? List.of(target) : List.of(), null, false);
    }

    /**
     * Creates a new NER condition with multiple targets but without variable binding.
     *
     * @param entityType The entity type to match
     * @param targets The specific entity texts to match
     */
    public Ner(String entityType, List<String> targets) {
        this(entityType, targets != null ? targets : List.of(), null, false);
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

    /**
     * Gets the first target for backward compatibility.
     *
     * @return The first target string, or null if targets list is empty
     */
    public String target() {
        return targets.isEmpty() ? null : targets.get(0);
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
        // Consumption of 'targets' if they were variables would also be handled in builder
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("NER(").append(entityType);

        if (!targets.isEmpty()) {
            final int MAX_TARGETS_TO_DISPLAY = 10;
            if (targets.size() == 1) {
                sb.append(", ").append(targets.get(0));
            } else if (targets.size() <= MAX_TARGETS_TO_DISPLAY) {
                sb.append(", [").append(String.join(", ", targets)).append("]");
            } else {
                List<String> sublist = targets.subList(0, MAX_TARGETS_TO_DISPLAY);
                int remaining = targets.size() - MAX_TARGETS_TO_DISPLAY;
                sb.append(", [")
                  .append(String.join(", ", sublist))
                  .append(", ... (and ")
                  .append(remaining)
                  .append(" more target")
                  .append(remaining > 1 ? "s" : "") // Pluralize 'target' if needed
                  .append(")]");
            }
        }
        sb.append(")");

        if (isVariable && qualifiedVariableName != null) {
            sb.append(" BIND ").append(qualifiedVariableName);
        }
        return sb.toString();
    }

    /**
     * Creates a new Ner condition with the variable name requalified.
     * This is used during subquery processing when variable names need to be updated
     * from one alias scope to another (e.g., from "$main.entity" to "q2.entity").
     *
     * @param oldPrefix The old prefix to replace (e.g., "$main.")
     * @param newPrefix The new prefix to use (e.g., "q2.")
     * @return A new Ner condition with the requalified variable name, or this condition if no change needed
     */
    public Ner requalifyVariable(String oldPrefix, String newPrefix) {
        if (!isVariable || qualifiedVariableName == null) {
            return this; // No variable to requalify
        }

        if (!qualifiedVariableName.startsWith(oldPrefix)) {
            return this; // Variable doesn't match the old prefix
        }

        String newVarName = newPrefix + qualifiedVariableName.substring(oldPrefix.length());
        return new Ner(this.entityType, this.targets, newVarName, this.isVariable);
    }
}