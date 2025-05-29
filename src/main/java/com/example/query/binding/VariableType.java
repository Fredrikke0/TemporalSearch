package com.example.query.binding;

/**
 * Defines the possible data types for variables.
 * Used for semantic validation of variable usage.
 */
public enum VariableType {
    /**
     * Entity references (e.g., person or organization names)
     */
    ENTITY,

    /**
     * Text spans (sequences of words)
     */
    TEXT_SPAN,

    /**
     * Temporal references (dates, times)
     */
    TEMPORAL,

    /**
     * Part-of-speech tags
     */
    POS_TAG,

    /**
     * Dependency relations
     */
    DEPENDENCY,

    /**
     * Used when the type is unknown or not important
     */
    ANY;

    /**
     * Checks if this type is compatible with another type.
     * Types are compatible if they are the same or if either is ANY.
     *
     * @param other The other type to check compatibility with
     * @return true if the types are compatible, false otherwise
     */
    public boolean isCompatibleWith(VariableType other) {
        return this == ANY || other == ANY || this == other;
    }
}