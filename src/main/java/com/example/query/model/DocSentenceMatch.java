package com.example.query.model;

import com.example.core.Position;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Represents a match at either document or sentence level.
 * This is the core data structure for representing matches with different granularity.
 */
public record DocSentenceMatch(
    int documentId,
    int sentenceId,  // -1 for document-level matches
    Map<String, Set<Position>> matchPositions,
    String source,    // The source of this match (e.g., "wikipedia"),
    Map<String, Object> variableValues  // Variable values specific to this match
) {
    /**
     * Constructor for sentence-level match.
     *
     * @param documentId The document ID
     * @param sentenceId The sentence ID
     */
    public DocSentenceMatch(int documentId, int sentenceId) {
        this(documentId, sentenceId, new HashMap<>(), null, new HashMap<>());
    }

    /**
     * Constructor for document-level match.
     *
     * @param documentId The document ID
     */
    public DocSentenceMatch(int documentId) {
        this(documentId, -1);
    }
    
    /**
     * Constructor for sentence-level match with source.
     *
     * @param documentId The document ID
     * @param sentenceId The sentence ID
     * @param source The source of this match
     */
    public DocSentenceMatch(int documentId, int sentenceId, String source) {
        this(documentId, sentenceId, new HashMap<>(), source, new HashMap<>());
    }

    /**
     * Constructor for document-level match with source.
     *
     * @param documentId The document ID
     * @param source The source of this match
     */
    public DocSentenceMatch(int documentId, String source) {
        this(documentId, -1, new HashMap<>(), source, new HashMap<>());
    }

    /**
     * Compact constructor to ensure defensive copy of matchPositions.
     */
    public DocSentenceMatch {
        matchPositions = matchPositions != null ? new HashMap<>(matchPositions) : new HashMap<>();
        variableValues = variableValues != null ? new HashMap<>(variableValues) : new HashMap<>();
    }

    /**
     * Gets the source of this match.
     * 
     * @return The source of this match, or "default" if not specified
     */
    public String getSource() {
        return source != null ? source : "default";
    }

    /**
     * Adds a position to the match for a specific key.
     *
     * @param key The key (e.g., variable name or condition identifier)
     * @param position The position to add
     */
    public void addPosition(String key, Position position) {
        matchPositions.computeIfAbsent(key, k -> new HashSet<>()).add(position);
    }

    /**
     * Adds multiple positions to the match for a specific key.
     *
     * @param key The key (e.g., variable name or condition identifier)
     * @param positions The positions to add
     */
    public void addPositions(String key, Set<Position> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        matchPositions.computeIfAbsent(key, k -> new HashSet<>()).addAll(positions);
    }

    /**
     * Checks if this is a sentence-level match.
     *
     * @return true if this is a sentence-level match, false if document-level
     */
    public boolean isSentenceLevel() {
        return sentenceId >= 0;
    }

    /**
     * Gets all positions for a specific key.
     *
     * @param key The key to get positions for
     * @return Set of positions for the key, or empty set if none
     */
    public Set<Position> getPositions(String key) {
        return matchPositions.getOrDefault(key, Collections.emptySet());
    }

    /**
     * Gets all match positions.
     *
     * @return Map of key to positions
     */
    public Map<String, Set<Position>> getAllPositions() {
        return Collections.unmodifiableMap(matchPositions);
    }

    /**
     * Gets all keys with positions.
     *
     * @return Set of keys
     */
    public Set<String> getKeys() {
        return matchPositions.keySet();
    }
    
    /**
     * Sets a variable value specific to this match.
     * 
     * @param variableName The plain variable name
     * @param value The value to set
     */
    public void setVariableValue(String variableName, Object value) {
        if (variableName == null || value == null) {
            return;
        }
        // Variable name should be plain
        variableValues.put(variableName, value);
    }
    
    /**
     * Gets a variable value specific to this match.
     * 
     * @param variableName The plain variable name
     * @return The variable value, or null if not found
     */
    public Object getVariableValue(String variableName) {
        if (variableName == null) {
            return null;
        }
        // Variable name should be plain
        return variableValues.get(variableName);
    }
    
    /**
     * Gets all variable values for this match.
     * 
     * @return Map of variable name to value
     */
    public Map<String, Object> getVariableValues() {
        return Collections.unmodifiableMap(variableValues);
    }

    public void mergePositions(DocSentenceMatch other) {
        other.matchPositions.forEach((key, positions) -> 
            this.matchPositions.merge(key, positions, (a, b) -> {
                a.addAll(b);
                return a;
            })
        );
        
        // Also merge variable values
        other.variableValues.forEach(this::setVariableValue);
    }

    @Override
    public String toString() {
        return "DocSentenceMatch{" +
               "docId=" + documentId +
               ", sentenceId=" + sentenceId +
               ", matchPositions=" + matchPositions.keySet() + // Simplified for brevity
               ", variableValues=" + variableValues +
               '}';
    }
} 