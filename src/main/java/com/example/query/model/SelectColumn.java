package com.example.query.model;

import java.util.List;
import java.util.Map;

import com.example.core.IndexAccessInterface;
import com.example.query.executor.CellResult;

import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;

/**
 * Interface for all column types that can appear in a SELECT clause.
 * This includes variables, snippets, and other column expressions.
 */
public interface SelectColumn {
    /**
     * Gets the name of this column as it should appear in results.
     * 
     * @return The column name
     */
    String getColumnName();

    /**
     * Creates a Tablesaw column for this select column.
     * 
     * @return A new Tablesaw column
     */
    Column<?> createColumn();

    /**
     * Populates the column with data for a given result unit (document or
     * sentence).
     * Implementations will extract necessary data from the CellResult using the
     * provided indices.
     *
     * @param table          The Tablesaw table to populate
     * @param rowIndex       The current row index in the table to set data for
     * @param result         The CellResult containing all match data
     * @param bindingIndices A list of indices into the result's bindings that
     *                       pertain to the current table row
     * @param source         The source name from the query (e.g., corpus name)
     * @param indexes        A map of available indexes for fetching additional data
     *                       if needed (e.g., document text for snippets)
     * @param query          The original query object, for context (e.g. select
     *                       columns, date format)
     * @param contextCache   A map for caching data across multiple calls for the
     *                       same row/document (e.g. fetched document text)
     */
    void populateColumn(Table table, int rowIndex,
            CellResult result, List<Integer> bindingIndices,
            String source,
            Map<String, IndexAccessInterface> indexes,
            Query query,
            Map<String, Object> contextCache);

    /**
     * Returns the string representation of the select column (e.g., variable name,
     * function call).
     * This is used in query string generation and potentially in column naming if
     * getColumnName is simple.
     */
    @Override
    String toString();
}
