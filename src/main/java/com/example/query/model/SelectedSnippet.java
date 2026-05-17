package com.example.query.model;

/**
 * Represents a SNIPPET expression in the SELECT clause.
 * Generates a text snippet centered around the value of a specified variable.
 */
public record SelectedSnippet(String qualifiedVariableName, int windowSize)
        implements SelectedColumn {

    public static final int DEFAULT_WINDOW = 40;

    public SelectedSnippet {
        if (qualifiedVariableName == null || !qualifiedVariableName.contains(".")) {
            throw new IllegalArgumentException(
                    "SelectedSnippet requires a qualified variable name (alias.var), got: "
                            + qualifiedVariableName);
        }
        if (windowSize < 0) {
            windowSize = DEFAULT_WINDOW;
        }
    }

    /**
     * Creates a SelectedSnippet with the default window size.
     */
    public SelectedSnippet(String qualifiedVariableName) {
        this(qualifiedVariableName, DEFAULT_WINDOW);
    }

    @Override
    public String columnName() {
        return "snippet_" + qualifiedVariableName.replace('.', '_');
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SNIPPET(").append(qualifiedVariableName);
        if (windowSize != DEFAULT_WINDOW) {
            sb.append(", CHAR_WINDOW=").append(windowSize);
        }
        sb.append(")");
        return sb.toString();
    }
}
