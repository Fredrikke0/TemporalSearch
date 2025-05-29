package com.example.index;

import com.example.core.Position;

/**
 * Represents a position in the stitch index, containing a unigram position and its associated annotation.
 * The annotation type (DATE, NER, POS, DEPENDENCY) determines how this position is processed.
 */
public class StitchPosition extends Position {
    private final int synonymId;  // ID from the appropriate synonym table
    private final AnnotationType type; // The type of annotation
    private final int annotationBeginChar; // Begin char of the linked annotation
    private final int annotationEndChar;   // End char of the linked annotation

    // Type identifier for serialization
    public static final byte POSITION_TYPE = 1;

    public StitchPosition(
        int documentId,
        int sentenceId,
        int beginPosition,     // Unigram's begin position
        int endPosition,       // Unigram's end position
        AnnotationType type,
        int synonymId,
        int annotationBeginChar,
        int annotationEndChar
    ) {
        super(documentId, sentenceId, beginPosition, endPosition);
        this.synonymId = synonymId;
        this.type = type;
        this.annotationBeginChar = annotationBeginChar;
        this.annotationEndChar = annotationEndChar;
    }

    public int getSynonymId() {
        return synonymId;
    }

    public AnnotationType getType() {
        return type;
    }

    public int getAnnotationBeginChar() {
        return annotationBeginChar;
    }

    public int getAnnotationEndChar() {
        return annotationEndChar;
    }

    /**
     * Creates a StitchPosition from a regular Position by adding the annotation type, synonym ID,
     * and annotation begin and end characters.
     *
     * @param position The base position
     * @param type The annotation type
     * @param synonymId The annotation synonym ID
     * @param annotationBeginChar Begin character of the annotation
     * @param annotationEndChar End character of the annotation
     * @return A new StitchPosition
     */
    public static StitchPosition fromPosition(Position position, AnnotationType type, int synonymId, int annotationBeginChar, int annotationEndChar) {
        return new StitchPosition(
            position.getDocumentId(),
            position.getSentenceId(),
            position.getBeginPosition(),
            position.getEndPosition(),
            type,
            synonymId,
            annotationBeginChar,
            annotationEndChar
        );
    }

    @Override
    public String toString() {
        return String.format("StitchPosition(doc=%d, sent=%d, unigram_begin=%d, unigram_end=%d, type=%s, synonymId=%d, ann_begin=%d, ann_end=%d)",
                getDocumentId(), getSentenceId(), getBeginPosition(), getEndPosition(),
                type, synonymId, annotationBeginChar, annotationEndChar);
    }
}