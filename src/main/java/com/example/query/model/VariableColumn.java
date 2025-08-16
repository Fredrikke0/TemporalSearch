package com.example.query.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.query.executor.QueryResultSoA;

import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;

/**
 * Represents a variable column in the SELECT clause of a query.
 * This column selects the value of a variable binding from matching documents.
 * It always stores a qualified variable name (e.g., "$main.var" or "alias.var").
 * Simple variable references (e.g., "var") are qualified by the parser/builder upstream.
 */
public class VariableColumn implements SelectColumn {
    private static final Logger logger = LoggerFactory.getLogger(VariableColumn.class);

    // Stores the fully qualified name (e.g., "var" qualified to "$main.var" or "alias.var")
    private final String qualifiedVariableName;

    // TODO: Consider inferring a concrete column type from VariableRegistry. For now we always use StringColumn.

    /**
     * Creates a new variable column, storing the fully qualified name.
     *
     * @param qualifiedName The fully qualified variable name (e.g., "$main.var" or "alias.var")
     */
    public VariableColumn(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank() || !qualifiedName.contains(".")) {
            // Throw error if the name provided by the builder isn't qualified as expected.
            throw new IllegalArgumentException("VariableColumn must be initialized with a qualified name (e.g., 'alias.var'), got: " + qualifiedName);
        }
        this.qualifiedVariableName = qualifiedName;
        logger.trace("Created VariableColumn with qualifiedVariableName='{}'", this.qualifiedVariableName);
    }

    /**
     * Gets the qualified variable name used by this column.
     *
     * @return The qualified variable name (e.g., "$main.var" or "alias.var")
     */
    @Override
    public String getColumnName() {
        // Return the qualified name as the unique identifier for this column's data source
        return qualifiedVariableName;
    }

    @Override
    public Column<?> createColumn() {
        // Use the qualified name for the Tablesaw column name to ensure uniqueness
        Column<?> col = StringColumn.create(qualifiedVariableName);
        logger.debug("VariableColumn creating column named '{}' of type {}", qualifiedVariableName, col.type());
        return col;
    }

    @Override
    public void populateColumn(Table table, int rowIndex,
                               QueryResultSoA resultSoA, List<Integer> indicesInSoA,
                               String source,
                               Map<String, IndexAccessInterface> indexes,
                               Query query,
                               Map<String, Object> contextCache) {
        logger.trace("Populating row {} for VariableColumn '{}'. SoA indices count: {}",
                      rowIndex, qualifiedVariableName, indicesInSoA.size());

        Optional<Object> valueOpt = Optional.empty();

        for (int soaIndex : indicesInSoA) {
            String varNameInSoA = resultSoA.getVariableNameAt(soaIndex); // Expected to be e.g., "$main.var" or "alias.var"

            // Directly compare the VariableColumn's qualified name with the SoA's variable name.
            if (qualifiedVariableName.equals(varNameInSoA)) {
                valueOpt = Optional.ofNullable(resultSoA.getValueAt(soaIndex));
                logger.trace("Found match for variable '{}' in SoA index {}. Value: '{}'",
                             qualifiedVariableName, soaIndex, valueOpt.orElse("null"));
                break; // Found the first match for this variable in the current conceptual row's entries
            }
        }

        Column<?> column = table.column(qualifiedVariableName);
        if (!(column instanceof StringColumn strCol)) {
            logger.error("VariableColumn '{}' expects a StringColumn in the table, but found {}. Setting missing.",
                         qualifiedVariableName, (column != null ? column.type() : "null column object"));
            if (column != null) {
                column.setMissing(rowIndex);
            }
            return;
        }

        if (valueOpt.isPresent()) {
            Object value = valueOpt.get();
            strCol.set(rowIndex, value != null ? value.toString() : null);
            logger.trace("Set value '{}' for column '{}' at row {}", valueOpt.orElse("null"), qualifiedVariableName, rowIndex);
        } else {
            strCol.setMissing(rowIndex);
            logger.trace("No matching value found for column '{}' in SoA indices for row {}, setting missing.", qualifiedVariableName, rowIndex);
        }
    }

    @Override
    public String toString() {
        // Return the qualified variable name for representation
        return qualifiedVariableName;
    }
}