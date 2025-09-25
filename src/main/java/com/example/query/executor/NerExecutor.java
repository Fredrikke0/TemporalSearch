package com.example.query.executor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.index.presence.RBGroupValueBlob;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Ner;

/**
 * Executor for NER (Named Entity Recognition) conditions, excluding DATE.
 * Handles entity type matching and variable binding for named entities.
 */
public final class NerExecutor implements ConditionExecutor<Ner> {
    private static final Logger logger = LoggerFactory.getLogger(NerExecutor.class);

    private static final String NER_INDEX_NAME = "rb_ner";
    private final SynonymManager synonymManager;

    public NerExecutor(SynonymManager synonymManager) {
        this.synonymManager = synonymManager;
    }

    @Override
    public QueryResultSoA execute(Ner condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements,
                               Optional<FilteringContext> context)
        throws QueryExecutionException {
        logger.debug(">>> Executing NerExecutor");
        logger.debug("Executing NER condition: {}, AttrReqs: {}, ContextIsPresent: {}",
                     condition, requirements, context.isPresent());

        String entityType = condition.entityType();
        String normalizedEntityType = entityType.toUpperCase();

        if ("DATE".equals(normalizedEntityType)) {
            throw new QueryExecutionException(
                "NER(DATE) queries should be handled by TemporalExecutor, not NerExecutor.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION
            );
        }

        List<String> targetValues = condition.targets();
        if (targetValues == null) targetValues = java.util.Collections.emptyList();
        boolean isVariable = condition.isVariable();
        String variableName = condition.qualifiedVariableName();

        logger.debug("NER condition details: entityType='{}', targets={}, isVariable={}, variableName='{}'",
                     normalizedEntityType, targetValues, isVariable, variableName != null ? variableName : "(none)");

        if (normalizedEntityType.contains("*")) {
            throw new QueryExecutionException(
                "Wildcard in entity type ('" + entityType + "') is not supported.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }

        for (String target : targetValues) {
            if (target != null && target.contains("*")) {
                throw new QueryExecutionException(
                    "Wildcard in target value ('" + target + "') is not supported.",
                    condition.toString(),
                    QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
            }
        }

        IndexAccessInterface index = indexes.get(NER_INDEX_NAME);
        if (index == null) {
            throw new QueryExecutionException(
                "Missing required NER index: " + NER_INDEX_NAME,
                condition.toString(),
                QueryExecutionException.ErrorType.MISSING_INDEX);
        }

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        int conceptualRowsAdded = 0;

        try {
            // Require RBGroupValueBlob (value blocks)
            Optional<byte[]> rawOpt = index.getRaw(normalizedEntityType.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (rawOpt.isPresent()) {
                try {
                    RBGroupValueBlob blob = RBGroupValueBlob.fromBytes(rawOpt.get());
                    if (isVariable || (!targetValues.isEmpty())) {
                        conceptualRowsAdded = executeWithTargetsOrBindFromBlob(normalizedEntityType, targetValues, variableName, blob, requirements, resultSoA, context);
            } else {
                        conceptualRowsAdded = executeTypeOnlyFromBlob(normalizedEntityType, blob, requirements, resultSoA, context);
                    }
                } catch (IOException parseEx) {
                    throw new QueryExecutionException("Failed to parse RBGroupValueBlob for '" + normalizedEntityType + "': " + parseEx.getMessage(), parseEx, normalizedEntityType, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
                }
            } else {
                throw new QueryExecutionException(
                    "Missing RBGroupValueBlob for NER type '" + normalizedEntityType + "'",
                    condition.toString(),
                    QueryExecutionException.ErrorType.MISSING_INDEX);
            }
            logger.debug("NER condition execution produced {} conceptual result rows, total SoA size: {}", conceptualRowsAdded, resultSoA.size());

            resultSoA.sort();

            return resultSoA;

        } catch (IndexAccessException e) {
            throw new QueryExecutionException("Error accessing NER index: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (Exception e) {
            if (e instanceof QueryExecutionException qee) {
                throw qee;
            }
            throw new QueryExecutionException("Unexpected error executing NER condition: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }
    }

    private int executeTypeOnlyFromBlob(String normalizedEntityType,
                                         RBGroupValueBlob blob,
                                         AttributeRequirements requirements,
                                         QueryResultSoA resultSoA,
                                         Optional<FilteringContext> context) {
        int added = 0;
        org.roaringbitmap.longlong.LongIterator it = blob.getPresenceIndex().getBitmap().getLongIterator();
        while (it.hasNext()) {
            long pair = it.next();
            int docId = (int)(pair >>> 16);
            int sentId = (int)(pair & 0xFFFFL);
            if (context.isPresent()) {
                FilteringContext fc = context.get();
                if (requirements.needsSentenceId && fc.granularity() == Query.Granularity.SENTENCE) {
                    if (fc.allowedDocumentSentenceIds().isPresent()) {
                        var allowed = fc.allowedDocumentSentenceIds().get();
                        var set = allowed.get(docId);
                        if (set != null && !set.contains(sentId)) continue;
                    }
                } else if (fc.allowedDocumentIds().isPresent()) {
                    if (!fc.allowedDocumentIds().get().contains(docId)) continue;
                }
            }
            resultSoA.add(
                normalizedEntityType,
                ValueType.ENTITY_TYPE,
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
        return added;
    }

    private int executeWithTargetsOrBindFromBlob(String normalizedEntityType,
                                                 List<String> targets,
                                                 String variableName,
                                                 RBGroupValueBlob blob,
                                                 AttributeRequirements requirements,
                                                 QueryResultSoA resultSoA,
                                                 Optional<FilteringContext> context) throws QueryExecutionException {
        if (targets == null) targets = Collections.emptyList();
        java.util.Set<Integer> targetIds = new java.util.HashSet<>();
        boolean filterByTargets = (!targets.isEmpty());
        if (filterByTargets) {
            for (String t : targets) {
                if (t == null) continue;
                String norm = t.toLowerCase();
                try {
                    int id = synonymManager.getId(norm);
                    targetIds.add(id);
                } catch (Exception e) {
                    // skip unknown target
                }
            }
            if (targetIds.isEmpty()) return 0;
        }

        int added = 0;
        for (var entry : blob.getDocBlocks().entrySet()) {
            int docId = entry.getKey();
            RBGroupValueBlob.DocBlock block = entry.getValue();

            for (int i = 0; i < block.sentIds.length; i++) {
                int sentId = block.sentIds[i];
                if (context.isPresent()) {
                    FilteringContext fc = context.get();
                    if (requirements.needsSentenceId && fc.granularity() == Query.Granularity.SENTENCE) {
                        if (fc.allowedDocumentSentenceIds().isPresent()) {
                            var allowed = fc.allowedDocumentSentenceIds().get();
                            var set = allowed.get(docId);
                            if (set != null && !set.contains(sentId)) continue;
                        }
                    } else if (fc.allowedDocumentIds().isPresent()) {
                        if (!fc.allowedDocumentIds().get().contains(docId)) continue;
                    }
                }

                java.util.List<Integer> valIds = block.getValuesForSentenceIndex(i);
                if (valIds.isEmpty()) continue;

                for (int synId : valIds) {
                    if (filterByTargets && !targetIds.contains(synId)) continue;
                    Object valueToStore;
                    if (filterByTargets) {
                        String match = null;
                        for (String tgt : targets) {
                            try { if (synonymManager.getId(tgt.toLowerCase()) == synId) { match = tgt; break; } }
                            catch (Exception ignore) {}
                        }
                        valueToStore = (match != null) ? match : null;
                    } else {
                        valueToStore = (variableName != null) ? resolveTermSafe(synId) : normalizedEntityType;
                    }

                    resultSoA.add(
                        valueToStore,
                        (variableName != null || filterByTargets) ? ValueType.ENTITY : ValueType.ENTITY_TYPE,
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
        return added;
    }

    private String resolveTermSafe(int synonymId) {
        try {
            java.util.Map<Integer, String> map = synonymManager.getTerms(java.util.Set.of(synonymId));
            return map.getOrDefault(synonymId, null);
        } catch (Exception e) {
            return null;
        }
    }
}