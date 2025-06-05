package com.example.query.executor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationType;
import com.example.index.TypedAnnotationSynonymStore;
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
    private static final DateTimeFormatter STITCH_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public StitchIntersectionExecutor() {
        // Constructor
    }

    public QueryResultSoA execute(
            Contains containsCondition,
            Condition annotationCondition, // Ner, Pos, or Temporal (for DATE)
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Query currentQuery // Passed for context, e.g. if Temporal condition evaluation needs more than its own state
            ) throws QueryExecutionException {

        if (containsCondition.terms() == null || containsCondition.terms().size() != 1) {
            logger.warn("StitchIntersectionExecutor requires a single term for CONTAINS. Found: {}. Fallback.", containsCondition.terms());
            return null; // Fallback
        }
        String term = containsCondition.terms().get(0).toLowerCase();

        String stitchIndexGroupIdentifier;
        String specificAnnotationTypeForLookup; // For NER/POS, this is the entity type/tag. For DATE, it's the "date" identifier.
        String targetAnnotationValue = null;
        String annotationVarName;
        ValueType annotationValueType;
        AnnotationType expectedSynonymStoreType; // For validating the stitch index's synonym store

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
            expectedSynonymStoreType = AnnotationType.NER;
        } else if (annotationCondition instanceof Pos posCond) {
            stitchIndexGroupIdentifier = "pos"; // Assumes "stitch_unigram_pos"
            specificAnnotationTypeForLookup = posCond.posTag().toUpperCase();
            targetAnnotationValue = posCond.term();
            annotationVarName = posCond.variableName();
            annotationValueType = ValueType.TERM; // Or a more specific type for POS tags
            expectedSynonymStoreType = AnnotationType.POS;
        } else if (annotationCondition instanceof Temporal tempCond) {
            // User wants stitch key for DATE to be term<DELIMITER>date
            stitchIndexGroupIdentifier = "date"; // Corrected: group identifier is "date"
            specificAnnotationTypeForLookup = "date"; // Corrected: type for lookup is "date"
            temporalCondition = tempCond;
            annotationVarName = tempCond.qualifiedVariableName().orElse(null);
            annotationValueType = ValueType.DATE;
            expectedSynonymStoreType = AnnotationType.DATE; // Corrected: expected store type is DATE
        } else {
            logger.warn("Unsupported annotation condition type for stitch optimization: {}. Fallback.", annotationCondition.getType());
            return null; // Fallback
        }

        String stitchIndexName = "stitch_unigram_" + stitchIndexGroupIdentifier;
        IndexAccessInterface stitchIndex = indexes.get(stitchIndexName);

        if (stitchIndex == null) {
            logger.warn("Stitch index '{}' not found for CONTAINS-{} optimization. Ensure it's generated. Fallback.",
                        stitchIndexName, specificAnnotationTypeForLookup);
            return null; // Fallback
        }
        if (!stitchIndex.isOpen()) {
            logger.warn("Stitch index '{}' is not open. Fallback.", stitchIndexName);
            return null; // Fallback
        }

        TypedAnnotationSynonymStore synonymStore = stitchIndex.getSynonymStore()
            .orElseThrow(() -> new QueryExecutionException(
                String.format("Synonym store not found in stitch index '%s'. This is required for stitch execution.", stitchIndexName),
                corpusName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR));

        if (synonymStore.getManagedType() != expectedSynonymStoreType) {
            throw new QueryExecutionException(
                String.format("Synonym store in stitch index '%s' is for type %s, but expected %s for a %s condition.",
                              stitchIndexName, synonymStore.getManagedType(), expectedSynonymStoreType, annotationCondition.getType()),
                corpusName, QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        requirements.needsConceptualRowIds = true;
        Optional<PositionListSoA> posListOpt = Optional.empty(); // Initialize to ensure it's in scope for the final return

        String stitchLookupKey = term + DELIMITER_CHAR + specificAnnotationTypeForLookup;
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

                    String retrievedAnnotationText = synonymStore.getValue(specificAnnotationTextId);
                    if (retrievedAnnotationText == null) {
                        logger.warn("Null annotation text for synonymId {} from stitch key '{}'. Skipping.",
                                    specificAnnotationTextId, stitchLookupKey);
                        continue;
                    }

                    boolean valueMatch = true; // Default to true
                    Object conditionSpecificValue = null; // For adding to SoA

                    if (temporalCondition != null) {
                        // For DATE conditions, retrievedAnnotationText is YYYYMMDD string
                        try {
                            LocalDate dateFromStitch = LocalDate.parse(retrievedAnnotationText, STITCH_DATE_FORMAT);
                            conditionSpecificValue = dateFromStitch; // Store LocalDate for SoA
                            // Delegate to Temporal.matches() for consistent logic
                            if (!temporalCondition.matches(dateFromStitch.atStartOfDay())) {
                                valueMatch = false;
                            }
                        } catch (DateTimeParseException e) {
                            logger.warn("Could not parse date '{}' from stitch index synonym store for key '{}'. SynonymId: {}. Skipping.",
                                        retrievedAnnotationText, stitchLookupKey, specificAnnotationTextId, e);
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
                            term,
                            ValueType.TERM,
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
                                     term, specificAnnotationTypeForLookup, retrievedAnnotationText, conceptualRowId);
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
            logger.info("StitchIntersectionExecutor finished for term '{}' and annotation type '{}'. Found {} valid combined conceptual rows.",
                        term, specificAnnotationTypeForLookup, resultSoA.getConceptualRowCount());
            return resultSoA;
        } else {
            logger.info("StitchIntersectionExecutor: Stitch key not found for term '{}' and annotation type '{}'. Optimization did not apply for this key. Fallback indicated.",
                        term, specificAnnotationTypeForLookup);
            return null;
        }
    }
}