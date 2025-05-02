package com.example.query.model;

import com.example.core.IndexAccessInterface;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import tech.tablesaw.api.*;
import tech.tablesaw.columns.Column;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a variable column in the SELECT clause of a query.
 * This column selects the value of a variable binding from matching documents,
 * handling both simple variables (var) and qualified variables (alias.var).
 */
public class VariableColumn implements SelectColumn {
    private static final Logger logger = LoggerFactory.getLogger(VariableColumn.class);
    
    // Stores the fully qualified name (e.g., "var" qualified to "$main.var" or "alias.var")
    private final String qualifiedVariableName; 
    
    // TODO: Infer ColumnType based on VariableRegistry? Currently defaults to String.
    private final ColumnType columnType = ColumnType.STRING; 

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
    
    @SuppressWarnings("unchecked")
    @Override
    public void populateColumn(Table table, int rowIndex, List<?> detailsForUnit, 
                               String source,
                               Map<String, IndexAccessInterface> indexes) {
        logger.trace("Populating row {} for VariableColumn '{}'. Details count: {}", 
                      rowIndex, qualifiedVariableName, detailsForUnit.size());

        Optional<Object> valueOpt = Optional.empty();

        for (Object obj : detailsForUnit) {
            if (obj instanceof com.example.query.binding.JoinedMatch joined) {
                // Handle join result: check left and right sides using QUALIFIED names
                // Assumes JoinedMatch provides access to the qualified name associated with left/right MatchDetail
                // TODO: Update JoinedMatch structure or access logic if needed.
                // For now, compare against the qualified name stored in the MatchDetail.
                if (qualifiedVariableName.equals(joined.left().variableName().orElse(null))) {
                    valueOpt = Optional.ofNullable(joined.left().value());
                    break;
                } else if (qualifiedVariableName.equals(joined.right().variableName().orElse(null))) {
                    valueOpt = Optional.ofNullable(joined.right().value());
                    break;
                }

            } else if (obj instanceof MatchDetail detail) {
                 // Non-join result: Check if the detail's qualified variable name matches.
                 // MatchDetail.variableName() now stores the qualified name.
                if (qualifiedVariableName.equals(detail.variableName().orElse(null))) {
                    valueOpt = Optional.ofNullable(detail.value());
                    break;
                }
            }
        }

        Column<?> column = table.column(qualifiedVariableName); // Use qualified name to get column
        if (!(column instanceof StringColumn strCol)) {
            logger.error("VariableColumn '{}' requires a StringColumn, but found {}", qualifiedVariableName, (column != null ? column.type() : "null"));
            return;
        }

        if (valueOpt.isPresent()) {
            Object value = valueOpt.get();
            strCol.set(rowIndex, value != null ? value.toString() : "");
            logger.trace("Set value '{}' for column '{}' at row {}", value, qualifiedVariableName, rowIndex);
        } else {
            strCol.setMissing(rowIndex);
            logger.trace("No matching detail found for column '{}' at row {}, setting missing.", qualifiedVariableName, rowIndex);
        }
    }
    
    @Override
    public String toString() {
        // Return the qualified variable name for representation
        return qualifiedVariableName; 
    }
} 