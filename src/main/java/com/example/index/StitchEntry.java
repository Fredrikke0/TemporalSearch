package com.example.index;

/**
 * Represents an entry in the stitch index, containing a unigram and its associated annotation.
 * The annotation type (DATE, NER, POS, DEPENDENCY) determines how this entry is processed.
 */
public record StitchEntry(
    int documentId,
    int sentenceId,
    int beginChar,        // Unigram's begin char
    int endChar,          // Unigram's end char
    String value,         // The unigram text
    AnnotationType type,  // The type of annotation (DATE, NER, POS, DEPENDENCY)
    int synonymId,        // ID from the appropriate synonym table
    int annotationBeginChar, // Begin char of the linked annotation
    int annotationEndChar    // End char of the linked annotation
) implements IndexEntry {
    @Override
    public int getDocumentId() {
        return documentId;
    }

    @Override
    public int getSentenceId() {
        return sentenceId;
    }

    @Override
    public int getBeginChar() {
        return beginChar;
    }

    @Override
    public int getEndChar() {
        return endChar;
    }
}