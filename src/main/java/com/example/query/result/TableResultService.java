package com.example.query.result;

// Static import for aggregate functions
import static tech.tablesaw.aggregate.AggregateFunctions.count;
import static tech.tablesaw.aggregate.AggregateFunctions.countNonMissing;
import static tech.tablesaw.aggregate.AggregateFunctions.first;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.query.executor.AttributeRequirements;
import com.example.query.executor.QueryResultSoA;
import com.example.query.executor.SubqueryContext;
import com.example.query.model.CountColumn;
import com.example.query.model.Query;
import com.example.query.model.SelectColumn;
import com.example.query.model.StructuralColumn;
import com.example.query.model.VariableColumn;

import tech.tablesaw.aggregate.AggregateFunction;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;
import tech.tablesaw.io.csv.CsvWriteOptions;

/**
 * Service for converting query results to Tablesaw Tables.
 * Leverages QueryResultSoA and its conceptualRowIds for table construction.
 */
public class TableResultService {
    private static final Logger logger = LoggerFactory.getLogger(TableResultService.class);
    private static final String DEFAULT_DOC_ID_COL = "document_id";
    private static final String DEFAULT_SENT_ID_COL = "sentence_id";

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
     * @param result The QueryResultSoA object containing match details.
     * @param indexes Map of indexes (using interface) to retrieve additional document information
     * @return A Tablesaw Table containing the query results
     * @throws ResultGenerationException if an error occurs
     */
    public Table generateTable(
            Query query,
            QueryResultSoA result,
            Map<String, IndexAccessInterface> indexes
    ) throws ResultGenerationException {
        return generateTable(query, result, indexes, new SubqueryContext());
    }

    /**
     * Converts query results to a Tablesaw Table, including subquery handling.
     *
     * @param query The original query
     * @param result The QueryResultSoA object containing match details.
     * @param indexes Map of indexes (using interface) to retrieve additional document information
     * @param subqueryContext Context containing subquery results
     * @return A Tablesaw Table containing the query results
     * @throws ResultGenerationException if an error occurs
     */
    public Table generateTable(
            Query query,
            QueryResultSoA result,
            Map<String, IndexAccessInterface> indexes,
            SubqueryContext subqueryContext
    ) throws ResultGenerationException {
        List<SelectColumn> selectColumns = query.selectColumns();
        boolean createdDefaultColumns = false;
        if (selectColumns == null || selectColumns.isEmpty() || selectColumns.stream().anyMatch(sc -> "*".equals(sc.getColumnName()))) {
            logger.debug("No specific columns selected or * found, attempting to create default columns.");
            selectColumns = createDefaultSelectColumns(query, result);
            createdDefaultColumns = true;
        }

        Table table = Table.create(query.mainAlias().orElse("QueryResults"));
        Map<String, Column<?>> columnMap = new HashMap<>();

        // Initialize table structure based on (potentially default) select columns
        if (selectColumns != null && !selectColumns.isEmpty()) {
            for (SelectColumn selectColumn : selectColumns) {
                if (!table.columnNames().contains(selectColumn.getColumnName())) {
                    Column<?> newColumn = selectColumn.createColumn();
                    logger.debug("Creating table column '{}' of type {}.", newColumn.name(), newColumn.type());
                    table.addColumns(newColumn);
                    columnMap.put(newColumn.name(), newColumn);
                }
            }
        } else {
            // If even after attempting to create default columns, selectColumns is empty,
            // and the result is also empty, this means an empty table with no columns is appropriate.
            // If result wasn't empty, this case is handled further down.
            if (result == null || result.isEmpty()){
                 logger.warn("No columns to select and QueryResultSoA is empty. Returning table with no columns.");
                 return table; // table is currently empty (no columns)
            }
        }

        if (result == null || result.isEmpty()) {
             logger.warn("Input QueryResultSoA is null or empty, but table structure created. Returning table with {} columns and 0 rows.", table.columnCount());
             return table; // table has columns but no rows
        }

        // This case is now only reachable if result is NOT empty, but we failed to determine selectColumns.
        if (createdDefaultColumns && selectColumns.isEmpty()) {
            logger.warn("QueryResultSoA has data, but no columns were selected (explicitly or by default after attempting). Returning table with no columns.");
            return Table.create(query.mainAlias().orElse("QueryResults_NoColumns")); // Fallback to table with no columns
        }

        try {
            // Validate ORDER BY columns exist (moved here as table structure is now set)
            for (String orderColumnName : query.orderBy()) {
                String actualColumnName = orderColumnName.startsWith("-") ? orderColumnName.substring(1) : orderColumnName;
                if (!table.columnNames().contains(actualColumnName)) {
                    throw new ResultGenerationException(
                        String.format("Cannot order by column '%s' - not found in table columns: %s", actualColumnName, table.columnNames()),
                        "table_result_service.orderByValidation",
                        ResultGenerationException.ErrorType.INTERNAL_ERROR
                    );
                }
            }

            Map<String, Object> contextCache = new HashMap<>();

            // --- START OPTIMIZATION ---
            // Pre-group raw indices by their conceptualRowId
            Map<Integer, List<Integer>> conceptualIdToRawIndicesMap = new HashMap<>();
            if (result != null && !result.isEmpty()) { // Only process if there's data
                for (int i = 0; i < result.size(); i++) {
                    conceptualIdToRawIndicesMap
                        .computeIfAbsent(result.getConceptualRowIdAt(i), k -> new ArrayList<>())
                        .add(i);
                }
            }

            // Get unique conceptualRowIds from the map's keyset and sort them
            List<Integer> uniqueConceptualRowIds = new ArrayList<>(conceptualIdToRawIndicesMap.keySet());
            Collections.sort(uniqueConceptualRowIds); // Ensure sorted order

            if (result != null && !result.isEmpty()) {
                logger.info("QueryResultSoA processed into {} unique conceptualRowIds for table rows. Original binding count: {}", uniqueConceptualRowIds.size(), result.size());
            } else if (result != null) { // result is not null but empty
                 logger.info("QueryResultSoA is empty. No conceptual rows to process.");
            }
            // --- END OPTIMIZATION ---

            String source = query.source();

            for (int conceptualRowId : uniqueConceptualRowIds) {
                table.appendRow(); // Add a new row to the Tablesaw table
                int currentRowIndex = table.rowCount() - 1; // Get the index of the row just added
                contextCache.clear(); // Clear context for each new conceptual row

                // Retrieve pre-computed list of raw indices for the current conceptualRowId
                List<Integer> indicesInSoA = conceptualIdToRawIndicesMap.getOrDefault(conceptualRowId, Collections.emptyList());

                if (indicesInSoA.isEmpty() && result.getRequirements().needsConceptualRowIds) {
                    // This could happen if a conceptualRowId was in uniqueConceptualRowIds but no raw rows actually match it.
                    // Should be rare if uniqueConceptualRowIds is derived from result.getConceptualRowIdAt(i).
                    logger.warn("No raw SoA entries found for conceptualRowId {}. Row {} will be empty or partially populated.", conceptualRowId, currentRowIndex);
                    // Continue to allow population of columns that don't depend on SoA indices (e.g. constants, if any)
                }

                for (SelectColumn selectColumn : selectColumns) {
                    Column<?> tableCol = columnMap.get(selectColumn.getColumnName());
                    if (tableCol != null) {
                        logger.trace("Populating column '{}' for conceptualRowId {} (raw indices: {}) at table_row {}.",
                                     selectColumn.getColumnName(), conceptualRowId, indicesInSoA, currentRowIndex);
                        selectColumn.populateColumn(table, currentRowIndex, result, indicesInSoA, source, indexes, query, contextCache);
                    } else {
                        // This should not happen if columns were created correctly from selectColumns
                        logger.warn("Column '{}' defined in select clause not found in created table structure. This is unexpected.",
                                    selectColumn.getColumnName());
                    }
                }
            }

            // GROUP BY, ORDER BY, LIMIT are applied to the materialized Tablesaw table
            if (!query.groupByColumns().isEmpty()) {
                logger.info("Applying GROUP BY clause with columns: {}", query.groupByColumns());
                table = applyGroupBy(table, query);
            }
            else if (selectColumns.stream().anyMatch(col -> col instanceof CountColumn)) {
                logger.debug("No GROUP BY clause, but CountColumn found. Applying legacy CountColumn aggregation.");
                table = CountColumn.applyCountAggregations(table);
            }

            if (!query.orderBy().isEmpty()) {
                logger.debug("Ordering results by {} criteria: {}", query.orderBy().size(), query.orderBy());
                table = applyOrdering(table, query.orderBy());
            }

            if (query.limit().isPresent()) {
                int limit = query.limit().get();
                if (limit > 0 && limit < table.rowCount()) {
                    logger.info("Limiting final {} rows to {}", table.rowCount(), limit);
                    table = table.first(limit);
                } else {
                     logger.debug("Limit {} is not applicable (<=0 or >= row count {}), no limit applied.", limit, table.rowCount());
                }
            }

            logger.info("Generated final table '{}' with {} columns and {} rows.",
                    table.name(), table.columnCount(), table.rowCount());
            return table;

        } catch (Exception e) {
            logger.error("Error generating table from QueryResultSoA: {}", e.getMessage(), e);
            throw new ResultGenerationException("Failed to generate table: " + e.getMessage(), e,
                                                query.mainAlias().orElse("table_generation"),
                                                ResultGenerationException.ErrorType.INTERNAL_ERROR);
        }
    }

    private Table applyOrdering(Table table, List<String> orderColumns) {
        if (orderColumns == null || orderColumns.isEmpty()) {
            return table;
        }
        logger.debug("Applying ordering to table. Columns: {}", orderColumns);

        // Ensure all columns exist before trying to sort and prepare for sortOn
        String[] sortOnArgs = new String[orderColumns.size()];
        for (int i = 0; i < orderColumns.size(); i++) {
            String orderSpec = orderColumns.get(i);
            boolean isDescending = orderSpec.startsWith("-");
            boolean isAscending = orderSpec.startsWith("+");
            String colName = (isDescending || isAscending) ? orderSpec.substring(1) : orderSpec;

            // Map count expressions to their actual generated column names
            String actualColName = mapToActualColumnName(colName, table);

            if (!table.columnNames().contains(actualColName)) {
                throw new IllegalArgumentException(
                    String.format("Cannot sort by column '%s': column not found in table. Available columns: %s",
                                  actualColName, table.columnNames()));
            }

            // Reconstruct the sort specification with the actual column name
            if (isDescending) {
                sortOnArgs[i] = "-" + actualColName;
            } else if (isAscending) {
                sortOnArgs[i] = "+" + actualColName;
            } else {
                sortOnArgs[i] = actualColName;
            }
        }

        try {
            // Table.sortOn directly accepts column names with optional '-' or '+' prefix
            return table.sortOn(sortOnArgs);
        } catch (Exception e) {
            logger.error("Failed to sort table on {}. Error: {}", Arrays.toString(sortOnArgs), e.getMessage(), e);
            // Consider rethrowing as ResultGenerationException for consistency
            throw new RuntimeException("Table sorting failed: " + e.getMessage(), e);
        }
    }

    /**
     * Maps ORDER BY column names to actual table column names, handling count expressions.
     * This is needed because GROUP BY aggregation generates different column names than
     * what appears in the original query.
     */
    private String mapToActualColumnName(String orderByColumnName, Table table) {
        // If the column exists as-is, return it
        if (table.columnNames().contains(orderByColumnName)) {
            return orderByColumnName;
        }

        // Handle COUNT(*) mapping to generated count column names
        if ("COUNT(*)".equals(orderByColumnName)) {
            // Look for any column that starts with "Count [" - this will be the COUNT(*) result
            for (String colName : table.columnNames()) {
                if (colName.startsWith("Count [") && colName.endsWith("]")) {
                    logger.debug("Mapping ORDER BY column 'COUNT(*)' to actual column '{}'", colName);
                    return colName;
                }
            }
        }

        // Handle COUNT(UNIQUE variable) - would generate similar pattern
        if (orderByColumnName.startsWith("COUNT(") && orderByColumnName.endsWith(")")) {
            for (String colName : table.columnNames()) {
                if (colName.startsWith("Count [") && colName.endsWith("]")) {
                    logger.debug("Mapping ORDER BY column '{}' to actual column '{}'", orderByColumnName, colName);
                    return colName;
                }
            }
        }

        // Handle COUNT(DOCUMENTS)
        if ("COUNT(DOCUMENTS)".equals(orderByColumnName)) {
            for (String colName : table.columnNames()) {
                if (colName.startsWith("Count [") && colName.endsWith("]")) {
                    logger.debug("Mapping ORDER BY column 'COUNT(DOCUMENTS)' to actual column '{}'", colName);
                    return colName;
                }
            }
        }

        // If no mapping found, return the original name (will likely cause an error)
        logger.warn("Could not map ORDER BY column '{}' to any actual table column. Available: {}",
                   orderByColumnName, table.columnNames());
        return orderByColumnName;
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
            sb.append("\n\nThis is a preview showing ").append(displayedRows)
              .append(" of ").append(totalRows).append(" total rows. To export all results, use: --export=csv:results.csv");
        }

        return sb.toString();
    }

     private List<SelectColumn> createDefaultSelectColumns(Query query, QueryResultSoA result) {
        logger.debug("Creating default select columns for query based on QueryResultSoA content.");
         List<SelectColumn> defaultColumns = new ArrayList<>();
        Set<String> addedColumns = new HashSet<>();

        // If the result QueryResultSoA has specific requirements, use them.
        AttributeRequirements requirements = result.getRequirements();

        if (requirements.needsDocumentId) {
            defaultColumns.add(new StructuralColumn(DEFAULT_DOC_ID_COL, "DOCUMENT_ID"));
            addedColumns.add(DEFAULT_DOC_ID_COL);
        }
        if (requirements.needsSentenceId && query.granularity() == Query.Granularity.SENTENCE) {
             defaultColumns.add(new StructuralColumn(DEFAULT_SENT_ID_COL, "SENTENCE_ID"));
             addedColumns.add(DEFAULT_SENT_ID_COL);
        }

        // Add columns for all unique variable names present in the QueryResultSoA
        // Assumes QueryResultSoA.getUniqueVariableNames() is implemented as per QueryResultSoA.md
        List<String> variableNames = result.getUniqueVariableNames();
        if(variableNames != null){
            for (String varName : variableNames) {
                if (varName != null && !varName.isEmpty() && !addedColumns.contains(varName)) {
                    defaultColumns.add(new VariableColumn(varName));
                    addedColumns.add(varName);
                    logger.debug("Adding default VariableColumn for variable: {}", varName);
                }
            }
        }

        if (defaultColumns.isEmpty() && !result.isEmpty()) {
            logger.warn("Result is not empty, but no default columns could be determined (no doc/sent id required, no variables found). Consider query select clause.");
            // Fallback: if there's data, at least show document ID if available, even if not strictly "required" by an empty select.
            if (result.getRequirements().needsDocumentId) {
                 if (!addedColumns.contains(DEFAULT_DOC_ID_COL)) {
                    defaultColumns.add(new StructuralColumn(DEFAULT_DOC_ID_COL, "DOCUMENT_ID"));
                 }
            }
        }

        logger.debug("Created default columns: {}", defaultColumns.stream().map(SelectColumn::getColumnName).collect(Collectors.toList()));
         return defaultColumns;
     }

    private Table applyGroupBy(Table table, Query query) throws ResultGenerationException {
        List<String> groupByColumns = query.groupByColumns();
        if (groupByColumns.isEmpty()) {
            return table;
        }

        List<SelectColumn> selectColumns = query.selectColumns();

        // Check if we have any COUNT columns (explicit aggregation)
        List<SelectColumn> countColumns = selectColumns.stream()
            .filter(sc -> sc instanceof CountColumn)
            .collect(Collectors.toList());

        // Check if we have any non-grouping columns that need implicit aggregation
        List<SelectColumn> nonGroupingColumns = selectColumns.stream()
            .filter(sc -> !(sc instanceof CountColumn) && !groupByColumns.contains(sc.getColumnName()))
            .collect(Collectors.toList());

        if (countColumns.isEmpty() && nonGroupingColumns.isEmpty()) {
            // Only grouping columns selected - return distinct group keys
            logger.info("GROUP BY with only grouping columns selected. Returning distinct group keys.");
            return table.selectColumns(groupByColumns.toArray(new String[0])).dropDuplicateRows();
        }

        // Build list of columns to summarize and functions to apply
        List<String> columnsToSummarize = new ArrayList<>();
        List<AggregateFunction<?, ?>> functionsToApply = new ArrayList<>();

        // Handle COUNT columns - these summarize the entire table/groups
        for (SelectColumn sc : countColumns) {
            CountColumn cc = (CountColumn) sc;
            String targetCol = cc.getVariableNameForValidation();

            if (targetCol == null) {
                // COUNT(*) - count all rows in each group
                columnsToSummarize.add(table.columnNames().get(0)); // Use any column for counting rows
                functionsToApply.add(count); // Static import from AggregateFunctions
            } else {
                // COUNT(variable) - count non-missing values in target column
                if (table.columnNames().contains(targetCol)) {
                    columnsToSummarize.add(targetCol);
                    functionsToApply.add(countNonMissing); // Static import from AggregateFunctions
                } else {
                    logger.warn("COUNT target column '{}' not found in table. Falling back to COUNT(*).", targetCol);
                    columnsToSummarize.add(table.columnNames().get(0));
                    functionsToApply.add(count);
                }
            }
        }

        // Handle non-grouping columns - apply FIRST() aggregation
        for (SelectColumn sc : nonGroupingColumns) {
            String colName = sc.getColumnName();
            if (table.columnNames().contains(colName)) {
                columnsToSummarize.add(colName);
                functionsToApply.add(first); // Static import from AggregateFunctions
            } else {
                logger.warn("Selected column '{}' not found in table for FIRST() aggregation.", colName);
            }
        }

        if (functionsToApply.isEmpty()) {
            logger.info("No aggregation functions to apply. Returning distinct group keys.");
            return table.selectColumns(groupByColumns.toArray(new String[0])).dropDuplicateRows();
        }

        try {
            logger.debug("Applying GROUP BY with {} functions on {} columns", functionsToApply.size(), columnsToSummarize.size());

            // Use first column for summarization with all functions
            String firstColumn = columnsToSummarize.get(0);
            Table groupedTable = table.summarize(firstColumn, functionsToApply.toArray(new AggregateFunction<?, ?>[0]))
                                      .by(groupByColumns.toArray(new String[0]));

            // Select columns based on the original SELECT list
            List<String> finalColumnNames = new ArrayList<>();
            Set<String> availableColumns = new HashSet<>(groupedTable.columnNames());

            // Add group by columns first (they should be present)
            for (String gbCol : groupByColumns) {
                if (availableColumns.contains(gbCol)) {
                    finalColumnNames.add(gbCol);
                }
            }

            // Add aggregated columns - Tablesaw generates names like "Count [column_name]", "First [column_name]"
            for (SelectColumn sc : selectColumns) {
                if (sc instanceof CountColumn) {
                    // Look for count column result
                    String expectedCountColName = "Count [" + firstColumn + "]";
                    if (availableColumns.contains(expectedCountColName)) {
                        finalColumnNames.add(expectedCountColName);
                    }
                } else if (!groupByColumns.contains(sc.getColumnName())) {
                    // Look for first() result
                    String expectedFirstColName = "First [" + sc.getColumnName() + "]";
                    if (availableColumns.contains(expectedFirstColName)) {
                        finalColumnNames.add(expectedFirstColName);
                    }
                }
            }

            if (finalColumnNames.isEmpty()) {
                logger.warn("No expected columns found in grouped result. Available: {}. Returning raw grouped table.", availableColumns);
                return groupedTable;
            }

            return groupedTable.selectColumns(finalColumnNames.toArray(new String[0]));

        } catch (Exception e) {
            logger.error("Failed to apply GROUP BY: {}", e.getMessage(), e);
            throw new ResultGenerationException("Failed to apply GROUP BY: " + e.getMessage(), e,
                                                query.mainAlias().orElse("groupBy_err"),
                                                ResultGenerationException.ErrorType.INTERNAL_ERROR);
        }
    }
}