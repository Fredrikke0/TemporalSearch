package com.example.index;

/**
 * Represents an annotation entry from the SQLite database.
 * Contains information about a token and its linguistic features.
 */
public final class AnnotationEntry implements IndexEntry {
    private final long annotationId;
    private final int documentId;
    private final int sentenceId;
    private final int beginChar;
    private final int endChar;
    private final String token;
    private final String pos;
    private final String ner;            // Added NER type
    private final String normalizedNer;  // Added normalized NER value

    public AnnotationEntry(long annotationId, int documentId, int sentenceId, int beginChar, int endChar,
            String token, String pos, String ner, String normalizedNer) {
        this.annotationId = annotationId;
        this.documentId = documentId;
        this.sentenceId = sentenceId;
        this.beginChar = beginChar;
        this.endChar = endChar;
        this.token = token;
        this.pos = pos;
        this.ner = ner;
        this.normalizedNer = normalizedNer;
    }

    /**
     * @return The unique identifier for this annotation
     */
    public long getAnnotationId() {
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

    // New getters
    public String getNer() {
        return ner;
    }

    public String getNormalizedNer() {
        return normalizedNer;
    }

}