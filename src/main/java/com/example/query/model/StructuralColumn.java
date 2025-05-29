package com.example.query.model;

// import java.util.Optional; // No longer needed
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
// import com.example.query.binding.MatchDetail; // No longer needed
import com.example.query.executor.QueryResultSoA;
import com.example.query.sqlite.SqliteAccessor; // Needed for title fetching

import tech.tablesaw.api.DateColumn;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;

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
        // Log the field name being switched on
        String upperFieldName = fieldName.toUpperCase();
        logger.debug("Switching on upperFieldName: '{}'", upperFieldName);

        Column<?> createdCol = switch (upperFieldName) {
            case "TITLE" -> StringColumn.create(getColumnName());
            case "TIMESTAMP" -> DateColumn.create(getColumnName());
            case "DOCUMENT_ID" -> {
                 logger.debug("Creating IntColumn for {}", getColumnName());
                 yield IntColumn.create(getColumnName());
            }
            case "SENTENCE_ID" -> IntColumn.create(getColumnName());
            case "BEGIN" -> IntColumn.create(getColumnName());
            case "END" -> IntColumn.create(getColumnName());
            default -> {
                 logger.warn("Unknown structural field '{}' for alias '{}'. Defaulting to StringColumn.", fieldName, alias);
                 yield StringColumn.create(getColumnName()); // Default to String
            }
        };
        // Log the type right after the switch expression completes
        logger.debug("Switch expression yielded column '{}' of type {}", createdCol.name(), createdCol.type());
        return createdCol;
    }

    @Override
    public void populateColumn(Table table, int rowIndex,
                               QueryResultSoA resultSoA, List<Integer> indicesInSoA,
                               String source,
                               Map<String, IndexAccessInterface> indexes,
                               Query query,
                               Map<String, Object> contextCache) {
        if (indicesInSoA == null || indicesInSoA.isEmpty()) {
            logger.warn("StructuralColumn populateColumn received null or empty SoA indices for alias {}. Row: {}. Setting missing.", alias, rowIndex);
            table.column(getColumnName()).setMissing(rowIndex);
            return;
        }

        int firstSoAIndex = indicesInSoA.get(0); // Use the first match in the unit as reference
        int docIdForMetadata = resultSoA.getDocumentIdAt(firstSoAIndex);

        logger.trace("Populating StructuralColumn {}.{} at row {} using SoA index {}", alias, fieldName, rowIndex, firstSoAIndex);

        Column<?> column = table.column(getColumnName());

        try {
            switch (fieldName.toUpperCase()) {
                case "TITLE":
                    if (column instanceof StringColumn strCol) {
                        String cacheKey = "title_" + docIdForMetadata;
                        String title = (String) contextCache.get(cacheKey);
                        if (title == null) {
                            title = SqliteAccessor.getInstance().getMetadata(source, docIdForMetadata, "title");
                            title = (title != null) ? title : ""; // Ensure title is not null before caching
                            contextCache.put(cacheKey, title);
                            logger.trace("Fetched and cached TITLE '{}' for docId {}", title, docIdForMetadata);
                        } else {
                            logger.trace("Retrieved TITLE '{}' from cache for docId {}", title, docIdForMetadata);
                        }
                        strCol.set(rowIndex, title);
                        logger.trace("Set TITLE '{}' for {}.{} at row {}", title, alias, fieldName, rowIndex);
                    } else {
                         logger.error("Expected StringColumn for TITLE but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                    }
                    break;
                case "TIMESTAMP":
                     if (column instanceof DateColumn dateCol) {
                         String cacheKey = "timestamp_" + docIdForMetadata;
                         LocalDate docTimestamp = (LocalDate) contextCache.get(cacheKey);
                         if (docTimestamp == null) {
                             String timestampStr = SqliteAccessor.getInstance().getMetadata(source, docIdForMetadata, "timestamp");
                             if (timestampStr != null && !timestampStr.isEmpty()) {
                                 try {
                                     docTimestamp = LocalDate.parse(timestampStr); // Assuming ISO_LOCAL_DATE format
                                 } catch (java.time.format.DateTimeParseException e) {
                                     logger.warn("Failed to parse timestamp string '{}' for docId {}. Setting missing.", timestampStr, docIdForMetadata, e);
                                     docTimestamp = null; // Explicitly set to null if parsing fails
                                 }
                             }
                             if (docTimestamp != null) { // Only cache if successfully parsed
                                contextCache.put(cacheKey, docTimestamp);
                                logger.trace("Fetched and cached TIMESTAMP '{}' for docId {}", docTimestamp, docIdForMetadata);
                             } else {
                                logger.trace("Timestamp was null or unparseable for docId {}. Not caching.", docIdForMetadata);
                             }
                         } else {
                             logger.trace("Retrieved TIMESTAMP '{}' from cache for docId {}", docTimestamp, docIdForMetadata);
                         }

                         if (docTimestamp != null) {
                            dateCol.set(rowIndex, docTimestamp);
                            logger.trace("Set TIMESTAMP '{}' for {}.{} at row {}", docTimestamp, alias, fieldName, rowIndex);
                         } else {
                            dateCol.setMissing(rowIndex);
                            logger.trace("Set TIMESTAMP to missing for {}.{} at row {} due to null/unparseable date", alias, fieldName, rowIndex);
                         }
                     } else {
                         logger.error("Expected DateColumn for TIMESTAMP but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                     }
                    break;
                case "DOCUMENT_ID":
                     if (column instanceof IntColumn intCol) {
                         intCol.set(rowIndex, docIdForMetadata); // Already have this from resultSoA
                          logger.trace("Set DOCUMENT_ID '{}' for {}.{} at row {}", docIdForMetadata, alias, fieldName, rowIndex);
                     } else {
                         logger.error("Expected IntColumn for DOCUMENT_ID but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                     }
                    break;
                case "SENTENCE_ID":
                    if (column instanceof IntColumn intCol) {
                         // Ensure sentenceId is actually available and needed by requirements for this SoA entry
                         int sentenceId = resultSoA.getRequirements().needsSentenceId ? resultSoA.getSentenceIdAt(firstSoAIndex) : -1;
                         if (sentenceId != -1 || !resultSoA.getRequirements().needsSentenceId) { // if -1 but not needed, that's fine (means doc granularity)
                            intCol.set(rowIndex, sentenceId);
                            logger.trace("Set SENTENCE_ID '{}' for {}.{} at row {}", sentenceId, alias, fieldName, rowIndex);
                         } else {
                            logger.trace("Sentence ID requested but not available or not applicable for SoA index {}, setting missing for {}.{}", firstSoAIndex, alias, fieldName);
                            intCol.setMissing(rowIndex);
                         }
                     } else {
                         logger.error("Expected IntColumn for SENTENCE_ID but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                     }
                    break;
                case "BEGIN":
                    if (column instanceof IntColumn intCol) {
                        int beginChar = resultSoA.getRequirements().needsPositions ? resultSoA.getBeginCharAt(firstSoAIndex) : -1;
                        if (beginChar != -1 || !resultSoA.getRequirements().needsPositions) {
                            intCol.set(rowIndex, beginChar);
                            logger.trace("Set BEGIN '{}' for {}.{} at row {}", beginChar, alias, fieldName, rowIndex);
                        } else {
                            logger.trace("Begin char requested but not available for SoA index {}, setting missing for {}.{}", firstSoAIndex, alias, fieldName);
                            intCol.setMissing(rowIndex);
                        }
                    } else {
                         logger.error("Expected IntColumn for BEGIN but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                    }
                    break;
                case "END":
                    if (column instanceof IntColumn intCol) {
                        int endChar = resultSoA.getRequirements().needsPositions ? resultSoA.getEndCharAt(firstSoAIndex) : -1;
                         if (endChar != -1 || !resultSoA.getRequirements().needsPositions) {
                            intCol.set(rowIndex, endChar);
                            logger.trace("Set END '{}' for {}.{} at row {}", endChar, alias, fieldName, rowIndex);
                        } else {
                            logger.trace("End char requested but not available for SoA index {}, setting missing for {}.{}", firstSoAIndex, alias, fieldName);
                            intCol.setMissing(rowIndex);
                        }
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
             if (column != null) column.setMissing(rowIndex); // Ensure missing on error
        }
    }

    @Override
    public String toString() {
        return alias + "." + fieldName;
    }
}