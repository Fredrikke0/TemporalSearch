package com.example.query.executor;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Pos;
import com.example.query.model.condition.Temporal;


public class StitchIntersectionExecutor {
    private static final Logger logger = LoggerFactory.getLogger(StitchIntersectionExecutor.class);
    private static final char DELIMITER_CHAR = IndexAccessInterface.DELIMITER; // Usually \u0000

    public StitchIntersectionExecutor() {
        // Constructor
    }

    public QueryResultSoA execute(
            Contains containsCondition,
            Condition annotationCondition, // Ner, Pos, or Temporal (for DATE)
            Map<String, IndexAccessInterface> indexes,
            SynonymManager synonymManager,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Query currentQuery, // Passed for context, e.g. if Temporal condition evaluation needs more than its own state
            Optional<FilteringContext> context // Added FilteringContext
            ) throws QueryExecutionException {

        List<String> terms = containsCondition.terms();
        if (terms == null || terms.isEmpty()) {
            logger.warn("StitchIntersectionExecutor requires at least one term for CONTAINS. Found: {}. Fallback.", terms);
            return null; // Fallback
        }

        // Determine N-gram level and construct the N-gram term string for lookup
        int ngramLevel = terms.size();
        String ngramTerm;
        String ngramPrefix;

        if (ngramLevel == 1) {
            ngramTerm = terms.get(0).toLowerCase();
            ngramPrefix = "unigram";
        } else if (ngramLevel == 2) {
            ngramTerm = terms.get(0).toLowerCase() + DELIMITER_CHAR + terms.get(1).toLowerCase();
            ngramPrefix = "bigram";
        } else { // 3 or more terms
            ngramTerm = terms.get(0).toLowerCase() + DELIMITER_CHAR +
                        terms.get(1).toLowerCase() + DELIMITER_CHAR +
                        terms.get(2).toLowerCase();
            ngramPrefix = "trigram";
            if (ngramLevel > 3) {
                logger.warn("Stitch optimization currently only supports up to trigrams directly for CONTAINS. Query has {} terms. Using first 3 for stitch key.", ngramLevel);
            }
        }

        String stitchIndexGroupIdentifier;
        String specificAnnotationTypeForLookup; // For NER/POS, this is the entity type/tag. For DATE, we use the date string.
        String targetAnnotationValue = null;
        String annotationVarName;
        ValueType annotationValueType;

        Temporal temporalCondition = null; // Will be non-null if annotationCondition is Temporal

        if (annotationCondition instanceof Ner nerCond) {
            String nerEntityType = nerCond.entityType().toUpperCase();
            if ("*".equals(nerEntityType)) {
                logger.warn("Stitch optimization for NER(*) is not currently supported. Query part: {}. Fallback.", nerCond);
                return null; // Fallback
            }
            stitchIndexGroupIdentifier = "ner"; // Assumes "stitch_unigram_ner"
            specificAnnotationTypeForLookup = nerEntityType;
            targetAnnotationValue = nerCond.target(); // e.g., "Google" from NER(..., "Google")
            annotationVarName = nerCond.qualifiedVariableName();
            annotationValueType = ValueType.ENTITY;
        } else if (annotationCondition instanceof Pos posCond) {
            stitchIndexGroupIdentifier = "pos"; // Assumes "stitch_unigram_pos"
            specificAnnotationTypeForLookup = posCond.posTag().toUpperCase();
            targetAnnotationValue = posCond.term();
            annotationVarName = posCond.variableName();
            annotationValueType = ValueType.TERM; // Or a more specific type for POS tags
        } else if (annotationCondition instanceof Temporal tempCond) {
            // User wants stitch key for DATE to be term<DELIMITER>date
            stitchIndexGroupIdentifier = "date"; // Corrected: group identifier is "date"
            specificAnnotationTypeForLookup = "date"; // Corrected: type for lookup is "date"
            temporalCondition = tempCond;
            annotationVarName = tempCond.qualifiedVariableName().orElse(null);
            annotationValueType = ValueType.DATE;
        } else {
            logger.warn("Unsupported annotation condition type for stitch optimization: {}. Fallback.", annotationCondition.getType());
            return null; // Fallback
        }

        String stitchIndexName = "stitch_" + ngramPrefix + "_" + stitchIndexGroupIdentifier;
        IndexAccessInterface stitchIndex = indexes.get(stitchIndexName);

        if (stitchIndex == null) {
            logger.warn("Stitch index '{}' not found for CONTAINS-{}({}-gram) optimization. Ensure it's generated. Fallback.",
                        stitchIndexName, specificAnnotationTypeForLookup, ngramPrefix);
            return null; // Fallback
        }
        if (!stitchIndex.isOpen()) {
            logger.warn("Stitch index '{}' is not open. Fallback.", stitchIndexName);
            return null; // Fallback
        }

        if (synonymManager == null) {
            throw new QueryExecutionException(
                "SynonymManager cannot be null for stitch execution.",
                corpusName, QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        requirements.needsConceptualRowIds = true;

        try {
            if (temporalCondition != null) {
                // For temporal conditions, we need to do prefix search since dates are stored as YYYYMMDD
                String searchPrefix = ngramTerm + DELIMITER_CHAR;
                logger.debug("Performing prefix search in stitch index '{}' with prefix: '{}', context isPresent: {}",
                           stitchIndexName, searchPrefix, context.isPresent());

                byte[] prefixBytes = searchPrefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);

                try (org.rocksdb.RocksIterator iterator = stitchIndex.seek(prefixBytes)) {
                    int foundKeys = 0;
                    while (iterator.isValid()) {
                        String currentKey = new String(iterator.key(), java.nio.charset.StandardCharsets.UTF_8);

                        // Check if current key still starts with our prefix
                        if (!currentKey.startsWith(searchPrefix)) {
                            break; // No more keys with our prefix
                        }

                        foundKeys++;

                        // Extract the date part (everything after the delimiter)
                        String datePart = currentKey.substring(searchPrefix.length());

                        // Parse the date and check if it matches the temporal condition
                        try {
                            LocalDate dateFromKey = TemporalExecutor.parseDateKey(datePart);
                            if (dateFromKey != null) {
                                // Use TemporalExecutor's evaluation method for consistency
                                boolean matches = TemporalExecutor.evaluateTemporalCondition(
                                    temporalCondition.temporalType(),
                                    dateFromKey.atStartOfDay(),
                                    dateFromKey.atTime(LocalTime.MAX),
                                    temporalCondition.startDate().orElse(null),
                                    temporalCondition.endDate().orElse(null)
                                );

                                if (matches) {
                                    // This date matches our temporal condition, process the positions
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

                                            // Add the term binding
                                            resultSoA.add(
                                                ngramTerm,
                                                ValueType.TERM,
                                                containsCondition.variableName(),
                                                docId, sentenceId,
                                                unigramBeginChar, unigramEndChar,
                                                -1,
                                                conceptualRowId
                                            );

                                            // Add the date binding if variable is specified
                                            if (annotationVarName != null && !annotationVarName.isBlank()) {
                                                resultSoA.add(
                                                    dateFromKey,
                                                    ValueType.DATE,
                                                    annotationVarName,
                                                    docId, sentenceId,
                                                    -1, -1, // Placeholder coordinates for annotation
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
                                logger.warn("Could not parse date '{}' from stitch key '{}' using TemporalExecutor.parseDateKey. Expected format yyyyMMdd. Skipping.",
                                          datePart, currentKey);
                            }
                        } catch (Exception e) {
                            logger.warn("Error processing date '{}' from stitch key '{}': {}. Skipping.",
                                      datePart, currentKey, e.getMessage());
                        }

                        iterator.next();
                    }

                    logger.debug("Prefix search completed. Examined {} keys for prefix '{}'", foundKeys, searchPrefix);
                }

            } else {
                // For non-temporal conditions (NER/POS), use the original single-key lookup
                String stitchLookupKey = ngramTerm + DELIMITER_CHAR + specificAnnotationTypeForLookup;
                logger.debug("Looking up in stitch index '{}' with key: '{}', context isPresent: {}", stitchIndexName, stitchLookupKey, context.isPresent());

                Optional<byte[]> rawBlobOpt = stitchIndex.getRaw(stitchLookupKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                if (rawBlobOpt.isPresent()) {
                    byte[] rawBlob = rawBlobOpt.get();
                    PositionListSoA positions = PositionListSoA.deserializeWithFilters(rawBlob, context);
                    logger.debug("Found {} potential co-occurrences for key '{}' in stitch index '{}' after context filtering.",
                                 positions.getNumPositions(), stitchLookupKey, stitchIndexName);

                    for (int i = 0; i < positions.getNumPositions(); i++) {
                        int docId = positions.getDocIdAt(i);
                        int sentenceId = positions.getSentenceIdAt(i);
                        int unigramBeginChar = positions.getBeginCharAt(i);
                        int unigramEndChar = positions.getEndCharAt(i);
                        int specificAnnotationTextId = positions.getSynonymIdAt(i); // ID of the annotation text in stitch synonym store

                        String retrievedAnnotationText = null;
                        try {
                            retrievedAnnotationText = synonymManager.getTerm(specificAnnotationTextId).orElse(null);
                        } catch (org.rocksdb.RocksDBException e) {
                            logger.warn("RocksDBException while retrieving term for synonymId {} from stitch key '{}'. Skipping.",
                                    specificAnnotationTextId, stitchLookupKey, e);
                            continue;
                        }

                        if (retrievedAnnotationText == null) {
                            logger.warn("Null annotation text for synonymId {} from stitch key '{}' (using SynonymManager). Skipping.",
                                        specificAnnotationTextId, stitchLookupKey);
                            continue;
                        }

                        boolean valueMatch = true; // Default to true
                        Object conditionSpecificValue = retrievedAnnotationText; // Store String for SoA
                        if (targetAnnotationValue != null && !targetAnnotationValue.equalsIgnoreCase(retrievedAnnotationText)) {
                            valueMatch = false;
                        }

                        if (valueMatch) {
                            int conceptualRowId = resultSoA.getNextConceptualRowId();

                            resultSoA.add(
                                ngramTerm, // Use the potentially multi-word N-gram term here
                                ValueType.TERM, // Or a more specific NGRAM_TERM type if available
                                containsCondition.variableName(),
                                docId, sentenceId,
                                unigramBeginChar, unigramEndChar,
                                -1, // No specific synonym ID for the plain term here
                                conceptualRowId
                            );

                            if (annotationVarName != null && !annotationVarName.isBlank()) {
                                resultSoA.add(
                                    conditionSpecificValue, // This is String for NER/POS
                                    annotationValueType,
                                    annotationVarName,
                                    docId, sentenceId,
                                    -1, -1, // Placeholder coordinates for the annotation part from stitch
                                    specificAnnotationTextId,
                                    conceptualRowId
                                );
                            }

                            logger.trace("Added stitched match: term='{}', annotationType='{}', annotationText='{}', conceptualId={}",
                                         ngramTerm, specificAnnotationTypeForLookup, retrievedAnnotationText, conceptualRowId);
                        }
                    }
                } else {
                     logger.debug("No co-occurrences found for key '{}' in stitch index '{}'.", stitchLookupKey, stitchIndexName);
                }
            }
        } catch (IndexAccessException e) {
            String keyInfo = temporalCondition != null ?
                "prefix: " + ngramTerm + DELIMITER_CHAR :
                "key: " + ngramTerm + DELIMITER_CHAR + specificAnnotationTypeForLookup;
            throw new QueryExecutionException("IndexAccessException during stitch index lookup for " + keyInfo,
                                            e, corpusName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (IOException e) { // Catch IOException from deserializeWithFilters
            String keyInfo = temporalCondition != null ?
                "prefix: " + ngramTerm + DELIMITER_CHAR :
                "key: " + ngramTerm + DELIMITER_CHAR + specificAnnotationTypeForLookup;
            throw new QueryExecutionException("IOException during stitch data deserialization for " + keyInfo,
                                            e, corpusName, QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        // The logic for returning based on posListOpt needs to be adjusted as we now always have a PositionListSoA (it might be empty)
        // The original fallback was if the key itself wasn't found. Now, it's if the key isn't found OR if filtering makes it empty.
        if (resultSoA.isEmpty()) {
            String keyInfo = temporalCondition != null ?
                "prefix '" + ngramTerm + DELIMITER_CHAR + "' for temporal condition" :
                "key '" + ngramTerm + DELIMITER_CHAR + specificAnnotationTypeForLookup + "'";
            logger.info("StitchIntersectionExecutor: Stitch {} for N-gram '{}' and annotation type '{}' resulted in no matches after filtering. Optimization did not apply effectively. Fallback indicated.",
                        keyInfo, ngramTerm, specificAnnotationTypeForLookup);
            return null; // Indicate fallback
        } else {
            logger.info("StitchIntersectionExecutor finished for N-gram '{}' and annotation type '{}'. Found {} valid combined conceptual rows.",
                        ngramTerm, specificAnnotationTypeForLookup, resultSoA.getConceptualRowCount());
            return resultSoA;
        }
    }
}