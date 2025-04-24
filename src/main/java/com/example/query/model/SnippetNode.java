package com.example.query.model;

/**
 * Represents a SNIPPET expression in the SELECT clause of a query.
 * This node captures the variable to extract a snippet for and the optional window size.
 */
public record SnippetNode(
    String qualifiedVariableName,
    int windowSize,
    String highlightStyle,
    boolean showSentenceBoundaries
) {
    
    public static final int DEFAULT_WINDOW_SIZE = 4;
    public static final String DEFAULT_HIGHLIGHT_STYLE = "**";  // Default bold highlighting
    public static final boolean DEFAULT_SHOW_SENTENCE_BOUNDARIES = false;
    
    /**
     * Creates a new SnippetNode with the specified variable and default settings
     * @param variable The variable to extract a snippet for
     */
    public SnippetNode(String variable) {
        this(variable, DEFAULT_WINDOW_SIZE, DEFAULT_HIGHLIGHT_STYLE, DEFAULT_SHOW_SENTENCE_BOUNDARIES);
    }
    
    /**
     * Creates a new SnippetNode with the specified variable and window size
     * @param variable The variable to extract a snippet for
     * @param windowSize The number of sentences to include on each side of the match
     */
    public SnippetNode(String variable, int windowSize) {
        this(variable, windowSize, DEFAULT_HIGHLIGHT_STYLE, DEFAULT_SHOW_SENTENCE_BOUNDARIES);
    }
    
    public SnippetNode {
        if (qualifiedVariableName == null || qualifiedVariableName.isEmpty() || !qualifiedVariableName.contains(".")) {
            throw new IllegalArgumentException("qualifiedVariableName must be a valid qualified name (e.g., alias.var)");
        }
        if (windowSize < 0 || windowSize > 5) {
            throw new IllegalArgumentException("windowSize must be between 0 and 5");
        }
        if (highlightStyle == null || highlightStyle.isEmpty()) {
            throw new IllegalArgumentException("highlightStyle must not be null or empty");
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("SNIPPET(%s", qualifiedVariableName));
        
        if (windowSize != DEFAULT_WINDOW_SIZE || 
            !highlightStyle.equals(DEFAULT_HIGHLIGHT_STYLE) || 
            showSentenceBoundaries != DEFAULT_SHOW_SENTENCE_BOUNDARIES) {
            
            sb.append(", window=").append(windowSize);
            
            if (!highlightStyle.equals(DEFAULT_HIGHLIGHT_STYLE) || 
                showSentenceBoundaries != DEFAULT_SHOW_SENTENCE_BOUNDARIES) {
                
                sb.append(", style=").append(highlightStyle);
                
                if (showSentenceBoundaries != DEFAULT_SHOW_SENTENCE_BOUNDARIES) {
                    sb.append(", boundaries=").append(showSentenceBoundaries);
                }
            }
        }
        
        sb.append(")");
        return sb.toString();
    }

    /**
     * Returns the variable name this snippet is based on.
     * 
     * @return The qualified variable name, or null if not set
     */
    public String variableName() {
        return qualifiedVariableName;
    }
} 