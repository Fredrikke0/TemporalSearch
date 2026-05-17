package com.example.query.model;

/**
 * Represents a variable column in the SELECT clause.
 * The qualified name has the form {@code alias.variableName}.
 */
public record SelectedVariable(String qualifiedName) implements SelectedColumn {

    public SelectedVariable {
        if (qualifiedName == null || !qualifiedName.contains(".")) {
            throw new IllegalArgumentException(
                    "SelectedVariable requires a qualified name (alias.var), got: " + qualifiedName);
        }
    }

    @Override
    public String columnName() {
        return qualifiedName;
    }

    @Override
    public String toString() {
        return qualifiedName;
    }
}
