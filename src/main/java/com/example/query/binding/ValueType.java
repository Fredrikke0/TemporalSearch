package com.example.query.binding;

/**
 * Defines the type of value captured in a MatchDetail.
 */
public enum ValueType {
    TERM,       // A simple text term
    DATE,       // A recognized date/time value
    ENTITY,     // A named entity (e.g., PERSON, ORGANIZATION)
    ENTITY_TYPE, // Represents the NER entity type itself (e.g., "PERSON", "LOCATION")
    DEPENDENCY, // A grammatical dependency relation
    POS_TERM,   // A term with its part-of-speech tag
    POS_TAG_TYPE, // Represents the POS tag type itself (e.g., "NN", "VB")
    NUMBER,
    // Add new types for unresolved IDs
    UNRESOLVED_NER_ID,
    UNRESOLVED_POS_ID
}