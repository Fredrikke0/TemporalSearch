package com.example.query.executor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Ner;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Executor for NER (Named Entity Recognition) conditions, excluding DATE.
 * Handles entity type matching and variable binding for named entities.
 * NER tags are keys in RocksDB, and entity values are stored as synonym IDs
 * in PositionListSoA, resolved via SynonymManager.
 */
public final class NerExecutor implements ConditionExecutor<Ner> {
    private static final Logger logger = LoggerFactory.getLogger(NerExecutor.class);

    private static final String NER_INDEX_NAME = "ner";
    private final SynonymManager synonymManager;

    /**
     * Creates a new NER executor.
     * @param synonymManager The synonym manager instance.
     */
    public NerExecutor(SynonymManager synonymManager) {
        this.synonymManager = synonymManager;
    }

    @Override
    public QueryResultSoA execute(Ner condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements)
        throws QueryExecutionException {

        logger.debug("Executing NER condition: {}, AttrReqs: {}", condition, requirements.getRequiredSoAAttributes());

        String entityType = condition.entityType();
        String normalizedEntityType = entityType.toUpperCase(); // NER types are generally stored/queried in uppercase

        // This executor specifically does not handle DATE entities.
        if ("DATE".equals(normalizedEntityType)) {
            throw new QueryExecutionException(
                "NER(DATE) queries should be handled by TemporalExecutor, not NerExecutor.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION
            );
        }

        String targetValue = condition.target(); // Can be null, original casing from query
        boolean isVariable = condition.isVariable();
        String variableName = condition.qualifiedVariableName(); // Null if not isVariable

        logger.debug("NER condition details: entityType='{}', target='{}', isVariable={}, variableName='{}'",
                     normalizedEntityType, targetValue, isVariable, variableName != null ? variableName : "(none)");

        if (normalizedEntityType.contains("*")) {
            throw new QueryExecutionException(
                "Wildcard in entity type ('" + entityType + "') is not supported.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }
        if (targetValue != null && targetValue.contains("*")) {
            throw new QueryExecutionException(
                "Wildcard in target value ('" + targetValue + "') is not supported.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
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
            if (isVariable) {
                logger.debug("NER path: Explicit Variable Binding. Type='{}', FilterTarget='{}', VarName='{}'",
                             normalizedEntityType, targetValue, variableName);
                conceptualRowsAdded = executeVariableBindingSearch(normalizedEntityType, targetValue, variableName, index, requirements, resultSoA);
            } else {
                if (targetValue != null) {
                    logger.debug("NER path: Specific Entity Filter (no BIND). Type='{}', TargetValue='{}'",
                                 normalizedEntityType, targetValue);
                    conceptualRowsAdded = executeSpecificEntityFilterSearch(normalizedEntityType, targetValue, index, requirements, resultSoA);
                } else {
                    logger.debug("NER path: Entity Type Only Search (no BIND). Type='{}'",
                                 normalizedEntityType);
                    conceptualRowsAdded = executeEntityTypeOnlySearch(normalizedEntityType, index, requirements, resultSoA);
                }
            }
            logger.debug("NER condition execution produced {} conceptual result rows, total SoA size: {}", conceptualRowsAdded, resultSoA.size());
            return resultSoA;

        } catch (IndexAccessException | IOException e) {
            throw new QueryExecutionException("Error accessing NER index: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (Exception e) { // Catch broader exceptions to ensure proper wrapping
            if (e instanceof QueryExecutionException qee) {
                throw qee;
            }
            throw new QueryExecutionException("Unexpected error executing NER condition: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }
    }

    /**
     * Handles NER(TYPE) queries, e.g., NER(PERSON).
     * The entity type string itself is stored as the value.
     */
    private int executeEntityTypeOnlySearch(String normalizedEntityType, IndexAccessInterface index,
                                            AttributeRequirements requirements, QueryResultSoA resultSoA)
        throws IOException, IndexAccessException {
        logger.debug("executeEntityTypeOnlySearch: Seeking for Type='{}'", normalizedEntityType);

        byte[] keyForIndexLookup = normalizedEntityType.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Optional<byte[]> rawBlobOptional = index.getRaw(keyForIndexLookup);

        if (!rawBlobOptional.isPresent() || rawBlobOptional.get().length == 0) {
            logger.debug("executeEntityTypeOnlySearch: No data found for entity type '{}'", normalizedEntityType);
            return 0;
        }

            byte[] rawBlob = rawBlobOptional.get();
            int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob);
        if (numPositions == 0) {
            logger.debug("executeEntityTypeOnlySearch: numPositions is 0 for type '{}', no positions to add.", normalizedEntityType);
            return 0;
        }

        int conceptualRowId = resultSoA.getNextConceptualRowId(); // All positions for this type share one conceptual ID
        int positionsAddedToSoa = 0;

        // For ENTITY_TYPE, we generally don't need synonym IDs, but we need other positional data.
        // AttributeRequirements should reflect this (needsSynonymIds=false typically for this path).
                    IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
                    IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
                    IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
                    IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
        // We don't need to decompress synonymIds as the value is the entityType itself.

                    for (int i = 0; i < numPositions; i++) {
                        resultSoA.add(
                normalizedEntityType, // Value is the entity type string
                ValueType.ENTITY_TYPE,
                null, // No variable name
                            docIds.getInt(i),
                            sentIds != null ? sentIds.getInt(i) : -1,
                            beginChars != null ? beginChars.getInt(i) : -1,
                            endChars != null ? endChars.getInt(i) : -1,
                -1, // No specific synonymId associated with the TYPE itself
                conceptualRowId
            );
            positionsAddedToSoa++;
        }
        logger.debug("executeEntityTypeOnlySearch for Type '{}' added {} positions to QueryResultSoA under conceptualRowId {}",
            normalizedEntityType, positionsAddedToSoa, conceptualRowId);
        return positionsAddedToSoa > 0 ? 1 : 0; // Returns 1 conceptual row if positions were added
    }

    /**
     * Handles NER(TYPE, 'targetText') queries (no variable binding), e.g., NER(PERSON, 'Smith').
     * Filters positions by the synonymId of 'targetText'.
     */
    private int executeSpecificEntityFilterSearch(String normalizedEntityType, String targetValueFromQuery,
                                                 IndexAccessInterface index, AttributeRequirements requirements,
                                                 QueryResultSoA resultSoA)
        throws IOException, IndexAccessException, QueryExecutionException {

        // For filtering by a specific entity value, we must have synonym IDs.
        if (!requirements.needsSynonymIds) {
            // This case should ideally be prevented by query planning ensuring requirements are set correctly.
            logger.warn("executeSpecificEntityFilterSearch called for Type='{}', Target='{}' but AttributeRequirements.needsSynonymIds is false. No filtering by synonym possible.",
                        normalizedEntityType, targetValueFromQuery);
            // Depending on strictness, could throw or return 0. Returning 0 for safety.
            return 0;
        }

        String normalizedTargetTerm = targetValueFromQuery.toLowerCase(); // NER terms are stored lowercase
        int targetSynonymId;
        try {
            targetSynonymId = synonymManager.getId(normalizedTargetTerm);
        } catch (RocksDBException e) {
            logger.error("RocksDBException while getting ID for term '{}' in executeSpecificEntityFilterSearch", normalizedTargetTerm, e);
            throw new IndexAccessException("Failed to get synonym ID for term: " + normalizedTargetTerm, NER_INDEX_NAME, IndexAccessException.ErrorType.READ_ERROR, e);
        } catch (IllegalArgumentException e) { // Should not happen with valid query inputs
             logger.error("IllegalArgumentException while getting ID for term '{}' in executeSpecificEntityFilterSearch", normalizedTargetTerm, e);
             throw new QueryExecutionException("Invalid term for synonym lookup: " + normalizedTargetTerm, e, "NerExecutor", QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        logger.debug("executeSpecificEntityFilterSearch: Type='{}', TargetValue='{}' (original), NormalizedTargetTerm='{}', TargetSynonymID={}",
            normalizedEntityType, targetValueFromQuery, normalizedTargetTerm, targetSynonymId);


        byte[] keyForIndexLookup = normalizedEntityType.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Optional<byte[]> rawBlobOptional = index.getRaw(keyForIndexLookup);

        if (!rawBlobOptional.isPresent() || rawBlobOptional.get().length == 0) {
            logger.debug("executeSpecificEntityFilterSearch: No data found for entity type '{}'", normalizedEntityType);
                return 0;
            }

        byte[] rawBlob = rawBlobOptional.get();
        int numPositionsTotal = PositionListSoA.getNumPositionsFromBlob(rawBlob);
        if (numPositionsTotal == 0) return 0;

        // Must decompress synonym IDs for filtering
        IntArrayList synonymIds = PositionListSoA.decompressSynonymIds(rawBlob);
        if (synonymIds == null || synonymIds.isEmpty()) { // Should not happen if numPositionsTotal > 0 and index is well-formed
             logger.warn("executeSpecificEntityFilterSearch: No synonym IDs found in blob for type '{}' despite {} positions. Inconsistent data?", normalizedEntityType, numPositionsTotal);
             return 0;
        }

        IntArrayList docIds = null;
        IntArrayList sentIds = null;
        IntArrayList beginChars = null;
        IntArrayList endChars = null;

        int conceptualRowId = -1; // Assigned when first match found
        int positionsAddedToSoa = 0;

        for (int i = 0; i < numPositionsTotal; i++) {
            if (synonymIds.getInt(i) == targetSynonymId) {
                if (conceptualRowId == -1) { // First match for this target value
                    conceptualRowId = resultSoA.getNextConceptualRowId();
                    // Decompress other attributes only now, if needed, to save work
                    docIds = PositionListSoA.decompressDocIds(rawBlob); // Always needed
                    sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
                    beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
                    endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
                }
                        resultSoA.add(
                    targetValueFromQuery, // Store original casing from query
                    ValueType.ENTITY,
                    null, // No variable name
                            docIds.getInt(i),
                            sentIds != null ? sentIds.getInt(i) : -1,
                            beginChars != null ? beginChars.getInt(i) : -1,
                            endChars != null ? endChars.getInt(i) : -1,
                    targetSynonymId,
                    conceptualRowId
                );
                positionsAddedToSoa++;
            }
        }
        logger.debug("executeSpecificEntityFilterSearch for Type '{}', Target '{}' added {} positions to QueryResultSoA under conceptualRowId {}",
            normalizedEntityType, targetValueFromQuery, positionsAddedToSoa, conceptualRowId != -1 ? conceptualRowId : "(none)");
        return positionsAddedToSoa > 0 ? 1 : 0; // Returns 1 conceptual row if positions were added
    }

    /**
     * Handles NER(TYPE) BIND ?var and NER(TYPE, 'targetText') BIND ?var queries.
     * Resolves synonym IDs to terms for binding.
     */
    private int executeVariableBindingSearch(String normalizedEntityType, String filterTargetValueFromQuery, // Original casing from query, can be null
                                            String queryVariableName, IndexAccessInterface index,
                                            AttributeRequirements requirements, QueryResultSoA resultSoA)
        throws IOException, IndexAccessException, QueryExecutionException {

        if (!requirements.needsSynonymIds) {
            logger.warn("executeVariableBindingSearch called for Type='{}', Var='{}', TargetFilter='{}' but AttributeRequirements.needsSynonymIds is false. This will likely yield no results or incorrect behavior for variable binding.",
                        normalizedEntityType, queryVariableName, filterTargetValueFromQuery);
            // Depending on strictness, could return 0 or throw an error.
            // For now, proceed but expect potential issues if synonym IDs are crucial later.
        }
        logger.debug("Executing executeVariableBindingSearch: Type='{}', Var='{}', TargetFilter='{}'",
                     normalizedEntityType, queryVariableName, filterTargetValueFromQuery);

        Optional<Integer> filterTargetSynonymId = Optional.empty();
        if (filterTargetValueFromQuery != null) {
            try {
                // Normalize the filter target value for lookup in SynonymManager
                String normalizedFilterTarget = filterTargetValueFromQuery.toLowerCase();
                int synId = synonymManager.getId(normalizedFilterTarget);
                filterTargetSynonymId = Optional.of(synId);
                logger.debug("TargetFilter '{}' (normalized: '{}') resolved to synonymId: {}",
                             filterTargetValueFromQuery, normalizedFilterTarget, synId);
            } catch (RocksDBException e) {
                logger.error("RocksDBException while getting synonymId for TargetFilter '{}' in variable binding search for Type '{}'", filterTargetValueFromQuery, normalizedEntityType, e);
                throw new IndexAccessException("Failed to get synonymId for target filter: " + filterTargetValueFromQuery, NER_INDEX_NAME, IndexAccessException.ErrorType.READ_ERROR, e);
            } catch (IllegalArgumentException e) {
                 logger.error("IllegalArgumentException (e.g. null/empty term) for TargetFilter '{}' in variable binding for Type '{}'", filterTargetValueFromQuery, normalizedEntityType, e);
                throw new QueryExecutionException("Invalid target value for NER variable binding: " + filterTargetValueFromQuery, e, "NerExecutor", QueryExecutionException.ErrorType.INVALID_CONDITION);
            }
        }

        byte[] keyForIndexLookup = normalizedEntityType.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Optional<byte[]> rawBlobOptional = index.getRaw(keyForIndexLookup);

        if (!rawBlobOptional.isPresent() || rawBlobOptional.get().length == 0) {
            logger.debug("executeVariableBindingSearch: No data found in index for entity type '{}' (key: [{}])",
                         normalizedEntityType, new String(keyForIndexLookup, java.nio.charset.StandardCharsets.UTF_8));
                return 0;
            }

        byte[] rawBlob = rawBlobOptional.get();
        int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob);
        logger.debug("executeVariableBindingSearch: Blob found for '{}'. numPositions from blob: {}", normalizedEntityType, numPositions);

        if (numPositions == 0) {
            logger.debug("executeVariableBindingSearch: numPositions is 0 for entity type '{}', returning 0 conceptual rows.", normalizedEntityType);
            return 0;
        }

                    IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
        IntArrayList sentenceIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
                    IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
                    IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
        IntArrayList synonymIds = PositionListSoA.decompressSynonymIds(rawBlob);

        logger.trace("executeVariableBindingSearch: Decompressed raw arrays. DocIds size: {}, SynonymIds size: {}", docIds.size(), synonymIds.size());

        Map<String, Integer> resolvedTermToConceptualRowId = new HashMap<>();
        Map<Integer, String> resolvedSynonymIdToTermCache = new HashMap<>(); // Cache for term resolution

                    for (int i = 0; i < numPositions; i++) {
            int currentSynonymId = synonymIds.getInt(i);
            logger.trace("executeVariableBindingSearch: Processing position {}, currentSynonymId: {}", i, currentSynonymId);

            if (filterTargetSynonymId.isPresent() && filterTargetSynonymId.get() != currentSynonymId) {
                logger.trace("executeVariableBindingSearch: Position {} (synId {}) skipped due to filterTargetSynonymId mismatch (expected {}).",
                             i, currentSynonymId, filterTargetSynonymId.get());
                continue;
            }

            try {
                String term;
                if (resolvedSynonymIdToTermCache.containsKey(currentSynonymId)) {
                    term = resolvedSynonymIdToTermCache.get(currentSynonymId);
                    logger.trace("executeVariableBindingSearch: Term '{}' for synId {} found in cache.", term, currentSynonymId);
                } else {
                    Optional<String> termOptional = synonymManager.getTerm(currentSynonymId);
                    if (!termOptional.isPresent()) {
                        logger.warn("executeVariableBindingSearch: No term found for synonymId {} at position {}. Skipping.", currentSynonymId, i);
                        continue;
                    }
                    term = termOptional.get();
                    resolvedSynonymIdToTermCache.put(currentSynonymId, term);
                    logger.trace("executeVariableBindingSearch: Resolved synId {} to term '{}' and cached it.", currentSynonymId, term);
                }

                // Use original casing if available (from filterTargetValueFromQuery), otherwise use resolved term
                String valueToBind = (filterTargetValueFromQuery != null && filterTargetSynonymId.isPresent() && filterTargetSynonymId.get() == currentSynonymId)
                                    ? filterTargetValueFromQuery
                                    : term;

                int conceptualRowId = resolvedTermToConceptualRowId.computeIfAbsent(valueToBind, k -> {
                    int newConceptualId = resultSoA.getNextConceptualRowId(); // Use QueryResultSoA's ID generator
                    logger.trace("executeVariableBindingSearch: New conceptual row for term '{}' (valueToBind: '{}'). Assigning new conceptualRowId: {}",
                                 term, valueToBind, newConceptualId);
                    return newConceptualId;
                });

                logger.trace("executeVariableBindingSearch: Adding to resultSoA. ConceptualRowId: {}, VarName: '{}', Value: '{}', ValueType: ENTITY, DocId: {}",
                    conceptualRowId, queryVariableName, valueToBind, docIds.getInt(i));

                resultSoA.add(
                    valueToBind,                  // Object value
                    ValueType.ENTITY,             // ValueType valueType
                    queryVariableName,            // String variableName
                    docIds.getInt(i),             // int documentId
                    requirements.needsSentenceId ? sentenceIds.getInt(i) : -1, // int sentenceId
                    requirements.needsPositions ? beginChars.getInt(i) : -1,   // int beginChar
                    requirements.needsPositions ? endChars.getInt(i) : -1,     // int endChar
                    currentSynonymId,             // int synonymId (Store the synonymId of the resolved entity)
                    conceptualRowId               // int conceptualRowId
                );
            } catch (RocksDBException e) {
                logger.error("RocksDBException while resolving term for synonymId {} in variable binding search for Type '{}'", currentSynonymId, normalizedEntityType, e);
                throw new IndexAccessException("Failed to resolve term for synonymId: " + currentSynonymId, NER_INDEX_NAME, IndexAccessException.ErrorType.READ_ERROR, e);
            }
        }
        int conceptualRowsAddedCount = resolvedTermToConceptualRowId.size();
        logger.debug("executeVariableBindingSearch for Type '{}' completed. Added {} conceptual rows and {} total bindings to QueryResultSoA.",
                     normalizedEntityType, conceptualRowsAddedCount, resultSoA.size());
        return conceptualRowsAddedCount;
    }
}