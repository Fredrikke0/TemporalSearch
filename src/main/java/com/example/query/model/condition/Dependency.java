package com.example.query.model.condition;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;

/**
 * Represents a dependency condition in the query language.
 * This condition matches documents based on syntactic dependencies between words.
 */
public record Dependency(
    String governor,
    String relation,
    String dependent,
    String qualifiedVariableName,
    boolean isVariable
) implements Condition {

    private static final Logger logger = LoggerFactory.getLogger(Dependency.class);

    /**
     * Creates a new dependency condition with validation.
     */
    public Dependency {
        Objects.requireNonNull(governor, "governor cannot be null");
        Objects.requireNonNull(relation, "relation cannot be null");
        Objects.requireNonNull(dependent, "dependent cannot be null");

        if (isVariable) {
            Objects.requireNonNull(qualifiedVariableName, "qualifiedVariableName cannot be null when isVariable is true");
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
     * Creates a new dependency condition without variable binding.
     */
    public Dependency(String governor, String relation, String dependent) {
        this(governor, relation, dependent, null, false);
    }

    /**
     * Creates a new dependency condition with variable binding.
     *
     * @param governor The governor term
     * @param relation The dependency relation
     * @param dependent The dependent term
     * @param variableName The variable name to bind the dependency to
     */
    public Dependency(String governor, String relation, String dependent, String variableName) {
        this(governor, relation, dependent, variableName, true);
    }

    /**
     * Returns whether this condition uses variable binding.
     */
    public boolean isVariable() {
        return isVariable;
    }


    /**
     * Returns the variable name if this is a variable binding condition.
     *
     * @return The qualified variable name, or null if not bound
     */
    public String variableName() {
        return qualifiedVariableName;
    }

    /**
     * Determines if a string is a variable reference.
     */
    private boolean isVariableReference(String s) {
        return s != null && s.startsWith("?");
    }

    @Override
    public String getType() {
        return "DEPENDENCY";
    }

    @Override
    public Set<String> getProducedVariables() {
        return isVariable ? Set.of(qualifiedVariableName) : Collections.emptySet();
    }

    @Override
    public Set<String> getConsumedVariables() {
        Set<String> consumed = new HashSet<>();
        // Governor/dependent are stored as plain names when variables were used in the query model.
        // For execution ordering, we need qualified names. Assume default main alias when unqualified.
        if (governor != null && !governor.isBlank() && !governor.equals("*") && !governor.startsWith("\"")) {
            if (!governor.contains(".")) {
                consumed.add(com.example.query.parser.QueryModelBuilder.DEFAULT_MAIN_ALIAS + "." + governor);
            } else {
                consumed.add(governor);
            }
            logger.debug("Marking {} as consumed variable (governor)", governor);
        }
        if (dependent != null && !dependent.isBlank() && !dependent.equals("*") && !dependent.startsWith("\"")) {
            if (!dependent.contains(".")) {
                consumed.add(com.example.query.parser.QueryModelBuilder.DEFAULT_MAIN_ALIAS + "." + dependent);
            } else {
                consumed.add(dependent);
            }
            logger.debug("Marking {} as consumed variable (dependent)", dependent);
        }
        logger.debug("Reporting consumed variables: {}", consumed);
        return consumed;
    }

    @Override
    public VariableType getProducedVariableType() {
        return VariableType.DEPENDENCY;
    }

    @Override
    public void registerVariables(VariableRegistry registry) {
        logger.debug("Registering variables for DEPENDS({}, {}, {})", governor, relation, dependent);
    }

    @Override
    public String toString() {
        if (isVariable) {
            return String.format("DEPENDS(%s, %s, %s) BIND %s", governor, relation, dependent, qualifiedVariableName);
        } else {
            return String.format("DEPENDS(%s, %s, %s)", governor, relation, dependent);
        }
    }

    /**
     * Creates a new Dependency condition with the variable name requalified.
     * This is used during subquery processing when variable names need to be updated
     * from one alias scope to another (e.g., from "$main.dep" to "q2.dep").
     *
     * @param oldPrefix The old prefix to replace (e.g., "$main.")
     * @param newPrefix The new prefix to use (e.g., "q2.")
     * @return A new Dependency condition with the requalified variable name, or this condition if no change needed
     */
    public Dependency requalifyVariable(String oldPrefix, String newPrefix) {
        if (!isVariable || qualifiedVariableName == null) {
            return this; // No variable to requalify
        }

        if (!qualifiedVariableName.startsWith(oldPrefix)) {
            return this; // Variable doesn't match the old prefix
        }

        String newVarName = newPrefix + qualifiedVariableName.substring(oldPrefix.length());
        return new Dependency(this.governor, this.relation, this.dependent, newVarName, this.isVariable);
    }
}