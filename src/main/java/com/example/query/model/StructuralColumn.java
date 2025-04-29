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

    @Override
    public Column<?> createColumn() {
        // Determine column type based on field name
        return switch (fieldName.toUpperCase()) {
            case "TITLE" -> StringColumn.create(getColumnName());
            case "TIMESTAMP" -> DateColumn.create(getColumnName());
            case "DOCUMENT_ID" -> IntColumn.create(getColumnName());
            case "SENTENCE_ID" -> IntColumn.create(getColumnName());
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
        if (detailsForUnit == null || detailsForUnit.isEmpty()) {
            logger.warn("StructuralColumn populateColumn received empty details for alias {}. Row: {}", alias, rowIndex);
            table.column(getColumnName()).setMissing(rowIndex);
            return;
        }

        MatchDetail relevantDetail = null;
        boolean isJoinContext = detailsForUnit.size() == 2 && 
                                detailsForUnit.get(0) instanceof MatchDetail && 
                                detailsForUnit.get(1) instanceof MatchDetail;

        if (isJoinContext) {
            // --- Handle JOIN context --- 
            MatchDetail leftDetail = (MatchDetail) detailsForUnit.get(0);
            MatchDetail rightDetail = (MatchDetail) detailsForUnit.get(1);

            Optional<String> mainAliasOpt = query.mainAlias();
            Optional<String> subqueryAliasOpt = (query.subqueries() != null && !query.subqueries().isEmpty())
                                                ? Optional.of(query.subqueries().get(0).alias())
                                                : Optional.empty();
            
            // Assumption: leftDetail corresponds to the main query alias, rightDetail to the subquery alias.
            if (mainAliasOpt.isPresent() && this.alias.equals(mainAliasOpt.get())) {
                relevantDetail = leftDetail;
                logger.trace("StructuralColumn [Join]: Alias '{}' matches main query alias '{}'. Using left detail.", this.alias, mainAliasOpt.get());
            } else if (subqueryAliasOpt.isPresent() && this.alias.equals(subqueryAliasOpt.get())) {
                relevantDetail = rightDetail;
                logger.trace("StructuralColumn [Join]: Alias '{}' matches subquery alias '{}'. Using right detail.", this.alias, subqueryAliasOpt.get());
            } else {
                 logger.warn("StructuralColumn [Join]: Alias '{}' does not match main alias ('{}') or subquery alias ('{}'). Cannot determine relevant detail for row {}.",
                          this.alias, mainAliasOpt.orElse("N/A"), subqueryAliasOpt.orElse("N/A"), rowIndex);
            }

        } else if (detailsForUnit.get(0) instanceof MatchDetail) {
            // --- Handle NON-JOIN context --- 
            // All details in the list belong to the same document/sentence unit.
            // Use the first detail to get the necessary context (like document ID).
            // We still need to ensure this StructuralColumn's alias matches the query's main alias.
            relevantDetail = (MatchDetail) detailsForUnit.get(0); 
            Optional<String> mainAliasOpt = query.mainAlias();

            // Validate alias match even in non-join context if main alias exists
            if (mainAliasOpt.isPresent() && !this.alias.equals(mainAliasOpt.get())) {
                 logger.warn("StructuralColumn [Non-Join]: Column alias '{}' does not match query main alias '{}'. Row: {}",
                          this.alias, mainAliasOpt.get(), rowIndex);
                 relevantDetail = null; // Set relevantDetail to null if aliases don't match
            } else if (mainAliasOpt.isEmpty() && !this.alias.equals("$main")) {
                // If no main alias explicitly defined in query, parser defaults to "$main"
                // Check if this column's alias is the default "$main"
                logger.trace("StructuralColumn [Non-Join]: No main alias in query, checking against default '$main' for column alias '{}'.", this.alias);
                if (!this.alias.equals("$main")) {
                     logger.warn("StructuralColumn [Non-Join]: Column alias '{}' does not match implicit main alias '$main'. Row: {}",
                              this.alias, rowIndex);
                     relevantDetail = null;
                }
            }
             // If aliases match (or no main alias to check against the default), relevantDetail remains set.
            logger.trace("StructuralColumn [Non-Join]: Using first detail for alias '{}'. Row: {}", this.alias, rowIndex);

        } else {
            // --- Handle unexpected context --- 
             logger.warn("StructuralColumn populateColumn received unexpected detailsForUnit content type: {} for alias {}. Row: {}", 
                        detailsForUnit.get(0).getClass().getName(), alias, rowIndex);
             table.column(getColumnName()).setMissing(rowIndex);
             return;
        }
        

        if (relevantDetail == null) {
            logger.trace("No relevant detail could be determined for row {} in StructuralColumn {}.{}. Setting missing.", rowIndex, alias, fieldName);
            table.column(getColumnName()).setMissing(rowIndex);
            return;
        }

        // --- Populate based on relevantDetail --- 
        Column<?> column = table.column(getColumnName());

        try {
            switch (fieldName.toUpperCase()) {
                case "TITLE":
                    if (column instanceof StringColumn strCol) {
                        // Use SqliteAccessor to get the title for the correct document ID
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
                         // Get timestamp from Position object of the correct detail
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