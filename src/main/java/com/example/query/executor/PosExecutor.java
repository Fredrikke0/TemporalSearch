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
    private static final String ALL_POS_TAGS_WILDCARD = "*";
    private static final String POS_INDEX_NAME = "pos";

    /**
     * Creates a new POS executor.
     */
    public PosExecutor() {
        // No initialization required
    }

    @Override
    public QueryResultSoA execute(Pos condition, Map<String, IndexAccessInterface> indexes, Query.Granularity granularity,
                                 int granularitySize, String corpusName, AttributeRequirements requirements)
            throws QueryExecutionException {
        logger.debug("Executing POS condition: {}, AttrReqs: {}", condition, requirements);

        IndexAccessInterface posIndex = indexes.get(POS_INDEX_NAME);
        if (posIndex == null) {
            throw new QueryExecutionException("POS index not found in provided indexes.", condition.toString(), QueryExecutionException.ErrorType.MISSING_INDEX);
        }

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        int currentConceptualRowId = -1;

        String tag = condition.posTag();
        String term = condition.term();
        String variableName = condition.variableName();
        boolean isVariable = variableName != null;

        logger.debug("POS condition details: tag='{}', term='{}', isVariable={}, variableName='{}'",
                     tag, term, isVariable, variableName != null ? variableName : "(none)");

        if (ALL_POS_TAGS_WILDCARD.equals(tag)) {
            throw new QueryExecutionException(
                "Wildcard POS tag (*) is not supported for direct execution. Use specific tags or variable binding for tags.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION
            );
        }

        try {
            if (term != null) {
                currentConceptualRowId = resultSoA.getNextConceptualRowId();
                logger.debug("POS path: Specific Term Search. Tag='{}', Term='{}'. ConceptualRowId={}", tag, term, currentConceptualRowId);
                executeSpecificTermSearch(tag, term, variableName, posIndex, condition, requirements, resultSoA, currentConceptualRowId);
            } else {
                int conceptualRowIdForThisTagCondition = -1;
                if (variableName == null) {
                    conceptualRowIdForThisTagCondition = resultSoA.getNextConceptualRowId();
                }
                logger.debug("POS path: Tag-Only/Variable Term Search. Tag='{}'. Initial ConceptualRowId (if not binding var to term)={}", tag, conceptualRowIdForThisTagCondition);
                executeTagOnlyOrVariableTermSearch(tag, variableName, posIndex, condition, requirements, resultSoA, conceptualRowIdForThisTagCondition);
            }
        } catch (IOException e) {
            logger.error("IOException during POS condition execution: {}", e.getMessage(), e);
            throw new QueryExecutionException("Unexpected IO error executing POS condition: " + e.getMessage(),
                                            e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        } catch (IndexAccessException e) {
            logger.error("IndexAccessException during POS condition execution: {}", e.getMessage(), e);
            throw new QueryExecutionException("Unexpected index access error executing POS condition: " + e.getMessage(),
                                            e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }

        logger.debug("POS condition execution produced {} conceptual rows, total SoA size: {}",
                     resultSoA.getConceptualRowCount(), resultSoA.size());
        return resultSoA;
    }

    private void executeSpecificTermSearch(String tag, String term, String variableName, IndexAccessInterface index,
                                           Pos condition, AttributeRequirements requirements, QueryResultSoA resultSoA, int conceptualRowId)
            throws IOException, IndexAccessException {

        String keyString = tag.toUpperCase() + IndexAccessInterface.DELIMITER + term.toLowerCase();
        byte[] keyBytes = keyString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        logger.debug("executeSpecificTermSearch: Seeking key '{}' in index", keyString);

        Optional<byte[]> optBlob = index.getRaw(keyBytes);

        if (optBlob.isPresent()) {
            byte[] blob = optBlob.get();
            logger.debug("executeSpecificTermSearch: Blob found for key '{}'. Blob size: {}. Deserializing...", keyString, blob.length);
            PositionListSoA positions = PositionListSoA.deserializeFromCompositeBlob(blob);
            logger.debug("executeSpecificTermSearch: Deserialized {} positions for key '{}'", positions.getNumPositions(), keyString);
            for (int i = 0; i < positions.getNumPositions(); i++) {
                resultSoA.add(term, ValueType.POS_TERM, variableName,
                              positions.getDocIdAt(i),
                              requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                              requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                              requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                              requirements.needsSynonymIds ? positions.getSynonymIdAt(i) : -1,
                              conceptualRowId);
            }
            logger.debug("executeSpecificTermSearch: Added {} bindings for key '{}' to QueryResultSoA", positions.getNumPositions(), keyString);
        } else {
            logger.debug("executeSpecificTermSearch: No blob found for key '{}'", keyString);
        }
    }

    private void executeTagOnlyOrVariableTermSearch(String tag, String variableName, IndexAccessInterface index,
                                                    Pos condition, AttributeRequirements requirements, QueryResultSoA resultSoA,
                                                    int conceptualRowIdForTagGroup)
            throws IOException, IndexAccessException {

        String prefixString = tag.toUpperCase() + IndexAccessInterface.DELIMITER;
        byte[] prefixBytes = prefixString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        logger.debug("executeTagOnlyOrVariableTermSearch: Seeking prefix '{}' in index", prefixString);

        try (RocksIterator iterator = index.seek(prefixBytes)) {
            logger.debug("executeTagOnlyOrVariableTermSearch: Iterator obtained from seek(prefix). Initial isValid: {}", iterator.isValid());

            while (iterator.isValid()) {
                logger.trace("executeTagOnlyOrVariableTermSearch: Loop start. Iterator isValid: true");
                byte[] currentKeyBytes = iterator.key();
                String currentKeyString = new String(currentKeyBytes, java.nio.charset.StandardCharsets.UTF_8);
                logger.trace("executeTagOnlyOrVariableTermSearch: Current key: '{}'", currentKeyString);

                if (!currentKeyString.startsWith(prefixString)) {
                    logger.debug("executeTagOnlyOrVariableTermSearch: Key '{}' does not match prefix '{}'. Breaking loop.", currentKeyString, prefixString);
                    break;
                }

                byte[] blob = iterator.value();
                logger.trace("executeTagOnlyOrVariableTermSearch: Blob for key '{}'. Blob size: {}. Deserializing...", currentKeyString, blob.length);
                PositionListSoA positions = PositionListSoA.deserializeFromCompositeBlob(blob);
                logger.trace("executeTagOnlyOrVariableTermSearch: Deserialized {} positions for key '{}'", positions.getNumPositions(), currentKeyString);

                String termValue = extractTermFromKey(currentKeyString, prefixString);

                int conceptualIdForThisTermEntry;
                if (variableName != null) {
                    conceptualIdForThisTermEntry = resultSoA.getNextConceptualRowId();
                    logger.trace("executeTagOnlyOrVariableTermSearch: Binding variable '{}', new conceptualRowId {} for term '{}'", variableName, conceptualIdForThisTermEntry, termValue);
                } else {
                    conceptualIdForThisTermEntry = conceptualRowIdForTagGroup;
                    logger.trace("executeTagOnlyOrVariableTermSearch: Not binding variable, using conceptualRowId {} for term '{}'", conceptualIdForThisTermEntry, termValue);
                }

                for (int i = 0; i < positions.getNumPositions(); i++) {
                    resultSoA.add(termValue, ValueType.POS_TERM, variableName,
                                  positions.getDocIdAt(i),
                                  requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                                  requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                                  requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                                  requirements.needsSynonymIds ? positions.getSynonymIdAt(i) : -1,
                                  conceptualIdForThisTermEntry);
                }
                logger.trace("executeTagOnlyOrVariableTermSearch: Added {} bindings for key '{}' to QueryResultSoA", positions.getNumPositions(), currentKeyString);
                logger.trace("executeTagOnlyOrVariableTermSearch: Calling iterator.next()");
                iterator.next();
                logger.trace("executeTagOnlyOrVariableTermSearch: After iterator.next(), isValid: {}", iterator.isValid());
            }
            logger.debug("executeTagOnlyOrVariableTermSearch: Loop finished. Iterator isValid: {}", iterator.isValid());
        }
    }

    private String extractTermFromKey(String key, String prefix) {
        if (key.startsWith(prefix)) {
            return key.substring(prefix.length());
        }
        // This case should ideally not be reached if the iterator logic is correct
        logger.warn("Key '{}' did not start with expected prefix '{}' during term extraction.", key, prefix);
        return ""; // Or throw an exception
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