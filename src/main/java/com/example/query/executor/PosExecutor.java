package com.example.query.executor;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
// Legacy PositionListSoA removed in RB-only migration
import com.example.index.presence.RBGroupValueBlob;
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
    private static final String POS_INDEX_NAME = "rb_pos";
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
            // Prefer blob if present
            java.util.Optional<byte[]> rawOpt = posIndex.getRaw(tagFromQuery.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (rawOpt.isPresent()) {
                RBGroupValueBlob blob = RBGroupValueBlob.fromBytes(rawOpt.get());
                if (termFromQuery != null) {
                    logger.debug("POS path (blob): Specific Term. Tag='{}', Term='{}', Var='{}'", tagFromQuery, termFromQuery, variableName);
                    executeSpecificTermSearchFromBlob(tagFromQuery, termFromQuery, variableName, blob, requirements, resultSoA, context);
                } else {
                    logger.debug("POS path (blob): Tag-Only or Variable Term BIND. Tag='{}', Var='{}'", tagFromQuery, variableName);
                    executeTagOnlyOrVariableTermSearchFromBlob(tagFromQuery, variableName, blob, requirements, resultSoA, context);
                }
            } else {
                throw new QueryExecutionException("Missing RBGroupValueBlob for POS tag '" + tagFromQuery + "' (RB-only mode)",
                                                  condition.toString(), QueryExecutionException.ErrorType.MISSING_INDEX);
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

    private void executeSpecificTermSearchFromBlob(String tagFromQuery, String termFromQuery, String variableName,
                                                   RBGroupValueBlob blob, AttributeRequirements requirements,
                                                   QueryResultSoA resultSoA, Optional<FilteringContext> context)
            throws IndexAccessException, org.rocksdb.RocksDBException {

        if (!requirements.needsSynonymIds && variableName == null) {
            logger.warn("executeSpecificTermSearchFromBlob called but needsSynonymIds is false. Filtering may be inefficient or unsupported.");
        }
        int targetSynonymId;
        try { targetSynonymId = synonymManager.getId(termFromQuery.toLowerCase()); }
        catch (Exception e) { return; }

        int added = 0;
        for (var e : blob.getDocBlocks().entrySet()) {
            int docId = e.getKey();
            RBGroupValueBlob.DocBlock block = e.getValue();
            for (int i = 0; i < block.sentIds.length; i++) {
                int sentId = block.sentIds[i];

                if (context.isPresent()) {
                    FilteringContext fc = context.get();
                    if (requirements.needsSentenceId && fc.granularity() == Query.Granularity.SENTENCE) {
                        if (fc.allowedDocumentSentenceIds().isPresent()) {
                            var set = fc.allowedDocumentSentenceIds().get().get(docId);
                            if (set != null && !set.contains(sentId)) continue;
                        }
                    } else if (fc.allowedDocumentIds().isPresent()) {
                        if (!fc.allowedDocumentIds().get().contains(docId)) continue;
                    }
                }

                java.util.List<Integer> vals = block.getValuesForSentenceIndex(i);
                for (int synId : vals) {
                    if (synId != targetSynonymId) continue;
                    resultSoA.add(
                        termFromQuery,
                        ValueType.POS_TERM,
                        variableName,
                        docId,
                        requirements.needsSentenceId ? sentId : -1,
                        -1,
                        -1,
                        synId,
                        resultSoA.getNextConceptualRowId()
                    );
                    added++;
                }
            }
        }
        logger.debug("executeSpecificTermSearchFromBlob: added {} bindings.", added);
    }

    private void executeTagOnlyOrVariableTermSearchFromBlob(String tagFromQuery, String variableName,
                                                            RBGroupValueBlob blob, AttributeRequirements requirements,
                                                            QueryResultSoA resultSoA, Optional<FilteringContext> context)
            throws IndexAccessException, org.rocksdb.RocksDBException, QueryExecutionException {

        int added = 0;
        if (variableName != null) {
            // First pass: collect all synonym IDs that pass context filters
            java.util.Set<Integer> allSynonymIds = new java.util.HashSet<>();
            for (var e : blob.getDocBlocks().entrySet()) {
                int docId = e.getKey();
                RBGroupValueBlob.DocBlock block = e.getValue();
                for (int i = 0; i < block.sentIds.length; i++) {
                    int sentId = block.sentIds[i];
                    if (!passesContextFilter(requirements, context, docId, sentId)) continue;
                    allSynonymIds.addAll(block.getValuesForSentenceIndex(i));
                }
            }
            // Resolve all terms in one batch to satisfy test stubbing and reduce calls
            java.util.Map<Integer, String> resolvedTerms;
            try {
                resolvedTerms = synonymManager.getTerms(allSynonymIds);
            } catch (Exception e) {
                resolvedTerms = java.util.Collections.emptyMap();
            }
            // Second pass: emit rows using resolved terms
            for (var e : blob.getDocBlocks().entrySet()) {
                int docId = e.getKey();
                RBGroupValueBlob.DocBlock block = e.getValue();
                for (int i = 0; i < block.sentIds.length; i++) {
                    int sentId = block.sentIds[i];
                    if (!passesContextFilter(requirements, context, docId, sentId)) continue;
                    for (int synId : block.getValuesForSentenceIndex(i)) {
                        String term = resolvedTerms.get(synId);
                        if (term == null) continue;
                        resultSoA.add(
                            term,
                            ValueType.POS_TERM,
                            variableName,
                            docId,
                            requirements.needsSentenceId ? sentId : -1,
                            -1,
                            -1,
                            synId,
                            resultSoA.getNextConceptualRowId()
                        );
                        added++;
                    }
                }
            }
        } else {
            // Tag-only entries (no term resolution needed)
            for (var e : blob.getDocBlocks().entrySet()) {
                int docId = e.getKey();
                RBGroupValueBlob.DocBlock block = e.getValue();
                for (int i = 0; i < block.sentIds.length; i++) {
                    int sentId = block.sentIds[i];
                    if (!passesContextFilter(requirements, context, docId, sentId)) continue;
                    resultSoA.add(
                        tagFromQuery,
                        ValueType.POS_TAG_TYPE,
                        null,
                        docId,
                        requirements.needsSentenceId ? sentId : -1,
                        -1,
                        -1,
                        -1,
                        resultSoA.getNextConceptualRowId()
                    );
                    added++;
                }
            }
        }
        logger.debug("executeTagOnlyOrVariableTermSearchFromBlob: added {} entries.", added);
    }

    private boolean passesContextFilter(AttributeRequirements requirements, Optional<FilteringContext> context, int docId, int sentId) {
        if (context.isEmpty()) return true;
        FilteringContext fc = context.get();
        if (requirements.needsSentenceId && fc.granularity() == Query.Granularity.SENTENCE) {
            if (fc.allowedDocumentSentenceIds().isPresent()) {
                var set = fc.allowedDocumentSentenceIds().get().get(docId);
                return set == null || set.contains(sentId);
            }
        } else if (fc.allowedDocumentIds().isPresent()) {
            return fc.allowedDocumentIds().get().contains(docId);
        }
        return true;
    }

    // Removed legacy PositionListSoA-based fallbacks (RB-only mode)
}