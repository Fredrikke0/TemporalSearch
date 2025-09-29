package com.example.query.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
        // Split by the delimiter and join with space
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
                        // Handle segmented keys by stripping trailing segment suffix like "#1", "#2", ...
                        String baseDatePart = datePart;
                        int hashPos = baseDatePart.lastIndexOf('#');
                        if (hashPos > 0 && hashPos < baseDatePart.length() - 1) {
                            boolean allDigits = true;
                            for (int i = hashPos + 1; i < baseDatePart.length(); i++) {
                                char c = baseDatePart.charAt(i);
                                if (c < '0' || c > '9') { allDigits = false; break; }
                            }
                            if (allDigits) {
                                baseDatePart = baseDatePart.substring(0, hashPos);
                            }
                        }
                        try {
                            LocalDate dateFromKey = TemporalExecutor.parseDateKey(baseDatePart);
                            if (dateFromKey != null) {
                                // Ensure date is within the supported range of the Nash index (1925-2025)
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
                                        PositionListSoA positions = PositionListSoA.deserializeWithFilters(rawBlob, context, requirements);
                                        logger.trace("Found {} positions for matching date '{}' with key '{}' after filtering",
                                                   positions.getNumPositions(), dateFromKey, currentKey);

                                        for (int i = 0; i < positions.getNumPositions(); i++) {
                                            int docId = positions.getDocIdAt(i);
                                            int sentenceId = positions.getSentenceIdAt(i);
                                            int termBeginChar = positions.getBeginCharAt(i);
                                            int termEndChar = positions.getEndCharAt(i);

                                            int conceptualRowId = resultSoA.getNextConceptualRowId();

                                            resultSoA.add(
                                                humanReadableNgramTerm,
                                                ValueType.TERM,
                                                containsCondition.variableName(),
                                                docId,
                                                requirements.needsSentenceId ? sentenceId : -1,
                                                requirements.needsPositions ? termBeginChar : -1,
                                                requirements.needsPositions ? termEndChar : -1,
                                                -1,
                                                conceptualRowId
                                            );

                                            if (!annotationVarName.isBlank()) {
                                                resultSoA.add(
                                                    dateFromKey,
                                                    annotationValueTypeToStore,
                                                    annotationVarName,
                                                    docId,
                                                    requirements.needsSentenceId ? sentenceId : -1,
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
                List<String> specificAnnotationValuesFromCondition = new ArrayList<>();

                if (annotationCondition instanceof Ner nerValCond && !nerValCond.targets().isEmpty()) {
                    for (String target : nerValCond.targets()) {
                        if (target != null && !target.isBlank()) {
                            specificAnnotationValuesFromCondition.add(target);
                            try {
                                int targetId = synonymManager.getId(target.toLowerCase());
                                targetAnnotationValueIds.add(targetId);
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
                        logger.trace("Stitch with specific POS term: '{}' (for POS tag {}), targetId: {}",
                                     specificPosValue, posCond.posTag().toUpperCase(), targetId);
                    } catch (Exception e) {
                        logger.warn("Failed to get synonym ID for specific POS term value '{}' (for POS tag {}) in StitchedExecutor. This term is unknown or DB error. Returning empty for this stitch.",
                                    specificPosValue, posCond.posTag().toUpperCase(), e);
                        return new QueryResultSoA(granularity, granularitySize, requirements);
                    }
                }

                Optional<PositionListSoA> mergedPositionsOpt = stitchIndex.getMergedPositions(stitchLookupKey, context, requirements);

                if (mergedPositionsOpt.isPresent() && !mergedPositionsOpt.get().isEmpty()) {
                    PositionListSoA positions = mergedPositionsOpt.get();
                    logger.debug("Found {} potential co-occurrences for key '{}' in stitch index '{}' after context filtering (merged segments).",
                                 positions.getNumPositions(), stitchLookupKey, stitchIndexName);

                    for (int i = 0; i < positions.getNumPositions(); i++) {
                        int docId = positions.getDocIdAt(i);
                        int sentenceId = positions.getSentenceIdAt(i);
                        int termBeginChar = positions.getBeginCharAt(i);
                        int termEndChar = positions.getEndCharAt(i);
                        int currentAnnotationSynonymId = positions.getSynonymIdAt(i);
                        Object annotationValueForSoA;

                        if (!targetAnnotationValueIds.isEmpty()) {
                            if (targetAnnotationValueIds.contains(currentAnnotationSynonymId)) {
                                // Find the original annotation value that matches this synonym ID
                                annotationValueForSoA = null;
                                for (String originalValue : specificAnnotationValuesFromCondition) {
                                    try {
                                        int originalId = synonymManager.getId(originalValue.toLowerCase());
                                        if (originalId == currentAnnotationSynonymId) {
                                            annotationValueForSoA = originalValue;
                                            break;
                                        }
                                    } catch (Exception e) {
                                        // Continue looking
                                    }
                                }
                                if (annotationValueForSoA == null) {
                                    annotationValueForSoA = specificAnnotationValuesFromCondition.get(0); // Fallback
                                }
                            } else {
                                logger.trace("Skipping co-occurrence: current annotation ID {} not in target IDs {} (for specific values {}) for stitch key '{}'",
                                             currentAnnotationSynonymId, targetAnnotationValueIds, specificAnnotationValuesFromCondition, stitchLookupKey);
                                continue;
                            }
                        } else {
                            annotationValueForSoA = currentAnnotationSynonymId;
                        }

                        int conceptualRowId = resultSoA.getNextConceptualRowId();

                        resultSoA.add(
                            humanReadableNgramTerm,
                            ValueType.TERM,
                            containsCondition.variableName(),
                            docId,
                            requirements.needsSentenceId ? sentenceId : -1,
                            requirements.needsPositions ? termBeginChar : -1,
                            requirements.needsPositions ? termEndChar : -1,
                            -1,
                            conceptualRowId
                        );

                        if (!annotationVarName.isBlank()) {
                            if (annotationValueForSoA != null) {
                                resultSoA.add(
                                    annotationValueForSoA,
                                    annotationValueTypeToStore,
                                    annotationVarName,
                                    docId,
                                    requirements.needsSentenceId ? sentenceId : -1,
                                    -1, -1,
                                    requirements.needsSynonymIds ? currentAnnotationSynonymId : -1,
                                    conceptualRowId
                                );
                            } else {
                                logger.warn("annotationValueForSoA is null but annotationVarName ('{}') is set for stitch key '{}'. Skipping annotation binding for conceptualRowId {}.",
                                            annotationVarName, stitchLookupKey, conceptualRowId);
                            }
                        }
                    }
                } else {
                     logger.debug("No entry found for key '{}' in stitch index '{}' (including segments) or positions were empty after filtering.", stitchLookupKey, stitchIndexName);
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

        // Sorting is centralized by QueryExecutor
        logger.debug("StitchedExecutor produced {} results.", resultSoA.size());

        return resultSoA;
    }
}