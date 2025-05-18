package com.example.query.binding;

import com.example.core.Position;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a single detailed match from executing a query condition.
 * Includes the matched value, its type, position, and potentially variable binding info.
 * 
 */
public record MatchDetail(
    Object value,
    ValueType valueType,
    Position position,
    Optional<String> variableName
) {
    /** Canonical constructor */
    public MatchDetail {
        Objects.requireNonNull(valueType, "valueType cannot be null");
        Objects.requireNonNull(position, "position cannot be null");
        Objects.requireNonNull(variableName, "variableName cannot be null");
    }

    // Convenience constructor for nullable variableName
    public MatchDetail(Object value, ValueType valueType, Position position, String variableName) {
        this(value, valueType, position, Optional.ofNullable(variableName));
    }

    // --- Convenience Getters for Position fields --- 

    /** Document ID */
    public int getDocumentId() { return position.getDocumentId(); }

    /** Sentence ID (-1 if not applicable) */
    public int getSentenceId() { return position.getSentenceId(); }

    /** Start position */
    public int getStartPosition() { return position.getBeginPosition(); }

    /** End position */
    public int getEndPosition() { return position.getEndPosition(); }

    // --- Helper Methods --- 

    /** Check if this detail represents a variable binding */
    public boolean isVariableBinding() { return variableName.isPresent(); }

    @Override
    public String toString() {
        return "MatchDetail{" +
               "value=" + value +
               ", type=" + valueType +
               ", pos=" + position +
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
        return "Span[" + getStartPosition() + ":" + getEndPosition() + "]"; 
    }
} 