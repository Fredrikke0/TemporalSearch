package com.example.query.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.query.executor.Bindings;
import com.example.query.executor.CellResult;
import com.example.query.sqlite.SqliteAccessor;

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
    private final String fieldName;

    public StructuralColumn(String alias, String fieldName) {
        this.alias = Objects.requireNonNull(alias, "alias cannot be null");
        this.fieldName = Objects.requireNonNull(fieldName, "fieldName cannot be null");
        logger.trace("Created StructuralColumn for {}.{}", alias, fieldName);
    }

    @Override
    public String getColumnName() {
        return alias + "." + fieldName;
    }

    public String getAlias() {
        return alias;
    }

    public String getFieldName() {
        return fieldName;
    }

    @Override
    public Column<?> createColumn() {
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
                logger.warn("Unknown structural field '{}' for alias '{}'. Defaulting to StringColumn.", fieldName,
                        alias);
                yield StringColumn.create(getColumnName());
            }
        };
        return createdCol;
    }

    @Override
    public void populateColumn(Table table, int rowIndex,
            CellResult result, List<Integer> bindingIndices,
            String source,
            Map<String, IndexAccessInterface> indexes,
            Query query,
            Map<String, Object> contextCache) {
        if (bindingIndices == null || bindingIndices.isEmpty()) {
            logger.warn(
                    "StructuralColumn populateColumn received null or empty binding indices for alias {}. Row: {}. Setting missing.",
                    alias, rowIndex);
            table.column(getColumnName()).setMissing(rowIndex);
            return;
        }

        // Extract doc/sent from contextCache (set by TableResultService)
        // or fall back to the first cell in the CellResult.
        int effectiveDocId = -1;
        int effectiveSentenceId = -1;
        Long cellKeyFromContext = (Long) contextCache.get("_cellKey");
        if (cellKeyFromContext != null) {
            effectiveDocId = PostingList.docIdFromCellKey(cellKeyFromContext);
            effectiveSentenceId = (int) (cellKeyFromContext & 0xFFFF_FFFFL);
        } else {
            Roaring64NavigableMap cells = result.cells();
            if (cells != null && !cells.isEmpty()) {
                long firstCell = cells.first();
                effectiveDocId = PostingList.docIdFromCellKey(firstCell);
                effectiveSentenceId = (int) (firstCell & 0xFFFF_FFFFL);
            }
        }

        boolean foundRelevantEntry = false;
        Bindings bindings = result.bindings();
        if (bindings != null) {
            for (int bindingIdx : bindingIndices) {
                String entryVarName = bindings.variableNameAt(bindingIdx);
                String determinedEntryAlias;

                if (entryVarName != null && entryVarName.contains(".")) {
                    determinedEntryAlias = entryVarName.substring(0, entryVarName.indexOf('.'));
                } else {
                    determinedEntryAlias = com.example.query.parser.QueryModelBuilder.DEFAULT_MAIN_ALIAS;
                }

                if (this.alias.equals(determinedEntryAlias)) {
                    foundRelevantEntry = true;
                    logger.trace(
                            "Found relevant entry for StructuralColumn {}.{} at bindingIdx {}: docId={}, sentenceId={}",
                            this.alias, this.fieldName, bindingIdx, effectiveDocId, effectiveSentenceId);
                    break;
                }
            }
        }

        if (!foundRelevantEntry && effectiveDocId < 0) {
            logger.warn(
                    "StructuralColumn {}.{} did not find a matching entry for its alias '{}'. Row: {}. Setting missing.",
                    this.alias, this.fieldName, this.alias, rowIndex);
            table.column(getColumnName()).setMissing(rowIndex);
            return;
        }

        final int docId = effectiveDocId;
        final int sentId = effectiveSentenceId;

        logger.trace("Populating StructuralColumn {}.{} at row {} using docId {}", this.alias, this.fieldName, rowIndex,
                docId);

        Column<?> column = table.column(getColumnName());

        try {
            switch (fieldName.toUpperCase()) {
                case "TITLE":
                    if (column instanceof StringColumn strCol) {
                        String cacheKey = "title_" + docId;
                        String title = (String) contextCache.get(cacheKey);
                        if (title == null) {
                            title = SqliteAccessor.getInstance().getMetadata(source, docId, "title");
                            title = (title != null) ? title : "";
                            contextCache.put(cacheKey, title);
                        }
                        strCol.set(rowIndex, title);
                    } else {
                        column.setMissing(rowIndex);
                    }
                    break;
                case "TIMESTAMP":
                    if (column instanceof DateColumn dateCol) {
                        String cacheKey = "timestamp_" + docId;
                        LocalDate docTimestamp = (LocalDate) contextCache.get(cacheKey);
                        if (docTimestamp == null) {
                            String timestampStr = SqliteAccessor.getInstance().getMetadata(source, docId,
                                    "timestamp");
                            if (timestampStr != null && !timestampStr.isEmpty()) {
                                try {
                                    docTimestamp = java.time.LocalDateTime.parse(timestampStr).toLocalDate();
                                } catch (java.time.format.DateTimeParseException e) {
                                    logger.warn("Failed to parse timestamp string '{}' for docId {}.", timestampStr,
                                            docId, e);
                                    docTimestamp = null;
                                }
                            }
                            if (docTimestamp != null) {
                                contextCache.put(cacheKey, docTimestamp);
                            }
                        }
                        if (docTimestamp != null) {
                            dateCol.set(rowIndex, docTimestamp);
                        } else {
                            dateCol.setMissing(rowIndex);
                        }
                    } else {
                        column.setMissing(rowIndex);
                    }
                    break;
                case "DOCUMENT_ID":
                    if (column instanceof IntColumn intCol) {
                        intCol.set(rowIndex, docId);
                    } else {
                        column.setMissing(rowIndex);
                    }
                    break;
                case "SENTENCE_ID":
                    if (column instanceof IntColumn intCol) {
                        if (sentId >= 0) {
                            intCol.set(rowIndex, sentId);
                        } else {
                            intCol.setMissing(rowIndex);
                        }
                    } else {
                        column.setMissing(rowIndex);
                    }
                    break;
                case "BEGIN":
                case "END":
                    // Not available in CellResult bindings
                    if (column != null)
                        column.setMissing(rowIndex);
                    break;
                default:
                    logger.warn("Unhandled structural field '{}' in populateColumn. Setting missing for column {}.",
                            fieldName, getColumnName());
                    column.setMissing(rowIndex);
                    break;
            }
        } catch (Exception e) {
            logger.error("Error populating StructuralColumn {}.{} at row {}: {}", alias, fieldName, rowIndex,
                    e.getMessage(), e);
            if (column != null)
                column.setMissing(rowIndex);
        }
    }

    @Override
    public String toString() {
        return alias + "." + fieldName;
    }
}
