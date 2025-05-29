package com.example.query.executor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        AttributeRequirements requirements) throws QueryExecutionException {
        ConditionExecutor<C> executor = executorFactory.getExecutor(condition);
        return executor.execute(condition, indexes, granularity, granularitySize, corpusName, requirements);
    }

    @Override
    public QueryResultSoA execute(Logical condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements)
        throws QueryExecutionException {
        QueryResultSoA internalResult = executeInternal(condition, indexes, granularity, granularitySize, corpusName, requirements);
         return internalResult;
    }

    // --- Internal execution logic using QueryResultSoA ---
    private QueryResultSoA executeInternal(Logical condition, Map<String, IndexAccessInterface> indexes,
                                      Query.Granularity granularity,
                                      int granularitySize,
                                      String corpusName,
                                      AttributeRequirements requirements)
        throws QueryExecutionException {

        logger.debug("Executing logical condition internally: operator={}, subconditions={}, granularity={}, size={}, corpus={}, requirements={}",
                condition.operator(), condition.conditions().size(), granularity, granularitySize, corpusName, requirements);

        List<Condition> subConditions = condition.conditions();
        if (subConditions.isEmpty()) {
            logger.debug("Logical condition has no subconditions, returning empty QueryResultSoA");
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        LogicalOperator operator = condition.operator();
        if (operator == LogicalOperator.AND) {
            return executeAnd(subConditions, indexes, granularity, granularitySize, corpusName, requirements);
        } else if (operator == LogicalOperator.OR) {
            return executeOr(subConditions, indexes, granularity, granularitySize, corpusName, requirements);
        } else {
            throw new QueryExecutionException("Unsupported logical operator: " + operator, condition.toString(), QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }
    }

    /**
     * Executes a logical AND, operating on QueryResultSoA.
     */
    private QueryResultSoA executeAnd(List<Condition> conditions,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements)
        throws QueryExecutionException {
        if (conditions.isEmpty()) {
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        // Execute the first condition
        QueryResultSoA combinedResult = executeCondition(conditions.get(0), indexes, granularity, granularitySize, corpusName, requirements);
        if (combinedResult.isEmpty()) {
            return combinedResult; // Early exit if any AND condition returns no results
        }

        // Iteratively apply AND with subsequent conditions
        for (int i = 1; i < conditions.size(); i++) {
            QueryResultSoA currentResult = executeCondition(conditions.get(i), indexes, granularity, granularitySize, corpusName, requirements);
            if (currentResult.isEmpty()) {
                return currentResult; // Early exit
            }
            combinedResult = performAndSoA(combinedResult, currentResult, granularity, requirements);

            if (combinedResult.isEmpty()) {
                return combinedResult; // Early exit
            }
        }
        return combinedResult;
    }

    /**
     * Executes a logical OR, operating on QueryResultSoA.
     */
    private QueryResultSoA executeOr(List<Condition> conditions,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements)
        throws QueryExecutionException {
        if (conditions.isEmpty()) {
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        // Execute the first condition
        QueryResultSoA combinedResult = executeCondition(conditions.get(0), indexes, granularity, granularitySize, corpusName, requirements);

        // Iteratively apply OR with subsequent conditions
        for (int i = 1; i < conditions.size(); i++) {
            QueryResultSoA currentResult = executeCondition(conditions.get(i), indexes, granularity, granularitySize, corpusName, requirements);
            if (currentResult.isEmpty()) {
                // If current is empty, combinedResult remains as is
                continue;
            }
            if (combinedResult.isEmpty()) {
                // If combined was empty and current is not, current becomes the new combined
                combinedResult = currentResult;
                continue;
            }
            combinedResult = performOrSoA(combinedResult, currentResult, granularity, requirements);
        }
        return combinedResult;
    }

    private QueryResultSoA performAndSoA(QueryResultSoA left, QueryResultSoA right,
                                         Query.Granularity granularity, AttributeRequirements requirements) {
        logger.debug("Performing SoA AND operation. Left size: {}, Right size: {}. Granularity: {}",
                     left.size(), right.size(), granularity);

        AttributeRequirements combinedReqs = new AttributeRequirements();
        combinedReqs.merge(left.getRequirements());
        combinedReqs.merge(right.getRequirements());
        combinedReqs.needsConceptualRowIds = true; // Crucial for AND/JOIN logic

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, left.getGranularitySize(), combinedReqs);
        int nextConceptualRowId = 0;

        if (granularity == Query.Granularity.DOCUMENT) {
            // Map docId to list of original indices in 'left'
            Map<Integer, List<Integer>> leftDocIdToIndices = new HashMap<>();
            for (int i = 0; i < left.size(); i++) {
                leftDocIdToIndices.computeIfAbsent(left.getDocumentIdAt(i), k -> new ArrayList<>()).add(i);
            }

            // Find common docIds and process them
            Set<Integer> rightProcessedDocIds = new HashSet<>(); // To avoid processing right-side docs multiple times if they have multiple entries for the same docId
            for (int i = 0; i < right.size(); i++) {
                int rightDocId = right.getDocumentIdAt(i);
                if (leftDocIdToIndices.containsKey(rightDocId) && !rightProcessedDocIds.contains(rightDocId)) {
                    // Common docId found, generate a new conceptual row
                    int currentOutputConceptualId = nextConceptualRowId++;

                    // Add all left-side bindings for this common docId
                    for (int leftIndex : leftDocIdToIndices.get(rightDocId)) {
                        resultSoA.add(
                            left.getValueAt(leftIndex),
                            left.getValueTypeAt(leftIndex),
                            left.getVariableNameAt(leftIndex),
                            left.getDocumentIdAt(leftIndex),
                            combinedReqs.needsSentenceId ? left.getSentenceIdAt(leftIndex) : -1,
                            combinedReqs.needsPositions ? left.getBeginCharAt(leftIndex) : -1,
                            combinedReqs.needsPositions ? left.getEndCharAt(leftIndex) : -1,
                            combinedReqs.needsSynonymIds ? left.getSynonymIdAt(leftIndex) : -1,
                            currentOutputConceptualId
                        );
                    }

                    // Add all right-side bindings for this common docId
                    // Need to iterate through 'right' to find all matches for rightDocId
                    for (int j = 0; j < right.size(); j++) {
                        if (right.getDocumentIdAt(j) == rightDocId) {
                            resultSoA.add(
                                right.getValueAt(j),
                                right.getValueTypeAt(j),
                                right.getVariableNameAt(j),
                                right.getDocumentIdAt(j),
                                combinedReqs.needsSentenceId ? right.getSentenceIdAt(j) : -1,
                                combinedReqs.needsPositions ? right.getBeginCharAt(j) : -1,
                                combinedReqs.needsPositions ? right.getEndCharAt(j) : -1,
                                combinedReqs.needsSynonymIds ? right.getSynonymIdAt(j) : -1,
                                currentOutputConceptualId
                            );
                        }
                    }
                    rightProcessedDocIds.add(rightDocId);
                }
            }
        } else { // Granularity.SENTENCE
            // Map DocSentIdPair to list of original indices in 'left'
            Map<DocSentIdPair, List<Integer>> leftPairToIndices = new HashMap<>();
            if (left.getRequirements().needsSentenceId) {
                for (int i = 0; i < left.size(); i++) {
                    leftPairToIndices.computeIfAbsent(
                        new DocSentIdPair(left.getDocumentIdAt(i), left.getSentenceIdAt(i)),
                        k -> new ArrayList<>()
                    ).add(i);
                }
            }

            Set<DocSentIdPair> rightProcessedPairs = new HashSet<>();
            if (right.getRequirements().needsSentenceId && left.getRequirements().needsSentenceId) {
                for (int i = 0; i < right.size(); i++) {
                    DocSentIdPair rightPair = new DocSentIdPair(right.getDocumentIdAt(i), right.getSentenceIdAt(i));
                    if (leftPairToIndices.containsKey(rightPair) && !rightProcessedPairs.contains(rightPair)) {
                        int currentOutputConceptualId = nextConceptualRowId++;

                        // Add all left-side bindings for this common pair
                        for (int leftIndex : leftPairToIndices.get(rightPair)) {
                            resultSoA.add(
                                left.getValueAt(leftIndex),
                                left.getValueTypeAt(leftIndex),
                                left.getVariableNameAt(leftIndex),
                                left.getDocumentIdAt(leftIndex),
                                left.getSentenceIdAt(leftIndex), // SentenceId is definitely available
                                combinedReqs.needsPositions ? left.getBeginCharAt(leftIndex) : -1,
                                combinedReqs.needsPositions ? left.getEndCharAt(leftIndex) : -1,
                                combinedReqs.needsSynonymIds ? left.getSynonymIdAt(leftIndex) : -1,
                                currentOutputConceptualId
                            );
                        }

                        // Add all right-side bindings for this common pair
                        for (int j = 0; j < right.size(); j++) {
                            if (right.getDocumentIdAt(j) == rightPair.docId() && right.getSentenceIdAt(j) == rightPair.sentId()) {
                                resultSoA.add(
                                    right.getValueAt(j),
                                    right.getValueTypeAt(j),
                                    right.getVariableNameAt(j),
                                    right.getDocumentIdAt(j),
                                    right.getSentenceIdAt(j), // SentenceId is definitely available
                                    combinedReqs.needsPositions ? right.getBeginCharAt(j) : -1,
                                    combinedReqs.needsPositions ? right.getEndCharAt(j) : -1,
                                    combinedReqs.needsSynonymIds ? right.getSynonymIdAt(j) : -1,
                                    currentOutputConceptualId
                                );
                            }
                        }
                        rightProcessedPairs.add(rightPair);
                    }
                }
            }
        }
        logger.debug("SoA AND operation complete. Result size: {}", resultSoA.size());
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