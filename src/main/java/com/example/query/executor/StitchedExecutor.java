package com.example.query.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.index.presence.RBGroupValueBlob;
import com.example.index.presence.RBPresenceIndex;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Pos;
import com.example.query.model.condition.StitchedCondition;
import com.example.query.model.condition.Temporal;

import no.ntnu.sandbox.Nash;

public final class StitchedExecutor implements ConditionExecutor<StitchedCondition> {
    private static final Logger logger = LoggerFactory.getLogger(StitchedExecutor.class);
    private static final char DELIMITER_CHAR = IndexAccessInterface.DELIMITER;

    private final SynonymManager synonymManager;

    public StitchedExecutor(SynonymManager synonymManager) {
        if (synonymManager == null) {
            throw new IllegalArgumentException("SynonymManager cannot be null for StitchedExecutor");
        }
        this.synonymManager = synonymManager;
    }

    private String reconstructValue(String key, char delimiter) {
        String[] parts = key.split(String.valueOf(delimiter));
        return String.join(" ", parts);
    }

    @Override
    public QueryResultSoA execute(
            StitchedCondition stitchedCondition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String sourceName,
            AttributeRequirements requirements,
            Optional<FilteringContext> context) throws QueryExecutionException {

        logger.debug(">>> Executing StitchedExecutor");
        Contains containsCondition = stitchedCondition.containsCondition();
        Condition annotationCondition = stitchedCondition.annotationCondition();

        List<String> terms = containsCondition.terms();
        if (terms == null || terms.isEmpty() || terms.size() > 3) {
            logger.warn("StitchedExecutor requires 1 to 3 terms for CONTAINS. Found: {}. Returning empty result.", terms != null ? terms.size() : "null");
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        int ngramLevel = terms.size();
        String ngramTerm;
        String ngramPrefix;

        if (ngramLevel == 1) {
            ngramTerm = terms.get(0).toLowerCase();
            ngramPrefix = "unigram";
        } else if (ngramLevel == 2) {
            ngramTerm = terms.get(0).toLowerCase() + DELIMITER_CHAR + terms.get(1).toLowerCase();
            ngramPrefix = "bigram";
        } else { // ngramLevel == 3
            ngramTerm = terms.get(0).toLowerCase() + DELIMITER_CHAR +
                        terms.get(1).toLowerCase() + DELIMITER_CHAR +
                        terms.get(2).toLowerCase();
            ngramPrefix = "trigram";
        }

        String humanReadableNgramTerm = reconstructValue(ngramTerm, DELIMITER_CHAR);

        String stitchIndexGroupIdentifier;
        String specificAnnotationTypeForLookup = null;
        String annotationVarName = "";
        ValueType annotationValueTypeToStore;

        Temporal temporalConditionDetails = null;

        if (annotationCondition instanceof Ner nerCond) {
            String nerEntityType = nerCond.entityType().toUpperCase();
            if ("DATE".equals(nerEntityType)) {
                logger.warn("Stitch optimization for NER type DATE is handled by Temporal stitch. Found NER({}). Returning empty.", nerEntityType);
                return new QueryResultSoA(granularity, granularitySize, requirements);
            }
            stitchIndexGroupIdentifier = "ner";
            specificAnnotationTypeForLookup = nerEntityType;
            annotationVarName = Optional.ofNullable(nerCond.qualifiedVariableName()).orElse("");
            annotationValueTypeToStore = (!nerCond.targets().isEmpty() && nerCond.targets().get(0) != null && !nerCond.targets().get(0).isBlank()) ? ValueType.ENTITY : ValueType.UNRESOLVED_NER_ID;
        } else if (annotationCondition instanceof Pos posCond) {
            stitchIndexGroupIdentifier = "pos";
            specificAnnotationTypeForLookup = posCond.posTag().toUpperCase();
            annotationVarName = Optional.ofNullable(posCond.variableName()).orElse("");
            annotationValueTypeToStore = (posCond.term() != null && !posCond.term().isBlank()) ? ValueType.POS_TERM : ValueType.UNRESOLVED_POS_ID;
        } else if (annotationCondition instanceof Temporal tempCond) {
            stitchIndexGroupIdentifier = "date";
            temporalConditionDetails = tempCond;
            annotationVarName = tempCond.qualifiedVariableName().orElse("");
            annotationValueTypeToStore = ValueType.DATE;
        } else {
            logger.warn("Unsupported annotation condition type for stitch optimization: {}. Returning empty.", annotationCondition.getType());
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        String stitchIndexName = "rb_stitch_" + ngramPrefix + "_" + stitchIndexGroupIdentifier;
        IndexAccessInterface stitchIndex = indexes.get(stitchIndexName);

        if (stitchIndex == null || !stitchIndex.isOpen()) {
            logger.warn("Stitch index '{}' not found or not open for {}-{}({}-gram) optimization. Returning empty.",
                        stitchIndexName, stitchIndexGroupIdentifier, ngramPrefix, ngramLevel);
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        requirements.needsConceptualRowIds = true;

        try {
            if (temporalConditionDetails != null) {
                String searchPrefix = ngramTerm + DELIMITER_CHAR;
                logger.debug("Performing prefix search in stitch index '{}' with prefix: '{}', context isPresent: {}",
                           stitchIndexName, searchPrefix, context.isPresent());
                byte[] prefixBytes = searchPrefix.getBytes(StandardCharsets.UTF_8);
                byte[] upperBound = java.util.Arrays.copyOf(prefixBytes, prefixBytes.length + 1);
                upperBound[upperBound.length - 1] = (byte)0xFF;

                try (RocksIterator iterator = stitchIndex.seekWithBounds(prefixBytes, upperBound, 256 * 1024)) {
                    int keysExamined = 0;
                    while (iterator.isValid()) {
                        keysExamined++;
                        String currentKey = new String(iterator.key(), StandardCharsets.UTF_8);

                        if (!currentKey.startsWith(searchPrefix)) {
                            break;
                        }

                        String datePart = currentKey.substring(searchPrefix.length());
                        try {
                            LocalDate dateFromKey = TemporalExecutor.parseDateKey(datePart);
                            if (dateFromKey != null) {
                                if (dateFromKey.isBefore(Nash.GLOBAL_LOWER_BOUND) || dateFromKey.isAfter(Nash.GLOBAL_UPPER_BOUND)) {
                                    iterator.next();
                                    continue;
                                }

                                boolean matches = TemporalExecutor.evaluateTemporalCondition(
                                    temporalConditionDetails.temporalType(),
                                    dateFromKey.atStartOfDay(),
                                    dateFromKey.atTime(LocalTime.MAX),
                                    temporalConditionDetails.startDate().orElse(null),
                                    temporalConditionDetails.endDate().orElse(null)
                                );

                                if (matches) {
                                    byte[] rawBlob = iterator.value();
                                    if (rawBlob != null && rawBlob.length > 0) {
                                        try {
                                            RBGroupValueBlob blob = RBGroupValueBlob.fromBytes(rawBlob);
                                            var it = blob.getPresenceIndex().getBitmap().getLongIterator();
                                            while (it.hasNext()) {
                                                long pair = it.next();
                                                int docId = (int)(pair >>> 16);
                                                int sentenceId = (int)(pair & 0xFFFFL);
                                                if (!passesContextFilter(context, requirements, docId, sentenceId)) continue;

                                                int conceptualRowId = resultSoA.getNextConceptualRowId();
                                                resultSoA.add(humanReadableNgramTerm, ValueType.TERM, containsCondition.variableName(), docId,
                                                    requirements.needsSentenceId ? sentenceId : -1, -1, -1, -1, conceptualRowId);
                                                if (!annotationVarName.isBlank()) {
                                                    resultSoA.add(dateFromKey, annotationValueTypeToStore, annotationVarName, docId,
                                                        requirements.needsSentenceId ? sentenceId : -1, -1, -1, -1, conceptualRowId);
                                                }
                                            }
                                        } catch (IOException ignoreBlob) {
                                            RBPresenceIndex pres = RBPresenceIndex.fromBytes(rawBlob);
                                            var it = pres.getBitmap().getLongIterator();
                                            while (it.hasNext()) {
                                                long pair = it.next();
                                                int docId = (int)(pair >>> 16);
                                                int sentenceId = (int)(pair & 0xFFFFL);
                                                if (!passesContextFilter(context, requirements, docId, sentenceId)) continue;

                                                int conceptualRowId = resultSoA.getNextConceptualRowId();
                                                resultSoA.add(humanReadableNgramTerm, ValueType.TERM, containsCondition.variableName(), docId,
                                                    requirements.needsSentenceId ? sentenceId : -1, -1, -1, -1, conceptualRowId);
                                                if (!annotationVarName.isBlank()) {
                                                    resultSoA.add(dateFromKey, annotationValueTypeToStore, annotationVarName, docId,
                                                        requirements.needsSentenceId ? sentenceId : -1, -1, -1, -1, conceptualRowId);
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                logger.warn("Could not parse date '{}' from stitch key '{}'. Expected format yyyyMMdd. Skipping.", datePart, currentKey);
                            }
                        } catch (Exception e) {
                            logger.warn("Error processing date '{}' from stitch key '{}'. Skipping.", datePart, currentKey, e);
                        }
                        iterator.next();
                    }
                    logger.debug("Prefix search completed for temporal stitch. Examined {} keys for prefix '{}'", keysExamined, searchPrefix);
                }
            } else {
                String stitchLookupKey = ngramTerm + DELIMITER_CHAR + specificAnnotationTypeForLookup;
                logger.debug("Looking up in stitch index '{}' with key: '{}', context isPresent: {}, annotationValueTypeToStore: {}",
                             stitchIndexName, stitchLookupKey, context.isPresent(), annotationValueTypeToStore);

                Set<Integer> targetAnnotationValueIds = new HashSet<>();
                Map<Integer, String> originalCaseById = new HashMap<>();
                List<String> specificAnnotationValuesFromCondition = new ArrayList<>();

                if (annotationCondition instanceof Ner nerValCond && nerValCond.targets() != null && !nerValCond.targets().isEmpty()) {
                    for (String target : nerValCond.targets()) {
                        if (target != null && !target.isBlank()) {
                            specificAnnotationValuesFromCondition.add(target);
                            try {
                                int targetId = synonymManager.getId(target.toLowerCase());
                                targetAnnotationValueIds.add(targetId);
                                originalCaseById.put(targetId, target);
                                logger.trace("Stitch with specific NER entity: '{}', targetId: {}", target, targetId);
                            } catch (Exception e) {
                                logger.warn("Failed to get synonym ID for specific NER entity value '{}' in StitchedExecutor. This entity is unknown or DB error. Skipping this target.", target, e);
                            }
                        }
                    }

                    if (targetAnnotationValueIds.isEmpty()) {
                        logger.warn("No valid synonym IDs found for NER targets {} in StitchedExecutor. Returning empty for this stitch.", nerValCond.targets());
                        return new QueryResultSoA(granularity, granularitySize, requirements);
                    }
                } else if (annotationCondition instanceof Pos posCond && posCond.term() != null && !posCond.term().isBlank()) {
                    String specificPosValue = posCond.term();
                    specificAnnotationValuesFromCondition.add(specificPosValue);
                    try {
                        int targetId = synonymManager.getId(specificPosValue.toLowerCase());
                        targetAnnotationValueIds.add(targetId);
                        originalCaseById.put(targetId, specificPosValue);
                        logger.trace("Stitch with specific POS term: '{}' (for POS tag {}), targetId: {}",
                                     specificPosValue, posCond.posTag().toUpperCase(), targetId);
                    } catch (Exception e) {
                        logger.warn("Failed to get synonym ID for specific POS term value '{}' (for POS tag {}) in StitchedExecutor. This term is unknown or DB error. Returning empty for this stitch.",
                                    specificPosValue, posCond.posTag().toUpperCase(), e);
                        return new QueryResultSoA(granularity, granularitySize, requirements);
                    }
                }

                Optional<byte[]> rawOpt = stitchIndex.getRaw(stitchLookupKey.getBytes(StandardCharsets.UTF_8));
                if (rawOpt.isEmpty()) {
                    logger.debug("No entry found for key '{}' in stitch index '{}' (raw read)", stitchLookupKey, stitchIndexName);
                    return new QueryResultSoA(granularity, granularitySize, requirements);
                }

                byte[] raw = rawOpt.get();
                RBGroupValueBlob blob;
                try {
                    blob = RBGroupValueBlob.fromBytes(raw);
                } catch (IOException e) {
                    logger.warn("Blob parse failed for stitch key '{}' in '{}': {}", stitchLookupKey, stitchIndexName, e.getMessage());
                    return new QueryResultSoA(granularity, granularitySize, requirements);
                }

                for (var entry : blob.getDocBlocks().entrySet()) {
                    int docId = entry.getKey();
                    RBGroupValueBlob.DocBlock block = entry.getValue();
                    for (int i = 0; i < block.sentIds.length; i++) {
                        int sentenceId = block.sentIds[i];
                        if (!passesContextFilter(context, requirements, docId, sentenceId)) continue;

                        List<Integer> vals = block.getValuesForSentenceIndex(i);
                        if (vals.isEmpty()) continue;

                        for (int synId : vals) {
                            if (!targetAnnotationValueIds.isEmpty() && !targetAnnotationValueIds.contains(synId)) continue;

                            int conceptualRowId = resultSoA.getNextConceptualRowId();
                            resultSoA.add(humanReadableNgramTerm, ValueType.TERM, containsCondition.variableName(), docId,
                                requirements.needsSentenceId ? sentenceId : -1, -1, -1, -1, conceptualRowId);

                            if (!annotationVarName.isBlank()) {
                                Object valueForSoa;
                                if (!targetAnnotationValueIds.isEmpty()) {
                                    valueForSoa = originalCaseById.getOrDefault(synId, null);
                                } else {
                                    try {
                                        valueForSoa = (annotationValueTypeToStore == ValueType.ENTITY || annotationValueTypeToStore == ValueType.POS_TERM)
                                            ? synonymManager.getTerms(Set.of(synId)).getOrDefault(synId, null)
                                            : Integer.valueOf(synId);
                                    } catch (Exception ex) {
                                        valueForSoa = Integer.valueOf(synId);
                                    }
                                }

                                resultSoA.add(
                                    valueForSoa,
                                    annotationValueTypeToStore,
                                    annotationVarName,
                                    docId,
                                    requirements.needsSentenceId ? sentenceId : -1,
                                    -1, -1,
                                    requirements.needsSynonymIds ? synId : -1,
                                    conceptualRowId
                                );
                            }
                        }
                    }
                }
            }
        } catch (IndexAccessException e) {
            logger.error("Error accessing stitch index {}.", stitchIndexName, e);
            throw new QueryExecutionException("Error accessing stitch index " + stitchIndexName, e, sourceName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (Exception e) {
            logger.error("Unexpected exception during StitchedExecutor execution for index {}.", stitchIndexName, e);
            String message = "Unexpected error during stitch execution for " + stitchIndexName;
            if (e.getClass().getName().contains("RocksDBException")) {
                 message = "Database error during stitch execution (synonym lookup) for " + stitchIndexName;
            }
            throw new QueryExecutionException(message, e, sourceName, QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        logger.debug("StitchedExecutor finished for type '{}', N-gram '{}', index '{}'. Found {} results.",
                     stitchedCondition.stitchType(), ngramPrefix, stitchIndexName, resultSoA.size());

        if (resultSoA.size() > 1) {
            resultSoA.sort();
            logger.debug("Sorted StitchedExecutor results. Size: {}", resultSoA.size());
        }

        return resultSoA;
    }

    private boolean passesContextFilter(Optional<FilteringContext> context, AttributeRequirements requirements, int docId, int sentenceId) {
        if (context.isEmpty()) return true;
        FilteringContext fc = context.get();
        if (requirements.needsSentenceId && fc.granularity() == Query.Granularity.SENTENCE) {
            if (fc.allowedDocumentSentenceIds().isPresent()) {
                var allowed = fc.allowedDocumentSentenceIds().get();
                var set = allowed.get(docId);
                return set == null || set.contains(sentenceId);
            }
        } else if (fc.allowedDocumentIds().isPresent()) {
            return fc.allowedDocumentIds().get().contains(docId);
        }
        return true;
    }
}