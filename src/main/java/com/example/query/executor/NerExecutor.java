package com.example.query.executor;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
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

            resultSoA.sort();

            return resultSoA;

        } catch (IndexAccessException | IOException e) {
            throw new QueryExecutionException("Error accessing NER index: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (Exception e) {
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
        logger.debug("executeEntityTypeOnlySearch (value-keyed): Prefix scan for Type='{}'", normalizedEntityType);

        String prefix = normalizedEntityType + IndexAccessInterface.DELIMITER;
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] upperBound = java.util.Arrays.copyOf(prefixBytes, prefixBytes.length + 1);
        upperBound[upperBound.length - 1] = (byte) 0xFF;

        try (RocksIterator iterator = index.seekWithBounds(prefixBytes, upperBound, 256 * 1024)) {
            ExecutorIndexUtils.iterateGroupedByBase(iterator, prefix, (baseKey, blobs) -> {
                Optional<PositionListSoA> mergedOpt = ExecutorIndexUtils.mergeAndFilter(blobs, context, requirements);
                if (mergedOpt.isPresent() && !mergedOpt.get().isEmpty()) {
                    PositionListSoA positions = mergedOpt.get();
                    for (int i = 0; i < positions.getNumPositions(); i++) {
                        resultSoA.add(
                            normalizedEntityType,
                            ValueType.ENTITY_TYPE,
                            null,
                            positions.getDocIdAt(i),
                            requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                            requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                            requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                            -1,
                            resultSoA.getNextConceptualRowId()
                        );
                    }
                }
            });
        }
        logger.debug("executeEntityTypeOnlySearch (value-keyed) for Type '{}' completed. SoA size now {}.", normalizedEntityType, resultSoA.size());
        return resultSoA.getConceptualRowCount();
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
            } catch (IllegalArgumentException e) {
                 logger.error("IllegalArgumentException while getting ID for term '{}' in executeSpecificEntityFilterSearch", normalizedTargetTerm, e);
                 throw new QueryExecutionException("Invalid term for synonym lookup: " + normalizedTargetTerm, e, "NerExecutor", QueryExecutionException.ErrorType.INTERNAL_ERROR);
            }
        }

        if (targetSynonymIds.isEmpty()) {
            logger.debug("executeSpecificEntityFilterSearch: No valid synonym IDs found for targets {}", targetValuesFromQuery);
            return 0;
        }

        logger.debug("executeSpecificEntityFilterSearch (value-keyed): Type='{}', TargetValues={}, TargetSynonymIDs={}",
            normalizedEntityType, targetValuesFromQuery, targetSynonymIds);

        int conceptualRowsAdded = 0;
        for (int synId : targetSynonymIds) {
            String key = normalizedEntityType + IndexAccessInterface.DELIMITER + synId;
            Optional<PositionListSoA> positionsOptional = index.getMergedPositions(key, context, requirements);
            if (!positionsOptional.isPresent() || positionsOptional.get().isEmpty()) {
                continue;
            }
            PositionListSoA positions = positionsOptional.get();
            String targetValueToStore = synonymIdToOriginalTarget.get(synId);
            if (targetValueToStore == null) {
                try {
                    targetValueToStore = synonymManager.getTerm(synId).orElse("");
                } catch (RocksDBException e) {
                    targetValueToStore = "";
                }
            }
            for (int i = 0; i < positions.getNumPositions(); i++) {
                resultSoA.add(
                    targetValueToStore,
                    ValueType.ENTITY,
                    null,
                    positions.getDocIdAt(i),
                    requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                    requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                    requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                    synId,
                    resultSoA.getNextConceptualRowId()
                );
                conceptualRowsAdded++;
            }
        }
        logger.debug("executeSpecificEntityFilterSearch (value-keyed) for Type '{}' , Targets {} (synIds {}) added {} positions.",
                     normalizedEntityType, targetValuesFromQuery, targetSynonymIds, conceptualRowsAdded);
        return conceptualRowsAdded;
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
            logger.warn("executeVariableBindingSearch called for Type='{}', Var='{}', TargetFilter={} but AttributeRequirements.needsSynonymIds is false. This may limit results.",
                        normalizedEntityType, queryVariableName, filterTargetValuesFromQuery);
        }
        logger.debug("Executing executeVariableBindingSearch (value-keyed): Type='{}', Var='{}', TargetFilter={}, ContextIsPresent={}",
                     normalizedEntityType, queryVariableName, filterTargetValuesFromQuery, context.isPresent());

        // Resolve filter target values to synonym IDs if provided
        Set<Integer> filterTargetSynonymIds = new HashSet<>();
        Map<Integer, String> filterSynonymIdToOriginalTarget = new HashMap<>();
        if (!filterTargetValuesFromQuery.isEmpty()) {
            for (String filterTarget : filterTargetValuesFromQuery) {
                try {
                    String normalizedFilterTarget = filterTarget.toLowerCase();
                    int synId = synonymManager.getId(normalizedFilterTarget);
                    filterTargetSynonymIds.add(synId);
                    filterSynonymIdToOriginalTarget.put(synId, filterTarget);
                } catch (RocksDBException e) {
                    logger.error("RocksDBException while getting synonymId for TargetFilter '{}' in variable binding search for Type '{}'", filterTarget, normalizedEntityType, e);
                    throw new IndexAccessException("Failed to get synonymId for target filter: " + filterTarget, NER_INDEX_NAME, IndexAccessException.ErrorType.READ_ERROR, e);
                } catch (IllegalArgumentException e) {
                    throw new QueryExecutionException("Invalid target value for NER variable binding: " + filterTarget, e, "NerExecutor", QueryExecutionException.ErrorType.INVALID_CONDITION);
                }
            }
        }

        String prefix = normalizedEntityType + IndexAccessInterface.DELIMITER;
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] upperBound = java.util.Arrays.copyOf(prefixBytes, prefixBytes.length + 1);
        upperBound[upperBound.length - 1] = (byte) 0xFF;

        try (RocksIterator iterator = index.seekWithBounds(prefixBytes, upperBound, 256 * 1024)) {
            ExecutorIndexUtils.iterateGroupedByBase(iterator, prefix, (baseKey, blobs) -> {
                int delimIdx = baseKey.lastIndexOf(IndexAccessInterface.DELIMITER);
                if (delimIdx <= 0 || delimIdx == baseKey.length() - 1) return;
                String synIdStr = baseKey.substring(delimIdx + 1);
                int synId;
                try { synId = Integer.parseInt(synIdStr); } catch (NumberFormatException nfe) { return; }
                if (!filterTargetSynonymIds.isEmpty() && !filterTargetSynonymIds.contains(synId)) {
                    return;
                }

                Optional<PositionListSoA> mergedOpt = ExecutorIndexUtils.mergeAndFilter(blobs, context, requirements);
                if (!mergedOpt.isPresent() || mergedOpt.get().isEmpty()) return;
                PositionListSoA positions = mergedOpt.get();

                String valueToBind = filterSynonymIdToOriginalTarget.get(synId);
                if (valueToBind == null) {
                    try {
                        valueToBind = synonymManager.getTerm(synId).orElse("");
                    } catch (RocksDBException e) {
                        valueToBind = "";
                    }
                }

                for (int i = 0; i < positions.getNumPositions(); i++) {
                    resultSoA.add(
                        valueToBind,
                        ValueType.ENTITY,
                        queryVariableName,
                        positions.getDocIdAt(i),
                        requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                        requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                        requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                        synId,
                        resultSoA.getNextConceptualRowId()
                    );
                }
            });
        }

        logger.debug("executeVariableBindingSearch (value-keyed) for Type '{}' completed. SoA size now {}.", normalizedEntityType, resultSoA.size());
        return resultSoA.getConceptualRowCount();
    }
}