package com.example.index;

/**
 * Common interface for all index entries.
 * Provides access to position and temporal information that is shared across
 * all entry types.
 */
public interface IndexEntry {
    /**
     * @return The ID of the document containing this entry
     */
    int getDocumentId();

    /**
     * @return The ID of the sentence containing this entry
     */
    int getSentenceId();

    /**
     * @return The character offset where this entry begins within its sentence
     *         (0-based, always in range [0,
     *         {@code CoreNLPConfig.MAX_SENTENCE_LENGTH}])
     */
    int getBeginChar();

    /**
     * @return The character offset where this entry ends within its sentence
     *         (0-based, always in range [0,
     *         {@code CoreNLPConfig.MAX_SENTENCE_LENGTH}])
     */
    int getEndChar();
}
