package com.example.query.model.condition;

import java.util.Collections;
import java.util.Set;

import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;

/**
 * Root of the condition hierarchy for query expressions.
 * Each condition type represents a different kind of query constraint.
 *
 * @see com.example.query.executor.ConditionExecutor
 */
public sealed interface Condition
    permits Contains,
            Dependency,
            Logical,
            Ner,
            Not,
            Pos,
            Temporal,
            StitchedCondition {

    /**
     * Gets the type of the condition.
     * Used for logging and error reporting.
     *
     * @return The condition type as a string
     */
    String getType();

    /**
     * Gets the set of variable names produced by this condition.
     * A produced variable is one that gets values bound to it by this condition.
     *
     * @return Set of produced variable names
     */
    default Set<String> getProducedVariables() {
        return Collections.emptySet();
    }

    /**
     * Gets the set of variable names consumed by this condition.
     * A consumed variable is one whose values are used by this condition.
     *
     * @return Set of consumed variable names
     */
    default Set<String> getConsumedVariables() {
        return Collections.emptySet();
    }

    /**
     * Registers the condition's variables with the registry.
     * This method should be called during query parsing/validation.
     *
     * @param registry The variable registry
     */
    default void registerVariables(VariableRegistry registry) {
        // Default implementation does nothing
        // Override in condition implementations that use variables
    }

    /**
     * Gets the VariableType for variables produced by this condition.
     * Used for type checking in variable binding.
     *
     * @return The variable type
     */
    default VariableType getProducedVariableType() {
        return VariableType.ANY;
    }
}