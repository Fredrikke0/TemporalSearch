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
import java.util.Comparator;

/**
 * Handles the execution of JOIN operations between subquery QueryResult objects.
 */
public class JoinHandler {
    private static final Logger logger = LoggerFactory.getLogger(JoinHandler.class);

    /**
     * Helper record to associate a MatchDetail with its extracted temporal value for sorting.
     */
    private record TemporalMatch(LocalDate date, MatchDetail detail) {}

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

        // Add INFO logging for the content of the lists (for debugging)
        if (logger.isInfoEnabled()) {
            String leftDetailsString = leftDetails.stream()
                .limit(20) // Limit to first 20
                .map(Object::toString) // Assuming MatchDetail.toString() is suitable
                .collect(Collectors.joining("\n    ", "\n    ", "")); // Indent each detail
            String rightDetailsString = rightDetails.stream()
                .limit(20) // Limit to first 20
                .map(Object::toString)
                .collect(Collectors.joining("\n    ", "\n    ", ""));

            // Add indication if list was truncated
            String leftCountSuffix = leftDetails.size() > 20 ? " (showing first 20)" : "";
            String rightCountSuffix = rightDetails.size() > 20 ? " (showing first 20)" : "";

            logger.info("Attempting JOIN. Left Details (size={}{}):{}{}\nRight Details (size={}{}):{}{}",
                        leftDetails.size(),
                        leftCountSuffix,
                        leftDetailsString.isEmpty() ? " [EMPTY]" : "", leftDetailsString,
                        rightDetails.size(),
                        rightCountSuffix,
                        rightDetailsString.isEmpty() ? " [EMPTY]" : "", rightDetailsString);
        }

        // 3. Execute the join based on JoinCondition and MatchDetail properties.
        List<JoinedMatch> joinedDetails = new ArrayList<>();
        Query.Granularity resultGranularity = query.granularity();
        JoinCondition.JoinType joinType = joinCondition.type();
        JoinCondition.JoinOperatorType operatorType = joinCondition.operatorType(); // Get operator type
        Optional<TemporalPredicate> temporalPredicateOpt = joinCondition.temporalPredicate();
        Optional<Integer> proximityWindow = joinCondition.proximityWindow(); // Needed for PROXIMITY

        if (joinType == JoinCondition.JoinType.INNER) {
            if (operatorType == JoinCondition.JoinOperatorType.EQUALITY) {
                logger.debug("Performing INNER EQUALITY JOIN on keys: {}.{} == {}.{}",
                             leftAlias, leftKey, rightAlias, rightKey);
                // If both keys are 'date', use the date-specific hash join
                if (leftKey.equals("date") && rightKey.equals("date")) {
                     // Check if values are actually LocalDate before calling date-specific join
                    ValueType leftType = extractTypeForKey(leftDetails.isEmpty() ? null : leftDetails.get(0), leftKey);
                    ValueType rightType = extractTypeForKey(rightDetails.isEmpty() ? null : rightDetails.get(0), rightKey);
                    if (leftType == ValueType.DATE && rightType == ValueType.DATE) {
                        joinedDetails = performHashJoinOnDate(leftDetails, rightDetails, leftKey, rightKey);
                    } else {
                        logger.warn("Equality join requested on 'date' keys, but types are not LocalDate ({}={}, {}={}). Falling back to generic hash join.",
                                    leftKey, leftType, rightKey, rightType);
                        joinedDetails = performGenericHashJoin(leftDetails, rightDetails, leftKey, rightKey);
                    }
                } else {
                    // Generic hash join for any key type
                    joinedDetails = performGenericHashJoin(leftDetails, rightDetails, leftKey, rightKey);
                }

            } else if (operatorType == JoinCondition.JoinOperatorType.TEMPORAL) {
                TemporalPredicate predicate = temporalPredicateOpt.orElseThrow(() ->
                    new QueryExecutionException("Temporal predicate is required for TEMPORAL join type",
                            "join", QueryExecutionException.ErrorType.INTERNAL_ERROR));

                logger.debug("Performing INNER TEMPORAL JOIN with predicate {} on keys: {}.{} {} {}.{}",
                             predicate, leftAlias, leftKey, predicate, rightAlias, rightKey);

                if (predicate == TemporalPredicate.BEFORE || predicate == TemporalPredicate.AFTER) {
                    // Use Sort-Merge for BEFORE/AFTER
                    joinedDetails = performTemporalSortMergeJoin(leftDetails, rightDetails, leftKey, rightKey, predicate);
                } else if (predicate == TemporalPredicate.INTERSECT || predicate == TemporalPredicate.EQUAL) {
                    // Use Hash Join for INTERSECT/EQUAL (on dates, this is effectively equality)
                    logger.debug("Temporal predicate {} identified, using Hash Join strategy.", predicate);
                    if (leftKey.equals("date") && rightKey.equals("date")) {
                        // Directly use the date-specific hash join.
                        // It internally handles filtering details that don't have a LocalDate value.
                        logger.debug("Joining on 'date' keys using performHashJoinOnDate.");
                        joinedDetails = performHashJoinOnDate(leftDetails, rightDetails, leftKey, rightKey);
                    } else {
                        // If keys are not 'date', use generic hash join for INTERSECT/EQUAL
                        logger.warn("Temporal join predicate {} used with non-'date' keys ({}={}, {}={}). Using generic hash join.",
                                    predicate, leftAlias, leftKey, rightAlias, rightKey);
                        joinedDetails = performGenericHashJoin(leftDetails, rightDetails, leftKey, rightKey);
                    }
                } else if (predicate == TemporalPredicate.PROXIMITY) {
                    logger.warn("Temporal join predicate {} (PROXIMITY) not yet implemented. Returning empty result.", predicate);
                    // TODO: Implement Proximity Join (likely different algorithm)
                } else {
                     // Handle other unimplemented temporal predicates (CONTAINS, CONTAINED_BY, etc.)
                     logger.warn("Temporal join predicate {} not yet implemented. Returning empty result.", predicate);
                }

            } else {
                // Should not happen due to enum completeness
                logger.error("Unhandled JoinOperatorType: {}. Returning empty result.", operatorType);
            }

        } else {
             logger.warn("Join type {} not yet implemented. Returning empty result.", joinType);
        }

        logger.debug("Join execution completed. Resulting join has {} pairs.", joinedDetails.size());

        int granularitySize = query.granularitySize().orElse(1);
        // Return the joined pairs directly for now (update QueryExecutor/TableResultService to consume this)
        return joinedDetails;
    }

    // --- Hash Join Implementation for Dates ---
    private Map<LocalDate, List<MatchDetail>> groupDetailsByDate(List<MatchDetail> details, String dateKey) {
        Map<LocalDate, List<MatchDetail>> grouped = new HashMap<>();
        for (MatchDetail detail : details) {
            Object val = extractValueForKey(detail, dateKey);
            // Ensure the value is actually a LocalDate before adding
            if (val instanceof LocalDate dateValue) {
                grouped.computeIfAbsent(dateValue, k -> new ArrayList<>()).add(detail);
            } else if (val != null) {
                 logger.warn("Expected LocalDate for key '{}' but got type {}. Skipping detail: {}", dateKey, val.getClass().getName(), detail);
            }
        }
         logger.trace("Grouped {} details into {} LocalDate groups for key '{}'.", details.size(), grouped.size(), dateKey);
        return grouped;
    }

    private List<JoinedMatch> performHashJoinOnDate(
            List<MatchDetail> leftDetails, List<MatchDetail> rightDetails, String leftKey, String rightKey)
    {
        Map<LocalDate, List<MatchDetail>> leftGrouped = groupDetailsByDate(leftDetails, leftKey);
        Map<LocalDate, List<MatchDetail>> rightGrouped = groupDetailsByDate(rightDetails, rightKey);

        if (logger.isDebugEnabled()) {
            String rightDates = rightGrouped.keySet().stream()
                                          .map(LocalDate::toString)
                                          .sorted()
                                          .collect(Collectors.joining(", "));
            logger.debug("Distinct dates found in right details ({} total): {}", rightGrouped.size(), rightDates);
        }

        List<JoinedMatch> joinedDetails = new ArrayList<>();
        // Iterate through the smaller map's keys for efficiency
        Map<LocalDate, List<MatchDetail>> smallerMap = leftGrouped.size() < rightGrouped.size() ? leftGrouped : rightGrouped;
        Map<LocalDate, List<MatchDetail>> largerMap = smallerMap == leftGrouped ? rightGrouped : leftGrouped;

        logger.debug("Performing hash join on DATE: Left groups = {}, Right groups = {}, Iterating smaller map (size {}).",
                     leftGrouped.size(), rightGrouped.size(), smallerMap.size());

        for (LocalDate dateKey : smallerMap.keySet()) {
            if (largerMap.containsKey(dateKey)) {
                // Found a match for dateValue
                List<MatchDetail> leftMatches = leftGrouped.get(dateKey);
                List<MatchDetail> rightMatches = rightGrouped.get(dateKey);

                // Create a cross product for all matching details on this date
                if (leftMatches != null && rightMatches != null && !leftMatches.isEmpty() && !rightMatches.isEmpty()) {
                    for (MatchDetail leftDetail : leftMatches) {
                        for (MatchDetail rightDetail : rightMatches) {
                            // Avoid joining a detail with itself if from the same source list (unlikely but possible)
                            // and document ID based joins (though this method is for date)
                            if (leftDetail != rightDetail) {
                                joinedDetails.add(new JoinedMatch(leftDetail, rightDetail));
                                logger.trace("Date hash join matched key {}: Added JoinedMatch({}, {})", dateKey, leftDetail, rightDetail);
                            }
                        }
                    }
                } else {
                     logger.warn("Date hash join matched key {} but one list was null or empty? Left: {}, Right: {}", dateKey, leftMatches, rightMatches);
                }
            } else {
                 logger.trace("Key {} from smaller map not found in larger map.", dateKey);
            }
        }
        logger.debug("Date hash join finished, produced {} pairs.", joinedDetails.size());
        return joinedDetails;
    }

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
     * Helper to extract a value from a MatchDetail based on a key.
     * This currently only handles direct variable bindings.
     * TODO: Extend to handle structural columns like TITLE, TIMESTAMP if needed here.
     */
    private Optional<Object> extractValueForKey(MatchDetail detail, String key) {
        if (detail == null || key == null) return Optional.empty();

        // 1. Check if the key matches a bound variable name (base part)
        if (detail.variableName().isPresent()) {
            String storedVarName = detail.variableName().get(); // e.g., "q1.date"
            String baseKey = storedVarName.contains(".") ? storedVarName.substring(storedVarName.indexOf('.') + 1) : storedVarName;
            
            if (key.equals(baseKey)) {
                // Found a match based on variable binding
                if (detail.valueType() == ValueType.DATE) { 
                    try {
                        Object rawValue = detail.value();
                        if (rawValue instanceof String dateString) {
                            return Optional.of(LocalDate.parse(dateString)); 
                        } else if (rawValue instanceof LocalDate localDate) {
                            return Optional.of(localDate);
                        } else {
                            logger.warn("Value for DATE type bound to variable '{}' was not String or LocalDate: {}", storedVarName, rawValue != null ? rawValue.getClass().getName() : "null");
                            return Optional.empty();
                        }
                    } catch (Exception e) {
                        logger.warn("Could not parse date value '{}' for variable '{}' in detail: {}", detail.value(), storedVarName, detail, e);
                        return Optional.empty();
                    }
                } else {
                    // Return raw value for other types bound to variables
                    return Optional.ofNullable(detail.value());
                }
            }
        }

        // 2. If no variable match, check common structural keys
        return switch (key.toLowerCase()) {
            case "document_id" -> Optional.of(detail.getDocumentId());
            // Ensure sentence_id is only returned if valid (not -1)
            case "sentence_id" -> detail.getSentenceId() != -1 ? Optional.of(detail.getSentenceId()) : Optional.empty();
            // Add other structural keys if needed (e.g., timestamp)
            // case "timestamp" -> Optional.ofNullable(detail.position().getTimestamp()); 
            default -> Optional.empty(); // Key doesn't match variable or known structural key
        };
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

    private List<JoinedMatch> performGenericHashJoin(
            List<MatchDetail> leftDetails, List<MatchDetail> rightDetails, String leftKey, String rightKey)
    {
        Map<Object, List<MatchDetail>> leftGrouped = new HashMap<>();
        for (MatchDetail detail : leftDetails) {
            Object val = extractValueForKey(detail, leftKey);
            if (val != null) {
                leftGrouped.computeIfAbsent(val, k -> new ArrayList<>()).add(detail);
            }
        }
        Map<Object, List<MatchDetail>> rightGrouped = new HashMap<>();
        for (MatchDetail detail : rightDetails) {
            Object val = extractValueForKey(detail, rightKey);
            if (val != null) {
                rightGrouped.computeIfAbsent(val, k -> new ArrayList<>()).add(detail);
            }
        }
        List<JoinedMatch> joinedDetails = new ArrayList<>();
        // Iterate through the smaller map's keys for efficiency
        Map<Object, List<MatchDetail>> smallerMap = leftGrouped.size() < rightGrouped.size() ? leftGrouped : rightGrouped;
        Map<Object, List<MatchDetail>> largerMap = smallerMap == leftGrouped ? rightGrouped : leftGrouped;

        logger.debug("Performing generic hash join: Left groups = {}, Right groups = {}, Iterating smaller map (size {}).",
                     leftGrouped.size(), rightGrouped.size(), smallerMap.size());

        for (Object key : smallerMap.keySet()) {
            if (largerMap.containsKey(key)) {
                List<MatchDetail> leftMatches = leftGrouped.getOrDefault(key, List.of());
                List<MatchDetail> rightMatches = rightGrouped.getOrDefault(key, List.of());

                // Create a cross product for all matching details for this key
                if (!leftMatches.isEmpty() && !rightMatches.isEmpty()) {
                    for (MatchDetail leftDetail : leftMatches) {
                        for (MatchDetail rightDetail : rightMatches) {
                             // Avoid joining a detail with itself if key represents something like doc ID
                            if (leftDetail != rightDetail) {
                                joinedDetails.add(new JoinedMatch(leftDetail, rightDetail));
                                logger.trace("Generic hash join matched key {}: Added JoinedMatch({}, {})", key, leftDetail, rightDetail);
                            }
                        }
                    }
                } else {
                     logger.warn("Generic hash join matched key {} but one list was empty? Left: {}, Right: {}", key, leftMatches.size(), rightMatches.size());
                }
            } else {
                 logger.trace("Key {} from smaller map not found in larger map.", key);
            }
        }
        logger.debug("Generic hash join finished, produced {} pairs.", joinedDetails.size());
        return joinedDetails;
    }

    // --- Temporal Sort-Merge Join Implementation ---

    List<TemporalMatch> extractTemporalMatches(List<MatchDetail> details, String key) {
        List<TemporalMatch> temporalMatches = new ArrayList<>();
        for (MatchDetail detail : details) {
            // Use the class helper method
            Optional<Object> valueOpt = extractValueForKey(detail, key); 
            if (valueOpt.isPresent() && valueOpt.get() instanceof LocalDate date) {
                temporalMatches.add(new TemporalMatch(date, detail));
            } else {
                // Log at DEBUG level instead of WARN
                logger.debug("Temporal join: Skipping detail due to null or non-LocalDate value for key '{}': {}", key, detail);
            }
        }
        return temporalMatches;
    }

    /**
     * Performs a sort-merge join for TEMPORAL BEFORE (<) or AFTER (>) predicates.
     *
     * @param leftDetails   List of MatchDetail from the left side.
     * @param rightDetails  List of MatchDetail from the right side.
     * @param leftKey       The key to extract the temporal value from the left side.
     * @param rightKey      The key to extract the temporal value from the right side.
     * @param predicate     The temporal predicate (must be BEFORE or AFTER).
     * @return A list of JoinedMatch representing the pairs satisfying the temporal condition.
     * @throws QueryExecutionException If the predicate is not BEFORE or AFTER, or if keys do not yield comparable temporal values.
     */
    List<JoinedMatch> performTemporalSortMergeJoin(
            List<MatchDetail> leftDetails, List<MatchDetail> rightDetails,
            String leftKey, String rightKey, TemporalPredicate predicate)
            throws QueryExecutionException
    {
        if (predicate != TemporalPredicate.BEFORE && predicate != TemporalPredicate.AFTER) {
            throw new QueryExecutionException(
                "performTemporalSortMergeJoin only supports BEFORE and AFTER predicates.",
                "join", QueryExecutionException.ErrorType.INTERNAL_ERROR
            );
        }
        logger.debug("Performing temporal sort-merge join with predicate: {}", predicate);

        // 1. Extract LocalDate and filter out details without a valid date for the key
        List<TemporalMatch> leftTemporalList = extractTemporalMatches(leftDetails, leftKey);
        List<TemporalMatch> rightTemporalList = extractTemporalMatches(rightDetails, rightKey);

        // Check for empty lists after filtering
        if (leftTemporalList.isEmpty() || rightTemporalList.isEmpty()) {
             logger.debug("Temporal join: One or both lists are empty after filtering non-LocalDate values. Returning empty result.");
             return new ArrayList<>(); // No matches possible
        }

        // 2. Sort both lists by date
        leftTemporalList.sort(Comparator.comparing(TemporalMatch::date));
        rightTemporalList.sort(Comparator.comparing(TemporalMatch::date));

        logger.debug("Sorted temporal lists: Left size = {}, Right size = {}\nLeft: {}\nRight: {}",
                     leftTemporalList.size(), rightTemporalList.size(),
                     leftTemporalList.stream().map(tm -> tm.date() + "->" + tm.detail().getDocumentId()).collect(Collectors.joining(", ")),
                     rightTemporalList.stream().map(tm -> tm.date() + "->" + tm.detail().getDocumentId()).collect(Collectors.joining(", ")));

        // 3. Perform the merge comparison based on the predicate
        List<JoinedMatch> resultList = new ArrayList<>();

        if (predicate == TemporalPredicate.BEFORE) { // Left Date < Right Date
            int j = 0; // Pointer for right list
            logger.debug("[Temporal BEFORE] Starting merge. Left size={}, Right size={}", leftTemporalList.size(), rightTemporalList.size());
            for (int i = 0; i < leftTemporalList.size(); i++) {
                TemporalMatch left = leftTemporalList.get(i);
                logger.debug("[Temporal BEFORE] Processing left[{}]: {}", i, left);
                // Advance j until right date is strictly > left date
                int initialJ = j;
                while (j < rightTemporalList.size() && rightTemporalList.get(j).date().compareTo(left.date()) <= 0) {
                    logger.debug("[Temporal BEFORE] Advancing right pointer j. right[{}]: {} <= left[{}]: {}. j++", j, rightTemporalList.get(j).date(), i, left.date());
                    j++;
                }
                if(j > initialJ) {
                    logger.debug("[Temporal BEFORE] Advanced right pointer j from {} to {}", initialJ, j);
                }
                // All remaining right elements (from index j onwards) satisfy the condition
                logger.debug("[Temporal BEFORE] Adding pairs for left[{}] starting from right index {}. Loop k from {} to {}", i, j, j, rightTemporalList.size());
                for (int k = j; k < rightTemporalList.size(); k++) {
                    TemporalMatch right = rightTemporalList.get(k);
                    resultList.add(new JoinedMatch(left.detail(), right.detail()));
                    logger.debug("[Temporal BEFORE] Added match: Left[{}]({}) < Right[{}]({})", i, left.date(), k, right.date());
                }
            }
        } else { // AFTER: Left Date > Right Date
            int i = 0; // Pointer for left list
            logger.debug("[Temporal AFTER] Starting merge. Left size={}, Right size={}", leftTemporalList.size(), rightTemporalList.size());
            for (int j = 0; j < rightTemporalList.size(); j++) {
                TemporalMatch right = rightTemporalList.get(j);
                logger.debug("[Temporal AFTER] Processing right[{}]: {}", j, right);
                // Advance i until left date is strictly > right date
                 int initialI = i;
                while (i < leftTemporalList.size() && leftTemporalList.get(i).date().compareTo(right.date()) <= 0) {
                     logger.debug("[Temporal AFTER] Advancing left pointer i. left[{}]: {} <= right[{}]: {}. i++", i, leftTemporalList.get(i).date(), j, right.date());
                    i++;
                }
                if (i > initialI) {
                    logger.debug("[Temporal AFTER] Advanced left pointer i from {} to {}", initialI, i);
                }
                // All remaining left elements (from index i onwards) satisfy the condition
                logger.debug("[Temporal AFTER] Adding pairs for right[{}] starting from left index {}. Loop k from {} to {}", j, i, i, leftTemporalList.size());
                for (int k = i; k < leftTemporalList.size(); k++) {
                     TemporalMatch left = leftTemporalList.get(k);
                     resultList.add(new JoinedMatch(left.detail(), right.detail()));
                     logger.debug("[Temporal AFTER] Added match: Left[{}]({}) > Right[{}]({})", k, left.date(), j, right.date());
                }
            }
        }

        logger.debug("Temporal sort-merge join finished for predicate {}. Produced {} pairs.", predicate, resultList.size());
        return resultList;
    }
} 