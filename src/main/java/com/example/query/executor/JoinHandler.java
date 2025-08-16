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

    public JoinHandler() {
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

        logger.debug("Performing binary {} JOIN between LHS ('{}', {} entries) and RHS ('{}', {} entries) ON {}",
                     joinType, lhsAlias, lhsSoA.size(), rhsAlias, rhsSoA.size(), condition);

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
                addBindingFromSource(lhsSoA, leftIdx, finalJoinedResultSoA,
                                     currentOutputConceptualId, addedVariablesInCurrentOutputRow,
                                     operatorType, lhsAlias, leftKey, pair.joinKeyValue());
            }

            List<Integer> rightIndices = rightConceptualIdToIndices.getOrDefault(pair.rightConceptualRowId(), Collections.emptyList());
            for (int rightIdx : rightIndices) {
                addBindingFromSource(rhsSoA, rightIdx, finalJoinedResultSoA,
                                     currentOutputConceptualId, addedVariablesInCurrentOutputRow,
                                     operatorType, rhsAlias, rightKey, pair.joinKeyValue());
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

    /**
     * Adds a single binding from a source SoA row into the output SoA, respecting join semantics
     * and ensuring each variable is only added once per conceptual output row.
     */
    private void addBindingFromSource(
            QueryResultSoA sourceSoA,
            int sourceIndex,
            QueryResultSoA outputSoA,
            int outputConceptualRowId,
            Set<String> addedVariablesInCurrentOutputRow,
            JoinCondition.JoinOperatorType operatorType,
            String sourceAlias,
            String sourceKey,
            Object joinKeyValue
    ) {
        String variableName = sourceSoA.getVariableNameAt(sourceIndex);
        if (variableName == null || addedVariablesInCurrentOutputRow.contains(variableName)) {
            return;
        }

        Object valueToAdd = sourceSoA.getValueAt(sourceIndex);
        ValueType typeToAdd = sourceSoA.getValueTypeAt(sourceIndex);

        if (operatorType == JoinCondition.JoinOperatorType.EQUALITY && variableName.equals(sourceAlias + "." + sourceKey)) {
            valueToAdd = joinKeyValue;
            typeToAdd = inferValueType(joinKeyValue, typeToAdd);
        }

        outputSoA.add(
                valueToAdd,
                typeToAdd,
                variableName,
                sourceSoA.getDocumentIdAt(sourceIndex),
                sourceSoA.getRequirements().needsSentenceId ? sourceSoA.getSentenceIdAt(sourceIndex) : -1,
                sourceSoA.getRequirements().needsPositions ? sourceSoA.getBeginCharAt(sourceIndex) : -1,
                sourceSoA.getRequirements().needsPositions ? sourceSoA.getEndCharAt(sourceIndex) : -1,
                sourceSoA.getRequirements().needsSynonymIds ? sourceSoA.getSynonymIdAt(sourceIndex) : -1,
                outputConceptualRowId
        );
        addedVariablesInCurrentOutputRow.add(variableName);
    }

    /**
     * Infers a ValueType from a Java value, falling back to a provided default when not recognized.
     */
    private static ValueType inferValueType(Object value, ValueType fallback) {
        if (value instanceof LocalDate) return ValueType.DATE;
        if (value instanceof String || value instanceof Number) return ValueType.TERM;
        return fallback;
    }
}