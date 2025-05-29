package com.example.query.executor;

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
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Ner;

/**
 * Executes a stitched query for a CONTAINS (unigram) condition AND-ed with a NER condition.
 */
public class StitchContainsNerExecutor {
    private static final Logger logger = LoggerFactory.getLogger(StitchContainsNerExecutor.class);
    private static final char NER_SYNONYM_DELIMITER = com.example.core.IndexAccessInterface.DELIMITER; // \0

    public StitchContainsNerExecutor() {
        // Constructor, if needed for future dependencies
    }

    public QueryResultSoA execute(
            Contains containsCondition,
            Ner nerCondition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements) throws QueryExecutionException {

        logger.info("StitchContainsNerExecutor executing for: Contains='{}', NER='{}', Granularity='{}', Corpus='{}'",
                    containsCondition.terms().get(0), nerCondition.entityType(), granularity, corpusName);

        // 1. Validate containsCondition (unigram) - Already done by QueryExecutor, but good for safety
        if (containsCondition.terms() == null || containsCondition.terms().size() != 1) {
            logger.warn("StitchContainsNerExecutor requires a single term for CONTAINS. Found: {}. This should have been caught by QueryExecutor.", containsCondition.terms());
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }
        String term = containsCondition.terms().get(0).toLowerCase();

        String stitchIndexName = "stitch-ner";
        IndexAccessInterface stitchIndex = indexes.get(stitchIndexName);
        if (stitchIndex == null) {
            logger.warn("Stitch index '{}' not found for CONTAINS-NER optimization.", stitchIndexName);
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }
        if (!stitchIndex.isOpen()) {
            logger.warn("Stitch index '{}' is not open.", stitchIndexName);
            return new QueryResultSoA(granularity, granularitySize, requirements);
        }

        TypedAnnotationSynonymStore synonymStore = stitchIndex.getSynonymStore()
            .orElseThrow(() -> new QueryExecutionException(
                String.format("Synonym store not found in stitch index '%s'.", stitchIndexName),
                corpusName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR));

        if (synonymStore.getManagedType() != AnnotationType.NER) {
            throw new QueryExecutionException(
                String.format("Synonym store in stitch index '%s' is for type %s, but expected %s.",
                              stitchIndexName, synonymStore.getManagedType(), AnnotationType.NER),
                corpusName, QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        requirements.needsConceptualRowIds = true; // Ensure conceptual IDs are handled

        try {
            Optional<PositionListSoA> posListOpt = stitchIndex.get(term.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            if (posListOpt.isPresent()) {
                PositionListSoA positions = posListOpt.get();
                logger.debug("Found {} co-occurrences of term '{}' in stitch-ner index.", positions.getNumPositions(), term);

                for (int i = 0; i < positions.getNumPositions(); i++) {
                    int docId = positions.getDocIdAt(i);
                    int sentenceId = positions.getSentenceIdAt(i);
                    int unigramBeginChar = positions.getBeginCharAt(i);
                    int unigramEndChar = positions.getEndCharAt(i);
                    int stitchedSynonymId = positions.getSynonymIdAt(i);

                    String stitchedAnnotationValue = synonymStore.getValue(stitchedSynonymId);
                    if (stitchedAnnotationValue == null) {
                        logger.warn("Null stitched annotation value for synonymId {} from term '{}'. Skipping.", stitchedSynonymId, term);
                        continue;
                    }

                    // Parse: UPPERCASE_ENTITY_TYPE<NULL_BYTE>lowercase_entity_text
                    int delimiterPos = stitchedAnnotationValue.indexOf(NER_SYNONYM_DELIMITER);
                    if (delimiterPos == -1) {
                        logger.warn("Invalid stitched NER annotation format (missing delimiter '{}'): '{}'. SynonymId: {}. Skipping.",
                                    (int)NER_SYNONYM_DELIMITER, stitchedAnnotationValue, stitchedSynonymId);
                        continue;
                    }
                    String parsedEntityType = stitchedAnnotationValue.substring(0, delimiterPos);
                    String parsedEntityText = stitchedAnnotationValue.substring(delimiterPos + 1);

                    // Validate against nerCondition
                    boolean typeMatch = parsedEntityType.equals(nerCondition.entityType().toUpperCase());
                    boolean textMatch = nerCondition.target() == null || nerCondition.target().equalsIgnoreCase(parsedEntityText);

                    if (typeMatch && textMatch) {
                        int conceptualRowId = resultSoA.getNextConceptualRowId();

                        // Add entry for CONTAINS part
                        resultSoA.add(
                            term,
                            ValueType.TERM,
                            containsCondition.variableName(),
                            docId, sentenceId,
                            unigramBeginChar, unigramEndChar,
                            -1, // No specific synonym ID for the plain term in this context
                            conceptualRowId
                        );

                        // Add entry for NER part
                        resultSoA.add(
                            parsedEntityText,
                            ValueType.ENTITY,
                            nerCondition.variableName(),
                            docId, sentenceId,
                            -1, -1, // Sentinel coordinates for stitched annotation
                            stitchedSynonymId,
                            conceptualRowId
                        );
                        logger.trace("Added match: term='{}', nerType='{}', nerText='{}', conceptualId={}", term, parsedEntityType, parsedEntityText, conceptualRowId);
                    }
                }
            }
        } catch (IndexAccessException e) {
            throw new QueryExecutionException("IndexAccessException during stitch index lookup for term: " + term, e, corpusName, QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }
        logger.info("StitchContainsNerExecutor finished for term '{}'. Found {} valid combined matches.", term, resultSoA.getConceptualRowCount());
        return resultSoA;
    }
}