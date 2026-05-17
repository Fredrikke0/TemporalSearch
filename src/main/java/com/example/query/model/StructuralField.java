package com.example.query.model;

/**
 * Enumeration of structural fields that can be selected from a corpus.
 * These are metadata fields attached to every document/sentence.
 */
public enum StructuralField {
    TITLE,
    TIMESTAMP,
    DOCUMENT_ID,
    SENTENCE_ID,
    BEGIN,
    END;

    /**
     * Converts a string to a StructuralField, case-insensitively.
     *
     * @param s the string to convert
     * @return the corresponding StructuralField
     * @throws IllegalArgumentException if no match is found
     */
    public static StructuralField fromString(String s) {
        return valueOf(s.toUpperCase());
    }
}
