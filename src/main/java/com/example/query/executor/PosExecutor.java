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
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Pos;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Executor for POS conditions.
 * Handles matching POS tags against indexed data.
 *
 * POS Condition Logic:
 *   - Simple match: POS(tag) -> Finds sentences containing the specified tag. Value stored is the tag itself (e.g. "NN") with ValueType.POS_TAG_TYPE.
 *   - Match with term: POS(tag, term) -> Finds sentences where 'term' has the specified tag. Value stored is 'term' (e.g. "apple") with ValueType.POS_TERM.
 *   - Variable binding: POS(tag) BIND var -> Binds var to the 'term' (e.g. "apple") that has the tag. ValueType.POS_TERM.
 *   - Variable consumption: POS(tag, var) -> Filters sentences where 'var' (must be text) has the tag.
 */
public final class PosExecutor implements ConditionExecutor<Pos> {
    private static final Logger logger = LoggerFactory.getLogger(PosExecutor.class);
    private static final String ALL_POS_TAGS_WILDCARD = "*";
    private static final String POS_INDEX_NAME = "pos";
    private final SynonymManager synonymManager;

    /**
     * Creates a new POS executor.
     * @param synonymManager The synonym manager instance.
     */
    public PosExecutor(SynonymManager synonymManager) {
        this.synonymManager = synonymManager;
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

        String tagFromQuery = condition.posTag().toUpperCase();
        String termFromQuery = condition.term();
        String variableName = condition.variableName();

        logger.debug("POS condition details: tag='{}', term='{}', isVariable={}, variableName='{}'",
                     tagFromQuery, termFromQuery, (variableName != null), variableName != null ? variableName : "(none)");

        if (ALL_POS_TAGS_WILDCARD.equals(tagFromQuery)) {
            throw new QueryExecutionException(
                "Wildcard POS tag (*) is not supported for direct execution. Use specific tags or variable binding for tags.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION
            );
        }

        try {
            if (termFromQuery != null) {
                logger.debug("POS path: Specific Term Search. Tag='{}', TermFromQuery='{}'", tagFromQuery, termFromQuery);
                executeSpecificTermSearch(tagFromQuery, termFromQuery, variableName, posIndex, requirements, resultSoA);
            } else {
                logger.debug("POS path: Tag-Only or Variable Binding to Term. Tag='{}'", tagFromQuery);
                executeTagOnlyOrVariableTermSearch(tagFromQuery, variableName, posIndex, requirements, resultSoA);
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

    private void executeSpecificTermSearch(String tagFromQuery, String termFromQuery, String variableName, IndexAccessInterface index,
                                           AttributeRequirements requirements, QueryResultSoA resultSoA)
            throws IOException, IndexAccessException {

        String keyString = tagFromQuery + IndexAccessInterface.DELIMITER + termFromQuery.toLowerCase();
        byte[] keyBytes = keyString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        logger.debug("executeSpecificTermSearch: Seeking key '{}' in index", keyString);

        Optional<byte[]> optBlob = index.getRaw(keyBytes);

        if (optBlob.isPresent()) {
            byte[] blob = optBlob.get();
            logger.debug("executeSpecificTermSearch: Blob found for key '{}'. Blob size: {}. Deserializing...", keyString, blob.length);

            int conceptualRowIdForThisMatch = resultSoA.getNextConceptualRowId();
            int numPositions = PositionListSoA.getNumPositionsFromBlob(blob);
            if (numPositions > 0) {
                IntArrayList docIds = PositionListSoA.decompressDocIds(blob);
                IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(blob) : null;
                IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(blob) : null;
                IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(blob) : null;
                IntArrayList synonymIds = requirements.needsSynonymIds ? PositionListSoA.decompressSynonymIds(blob) : null;

                for (int i = 0; i < numPositions; i++) {
                    resultSoA.add(termFromQuery, ValueType.POS_TERM, variableName,
                                  docIds.getInt(i),
                                  sentIds != null ? sentIds.getInt(i) : -1,
                                  beginChars != null ? beginChars.getInt(i) : -1,
                                  endChars != null ? endChars.getInt(i) : -1,
                                  synonymIds != null ? synonymIds.getInt(i) : -1,
                                  conceptualRowIdForThisMatch);
                }
                logger.debug("executeSpecificTermSearch: Added {} bindings for key '{}' to QueryResultSoA with optimized deserialization", numPositions, keyString);
            } else {
                 logger.debug("executeSpecificTermSearch: numPositions is 0 from blob for key '{}'", keyString);
            }
        } else {
            logger.debug("executeSpecificTermSearch: No blob found for key '{}'", keyString);
        }
    }

    private void executeTagOnlyOrVariableTermSearch(String tagFromQuery, String variableName, IndexAccessInterface index,
                                                    AttributeRequirements requirements, QueryResultSoA resultSoA)
            throws IOException, IndexAccessException {

        String prefixString = tagFromQuery + IndexAccessInterface.DELIMITER;
        byte[] prefixBytes = prefixString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        logger.debug("executeTagOnlyOrVariableTermSearch: Seeking prefix '{}' in index for tag '{}'", prefixString, tagFromQuery);

        try (RocksIterator iterator = index.seek(prefixBytes)) {
            logger.debug("executeTagOnlyOrVariableTermSearch: Iterator obtained. Initial isValid: {}", iterator.isValid());

            int conceptualRowIdForTagItself = (variableName == null) ? resultSoA.getNextConceptualRowId() : -1;

            while (iterator.isValid()) {
                byte[] currentKeyBytes = iterator.key();
                String currentKeyString = new String(currentKeyBytes, java.nio.charset.StandardCharsets.UTF_8);

                if (!currentKeyString.startsWith(prefixString)) {
                    logger.debug("executeTagOnlyOrVariableTermSearch: Key '{}' does not match prefix '{}'. Breaking loop.", currentKeyString, prefixString);
                    break;
                }

                byte[] blob = iterator.value();
                String termValueFromIndexKey = extractTermFromKey(currentKeyString, prefixString);
                logger.trace("executeTagOnlyOrVariableTermSearch: Processing key '{}', extracted term '{}'", currentKeyString, termValueFromIndexKey);

                int conceptualIdForThisEntry;
                String valueToStoreInSoa;
                ValueType typeToStoreInSoa;

                if (variableName != null) {
                    conceptualIdForThisEntry = resultSoA.getNextConceptualRowId();
                    valueToStoreInSoa = termValueFromIndexKey;
                    typeToStoreInSoa = ValueType.POS_TERM;
                    logger.trace("Binding variable '{}' to term '{}'. ConceptualRowId={}", variableName, valueToStoreInSoa, conceptualIdForThisEntry);
                } else {
                    conceptualIdForThisEntry = conceptualRowIdForTagItself;
                    valueToStoreInSoa = tagFromQuery;
                    typeToStoreInSoa = ValueType.POS_TAG_TYPE;
                    logger.trace("Matching tag '{}'. ConceptualRowId={}", valueToStoreInSoa, conceptualIdForThisEntry);
                }

                int numPositions = PositionListSoA.getNumPositionsFromBlob(blob);
                if (numPositions > 0) {
                    IntArrayList docIds = PositionListSoA.decompressDocIds(blob);
                    IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(blob) : null;
                    IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(blob) : null;
                    IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(blob) : null;
                    IntArrayList synonymIds = requirements.needsSynonymIds ? PositionListSoA.decompressSynonymIds(blob) : null;

                    for (int i = 0; i < numPositions; i++) {
                        resultSoA.add(valueToStoreInSoa, typeToStoreInSoa, variableName,
                                      docIds.getInt(i),
                                      sentIds != null ? sentIds.getInt(i) : -1,
                                      beginChars != null ? beginChars.getInt(i) : -1,
                                      endChars != null ? endChars.getInt(i) : -1,
                                      synonymIds != null ? synonymIds.getInt(i) : -1,
                                      conceptualIdForThisEntry);
                    }
                    logger.trace("Added {} bindings for key '{}' (term '{}') to QueryResultSoA with optimized deserialization", numPositions, currentKeyString, termValueFromIndexKey);
                } else {
                    logger.trace("numPositions is 0 from blob for key '{}'", currentKeyString);
                }
                iterator.next();
            }
            logger.debug("executeTagOnlyOrVariableTermSearch: Loop finished. Iterator isValid: {}", iterator.isValid());
        }
    }

    private String extractTermFromKey(String key, String prefix) {
        if (key.startsWith(prefix)) {
            return key.substring(prefix.length());
        }
        logger.warn("Key '{}' did not start with expected prefix '{}' during term extraction.", key, prefix);
        return "";
    }
}