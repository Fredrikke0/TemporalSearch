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
 * handling both simple variables (?var) and qualified variables (alias.?var).
 */
public class VariableColumn implements SelectColumn {
    private static final Logger logger = LoggerFactory.getLogger(VariableColumn.class);
    
    private final String columnName; // The full name as it appears in SELECT (e.g., "?var" or "alias.?var")
    private final String alias; // The alias part (e.g., "alias"), or null if unqualified
    private final String targetVariableName; // The simple variable name (e.g., "?var")
    
    // TODO: Infer ColumnType based on VariableRegistry? Currently defaults to String.
    private final ColumnType columnType = ColumnType.STRING; 

    /**
     * Creates a new variable column, parsing qualified names if necessary.
     * 
     * @param nameInSelect The name as it appears in the SELECT clause (e.g., "?var" or "alias.?var")
     */
    public VariableColumn(String nameInSelect) {
        this.columnName = nameInSelect;
        if (nameInSelect.contains(".")) {
            String[] parts = nameInSelect.split("\\.", 2);
            if (parts.length == 2 && parts[1].startsWith("?")) {
                this.alias = parts[0];
                this.targetVariableName = parts[1];
            } else {
                logger.warn("Invalid qualified variable format '{}' treated as simple variable name.", nameInSelect);
                this.alias = null;
                this.targetVariableName = nameInSelect.startsWith("?") ? nameInSelect : "?" + nameInSelect;
            }
        } else {
            this.alias = null;
            // Ensure targetVariableName always starts with ? for unqualified names
            this.targetVariableName = nameInSelect.startsWith("?") ? nameInSelect : "?" + nameInSelect;
        }
        logger.trace("Created VariableColumn: columnName='{}', alias='{}', targetVariableName='{}'", 
                     this.columnName, this.alias, this.targetVariableName);
    }
    
    /**
     * Gets the variable name.
     * 
     * @return The variable name (without '?')
     */
    @Override
    public String getColumnName() {
        return columnName;
    }
    
    @Override
    public Column<?> createColumn() {
        return StringColumn.create(columnName);
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public void populateColumn(Table table, int rowIndex, List<?> detailsForUnit, 
                               String source,
                               Map<String, IndexAccessInterface> indexes) {
        logger.trace("Populating row {} for column '{}' (alias: {}, targetVar: {}). Details count: {}", 
                      rowIndex, columnName, alias, targetVariableName, detailsForUnit.size());

        Optional<Object> valueOpt = Optional.empty();

        for (Object obj : detailsForUnit) {
            if (obj instanceof com.example.query.binding.JoinedMatch joined) {
                // Handle join result: check left and right
                if (alias == null) {
                    if (targetVariableName.equals(joined.getLeftVariableName())) {
                        valueOpt = Optional.ofNullable(joined.getLeftValue());
                        break;
                    } else if (targetVariableName.equals(joined.getRightVariableName())) {
                        valueOpt = Optional.ofNullable(joined.getRightValue());
                        break;
                    }
                } else {
                    if ("left".equals(alias) && targetVariableName.equals(joined.getLeftVariableName())) {
                        valueOpt = Optional.ofNullable(joined.getLeftValue());
                        break;
                    } else if ("right".equals(alias) && targetVariableName.equals(joined.getRightVariableName())) {
                        valueOpt = Optional.ofNullable(joined.getRightValue());
                        break;
                    }
                }
            } else if (obj instanceof MatchDetail detail) {
                if (alias == null) {
                    if (targetVariableName.equals(detail.variableName().orElse(null))) {
                        valueOpt = Optional.ofNullable(detail.value());
                        break;
                    }
                }
            }
        }

        Column<?> column = table.column(columnName);
        if (!(column instanceof StringColumn strCol)) {
            logger.error("VariableColumn '{}' requires a StringColumn, but found {}", columnName, (column != null ? column.type() : "null"));
            return;
        }

        if (valueOpt.isPresent()) {
            Object value = valueOpt.get();
            strCol.set(rowIndex, value != null ? value.toString() : "");
            logger.trace("Set value '{}' for column '{}' at row {}", value, columnName, rowIndex);
        } else {
            strCol.setMissing(rowIndex);
            logger.trace("No matching detail found for column '{}' at row {}, setting missing.", columnName, rowIndex);
        }
    }
    
    @Override
    public String toString() {
        return "?" + targetVariableName;
    }
} 