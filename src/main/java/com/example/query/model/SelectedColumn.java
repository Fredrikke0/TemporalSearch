package com.example.query.model;

/**
 * Sealed interface representing a column in the SELECT clause.
 * All variants are records — immutable, with equals/hashCode/toString free.
 * The interface is sealed so the compiler enforces exhaustiveness in switch
 * expressions.
 */
public sealed interface SelectedColumn
        permits SelectedVariable, SelectedStructural, SelectedCount, SelectedSnippet {

    /**
     * Returns the column name as it should appear in result output.
     */
    String columnName();
}
