package com.example.index;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Represents a dependency relation entry for indexing.
 */
public final class DependencyEntry implements IndexEntry {
    private final int dependencyId; // Added for keyset pagination
    private final int documentId;
    private final int sentenceId;
    private final int beginChar;
    private final int endChar;
    private final String headToken;
    private final String dependentToken;
    private final String relation;
    private final LocalDate timestamp; // From joined 'documents' table

    public DependencyEntry(int dependencyId, int documentId, int sentenceId, int beginChar, int endChar,
                           String headToken, String dependentToken, String relation, LocalDate timestamp) {
        this.dependencyId = dependencyId;
        this.documentId = documentId;
        this.sentenceId = sentenceId;
        this.beginChar = beginChar;
        this.endChar = endChar;
        this.headToken = headToken;
        this.dependentToken = dependentToken;
        this.relation = relation;
        this.timestamp = timestamp;
    }

    // Getter for dependencyId
    public int getDependencyId() {
        return dependencyId;
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
     * @return The head token in the dependency relation
     */
    public String getHeadToken() {
        return headToken;
    }

    /**
     * @return The dependent token in the dependency relation
     */
    public String getDependentToken() {
        return dependentToken;
    }

    /**
     * @return The type of dependency relation
     */
    public String getRelation() {
        return relation;
    }

    @Override
    public LocalDate getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "DependencyEntry{" +
               "dependencyId=" + dependencyId +
               ", documentId=" + documentId +
               ", sentenceId=" + sentenceId +
               ", beginChar=" + beginChar +
               ", endChar=" + endChar +
               ", headToken='" + headToken + '\'' +
               ", dependentToken='" + dependentToken + '\'' +
               ", relation='" + relation + '\'' +
               ", timestamp=" + timestamp +
               '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DependencyEntry that = (DependencyEntry) o;
        return dependencyId == that.dependencyId &&
               documentId == that.documentId &&
               sentenceId == that.sentenceId &&
               beginChar == that.beginChar &&
               endChar == that.endChar &&
               Objects.equals(headToken, that.headToken) &&
               Objects.equals(dependentToken, that.dependentToken) &&
               Objects.equals(relation, that.relation) &&
               Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dependencyId, documentId, sentenceId, beginChar, endChar, headToken, dependentToken, relation, timestamp);
    }
} 