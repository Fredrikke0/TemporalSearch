package com.example.query.executor;

import com.example.core.IndexAccess;
import com.example.core.Position;
import com.example.query.binding.MatchDetail;
import com.example.query.executor.QueryResult;
import com.example.query.binding.ValueType;
import com.example.query.model.JoinCondition;
import com.example.query.model.Query;
import com.example.query.model.SubquerySpec;
import com.example.query.model.TemporalPredicate;
import com.example.query.binding.JoinedMatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Handles the execution of JOIN operations between subquery QueryResult objects.
 */
public class JoinHandler {
    private static final Logger logger = LoggerFactory.getLogger(JoinHandler.class);

    /**
     * Creates a new JoinHandler.
     * Constructor might become empty or take different dependencies later
     * for different join strategies.
     */
    public JoinHandler() {
        // No dependencies needed for the basic implementation
    }

    /**
     * Executes the join specified in the query using pre-computed subquery QueryResults.
     *
     * @param query           The query containing the join condition and subquery definitions.
     * @param subqueryContext Context containing the results of executed subqueries as QueryResults.
     * @return A QueryResult representing the result of the join.
     * @throws QueryExecutionException if the join execution fails.
     */
    public List<JoinedMatch> handleJoin(
            Query query,
            SubqueryContext subqueryContext)
            throws QueryExecutionException {

        logger.debug("Handling JOIN operation based on subquery context results.");

        JoinCondition joinCondition = query.joinCondition().orElseThrow(() ->
                new QueryExecutionException("Join condition is required but missing in JoinHandler",
                        "join", QueryExecutionException.ErrorType.INTERNAL_ERROR));

        // 1. Extract left/right subquery aliases AND the main query source alias from joinCondition columns.
        //    The left alias should correspond to the source of the mainResult.
        //    The right alias corresponds to the subquery result needed from the context.
        String leftAlias = extractAliasFromColumnName(joinCondition.leftColumn()); 
        String rightAlias = extractAliasFromColumnName(joinCondition.rightColumn());
        String leftKey = extractKeyFromColumnName(joinCondition.leftColumn());
        String rightKey = extractKeyFromColumnName(joinCondition.rightColumn());

        // TODO: Validate that leftAlias actually matches the alias (if any) used for the main query part.
        // This might require passing the main query alias explicitly or inferring it.
        logger.debug("Joining subquery '{}' with subquery '{}' on keys: {}.{} == {}.{}",
                     leftAlias, rightAlias, leftAlias, leftKey, rightAlias, rightKey);

        // Get both QueryResults from subqueryContext using aliases
        QueryResult leftResult = subqueryContext.getQueryResult(leftAlias);
        QueryResult rightResult = subqueryContext.getQueryResult(rightAlias);

        if (leftResult == null) {
            throw new QueryExecutionException(
                String.format("Missing QueryResult for subquery '%s' in JOIN context", leftAlias),
                "join", QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }
        if (rightResult == null) {
            throw new QueryExecutionException(
                String.format("Missing QueryResult for subquery '%s' in JOIN context", rightAlias),
                "join", QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        List<MatchDetail> leftDetails = leftResult.getAllDetails();
        List<MatchDetail> rightDetails = rightResult.getAllDetails();

        logger.debug("Left QueryResult ('{}') has {} details, Right QueryResult ('{}') has {} details",
                     leftAlias, leftDetails.size(), rightAlias, rightDetails.size());

        // 3. Execute the join based on JoinCondition and MatchDetail properties.
        List<JoinedMatch> joinedDetails = new ArrayList<>();
        Query.Granularity resultGranularity = query.granularity();
        TemporalPredicate predicate = joinCondition.temporalPredicate();
        JoinCondition.JoinType joinType = joinCondition.type();
        Optional<Integer> proximityWindow = joinCondition.proximityWindow(); // Needed for PROXIMITY

        if (joinType == JoinCondition.JoinType.INNER) {
            logger.debug("Performing INNER JOIN with predicate {} on keys: {}.{} {} {}.{}",
                         predicate, leftAlias, leftKey, predicate, rightAlias, rightKey);

            // --- Always use Hash Join for now ---
            // In the future, compare with sort-merge join for performance/behavior.
            // The sort-merge join code is commented out below for future experimentation.
            //
            // Example (future):
            // List<JoinedMatch> joinedDetails = performSortMergeJoin(leftDetails, rightDetails, leftKey, rightKey);
            //
            // For now, always use hash join:
            joinedDetails = performHashJoinOnDate(leftDetails, rightDetails, leftKey, rightKey);
            //
            // --- Sort-Merge Join (for future comparison, currently disabled) ---
            // joinedDetails = performSortMergeJoin(leftDetails, rightDetails, leftKey, rightKey);
            // --- End Sort-Merge Join ---

        } else {
             logger.warn("Join type {} not yet implemented. Returning empty result.", joinType);
        }

        logger.debug("Join execution completed. Resulting join has {} pairs.", joinedDetails.size());

        int granularitySize = query.granularitySize().orElse(1);
        // Return the joined pairs directly for now (update QueryExecutor/TableResultService to consume this)
        return joinedDetails;
    }

    // --- Hash Join Implementation for Dates ---
    private record DocDateKey(int documentId, LocalDate dateValue) {}

    private List<JoinedMatch> performHashJoinOnDate(
            List<MatchDetail> leftDetails, List<MatchDetail> rightDetails, String leftKey, String rightKey)
    {
        // Group left details by (docId, dateValue)
        Map<DocDateKey, List<MatchDetail>> leftGrouped = groupDetailsByDocDate(leftDetails, leftKey);
        // Group right details by (docId, dateValue)
        Map<DocDateKey, List<MatchDetail>> rightGrouped = groupDetailsByDocDate(rightDetails, rightKey);

        List<JoinedMatch> joinedDetails = new ArrayList<>();
        // Iterate through the smaller map's keys for efficiency
        Map<DocDateKey, List<MatchDetail>> smallerMap = leftGrouped.size() < rightGrouped.size() ? leftGrouped : rightGrouped;
        Map<DocDateKey, List<MatchDetail>> largerMap = smallerMap == leftGrouped ? rightGrouped : leftGrouped;

        logger.debug("Performing hash join: Left groups = {}, Right groups = {}, Iterating smaller map (size {}).",
                     leftGrouped.size(), rightGrouped.size(), smallerMap.size());

        for (DocDateKey key : smallerMap.keySet()) {
            if (largerMap.containsKey(key)) {
                // Found a match for (docId, dateValue)
                List<MatchDetail> leftMatches = leftGrouped.get(key);
                List<MatchDetail> rightMatches = rightGrouped.get(key);

                // IMPORTANT: Add only ONE JoinedMatch per key to avoid duplicates from multiple mentions
                if (!leftMatches.isEmpty() && !rightMatches.isEmpty()) {
                    // Use the first match from each list as representatives
                    joinedDetails.add(new JoinedMatch(leftMatches.get(0), rightMatches.get(0)));
                     logger.trace("Hash join matched key {}: Added JoinedMatch({}, {})", key, leftMatches.get(0), rightMatches.get(0));
                } else {
                     logger.warn("Hash join matched key {} but one list was empty? Left: {}, Right: {}", key, leftMatches.size(), rightMatches.size());
                }
            } else {
                 logger.trace("Key {} from smaller map not found in larger map.", key);
            }
        }
        logger.debug("Hash join finished, produced {} pairs.", joinedDetails.size());
        return joinedDetails;
    }

    private Map<DocDateKey, List<MatchDetail>> groupDetailsByDocDate(List<MatchDetail> details, String dateKey) {
        Map<DocDateKey, List<MatchDetail>> grouped = new HashMap<>();
        for (MatchDetail detail : details) {
            Object val = extractValueForKey(detail, dateKey);
            if (val instanceof LocalDate dateValue) {
                DocDateKey key = new DocDateKey(detail.getDocumentId(), dateValue);
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(detail);
            }
        }
         logger.trace("Grouped {} details into {} DocDateKey groups for key '{}'.", details.size(), grouped.size(), dateKey);
        return grouped;
    }
    // --- End Hash Join Implementation ---

    /**
     * Extracts the alias part from a column name in the format "alias.key".
     *
     * @param columnName The column name (e.g., "subAlias.document_id")
     * @return The alias part (e.g., "subAlias")
     * @throws QueryExecutionException if the format is invalid
     */
    private String extractAliasFromColumnName(String columnName) throws QueryExecutionException {
        if (columnName == null || !columnName.contains(".")) {
            throw new QueryExecutionException(
                String.format("Join column name '%s' must be in the format 'alias.key'", columnName),
                "join", QueryExecutionException.ErrorType.INVALID_CONDITION);
        }
        return columnName.substring(0, columnName.indexOf('.'));
    }

    /**
     * Extracts the key part (attribute name) from a column name in the format "alias.key".
     *
     * @param columnName The column name (e.g., "subAlias.document_id")
     * @return The key part (e.g., "document_id")
     * @throws QueryExecutionException if the format is invalid
     */
    private String extractKeyFromColumnName(String columnName) throws QueryExecutionException {
        if (columnName == null || !columnName.contains(".")) {
            throw new QueryExecutionException(
                String.format("Join column name '%s' must be in the format 'alias.key'", columnName),
                "join", QueryExecutionException.ErrorType.INVALID_CONDITION);
        }
        int dotIndex = columnName.indexOf('.');
        if (dotIndex == columnName.length() - 1) {
             throw new QueryExecutionException(
                String.format("Join column name '%s' is missing key part after '.'", columnName),
                "join", QueryExecutionException.ErrorType.INVALID_CONDITION);
        }
        return columnName.substring(dotIndex + 1);
    }

    /**
     * Extracts the value corresponding to a specific key from a MatchDetail object.
     * Supports variable names (e.g., "?myVar") and common keys like "document_id", "sentence_id".
     *
     * @param detail The MatchDetail object
     * @param key The key to extract (e.g., "?myVar", "document_id")
     * @return The extracted value, or null if key is not supported or value is null.
     */
    private Object extractValueForKey(Object detailObj, String key) {
        if (detailObj == null || key == null) {
            return null;
        }
        // Handle JoinedMatch first (might occur in nested joins, though not currently supported)
        if (detailObj instanceof JoinedMatch joined) {
             // Simplified: Assume key directly matches intended part for now
             // A more robust solution might need alias context here too.
            Object leftVal = extractValueForKey(joined.left(), key);
            if (leftVal != null) return leftVal;
            return extractValueForKey(joined.right(), key);

        } else if (detailObj instanceof MatchDetail detail) {
             // Handle bound variables stored in MatchDetail (e.g., "q1.date")
            if (detail.variableName().isPresent()) {
                String storedVarName = detail.variableName().get();
                // Check if stored variable name has a dot (alias.key format)
                int dotIndex = storedVarName.indexOf('.');
                if (dotIndex != -1 && dotIndex < storedVarName.length() - 1) {
                    String storedBaseKey = storedVarName.substring(dotIndex + 1);
                    // Compare the requested key with the base key part of the stored variable
                    if (key.equals(storedBaseKey)) {
                        return detail.value();
                    }
                } else {
                    // Handle cases where variable name might not have an alias (shouldn't happen with current parsing?)
                     if (key.equals(storedVarName)) {
                         return detail.value();
                     }
                }
            }
            // Fallback to check structural keys if no variable matched
            return switch (key.toLowerCase()) {
                case "document_id" -> detail.getDocumentId();
                case "sentence_id" -> detail.getSentenceId() != -1 ? detail.getSentenceId() : null;
                // Add case for the "date" key specifically if it refers to the document date
                // case "date" -> detail.getDocumentDate(); // Example - uncomment if needed
                default -> null; // Key doesn't match variable or known structural key
            };
        }
        return null;
    }

    /**
     * Extracts the type corresponding to a specific key from a MatchDetail object.
     * Supports variable names (e.g., "?myVar") and common keys like "document_id", "sentence_id".
     *
     * @param detail The MatchDetail object
     * @param key The key to extract (e.g., "?myVar", "document_id")
     * @return The extracted type, or null if key is not supported or value is null.
     */
    private ValueType extractTypeForKey(Object detailObj, String key) {
         if (detailObj == null || key == null) {
            return null;
        }
        // Handle JoinedMatch first
        if (detailObj instanceof JoinedMatch joined) {
            // Simplified: Assume key directly matches intended part for now
            ValueType leftType = extractTypeForKey(joined.left(), key);
            if (leftType != null) return leftType;
            return extractTypeForKey(joined.right(), key);

        } else if (detailObj instanceof MatchDetail detail) {
             // Handle bound variables stored in MatchDetail (e.g., "q1.date")
             if (detail.variableName().isPresent()) {
                String storedVarName = detail.variableName().get();
                int dotIndex = storedVarName.indexOf('.');
                 if (dotIndex != -1 && dotIndex < storedVarName.length() - 1) {
                    String storedBaseKey = storedVarName.substring(dotIndex + 1);
                    if (key.equals(storedBaseKey)) {
                        return detail.valueType();
                    }
                } else {
                     if (key.equals(storedVarName)) {
                         return detail.valueType();
                     }
                 }
            }
            // Fallback for structural keys (return appropriate type if needed)
            return switch (key.toLowerCase()) {
                 // No specific ValueType for IDs, handled by direct key check
                 // case "document_id" -> ValueType.INTEGER;
                 // case "sentence_id" -> ValueType.INTEGER;
                 // case "date" -> ValueType.DATE; // Example for document date
                 default -> null;
            };
        }
        return null;
    }
} 