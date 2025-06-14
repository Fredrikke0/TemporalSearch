package com.example.query.executor;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
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
import com.example.query.model.condition.StitchedPairCondition;
import com.example.query.model.condition.Temporal;

public final class StitchedPairExecutor implements ConditionExecutor<StitchedPairCondition> {
    private static final Logger logger = LoggerFactory.getLogger(StitchedPairExecutor.class);
    private static final char DELIMITER_CHAR = IndexAccessInterface.DELIMITER;

    private final SynonymManager synonymManager;

    public StitchedPairExecutor(SynonymManager synonymManager) {
        this.synonymManager = synonymManager;
    }

    @Override
    public QueryResultSoA execute(
            StitchedPairCondition stitchedCondition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Optional<FilteringContext> context)
            throws QueryExecutionException {

        Contains containsCondition = stitchedCondition.containsCondition();
        Condition annotationCondition = stitchedCondition.annotationCondition();

        List<String> terms = containsCondition.terms();
        // containsCondition should always have 1 term due to LogicalExecutor's fusing logic
        if (terms == null || terms.size() != 1) {
            logger.warn("StitchedPairExecutor expects Contains condition with exactly one term. Found: {}. Returning empty.", terms);
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        int ngramLevel = terms.size(); // Should be 1
        String ngramTerm = terms.get(0).toLowerCase();
        String ngramPrefix = "unigram"; // Since terms.size() must be 1

        String stitchIndexGroupIdentifier;
        String specificAnnotationTypeForLookup;
        String targetAnnotationValue = null;
        String annotationVarName;
        ValueType annotationValueType;
        Temporal temporalCondition = null;

        if (annotationCondition instanceof Ner nerCond) {
            String nerEntityType = nerCond.entityType().toUpperCase();
            if ("*".equals(nerEntityType)) {
                logger.warn("Stitch optimization for NER(*) is not currently supported. Query part: {}. Returning empty.", nerCond);
                return new QueryResultSoA(granularity, granularitySize, requirements);
            }
            stitchIndexGroupIdentifier = "ner";
            specificAnnotationTypeForLookup = nerEntityType;
            targetAnnotationValue = nerCond.target();
            annotationVarName = nerCond.qualifiedVariableName();
            annotationValueType = ValueType.ENTITY;
        } else if (annotationCondition instanceof Pos posCond) {
            stitchIndexGroupIdentifier = "pos";
            specificAnnotationTypeForLookup = posCond.posTag().toUpperCase();
            targetAnnotationValue = posCond.term();
            annotationVarName = posCond.variableName();
            annotationValueType = ValueType.TERM;
        } else if (annotationCondition instanceof Temporal tempCond) {
            stitchIndexGroupIdentifier = "date";
            specificAnnotationTypeForLookup = "date";
            temporalCondition = tempCond;
            annotationVarName = tempCond.qualifiedVariableName().orElse(null);
            annotationValueType = ValueType.DATE;
        } else {
            logger.warn("Unsupported annotation condition type for stitch optimization: {}. Returning empty.", annotationCondition.getType());
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        String stitchIndexName = "stitch_" + ngramPrefix + "_" + stitchIndexGroupIdentifier;
        IndexAccessInterface stitchIndex = indexes.get(stitchIndexName);

        if (stitchIndex == null) {
            logger.warn("Stitch index '{}' not found. Ensure it's generated. Returning empty.", stitchIndexName);
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }
        if (!stitchIndex.isOpen()) {
            logger.warn("Stitch index '{}' is not open. Returning empty.", stitchIndexName);
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        if (synonymManager == null) {
            throw new QueryExecutionException(
                    "SynonymManager cannot be null for stitch execution.",
                    corpusName, QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        if (requirements.needsConceptualRowIds) { // Ensure conceptualRowIds are managed
             // This is handled by QueryResultSoA.add -> getNextConceptualRowId now
        } else {
            logger.warn("StitchedPairExecutor: AttributeRequirements.needsConceptualRowIds is false. This might lead to issues in AND/JOIN operations.");
        }

        try {
            if (temporalCondition != null) {
                String searchPrefix = ngramTerm + DELIMITER_CHAR;
                logger.debug("Performing prefix search in stitch index '{}' with prefix: '{}', context isPresent: {}",
                        stitchIndexName, searchPrefix, context.isPresent());

                byte[] prefixBytes = searchPrefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);

                try (RocksIterator iterator = stitchIndex.seek(prefixBytes)) {
                    int foundKeys = 0;
                    while (iterator.isValid()) {
                        String currentKey = new String(iterator.key(), java.nio.charset.StandardCharsets.UTF_8);
                        if (!currentKey.startsWith(searchPrefix)) {
                            break;
                        }
                        foundKeys++;
                        String datePart = currentKey.substring(searchPrefix.length());
                        try {
                            LocalDate dateFromKey = TemporalExecutor.parseDateKey(datePart);
                            if (dateFromKey != null) {
                                boolean matches = TemporalExecutor.evaluateTemporalCondition(
                                        temporalCondition.temporalType(),
                                        dateFromKey.atStartOfDay(),
                                        dateFromKey.atTime(LocalTime.MAX),
                                        temporalCondition.startDate().orElse(null),
                                        temporalCondition.endDate().orElse(null)
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
                                            int unigramBeginChar = positions.getBeginCharAt(i);
                                            int unigramEndChar = positions.getEndCharAt(i);
                                            int specificAnnotationTextId = positions.getSynonymIdAt(i);
                                            int conceptualRowId = resultSoA.getNextConceptualRowId();

                                            resultSoA.add(
                                                    ngramTerm,
                                                    ValueType.TERM,
                                                    containsCondition.variableName(),
                                                    docId, sentenceId,
                                                    unigramBeginChar, unigramEndChar,
                                                    -1,
                                                    conceptualRowId
                                            );
                                            if (annotationVarName != null && !annotationVarName.isBlank()) {
                                                resultSoA.add(
                                                        dateFromKey,
                                                        ValueType.DATE,
                                                        annotationVarName,
                                                        docId, sentenceId,
                                                        -1, -1,
                                                        specificAnnotationTextId,
                                                        conceptualRowId
                                                );
                                            }
                                            logger.trace("Added stitched match: term='{}', date='{}', conceptualId={}",
                                                    ngramTerm, dateFromKey, conceptualRowId);
                                        }
                                    }
                                }
                            } else {
                                logger.warn("Could not parse date '{}' from stitch key '{}'. Expected yyyyMMdd. Skipping.", datePart, currentKey);
                            }
                        } catch (Exception e) {
                            logger.warn("Error processing date '{}' from stitch key '{}': {}. Skipping.", datePart, currentKey, e.getMessage());
                        }
                        iterator.next();
                    }
                    logger.debug("Prefix search completed. Examined {} keys for prefix '{}'", foundKeys, searchPrefix);
                }
            } else {
                String stitchLookupKey = ngramTerm + DELIMITER_CHAR + specificAnnotationTypeForLookup;
                logger.debug("Looking up in stitch index '{}' with key: '{}', context isPresent: {}", stitchIndexName, stitchLookupKey, context.isPresent());

                Optional<byte[]> rawBlobOpt = stitchIndex.getRaw(stitchLookupKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                if (rawBlobOpt.isPresent()) {
                    byte[] rawBlob = rawBlobOpt.get();
                    PositionListSoA positions = PositionListSoA.deserializeWithFilters(rawBlob, context);
                    logger.debug("Found {} potential co-occurrences for key '{}' after context filtering.",
                            positions.getNumPositions(), stitchLookupKey);

                    Set<Integer> uniqueAnnotationSynonymIds = new HashSet<>();
                    if (positions.getNumPositions() > 0) {
                        for (int i = 0; i < positions.getNumPositions(); i++) {
                            uniqueAnnotationSynonymIds.add(positions.getSynonymIdAt(i));
                        }
                    }
                    Map<Integer, String> resolvedAnnotationTermsCache = Collections.emptyMap();
                    if (!uniqueAnnotationSynonymIds.isEmpty()) {
                        try {
                            resolvedAnnotationTermsCache = synonymManager.getTerms(uniqueAnnotationSynonymIds);
                        } catch (org.rocksdb.RocksDBException e) {
                            logger.error("RocksDBException while batch fetching terms for stitch key '{}'", stitchLookupKey, e);
                            throw new QueryExecutionException("Failed to batch fetch annotation terms: " + stitchLookupKey, e, corpusName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
                        }
                    }

                    for (int i = 0; i < positions.getNumPositions(); i++) {
                        int docId = positions.getDocIdAt(i);
                        int sentenceId = positions.getSentenceIdAt(i);
                        int unigramBeginChar = positions.getBeginCharAt(i);
                        int unigramEndChar = positions.getEndCharAt(i);
                        int specificAnnotationTextId = positions.getSynonymIdAt(i);
                        String retrievedAnnotationText = resolvedAnnotationTermsCache.get(specificAnnotationTextId);

                        if (retrievedAnnotationText == null) {
                            logger.warn("Null annotation text for synonymId {} from stitch key '{}'. Skipping.", specificAnnotationTextId, stitchLookupKey);
                            continue;
                        }

                        boolean valueMatch = true;
                        Object conditionSpecificValue = retrievedAnnotationText;
                        if (targetAnnotationValue != null && !targetAnnotationValue.equalsIgnoreCase(retrievedAnnotationText)) {
                            valueMatch = false;
                        }

                        if (valueMatch) {
                            int conceptualRowId = resultSoA.getNextConceptualRowId();
                            resultSoA.add(
                                    ngramTerm,
                                    ValueType.TERM,
                                    containsCondition.variableName(),
                                    docId, sentenceId,
                                    unigramBeginChar, unigramEndChar,
                                    -1,
                                    conceptualRowId
                            );
                            if (annotationVarName != null && !annotationVarName.isBlank()) {
                                resultSoA.add(
                                        conditionSpecificValue,
                                        annotationValueType,
                                        annotationVarName,
                                        docId, sentenceId,
                                        -1, -1,
                                        specificAnnotationTextId,
                                        conceptualRowId
                                );
                            }
                            logger.trace("Added stitched match: term='{}', annotation='{}', conceptualId={}", ngramTerm, retrievedAnnotationText, conceptualRowId);
                        }
                    }
                } else {
                    logger.debug("No co-occurrences found for key '{}' in stitch index '{}'.", stitchLookupKey, stitchIndexName);
                }
            }
        } catch (IndexAccessException e) {
            String keyInfo = temporalCondition != null ? "prefix: " + ngramTerm + DELIMITER_CHAR : "key: " + ngramTerm + DELIMITER_CHAR + specificAnnotationTypeForLookup;
            throw new QueryExecutionException("IndexAccessException during stitch index lookup for " + keyInfo, e, corpusName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (IOException e) {
            String keyInfo = temporalCondition != null ? "prefix: " + ngramTerm + DELIMITER_CHAR : "key: " + ngramTerm + DELIMITER_CHAR + specificAnnotationTypeForLookup;
            throw new QueryExecutionException("IOException during stitch data deserialization for " + keyInfo, e, corpusName, QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        if (resultSoA.isEmpty()) {
            String keyInfo = temporalCondition != null ? "prefix '" + ngramTerm + DELIMITER_CHAR + "' for temporal" : "key '" + ngramTerm + DELIMITER_CHAR + specificAnnotationTypeForLookup + "'";
            logger.info("StitchedPairExecutor: Stitch {} for N-gram '{}' and annotation type '{}' resulted in no matches. Returning empty QueryResultSoA.",
                    keyInfo, ngramTerm, specificAnnotationTypeForLookup);
        } else {
            logger.info("StitchedPairExecutor finished for N-gram '{}' and annotation type '{}'. Found {} conceptual rows.",
                    ngramTerm, specificAnnotationTypeForLookup, resultSoA.getConceptualRowCount());
        }
        resultSoA.sort(); // Ensure results are sorted for downstream consumers (like LogicalExecutor)
        return resultSoA;
    }
}