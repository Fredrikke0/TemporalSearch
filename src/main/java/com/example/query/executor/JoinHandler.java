package com.example.query.executor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.query.binding.ValueType;
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

    /**
     * Performs a binary join between two QueryResultSoA objects based on the provided condition and type.
     *
     * @param lhsSoA The QueryResultSoA for the left-hand side of the join.
     * @param lhsAlias The alias for the left-hand side.
     * @param rhsSoA The QueryResultSoA for the right-hand side of the join.
     * @param rhsAlias The alias for the right-hand side.
     * @param condition The JoinCondition specifying how to join.
     * @param joinType The type of join (INNER, LEFT, etc.).
     * @param outputGranularity The granularity for the output QueryResultSoA.
     * @param outputGranularitySize The granularity size for the output.
     * @param outputRequirements The attribute requirements for the output SoA.
     * @return A new QueryResultSoA representing the result of the binary join.
     * @throws QueryExecutionException If an error occurs during join processing.
     */
    public QueryResultSoA performBinaryJoin(
            QueryResultSoA lhsSoA,
            String lhsAlias,
            QueryResultSoA rhsSoA,
            String rhsAlias,
            JoinCondition condition,
            JoinCondition.JoinType joinType,
            Query.Granularity outputGranularity,
            int outputGranularitySize,
            AttributeRequirements outputRequirements
    ) throws QueryExecutionException {
        logger.debug("Performing binary {} JOIN between LHS ('{}', {} entries) and RHS ('{}', {} entries) ON {}",
                     joinType, lhsAlias, lhsSoA.size(), rhsAlias, rhsSoA.size(), condition);

        if (lhsSoA == null) {
            throw new QueryExecutionException(
                String.format("LHS QueryResultSoA for alias '%s' is null in performBinaryJoin.", lhsAlias),
                "join", QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }
        if (rhsSoA == null) {
            throw new QueryExecutionException(
                String.format("RHS QueryResultSoA for alias '%s' is null in performBinaryJoin.", rhsAlias),
                "join", QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        // Ensure input SoAs have conceptualRowIds if required by their own internal state/needs
        // The outputRequirements passed in should already reflect that the output needs conceptualRowIds.
        if (!lhsSoA.getRequirements().needsConceptualRowIds || !rhsSoA.getRequirements().needsConceptualRowIds) {
             logger.warn("CRITICAL: One or both input QueryResultSoA for JOIN are missing conceptualRowIds. Left: {}, Right: {}. This might impact join accuracy if optimizer relies on them internally, though buildConceptualIdToRowIndicesMap handles it.",
                lhsSoA.getRequirements().needsConceptualRowIds, rhsSoA.getRequirements().needsConceptualRowIds);
        }

        // Base keys for result construction logic later
        String leftKey = extractKeyFromColumnName(condition.leftColumn());
        String rightKey = extractKeyFromColumnName(condition.rightColumn());

        // Qualified keys for SoAJoinOptimizer
        String qualifiedLeftKey = condition.leftColumn();
        String qualifiedRightKey = condition.rightColumn();

        JoinCondition.JoinOperatorType operatorType = condition.operatorType();
        Optional<TemporalPredicate> temporalPredicateOpt = condition.temporalPredicate();

        List<SoAJoinOptimizer.SoAJoinKeyMatch> matchingConceptualIdPairs = Collections.emptyList();

        // SoAJoinOptimizer is for INNER joins.
        if (joinType == JoinCondition.JoinType.INNER) {
            if (operatorType == JoinCondition.JoinOperatorType.EQUALITY) {
                logger.debug("Invoking SoAOptimizer for INNER EQUALITY JOIN on keys: {} == {}",
                             qualifiedLeftKey, qualifiedRightKey);
                matchingConceptualIdPairs = SoAJoinOptimizer.performOptimizedHashJoin(
                    lhsSoA, rhsSoA, qualifiedLeftKey, qualifiedRightKey); // Use qualified keys
            } else if (operatorType == JoinCondition.JoinOperatorType.TEMPORAL) {
                TemporalPredicate predicate = temporalPredicateOpt.orElseThrow(() ->
                    new QueryExecutionException("Temporal predicate is required for TEMPORAL join type",
                            "join", QueryExecutionException.ErrorType.INTERNAL_ERROR));
                logger.debug("Invoking SoAOptimizer for INNER TEMPORAL JOIN with predicate {} on keys: {} {} {}",
                             predicate, qualifiedLeftKey, predicate, qualifiedRightKey); // Use qualified keys
                matchingConceptualIdPairs = SoAJoinOptimizer.performOptimizedTemporalJoin(
                    lhsSoA, rhsSoA, qualifiedLeftKey, qualifiedRightKey, predicate.toString()); // Use qualified keys
            } else {
                logger.error("Unhandled JoinOperatorType for INNER JOIN: {}. Returning empty result.", operatorType);
                // matchingConceptualIdPairs remains empty
            }
        } else {
             logger.warn("Join type {} not yet fully implemented in performBinaryJoin. Defaulting to INNER join behavior or empty if optimizer doesn't support. Optimizer might only produce INNER results.", joinType);
             if (operatorType == JoinCondition.JoinOperatorType.EQUALITY) {
                matchingConceptualIdPairs = SoAJoinOptimizer.performOptimizedHashJoin(lhsSoA, rhsSoA, qualifiedLeftKey, qualifiedRightKey); // Use qualified keys
             } else if (operatorType == JoinCondition.JoinOperatorType.TEMPORAL && temporalPredicateOpt.isPresent()){
                matchingConceptualIdPairs = SoAJoinOptimizer.performOptimizedTemporalJoin(lhsSoA, rhsSoA, qualifiedLeftKey, qualifiedRightKey, temporalPredicateOpt.get().toString()); // Use qualified keys
             } else {
                 logger.error("Cannot attempt non-INNER join for operator type {} or missing temporal predicate. Returning empty.", operatorType);
             }
        }

        logger.debug("SoAJoinOptimizer returned {} matching conceptual ID pairs.", matchingConceptualIdPairs.size());

        QueryResultSoA finalJoinedResultSoA = new QueryResultSoA(outputGranularity, outputGranularitySize, outputRequirements);
        int nextOutputConceptualId = 0;

        Map<Integer, List<Integer>> leftConceptualIdToIndices = buildConceptualIdToRowIndicesMap(lhsSoA);
        Map<Integer, List<Integer>> rightConceptualIdToIndices = buildConceptualIdToRowIndicesMap(rhsSoA);

        for (SoAJoinOptimizer.SoAJoinKeyMatch pair : matchingConceptualIdPairs) {
            int currentOutputConceptualId = nextOutputConceptualId++;
            Set<String> addedVariablesInCurrentOutputRow = new HashSet<>();

            List<Integer> leftIndices = leftConceptualIdToIndices.getOrDefault(pair.leftConceptualRowId(), Collections.emptyList());
            for (int leftIdx : leftIndices) {
                String variableName = lhsSoA.getVariableNameAt(leftIdx);
                if (variableName == null || addedVariablesInCurrentOutputRow.contains(variableName)) continue;

                Object valueToAdd = lhsSoA.getValueAt(leftIdx);
                ValueType typeToAdd = lhsSoA.getValueTypeAt(leftIdx);

                if (operatorType == JoinCondition.JoinOperatorType.EQUALITY && variableName.equals(lhsAlias + "." + leftKey)) {
                    valueToAdd = pair.joinKeyValue();
                    if (valueToAdd instanceof LocalDate) typeToAdd = ValueType.DATE;
                    else if (valueToAdd instanceof String || valueToAdd instanceof Number) typeToAdd = ValueType.TERM;
                }
                finalJoinedResultSoA.add(valueToAdd, typeToAdd, variableName,
                                           lhsSoA.getDocumentIdAt(leftIdx),
                                           outputRequirements.needsSentenceId ? lhsSoA.getSentenceIdAt(leftIdx) : -1,
                                           outputRequirements.needsPositions ? lhsSoA.getBeginCharAt(leftIdx) : -1,
                                           outputRequirements.needsPositions ? lhsSoA.getEndCharAt(leftIdx) : -1,
                                           outputRequirements.needsSynonymIds ? lhsSoA.getSynonymIdAt(leftIdx) : -1,
                                           currentOutputConceptualId);
                addedVariablesInCurrentOutputRow.add(variableName);
            }

            List<Integer> rightIndices = rightConceptualIdToIndices.getOrDefault(pair.rightConceptualRowId(), Collections.emptyList());
            for (int rightIdx : rightIndices) {
                String variableName = rhsSoA.getVariableNameAt(rightIdx);
                if (variableName == null || addedVariablesInCurrentOutputRow.contains(variableName)) continue;

                Object valueToAdd = rhsSoA.getValueAt(rightIdx);
                ValueType typeToAdd = rhsSoA.getValueTypeAt(rightIdx);

                // ===== TEMP DEBUG LOGGING START =====
                if (rhsAlias != null && variableName.startsWith(rhsAlias + ".")) {
                    if (variableName.endsWith(".party")) { // Assuming party is a common variable name
                        logger.info("JOIN_HANDLER_TRACE: OutputConceptualId={}, RHS_Input_ConceptualId={}, Var='{}', Value='{}'",
                            currentOutputConceptualId, pair.rightConceptualRowId(), variableName, valueToAdd);
                    }
                    if (variableName.endsWith(".person")) { // Assuming person is the join key here
                         logger.info("JOIN_HANDLER_TRACE: OutputConceptualId={}, RHS_Input_ConceptualId={}, Var='{}', Value='{}'",
                            currentOutputConceptualId, pair.rightConceptualRowId(), variableName, valueToAdd);
                    }
                } else if (variableName.equals("party")) { // For unaliased variables from subquery
                     logger.info("JOIN_HANDLER_TRACE (unaliased): OutputConceptualId={}, RHS_Input_ConceptualId={}, Var='{}', Value='{}'",
                            currentOutputConceptualId, pair.rightConceptualRowId(), variableName, valueToAdd);
                } else if (variableName.equals("person")) {
                     logger.info("JOIN_HANDLER_TRACE (unaliased): OutputConceptualId={}, RHS_Input_ConceptualId={}, Var='{}', Value='{}'",
                            currentOutputConceptualId, pair.rightConceptualRowId(), variableName, valueToAdd);
                }
                // ===== TEMP DEBUG LOGGING END =====

                if (operatorType == JoinCondition.JoinOperatorType.EQUALITY && variableName.equals(rhsAlias + "." + rightKey)) {
                    valueToAdd = pair.joinKeyValue(); // For RHS, this ensures consistency if joinKey was from LHS
                    if (valueToAdd instanceof LocalDate) typeToAdd = ValueType.DATE;
                    else if (valueToAdd instanceof String || valueToAdd instanceof Number) typeToAdd = ValueType.TERM;
                }
                finalJoinedResultSoA.add(valueToAdd, typeToAdd, variableName,
                                           rhsSoA.getDocumentIdAt(rightIdx),
                                           outputRequirements.needsSentenceId ? rhsSoA.getSentenceIdAt(rightIdx) : -1,
                                           outputRequirements.needsPositions ? rhsSoA.getBeginCharAt(rightIdx) : -1,
                                           outputRequirements.needsPositions ? rhsSoA.getEndCharAt(rightIdx) : -1,
                                           outputRequirements.needsSynonymIds ? rhsSoA.getSynonymIdAt(rightIdx) : -1,
                                           currentOutputConceptualId);
                addedVariablesInCurrentOutputRow.add(variableName);
            }
        }

        logger.info("Binary join processed. Output QueryResultSoA has {} entries, representing {} conceptual output rows.",
                    finalJoinedResultSoA.size(), nextOutputConceptualId);
        return finalJoinedResultSoA;
    }

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
     * @deprecated This method is deprecated. Use {@link #performBinaryJoin(QueryResultSoA, String, QueryResultSoA, String, JoinCondition, JoinCondition.JoinType, Query.Granularity, int, AttributeRequirements)} instead.
     * The new method handles a single binary join step directly.
     */
    @Deprecated
    public QueryResultSoA handleJoin(
            Query query, // query parameter is problematic for a generic binary join handler
            SubqueryContext subqueryContext)
            throws QueryExecutionException {

        logger.error("DEPRECATED JoinHandler.handleJoin(Query, SubqueryContext) was called. This method can no longer function correctly due to Query model changes (removal of single joinCondition). " +
                     "The caller (QueryExecutor) must be updated to use performBinaryJoin() for each JoinStep. Returning an empty result to prevent crashes.");

        // Cannot reliably get a single JoinCondition from the new Query model.
        // The old logic here is no longer valid.
        // Return an empty QueryResultSoA based on the query's primary granularity and requirements.

        AttributeRequirements fallbackRequirements = new AttributeRequirements();
        if (query.mainAlias().isPresent() && subqueryContext.hasResults(query.mainAlias().get())) {
            fallbackRequirements.merge(subqueryContext.getQueryResult(query.mainAlias().get()).getRequirements());
        } else if (!query.joinSteps().isEmpty() && subqueryContext.hasResults(query.joinSteps().get(0).rightSourceAlias())) {
            // Fallback: try to get requirements from the first subquery if main alias results not present
             QueryResultSoA firstSubSoA = subqueryContext.getQueryResult(query.joinSteps().get(0).rightSourceAlias());
             if (firstSubSoA != null) fallbackRequirements.merge(firstSubSoA.getRequirements());
        }
        fallbackRequirements.needsConceptualRowIds = true; // Joins typically need this

        return new QueryResultSoA(query.granularity(), query.granularitySize().orElse(0), fallbackRequirements);
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