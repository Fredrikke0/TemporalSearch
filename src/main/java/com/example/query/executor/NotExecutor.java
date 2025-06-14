package com.example.query.executor;

import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Not;

/**
 * Executes a NOT condition.
 * This executor is currently disabled pending refactoring for QueryResult.
 */
// @Disabled // Re-enable this executor
public final class NotExecutor implements ConditionExecutor<Not> {
    private static final Logger logger = LoggerFactory.getLogger(NotExecutor.class);
    private static final String UNIGRAM_INDEX_NAME = "unigram"; // Target index for universe approximation

    private final ConditionExecutorFactory factory;

    /**
     * Constructs a NotExecutor.
     *
     * @param factory The factory to get sub-executors.
     */
    public NotExecutor(ConditionExecutorFactory factory) {
        this.factory = factory;
        logger.info("NotExecutor initialized.");
    }

    public QueryResultSoA execute(Not condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               Optional<FilteringContext> context) throws QueryExecutionException {
        logger.debug("Executing NOT condition (delegating to execute with default AttributeRequirements): {}, Granularity: {}, Size: {}, Corpus: {}, ContextIsPresent: {}",
                     condition, granularity, granularitySize, corpusName, context.isPresent());
        AttributeRequirements defaultRequirements = new AttributeRequirements();
        if (granularity == Query.Granularity.SENTENCE) {
            defaultRequirements.needsSentenceId = true;
        }
        defaultRequirements.needsConceptualRowIds = true;
        return execute(condition, indexes, granularity, granularitySize, corpusName, defaultRequirements, context);
    }

    /** Helper to extract IDs based on granularity */
    private Set<?> extractIds(QueryResultSoA queryResult, Query.Granularity granularity) {
        Set<Object> ids = new HashSet<>(); // Use Object to hold Integer or SimpleEntry
        if (granularity == Query.Granularity.DOCUMENT) {
            for (int i = 0; i < queryResult.size(); i++) {
                // Ensure we only add unique document IDs, conceptual IDs might map to same docId multiple times
                // This needs to be reviewed: if a conceptual ID maps to a doc ID, that doc ID is "hit".
                // We are interested in the set of *document IDs* that the subquery matched.
                // A simple iteration might give duplicate doc IDs if multiple bindings map to the same doc.
                // Using a Set naturally handles this.
                 ids.add(queryResult.getDocumentIdAt(i));
            }
        } else { // SENTENCE granularity
            for (int i = 0; i < queryResult.size(); i++) {
                if (queryResult.getRequirements().needsSentenceId) {
                    ids.add(new SimpleEntry<>(queryResult.getDocumentIdAt(i), queryResult.getSentenceIdAt(i)));
                } else {
                    // This case should ideally not happen if granularity is SENTENCE.
                    // Or, if it does, it implies only doc ID is available, which is like DOCUMENT granularity for this entry.
                    // Log a warning, and potentially add just the doc ID if that makes sense.
                    logger.warn("Sentence granularity requested for NOT, but sub-result for conceptual ID {} lacks sentence ID. Doc ID: {}",
                                queryResult.getConceptualRowIdAt(i), queryResult.getDocumentIdAt(i));
                    // Fallback: add document ID if sentence ID is missing but required by granularity.
                    // This behavior might need refinement based on strictness.
                    // ids.add(queryResult.getDocumentIdAt(i)); // Option: treat as document-level match if sentId missing
                }
            }
        }
        return ids;
    }

    /**
     * Retrieves all unique document IDs or sentence ID pairs by iterating the unigram index.
     * This serves as an approximation of the "universe" of possible matches.
     */
    private Set<?> getAllPossibleIds(Map<String, IndexAccessInterface> indexes, Query.Granularity granularity)
            throws QueryExecutionException {
        IndexAccessInterface unigramIndex = indexes.get(UNIGRAM_INDEX_NAME);
        if (unigramIndex == null) {
            logger.error("Required index '{}' not found for approximating universe in NOT operation.", UNIGRAM_INDEX_NAME);
            throw new QueryExecutionException(
                "Required index '" + UNIGRAM_INDEX_NAME + "' is missing for NOT operation.",
                "N/A",
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }

        Set<Object> allIds = new HashSet<>();
        logger.debug("Iterating '{}' index to approximate universe for NOT (granularity: {})...", UNIGRAM_INDEX_NAME, granularity);
        long count = 0;

        try (RocksIterator iterator = unigramIndex.iterateFromFirst()) {
            while (iterator.isValid()) {
                byte[] keyBytes = iterator.key();
                byte[] valueBytes = iterator.value();
                if (valueBytes == null || valueBytes.length == 0) {
                    iterator.next();
                    continue;
                }

                try {
                    PositionListSoA positionList = PositionListSoA.deserializeFromCompositeBlob(valueBytes);
                    count++;

                    for (int i = 0; i < positionList.getNumPositions(); i++) {
                        Position actualPosition = positionList.getPositionAt(i);
                        if (granularity == Query.Granularity.DOCUMENT) {
                            allIds.add(actualPosition.getDocumentId());
                        } else {
                            allIds.add(new SimpleEntry<>(actualPosition.getDocumentId(), actualPosition.getSentenceId()));
                        }
                    }
                } catch (IOException e) {
                    logger.warn("Failed to deserialize PositionListSoA for key '{}' in '{}': {}",
                            new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8),
                            UNIGRAM_INDEX_NAME, e.getMessage());
                } catch (Exception e) {
                    logger.warn("Error processing entry for key '{}' in '{}' during universe creation: {}",
                            new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8),
                            UNIGRAM_INDEX_NAME, e.getMessage());
                }
                iterator.next();
            }
        } catch (Exception e) {
            logger.error("Failed to iterate through '{}' index: {}", UNIGRAM_INDEX_NAME, e.getMessage(), e);
            throw new QueryExecutionException(
                "Error accessing index '" + UNIGRAM_INDEX_NAME + "' for NOT operation.",
                e,
                "N/A",
                QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR
            );
        }

        logger.debug("Finished iterating '{}'. Found {} unique IDs from {} PositionListSoA entries for granularity {}",
                     UNIGRAM_INDEX_NAME, allIds.size(), count, granularity);
        return allIds;
    }

    @Override
    public QueryResultSoA execute(Not condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements,
                               Optional<FilteringContext> context)
        throws QueryExecutionException {
        logger.debug(">>> Executing NotExecutor");
        Condition subCondition = condition.condition();
        AttributeRequirements subConditionRequirements = new AttributeRequirements();
        logger.debug("Executing NOT condition with AttributeRequirements: {}, ContextIsPresent: {}",
                     requirements.getRequiredSoAAttributes(), context.isPresent());

        ConditionExecutor<Condition> subExecutor = factory.getExecutor(subCondition);

        // Execute the sub-condition with the provided requirements AND context
        QueryResultSoA subResult = subExecutor.execute(subCondition, indexes, granularity, granularitySize, corpusName, requirements, context);

        Set<?> subResultIds = extractIds(subResult, granularity);
        logger.debug("Sub-condition executed. Found {} entries, resulting in {} unique IDs for NOT logic.", subResult.size(), subResultIds.size());

        Set<?> allPossibleIds = getAllPossibleIds(indexes, granularity);
        if (allPossibleIds.isEmpty()) {
            logger.error("Universe for NOT operation is empty. Check if '{}' index exists and is populated.", UNIGRAM_INDEX_NAME);
            throw new QueryExecutionException(
                "Could not determine the set of all possible matches (universe is empty). Check if '" + UNIGRAM_INDEX_NAME + "' index exists and is populated.",
                "N/A",
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }
        logger.debug("Total possible IDs for granularity {}: {}", granularity, allPossibleIds.size());

        Set<?> resultIds = new HashSet<>(allPossibleIds);
        resultIds.removeAll(subResultIds);
        logger.debug("Resulting IDs after NOT operation: {}", resultIds.size());

        // Directly populate the QueryResultSoA
        QueryResultSoA finalResult = new QueryResultSoA(granularity, granularitySize, requirements);
        int conceptualRowIdCounter = 0;

        for (Object id : resultIds) {
            int docId;
            int sentenceId = -1; // Default if not applicable or not needed

            if (granularity == Query.Granularity.DOCUMENT) {
                docId = (Integer) id;
            } else { // SENTENCE granularity
                @SuppressWarnings("unchecked")
                SimpleEntry<Integer, Integer> pair = (SimpleEntry<Integer, Integer>) id;
                docId = pair.getKey();
                if (requirements.needsSentenceId) {
                    sentenceId = pair.getValue();
                }
            }

            // For NOT results, value, variable, positions are typically not meaningful
            // as they represent the *absence* of the sub-condition's match.
            finalResult.add(
                null,                             // value (placeholder)
                ValueType.TERM,        // Using TERM with null value as a placeholder for NOT match
                null,                             // variableName (placeholder)
                docId,
                sentenceId,
                -1,                               // beginChar (placeholder)
                -1,                               // endChar (placeholder)
                -1,                               // synonymId (placeholder)
                conceptualRowIdCounter++
            );
        }
        logger.info("NOT condition execution complete. Produced {} result entries.", finalResult.size());

        // Sort by document ID to ensure merge join optimization works correctly
        finalResult.sort();

        return finalResult;
    }
}