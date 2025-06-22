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

        Integer docIdForAlias = null;
        Integer sentenceIdForAlias = null;
        Integer beginCharForAlias = null;
        Integer endCharForAlias = null;
        boolean foundRelevantEntry = false;

        for (int soaIndex : indicesInSoA) {
            String entryVarName = resultSoA.getVariableNameAt(soaIndex);
            String determinedEntryAlias;

            if (entryVarName != null && entryVarName.contains(".")) {
                determinedEntryAlias = entryVarName.substring(0, entryVarName.indexOf('.'));
            } else {
                // If no explicit alias qualification (no '.'), or varName is null (e.g. for a raw condition match),
                // it implies the entry belongs to the default main alias of the conceptual row component it originated from.
                // In a joined SoA, QueryExecutor/JoinHandler are responsible for ensuring variable names are qualified if they came from a sub-alias.
                // So, if it's not qualified here, it's effectively part of the "main" context of this conceptual row segment.
                determinedEntryAlias = com.example.query.parser.QueryModelBuilder.DEFAULT_MAIN_ALIAS;
            }

            if (this.alias.equals(determinedEntryAlias)) {
                docIdForAlias = resultSoA.getDocumentIdAt(soaIndex);
                if (resultSoA.getRequirements().needsSentenceId) {
                    sentenceIdForAlias = resultSoA.getSentenceIdAt(soaIndex);
                }
                if (resultSoA.getRequirements().needsPositions) {
                    beginCharForAlias = resultSoA.getBeginCharAt(soaIndex);
                    endCharForAlias = resultSoA.getEndCharAt(soaIndex);
                }
                foundRelevantEntry = true;
                logger.trace("Found relevant entry for StructuralColumn {}.{} at soaIndex {}: docId={}, sentenceId={}, begin={}, end={}",
                             this.alias, this.fieldName, soaIndex, docIdForAlias, sentenceIdForAlias, beginCharForAlias, endCharForAlias);
                break;
            }
        }

        if (!foundRelevantEntry) {
            logger.warn("StructuralColumn {}.{} did not find a matching entry for its alias '{}' within the provided SoA indices for conceptual row. Row: {}. Setting missing.",
                        this.alias, this.fieldName, this.alias, rowIndex);
            table.column(getColumnName()).setMissing(rowIndex);
            return;
        }

        // Use docIdForAlias which is now specific to 'this.alias'
        final int effectiveDocId = docIdForAlias; // docIdForAlias must be non-null if foundRelevantEntry is true

        logger.trace("Populating StructuralColumn {}.{} at row {} using effectiveDocId {}", this.alias, this.fieldName, rowIndex, effectiveDocId);

        Column<?> column = table.column(getColumnName());

        try {
            switch (fieldName.toUpperCase()) {
                case "TITLE":
                    if (column instanceof StringColumn strCol) {
                        String cacheKey = "title_" + effectiveDocId; // Use effectiveDocId
                        String title = (String) contextCache.get(cacheKey);
                        if (title == null) {
                            title = SqliteAccessor.getInstance().getMetadata(source, effectiveDocId, "title"); // Use effectiveDocId
                            title = (title != null) ? title : "";
                            contextCache.put(cacheKey, title);
                            logger.trace("Fetched and cached TITLE '{}' for docId {}", title, effectiveDocId);
                        } else {
                            logger.trace("Retrieved TITLE '{}' from cache for docId {}", title, effectiveDocId);
                        }
                        strCol.set(rowIndex, title);
                        logger.trace("Set TITLE '{}' for {}.{} at row {}", title, this.alias, this.fieldName, rowIndex);
                    } else {
                         logger.error("Expected StringColumn for TITLE but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                    }
                    break;
                case "TIMESTAMP":
                     if (column instanceof DateColumn dateCol) {
                         String cacheKey = "timestamp_" + effectiveDocId; // Use effectiveDocId
                         LocalDate docTimestamp = (LocalDate) contextCache.get(cacheKey);
                         if (docTimestamp == null) {
                             String timestampStr = SqliteAccessor.getInstance().getMetadata(source, effectiveDocId, "timestamp"); // Use effectiveDocId
                             if (timestampStr != null && !timestampStr.isEmpty()) {
                                 try {
                                     docTimestamp = java.time.LocalDateTime.parse(timestampStr).toLocalDate();
                                 } catch (java.time.format.DateTimeParseException e) {
                                     logger.warn("Failed to parse timestamp string '{}' for docId {}. Setting missing.", timestampStr, effectiveDocId, e);
                                     docTimestamp = null;
                                 }
                             }
                             if (docTimestamp != null) {
                                contextCache.put(cacheKey, docTimestamp);
                                logger.trace("Fetched and cached TIMESTAMP '{}' for docId {}", docTimestamp, effectiveDocId);
                             } else {
                                logger.trace("Timestamp was null or unparseable for docId {}. Not caching.", effectiveDocId);
                             }
                         } else {
                             logger.trace("Retrieved TIMESTAMP '{}' from cache for docId {}", docTimestamp, effectiveDocId);
                         }

                         if (docTimestamp != null) {
                            dateCol.set(rowIndex, docTimestamp);
                            logger.trace("Set TIMESTAMP '{}' for {}.{} at row {}", docTimestamp, this.alias, this.fieldName, rowIndex);
                         } else {
                            dateCol.setMissing(rowIndex);
                            logger.trace("Set TIMESTAMP to missing for {}.{} at row {} due to null/unparseable date", this.alias, this.fieldName, rowIndex);
                         }
                     } else {
                         logger.error("Expected DateColumn for TIMESTAMP but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                     }
                    break;
                case "DOCUMENT_ID":
                     if (column instanceof IntColumn intCol) {
                         intCol.set(rowIndex, effectiveDocId); // Use effectiveDocId
                          logger.trace("Set DOCUMENT_ID '{}' for {}.{} at row {}", effectiveDocId, this.alias, this.fieldName, rowIndex);
                     } else {
                         logger.error("Expected IntColumn for DOCUMENT_ID but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                     }
                    break;
                case "SENTENCE_ID":
                    if (column instanceof IntColumn intCol) {
                         // Use sentenceIdForAlias, which was fetched based on this.alias
                         if (sentenceIdForAlias != null) {
                            intCol.set(rowIndex, sentenceIdForAlias);
                            logger.trace("Set SENTENCE_ID '{}' for {}.{} at row {}", sentenceIdForAlias, this.alias, this.fieldName, rowIndex);
                         } else {
                            // This case means either sentence IDs were not required by SoA,
                            // or no entry matching this.alias was found that had a sentenceId.
                            // The foundRelevantEntry check should have caught the latter.
                            // If sentenceIds were not required by SoA at all, sentenceIdForAlias would be null.
                            logger.trace("Sentence ID for {}.{} at row {} is not available or not applicable (sentenceIdForAlias is null). Setting missing.", this.alias, this.fieldName, rowIndex);
                            intCol.setMissing(rowIndex);
                         }
                     } else {
                         logger.error("Expected IntColumn for SENTENCE_ID but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                     }
                    break;
                case "BEGIN":
                    if (column instanceof IntColumn intCol) {
                        if (beginCharForAlias != null) {
                            intCol.set(rowIndex, beginCharForAlias);
                            logger.trace("Set BEGIN '{}' for {}.{} at row {}", beginCharForAlias, this.alias, this.fieldName, rowIndex);
                        } else {
                            logger.trace("Begin char for {}.{} at row {} is not available. Setting missing.", this.alias, this.fieldName, rowIndex);
                            intCol.setMissing(rowIndex);
                        }
                    } else {
                         logger.error("Expected IntColumn for BEGIN but got {} for column {}", column.type(), getColumnName());
                         column.setMissing(rowIndex);
                    }
                    break;
                case "END":
                    if (column instanceof IntColumn intCol) {
                        if (endCharForAlias != null) {
                            intCol.set(rowIndex, endCharForAlias);
                            logger.trace("Set END '{}' for {}.{} at row {}", endCharForAlias, this.alias, this.fieldName, rowIndex);
                        } else {
                            logger.trace("End char for {}.{} at row {} is not available. Setting missing.", this.alias, this.fieldName, rowIndex);
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