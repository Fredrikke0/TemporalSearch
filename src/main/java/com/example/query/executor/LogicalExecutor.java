package com.example.query.executor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.query.model.Query;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Logical.LogicalOperator;

/**
 * Executor for logical conditions (AND, OR).
 * Handles recursive execution and result combination of subconditions using QueryResultSoA.
 */
public final class LogicalExecutor implements ConditionExecutor<Logical> {
    private static final Logger logger = LoggerFactory.getLogger(LogicalExecutor.class);

    private final ConditionExecutorFactory executorFactory;

    // Helper record for Sentence granularity keys
    private record DocSentIdPair(int docId, int sentId) {}

    /**
     * Creates a new LogicalConditionExecutor that uses the provided factory to create
     * executors for subconditions.
     *
     * @param executorFactory The factory to use for creating condition executors
     */
    public LogicalExecutor(ConditionExecutorFactory executorFactory) {
        this.executorFactory = executorFactory;
    }



    private <C extends Condition> QueryResultSoA executeCondition(
        C condition,
        Map<String, IndexAccessInterface> indexes,
        Query.Granularity granularity,
        int granularitySize,
        String corpusName,
        AttributeRequirements requirements,
        Optional<FilteringContext> context) throws QueryExecutionException {
        ConditionExecutor<C> executor = executorFactory.getExecutor(condition);
        return executor.execute(condition, indexes, granularity, granularitySize, corpusName, requirements, context);
    }

    @Override
    public QueryResultSoA execute(Logical condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements,
                               Optional<FilteringContext> context)
        throws QueryExecutionException {
        return executeInternal(condition, indexes, granularity, granularitySize, corpusName, requirements, context);
    }

    // --- Internal execution logic using QueryResultSoA ---
    private QueryResultSoA executeInternal(Logical condition, Map<String, IndexAccessInterface> indexes,
                                      Query.Granularity granularity,
                                      int granularitySize,
                                      String corpusName,
                                      AttributeRequirements requirements,
                                      Optional<FilteringContext> context)
        throws QueryExecutionException {

        logger.debug("Executing logical condition internally: operator={}, subconditions={}, granularity={}, size={}, corpus={}, requirements={}, contextIsPresent={}",
                condition.operator(), condition.conditions().size(), granularity, granularitySize, corpusName, requirements, context.isPresent());

        List<Condition> subConditions = condition.conditions();
        if (subConditions.isEmpty()) {
            logger.debug("Logical condition has no subconditions, returning empty QueryResultSoA");
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        LogicalOperator operator = condition.operator();
        if (operator == LogicalOperator.AND) {
            return executeAnd(subConditions, indexes, granularity, granularitySize, corpusName, requirements, context);
        } else if (operator == LogicalOperator.OR) {
            return executeOr(subConditions, indexes, granularity, granularitySize, corpusName, requirements, context);
        } else {
            throw new QueryExecutionException("Unsupported logical operator: " + operator, condition.toString(), QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }
    }

    /**
     * Executes a logical AND, operating on QueryResultSoA, with FilteringContext propagation.
     */
    private QueryResultSoA executeAnd(List<Condition> conditions,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Optional<FilteringContext> initialContext)
        throws QueryExecutionException {
        if (conditions.isEmpty()) {
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        Optional<FilteringContext> currentContext = initialContext.isPresent() ? initialContext :
                                                Optional.of(FilteringContext.unrestricted(granularity));
        logger.debug("executeAnd: Initial FilteringContext isPresent: {}, isUnrestricted: {}",
                     currentContext.isPresent(), currentContext.map(FilteringContext::isUnrestricted).orElse(true));

        QueryResultSoA firstResult = executeCondition(conditions.get(0), indexes, granularity, granularitySize, corpusName, requirements, currentContext);

        if (firstResult.isEmpty()) {
            logger.debug("executeAnd: First condition returned empty result. AND chain result is empty.");
            return firstResult;
        }

        currentContext = Optional.of(currentContext.get().intersect(firstResult));
        logger.debug("executeAnd: Context after first condition, isPresent: {}, isUnrestricted: {}, isEmptyFilter: {}",
                     currentContext.isPresent(), currentContext.map(FilteringContext::isUnrestricted).orElse(true),
                     currentContext.map(c -> c.allowedDocumentIds().isPresent() && c.allowedDocumentIds().get().isEmpty()).orElse(false));

        if (currentContext.get().allowedDocumentIds().isPresent() && currentContext.get().allowedDocumentIds().get().isEmpty()){
            logger.debug("executeAnd: FilteringContext became empty (no doc IDs) after first condition. AND chain result is empty.");
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        QueryResultSoA cumulativeResult = firstResult;

        for (int i = 1; i < conditions.size(); i++) {
            logger.debug("executeAnd: Processing condition {} of {} with current context.", i + 1, conditions.size());
            QueryResultSoA currentStepResult = executeCondition(conditions.get(i), indexes, granularity, granularitySize, corpusName, requirements, currentContext);

            if (currentStepResult.isEmpty()) {
                logger.debug("executeAnd: Condition {} returned empty result. AND chain result is empty.", i + 1);
                return currentStepResult;
            }

            cumulativeResult = performAndSoA(cumulativeResult, currentStepResult, granularity, requirements);
            logger.debug("executeAnd: Cumulative result size after performAndSoA with condition {}: {}", i + 1, cumulativeResult.size());

            if (cumulativeResult.isEmpty()) {
                logger.debug("executeAnd: Cumulative result became empty after performAndSoA. AND chain result is empty.");
                return cumulativeResult;
            }

            currentContext = Optional.of(currentContext.get().intersect(currentStepResult));
            logger.debug("executeAnd: Context updated after condition {}, isPresent: {}, isUnrestricted: {}, isEmptyFilter: {}",
                         i + 1, currentContext.isPresent(), currentContext.map(FilteringContext::isUnrestricted).orElse(true),
                         currentContext.map(c -> c.allowedDocumentIds().isPresent() && c.allowedDocumentIds().get().isEmpty()).orElse(false));

            if (currentContext.get().allowedDocumentIds().isPresent() && currentContext.get().allowedDocumentIds().get().isEmpty()){
                logger.debug("executeAnd: FilteringContext became empty (no doc IDs) after condition {}. AND chain result is empty.", i + 1);
                return new QueryResultSoA(granularity, granularitySize, requirements);
            }
        }
        logger.debug("executeAnd: Completed. Final cumulative result size: {}", cumulativeResult.size());
        return cumulativeResult;
    }

    /**
     * Executes a logical OR, operating on QueryResultSoA. Each branch of OR is filtered by the incoming context.
     */
    private QueryResultSoA executeOr(List<Condition> conditions,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Optional<FilteringContext> context)
        throws QueryExecutionException {
        if (conditions.isEmpty()) {
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        QueryResultSoA combinedResult = executeCondition(conditions.get(0), indexes, granularity, granularitySize, corpusName, requirements, context);

        for (int i = 1; i < conditions.size(); i++) {
            QueryResultSoA currentResult = executeCondition(conditions.get(i), indexes, granularity, granularitySize, corpusName, requirements, context);
            if (currentResult.isEmpty()) {
                continue;
            }
            if (combinedResult.isEmpty()) {
                combinedResult = currentResult;
                continue;
            }
            combinedResult = performOrSoA(combinedResult, currentResult, granularity, requirements);
        }
        return combinedResult;
    }

    private QueryResultSoA performAndSoA(QueryResultSoA left, QueryResultSoA right,
                                         Query.Granularity granularity, AttributeRequirements requirements) {
        logger.debug("Performing SoA AND operation (merge join). Left size: {}, Right size: {}. Granularity: {}",
                     left.size(), right.size(), granularity);

        // DEBUG: Log the actual data being merged
        logger.debug("LEFT data:");
        for (int i = 0; i < left.size(); i++) {
            logger.debug("  Left[{}]: docId={}, value={}, valueType={}",
                        i, left.getDocumentIdAt(i), left.getValueAt(i), left.getValueTypeAt(i));
        }
        logger.debug("RIGHT data:");
        for (int i = 0; i < right.size(); i++) {
            logger.debug("  Right[{}]: docId={}, value={}, valueType={}",
                        i, right.getDocumentIdAt(i), right.getValueAt(i), right.getValueTypeAt(i));
        }

        AttributeRequirements combinedReqs = new AttributeRequirements();
        combinedReqs.merge(left.getRequirements());
        combinedReqs.merge(right.getRequirements());
        combinedReqs.needsConceptualRowIds = true; // Crucial for AND/JOIN logic

        // Data from indexes should already be sorted by document ID due to indexing process
        // No need to re-sort - trust the index ordering

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, left.getGranularitySize(), combinedReqs);
        int nextConceptualRowId = 0;

        // Merge join algorithm - now guaranteed to work with sorted inputs
        int leftIdx = 0;
        int rightIdx = 0;

        if (granularity == Query.Granularity.DOCUMENT) {
            while (leftIdx < left.size() && rightIdx < right.size()) {
                int leftDocId = left.getDocumentIdAt(leftIdx);
                int rightDocId = right.getDocumentIdAt(rightIdx);

                logger.trace("Merge join: comparing left[{}] docId={} vs right[{}] docId={}",
                           leftIdx, leftDocId, rightIdx, rightDocId);

                if (leftDocId < rightDocId) {
                    logger.trace("Left docId {} < right docId {}, advancing left", leftDocId, rightDocId);
                    leftIdx++;
                } else if (leftDocId > rightDocId) {
                    logger.trace("Left docId {} > right docId {}, advancing right", leftDocId, rightDocId);
                    rightIdx++;
                } else {
                    logger.debug("MATCH FOUND: docId={}", leftDocId);
                    // Found matching docId - create cross product of all matching entries
                    int currentConceptualId = nextConceptualRowId++;

                    // Store the start positions to iterate over all combinations
                    int leftStart = leftIdx;
                    int rightStart = rightIdx;

                    // Count how many entries we have for this docId on both sides
                    int leftCount = 0;
                    while (leftIdx + leftCount < left.size() && left.getDocumentIdAt(leftIdx + leftCount) == leftDocId) {
                        leftCount++;
                    }

                    int rightCount = 0;
                    while (rightIdx + rightCount < right.size() && right.getDocumentIdAt(rightIdx + rightCount) == rightDocId) {
                        rightCount++;
                    }

                    logger.debug("Adding {} left entries and {} right entries for conceptual ID {}",
                               leftCount, rightCount, currentConceptualId);

                                        // Add all left bindings for this docId
                    for (int li = 0; li < leftCount; li++) {
                        int leftRowIdx = leftStart + li;
                        resultSoA.add(
                            left.getValueAt(leftRowIdx),
                            left.getValueTypeAt(leftRowIdx),
                            left.getVariableNameAt(leftRowIdx),
                            left.getDocumentIdAt(leftRowIdx),
                            combinedReqs.needsSentenceId ? left.getSentenceIdAt(leftRowIdx) : -1,
                            combinedReqs.needsPositions ? left.getBeginCharAt(leftRowIdx) : -1,
                            combinedReqs.needsPositions ? left.getEndCharAt(leftRowIdx) : -1,
                            combinedReqs.needsSynonymIds ? left.getSynonymIdAt(leftRowIdx) : -1,
                            currentConceptualId
                        );
                    }

                    // Add all right bindings for this docId
                    for (int ri = 0; ri < rightCount; ri++) {
                        int rightRowIdx = rightStart + ri;
                        resultSoA.add(
                            right.getValueAt(rightRowIdx),
                            right.getValueTypeAt(rightRowIdx),
                            right.getVariableNameAt(rightRowIdx),
                            right.getDocumentIdAt(rightRowIdx),
                            combinedReqs.needsSentenceId ? right.getSentenceIdAt(rightRowIdx) : -1,
                            combinedReqs.needsPositions ? right.getBeginCharAt(rightRowIdx) : -1,
                            combinedReqs.needsPositions ? right.getEndCharAt(rightRowIdx) : -1,
                            combinedReqs.needsSynonymIds ? right.getSynonymIdAt(rightRowIdx) : -1,
                            currentConceptualId
                        );
                    }

                    // Advance both pointers past all processed entries
                    leftIdx += leftCount;
                    rightIdx += rightCount;
                }
            }
        } else { // Granularity.SENTENCE
            // For sentence granularity, we need to merge on (docId, sentenceId) pairs
            while (leftIdx < left.size() && rightIdx < right.size()) {
                int leftDocId = left.getDocumentIdAt(leftIdx);
                int rightDocId = right.getDocumentIdAt(rightIdx);
                int leftSentId = combinedReqs.needsSentenceId ? left.getSentenceIdAt(leftIdx) : -1;
                int rightSentId = combinedReqs.needsSentenceId ? right.getSentenceIdAt(rightIdx) : -1;

                // Compare (docId, sentenceId) pairs lexicographically
                int comparison = Integer.compare(leftDocId, rightDocId);
                if (comparison == 0 && combinedReqs.needsSentenceId) {
                    comparison = Integer.compare(leftSentId, rightSentId);
                }

                if (comparison < 0) {
                    leftIdx++;
                } else if (comparison > 0) {
                    rightIdx++;
                } else {
                    // Found matching (docId, sentenceId) pair
                    int currentConceptualId = nextConceptualRowId++;

                    // Count entries for this (docId, sentenceId) pair on both sides
                    int leftStart = leftIdx;
                    int rightStart = rightIdx;

                    int leftCount = 0;
                    while (leftIdx + leftCount < left.size() &&
                           left.getDocumentIdAt(leftIdx + leftCount) == leftDocId &&
                           (!combinedReqs.needsSentenceId || left.getSentenceIdAt(leftIdx + leftCount) == leftSentId)) {
                        leftCount++;
                    }

                    int rightCount = 0;
                    while (rightIdx + rightCount < right.size() &&
                           right.getDocumentIdAt(rightIdx + rightCount) == rightDocId &&
                           (!combinedReqs.needsSentenceId || right.getSentenceIdAt(rightIdx + rightCount) == rightSentId)) {
                        rightCount++;
                    }

                    // Add all left bindings for this (docId, sentenceId) pair
                    for (int li = 0; li < leftCount; li++) {
                        int leftRowIdx = leftStart + li;
                        resultSoA.add(
                            left.getValueAt(leftRowIdx),
                            left.getValueTypeAt(leftRowIdx),
                            left.getVariableNameAt(leftRowIdx),
                            left.getDocumentIdAt(leftRowIdx),
                            combinedReqs.needsSentenceId ? left.getSentenceIdAt(leftRowIdx) : -1,
                            combinedReqs.needsPositions ? left.getBeginCharAt(leftRowIdx) : -1,
                            combinedReqs.needsPositions ? left.getEndCharAt(leftRowIdx) : -1,
                            combinedReqs.needsSynonymIds ? left.getSynonymIdAt(leftRowIdx) : -1,
                            currentConceptualId
                        );
                    }

                    // Add all right bindings for this (docId, sentenceId) pair
                    for (int ri = 0; ri < rightCount; ri++) {
                        int rightRowIdx = rightStart + ri;
                        resultSoA.add(
                            right.getValueAt(rightRowIdx),
                            right.getValueTypeAt(rightRowIdx),
                            right.getVariableNameAt(rightRowIdx),
                            right.getDocumentIdAt(rightRowIdx),
                            combinedReqs.needsSentenceId ? right.getSentenceIdAt(rightRowIdx) : -1,
                            combinedReqs.needsPositions ? right.getBeginCharAt(rightRowIdx) : -1,
                            combinedReqs.needsPositions ? right.getEndCharAt(rightRowIdx) : -1,
                            combinedReqs.needsSynonymIds ? right.getSynonymIdAt(rightRowIdx) : -1,
                            currentConceptualId
                        );
                    }

                    // Advance both pointers past all processed entries
                    leftIdx += leftCount;
                    rightIdx += rightCount;
                }
            }
        }

        logger.debug("SoA AND operation (merge join) complete. Result size: {}", resultSoA.size());
        return resultSoA;
    }

    private QueryResultSoA performOrSoA(QueryResultSoA left, QueryResultSoA right,
                                        Query.Granularity granularity, AttributeRequirements baseRequirements) {
        logger.debug("Performing SoA OR operation. Left size: {}, Right size: {}. Granularity: {}",
                    left.size(), right.size(), granularity);

        AttributeRequirements combinedReqs = new AttributeRequirements();
        combinedReqs.merge(left.getRequirements());
        combinedReqs.merge(right.getRequirements());
        combinedReqs.needsConceptualRowIds = true; // OR operations also need to manage conceptual IDs

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, left.getGranularitySize(), combinedReqs);
        int maxLeftConceptualId = -1;

        // Add all from left, preserving conceptual IDs and finding max
        if (left.getRequirements().needsConceptualRowIds) {
            for (int i = 0; i < left.size(); i++) {
                int conceptualId = left.getConceptualRowIdAt(i);
                resultSoA.add(
                    left.getValueAt(i),
                    left.getValueTypeAt(i),
                    left.getVariableNameAt(i),
                    left.getDocumentIdAt(i),
                    combinedReqs.needsSentenceId ? left.getSentenceIdAt(i) : -1,
                    combinedReqs.needsPositions ? left.getBeginCharAt(i) : -1,
                    combinedReqs.needsPositions ? left.getEndCharAt(i) : -1,
                    combinedReqs.needsSynonymIds ? left.getSynonymIdAt(i) : -1,
                    conceptualId
                );
                if (conceptualId > maxLeftConceptualId) {
                    maxLeftConceptualId = conceptualId;
                }
            }
        } else {
            // If left doesn't have conceptual IDs (e.g., from a very old executor not yet updated), assign new ones.
            // This is a fallback and ideally all executors should provide conceptual IDs.
            int currentConceptualId = 0;
            for (int i = 0; i < left.size(); i++) {
                 currentConceptualId = i; // Simplistic: 1 new conceptual ID per entry
                resultSoA.add(
                    left.getValueAt(i),
                    left.getValueTypeAt(i),
                    left.getVariableNameAt(i),
                    left.getDocumentIdAt(i),
                    combinedReqs.needsSentenceId ? left.getSentenceIdAt(i) : -1,
                    combinedReqs.needsPositions ? left.getBeginCharAt(i) : -1,
                    combinedReqs.needsPositions ? left.getEndCharAt(i) : -1,
                    combinedReqs.needsSynonymIds ? left.getSynonymIdAt(i) : -1,
                    currentConceptualId
                );
                 if (currentConceptualId > maxLeftConceptualId) {
                    maxLeftConceptualId = currentConceptualId;
                }
            }
             logger.warn("Left QueryResultSoA in OR operation did not have conceptualRowIds. Assigning new ones. This may indicate an older executor.");
        }


        // Add all from right, offsetting conceptual IDs to ensure uniqueness
        int offset = maxLeftConceptualId + 1;
        if (right.getRequirements().needsConceptualRowIds) {
            for (int i = 0; i < right.size(); i++) {
                int conceptualId = right.getConceptualRowIdAt(i) + offset;
                resultSoA.add(
                    right.getValueAt(i),
                    right.getValueTypeAt(i),
                    right.getVariableNameAt(i),
                    right.getDocumentIdAt(i),
                    combinedReqs.needsSentenceId ? right.getSentenceIdAt(i) : -1,
                    combinedReqs.needsPositions ? right.getBeginCharAt(i) : -1,
                    combinedReqs.needsPositions ? right.getEndCharAt(i) : -1,
                    combinedReqs.needsSynonymIds ? right.getSynonymIdAt(i) : -1,
                    conceptualId
                );
            }
        } else {
            // Fallback for right side if no conceptual IDs
            int currentConceptualIdOffset = 0;
            for (int i = 0; i < right.size(); i++) {
                currentConceptualIdOffset = i; // Simplistic: 1 new conceptual ID per entry
                resultSoA.add(
                    right.getValueAt(i),
                    right.getValueTypeAt(i),
                    right.getVariableNameAt(i),
                    right.getDocumentIdAt(i),
                    combinedReqs.needsSentenceId ? right.getSentenceIdAt(i) : -1,
                    combinedReqs.needsPositions ? right.getBeginCharAt(i) : -1,
                    combinedReqs.needsPositions ? right.getEndCharAt(i) : -1,
                    combinedReqs.needsSynonymIds ? right.getSynonymIdAt(i) : -1,
                    offset + currentConceptualIdOffset
                );
            }
            logger.warn("Right QueryResultSoA in OR operation did not have conceptualRowIds. Assigning new offset ones. This may indicate an older executor.");
        }

        logger.debug("SoA OR operation complete. Result size: {}", resultSoA.size());
        return resultSoA;
    }
}