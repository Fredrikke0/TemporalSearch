package com.example.index;

import com.example.core.Position;
import java.time.LocalDate;

/**
 * Represents a position in the stitch index, containing a unigram position and its associated annotation.
 * The annotation type (DATE, NER, POS, DEPENDENCY) determines how this position is processed.
 */
public class StitchPosition extends Position {
    private final int synonymId;  // ID from the appropriate synonym table
    private final AnnotationType type; // The type of annotation
    
    // Type identifier for serialization
    public static final byte POSITION_TYPE = 1;

    public StitchPosition(
        int documentId,
        int sentenceId,
        int beginPosition,
        int endPosition,
        AnnotationType type,
        int synonymId
    ) {
        super(documentId, sentenceId, beginPosition, endPosition);
        this.synonymId = synonymId;
        this.type = type;
    }

    public int getSynonymId() {
        return synonymId;
    }
    
    public AnnotationType getType() {
        return type;
    }
    
    /**
     * Creates a StitchPosition from a regular Position by adding the annotation type and synonym ID.
     * 
     * @param position The base position
     * @param type The annotation type
     * @param synonymId The annotation synonym ID
     * @return A new StitchPosition
     */
    public static StitchPosition fromPosition(Position position, AnnotationType type, int synonymId) {
        return new StitchPosition(
            position.getDocumentId(),
            position.getSentenceId(),
            position.getBeginPosition(),
            position.getEndPosition(),
            type,
            synonymId
        );
    }
    
    @Override
    public String toString() {
        return String.format("StitchPosition(doc=%d, sent=%d, begin=%d, end=%d, type=%s, synonymId=%d)",
                getDocumentId(), getSentenceId(), getBeginPosition(), getEndPosition(), 
                type, synonymId);
    }
} 