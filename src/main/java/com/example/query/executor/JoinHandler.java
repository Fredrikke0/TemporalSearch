package com.example.query.executor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.query.model.JoinCondition;
import com.example.query.model.Query;
import com.example.query.model.TemporalPredicate;

/**
 * Handles the execution of JOIN operations between subquery QueryResultSoA objects.
 * Produces a new, unified QueryResultSoA as the result of the join.
 */
public class JoinHandler {
    private static final Logger logger = LoggerFactory.getLogger(JoinHandler.class);

    // The TemporalMatch record was for MatchDetail, can be removed if not adapted for SoA directly.
    // private record TemporalMatch(LocalDate date, MatchDetail detail) {}

    public JoinHandler() {
        // Constructor remains simple
    }

    // convertSoAToMatchDetails is no longer needed and will be removed.
    /*
    private List<MatchDetail> convertSoAToMatchDetails(QueryResultSoA soaResult) {
        // ... old implementation ...
    }
    */

    private Map<Integer, List<Integer>> buildConceptualIdToRowIndicesMap(QueryResultSoA soa) {
        Map<Integer, List<Integer>> map = new HashMap<>();
        if (soa == null || !soa.getRequirements().needsConceptualRowIds) {
            logger.warn("Cannot build conceptualId to row indices map: SoA is null or lacks conceptualRowIds.");
            return map;
        }
        for (int i = 0; i < soa.size(); i++) {
            map.computeIfAbsent(soa.getConceptualRowIdAt(i), k -> new ArrayList<>()).add(i);
        }
        return map;
    }

    /**
     * Executes the join specified in the query using pre-computed subquery QueryResultSoA instances.
     * The method now returns a single QueryResultSoA representing the unified result of the join.
     *
     * @param query           The query containing the join condition and subquery definitions.
     * @param subqueryContext Context containing the results of executed subqueries as QueryResultSoA.
     * @return A QueryResultSoA representing the result of the join.
     * @throws QueryExecutionException if the join execution fails.
     */
    public QueryResultSoA handleJoin(
            Query query,
            SubqueryContext subqueryContext)
            throws QueryExecutionException {

        logger.debug("Handling JOIN operation with QueryResultSoA inputs.");

        JoinCondition joinCondition = query.joinCondition().orElseThrow(() ->
                new QueryExecutionException("Join condition is required but missing in JoinHandler",
                        "join", QueryExecutionException.ErrorType.INTERNAL_ERROR));

        String leftAlias = extractAliasFromColumnName(joinCondition.leftColumn());
        String rightAlias = extractAliasFromColumnName(joinCondition.rightColumn());
        String leftKey = extractKeyFromColumnName(joinCondition.leftColumn());
        String rightKey = extractKeyFromColumnName(joinCondition.rightColumn());

        logger.debug("Joining subquery SoA '{}' with subquery SoA '{}' on keys: {}.{} {} {}.{}",
                     leftAlias, rightAlias, leftAlias, leftKey, joinCondition.operatorType(), rightAlias, rightKey);

        QueryResultSoA leftSoA = subqueryContext.getQueryResult(leftAlias);
        QueryResultSoA rightSoA = subqueryContext.getQueryResult(rightAlias);

        if (leftSoA == null) {
            throw new QueryExecutionException(
                String.format("Missing QueryResultSoA for left subquery '%s' in JOIN context", leftAlias),
                "join", QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }
        if (rightSoA == null) {
            throw new QueryExecutionException(
                String.format("Missing QueryResultSoA for right subquery '%s' in JOIN context", rightAlias),
                "join", QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        if (!leftSoA.getRequirements().needsConceptualRowIds || !rightSoA.getRequirements().needsConceptualRowIds) {
             logger.error("CRITICAL: One or both input QueryResultSoA for JOIN are missing conceptualRowIds. Left: {}, Right: {}. Join results will be incorrect.",
                leftSoA.getRequirements().needsConceptualRowIds, rightSoA.getRequirements().needsConceptualRowIds);
            // Depending on strictness, could throw an exception or return empty.
            // For now, proceed but results will be flawed if conceptual IDs are missing.
        }

        logger.debug("Left SoA ('{}') has {} entries, Right SoA ('{}') has {} entries",
                     leftAlias, leftSoA.size(), rightAlias, rightSoA.size());

        List<SoAJoinOptimizer.SoAJoinKeyMatch> matchingConceptualIdPairs = Collections.emptyList();
        JoinCondition.JoinType joinType = joinCondition.type(); // Only INNER JOIN currently fully supported by SoAJoinOptimizer refactor
        JoinCondition.JoinOperatorType operatorType = joinCondition.operatorType();
        Optional<TemporalPredicate> temporalPredicateOpt = joinCondition.temporalPredicate();

        if (joinType == JoinCondition.JoinType.INNER) {
            if (operatorType == JoinCondition.JoinOperatorType.EQUALITY) {
                logger.debug("Invoking SoAOptimizer for INNER EQUALITY JOIN on keys: {}.{} == {}.{}",
                             leftAlias, leftKey, rightAlias, rightKey);
                matchingConceptualIdPairs = SoAJoinOptimizer.performOptimizedHashJoin(
                    leftSoA, rightSoA, leftKey, rightKey);

            } else if (operatorType == JoinCondition.JoinOperatorType.TEMPORAL) {
                TemporalPredicate predicate = temporalPredicateOpt.orElseThrow(() ->
                    new QueryExecutionException("Temporal predicate is required for TEMPORAL join type",
                            "join", QueryExecutionException.ErrorType.INTERNAL_ERROR));

                logger.debug("Invoking SoAOptimizer for INNER TEMPORAL JOIN with predicate {} on keys: {}.{} {} {}.{}",
                             predicate, leftAlias, leftKey, predicate, rightAlias, rightKey);

                // Note: SoAJoinOptimizer.performOptimizedTemporalJoin is currently a stub
                matchingConceptualIdPairs = SoAJoinOptimizer.performOptimizedTemporalJoin(
                    leftSoA, rightSoA, leftKey, rightKey, predicate.toString());
            } else {
                logger.error("Unhandled JoinOperatorType for INNER JOIN: {}. Returning empty result.", operatorType);
            }
        } else {
             logger.warn("Join type {} not yet fully implemented for SoA pipeline. Returning empty result.", joinType);
        }

        logger.debug("SoAJoinOptimizer returned {} matching conceptual ID pairs.", matchingConceptualIdPairs.size());

        // Granularity of the output QueryResultSoA should match the original query's granularity.
        // AttributeRequirements for the output SoA are determined by merging inputs.
        AttributeRequirements finalOutputRequirements = new AttributeRequirements();
        finalOutputRequirements.merge(leftSoA.getRequirements());
        finalOutputRequirements.merge(rightSoA.getRequirements());
        finalOutputRequirements.needsConceptualRowIds = true; // Output of a join MUST have conceptual IDs

        QueryResultSoA finalJoinedResultSoA = new QueryResultSoA(query.granularity(), query.granularitySize().orElse(0), finalOutputRequirements);
        int nextOutputConceptualId = 0;

        Map<Integer, List<Integer>> leftConceptualIdToIndices = buildConceptualIdToRowIndicesMap(leftSoA);
        Map<Integer, List<Integer>> rightConceptualIdToIndices = buildConceptualIdToRowIndicesMap(rightSoA);

        for (SoAJoinOptimizer.SoAJoinKeyMatch pair : matchingConceptualIdPairs) {
            int currentOutputConceptualId = nextOutputConceptualId++;

            List<Integer> leftIndices = leftConceptualIdToIndices.getOrDefault(pair.leftConceptualRowId(), Collections.emptyList());
            for (int leftIdx : leftIndices) {
                finalJoinedResultSoA.add(
                    leftSoA.getValueAt(leftIdx),
                    leftSoA.getValueTypeAt(leftIdx),
                    leftSoA.getVariableNameAt(leftIdx),
                    leftSoA.getDocumentIdAt(leftIdx),
                    finalOutputRequirements.needsSentenceId ? leftSoA.getSentenceIdAt(leftIdx) : -1,
                    finalOutputRequirements.needsPositions ? leftSoA.getBeginCharAt(leftIdx) : -1,
                    finalOutputRequirements.needsPositions ? leftSoA.getEndCharAt(leftIdx) : -1,
                    finalOutputRequirements.needsSynonymIds ? leftSoA.getSynonymIdAt(leftIdx) : -1,
                    currentOutputConceptualId
                );
            }

            List<Integer> rightIndices = rightConceptualIdToIndices.getOrDefault(pair.rightConceptualRowId(), Collections.emptyList());
            for (int rightIdx : rightIndices) {
                finalJoinedResultSoA.add(
                    rightSoA.getValueAt(rightIdx),
                    rightSoA.getValueTypeAt(rightIdx),
                    rightSoA.getVariableNameAt(rightIdx),
                    rightSoA.getDocumentIdAt(rightIdx),
                    finalOutputRequirements.needsSentenceId ? rightSoA.getSentenceIdAt(rightIdx) : -1,
                    finalOutputRequirements.needsPositions ? rightSoA.getBeginCharAt(rightIdx) : -1,
                    finalOutputRequirements.needsPositions ? rightSoA.getEndCharAt(rightIdx) : -1,
                    finalOutputRequirements.needsSynonymIds ? rightSoA.getSynonymIdAt(rightIdx) : -1,
                    currentOutputConceptualId
                );
            }
        }

        logger.info("Join execution completed. Final unified QueryResultSoA has {} entries, representing {} conceptual output rows.",
                    finalJoinedResultSoA.size(), nextOutputConceptualId);
        return finalJoinedResultSoA;
    }

    // Static helper methods previously here for MatchDetail are now part of SoAJoinOptimizer or handled by QueryResultSoA accessors.
    // extractAliasFromColumnName, extractKeyFromColumnName, extractValueForKey can remain if they are general utility.

    public static String extractAliasFromColumnName(String columnName) throws QueryExecutionException {
        if (columnName == null || !columnName.contains(".")) {
            // If there's no dot, it might be a direct structural key or an unaliased variable from the main query.
            // For join purposes, if it's unaliased in a subquery context, the subquery itself is the implicit alias.
            // This part of the logic might need refinement based on how aliases are consistently handled.
            logger.trace("Column name '{}' does not contain '.', returning empty alias for join key extraction.", columnName);
            return ""; // Or throw error if alias is strictly expected for subquery vars
        }
        return columnName.substring(0, columnName.indexOf('.'));
    }

    public static String extractKeyFromColumnName(String columnName) throws QueryExecutionException {
        if (columnName == null) {
            throw new QueryExecutionException("Column name for key extraction cannot be null", "join key extraction", QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }
        if (!columnName.contains(".")) {
            return columnName; // Assume it's a direct key if no alias part
        }
        return columnName.substring(columnName.indexOf('.') + 1);
    }

    // extractValueForKey was for MatchDetail, no longer directly applicable in the same way for QueryResultSoA.
    // The logic is now within SoAJoinOptimizer.buildKeyToConceptualIdsMap.
    /*
    public static Optional<Object> extractValueForKey(MatchDetail detail, String key) {
        // ... old implementation ...
    }
    */

    // extractTypeForKey was for MatchDetail, also superseded.
    /*
    private ValueType extractTypeForKey(List<MatchDetail> details, String key) {
        // ... old implementation ...
    }
    */

    // All private helper methods for performing joins (performHashJoinOnDate, performGenericHashJoin, performTemporalSortMergeJoin)
    // were based on List<MatchDetail> and are now effectively replaced by the SoAJoinOptimizer methods.
    // They can be removed.
}