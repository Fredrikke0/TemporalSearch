package com.example.query.model;

import com.example.core.IndexAccessInterface;
import com.example.query.binding.MatchDetail;
import com.example.query.sqlite.SqliteAccessor; // Needed for title fetching
import tech.tablesaw.api.*;
import tech.tablesaw.columns.Column;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Optional;

/**
 * Represents a structural column like alias.TITLE or alias.TIMESTAMP.
 */
public class StructuralColumn implements SelectColumn {
    private static final Logger logger = LoggerFactory.getLogger(StructuralColumn.class);

    private final String alias;
    private final String fieldName; // e.g., TITLE, TIMESTAMP, DOCUMENT_ID, SENTENCE_ID

    public StructuralColumn(String alias, String fieldName) {
        this.alias = Objects.requireNonNull(alias, "alias cannot be null");
        this.fieldName = Objects.requireNonNull(fieldName, "fieldName cannot be null");
        logger.trace("Created StructuralColumn for {}.{}", alias, fieldName);
    }

    @Override
    public String getColumnName() {
        // Column name in the final table will be alias.fieldName
        return alias + "." + fieldName;
    }

    // --- Add Getters --- 
    public String getAlias() {
        return alias;
    }

    public String getFieldName() {
        return fieldName;
    }
    // --- End Getters --- 

    @Override
    public Column<?> createColumn() {
        // Determine column type based on field name
        return switch (fieldName.toUpperCase()) {
            case "TITLE" -> StringColumn.create(getColumnName());
            case "TIMESTAMP" -> DateColumn.create(getColumnName());
            case "DOCUMENT_ID" -> IntColumn.create(getColumnName());
            case "SENTENCE_ID" -> IntColumn.create(getColumnName());
            case "BEGIN" -> IntColumn.create(getColumnName());
            case "END" -> IntColumn.create(getColumnName());
            default -> {
                 logger.warn("Unknown structural field '{}' for alias '{}'. Defaulting to StringColumn.", fieldName, alias);
                 yield StringColumn.create(getColumnName()); // Default to String
            }
        };
    }

    @Override
    public void populateColumn(Table table, int rowIndex, List<?> detailsForUnit, String source, Map<String, IndexAccessInterface> indexes) {
        throw new UnsupportedOperationException("StructuralColumn requires Query context. Use populateColumn with Query parameter.");
    }

    @Override
    public void populateColumn(Table table, int rowIndex, List<?> detailsForUnit, String source, Map<String, IndexAccessInterface> indexes, Query query) {
        if (detailsForUnit == null || detailsForUnit.isEmpty() || !(detailsForUnit.get(0) instanceof MatchDetail)) {
            logger.warn("StructuralColumn populateColumn received null, empty, or non-MatchDetail list for alias {}. Row: {}. Setting missing.", alias, rowIndex);
            table.column(getColumnName()).setMissing(rowIndex);
            return;
        }

        // Assume the single detail passed IS the relevant one.
        // The caller (TableResultService) is responsible for selecting the correct detail (left/right).
        MatchDetail relevantDetail = (MatchDetail) detailsForUnit.get(0);
        logger.trace("Populating StructuralColumn {}.{} at row {} using provided detail", alias, fieldName, rowIndex);

        // --- Populate based on relevantDetail --- 
        Column<?> column = table.column(getColumnName());

        try {
            switch (fieldName.toUpperCase()) {
                case "TITLE":
                    if (column instanceof StringColumn strCol) {
                        String title = SqliteAccessor.getInstance().getMetadata(source, relevantDetail.getDocumentId(), "title");
                        strCol.set(rowIndex, title != null ? title : "");
                        logger.trace("Set TITLE '{}' for {}.{} at row {}", title, alias, fieldName, rowIndex);
                    } else {
                         logger.error("Expected StringColumn for TITLE but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                    }
                    break;
                case "TIMESTAMP":
                     if (column instanceof DateColumn dateCol) {
                         dateCol.set(rowIndex, relevantDetail.position().getTimestamp());
                         logger.trace("Set TIMESTAMP '{}' for {}.{} at row {}", relevantDetail.position().getTimestamp(), alias, fieldName, rowIndex);
                     } else {
                         logger.error("Expected DateColumn for TIMESTAMP but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                     }
                    break;
                case "DOCUMENT_ID":
                     if (column instanceof IntColumn intCol) {
                         intCol.set(rowIndex, relevantDetail.getDocumentId());
                          logger.trace("Set DOCUMENT_ID '{}' for {}.{} at row {}", relevantDetail.getDocumentId(), alias, fieldName, rowIndex);
                     } else {
                         logger.error("Expected IntColumn for DOCUMENT_ID but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                     }
                    break;
                case "SENTENCE_ID":
                    if (column instanceof IntColumn intCol) {
                         intCol.set(rowIndex, relevantDetail.getSentenceId());
                         logger.trace("Set SENTENCE_ID '{}' for {}.{} at row {}", relevantDetail.getSentenceId(), alias, fieldName, rowIndex);
                     } else {
                         logger.error("Expected IntColumn for SENTENCE_ID but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                     }
                    break;
                case "BEGIN":
                    if (column instanceof IntColumn intCol) {
                        intCol.set(rowIndex, relevantDetail.position().getBeginPosition());
                        logger.trace("Set BEGIN '{}' for {}.{} at row {}", relevantDetail.position().getBeginPosition(), alias, fieldName, rowIndex);
                    } else {
                         logger.error("Expected IntColumn for BEGIN but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                    }
                    break;
                case "END":
                    if (column instanceof IntColumn intCol) {
                        intCol.set(rowIndex, relevantDetail.position().getEndPosition());
                        logger.trace("Set END '{}' for {}.{} at row {}", relevantDetail.position().getEndPosition(), alias, fieldName, rowIndex);
                    } else {
                         logger.error("Expected IntColumn for END but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                    }
                    break;
                default:
                    logger.warn("Unhandled structural field '{}' in populateColumn. Setting missing for column {}.", fieldName, getColumnName());
                    column.setMissing(rowIndex);
                    break;
            }
        } catch (Exception e) {
             logger.error("Error populating StructuralColumn {}.{} at row {}: {}", alias, fieldName, rowIndex, e.getMessage(), e);
             column.setMissing(rowIndex);
        }
    }

    @Override
    public String toString() {
        return alias + "." + fieldName;
    }
} 