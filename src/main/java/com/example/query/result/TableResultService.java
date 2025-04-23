package com.example.query.result;

import com.example.query.executor.QueryResult;
import com.example.query.executor.SubqueryContext;
import com.example.query.model.Query;
import com.example.query.model.SelectColumn;
import com.example.query.model.CountColumn;
import com.example.query.model.VariableColumn;
import com.example.core.IndexAccessInterface;
import com.example.query.binding.MatchDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.tablesaw.api.*;
import tech.tablesaw.columns.Column;
import tech.tablesaw.io.csv.CsvWriteOptions;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Function;

/**
 * Service for converting query results to Tablesaw Tables.
 * Replaces the previous ResultGenerator with a simpler implementation
 * that leverages Tablesaw for data representation and formatting.
 * 
 * Now supports joining results from subqueries based on temporal relationships.
 */
public class TableResultService {
    private static final Logger logger = LoggerFactory.getLogger(TableResultService.class);

    private static final String DEFAULT_DOC_ID_COL = "document_id";
    private static final String LEFT_DOC_ID_COL = "left_document_id";
    private static final String RIGHT_DOC_ID_COL = "right_document_id";
    private static final String DEFAULT_SENT_ID_COL = "sentence_id";
    private static final String LEFT_SENT_ID_COL = "left_sentence_id";
    private static final String RIGHT_SENT_ID_COL = "right_sentence_id";

    /**
     * Creates a new TableResultService with default configuration.
     */
    public TableResultService() {
    }

    /**
     * Creates a new TableResultService with custom database path.
     *
     * @param dbPath The path to the database file (not used, kept for backwards compatibility)
     */
    public TableResultService(String dbPath) {
    }

    /**
     * Converts query results to a Tablesaw Table.
     *
     * @param query The original query
     * @param result The QueryResult object containing match details.
     * @param indexes Map of indexes (using interface) to retrieve additional document information
     * @return A Tablesaw Table containing the query results
     * @throws ResultGenerationException if an error occurs
     */
    public Table generateTable(
            Query query,
            QueryResult result,
            Map<String, IndexAccessInterface> indexes
    ) throws ResultGenerationException {
        return generateTable(query, result, indexes, new SubqueryContext());
    }
    
    /**
     * Converts query results to a Tablesaw Table, including subquery handling.
     *
     * @param query The original query
     * @param result The QueryResult object containing match details.
     * @param indexes Map of indexes (using interface) to retrieve additional document information
     * @param subqueryContext Context containing subquery results
     * @return A Tablesaw Table containing the query results
     * @throws ResultGenerationException if an error occurs
     */
    public Table generateTable(
            Query query,
            QueryResult result,
            Map<String, IndexAccessInterface> indexes,
            SubqueryContext subqueryContext
    ) throws ResultGenerationException {
        Query.Granularity granularity = query.granularity();
        int initialDetailCount = (result != null && result.getAllDetails() != null) ? result.getAllDetails().size() : 0;
        logger.info("Processing {} initial matching details at {} granularity",
                initialDetailCount, granularity);

        if (result == null || result.getAllDetails() == null || result.getAllDetails().isEmpty()) {
             logger.warn("Input QueryResult is null or empty, returning empty table.");
             return Table.create("EmptyQueryResults"); // Return an empty table
        }

        try {
            boolean isJoinQuery = query.joinCondition().isPresent(); // Check if it's a JOIN query
            
            if (isJoinQuery) {
                throw new ResultGenerationException(
                    "generateTable(QueryResult) does not support join queries. Use generateTableForJoin(List<JoinedMatch>) instead.",
                    "table_result_service",
                    ResultGenerationException.ErrorType.INTERNAL_ERROR
                );
            }

            // Proceed directly to generating table from the QueryResult
            List<Column<?>> columns = new ArrayList<>();
            List<SelectColumn> selectColumns = query.selectColumns();
            
             // Ensure default columns if SELECT * or no SELECT clause
            if (selectColumns == null || selectColumns.isEmpty() || selectColumns.stream().anyMatch(sc -> "*".equals(sc.getColumnName()))) {
                logger.debug("No specific columns selected or * found, using default columns.");
                selectColumns = createDefaultSelectColumns(result); // Create default based on QueryResult content
            }

            // Create table structure based on select columns
            for (SelectColumn selectColumn : selectColumns) {
                columns.add(selectColumn.createColumn());
            }
            
            // Always include document_id and sentence_id based on join status and granularity
            // Use IntColumn for IDs
            if (granularity == Query.Granularity.SENTENCE) {
                if (columns.stream().noneMatch(c -> c.name().equalsIgnoreCase(LEFT_SENT_ID_COL))) {
                     columns.add(2, IntColumn.create(LEFT_SENT_ID_COL)); // Add after doc IDs
                }
                if (columns.stream().noneMatch(c -> c.name().equalsIgnoreCase(RIGHT_SENT_ID_COL))) {
                     columns.add(3, IntColumn.create(RIGHT_SENT_ID_COL)); // Add after left sentence ID
                }
            } else { // Not a JOIN query
                 if (columns.stream().noneMatch(c -> c.name().equalsIgnoreCase(DEFAULT_DOC_ID_COL))) {
                     columns.add(0, IntColumn.create(DEFAULT_DOC_ID_COL)); // Add at the beginning
                 }
                 if (granularity == Query.Granularity.SENTENCE && columns.stream().noneMatch(c -> c.name().equalsIgnoreCase(DEFAULT_SENT_ID_COL))) {
                     columns.add(1, IntColumn.create(DEFAULT_SENT_ID_COL)); // Add after doc ID
                 }
            }
            
            // Create the table
            Table table = Table.create("QueryResults");
            Map<String, Column<?>> columnMap = new HashMap<>();
            for (Column<?> column : columns) {
                if (!table.columnNames().contains(column.name())) { // Avoid adding duplicates if defaults overlap
                     table.addColumns(column);
                     columnMap.put(column.name(), column); // Store for easy access
                }
            }
            
            // Validate order by columns
            for (String orderColumn : query.orderBy()) {
                String columnName = orderColumn.startsWith("-") ? orderColumn.substring(1) : orderColumn;
                if (!table.columnNames().contains(columnName)) {
                    throw new ResultGenerationException(
                        String.format("Cannot order by column '%s' - not found in table columns: %s", columnName, table.columnNames()),
                        "table_result_service",
                        ResultGenerationException.ErrorType.INTERNAL_ERROR
                    );
                }
            }
            
            // Populate the table with data
            // 1. Group MatchDetails by the result unit (document or sentence ID)
            Map<?, List<MatchDetail>> groupedDetails; // Use wildcard for key type
            
            Function<MatchDetail, Object> groupingKeyExtractor;
            if (granularity == Query.Granularity.SENTENCE) {
                // Group by composite key for sentence granularity
                groupingKeyExtractor = detail -> new Pair<>(detail.getDocumentId(), detail.getSentenceId()); // Use getters
            } else { // Default to DOCUMENT granularity
                groupingKeyExtractor = MatchDetail::getDocumentId; // Use getter method reference
            }
            groupedDetails = result.getAllDetails().stream()
                                 .filter(Objects::nonNull) // Add null check for safety
                                 .collect(Collectors.groupingBy(groupingKeyExtractor));
            
            int finalRowCount = groupedDetails.size(); // Rows after grouping
            logger.info("Grouped into {} final result units (granularity: {})", 
                     finalRowCount, granularity);
            
            // Get the source name once
            String source = query.source();
            
            // 2. Iterate through each group (representing one row in the output)
            for (List<MatchDetail> detailsForUnit : groupedDetails.values()) {
                if (detailsForUnit.isEmpty()) continue; // Should not happen, but safe check
                
                int rowIndex = table.rowCount();
                table.appendRow(); // Append empty row first
                
                // Get representative docId/sentenceId from the first detail in the group
                MatchDetail representativeDetail = detailsForUnit.get(0);
                // No need to extract docId/sentenceId here, done during population
                
                // 3. Populate columns for this row using the list of details
                for (SelectColumn selectColumn : selectColumns) {
                    Column<?> tableCol = columnMap.get(selectColumn.getColumnName());
                    if (tableCol != null) {
                        // Pass the whole list of details for this unit
                        selectColumn.populateColumn(table, rowIndex, detailsForUnit, source, indexes);
                    } else {
                        logger.warn("Column '{}' defined in SelectColumn but not found in table structure?", selectColumn.getColumnName());
                    }
                }
                
                // Set document_id and sentence_id (if columns exist) based on join status
                if (granularity == Query.Granularity.SENTENCE) {
                    Column<?> leftSentIdCol = columnMap.get(LEFT_SENT_ID_COL);
                    if (leftSentIdCol instanceof IntColumn ic) { ic.set(rowIndex, representativeDetail.getSentenceId()); }
                    
                    Column<?> rightSentIdCol = columnMap.get(RIGHT_SENT_ID_COL);
                    if (rightSentIdCol instanceof IntColumn ic) { ic.set(rowIndex, representativeDetail.getSentenceId()); }
                } else { // Not a JOIN query
                    // Populate default ID columns
                    Column<?> docIdCol = columnMap.get(DEFAULT_DOC_ID_COL);
                    if (docIdCol instanceof IntColumn ic) { ic.set(rowIndex, representativeDetail.getDocumentId()); }
                    
                    if (granularity == Query.Granularity.SENTENCE) {
                         Column<?> sentIdCol = columnMap.get(DEFAULT_SENT_ID_COL);
                         if (sentIdCol instanceof IntColumn ic) { ic.set(rowIndex, representativeDetail.getSentenceId()); }
                    }
                }
            }
            
            // Apply count aggregations if necessary
            boolean hasCountColumn = selectColumns.stream().anyMatch(col -> col instanceof CountColumn);
            if (hasCountColumn) {
                table = CountColumn.applyCountAggregations(table);
            }
            
            // Apply ordering if specified
            if (!query.orderBy().isEmpty()) {
                logger.debug("Ordering results by {} criteria", query.orderBy().size());
                table = applyOrdering(table, query.orderBy());
            }
            
            // Apply limit if specified - Apply AFTER grouping and ordering
            if (query.limit().isPresent()) {
                int limit = query.limit().get();
                // Check limit against final row count
                if (limit > 0 && limit < table.rowCount()) { 
                    // Use INFO level for limit application
                    logger.info("Limiting final {} rows to {}", table.rowCount(), limit);
                    table = table.first(limit);
                } else {
                     // Use DEBUG level for non-application
                     logger.debug("Limit {} is not less than or equal to 0, or not less than final row count {}, no limit applied.", limit, table.rowCount());
                }
            }
            
            // Use INFO level for final table stats
            logger.info("Generated final table with {} columns and {} rows", 
                    table.columnCount(), table.rowCount());
            
            return table;
        } catch (Exception e) {
            // Log the specific detail causing the issue if possible (though harder now)
            logger.error("Error during table generation: {}", e.getMessage(), e);
            throw new ResultGenerationException(
                    "Failed to generate table: " + e.getMessage(),
                    e,
                    "table_result_service",
                    ResultGenerationException.ErrorType.INTERNAL_ERROR
            );
        }
    }

    /**
     * Applies ordering to a Tablesaw table based on order specifications.
     *
     * @param table The table to order
     * @param orderColumns The order columns (prefix with "-" for descending order)
     * @return The ordered table
     */
    private Table applyOrdering(Table table, List<String> orderColumns) {
        if (orderColumns.isEmpty()) {
            return table;
        }
        
        // Use Tablesaw's sortOn method with the column names
        logger.debug("Sorting table on columns: {}", orderColumns);
        return table.sortOn(orderColumns.toArray(new String[0]));
    }

    /**
     * Sorts a table by the given columns.
     * 
     * This method provides a direct way to sort tables using Tablesaw's column syntax.
     * Columns can be prefixed with "-" to indicate descending order.
     * 
     * @param table The table to sort
     * @param columns The columns to sort by
     * @return The sorted table
     */
    public Table sortTable(Table table, String... columns) {
        if (columns == null || columns.length == 0) {
            return table;
        }
        
        logger.debug("Sorting table on columns: {}", Arrays.toString(columns));
        return table.sortOn(columns);
    }

    /**
     * Exports a Tablesaw table to a file in the specified format.
     *
     * @param table The table to export
     * @param format The export format (csv, json, html)
     * @param filename The filename to export to
     * @throws IOException if an error occurs during export
     */
    public void exportTable(Table table, String format, String filename) throws IOException {
        switch (format.toLowerCase()) {
            case "csv" -> {
                CsvWriteOptions options = CsvWriteOptions.builder(filename)
                        .header(true)
                        .build();
                table.write().csv(options);
            }
            case "json", "html" -> {
                CsvWriteOptions options = CsvWriteOptions.builder(filename)
                        .header(true)
                        .build();
                table.write().csv(options);
                logger.warn("Exporting as CSV instead of {} - full support requires additional dependencies", format);
            }
            default -> throw new IllegalArgumentException("Unsupported export format: " + format);
        }
    }

    /**
     * Gets a formatted string representation of a Tablesaw table.
     *
     * @param table The table to format
     * @return A string representation of the table
     */
    public String formatTable(Table table) {
        int totalRows = table.rowCount();
        int displayedRows = Math.min(totalRows, 20); // Tablesaw typically shows ~20 rows by default
        
        StringBuilder sb = new StringBuilder();
        sb.append(table.print());
        
        // Add a note about the preview if there are more rows than displayed
        if (totalRows > displayedRows) {
            sb.append("\n\nNote: This is a preview showing ").append(displayedRows)
              .append(" of ").append(totalRows).append(" total rows. Use export options to view all data.");
            sb.append("\nTo export all results, use: --export=csv:results.csv");
        }
        
        return sb.toString();
    }

    // Helper class for Pair grouping key when granularity is SENTENCE
    private static record Pair<K, V>(K key, V value) {}

    /**
     * Creates default SelectColumn list based on QueryResult content.
     * Includes document_id, sentence_id (if applicable), and any variables found.
     */
     private List<SelectColumn> createDefaultSelectColumns(QueryResult result) {
         List<SelectColumn> defaultColumns = new ArrayList<>();

         // Always add document_id
         defaultColumns.add(new com.example.query.model.SelectColumn() {
             @Override
             public String getColumnName() { return "document_id"; }
             @Override
             public tech.tablesaw.columns.Column<?> createColumn() { return tech.tablesaw.api.IntColumn.create("document_id"); }
             @Override
             public void populateColumn(tech.tablesaw.api.Table table, int rowIndex, java.util.List<?> detailsForUnit, String source, java.util.Map<String, com.example.core.IndexAccessInterface> indexes) {
                 if (detailsForUnit != null && !detailsForUnit.isEmpty()) {
                     com.example.query.binding.MatchDetail detail = (com.example.query.binding.MatchDetail) detailsForUnit.get(0);
                     ((tech.tablesaw.api.IntColumn) table.column("document_id")).set(rowIndex, detail.getDocumentId());
                 }
             }
         });

         // Add sentence_id if any detail has a valid sentence id (>=0)
         boolean hasSentence = result.getAllDetails().stream().anyMatch(d -> d.getSentenceId() >= 0);
         if (hasSentence) {
             defaultColumns.add(new com.example.query.model.SelectColumn() {
                 @Override
                 public String getColumnName() { return "sentence_id"; }
                 @Override
                 public tech.tablesaw.columns.Column<?> createColumn() { return tech.tablesaw.api.IntColumn.create("sentence_id"); }
                 @Override
                 public void populateColumn(tech.tablesaw.api.Table table, int rowIndex, java.util.List<?> detailsForUnit, String source, java.util.Map<String, com.example.core.IndexAccessInterface> indexes) {
                     if (detailsForUnit != null && !detailsForUnit.isEmpty()) {
                         com.example.query.binding.MatchDetail detail = (com.example.query.binding.MatchDetail) detailsForUnit.get(0);
                         ((tech.tablesaw.api.IntColumn) table.column("sentence_id")).set(rowIndex, detail.getSentenceId());
                     }
                 }
             });
         }

         // Add variable columns
         Set<String> variableNames = result.getAllDetails().stream()
             .filter(Objects::nonNull)
             .map(com.example.query.binding.MatchDetail::variableName)
             .filter(Optional::isPresent)
             .map(Optional::get)
             .collect(Collectors.toSet());

         for (String varName : variableNames) {
              defaultColumns.add(new VariableColumn(varName));
         }
         logger.debug("Created default select columns: {}", defaultColumns.stream().map(SelectColumn::getColumnName).toList());
         return defaultColumns;
     }

    /**
     * Converts join results (List<JoinedMatch>) to a Tablesaw Table.
     *
     * @param query The original query
     * @param joinedResults The list of JoinedMatch objects from join logic
     * @param indexes Map of indexes (using interface) to retrieve additional document information
     * @return A Tablesaw Table containing the join results
     * @throws ResultGenerationException if an error occurs
     */
    public Table generateTableForJoin(
            Query query,
            List<com.example.query.binding.JoinedMatch> joinedResults,
            Map<String, IndexAccessInterface> indexes
    ) throws ResultGenerationException {
        Query.Granularity granularity = query.granularity();
        int initialDetailCount = (joinedResults != null) ? joinedResults.size() : 0;
        logger.info("Processing {} joined match pairs at {} granularity", initialDetailCount, granularity);

        if (joinedResults == null || joinedResults.isEmpty()) {
            logger.warn("Input join result is null or empty, returning empty table.");
            return Table.create("EmptyJoinResults");
        }

        try {
            List<Column<?>> columns = new ArrayList<>();
            List<SelectColumn> selectColumns = query.selectColumns();
            boolean isJoinQuery = true;

            // Ensure default columns if SELECT * or no SELECT clause
            if (selectColumns == null || selectColumns.isEmpty() || selectColumns.stream().anyMatch(sc -> "*".equals(sc.getColumnName()))) {
                logger.debug("No specific columns selected or * found, using default columns for join.");
                // For join, default columns could be left/right variable names if present
                Set<String> leftVars = joinedResults.stream().map(com.example.query.binding.JoinedMatch::getLeftVariableName).filter(Objects::nonNull).collect(Collectors.toSet());
                Set<String> rightVars = joinedResults.stream().map(com.example.query.binding.JoinedMatch::getRightVariableName).filter(Objects::nonNull).collect(Collectors.toSet());
                selectColumns = new ArrayList<>();
                for (String var : leftVars) selectColumns.add(new VariableColumn(var));
                for (String var : rightVars) selectColumns.add(new VariableColumn(var));
            }

            // Create table structure based on select columns
            for (SelectColumn selectColumn : selectColumns) {
                columns.add(selectColumn.createColumn());
            }

            // Always include left/right document_id and sentence_id columns
            if (columns.stream().noneMatch(c -> c.name().equalsIgnoreCase(LEFT_DOC_ID_COL))) {
                columns.add(0, IntColumn.create(LEFT_DOC_ID_COL));
            }
            if (columns.stream().noneMatch(c -> c.name().equalsIgnoreCase(RIGHT_DOC_ID_COL))) {
                columns.add(1, IntColumn.create(RIGHT_DOC_ID_COL));
            }
            if (granularity == Query.Granularity.SENTENCE) {
                if (columns.stream().noneMatch(c -> c.name().equalsIgnoreCase(LEFT_SENT_ID_COL))) {
                    columns.add(2, IntColumn.create(LEFT_SENT_ID_COL));
                }
                if (columns.stream().noneMatch(c -> c.name().equalsIgnoreCase(RIGHT_SENT_ID_COL))) {
                    columns.add(3, IntColumn.create(RIGHT_SENT_ID_COL));
                }
            }

            Table table = Table.create("JoinQueryResults");
            Map<String, Column<?>> columnMap = new HashMap<>();
            for (Column<?> column : columns) {
                if (!table.columnNames().contains(column.name())) {
                    table.addColumns(column);
                    columnMap.put(column.name(), column);
                }
            }

            // Validate order by columns
            for (String orderColumn : query.orderBy()) {
                String columnName = orderColumn.startsWith("-") ? orderColumn.substring(1) : orderColumn;
                if (!table.columnNames().contains(columnName)) {
                    throw new ResultGenerationException(
                        String.format("Cannot order by column '%s' - not found in table columns: %s", columnName, table.columnNames()),
                        "table_result_service",
                        ResultGenerationException.ErrorType.INTERNAL_ERROR
                    );
                }
            }

            // Populate the table with data
            for (com.example.query.binding.JoinedMatch joined : joinedResults) {
                int rowIndex = table.rowCount();
                table.appendRow();

                // Populate select columns
                for (SelectColumn selectColumn : selectColumns) {
                    Column<?> tableCol = columnMap.get(selectColumn.getColumnName());
                    if (tableCol != null) {
                        // For join, pass both left and right as a list for compatibility
                        List<MatchDetail> details = List.of(joined.left(), joined.right());
                        selectColumn.populateColumn(table, rowIndex, details, query.source(), indexes);
                    } else {
                        logger.warn("Column '{}' defined in SelectColumn but not found in table structure?", selectColumn.getColumnName());
                    }
                }

                // Set left/right document_id and sentence_id columns
                Column<?> leftDocIdCol = columnMap.get(LEFT_DOC_ID_COL);
                if (leftDocIdCol instanceof IntColumn ic) { ic.set(rowIndex, joined.left().getDocumentId()); }

                Column<?> rightDocIdCol = columnMap.get(RIGHT_DOC_ID_COL);
                if (rightDocIdCol instanceof IntColumn ic) { ic.set(rowIndex, joined.right().getDocumentId()); }

                if (granularity == Query.Granularity.SENTENCE) {
                    Column<?> leftSentIdCol = columnMap.get(LEFT_SENT_ID_COL);
                    if (leftSentIdCol instanceof IntColumn ic) { ic.set(rowIndex, joined.left().getSentenceId()); }

                    Column<?> rightSentIdCol = columnMap.get(RIGHT_SENT_ID_COL);
                    if (rightSentIdCol instanceof IntColumn ic) { ic.set(rowIndex, joined.right().getSentenceId()); }
                }
            }

            // Apply count aggregations if necessary
            boolean hasCountColumn = selectColumns.stream().anyMatch(col -> col instanceof CountColumn);
            if (hasCountColumn) {
                table = CountColumn.applyCountAggregations(table);
            }

            // Apply ordering if specified
            if (!query.orderBy().isEmpty()) {
                logger.debug("Ordering join results by {} criteria", query.orderBy().size());
                table = applyOrdering(table, query.orderBy());
            }

            // Apply limit if specified
            if (query.limit().isPresent()) {
                int limit = query.limit().get();
                if (limit > 0 && limit < table.rowCount()) {
                    logger.info("Limiting final {} rows to {} (join)", table.rowCount(), limit);
                    table = table.first(limit);
                } else {
                    logger.debug("Limit {} is not less than or equal to 0, or not less than final row count {}, no limit applied (join).", limit, table.rowCount());
                }
            }

            logger.info("Generated join table with {} columns and {} rows", table.columnCount(), table.rowCount());
            return table;
        } catch (Exception e) {
            logger.error("Error during join table generation: {}", e.getMessage(), e);
            throw new ResultGenerationException(
                    "Failed to generate join table: " + e.getMessage(),
                    e,
                    "table_result_service",
                    ResultGenerationException.ErrorType.INTERNAL_ERROR
            );
        }
    }
} 