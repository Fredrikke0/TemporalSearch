package com.example.index;

import java.time.LocalDate;

/**
 * Represents an annotation entry from the SQLite database.
 * Contains information about a token's original text and part-of-speech tag.
 */
public final class AnnotationEntry implements IndexEntry {
    private final int annotationId;
    private final int documentId;
    private final int sentenceId;
    private final int beginChar;
    private final int endChar;
    private final String token;
    private final String pos;

    public AnnotationEntry(int annotationId, int documentId, int sentenceId, int beginChar, int endChar,
            String token, String pos) {
        this.annotationId = annotationId;
        this.documentId = documentId;
        this.sentenceId = sentenceId;
        this.beginChar = beginChar;
        this.endChar = endChar;
        this.token = token;
        this.pos = pos;
    }

    /**
     * @return The unique identifier for this annotation
     */
    public int getAnnotationId() {
        return annotationId;
    }

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

    /**
     * @return The original token text
     */
    public String getToken() {
        return token;
    }

    /**
     * @return The part-of-speech tag for the token
     */
    public String getPos() {
        return pos;
    }
} 