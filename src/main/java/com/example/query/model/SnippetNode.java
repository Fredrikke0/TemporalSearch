package com.example.query.model;

/**
 * Represents a SNIPPET expression in the SELECT clause of a query.
 * This node captures the variable to extract a snippet for and the optional window size.
 */
public record SnippetNode(
    String qualifiedVariableName,
    int windowSize
) {

    public static final int DEFAULT_WINDOW_SIZE = -1;

    /**
     * Creates a new SnippetNode with the specified variable and default settings
     * @param qualifiedVariableName The qualified variable name to extract a snippet for
     */
    public SnippetNode(String qualifiedVariableName) {
        this(qualifiedVariableName, DEFAULT_WINDOW_SIZE);
    }

    public SnippetNode {
        if (qualifiedVariableName == null || qualifiedVariableName.isEmpty() || !qualifiedVariableName.contains(".")) {
            throw new IllegalArgumentException("qualifiedVariableName must be a valid qualified name (e.g., alias.var)");
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("SNIPPET(%s", qualifiedVariableName));

        if (windowSize != DEFAULT_WINDOW_SIZE) {
            sb.append(", CHAR_WINDOW=").append(windowSize);
        }

        sb.append(")");
        return sb.toString();
    }

    /**
     * Returns the variable name this snippet is based on.
     *
     * @return The qualified variable name
     */
    public String variableName() {
        return qualifiedVariableName;
    }
}