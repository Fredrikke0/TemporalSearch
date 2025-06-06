package com.example.query.executor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationType;
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
    private static final DateTimeFormatter STITCH_DATE_PARSE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd"); // For parsing what's retrieved

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
            Query currentQuery // Passed for context, e.g. if Temporal condition evaluation needs more than its own state
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
                // For Contains with >3 terms, we might not have a direct stitch_quadgram_... index.
                // The standard N-gram indexes (unigram, bigram, trigram) are primary. Stitch follows this.
            }
        }

        String stitchIndexGroupIdentifier;
        String specificAnnotationTypeForLookup; // For NER/POS, this is the entity type/tag. For DATE, it's the "date" identifier.
        String targetAnnotationValue = null;
        String annotationVarName;
        ValueType annotationValueType;
        AnnotationType expectedAnnotationType; // Renamed from expectedSynonymStoreType

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
            expectedAnnotationType = AnnotationType.NER;
        } else if (annotationCondition instanceof Pos posCond) {
            stitchIndexGroupIdentifier = "pos"; // Assumes "stitch_unigram_pos"
            specificAnnotationTypeForLookup = posCond.posTag().toUpperCase();
            targetAnnotationValue = posCond.term();
            annotationVarName = posCond.variableName();
            annotationValueType = ValueType.TERM; // Or a more specific type for POS tags
            expectedAnnotationType = AnnotationType.POS;
        } else if (annotationCondition instanceof Temporal tempCond) {
            // User wants stitch key for DATE to be term<DELIMITER>date
            stitchIndexGroupIdentifier = "date"; // Corrected: group identifier is "date"
            specificAnnotationTypeForLookup = "date"; // Corrected: type for lookup is "date"
            temporalCondition = tempCond;
            annotationVarName = tempCond.qualifiedVariableName().orElse(null);
            annotationValueType = ValueType.DATE;
            expectedAnnotationType = AnnotationType.DATE; // Corrected: expected store type is DATE
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

        // Validate the AnnotationType of the stitch index itself
        AnnotationType actualIndexAnnotationType = stitchIndex.getAnnotationType();
        if (actualIndexAnnotationType == AnnotationType.UNKNOWN && expectedAnnotationType != AnnotationType.UNKNOWN) {
             logger.warn("Stitch index '{}' has an UNKNOWN annotation type. Expected {} for a {} condition. Proceeding with caution.",
                              stitchIndexName, expectedAnnotationType, annotationCondition.getType());
            // Decide if this is a fatal error or a warning. For now, warning.
        } else if (actualIndexAnnotationType != expectedAnnotationType && expectedAnnotationType != AnnotationType.UNKNOWN) {
            // Allow if expected is UNKNOWN (e.g. if stitch index is generic)
            // but if we expect a specific type (NER, POS, DATE) and get something else (and not UNKNOWN), it's an error.
             throw new QueryExecutionException(
                String.format("Stitch index '%s' provides annotation type %s, but expected %s for a %s condition.",
                              stitchIndexName, actualIndexAnnotationType, expectedAnnotationType, annotationCondition.getType()),
                corpusName, QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        requirements.needsConceptualRowIds = true;
        Optional<PositionListSoA> posListOpt = Optional.empty(); // Initialize to ensure it's in scope for the final return

        String stitchLookupKey = ngramTerm + DELIMITER_CHAR + specificAnnotationTypeForLookup;
        logger.debug("Looking up in stitch index '{}' with key: '{}'", stitchIndexName, stitchLookupKey);

        try {
            posListOpt = stitchIndex.get(stitchLookupKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            if (posListOpt.isPresent()) {
                PositionListSoA positions = posListOpt.get();
                logger.debug("Found {} potential co-occurrences for key '{}' in stitch index '{}'.",
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
                    Object conditionSpecificValue = null; // For adding to SoA

                    if (temporalCondition != null) {
                        // For DATE conditions, retrievedAnnotationText is expected to be YYYYMMDD string from SynonymManager
                        try {
                            LocalDate dateFromStitch = LocalDate.parse(retrievedAnnotationText, STITCH_DATE_PARSE_FORMAT);
                            conditionSpecificValue = dateFromStitch; // Store LocalDate for SoA
                            // Delegate to Temporal.matches() for consistent logic
                            if (!temporalCondition.matches(dateFromStitch.atStartOfDay())) {
                                valueMatch = false;
                            }
                        } catch (DateTimeParseException e) {
                            logger.warn("Could not parse date '{}' from stitch index synonym store (ID: {}). Expected format yyyyMMdd. Key '{}'. Skipping.",
                                        retrievedAnnotationText, specificAnnotationTextId, stitchLookupKey, e);
                            valueMatch = false;
                        }
                    } else {
                        // For NER/POS, check against targetAnnotationValue if provided
                        conditionSpecificValue = retrievedAnnotationText; // Store String for SoA
                        if (targetAnnotationValue != null && !targetAnnotationValue.equalsIgnoreCase(retrievedAnnotationText)) {
                            valueMatch = false;
                        }
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
                                conditionSpecificValue, // This is LocalDate for DATE, String for NER/POS
                                annotationValueType,
                                annotationVarName,
                                docId, sentenceId,
                                -1, -1, // Placeholder coordinates for the annotation part from stitch
                                specificAnnotationTextId,
                                conceptualRowId
                            );
                        } else if (annotationCondition instanceof Temporal && annotationVarName == null){
                            logger.trace("DATE condition was met but not bound to a variable for conceptualRowId {}", conceptualRowId);
                        }


                        logger.trace("Added stitched match: term='{}', annotationType='{}', annotationText='{}' (or date), conceptualId={}",
                                     ngramTerm, specificAnnotationTypeForLookup, retrievedAnnotationText, conceptualRowId);
                    }
                }
            } else {
                 logger.debug("No co-occurrences found for key '{}' in stitch index '{}'.", stitchLookupKey, stitchIndexName);
            }
        } catch (IndexAccessException e) {
            throw new QueryExecutionException("IndexAccessException during stitch index lookup for key: " + stitchLookupKey,
                                            e, corpusName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }

        if (posListOpt.isPresent()) {
            logger.info("StitchIntersectionExecutor finished for N-gram '{}' and annotation type '{}'. Found {} valid combined conceptual rows.",
                        ngramTerm, specificAnnotationTypeForLookup, resultSoA.getConceptualRowCount());
            return resultSoA;
        } else {
            logger.info("StitchIntersectionExecutor: Stitch key not found for N-gram '{}' and annotation type '{}'. Optimization did not apply for this key. Fallback indicated.",
                        ngramTerm, specificAnnotationTypeForLookup);
            return null;
        }
    }
}