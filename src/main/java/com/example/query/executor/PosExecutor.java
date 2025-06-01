package com.example.query.executor;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Pos;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Executor for POS conditions.
 * Handles matching POS tags against indexed data.
 *
 * POS Condition Logic:
 *   - Simple match: POS(tag) -> Finds sentences containing the specified tag.
 *   - Match with term: POS(tag, term) -> Finds sentences where 'term' has the specified tag.
 *   - Variable binding: POS(tag) BIND var -> Binds var to the tag string itself.
 *   - Variable consumption: POS(tag, var) -> Filters sentences where 'var' (must be text) has the tag.
 * This executor reflects the basic index structure where keys are POS tags
 * and values are lists of all positions for that tag.
 * It supports:
 *   - Basic lookup: POS(tag) -> Returns matches with the tag as the value.
 *   - Variable binding: POS(tag) BIND ?var -> Binds ?var to the tag string itself.
 * It does NOT support:
 *   - Term specification: POS(tag, 'term')
 * Returns QueryResult containing MatchDetail objects.
 */
public final class PosExecutor implements ConditionExecutor<Pos> {
    private static final Logger logger = LoggerFactory.getLogger(PosExecutor.class);

    private static final String POS_INDEX = "pos";

    /**
     * Creates a new POS executor.
     */
    public PosExecutor() {
        // No initialization required
    }

    @Override
    public QueryResultSoA execute(Pos condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements)
        throws QueryExecutionException {

        logger.debug("Executing POS condition with AttributeRequirements: {}", requirements.getRequiredSoAAttributes());

        String posTag = condition.posTag();
        String term = condition.term(); // Can be null
        boolean isVariable = condition.isVariable();
        String variableName = condition.variableName();

        if ("*".equals(posTag)) {
            throw new QueryExecutionException(
                "Wildcard POS tag (*) is not supported by the PosExecutor.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION
            );
        }

        if (!indexes.containsKey(POS_INDEX)) {
            throw new QueryExecutionException(
                "Missing required POS index",
                condition.toString(),
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }

        String normalizedPosTag = posTag.toUpperCase(); // POS tags in index are uppercase
        String normalizedTerm = (term != null) ? term.toLowerCase() : null; // Terms in index are lowercase

        logger.debug("POS condition details: tag='{}', term='{}', isVariable={}, variableName='{}'",
                     normalizedPosTag, normalizedTerm, isVariable, variableName != null ? variableName : "(none)");

        IndexAccessInterface index = indexes.get(POS_INDEX);
        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        int conceptualRowIdCounter = 0;

        try {
            if (normalizedTerm != null) {
                // Case 1: Specific term is provided (e.g., POS(NN, 'cat'))
                String compositeKey = normalizedPosTag + String.valueOf(IndexAccessInterface.DELIMITER) + normalizedTerm;
                byte[] keyBytes = compositeKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Optional<byte[]> rawBlob = index.getRaw(keyBytes);

            if (rawBlob.isPresent()) {
                    conceptualRowIdCounter = addPositionsToSoA(rawBlob.get(), normalizedTerm, ValueType.POS_TERM,
                                                               isVariable ? variableName : null, requirements, resultSoA, conceptualRowIdCounter);
                } else {
                    logger.debug("POS key '{}' not found in index", compositeKey);
                }
            } else {
                // Case 2: No specific term, iterate over tag (e.g., POS(NN) or POS(NN) BIND ?var)
                String prefix = normalizedPosTag + String.valueOf(IndexAccessInterface.DELIMITER);
                byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                logger.debug("Iterating POS index with prefix: {}", prefix);

                try (RocksIterator iterator = index.seek(prefixBytes)) {
                    while (iterator.isValid()) {
                        byte[] keyBytes = iterator.key();
                        byte[] valueBytes = iterator.value();
                        String currentKey = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);

                        if (!currentKey.startsWith(prefix)) {
                            break;
                        }

                        String extractedTerm = currentKey.substring(prefix.length());
                        if (extractedTerm.isEmpty()) {
                            iterator.next();
                            continue;
                        }

                        conceptualRowIdCounter = addPositionsToSoA(valueBytes, extractedTerm, ValueType.POS_TERM,
                                                                   isVariable ? variableName : null, requirements, resultSoA, conceptualRowIdCounter);
                        iterator.next();
                    }
                }
            }

            logger.debug("POS condition produced {} entries in QueryResultSoA", resultSoA.size());
            return resultSoA;

        } catch (IndexAccessException e) {
             throw new QueryExecutionException("Index access error during POS execution for tag " + normalizedPosTag + (normalizedTerm != null ? " term " + normalizedTerm : ""), e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (IOException e) {
             throw new QueryExecutionException("I/O error during POS execution for tag " + normalizedPosTag + (normalizedTerm != null ? " term " + normalizedTerm : ""), e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        } catch (Exception e) { // Catches any other Exception not handled above (e.g., RuntimeException)
            throw new QueryExecutionException(
                "Unexpected error executing POS condition: " + e.getMessage(),
                e,
                condition.toString(),
                QueryExecutionException.ErrorType.INTERNAL_ERROR
            );
        }
    }

    private int addPositionsToSoA(byte[] rawBlob, String valueString, ValueType valueType,
                                    String variableName, AttributeRequirements requirements,
                                    QueryResultSoA resultSoA, int conceptualRowIdCounter) throws IOException {
        int initialConceptualRowIdCounter = conceptualRowIdCounter;
        try {
            int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob);
            if (numPositions == 0) {
                return initialConceptualRowIdCounter;
            }

            IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
            IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
            IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
            IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
            IntArrayList synonymIds = requirements.needsSynonymIds ? PositionListSoA.decompressSynonymIds(rawBlob) : null;

            for (int i = 0; i < numPositions; i++) {
                resultSoA.add(
                    valueString,
                    valueType,
                    variableName,
                    docIds.getInt(i),
                    sentIds != null ? sentIds.getInt(i) : -1,
                    beginChars != null ? beginChars.getInt(i) : -1,
                    endChars != null ? endChars.getInt(i) : -1,
                    synonymIds != null ? synonymIds.getInt(i) : -1,
                    conceptualRowIdCounter++
                );
            }
        } catch (IOException e) {
            logger.warn("IOException during selective deserialization for POS value '{}', falling back to full deserialization: {}",
                       valueString, e.getMessage());
            PositionListSoA positionListSoA = PositionListSoA.deserializeFromCompositeBlob(rawBlob);
            for (int i = 0; i < positionListSoA.getNumPositions(); i++) {
                resultSoA.add(
                    valueString,
                    valueType,
                    variableName,
                    positionListSoA.getDocIdAt(i),
                    positionListSoA.getSentenceIdAt(i),
                    positionListSoA.getBeginCharAt(i),
                    positionListSoA.getEndCharAt(i),
                    positionListSoA.getSynonymIdAt(i),
                    conceptualRowIdCounter++
                );
            }
        } catch (Exception e) {
             logger.error("Error processing POS entry for value '{}': {}", valueString, e.getMessage(), e);
             // Optionally rethrow or handle more gracefully depending on expected robustness
        }
        return conceptualRowIdCounter;
    }
}