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

            for (MatchDetail left : leftDetails) {
                for (MatchDetail right : rightDetails) {
                    Object leftVal = extractValueForKey(left, leftKey);
                    Object rightVal = extractValueForKey(right, rightKey);
                    ValueType leftType = extractTypeForKey(left, leftKey); 
                    ValueType rightType = extractTypeForKey(right, rightKey); 

                    boolean match = false;
                    // Special handling for structural keys first
                    if (Objects.equals(leftKey, "document_id") && Objects.equals(rightKey, "document_id")) {
                         if (Objects.equals(leftVal, rightVal)) { match = true; }
                    } else if (Objects.equals(leftKey, "sentence_id") && Objects.equals(rightKey, "sentence_id")) {
                         if (leftVal != null && rightVal != null && Objects.equals(leftVal, rightVal)) { match = true; }
                    }
                    // Handle temporal predicates based on extracted keys
                    else if (leftType == ValueType.DATE && rightType == ValueType.DATE && 
                             leftVal instanceof LocalDate && rightVal instanceof LocalDate) {
                        LocalDate leftDate = (LocalDate) leftVal;
                        LocalDate rightDate = (LocalDate) rightVal;
                        
                        switch (predicate) {
                            case EQUAL:
                                match = leftDate.isEqual(rightDate);
                                break;
                            case INTERSECT: 
                                // For single dates, INTERSECT is the same as EQUAL
                                match = leftDate.isEqual(rightDate);
                                break;
                            case CONTAINS: // Does leftDate contain rightDate? (Only true if equal)
                                match = leftDate.isEqual(rightDate);
                                break;
                            case CONTAINED_BY: // Is leftDate contained by rightDate? (Only true if equal)
                                match = leftDate.isEqual(rightDate);
                                break;
                             case BEFORE:
                                match = leftDate.isBefore(rightDate);
                                break;
                             case AFTER:
                                match = leftDate.isAfter(rightDate);
                                break;
                             case BEFORE_EQUAL:
                                match = !leftDate.isAfter(rightDate);
                                break;
                             case AFTER_EQUAL:
                                match = !leftDate.isBefore(rightDate);
                                break;
                            case PROXIMITY:
                                if (proximityWindow.isPresent()) {
                                    long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(leftDate, rightDate);
                                    match = Math.abs(daysBetween) <= proximityWindow.get();
                                } else {
                                    logger.warn("PROXIMITY predicate used without window size.");
                                }
                                break;
                        }
                    } 
                    // Handle other value types if necessary (only EQUAL currently supported implicitly)
                    else if (leftType == rightType && predicate == TemporalPredicate.EQUAL) {
                        if (Objects.equals(leftVal, rightVal)) {
                           match = true;
                        }
                    } else {
                        // Types are different or predicate not supported for these types
                        logger.trace("Skipping join comparison for predicate {} between keys/types: '{}' ({}) vs '{}' ({})", 
                                     predicate, leftKey, leftType, rightKey, rightType);
                    }

                    if (match) {
                        // Create a new JoinedMatch containing info from both sides
                        JoinedMatch joinedMatch = new JoinedMatch(left, right);
                        joinedDetails.add(joinedMatch);
                        
                        // REMOVED: break; // Allow multiple matches per left detail (standard INNER JOIN)
                    }
                }
            }
        } else {
             logger.warn("Join type {} not yet implemented. Returning empty result.", joinType);
        }

        logger.debug("Join execution completed. Resulting join has {} pairs.", joinedDetails.size());

        int granularitySize = query.granularitySize().orElse(1);
        // Return the joined pairs directly for now (update QueryExecutor/TableResultService to consume this)
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
        if (detailObj instanceof JoinedMatch joined) {
            // Check left and right for variable match
            if (key.startsWith("?")) {
                if (joined.left().variableName().isPresent() && key.equals(joined.left().variableName().get())) {
                    return joined.left().value();
                } else if (key.equals(joined.getRightVariableName())) {
                    return joined.getRightValue();
                }
                return null;
            }
            // Fallback to standard keys for left/right
            return switch (key.toLowerCase()) {
                case "document_id" -> joined.left().getDocumentId();
                case "sentence_id" -> joined.left().getSentenceId() != -1 ? joined.left().getSentenceId() : null;
                default -> null;
            };
        } else if (detailObj instanceof MatchDetail detail) {
            if (key.startsWith("?")) {
                if (detail.variableName().isPresent() && key.equals(detail.variableName().get())) {
                    return detail.value();
                }
                return null;
            }
            return switch (key.toLowerCase()) {
                case "document_id" -> detail.getDocumentId();
                case "sentence_id" -> detail.getSentenceId() != -1 ? detail.getSentenceId() : null;
                default -> null;
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
        if (detailObj instanceof JoinedMatch joined) {
            if (key.startsWith("?")) {
                if (joined.left().variableName().isPresent() && key.equals(joined.left().variableName().get())) {
                    return joined.left().valueType();
                } else if (key.equals(joined.getRightVariableName())) {
                    return joined.getRightValueType();
                }
                return null;
            }
            return null;
        } else if (detailObj instanceof MatchDetail detail) {
            if (key.startsWith("?")) {
                if (detail.variableName().isPresent() && key.equals(detail.variableName().get())) {
                    return detail.valueType();
                }
                return null;
            }
            return null;
        }
        return null;
    }
} 