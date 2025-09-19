package com.example.query.executor;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.rocksdb.RocksIterator;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Pos;

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
    private static final String POS_PRESENCE_INDEX_NAME = "pos_presence";
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
                                 int granularitySize, String corpusName, AttributeRequirements requirements,
                                 Optional<FilteringContext> context)
            throws QueryExecutionException {
        logger.debug(">>> Executing PosExecutor");
        logger.debug("Executing POS condition: {}, AttrReqs: {}, ContextIsPresent: {}",
                     condition, requirements, context.isPresent());

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
                executeSpecificTermSearch(tagFromQuery, termFromQuery, variableName, posIndex, requirements, resultSoA, context);
            } else {
                logger.debug("POS path: Tag-Only or Variable Binding to Term. Tag='{}', VarName='{}'", tagFromQuery, variableName);
                IndexAccessInterface presenceIndex = indexes.get(POS_PRESENCE_INDEX_NAME);
                if (presenceIndex != null && variableName != null) {
                    executeVariableBindingViaPresence(tagFromQuery, variableName, presenceIndex, resultSoA, context);
                } else {
                    executeTagOnlyOrVariableTermSearch(tagFromQuery, variableName, posIndex, requirements, resultSoA, context);
                }
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

        // Sort by document ID to ensure merge join optimization works correctly
        resultSoA.sort();

        return resultSoA;
    }

    private void executeVariableBindingViaPresence(String tagFromQuery,
                                                   String variableName,
                                                   IndexAccessInterface presenceIndex,
                                                   QueryResultSoA resultSoA,
                                                   Optional<FilteringContext> context)
            throws IndexAccessException, IOException, org.rocksdb.RocksDBException {
        logger.info("POS presence: using pos_presence for ANY TAG + BIND (tag='{}', var='{}')", tagFromQuery, variableName);
        String prefix = tagFromQuery + IndexAccessInterface.DELIMITER;
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] upperBound = java.util.Arrays.copyOf(prefixBytes, prefixBytes.length + 1);
        upperBound[upperBound.length - 1] = (byte) 0xFF;

        AttributeRequirements req = new AttributeRequirements();
        req.needsSentenceId = true;
        req.needsPositions = false;
        req.needsSynonymIds = true;

        try (RocksIterator iterator = presenceIndex.seekWithBounds(prefixBytes, upperBound, 256 * 1024)) {
            ExecutorIndexUtils.iterateGroupedByBase(iterator, prefix, (baseKey, blobs) -> {
                Optional<PositionListSoA> mergedOpt = ExecutorIndexUtils.mergeAndFilter(blobs, context, req);
                if (!mergedOpt.isPresent() || mergedOpt.get().isEmpty()) return;
                PositionListSoA pl = mergedOpt.get();
                int delimIdx = baseKey.lastIndexOf(IndexAccessInterface.DELIMITER);
                if (delimIdx <= 0 || delimIdx == baseKey.length() - 1) return;
                int docId;
                try { docId = Integer.parseInt(baseKey.substring(delimIdx + 1)); } catch (NumberFormatException nfe) { return; }

                for (int i = 0; i < pl.getNumPositions(); i++) {
                    int sentId = pl.getSentenceIdAt(i);
                    int synId = pl.getSynonymIdAt(i);
                    String termToBind;
                    try {
                        termToBind = synonymManager.getTerm(synId).orElse("");
                    } catch (org.rocksdb.RocksDBException e) {
                        termToBind = "";
                    }
                    resultSoA.add(
                        termToBind,
                        ValueType.POS_TERM,
                        variableName,
                        docId,
                        sentId,
                        -1,
                        -1,
                        synId,
                        resultSoA.getNextConceptualRowId()
                    );
                }
            });
        }
    }

    private void executeSpecificTermSearch(String tagFromQuery, String termFromQuery, String variableName, IndexAccessInterface index,
                                           AttributeRequirements requirements, QueryResultSoA resultSoA,
                                           Optional<FilteringContext> context)
            throws IOException, IndexAccessException, org.rocksdb.RocksDBException {

        if (!requirements.needsSynonymIds && variableName == null) {
            logger.warn("executeSpecificTermSearch called for Tag='{}', Term='{}' but AttributeRequirements.needsSynonymIds is false. Efficient filtering by synonym ID is not possible.",
                        tagFromQuery, termFromQuery);
        }

        String normalizedTargetTerm = termFromQuery.toLowerCase();
        int targetSynonymId = synonymManager.getId(normalizedTargetTerm);

        logger.debug("executeSpecificTermSearch (value-keyed): Tag='{}', Term='{}' -> synId={}",
            tagFromQuery, termFromQuery, targetSynonymId);

        String key = tagFromQuery + IndexAccessInterface.DELIMITER + targetSynonymId;
        Optional<PositionListSoA> positionsOptional = index.getMergedPositions(key, context, requirements);

        if (!positionsOptional.isPresent() || positionsOptional.get().isEmpty()) {
            logger.debug("executeSpecificTermSearch: No data found for POS key '{}' after getMergedPositions (with context filtering)", key);
            return;
        }

        PositionListSoA positions = positionsOptional.get();

        if (positions.isEmpty()) {
            logger.debug("executeSpecificTermSearch: No positions for POS key '{}' after context filtering.", key);
            return;
        }

        int numPositionsTotal = positions.getNumPositions();
        if (numPositionsTotal == 0) return;

        for (int i = 0; i < numPositionsTotal; i++) {
            resultSoA.add(
                termFromQuery,
                ValueType.POS_TERM,
                variableName,
                positions.getDocIdAt(i),
                requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                targetSynonymId,
                resultSoA.getNextConceptualRowId()
            );
        }
        logger.debug("executeSpecificTermSearch (value-keyed) for Tag '{}', synId '{}' added {} positions.",
            tagFromQuery, targetSynonymId, numPositionsTotal);
    }

    private void executeTagOnlyOrVariableTermSearch(String tagFromQuery, String variableName, IndexAccessInterface index,
                                                    AttributeRequirements requirements, QueryResultSoA resultSoA,
                                                    Optional<FilteringContext> context)
            throws IOException, IndexAccessException, org.rocksdb.RocksDBException, QueryExecutionException {

        String prefix = tagFromQuery + IndexAccessInterface.DELIMITER;
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] upperBound = java.util.Arrays.copyOf(prefixBytes, prefixBytes.length + 1);
        upperBound[upperBound.length - 1] = (byte) 0xFF;

        try (RocksIterator iterator = index.seekWithBounds(prefixBytes, upperBound, 256 * 1024)) {
            ExecutorIndexUtils.iterateGroupedByBase(iterator, prefix, (baseKey, blobs) -> {
                // baseKey is TAG\0<synId>
                int delimIdx = baseKey.lastIndexOf(IndexAccessInterface.DELIMITER);
                if (delimIdx <= 0 || delimIdx == baseKey.length() - 1) return;
                String synIdStr = baseKey.substring(delimIdx + 1);
                int synId;
                try { synId = Integer.parseInt(synIdStr); } catch (NumberFormatException nfe) { return; }

                Optional<PositionListSoA> mergedOpt = ExecutorIndexUtils.mergeAndFilter(blobs, context, requirements);
                if (!mergedOpt.isPresent() || mergedOpt.get().isEmpty()) return;
                PositionListSoA positions = mergedOpt.get();

                if (variableName != null) {
                    String termToBind = null;
                    try {
                        termToBind = synonymManager.getTerm(synId).orElse(null);
                    } catch (org.rocksdb.RocksDBException e) {
                        termToBind = null;
                    }
                    if (termToBind == null) termToBind = "";

                    for (int i = 0; i < positions.getNumPositions(); i++) {
                        resultSoA.add(
                            termToBind,
                            ValueType.POS_TERM,
                            variableName,
                            positions.getDocIdAt(i),
                            requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                            requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                            requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                            synId,
                            resultSoA.getNextConceptualRowId()
                        );
                    }
                } else {
                    // Tag-only: value is the tag itself
                    for (int i = 0; i < positions.getNumPositions(); i++) {
                        resultSoA.add(
                            tagFromQuery,
                            ValueType.POS_TAG_TYPE,
                            null,
                            positions.getDocIdAt(i),
                            requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                            requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                            requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                            -1,
                            resultSoA.getNextConceptualRowId()
                        );
                    }
                }
            });
        }
    }
}