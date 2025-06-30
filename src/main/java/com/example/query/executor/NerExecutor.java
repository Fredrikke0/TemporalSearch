package com.example.query.executor;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

/**
 * Executor for NER (Named Entity Recognition) conditions, excluding DATE.
 * Handles entity type matching and variable binding for named entities.
 * NER tags are keys in RocksDB, and entity values are stored as synonym IDs
 * in PositionListSoA, resolved via SynonymManager.
 *
 * Supports pushdown optimization via the targets field for improved join performance.
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
                               AttributeRequirements requirements,
                               Optional<FilteringContext> context)
        throws QueryExecutionException {
        logger.debug(">>> Executing NerExecutor");
        logger.debug("Executing NER condition: {}, AttrReqs: {}, ContextIsPresent: {}",
                     condition, requirements, context.isPresent());

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

        List<String> targetValues = condition.targets(); // Can be empty list
        boolean isVariable = condition.isVariable();
        String variableName = condition.qualifiedVariableName(); // Null if not isVariable

        logger.debug("NER condition details: entityType='{}', targets={}, isVariable={}, variableName='{}'",
                     normalizedEntityType, targetValues, isVariable, variableName != null ? variableName : "(none)");

        if (normalizedEntityType.contains("*")) {
            throw new QueryExecutionException(
                "Wildcard in entity type ('" + entityType + "') is not supported.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }

        for (String target : targetValues) {
            if (target != null && target.contains("*")) {
                throw new QueryExecutionException(
                    "Wildcard in target value ('" + target + "') is not supported.",
                    condition.toString(),
                    QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
            }
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
                logger.debug("NER path: Explicit Variable Binding. Type='{}', FilterTargets={}, VarName='{}'",
                             normalizedEntityType, targetValues, variableName);
                conceptualRowsAdded = executeVariableBindingSearch(normalizedEntityType, targetValues, variableName, index, requirements, resultSoA, context);
            } else {
                if (!targetValues.isEmpty()) {
                    logger.debug("NER path: Specific Entity Filter (no BIND). Type='{}', TargetValues={}",
                                 normalizedEntityType, targetValues);
                    conceptualRowsAdded = executeSpecificEntityFilterSearch(normalizedEntityType, targetValues, index, requirements, resultSoA, context);
                } else {
                    logger.debug("NER path: Entity Type Only Search (no BIND). Type='{}'",
                                 normalizedEntityType);
                    conceptualRowsAdded = executeEntityTypeOnlySearch(normalizedEntityType, index, requirements, resultSoA, context);
                }
            }
            logger.debug("NER condition execution produced {} conceptual result rows, total SoA size: {}", conceptualRowsAdded, resultSoA.size());

            // Sort by document ID to ensure merge join optimization works correctly
            resultSoA.sort();

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
                                            AttributeRequirements requirements, QueryResultSoA resultSoA,
                                            Optional<FilteringContext> context)
        throws IOException, IndexAccessException {
        logger.debug("executeEntityTypeOnlySearch: Seeking for Type='{}', ContextIsPresent={}", normalizedEntityType, context.isPresent());

        byte[] keyForIndexLookup = normalizedEntityType.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Optional<byte[]> rawBlobOptional = index.getRaw(keyForIndexLookup);

        if (!rawBlobOptional.isPresent() || rawBlobOptional.get().length == 0) {
            logger.debug("executeEntityTypeOnlySearch: No data found for entity type '{}'", normalizedEntityType);
            return 0;
        }

        byte[] rawBlob = rawBlobOptional.get();
        PositionListSoA positions = PositionListSoA.deserializeWithFilters(rawBlob, requirements, context);

        if (positions.isEmpty()) {
            logger.debug("executeEntityTypeOnlySearch: No positions for type '{}' after context filtering.", normalizedEntityType);
            return 0;
        }
        int numPositions = positions.getNumPositions();

        int conceptualRowId = resultSoA.getNextConceptualRowId();
        int positionsAddedToSoa = 0;

        for (int i = 0; i < numPositions; i++) {
            resultSoA.add(
                normalizedEntityType,
                ValueType.ENTITY_TYPE,
                null,
                positions.getDocIdAt(i),
                requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                -1,
                conceptualRowId
            );
            positionsAddedToSoa++;
        }
        logger.debug("executeEntityTypeOnlySearch for Type '{}' added {} positions to QueryResultSoA under conceptualRowId {}",
            normalizedEntityType, positionsAddedToSoa, conceptualRowId);
        return positionsAddedToSoa > 0 ? 1 : 0; // Returns 1 conceptual row if positions were added
    }

    /**
     * Handles NER(TYPE, target) queries where specific entity text is sought.
     * No variable binding is done.
     */
    private int executeSpecificEntityFilterSearch(String normalizedEntityType, List<String> targetValuesFromQuery,
                                                 IndexAccessInterface index, AttributeRequirements requirements,
                                                 QueryResultSoA resultSoA,
                                                 Optional<FilteringContext> context)
        throws IOException, IndexAccessException, QueryExecutionException {

        if (targetValuesFromQuery.isEmpty()) {
            logger.warn("executeSpecificEntityFilterSearch called for Type='{}' with empty targetValuesFromQuery. This might yield unexpected behavior.",
                        normalizedEntityType);
            // Depending on strictness, could throw or return 0. Returning 0 for safety.
            return 0;
        }

        // Resolve all target values to synonym IDs and store the mapping
        Set<Integer> targetSynonymIds = new HashSet<>();
        Map<Integer, String> synonymIdToOriginalTarget = new HashMap<>();
        for (String targetValue : targetValuesFromQuery) {
            String normalizedTargetTerm = targetValue.toLowerCase(); // NER terms are stored lowercase
            try {
                int targetSynonymId = synonymManager.getId(normalizedTargetTerm);
                targetSynonymIds.add(targetSynonymId);
                synonymIdToOriginalTarget.put(targetSynonymId, targetValue); // Store original casing
            } catch (RocksDBException e) {
                logger.error("RocksDBException while getting ID for term '{}' in executeSpecificEntityFilterSearch", normalizedTargetTerm, e);
                throw new IndexAccessException("Failed to get synonym ID for term: " + normalizedTargetTerm, NER_INDEX_NAME, IndexAccessException.ErrorType.READ_ERROR, e);
            } catch (IllegalArgumentException e) { // Should not happen with valid query inputs
                 logger.error("IllegalArgumentException while getting ID for term '{}' in executeSpecificEntityFilterSearch", normalizedTargetTerm, e);
                 throw new QueryExecutionException("Invalid term for synonym lookup: " + normalizedTargetTerm, e, "NerExecutor", QueryExecutionException.ErrorType.INTERNAL_ERROR);
            }
        }

        if (targetSynonymIds.isEmpty()) {
            logger.debug("executeSpecificEntityFilterSearch: No valid synonym IDs found for targets {}", targetValuesFromQuery);
            return 0;
        }

        logger.debug("executeSpecificEntityFilterSearch: Type='{}', TargetValues={}, TargetSynonymIDs={}",
            normalizedEntityType, targetValuesFromQuery, targetSynonymIds);

        byte[] keyForIndexLookup = normalizedEntityType.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Optional<byte[]> rawBlobOptional = index.getRaw(keyForIndexLookup);

        if (!rawBlobOptional.isPresent() || rawBlobOptional.get().length == 0) {
            logger.debug("executeSpecificEntityFilterSearch: No data found for entity type '{}'", normalizedEntityType);
                return 0;
            }

        byte[] rawBlob = rawBlobOptional.get();
        PositionListSoA positions = PositionListSoA.deserializeWithFilters(rawBlob, requirements, context);

        if (positions.isEmpty()) {
            logger.debug("executeSpecificEntityFilterSearch: No positions for type '{}' after initial context filtering.", normalizedEntityType);
            return 0;
        }

        Map<Integer, Integer> synonymIdToConceptualRowId = new HashMap<>();
        int positionsAddedToSoa = 0;
        int initialNumPositions = positions.getNumPositions();

        for (int i = 0; i < initialNumPositions; i++) {
            int currentSynonymId = positions.getSynonymIdAt(i);
            if (targetSynonymIds.contains(currentSynonymId)) {
                int conceptualRowId = synonymIdToConceptualRowId.computeIfAbsent(currentSynonymId, k -> resultSoA.getNextConceptualRowId());

                // Get the original target value for this synonym ID from our pre-computed mapping
                String targetValueToStore = synonymIdToOriginalTarget.get(currentSynonymId);
                if (targetValueToStore == null) {
                    // Fallback to first target if somehow not found
                    targetValueToStore = targetValuesFromQuery.get(0);
                }

                resultSoA.add(
                    targetValueToStore,
                    ValueType.ENTITY,
                    null,
                    positions.getDocIdAt(i),
                    requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                    requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                    requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                    currentSynonymId,
                    conceptualRowId
                );
                positionsAddedToSoa++;
            }
        }
        logger.debug("executeSpecificEntityFilterSearch for Type '{}', Targets {} (synIds {}), added {} positions to QueryResultSoA with {} conceptual rows after all filtering.",
                     normalizedEntityType, targetValuesFromQuery, targetSynonymIds, positionsAddedToSoa, synonymIdToConceptualRowId.size());
        return synonymIdToConceptualRowId.size();
    }

    /**
     * Handles NER(TYPE) BIND var and NER(TYPE, [targets...]) BIND var queries.
     * Resolves synonym IDs to terms for binding.
     */
    private int executeVariableBindingSearch(String normalizedEntityType, List<String> filterTargetValuesFromQuery, // Original casing from query, can be empty
                                            String queryVariableName, IndexAccessInterface index,
                                            AttributeRequirements requirements, QueryResultSoA resultSoA,
                                            Optional<FilteringContext> context)
        throws IOException, IndexAccessException, QueryExecutionException {

        if (!requirements.needsSynonymIds) {
            logger.warn("executeVariableBindingSearch called for Type='{}', Var='{}', TargetFilter={} but AttributeRequirements.needsSynonymIds is false. This will likely yield no results or incorrect behavior for variable binding.",
                        normalizedEntityType, queryVariableName, filterTargetValuesFromQuery);
            // Depending on strictness, could return 0 or throw an error.
            // For now, proceed but expect potential issues if synonym IDs are crucial later.
        }
        logger.debug("Executing executeVariableBindingSearch: Type='{}', Var='{}', TargetFilter={}, ContextIsPresent={}",
                     normalizedEntityType, queryVariableName, filterTargetValuesFromQuery, context.isPresent());

        // Resolve filter target values to synonym IDs if provided
        Set<Integer> filterTargetSynonymIds = new HashSet<>();
        Map<Integer, String> filterSynonymIdToOriginalTarget = new HashMap<>();
        if (!filterTargetValuesFromQuery.isEmpty()) {
            for (String filterTarget : filterTargetValuesFromQuery) {
                try {
                    // Normalize the filter target value for lookup in SynonymManager
                    String normalizedFilterTarget = filterTarget.toLowerCase();
                    int synId = synonymManager.getId(normalizedFilterTarget);
                    filterTargetSynonymIds.add(synId);
                    filterSynonymIdToOriginalTarget.put(synId, filterTarget); // Store original casing
                    // logger.debug("TargetFilter '{}' (normalized: '{}') resolved to synonymId: {}",
                    //              filterTarget, normalizedFilterTarget, synId);
                } catch (RocksDBException e) {
                    logger.error("RocksDBException while getting synonymId for TargetFilter '{}' in variable binding search for Type '{}'", filterTarget, normalizedEntityType, e);
                    throw new IndexAccessException("Failed to get synonymId for target filter: " + filterTarget, NER_INDEX_NAME, IndexAccessException.ErrorType.READ_ERROR, e);
                } catch (IllegalArgumentException e) {
                     logger.error("IllegalArgumentException (e.g. null/empty term) for TargetFilter '{}' in variable binding for Type '{}'", filterTarget, normalizedEntityType, e);
                    throw new QueryExecutionException("Invalid target value for NER variable binding: " + filterTarget, e, "NerExecutor", QueryExecutionException.ErrorType.INVALID_CONDITION);
                }
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
        PositionListSoA positions = PositionListSoA.deserializeWithFilters(rawBlob, requirements, context);

        logger.trace("executeVariableBindingSearch: Decompressed raw arrays. DocIds size: {}, SynonymIds size: {}", positions.getNumPositions(), positions.getSynonymIds().size());

        Map<String, Integer> resolvedTermToConceptualRowId = new HashMap<>();

        int conceptualRowsAdded = 0;
        int initialNumPositions = positions.getNumPositions();

        // Step 1: Collect unique synonym IDs
        Set<Integer> uniqueSynonymIds = new HashSet<>();
        if (initialNumPositions > 0 && requirements.needsSynonymIds) { // Only collect if needed and positions exist
            for (int i = 0; i < initialNumPositions; i++) {
                int synonymId = positions.getSynonymIdAt(i);
                // Apply filter if targets were specified
                if (filterTargetSynonymIds.isEmpty() || filterTargetSynonymIds.contains(synonymId)) {
                    uniqueSynonymIds.add(synonymId);
                }
            }
        }

        // Step 2: Fetch terms in a batch
        Map<Integer, String> resolvedTermsCache = Collections.emptyMap(); // Default to empty
        if (!uniqueSynonymIds.isEmpty()) {
            try {
                resolvedTermsCache = synonymManager.getTerms(uniqueSynonymIds);
            } catch (RocksDBException e) {
                logger.error("RocksDBException while batch fetching terms in variable binding search for Type '{}'", normalizedEntityType, e);
                throw new IndexAccessException("Failed to batch fetch terms from SynonymManager", NER_INDEX_NAME, IndexAccessException.ErrorType.READ_ERROR, e);
            }
        }

        for (int i = 0; i < initialNumPositions; i++) {
            int currentSynonymId = positions.getSynonymIdAt(i);
            logger.trace("executeVariableBindingSearch: Processing position {}, currentSynonymId: {}", i, currentSynonymId);

            // Apply target filter if provided
            if (!filterTargetSynonymIds.isEmpty() && !filterTargetSynonymIds.contains(currentSynonymId)) {
                logger.trace("executeVariableBindingSearch: Position {} (synId {}) skipped due to filterTargetSynonymIds mismatch (expected one of {}).",
                             i, currentSynonymId, filterTargetSynonymIds);
                continue;
            }

            String term = resolvedTermsCache.get(currentSynonymId);
            if (term == null) {
                if (requirements.needsSynonymIds) {
                     logger.warn("executeVariableBindingSearch: No term found in pre-fetched cache for synonymId {} at position {}. Skipping.", currentSynonymId, i);
                } else {
                     logger.error("executeVariableBindingSearch: Term for synonymId {} is null and needsSynonymIds is false. Cannot bind variable '{}'. Skipping position {}.",
                                 currentSynonymId, queryVariableName, i);
                }
                continue;
            }
            logger.trace("executeVariableBindingSearch: Term '{}' for synId {} found via pre-fetched cache.", term, currentSynonymId);

            // Determine the value to bind - use original target value if it matches, otherwise use resolved term
            String valueToBind = term;
            if (!filterTargetValuesFromQuery.isEmpty()) {
                // Use pre-computed mapping instead of calling synonymManager.getId again
                String originalTarget = filterSynonymIdToOriginalTarget.get(currentSynonymId);
                if (originalTarget != null) {
                    valueToBind = originalTarget;
                }
            }

            final String finalValueToBind = valueToBind;
            int conceptualRowId = resolvedTermToConceptualRowId.computeIfAbsent(finalValueToBind, k -> {
                int newConceptualId = resultSoA.getNextConceptualRowId();
                logger.trace("executeVariableBindingSearch: New conceptual row for term '{}' (valueToBind: '{}'). Assigning new conceptualRowId: {}",
                             term, finalValueToBind, newConceptualId);
                return newConceptualId;
            });

            logger.trace("executeVariableBindingSearch: Adding to resultSoA. ConceptualRowId: {}, VarName: '{}', Value: '{}', ValueType: ENTITY, DocId: {}",
                conceptualRowId, queryVariableName, finalValueToBind, positions.getDocIdAt(i));

            resultSoA.add(
                finalValueToBind,
                ValueType.ENTITY,
                queryVariableName,
                positions.getDocIdAt(i),
                requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                currentSynonymId,
                conceptualRowId
            );
        }
        conceptualRowsAdded = resolvedTermToConceptualRowId.size();
        logger.debug("executeVariableBindingSearch for Type '{}' completed. Added {} conceptual rows and {} total bindings to QueryResultSoA.",
                     normalizedEntityType, conceptualRowsAdded, resultSoA.size());
        return conceptualRowsAdded;
    }
}