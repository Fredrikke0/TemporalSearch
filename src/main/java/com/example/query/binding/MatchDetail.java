package com.example.query.binding;

import com.example.core.Position;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a single detailed match from executing a query condition.
 * Includes the matched value, its type, position, and potentially variable binding info.
 * 
 * Now supports SoA-native structure storing individual position attributes directly
 * for better performance during query processing.
 */
public record MatchDetail(
    Object value,
    ValueType valueType,
    // SoA-native fields - store position attributes individually
    int documentId,
    int sentenceId,
    int beginChar,
    int endChar,
    int synonymId,  // -1 for non-stitch positions
    Optional<String> variableName
) {
    /** Canonical constructor for SoA-native structure */
    public MatchDetail {
        Objects.requireNonNull(valueType, "valueType cannot be null");
        Objects.requireNonNull(variableName, "variableName cannot be null");
    }

    // SoA-native convenience constructor for nullable variableName
    public MatchDetail(Object value, ValueType valueType, String variableName, 
                      int documentId, int sentenceId, int beginChar, int endChar, int synonymId) {
        this(value, valueType, documentId, sentenceId, beginChar, endChar, synonymId, Optional.ofNullable(variableName));
    }

    // SoA-native convenience constructor without synonymId (defaults to -1)
    public MatchDetail(Object value, ValueType valueType, String variableName, 
                      int documentId, int sentenceId, int beginChar, int endChar) {
        this(value, valueType, documentId, sentenceId, beginChar, endChar, -1, Optional.ofNullable(variableName));
    }

    // --- Backward compatibility constructors (deprecated) ---
    
    /** @deprecated Use SoA-native constructors instead */
    @Deprecated
    public MatchDetail(Object value, ValueType valueType, Position position, Optional<String> variableName) {
        this(value, valueType, position.getDocumentId(), position.getSentenceId(), 
             position.getBeginPosition(), position.getEndPosition(), -1, variableName);
    }

    /** @deprecated Use SoA-native constructors instead */
    @Deprecated
    public MatchDetail(Object value, ValueType valueType, Position position, String variableName) {
        this(value, valueType, position, Optional.ofNullable(variableName));
    }

    // --- Convenience Getters for Position fields --- 

    /** Document ID */
    public int getDocumentId() { return documentId; }

    /** Sentence ID (-1 if not applicable) */
    public int getSentenceId() { return sentenceId; }

    /** Start position */
    public int getStartPosition() { return beginChar; }

    /** End position */
    public int getEndPosition() { return endChar; }

    /** Begin character offset */
    public int getBeginChar() { return beginChar; }

    /** End character offset */
    public int getEndChar() { return endChar; }

    /** Synonym ID (-1 for non-stitch positions) */
    public int getSynonymId() { return synonymId; }

    // --- Backward compatibility methods ---

    /** @deprecated Use direct field access instead */
    @Deprecated
    public Position position() {
        return new Position(documentId, sentenceId, beginChar, endChar);
    }

    // --- Helper Methods --- 

    /** Check if this detail represents a variable binding */
    public boolean isVariableBinding() { return variableName.isPresent(); }

    @Override
    public String toString() {
        return "MatchDetail{" +
               "value=" + value +
               ", type=" + valueType +
               ", doc=" + documentId +
               ", sent=" + sentenceId +
               ", pos=[" + beginChar + ":" + endChar + "]" +
               (synonymId != -1 ? ", syn=" + synonymId : "") +
               (variableName.isPresent() ? ", var='" + variableName.get() + '\'' : "") +
               "}";
    }

    /**
     * Gets the matched value interpreted as a LocalDate, if applicable.
     * Returns null if the valueType is not DATE or the value is not a LocalDate.
     * @return The matched LocalDate, or null.
     */
    public LocalDate getMatchedDate() {
        if (valueType == ValueType.DATE && value instanceof LocalDate dateValue) {
            return dateValue;
        }
        return null;
    }

    /**
     * Gets the text span covered by the position.
     * @return The text span.
     */
    public String getTextSpan() {
        return "Span[" + beginChar + ":" + endChar + "]"; 
    }
} 