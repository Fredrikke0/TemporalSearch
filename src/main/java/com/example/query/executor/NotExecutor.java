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
     * Retrieves all unique document IDs or sentence ID pairs.
     * If a restrictive FilteringContext is provided, it's used as the universe.
     * Otherwise, iterates the unigram index to approximate the "universe" of possible matches.
     */
    private Set<?> getAllPossibleIds(Map<String, IndexAccessInterface> indexes,
                                     Query.Granularity granularity,
                                     Optional<FilteringContext> context)
            throws QueryExecutionException {

        if (context.isPresent() && !context.get().isUnrestricted()) {
            logger.debug("Using FilteringContext to define universe for NOT operation (granularity: {}).", granularity);
            FilteringContext fc = context.get();
            Set<Object> idsFromContext = new HashSet<>();

            if (granularity == Query.Granularity.DOCUMENT) {
                fc.allowedDocumentIds().ifPresent(docIds -> idsFromContext.addAll(docIds));
                logger.debug("Defined universe from FilteringContext: {} document IDs.", idsFromContext.size());
            } else { // SENTENCE granularity
                fc.allowedDocumentSentenceIds().ifPresent(docSentMap -> {
                    docSentMap.forEach((docId, sentIds) -> {
                        sentIds.forEach(sentId -> idsFromContext.add(new SimpleEntry<>(docId, sentId)));
                    });
                });
                if (!idsFromContext.isEmpty()) {
                     logger.debug("Defined universe from FilteringContext: {} sentence IDs.", idsFromContext.size());
                } else {
                    // This can happen if allowedDocumentIds is present but allowedDocumentSentenceIds is empty or not specific enough.
                    // Or if granularity is SENTENCE but context only has doc IDs.
                    // Fallback to doc IDs if sentence IDs are not specifically restricted by the context but doc IDs are.
                    // This scenario implies "all sentences within the allowed documents".
                    // However, to truly get all sentences for those docs, we'd still need to scan.
                    // For now, if allowedDocumentSentenceIds is empty but allowedDocumentIds is not,
                    // we might be in a mixed state. The most correct 'universe' in this specific branch
                    // if sentence granularity is truly required is what allowedDocumentSentenceIds provides.
                    // If it's empty, the restricted universe of sentences is empty.
                    // If the context *only* had documentIds, it means "all sentences in these documents".
                    // The current FilteringContext structure for SENTENCE granularity expects allowedDocumentSentenceIds
                    // to be populated if sentence-level restriction is intended.
                    // If allowedDocumentIds() is present, but allowedDocumentSentenceIds() is not,
                    // it means "these documents are allowed, but no specific sentences within them are restricted yet by the context".
                    // This is tricky. For NOT, we need a defined set.
                    // If allowedDocumentSentenceIds is empty/absent, but allowedDocumentIds is present AND sentence granularity,
                    // this implies the context isn't specific enough for sentences yet.
                    // In this specific sub-case, falling back to unigram index for these docs might be one option,
                    // or considering the universe for sentences as empty if no specific sentences are allowed by context.
                    // Let's assume if fc.allowedDocumentSentenceIds() is not populated meaningfully for SENTENCE,
                    // the universe of *specific sentences* from context is empty.
                    // The safer option if context is restrictive but not for the right granularity might be to still scan,
                    // but ONLY for the documents specified in allowedDocumentIds. This is an optimization for later.
                    // For now, if allowedDocumentSentenceIds is empty, the context-derived sentence universe is empty.
                    logger.debug("Defined universe from FilteringContext for SENTENCE granularity. Allowed sentences map resulted in {} specific sentence IDs.", idsFromContext.size());
                }
            }
            // If idsFromContext is empty after trying to use context, it means the context itself implies an empty universe.
            // This is a valid state (e.g., previous AND conditions yielded no common docs/sentences).
            return idsFromContext;
        }

        logger.debug("FilteringContext is unrestricted or not present. Iterating '{}' index to approximate universe for NOT (granularity: {})...", UNIGRAM_INDEX_NAME, granularity);
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
        subConditionRequirements.merge(requirements); // Start with parent requirements
        // Ensure sub-condition also fetches what's needed for ID extraction based on granularity
        if (granularity == Query.Granularity.SENTENCE) {
            subConditionRequirements.needsSentenceId = true;
        }
        // Sub-condition needs doc IDs for sure if we are doing NOT.
        // It does not necessarily need conceptual row IDs itself unless it's complex.
        // The requirements passed from parent already cover what the *final* QueryResultSoA of NOT needs.

        logger.debug("Executing NOT condition with incoming AttributeRequirements: {}, ContextIsPresent: {}. Sub-condition will use merged requirements.",
                     requirements.getRequiredSoAAttributes(), context.isPresent());

        ConditionExecutor<Condition> subExecutor = factory.getExecutor(subCondition);

        // Execute the sub-condition with the provided requirements AND context
        QueryResultSoA subResult = subExecutor.execute(subCondition, indexes, granularity, granularitySize, corpusName, subConditionRequirements, context);

        Set<?> subResultIds = extractIds(subResult, granularity);
        logger.debug("Sub-condition executed. Found {} entries, resulting in {} unique IDs for NOT logic.", subResult.size(), subResultIds.size());

        // Pass the context to getAllPossibleIds
        Set<?> allPossibleIds = getAllPossibleIds(indexes, granularity, context);
        if (allPossibleIds.isEmpty() && (context.isEmpty() || context.get().isUnrestricted())) {
            logger.error("Universe for NOT operation is empty (and context was unrestricted). Check if '{}' index exists and is populated.", UNIGRAM_INDEX_NAME);
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