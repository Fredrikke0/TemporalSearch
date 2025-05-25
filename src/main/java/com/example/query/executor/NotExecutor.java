package com.example.query.executor;

import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.core.Position;
import com.example.query.model.Query;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Not;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import com.example.query.executor.QueryResult;
import org.iq80.leveldb.DBIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.AbstractMap.SimpleEntry;

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

    @Override
    public QueryResult execute(Not condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName) throws QueryExecutionException {
        logger.debug("Executing NOT condition: {}, Granularity: {}, Size: {}, Corpus: {}", 
                     condition, granularity, granularitySize, corpusName);

        Condition operand = condition.condition();
        ConditionExecutor<Condition> subExecutor = factory.getExecutor(operand);

        // Execute the sub-condition using default requirements for backward compatibility
        AttributeRequirements defaultRequirements = new AttributeRequirements();
        QueryResult subResult = subExecutor.execute(operand, indexes, granularity, granularitySize, corpusName, defaultRequirements);

        // Extract IDs based on granularity from the sub-result
        Set<?> subResultIds = extractIds(subResult, granularity);
        logger.debug("Sub-condition executed. Found {} match details, {} unique IDs.", subResult.getAllDetails().size(), subResultIds.size());

        // Get all possible matches (as IDs) based on granularity
        Set<?> allPossibleIds = getAllPossibleIds(indexes, granularity);
        if (allPossibleIds.isEmpty()) {
            logger.warn("Could not determine the set of all possible matches. Check if '{}' index exists and is populated.", UNIGRAM_INDEX_NAME);
            throw new QueryExecutionException(
                "Failed to retrieve the set of all possible documents/sentences for NOT operation.",
                condition.toString(),
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }
        logger.debug("Total possible IDs for granularity {}: {}", granularity, allPossibleIds.size());

        // Perform the NOT operation (set difference on IDs)
        Set<?> resultIds = new HashSet<>(allPossibleIds); // Create a mutable copy
        resultIds.removeAll(subResultIds);
        logger.debug("Resulting IDs after NOT operation: {}", resultIds.size());

        // Create the final QueryResult from the remaining IDs by creating placeholder MatchDetails
        // We need to reconstruct minimal Position objects based on the ID type
        return new QueryResult(granularity, granularitySize, resultIds.stream()
                .map(id -> createPlaceholderMatchDetail(id, granularity))
                .collect(Collectors.toList()));
    }

    /** Helper to extract IDs based on granularity */
    private Set<?> extractIds(QueryResult queryResult, Query.Granularity granularity) {
        if (granularity == Query.Granularity.DOCUMENT) {
            return queryResult.getAllDetails().stream()
                    .map(MatchDetail::getDocumentId)
                    .collect(Collectors.toSet());
        } else { // SENTENCE granularity
            return queryResult.getAllDetails().stream()
                    .map(d -> new SimpleEntry<>(d.getDocumentId(), d.getSentenceId()))
                    .collect(Collectors.toSet());
        }
    }

    /** Helper to create placeholder MatchDetail from an ID */
    private MatchDetail createPlaceholderMatchDetail(Object id, Query.Granularity granularity) {
        if (granularity == Query.Granularity.DOCUMENT) {
            Integer docId = (Integer) id;
            return new MatchDetail("NOT_MATCH", ValueType.TERM, (String) null, docId, -1, -1, -1);
        } else { // SENTENCE granularity
            @SuppressWarnings("unchecked")
            SimpleEntry<Integer, Integer> pair = (SimpleEntry<Integer, Integer>) id;
            return new MatchDetail("NOT_MATCH", ValueType.TERM, (String) null, pair.getKey(), pair.getValue(), -1, -1);
        }
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

        Set<Object> allIds = new HashSet<>(); // Use Set<Object> to hold Integers or SimpleEntries
        logger.debug("Iterating '{}' index to approximate universe for NOT (granularity: {})...", UNIGRAM_INDEX_NAME, granularity);
        long count = 0;

        try (DBIterator iterator = unigramIndex.iterator()) {
            iterator.seekToFirst();
            while (iterator.hasNext()) {
                Map.Entry<byte[], byte[]> entry = iterator.next();
                byte[] valueBytes = entry.getValue();
                if (valueBytes == null || valueBytes.length == 0) {
                    continue;
                }

                try {
                    PositionListSoA positionList = PositionListSoA.deserializeFromCompositeBlob(valueBytes);
                    count++;
                    
                    // Extract positions and add to the set
                    for (int i = 0; i < positionList.getNumPositions(); i++) {
                        Position actualPosition = positionList.getPositionAt(i);
                        if (granularity == Query.Granularity.DOCUMENT) {
                            allIds.add(actualPosition.getDocumentId());
                        } else { // SENTENCE granularity
                            allIds.add(new SimpleEntry<>(actualPosition.getDocumentId(), actualPosition.getSentenceId()));
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to deserialize PositionListSoA for key '{}' in '{}': {}",
                            new String(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8),
                            UNIGRAM_INDEX_NAME, e.getMessage());
                    continue; // Skip this entry and continue with the next
                }
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
    public QueryResult execute(Not condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements)
        throws QueryExecutionException {
        
        logger.debug("Executing NOT condition with AttributeRequirements: {}", requirements.getRequiredSoAAttributes());
        
        Condition operand = condition.condition();
        ConditionExecutor<Condition> subExecutor = factory.getExecutor(operand);

        // Execute the sub-condition with the provided requirements
        QueryResult subResult = subExecutor.execute(operand, indexes, granularity, granularitySize, corpusName, requirements);

        // Extract IDs based on granularity from the sub-result
        Set<?> subResultIds = extractIds(subResult, granularity);
        logger.debug("Sub-condition executed. Found {} match details, {} unique IDs.", subResult.getAllDetails().size(), subResultIds.size());

        // Get all possible matches (as IDs) based on granularity
        Set<?> allPossibleIds = getAllPossibleIds(indexes, granularity);
        if (allPossibleIds.isEmpty()) {
            logger.warn("Could not determine the set of all possible matches. Check if '{}' index exists and is populated.", UNIGRAM_INDEX_NAME);
            throw new QueryExecutionException(
                "Failed to retrieve the set of all possible documents/sentences for NOT operation.",
                condition.toString(),
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }
        logger.debug("Total possible IDs for granularity {}: {}", granularity, allPossibleIds.size());

        // Perform the NOT operation (set difference on IDs)
        Set<?> resultIds = new HashSet<>(allPossibleIds); // Create a mutable copy
        resultIds.removeAll(subResultIds);
        logger.debug("Resulting IDs after NOT operation: {}", resultIds.size());

        // Create the final QueryResult from the remaining IDs by creating placeholder MatchDetails
        // We need to reconstruct minimal Position objects based on the ID type
        return new QueryResult(granularity, granularitySize, resultIds.stream()
                .map(id -> createPlaceholderMatchDetail(id, granularity))
                .collect(Collectors.toList()));
    }
} 