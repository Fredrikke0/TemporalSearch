package com.example.query.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
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
import com.example.core.PositionListSoA;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Pos;
import com.example.query.model.condition.StitchedCondition;
import com.example.query.model.condition.Temporal;

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

        String stitchIndexName = "stitch_" + ngramPrefix + "_" + stitchIndexGroupIdentifier;
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

                try (RocksIterator iterator = stitchIndex.seek(prefixBytes)) {
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
                                        PositionListSoA positions = PositionListSoA.deserializeWithFilters(rawBlob, requirements, context);
                                        logger.trace("Found {} positions for matching date '{}' with key '{}' after filtering",
                                                   positions.getNumPositions(), dateFromKey, currentKey);

                                        for (int i = 0; i < positions.getNumPositions(); i++) {
                                            int docId = positions.getDocIdAt(i);
                                            int sentenceId = requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1;
                                            int termBeginChar = requirements.needsPositions ? positions.getBeginCharAt(i) : -1;
                                            int termEndChar = requirements.needsPositions ? positions.getEndCharAt(i) : -1;

                                            int conceptualRowId = resultSoA.getNextConceptualRowId();

                                            resultSoA.add(
                                                ngramTerm,
                                                ValueType.TERM,
                                                containsCondition.variableName(),
                                                docId,
                                                sentenceId,
                                                termBeginChar,
                                                termEndChar,
                                                -1,
                                                conceptualRowId
                                            );

                                            if (!annotationVarName.isBlank()) {
                                                resultSoA.add(
                                                    dateFromKey,
                                                    annotationValueTypeToStore,
                                                    annotationVarName,
                                                    docId,
                                                    sentenceId,
                                                    -1, -1,
                                                    -1,
                                                    conceptualRowId
                                                );
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
                Map<Integer, String> synonymIdToOriginalTarget = new HashMap<>();

                if (annotationCondition instanceof Ner nerValCond && !nerValCond.targets().isEmpty()) {
                    for (String target : nerValCond.targets()) {
                        if (target != null && !target.isBlank()) {
                            try {
                                int targetId = synonymManager.getId(target.toLowerCase());
                                targetAnnotationValueIds.add(targetId);
                                synonymIdToOriginalTarget.put(targetId, target);
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
                    try {
                        int targetId = synonymManager.getId(specificPosValue.toLowerCase());
                        targetAnnotationValueIds.add(targetId);
                        synonymIdToOriginalTarget.put(targetId, specificPosValue);
                        logger.trace("Stitch with specific POS term: '{}' (for POS tag {}), targetId: {}",
                                     specificPosValue, posCond.posTag().toUpperCase(), targetId);
                    } catch (Exception e) {
                        logger.warn("Failed to get synonym ID for specific POS term value '{}' (for POS tag {}) in StitchedExecutor. This term is unknown or DB error. Returning empty for this stitch.",
                                    specificPosValue, posCond.posTag().toUpperCase(), e);
                        return new QueryResultSoA(granularity, granularitySize, requirements);
                    }
                }

                Optional<byte[]> rawBlobOpt = stitchIndex.getRaw(stitchLookupKey.getBytes(StandardCharsets.UTF_8));

                if (rawBlobOpt.isPresent() && rawBlobOpt.get().length > 0) {
                    byte[] rawBlob = rawBlobOpt.get();
                    PositionListSoA positions = PositionListSoA.deserializeWithFilters(rawBlob, requirements, context);
                    logger.trace("Deserialized {} positions for key '{}'", positions.getNumPositions(), stitchLookupKey);

                    if (positions.isEmpty()) {
                        return resultSoA;
                    }

                    // --- Optimized Synonym Resolution (inspired by NerExecutor) ---
                    Map<Integer, String> resolvedTermsCache = Collections.emptyMap();
                    boolean needsResolving = !annotationVarName.isBlank() && targetAnnotationValueIds.isEmpty() &&
                                             (annotationValueTypeToStore == ValueType.UNRESOLVED_NER_ID ||
                                              annotationValueTypeToStore == ValueType.UNRESOLVED_POS_ID);

                    if (needsResolving) {
                        Set<Integer> uniqueSynonymIds = new HashSet<>();
                        for (int i = 0; i < positions.getNumPositions(); i++) {
                            uniqueSynonymIds.add(positions.getSynonymIdAt(i));
                        }
                        if (!uniqueSynonymIds.isEmpty()) {
                            resolvedTermsCache = synonymManager.getTerms(uniqueSynonymIds);
                        }
                    }
                    // --- End of Optimized Synonym Resolution ---

                    for (int i = 0; i < positions.getNumPositions(); i++) {
                        int docId = positions.getDocIdAt(i);
                        int sentenceId = requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1;
                        int termBeginChar = requirements.needsPositions ? positions.getBeginCharAt(i) : -1;
                        int termEndChar = requirements.needsPositions ? positions.getEndCharAt(i) : -1;
                        int annotationSynonymId = positions.getSynonymIdAt(i); // Stitch index always has synonym IDs

                        // If the stitch condition requires specific entity/term values, filter here
                        if (!targetAnnotationValueIds.isEmpty() && !targetAnnotationValueIds.contains(annotationSynonymId)) {
                            continue; // This annotation ID doesn't match the required set
                        }

                        int conceptualRowId = resultSoA.getNextConceptualRowId();

                        // Add the 'contains' part of the stitch
                        resultSoA.add(
                            ngramTerm,
                            ValueType.TERM,
                            containsCondition.variableName(),
                            docId,
                            sentenceId,
                            termBeginChar,
                            termEndChar,
                            -1,
                            conceptualRowId
                        );

                        // Add the 'annotation' part of the stitch
                        if (!annotationVarName.isBlank()) {
                            String valueToStore = "";
                            ValueType finalAnnotationValueTypeToStore = annotationValueTypeToStore;

                            if (!targetAnnotationValueIds.isEmpty()) {
                                valueToStore = synonymIdToOriginalTarget.getOrDefault(annotationSynonymId, "");
                                if (valueToStore.isBlank()) {
                                    logger.warn("Stitch Executor: synonym ID {} was matched but not found in the original target map. This indicates a potential logic issue.", annotationSynonymId);
                                }
                            } else if (needsResolving) {
                                valueToStore = resolvedTermsCache.getOrDefault(annotationSynonymId, "");
                                if (!valueToStore.isBlank()) {
                                    if (annotationValueTypeToStore == ValueType.UNRESOLVED_NER_ID) {
                                        finalAnnotationValueTypeToStore = ValueType.ENTITY;
                                    } else { // UNRESOLVED_POS_ID
                                        finalAnnotationValueTypeToStore = ValueType.POS_TERM;
                                    }
                                } else {
                                    logger.warn("Could not resolve synonym ID {} to a term using pre-fetched cache. Storing empty value for variable '{}'.",
                                                annotationSynonymId, annotationVarName);
                                }
                            }

                            resultSoA.add(
                                valueToStore,
                                finalAnnotationValueTypeToStore,
                                annotationVarName,
                                docId,
                                sentenceId,
                                -1, -1, // Annotations don't have their own position
                                annotationSynonymId,
                                conceptualRowId
                            );
                        }
                    }
                } else {
                    logger.debug("No data found for stitch key: {}", stitchLookupKey);
                }
            }
        } catch (IndexAccessException e) {
            logger.error("Error accessing stitch index {}.", stitchIndexName, e);
            throw new QueryExecutionException("Error accessing stitch index " + stitchIndexName, e, sourceName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (IOException e) {
            logger.error("IOException during StitchedExecutor execution for index {}.", stitchIndexName, e);
            throw new QueryExecutionException("IO error during stitch execution for " + stitchIndexName, e, sourceName, QueryExecutionException.ErrorType.INTERNAL_ERROR);
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
}