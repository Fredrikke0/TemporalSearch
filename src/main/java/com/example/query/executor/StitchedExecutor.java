package com.example.query.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.rocksdb.RocksDBException; // Added import
import org.rocksdb.RocksIterator; // Added for temporal prefix scan
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
        ValueType annotationValueType;

        Temporal temporalConditionDetails = null;

        if (annotationCondition instanceof Ner nerCond) {
            String nerEntityType = nerCond.entityType().toUpperCase();
            if ("DATE".equals(nerEntityType)) {
                logger.warn("Stitch optimization for NER type DATE is handled by Temporal stitch. Found NER({}). Returning empty.", nerEntityType);
                return new QueryResultSoA(granularity, granularitySize, requirements);
            }
            stitchIndexGroupIdentifier = "ner";
            specificAnnotationTypeForLookup = nerEntityType;
            annotationVarName = nerCond.qualifiedVariableName();
            annotationValueType = ValueType.ENTITY;
        } else if (annotationCondition instanceof Pos posCond) {
            stitchIndexGroupIdentifier = "pos";
            specificAnnotationTypeForLookup = posCond.posTag().toUpperCase();
            annotationVarName = posCond.variableName();
            annotationValueType = ValueType.POS_TERM;
        } else if (annotationCondition instanceof Temporal tempCond) {
            stitchIndexGroupIdentifier = "date";
            temporalConditionDetails = tempCond;
            annotationVarName = tempCond.qualifiedVariableName().orElse("");
            annotationValueType = ValueType.DATE;
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
                                        PositionListSoA positions = PositionListSoA.deserializeWithFilters(rawBlob, context);
                                        logger.trace("Found {} positions for matching date '{}' with key '{}' after filtering",
                                                   positions.getNumPositions(), dateFromKey, currentKey);

                                        for (int i = 0; i < positions.getNumPositions(); i++) {
                                            int docId = positions.getDocIdAt(i);
                                            int sentenceId = positions.getSentenceIdAt(i);
                                            int termBeginChar = positions.getBeginCharAt(i);
                                            int termEndChar = positions.getEndCharAt(i);
                                            int annotationSpecificValueId = positions.getSynonymIdAt(i);

                                            int conceptualRowId = resultSoA.getNextConceptualRowId();

                                            resultSoA.add(
                                                ngramTerm,
                                                ValueType.TERM,
                                                containsCondition.variableName(),
                                                docId, sentenceId,
                                                termBeginChar, termEndChar,
                                                -1,
                                                conceptualRowId
                                            );

                                            if (!annotationVarName.isBlank()) {
                                                resultSoA.add(
                                                    dateFromKey,
                                                    annotationValueType,
                                                    annotationVarName,
                                                    docId, sentenceId,
                                                    -1, -1,
                                                    annotationSpecificValueId,
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
                } catch (IndexAccessException e) {
                    logger.error("RocksDB access error during temporal stitch prefix scan for index {}.", stitchIndexName, e);
                    throw new QueryExecutionException("Error during temporal stitch index access", e, sourceName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
                }

            } else {
                String stitchLookupKey = ngramTerm + DELIMITER_CHAR + specificAnnotationTypeForLookup;
                logger.debug("Looking up in stitch index '{}' with key: '{}', context isPresent: {}",
                             stitchIndexName, stitchLookupKey, context.isPresent());

                Optional<byte[]> rawBlobOpt = stitchIndex.getRaw(stitchLookupKey.getBytes(StandardCharsets.UTF_8));

                if (rawBlobOpt.isPresent() && rawBlobOpt.get().length > 0) {
                    byte[] rawBlob = rawBlobOpt.get();
                    PositionListSoA positions = PositionListSoA.deserializeWithFilters(rawBlob, context);
                    logger.debug("Found {} potential co-occurrences for key '{}' in stitch index '{}' after context filtering.",
                                 positions.getNumPositions(), stitchLookupKey, stitchIndexName);

                    for (int i = 0; i < positions.getNumPositions(); i++) {
                        int docId = positions.getDocIdAt(i);
                        int sentenceId = positions.getSentenceIdAt(i);
                        int termBeginChar = positions.getBeginCharAt(i);
                        int termEndChar = positions.getEndCharAt(i);
                        int annotationSpecificValueId = positions.getSynonymIdAt(i);

                        int conceptualRowId = resultSoA.getNextConceptualRowId();

                        resultSoA.add(
                            ngramTerm,
                            ValueType.TERM,
                            containsCondition.variableName(),
                            docId, sentenceId,
                            termBeginChar, termEndChar,
                            -1,
                            conceptualRowId
                        );

                        if (!annotationVarName.isBlank()) {
                            Object valueToAdd = null;
                            Optional<String> resolvedValueOpt = synonymManager.getTerm(annotationSpecificValueId);

                            if (resolvedValueOpt.isPresent()) {
                                valueToAdd = resolvedValueOpt.get();
                            } else {
                                logger.warn("Could not resolve synonym ID {} for annotation value. Stitch key: '{}'. Skipping this annotation binding.", annotationSpecificValueId, stitchLookupKey);
                                continue;
                            }

                            if (valueToAdd != null) {
                                resultSoA.add(
                                    valueToAdd,
                                    annotationValueType,
                                    annotationVarName,
                                    docId, sentenceId,
                                    -1, -1,
                                    annotationSpecificValueId,
                                    conceptualRowId
                                );
                            }
                        }
                    }
                } else {
                     logger.debug("No entry found for key '{}' in stitch index '{}' or blob was empty.", stitchLookupKey, stitchIndexName);
                }
            }
        } catch (IndexAccessException e) {
            logger.error("Error accessing stitch index {}.", stitchIndexName, e);
            throw new QueryExecutionException("Error accessing stitch index " + stitchIndexName, e, sourceName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (RocksDBException e) {
            logger.error("RocksDB error during StitchedExecutor execution for index {}.", stitchIndexName, e);
            throw new QueryExecutionException("RocksDB error during stitch execution for " + stitchIndexName, e, sourceName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (IOException e) {
            logger.error("IOException during StitchedExecutor execution for index {}.", stitchIndexName, e);
            throw new QueryExecutionException("IO error during stitch execution for " + stitchIndexName, e, sourceName, QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        logger.debug("StitchedExecutor finished for type '{}', N-gram '{}', index '{}'. Found {} results.",
                     stitchedCondition.stitchType(), ngramPrefix, stitchIndexName, resultSoA.size());

        // Ensure results are sorted for subsequent merge joins
        if (resultSoA.size() > 1) {
            resultSoA.sort();
            logger.debug("Sorted StitchedExecutor results. Size: {}", resultSoA.size());
        }

        return resultSoA;
    }
}