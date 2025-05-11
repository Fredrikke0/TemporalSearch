package com.example.query.model;

import com.example.core.IndexAccessInterface;
import com.example.query.binding.MatchDetail;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;
import tech.tablesaw.api.ColumnType;

import java.util.List;
import java.util.Map;

/**
 * Interface for all column types that can appear in a SELECT clause.
 * This includes variables, snippets, and other column expressions.
 */
public interface SelectColumn {
    /**
     * Gets the name of this column as it should appear in results.
     * @return The column name
     */
    String getColumnName();
    
    /**
     * Creates a Tablesaw column for this select column.
     * @return A new Tablesaw column
     */
    Column<?> createColumn();
    
    /**
     * Populates the column with data for a given result unit (document or sentence).
     * Implementations should cast detailsForUnit to the appropriate type (e.g., List<MatchDetail> or List<JoinedMatch>)
     * based on the column's purpose.
     * 
     * @param table The table containing the column
     * @param rowIndex The row index to populate
     * @param detailsForUnit A list of all details (MatchDetail or JoinedMatch) belonging to the current result unit
     * @param source The source name (corpus) for this detail
     * @param indexes The indexes for additional document information
     */
    void populateColumn(Table table, int rowIndex, List<?> detailsForUnit, 
                        String source,
                        Map<String, IndexAccessInterface> indexes);

    /**
     * Populates the column with data for a given result unit (document or sentence).
     * This version includes the Query object for context, especially for joins.
     * 
     * @param table The table containing the column
     * @param rowIndex The row index to populate
     * @param detailsForUnit A list of all details (MatchDetail or JoinedMatch) belonging to the current result unit
     * @param source The source name (corpus) for this detail
     * @param indexes The indexes for additional document information
     * @param query The original Query object for context (e.g., alias mapping)
     * @param contextCache A cache for storing and retrieving data during population, like titles.
     */
    default void populateColumn(Table table, int rowIndex, List<?> detailsForUnit, 
                                String source, Map<String, IndexAccessInterface> indexes, Query query, Map<String, Object> contextCache) {
        // Default implementation calls the older version for backward compatibility
        // This might now cause a compile error if the older version doesn't exist or has a different signature.
        // For now, let's assume the older one is still intended to be called if a class doesn't override this new one.
        // However, classes *implementing* this will need to be updated.
        // The most robust change here would be to make this method abstract and remove the default body,
        // forcing all implementers to update. But for now, keeping the default.
        populateColumn(table, rowIndex, detailsForUnit, source, indexes); // This call is problematic now.
    }

    /**
     * Populates the column with data for a given result unit (document or sentence).
     * This version includes the Query object for context, especially for joins.
     * 
     * @param table The table containing the column
     * @param rowIndex The row index to populate
     * @param detailsForUnit A list of all details (MatchDetail or JoinedMatch) belonging to the current result unit
     * @param source The source name (corpus) for this detail
     * @param indexes The indexes for additional document information
     * @param query The original Query object for context (e.g., alias mapping)
     */
    default void populateColumn(Table table, int rowIndex, List<?> detailsForUnit, 
                                String source, Map<String, IndexAccessInterface> indexes, Query query) {
        // Default implementation calls the older version for backward compatibility
        populateColumn(table, rowIndex, detailsForUnit, source, indexes);
    }
} 