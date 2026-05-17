package com.example.query.model;

/**
 * Represents a structural column like {@code alias.TITLE} or
 * {@code alias.TIMESTAMP}.
 */
public record SelectedStructural(String alias, StructuralField field) implements SelectedColumn {

    public SelectedStructural {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be null or blank");
        }
        if (field == null) {
            throw new IllegalArgumentException("field must not be null");
        }
    }

    @Override
    public String columnName() {
        return alias + "." + field.name();
    }

    @Override
    public String toString() {
        return columnName();
    }
}
