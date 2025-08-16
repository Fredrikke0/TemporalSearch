package com.example.query.executor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.query.binding.ValueType;

/**
 * Optimizes join operations using SoA (Structure of Arrays) patterns.
 * Operates directly on QueryResultSoA instances and returns pairs of matching conceptual IDs.
 */
public class SoAJoinOptimizer {
    private static final Logger logger = LoggerFactory.getLogger(SoAJoinOptimizer.class);

    /**
     * Represents a pair of conceptualRowIds from the left and right QueryResultSoA that have matched on a join key.
     */
    public record SoAJoinKeyMatch(int leftConceptualRowId, int rightConceptualRowId, Object joinKeyValue) {}

    /**
     * Performs an optimized hash join by selecting the best algorithm based on join keys.
     *
     * @param leftSoA QueryResultSoA from the left side
     * @param rightSoA QueryResultSoA from the right side
     * @param leftJoinKey The key/variable name to extract from left side
     * @param rightJoinKey The key/variable name to extract from right side
     * @return List of SoAJoinKeyMatch representing the join result
     */
    public static List<SoAJoinKeyMatch> performOptimizedHashJoin(
            QueryResultSoA leftSoA, QueryResultSoA rightSoA,
            String leftJoinKey, String rightJoinKey) {

        logger.debug("Performing optimized SoA hash join on keys: {} = {} (left size: {}, right size: {})",
                    leftJoinKey, rightJoinKey, leftSoA.size(), rightSoA.size());

        if (!leftSoA.getRequirements().needsConceptualRowIds || !rightSoA.getRequirements().needsConceptualRowIds) {
            logger.error("Critical: Input QueryResultSoA(s) for Hash Join are missing conceptualRowIds. Join cannot proceed correctly.");
            return Collections.emptyList();
        }

        if (isStructuralDateKey(leftJoinKey) && isStructuralDateKey(rightJoinKey)) {
            return performDateJoinSoA(leftSoA, rightSoA, leftJoinKey, rightJoinKey);
        } else {
            return performGenericJoinSoA(leftSoA, rightSoA, leftJoinKey, rightJoinKey);
        }
    }

    public static boolean isStructuralDateKey(String key) {
        return "date".equalsIgnoreCase(key) || "DATE".equalsIgnoreCase(key);
    }

    public static boolean isStructuralDocumentIdKey(String key) {
        return "document_id".equalsIgnoreCase(key) || "DOCID".equalsIgnoreCase(key);
    }

    public static boolean isStructuralSentenceIdKey(String key) {
        return "sentence_id".equalsIgnoreCase(key) || "SENTID".equalsIgnoreCase(key);
    }

    private static <T> Map<T, Set<Integer>> buildKeyToConceptualIdsMap(
            QueryResultSoA soa, String keyName, Class<T> keyType) {

        Map<T, Set<Integer>> keyToConceptualIds = new HashMap<>();
        if (!soa.getRequirements().needsConceptualRowIds) {
            logger.warn("Building key map for SoA (key: '{}'), but it lacks conceptualRowIds. Results may be incorrect.", keyName);
            // Still proceed, but be aware conceptual IDs might be default/missing.
        }

        for (int i = 0; i < soa.size(); i++) {
            Object extractedValue = null;
            boolean keyFound = false;

            String varNameAtIndex = soa.getVariableNameAt(i);
            if (varNameAtIndex != null && (varNameAtIndex.equals(keyName) || varNameAtIndex.equals("?" + keyName))) {
                extractedValue = soa.getValueAt(i);
                keyFound = true;
            } else if (isStructuralDocumentIdKey(keyName) && soa.getRequirements().needsDocumentId) {
                 extractedValue = soa.getDocumentIdAt(i);
                 keyFound = true;
            } else if (isStructuralSentenceIdKey(keyName) && soa.getRequirements().needsSentenceId) {
                 extractedValue = soa.getSentenceIdAt(i);
                 keyFound = true;
            } else if (isStructuralDateKey(keyName)) {
                if (varNameAtIndex != null) {
                    String plainVarName = varNameAtIndex.substring(varNameAtIndex.lastIndexOf('.') + 1);
                    if (plainVarName.equalsIgnoreCase(keyName)) {
                        if (soa.getValueTypeAt(i) == ValueType.DATE) {
                            extractedValue = soa.getValueAt(i);
                            keyFound = true;
                        } else {
                            logger.warn("Structural date join key '{}': Variable '{}' was expected to be DATE type but is {}. Skipping for join key map.",
                                        keyName, varNameAtIndex, soa.getValueTypeAt(i));
                        }
                    }
                }
            }

            if (keyFound && extractedValue != null) {
                try {
                    T castedValue = keyType.cast(extractedValue);
                    int conceptualId = soa.getRequirements().needsConceptualRowIds ? soa.getConceptualRowIdAt(i) : i; // Fallback if no conceptualIDs
                    keyToConceptualIds.computeIfAbsent(castedValue, k -> new HashSet<>()).add(conceptualId);
                } catch (ClassCastException e) {
                    logger.warn("Cast failed for key '{}', value '{}' to type '{}'. Skipping.", keyName, extractedValue, keyType.getSimpleName());
                }
            }
        }
        return keyToConceptualIds;
    }

    private static List<SoAJoinKeyMatch> performDateJoinSoA(
            QueryResultSoA leftSoA, QueryResultSoA rightSoA,
            String leftDateKeyName, String rightDateKeyName) {

        logger.debug("Performing SoA DATE join: {} (left) vs {} (right)", leftDateKeyName, rightDateKeyName);

        Map<LocalDate, Set<Integer>> leftMap = buildKeyToConceptualIdsMap(leftSoA, leftDateKeyName, LocalDate.class);
        Map<LocalDate, Set<Integer>> rightMap = buildKeyToConceptualIdsMap(rightSoA, rightDateKeyName, LocalDate.class);
        return combineMaps(leftMap, rightMap);
    }

    private static List<SoAJoinKeyMatch> performGenericJoinSoA(
            QueryResultSoA leftSoA, QueryResultSoA rightSoA,
            String leftKeyName, String rightKeyName) {
        logger.debug("Performing generic SoA hash join: {} (left) vs {} (right)", leftKeyName, rightKeyName);
        Map<Object, Set<Integer>> leftMap = buildKeyToConceptualIdsMap(leftSoA, leftKeyName, Object.class);
        Map<Object, Set<Integer>> rightMap = buildKeyToConceptualIdsMap(rightSoA, rightKeyName, Object.class);
        return combineMaps(leftMap, rightMap);
    }

    /**
     * Helper to combine two maps (key -> Set<ConceptualId>) into List<SoAJoinKeyMatch>.
     */
    private static <K> List<SoAJoinKeyMatch> combineMaps(Map<K, Set<Integer>> leftMap, Map<K, Set<Integer>> rightMap) {
        List<SoAJoinKeyMatch> results = new ArrayList<>();
        Map<K, Set<Integer>> smallerMap = leftMap.size() <= rightMap.size() ? leftMap : rightMap;
        Map<K, Set<Integer>> largerMap = smallerMap == leftMap ? rightMap : leftMap;

        for (Map.Entry<K, Set<Integer>> entry : smallerMap.entrySet()) {
            K key = entry.getKey();
            Set<Integer> conceptualIdsFromLargerMap = largerMap.get(key);

            if (conceptualIdsFromLargerMap != null && !conceptualIdsFromLargerMap.isEmpty()) {
                Set<Integer> conceptualIdsFromSmallerMap = entry.getValue();

                Set<Integer> leftConceptualIds = (smallerMap == leftMap) ? conceptualIdsFromSmallerMap : conceptualIdsFromLargerMap;
                Set<Integer> rightConceptualIds = (smallerMap == leftMap) ? conceptualIdsFromLargerMap : conceptualIdsFromSmallerMap;

                for (int leftCid : leftConceptualIds) {
					for (int rightCid : rightConceptualIds) {
						// Allow all pairs (including self-pairs); JoinHandler governs output rows.
						results.add(new SoAJoinKeyMatch(leftCid, rightCid, key));
					}
				}
            }
        }
        logger.debug("Map combination yielded {} key matches.", results.size());
        return results;
    }

    public static List<SoAJoinKeyMatch> performOptimizedTemporalJoin(
            QueryResultSoA leftSoA, QueryResultSoA rightSoA,
            String leftKey, String rightKey, String predicate)
            throws QueryExecutionException {
        logger.debug("Performing SoA-based Temporal Join for predicate '{}' on keys: {} (left) vs {} (right)", predicate, leftKey, rightKey);

        if (!leftSoA.getRequirements().needsConceptualRowIds || !rightSoA.getRequirements().needsConceptualRowIds) {
            logger.error("Critical: Input QueryResultSoA(s) for Temporal Join are missing conceptualRowIds. Join cannot proceed correctly.");
            return Collections.emptyList();
        }

        List<TemporalConceptualPair> leftPairs = extractTemporalPairs(leftSoA, leftKey);
        List<TemporalConceptualPair> rightPairs = extractTemporalPairs(rightSoA, rightKey);

        if (leftPairs.isEmpty() || rightPairs.isEmpty()) {
            logger.debug("One or both sides of the temporal join have no valid date entries. Left: {}, Right: {}.", leftPairs.size(), rightPairs.size());
            return Collections.emptyList();
        }

        // Sort by date, then by conceptual ID for stable processing
        Comparator<TemporalConceptualPair> pairComparator = Comparator.comparing(TemporalConceptualPair::date)
                                                                    .thenComparingInt(TemporalConceptualPair::conceptualId);
        leftPairs.sort(pairComparator);
        rightPairs.sort(pairComparator);

        List<SoAJoinKeyMatch> results = new ArrayList<>();

        switch (predicate.toUpperCase()) {
            case "BEFORE": // left occurs before right; left.date < right.date
                int rightPtrBefore = 0;
                for (TemporalConceptualPair left : leftPairs) {
                    // Advance rightPtrBefore to find the first element R[rightPtrBefore] such that left.date < R[rightPtrBefore].date
                    while (rightPtrBefore < rightPairs.size() && !left.date().isBefore(rightPairs.get(rightPtrBefore).date())) {
                        // This means left.date >= rightPairs.get(rightPtrBefore).date(), so R[rightPtrBefore] is too early or same.
                        rightPtrBefore++;
                    }
                    // All elements in rightPairs from rightPtrBefore onwards are matches for the current left pair
                    for (int k = rightPtrBefore; k < rightPairs.size(); k++) {
                        results.add(new SoAJoinKeyMatch(left.conceptualId(), rightPairs.get(k).conceptualId(), left.date()));
                    }
                }
                break;
            case "AFTER": // left occurs after right; left.date > right.date
                int leftPtrAfter = 0;
                for (TemporalConceptualPair right : rightPairs) {
                    // Advance leftPtrAfter to find the first element L[leftPtrAfter] such that L[leftPtrAfter].date > right.date
                    while (leftPtrAfter < leftPairs.size() && !leftPairs.get(leftPtrAfter).date().isAfter(right.date())) {
                        // This means leftPairs.get(leftPtrAfter).date <= right.date(), so L[leftPtrAfter] is too early or same.
                        leftPtrAfter++;
                    }
                    // All elements in leftPairs from leftPtrAfter onwards are matches for the current right pair
                    // The join key value comes from the left side (leftPairs.get(k).date())
                    for (int k = leftPtrAfter; k < leftPairs.size(); k++) {
                        results.add(new SoAJoinKeyMatch(leftPairs.get(k).conceptualId(), right.conceptualId(), leftPairs.get(k).date()));
                    }
                }
                break;
            case "EQUALS": // This was TemporalPredicate.EQUAL in the test
                 for (TemporalConceptualPair left : leftPairs) {
                    for (TemporalConceptualPair right : rightPairs) {
                        if (left.date().isEqual(right.date())) {
                            results.add(new SoAJoinKeyMatch(left.conceptualId(), right.conceptualId(), left.date()));
                        }
                    }
                }
                break;
            default:
                logger.warn("Unsupported or not yet implemented temporal predicate: '{}' for single date point comparison.", predicate);
                throw new QueryExecutionException(
                    "Unsupported temporal predicate for SoA join: " + predicate,
                    "Temporal Join",
                    QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION
                );
        }

        logger.debug("Temporal join with predicate '{}' produced {} matches.", predicate, results.size());
        return results;
    }

    private record TemporalConceptualPair(LocalDate date, int conceptualId) {}

    private static List<TemporalConceptualPair> extractTemporalPairs(QueryResultSoA soa, String keyName) {
        List<TemporalConceptualPair> pairs = new ArrayList<>();
        if (!soa.getRequirements().needsConceptualRowIds) {
             logger.warn("Extracting temporal pairs for SoA (key: '{}'), but it lacks conceptualRowIds. Results may be incomplete or incorrect.", keyName);
        }
        for (int i = 0; i < soa.size(); i++) {
            int conceptualId = soa.getConceptualRowIdAt(i);

            String varNameAtIndex = soa.getVariableNameAt(i);
            boolean isTargetKey = (varNameAtIndex != null && (varNameAtIndex.equals(keyName) || varNameAtIndex.equals("?" + keyName)));
            boolean isStructuralDate = isStructuralDateKey(keyName);

            Object value = null;
            ValueType type = null;

            if (isTargetKey) {
                value = soa.getValueAt(i);
                type = soa.getValueTypeAt(i);
            } else if (isStructuralDate) {
                 if (soa.getValueTypeAt(i) == ValueType.DATE) {
                    value = soa.getValueAt(i);
                    type = ValueType.DATE;
                 }
            }

            if (value instanceof LocalDate && type == ValueType.DATE) {
                pairs.add(new TemporalConceptualPair((LocalDate) value, conceptualId));
            } else if (value != null && type != ValueType.DATE && (isTargetKey || isStructuralDate) ) {
                 logger.warn("Temporal join key '{}' for conceptual ID {} resolved to value '{}' of type {} but expected LocalDate. Skipping this entry.",
                            keyName, conceptualId, value, type);
            }
        }
        if (!pairs.isEmpty()) {
            return new ArrayList<>(new HashSet<>(pairs));
        }
        return pairs;
    }
}