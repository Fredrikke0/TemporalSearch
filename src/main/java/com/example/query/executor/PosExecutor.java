package com.example.query.executor;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

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
        if (termFromQuery != null && termFromQuery.contains("*")) {
            throw new QueryExecutionException(
                "Wildcard in target term ('" + termFromQuery + "') for POS condition is not supported.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }

        try {
            if (termFromQuery != null) {
                logger.debug("POS path: Specific Term Search. Tag='{}', TermFromQuery='{}', VarName='{}'", tagFromQuery, termFromQuery, variableName);
                executeSpecificTermSearch(tagFromQuery, termFromQuery, variableName, posIndex, requirements, resultSoA);
            } else {
                logger.debug("POS path: Tag-Only or Variable Binding to Term. Tag='{}', VarName='{}'", tagFromQuery, variableName);
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
        } catch (org.rocksdb.RocksDBException rde) {
            logger.error("RocksDBException during POS condition execution: {}", rde.getMessage(), rde);
            throw new QueryExecutionException("RocksDB error executing POS condition: " + rde.getMessage(),
                                            rde, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }

        logger.debug("POS condition execution produced {} conceptual rows, total SoA size: {}",
                     resultSoA.getConceptualRowCount(), resultSoA.size());
        return resultSoA;
    }

    private void executeSpecificTermSearch(String tagFromQuery, String termFromQuery, String variableName, IndexAccessInterface index,
                                           AttributeRequirements requirements, QueryResultSoA resultSoA)
            throws IOException, IndexAccessException, org.rocksdb.RocksDBException {

        if (!requirements.needsSynonymIds && variableName == null) {
            logger.warn("executeSpecificTermSearch called for Tag='{}', Term='{}' but AttributeRequirements.needsSynonymIds is false. Efficient filtering by synonym ID is not possible.",
                        tagFromQuery, termFromQuery);
        }

        String normalizedTargetTerm = termFromQuery.toLowerCase();
        int targetSynonymId = synonymManager.getId(normalizedTargetTerm);

        logger.debug("executeSpecificTermSearch: Tag='{}', TermValue='{}' (original), NormalizedTerm='{}', TargetSynonymID={}",
            tagFromQuery, termFromQuery, normalizedTargetTerm, targetSynonymId);

        byte[] keyForIndexLookup = tagFromQuery.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Optional<byte[]> rawBlobOptional = index.getRaw(keyForIndexLookup);

        if (!rawBlobOptional.isPresent() || rawBlobOptional.get().length == 0) {
            logger.debug("executeSpecificTermSearch: No data found for POS tag '{}'", tagFromQuery);
            return;
        }

        byte[] rawBlob = rawBlobOptional.get();
        int numPositionsTotal = PositionListSoA.getNumPositionsFromBlob(rawBlob);
        if (numPositionsTotal == 0) return;

        IntArrayList synonymIds = PositionListSoA.decompressSynonymIds(rawBlob);
        if (synonymIds == null || synonymIds.isEmpty()) {
             logger.warn("executeSpecificTermSearch: No synonym IDs found in blob for tag '{}' despite {} positions. Inconsistent data?", tagFromQuery, numPositionsTotal);
             return;
        }

        IntArrayList docIds = null;
        IntArrayList sentIds = null;
        IntArrayList beginChars = null;
        IntArrayList endChars = null;

        int conceptualRowId = -1;
        int positionsAddedToSoa = 0;

        for (int i = 0; i < numPositionsTotal; i++) {
            if (synonymIds.getInt(i) == targetSynonymId) {
                if (conceptualRowId == -1) {
                    conceptualRowId = resultSoA.getNextConceptualRowId();
                    docIds = PositionListSoA.decompressDocIds(rawBlob);
                    sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
                    beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
                    endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
                }
                resultSoA.add(
                    termFromQuery,
                    ValueType.POS_TERM,
                    variableName,
                    docIds.getInt(i),
                    sentIds != null ? sentIds.getInt(i) : -1,
                    beginChars != null ? beginChars.getInt(i) : -1,
                    endChars != null ? endChars.getInt(i) : -1,
                    targetSynonymId,
                    conceptualRowId
                );
                positionsAddedToSoa++;
            }
        }
        logger.debug("executeSpecificTermSearch for Tag '{}', Term '{}' added {} positions to QueryResultSoA under conceptualRowId {}",
            tagFromQuery, termFromQuery, positionsAddedToSoa, conceptualRowId != -1 ? conceptualRowId : "(none)");
    }

    private void executeTagOnlyOrVariableTermSearch(String tagFromQuery, String variableName, IndexAccessInterface index,
                                                    AttributeRequirements requirements, QueryResultSoA resultSoA)
            throws IOException, IndexAccessException, org.rocksdb.RocksDBException {

        byte[] keyForIndexLookup = tagFromQuery.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Optional<byte[]> rawBlobOptional = index.getRaw(keyForIndexLookup);

        if (!rawBlobOptional.isPresent() || rawBlobOptional.get().length == 0) {
            logger.debug("executeTagOnlyOrVariableTermSearch: No data found for POS tag '{}'", tagFromQuery);
            return;
        }

        byte[] rawBlob = rawBlobOptional.get();
        int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob);
        logger.debug("executeTagOnlyOrVariableTermSearch: Blob found for '{}'. numPositions from blob: {}", tagFromQuery, numPositions);

        if (numPositions == 0) {
            logger.debug("executeTagOnlyOrVariableTermSearch: numPositions is 0 for POS tag '{}', returning.", tagFromQuery);
            return;
        }

        IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
        IntArrayList sentenceIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
        IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
        IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
        IntArrayList synonymIdsFromBlob = PositionListSoA.decompressSynonymIds(rawBlob);

        if (variableName != null) {
            Map<String, Integer> resolvedTermToConceptualRowId = new java.util.HashMap<>();
            Map<Integer, String> resolvedSynonymIdToTermCache = new java.util.HashMap<>();

            for (int i = 0; i < numPositions; i++) {
                int currentSynonymId = synonymIdsFromBlob.getInt(i);
                String termToBind;

                if (resolvedSynonymIdToTermCache.containsKey(currentSynonymId)) {
                    termToBind = resolvedSynonymIdToTermCache.get(currentSynonymId);
                } else {
                    Optional<String> termOptional = synonymManager.getTerm(currentSynonymId);
                    if (!termOptional.isPresent()) {
                        logger.warn("executeTagOnlyOrVariableTermSearch: No term found for synonymId {} (tag: {}). Skipping.", currentSynonymId, tagFromQuery);
                        continue;
                    }
                    termToBind = termOptional.get();
                    resolvedSynonymIdToTermCache.put(currentSynonymId, termToBind);
                }

                int conceptualRowId = resolvedTermToConceptualRowId.computeIfAbsent(termToBind, k -> resultSoA.getNextConceptualRowId());

                resultSoA.add(
                    termToBind,
                    ValueType.POS_TERM,
                    variableName,
                    docIds.getInt(i),
                    sentenceIds != null ? sentenceIds.getInt(i) : -1,
                    beginChars != null ? beginChars.getInt(i) : -1,
                    endChars != null ? endChars.getInt(i) : -1,
                    currentSynonymId,
                    conceptualRowId
                );
            }
            logger.debug("executeTagOnlyOrVariableTermSearch for Tag '{}' BIND '{}' added {} conceptual rows and {} total bindings.",
                tagFromQuery, variableName, resolvedTermToConceptualRowId.size(), resultSoA.size());

        } else {
            int conceptualRowIdForTag = resultSoA.getNextConceptualRowId();
            int positionsAddedToSoa = 0;
            for (int i = 0; i < numPositions; i++) {
                resultSoA.add(
                    tagFromQuery,
                    ValueType.POS_TAG_TYPE,
                    null,
                    docIds.getInt(i),
                    sentenceIds != null ? sentenceIds.getInt(i) : -1,
                    beginChars != null ? beginChars.getInt(i) : -1,
                    endChars != null ? endChars.getInt(i) : -1,
                    -1,
                    conceptualRowIdForTag
                );
                positionsAddedToSoa++;
            }
            logger.debug("executeTagOnlyOrVariableTermSearch for Tag '{}' (no BIND) added {} positions to QueryResultSoA under conceptualRowId {}",
                tagFromQuery, positionsAddedToSoa, conceptualRowIdForTag);
        }
    }
}