package com.example.index;

import java.time.LocalDate;

/**
 * Common interface for all index entries.
 * Provides access to position and temporal information that is shared across all entry types.
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
     * @return The character offset where this entry begins in the document
     */
    int getBeginChar();

    /**
     * @return The character offset where this entry ends in the document
     */
    int getEndChar();
} 