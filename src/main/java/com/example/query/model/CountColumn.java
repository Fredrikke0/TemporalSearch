package com.example.query.model;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.query.executor.CellResult;

import tech.tablesaw.aggregate.AggregateFunctions;
import tech.tablesaw.api.ColumnType;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;

/**
 * Represents a COUNT expression in the SELECT clause of a query.
 * This column performs counting operations using Tablesaw's built-in
 * aggregation features.
 */
public class CountColumn implements SelectColumn {
    private static final Logger logger = LoggerFactory.getLogger(CountColumn.class);

    public enum CountType {
        ALL, // COUNT(*)
        UNIQUE, // COUNT(UNIQUE ?var)
        DOCUMENTS // COUNT(DOCUMENTS)
    }

    private final CountType type;
    private final String qualifiedVariableName;
    private final String columnName;
    private final ColumnType columnType = ColumnType.INTEGER;

    /**
     * Creates a COUNT(*) column.
     */
    public static CountColumn countAll() {
        return new CountColumn(CountType.ALL, null, "COUNT(*)");
    }

    /**
     * Creates a COUNT(UNIQUE var) column.
     *
     * @param qualifiedVariableName The qualified variable name to count unique
     *                              values of (e.g., $main.var)
     */
    public static CountColumn countUnique(String qualifiedVariableName) {
        if (qualifiedVariableName == null || qualifiedVariableName.isEmpty() || !qualifiedVariableName.contains(".")) {
            throw new IllegalArgumentException(
                    "COUNT(UNIQUE) requires a qualified variable name (e.g., $main.var), got: "
                            + qualifiedVariableName);
        }
        String colName = "count_unique_" + qualifiedVariableName.replace('.', '_');
        return new CountColumn(CountType.UNIQUE, qualifiedVariableName, colName);
    }

    /**
     * Creates a COUNT(DOCUMENTS) column.
     */
    public static CountColumn countDocuments() {
        return new CountColumn(CountType.DOCUMENTS, null, "document_count");
    }

    private CountColumn(CountType type, String qualifiedVariableName, String columnName) {
        this.type = type;
        this.qualifiedVariableName = (type == CountType.UNIQUE) ? qualifiedVariableName : null;
        this.columnName = columnName;
    }

    /**
     * Returns the qualified variable name if this is a COUNT(UNIQUE var) column,
     * otherwise returns null. Used for validation purposes.
     */
    public String getVariableNameForValidation() {
        return qualifiedVariableName;
    }

    @Override
    public String getColumnName() {
        return columnName;
    }

    @Override
    public Column<?> createColumn() {
        return IntColumn.create(columnName);
    }

    @Override
    public void populateColumn(Table table, int rowIndex,
            CellResult result, List<Integer> bindingIndices,
            String source,
            Map<String, IndexAccessInterface> indexes,
            Query query,
            Map<String, Object> contextCache) {
        // COUNT columns are handled by a separate aggregation step after initial table
        // population.
        logger.trace("populateColumn called for CountColumn '{}', row {}. No direct population needed.", columnName,
                rowIndex);
    }

    /**
     * Applies count aggregations to a table.
     * Assumes the table is already populated with individual match details.
     * Groups by all columns *except* the COUNT column(s) and calculates counts.
     *
     * @param inputTable The table populated with raw match details.
     * @return A new table with aggregated counts.
     */
    public static Table applyCountAggregations(Table inputTable) {
        List<String> countColumns = inputTable.columns().stream()
                .filter(col -> col.type() == ColumnType.INTEGER &&
                        (col.name().toUpperCase().startsWith("COUNT(") ||
                                col.name().toUpperCase().startsWith("COUNT_") ||
                                col.name().toUpperCase().endsWith("_COUNT")))
                .map(Column::name)
                .toList();

        if (countColumns.isEmpty()) {
            logger.debug("No COUNT columns found by name pattern, returning original table.");
            return inputTable;
        }

        List<String> groupColumns = inputTable.columns().stream()
                .map(Column::name)
                .filter(name -> countColumns.stream().noneMatch(countCol -> countCol.equalsIgnoreCase(name)))
                .toList();

        if (groupColumns.isEmpty() && inputTable.rowCount() > 0) { // Allow count if table has rows but no other cols
            logger.warn(
                    "Cannot perform COUNT aggregation without non-count columns to group by if table has multiple rows and no explicit groups. Counting total rows for first COUNT column.");
            // Create a table with just the count
            Table resultTable = Table.create(inputTable.name() + "_count");
            IntColumn resultCountCol = IntColumn.create(countColumns.get(0));
            resultCountCol.append(inputTable.rowCount());
            resultTable.addColumns(resultCountCol);
            return resultTable;
        } else if (groupColumns.isEmpty() && inputTable.rowCount() == 0) {
            logger.debug(
                    "COUNT aggregation on empty table with no group columns. Returning empty table with count column.");
            Table resultTable = Table.create(inputTable.name() + "_empty_count");
            resultTable.addColumns(IntColumn.create(countColumns.get(0))); // Add empty count column
            return resultTable;
        }

        logger.debug("Applying COUNT aggregation, grouping by: {}, counting: {}", groupColumns, countColumns);

        Table aggregatedTable = inputTable.summarize(groupColumns.get(0), AggregateFunctions.count)
                .by(groupColumns.toArray(String[]::new));

        String expectedAggColName = "Count [" + groupColumns.get(0) + "]";
        if (aggregatedTable.columnNames().contains(expectedAggColName) && !countColumns.isEmpty()) {
            aggregatedTable.column(expectedAggColName).setName(countColumns.get(0));
        } else if (!countColumns.isEmpty()) {
            logger.warn("Could not find expected aggregated column '{}' to rename to '{}'", expectedAggColName,
                    countColumns.get(0));
        }

        if (countColumns.size() > 1) {
            logger.warn(
                    "Handling multiple COUNT columns ({}) with basic aggregation on first group column. This may not be correct for all count types.",
                    countColumns);
        }

        logger.debug("Aggregation complete, resulting table has {} rows.", aggregatedTable.rowCount());
        return aggregatedTable;
    }

    @Override
    public String toString() {
        return switch (type) {
            case ALL -> "COUNT(*)";
            case UNIQUE -> "COUNT(UNIQUE " + qualifiedVariableName + ")";
            case DOCUMENTS -> "COUNT(DOCUMENTS)";
        };
    }
}
