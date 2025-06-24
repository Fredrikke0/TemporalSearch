package com.example.query.binding;

/**
 * Represents a variable in the query, either a producer or a consumer.
 * Tracks the variable name (plain identifier) and its inferred type.
 *
 * Producer variables are created by conditions using `BIND` syntax:
 * For example, `NER(PERSON) BIND person` produces person entities.
 *
 * Consumer variables reference existing variables, potentially for filtering or joining.
 */
public sealed interface Variable permits ProducerVariable, ConsumerVariable {

    /**
     * Gets the name of the variable.
     *
     * @return The plain variable name
     */
    String getName();

    /**
     * Gets the variable type for type checking.
     * This allows semantic validation of variable usage.
     *
     * @return The variable's data type
     */
    VariableType getType();
}