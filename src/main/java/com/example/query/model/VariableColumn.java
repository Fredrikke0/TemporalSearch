package com.example.query.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.query.executor.Bindings;
import com.example.query.executor.CellResult;

import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;

/**
 * Represents a variable column in the SELECT clause of a query.
 * This column selects the value of a variable binding from matching documents.
 */
public class VariableColumn implements SelectColumn {
    private static final Logger logger = LoggerFactory.getLogger(VariableColumn.class);

    private final String qualifiedVariableName;

    public VariableColumn(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank() || !qualifiedName.contains(".")) {
            throw new IllegalArgumentException(
                    "VariableColumn must be initialized with a qualified name (e.g., 'alias.var'), got: "
                            + qualifiedName);
        }
        this.qualifiedVariableName = qualifiedName;
    }

    @Override
    public String getColumnName() {
        return qualifiedVariableName;
    }

    @Override
    public Column<?> createColumn() {
        Column<?> col = StringColumn.create(qualifiedVariableName);
        logger.debug("VariableColumn creating column named '{}' of type {}", qualifiedVariableName, col.type());
        return col;
    }

    @Override
    public void populateColumn(Table table, int rowIndex,
            CellResult result, List<Integer> bindingIndices,
            String source,
            Map<String, IndexAccessInterface> indexes,
            Query query,
            Map<String, Object> contextCache) {
        logger.trace("Populating row {} for VariableColumn '{}'. binding indices count: {}",
                rowIndex, qualifiedVariableName, bindingIndices.size());

        Optional<Object> valueOpt = Optional.empty();
        Bindings bindings = result.bindings();
        if (bindings == null) {
            Column<?> column = table.column(qualifiedVariableName);
            if (column != null)
                column.setMissing(rowIndex);
            return;
        }

        for (int bindingIdx : bindingIndices) {
            String varNameInBinding = bindings.variableNameAt(bindingIdx);

            if (qualifiedVariableName.equals(varNameInBinding)) {
                valueOpt = Optional.ofNullable(bindings.valueAt(bindingIdx));
                logger.trace("Found match for variable '{}' in binding index {}. Value: '{}'",
                        qualifiedVariableName, bindingIdx, valueOpt.orElse("null"));
                break;
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
            logger.trace("Set value '{}' for column '{}' at row {}", valueOpt.orElse("null"), qualifiedVariableName,
                    rowIndex);
        } else {
            strCol.setMissing(rowIndex);
            logger.trace("No matching value found for column '{}' in binding indices for row {}, setting missing.",
                    qualifiedVariableName, rowIndex);
        }
    }

    @Override
    public String toString() {
        return qualifiedVariableName;
    }
}
