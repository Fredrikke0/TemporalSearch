package com.example.query.result;

import com.example.query.executor.QueryResult;
import com.example.query.executor.SubqueryContext;
import com.example.query.model.Query;
import com.example.query.model.SelectColumn;
import com.example.query.model.CountColumn;
import com.example.query.model.VariableColumn;
import com.example.query.model.StructuralColumn;
import com.example.query.model.SnippetColumn;
import com.example.core.IndexAccessInterface;
import com.example.query.binding.MatchDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.tablesaw.api.*;
import tech.tablesaw.columns.Column;
import tech.tablesaw.io.csv.CsvWriteOptions;
import tech.tablesaw.selection.Selection;

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
                selectColumns = createDefaultSelectColumns(query, result); 
            }

            // If, after default generation, there are still no columns to select, return an empty table.
            if (selectColumns.isEmpty()) {
                logger.warn("Query resulted in matches, but no columns were selected (explicitly or by default). Returning empty table.");
                return Table.create("EmptyQueryResults_NoColumns");
            }

            // Create the table
            Table table = Table.create("QueryResults");
            Map<String, Column<?>> columnMap = new HashMap<>();
            Map<String, Object> contextCache = new HashMap<>(); // Initialize context cache
            
            // Determine all columns that need to be present in the table for data population
            // This includes explicitly selected columns and arguments to functions like COUNT(UNIQUE var) or SNIPPET(var)
            Set<SelectColumn> effectiveDataColumns = new LinkedHashSet<>();
            for (SelectColumn sc : selectColumns) {
                if (sc instanceof VariableColumn || sc instanceof StructuralColumn) {
                    effectiveDataColumns.add(sc);
                } else if (sc instanceof SnippetColumn snippetCol) {
                    // Add the snippet variable itself as a data column if not already effectively selected
                    // This assumes SnippetColumn's populate might need it or it's good for intermediate table
                    effectiveDataColumns.add(new VariableColumn(snippetCol.getVariableName()));
                     // Also add the SnippetColumn itself for later (it creates its own display column)
                } else if (sc instanceof CountColumn countCol && countCol.getVariableNameForValidation() != null) {
                    // For COUNT(UNIQUE var), ensure 'var' is available as a data column
                    effectiveDataColumns.add(new VariableColumn(countCol.getVariableNameForValidation()));
                }
                // Note: The original SelectColumn (like CountColumn or SnippetColumn) will also be processed
                // later to create its specific output column structure if different from the data column.
            }
            
            // Add all selectColumns to ensure their final structure is also prepared,
            // especially for aggregates that will be populated by applyGroupBy or special formatting.
            // effectiveDataColumns ensures the underlying data is present.
            // selectColumns ensures the final output shape is prepared.
            Set<SelectColumn> allColumnsToConsiderForStructure = new LinkedHashSet<>(selectColumns);
            allColumnsToConsiderForStructure.addAll(effectiveDataColumns); // ensure data columns are also structured if not directly selected

            // Create and add columns to the table structure
            for (SelectColumn selectColumn : allColumnsToConsiderForStructure) {
                 if (!table.columnNames().contains(selectColumn.getColumnName())) {
                      Column<?> newColumn = selectColumn.createColumn();
                      logger.debug("Creating structural column '{}' of type {}. Adding to table.", newColumn.name(), newColumn.type());
                      table.addColumns(newColumn); 
                      columnMap.put(newColumn.name(), newColumn); 
                 } else {
                     // If it's an effectiveDataColumn that happens to have the same name as a primary selectColumn,
                     // it's fine, it means the data source and the final column are the same.
                     // If two primary selectColumns had the same name, that's a different issue (parser/validator should catch).
                     logger.trace("Column already exists or added: {}", selectColumn.getColumnName());
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
                if (detailsForUnit.isEmpty()) continue; 
                
                int rowIndex = table.rowCount();
                table.appendRow(); 
                
                // 3. Populate columns for this row using the list of details
                // Populate ONLY the effectiveDataColumns. Aggregates and complex types (like Snippet output)
                // will be handled later or by their own non-data-populating populateColumn method.
                for (SelectColumn dataColumnToPopulate : effectiveDataColumns) {
                    Column<?> tableCol = columnMap.get(dataColumnToPopulate.getColumnName());
                    if (tableCol != null) {
                        // Ensure this dataColumnToPopulate is of a type that actually populates data here.
                        // CountColumn's populateColumn does nothing, which is fine.
                        // VariableColumn and StructuralColumn will populate.
                        logger.trace("Populating data for column: {} at row {}", dataColumnToPopulate.getColumnName(), rowIndex);
                        dataColumnToPopulate.populateColumn(table, rowIndex, detailsForUnit, source, indexes, query, contextCache);
                    } else {
                        logger.warn("Data column '{}' for population not found in table structure? Available: {}", 
                                    dataColumnToPopulate.getColumnName(), table.columnNames());
                    }
                }

                // After data columns are populated, handle specific population for selected columns
                // that might rely on already populated data or have special formatting (e.g. SnippetColumn)
                // This loop is over the original selectColumns from the query.
                for (SelectColumn originalSelectColumn : selectColumns) {
                    if (effectiveDataColumns.contains(originalSelectColumn) && 
                        (originalSelectColumn instanceof VariableColumn || originalSelectColumn instanceof StructuralColumn)) {
                        // Already populated by the effectiveDataColumns loop if it's a simple data column.
                        continue;
                    }

                    Column<?> tableCol = columnMap.get(originalSelectColumn.getColumnName());
                    if (tableCol != null) {
                         // For types like SnippetColumn, their populateColumn might format data or use contextCache.
                         // For CountColumn, its populateColumn does nothing here, which is correct.
                        if (!(originalSelectColumn instanceof CountColumn)) { // CountColumn is populated by applyGroupBy
                             logger.trace("Performing secondary population/formatting for selected column: {} at row {}", originalSelectColumn.getColumnName(), rowIndex);
                             originalSelectColumn.populateColumn(table, rowIndex, detailsForUnit, source, indexes, query, contextCache);
                        }
                    } else {
                         // This case should ideally not happen if allColumnsToConsiderForStructure was set up correctly.
                         logger.warn("Original selected column '{}' not found in table structure during secondary population pass. Available: {}",
                                     originalSelectColumn.getColumnName(), table.columnNames());
                    }
                }
            }
            
            // New: Apply GROUP BY if specified
            if (!query.groupByColumns().isEmpty()) {
                logger.info("Applying GROUP BY clause with columns: {}", query.groupByColumns());
                table = applyGroupBy(table, query);
            }
            // Original CountColumn aggregation logic might need to be revisited or removed
            // if GROUP BY handles all necessary count aggregations.
            // For now, let it run if no GROUP BY, or if GROUP BY doesn't produce a count column that this would.
            else if (selectColumns.stream().anyMatch(col -> col instanceof CountColumn)) { // Only run if no GROUP BY and CountColumn exists
                logger.debug("No GROUP BY clause, but CountColumn found. Applying legacy CountColumn aggregation.");
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
     private List<SelectColumn> createDefaultSelectColumns(Query query, QueryResult result) {
         List<SelectColumn> defaultColumns = new ArrayList<>();

         // Add variable columns based on the query's variable registry
         Set<String> variableNames = query.variableRegistry().getAllVariableNames(); // Get all defined vars

         for (String varName : variableNames) {
              // Only add variables from the main scope ($main) that are actually produced
              if (varName.startsWith(query.mainAlias().orElse("$main") + ".") && query.variableRegistry().isProduced(varName)) {
                  defaultColumns.add(new VariableColumn(varName));
              }
         }
         logger.debug("Created default select columns: {}", defaultColumns.stream().map(SelectColumn::getColumnName).toList());
         return defaultColumns;
     }

    // New: Helper method to apply GROUP BY clause using Tablesaw
    private Table applyGroupBy(Table table, Query query) throws ResultGenerationException {
        List<String> groupByColumnNames = query.groupByColumns();
        if (groupByColumnNames.isEmpty()) {
            return table; // Should not happen if called from generateTable's check
        }

        // Identify aggregate functions and their target columns from the SELECT clause
        List<tech.tablesaw.aggregate.AggregateFunction<?, ?>> aggregateFunctions = new ArrayList<>();
        List<String> columnsToAggregate = new ArrayList<>();
        Map<String, String> finalColumnNames = new HashMap<>(); // Map from temp agg name to final name

        for (SelectColumn sc : query.selectColumns()) {
            if (sc instanceof CountColumn countColumn) {
                // Determine the column to apply the count on. For COUNT(*), it can be any non-null column or a specific one.
                // For COUNT(UNIQUE var), it's the column corresponding to 'var'.
                // Tablesaw's count usually doesn't need a specific column for COUNT(*)-like behavior when grouping.
                // However, to use .summarize(col, func), we need a column name.
                // Let's use the first groupBy column as a placeholder for COUNT(*)-like behavior within summarize.
                String targetColForCount = groupByColumnNames.get(0); 

                String originalAggColName = countColumn.getColumnName(); // e.g., "count", "count_unique_var"

                if (countColumn.toString().startsWith("COUNT(UNIQUE")) { // Check based on CountColumn's own representation
                    String varToCount = countColumn.getVariableNameForValidation(); // This should be the qualified name
                    if (varToCount == null || !table.columnNames().contains(varToCount)) {
                        throw new ResultGenerationException(
                            String.format("Cannot apply COUNT(UNIQUE %s): column '%s' not found in table for aggregation.", varToCount, varToCount),
                            "table_result_service", ResultGenerationException.ErrorType.INTERNAL_ERROR);
                    }
                    columnsToAggregate.add(varToCount); // Column to count unique values from
                    aggregateFunctions.add(tech.tablesaw.aggregate.AggregateFunctions.countUnique);
                    finalColumnNames.put("Count Unique [" + varToCount + "]", countColumn.toString());
                } else if (countColumn.toString().equals("COUNT(*)")) {
                    columnsToAggregate.add(targetColForCount); // Placeholder for COUNT(*)
                    aggregateFunctions.add(tech.tablesaw.aggregate.AggregateFunctions.count);
                    finalColumnNames.put("Count [" + targetColForCount + "]", countColumn.toString());
                } else if (countColumn.toString().equals("COUNT(DOCUMENTS)")) {
                    // COUNT(DOCUMENTS) is complex with summarize.by(). 
                    // It needs to count unique document IDs *within each group*
                    // This might require a more complex aggregation setup or pre-calculation.
                    // For now, we'll log a warning and potentially skip or use a simple count as placeholder.
                    logger.warn("COUNT(DOCUMENTS) with GROUP BY is not fully supported yet. Using COUNT(*) as placeholder for column '{}'.", originalAggColName);
                    columnsToAggregate.add(targetColForCount);
                    aggregateFunctions.add(tech.tablesaw.aggregate.AggregateFunctions.count);
                    finalColumnNames.put("Count [" + targetColForCount + "]", originalAggColName);
                } else {
                     logger.warn("Unhandled CountColumn type for GROUP BY: {} for column '{}'", countColumn.toString(), originalAggColName);
                }
            }
            // Other aggregate functions (SUM, AVG, etc.) would be handled here if added in the future
        }

        if (aggregateFunctions.isEmpty()) {
            // If only grouping without aggregation, Tablesaw's `by` can be used, 
            // but it typically expects an aggregation. 
            // A simple `table.groupBy(groupByColumnNames).reduce(...)` or just selecting distinct rows might be needed.
            // For now, if there are no *explicit* aggregates like COUNT, we assume the user wants distinct combinations of grouped columns.
            // The validator ensures all selected non-aggregate columns are in GROUP BY.
            // So, we can take the first row of each group after grouping.
            logger.debug("GROUP BY without explicit aggregates. Selecting first row of each group for columns: {}", query.selectColumns().stream().map(SelectColumn::getColumnName).toList());
            Table groupedTable = table.emptyCopy(); // CORRECTED: Use emptyCopy() to get an empty table with the same structure
            Table tempGrouped = table.sortOn(groupByColumnNames.toArray(new String[0])); 
            
            if (tempGrouped.isEmpty()) return groupedTable; 

            Set<List<Object>> distinctGroupValues = new HashSet<>();
            for (Row row : tempGrouped) {
                List<Object> currentGroupKey = new ArrayList<>();
                for (String groupColName : groupByColumnNames) {
                    currentGroupKey.add(row.getObject(groupColName));
                }
                if (distinctGroupValues.add(currentGroupKey)) {
                    groupedTable.addRow(row);
                }
            }
            return groupedTable;
        }

        // Perform aggregation
        Table summarizedTable = table.summarize(columnsToAggregate.get(0), aggregateFunctions.get(0))
                                     .by(groupByColumnNames.toArray(new String[0]));
        
        // If there are more aggregates, they need to be added. 
        // Tablesaw's fluent API for multiple aggregates on different columns in one go is tricky.
        // Often it's `table.summarize(col1, func1, func2).by(groupCols)` or multiple `summarize` calls then join.
        // For simplicity, if there are multiple COUNTs, we might hit limitations here or need to process them sequentially.
        if (aggregateFunctions.size() > 1) {
            logger.warn("Multiple aggregate functions in GROUP BY. Current implementation might only correctly process the first one: '{}' on '{}'.", 
                        aggregateFunctions.get(0).functionName(), columnsToAggregate.get(0));
            // TODO: Enhance to handle multiple aggregations correctly, possibly by separate summarize calls and joining,
            // or by ensuring the `summarize` call can take multiple (column, function) pairs if Tablesaw supports it directly.
        }

        // Rename aggregated columns to their original names
        for (Map.Entry<String, String> entry : finalColumnNames.entrySet()) {
            String tempName = entry.getKey();
            String finalName = entry.getValue();
            if (summarizedTable.columnNames().contains(tempName)) {
                Column<?> aggCol = summarizedTable.column(tempName);
                aggCol.setName(finalName);

                // If it's a known count column (e.g., starts with "COUNT(") and is DoubleColumn, convert to IntColumn
                if (finalName.startsWith("COUNT(") && aggCol.type() == ColumnType.DOUBLE) {
                    logger.warn("Count aggregate column '{}' was DoubleColumn, converting to IntColumn (truncating).", finalName);
                    try {
                        // Cast to DoubleColumn first, then convert to IntColumn
                        DoubleColumn doubleAggCol = (DoubleColumn) aggCol;
                        IntColumn intValues = doubleAggCol.asIntColumn(); 
                        summarizedTable.replaceColumn(finalName, intValues); 
                    } catch (ClassCastException cce) {
                        // This should not happen if aggCol.type() == ColumnType.DOUBLE
                        logger.error("Failed to cast aggregate column '{}' to DoubleColumn for conversion: {}", finalName, cce.getMessage());
                    } catch (Exception e) {
                        logger.error("Could not convert DoubleColumn '{}' to IntColumn: {}", finalName, e.getMessage());
                    }
                }
            } else {
                logger.warn("Aggregated column '{}' not found in summarized table for renaming to '{}'. Available: {}", 
                            tempName, finalName, summarizedTable.columnNames());
            }
        }

        return summarizedTable;
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
        logger.debug("Generating result table from List<JoinedMatch>");

        if (joinedResults == null || joinedResults.isEmpty()) {
            logger.warn("Input joinedResults list is null or empty, returning empty table.");
            return Table.create("EmptyJoinResults");
        }

        try {
            List<SelectColumn> selectColumns = query.selectColumns();
            boolean isSelectStarOrEmpty = selectColumns == null || selectColumns.isEmpty() || selectColumns.stream().anyMatch(sc -> "*".equals(sc.getColumnName()));

            // Check if we should use default columns (SELECT * or empty)
            if (isSelectStarOrEmpty) {
                logger.debug("No specific columns selected or * found for JOIN. Defaulting to all variables and structural columns.");
                // Create default columns for JOIN
                selectColumns = createDefaultJoinSelectColumns(query); 
            }

            Table table = Table.create("JoinQueryResults");
            Map<String, Column<?>> columnMap = new HashMap<>();
            Map<String, Object> contextCache = new HashMap<>(); // Initialize context cache

            // Create and add columns based on the *effective* SELECT clause (explicit or default)
            for (SelectColumn selectColumn : selectColumns) {
                // Avoid re-adding columns if somehow duplicated (shouldn't happen with proper defaults)
                if (!table.columnNames().contains(selectColumn.getColumnName())) {
                    Column<?> tableCol = selectColumn.createColumn();
                    table.addColumns(tableCol);
                    columnMap.put(tableCol.name(), tableCol);
                }
            }

            String source = query.source();
            logger.info("Processing {} joined match pairs at {} granularity", joinedResults.size(), query.granularity());

            // Populate the table row by row
            for (com.example.query.binding.JoinedMatch joinedMatch : joinedResults) {
                int rowIndex = table.rowCount();
                table.appendRow(); // Append empty row first

                // Populate columns based ONLY on the *effective* SELECT clause (explicit or default)
                for (SelectColumn selectColumn : selectColumns) {
                    Column<?> tableCol = columnMap.get(selectColumn.getColumnName());
                    if (tableCol != null) {
                        String qualifiedName = selectColumn.getColumnName(); 
                        String alias = qualifiedName.contains(".") ? qualifiedName.substring(0, qualifiedName.indexOf(".")) : "";
                        
                        MatchDetail relevantDetail = null;
                        if (alias.equals(query.mainAlias().orElse("$main"))) { 
                            relevantDetail = joinedMatch.left();
                        } else if (!query.subqueries().isEmpty() && alias.equals(query.subqueries().get(0).alias())) {
                            relevantDetail = joinedMatch.right();
                        } else if (query.subqueries().isEmpty() && !qualifiedName.contains(".")) {
                            relevantDetail = joinedMatch.left(); 
                        } else {
                            logger.warn("Could not determine alias match for column: {}", qualifiedName);
                            // relevantDetail remains null
                        }

                        if (relevantDetail != null) {
                            // Delegate population to the SelectColumn implementation
                            List<MatchDetail> detailList = List.of(relevantDetail);
                            selectColumn.populateColumn(table, rowIndex, detailList, source, indexes, query, contextCache);
                        } else {
                            // Handle cases where the alias didn't match / no relevant detail
                            logger.trace("No relevant detail found for column {} at row {}. Setting missing.", qualifiedName, rowIndex);
                            tableCol.setMissing(rowIndex);
                        }
                    }
                }
            }

            logger.info("Generated join table with {} columns and {} rows", table.columnCount(), table.rowCount());
            return table;

        } catch (Exception e) {
            logger.error("Error generating table for join results", e);
            throw new ResultGenerationException("Failed to generate table for join results: " + e.getMessage(), e, "table_result_service", ResultGenerationException.ErrorType.INTERNAL_ERROR);
        }
    }

    // --- Helper method to create default select columns for JOIN ---
    private List<SelectColumn> createDefaultJoinSelectColumns(Query query) {
        List<SelectColumn> defaultColumns = new ArrayList<>();
        String mainAlias = query.mainAlias().orElse("$main");
        
        // 1. Add all produced variables from the main query registry
        query.variableRegistry().getAllVariableNames().stream() // Get all defined vars
            .filter(varName -> varName.startsWith(mainAlias + ".")) // Ensure it belongs to main scope
            .filter(varName -> query.variableRegistry().isProduced(varName)) // Ensure it is produced
            .map(VariableColumn::new)
            .forEach(defaultColumns::add);

        // 2. Add default structural columns for the main query
        defaultColumns.add(new com.example.query.model.StructuralColumn(mainAlias, "DOCUMENT_ID"));
        defaultColumns.add(new com.example.query.model.StructuralColumn(mainAlias, "SENTENCE_ID")); // Add even if doc granularity, populate handles it
        defaultColumns.add(new com.example.query.model.StructuralColumn(mainAlias, "TIMESTAMP"));
        // Add BEGIN/END if needed by default? Probably not.

        // 3. Add variables and structural columns for each subquery
        for (com.example.query.model.SubquerySpec subquerySpec : query.subqueries()) {
            String subAlias = subquerySpec.alias();
            // Add produced variables from subquery registry
            subquerySpec.subquery().variableRegistry().getAllVariableNames().stream() // Get all defined vars
                 // No need to filter by alias here, subquery registry already contains qualified names like 'subAlias.var'
                 .filter(varName -> subquerySpec.subquery().variableRegistry().isProduced(varName)) // Ensure it is produced
                 .map(VariableColumn::new)
                 .forEach(defaultColumns::add);

            // Add default structural columns for the subquery
            defaultColumns.add(new com.example.query.model.StructuralColumn(subAlias, "DOCUMENT_ID"));
            defaultColumns.add(new com.example.query.model.StructuralColumn(subAlias, "SENTENCE_ID"));
            defaultColumns.add(new com.example.query.model.StructuralColumn(subAlias, "TIMESTAMP"));
        }

        logger.debug("Created default JOIN select columns: {}", defaultColumns.stream().map(SelectColumn::getColumnName).toList());
        return defaultColumns;
    }
} 