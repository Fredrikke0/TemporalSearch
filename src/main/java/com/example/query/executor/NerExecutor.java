package com.example.query.executor;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Ner;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Executor for NER (Named Entity Recognition) conditions.
 * Handles entity type matching and variable binding for named entities.
 * Returns QueryResultSoA directly.
 */
public final class NerExecutor implements ConditionExecutor<Ner> {
    private static final Logger logger = LoggerFactory.getLogger(NerExecutor.class);

    private static final String NER_INDEX_NAME = "ner";
    private static final String NER_DATE_INDEX_NAME = "ner_date";

    /**
     * Creates a new NER executor.
     */
    public NerExecutor() {
        // No initialization required
    }

    @Override
    public QueryResultSoA execute(Ner condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements)
        throws QueryExecutionException {

        logger.debug("Executing NER condition with AttributeRequirements: {}", requirements.getRequiredSoAAttributes());

        String entityType = condition.entityType();
        String normalizedEntityType = entityType.toUpperCase();
        boolean isVariable = condition.isVariable();
        String variableName = condition.variableName();
        String targetValue = condition.target(); // Can be null

        logger.debug("NER condition details: entityType='{}', target='{}', isVariable={}, variableName='{}'",
                     normalizedEntityType, targetValue, isVariable, variableName != null ? variableName : "(none)");

        if ("*".equals(normalizedEntityType)) {
            throw new QueryExecutionException(
                "Wildcard entity type (*) is not currently supported for execution.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION
            );
        }

        String indexName = "DATE".equals(normalizedEntityType) ? NER_DATE_INDEX_NAME : NER_INDEX_NAME;

        if (!indexes.containsKey(indexName)) {
            throw new QueryExecutionException(
                "Missing required NER index: " + indexName,
                condition.toString(),
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }

        IndexAccessInterface index = indexes.get(indexName);
        if (index == null) {
            throw new QueryExecutionException("Required index not found: " + indexName, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        int conceptualRowsAdded = 0;

        try {
            if (isVariable) {
                // Handles NER(type, ?var) and NER(type, 'target', ?var)
                // Value stored is the specific entity instance text.
                conceptualRowsAdded = executeVariableExtractionOptimized(normalizedEntityType, targetValue, variableName, index, condition, requirements, resultSoA);
            } else {
                if (targetValue != null) {
                    // Handles NER(type, 'target')
                    // Value stored is targetValue.
                    conceptualRowsAdded = executeSpecificEntitySearchOptimized(normalizedEntityType, targetValue, index, condition, requirements, resultSoA);
                } else {
                    // Handles NER(type)
                    // Value stored is normalizedEntityType.
                    conceptualRowsAdded = executeEntityTypeSearchOptimized(normalizedEntityType, index, condition, requirements, resultSoA);
                }
            }

            logger.debug("NER condition execution produced {} conceptual rows in QueryResultSoA", conceptualRowsAdded);
            return resultSoA;

        } catch (Exception e) {
            if (e instanceof QueryExecutionException qee) {
                throw qee;
            }
            throw new QueryExecutionException(
                "Error executing NER condition: " + e.getMessage(),
                e,
                condition.toString(),
                QueryExecutionException.ErrorType.INTERNAL_ERROR
            );
        }
    }

    // For NER(type, 'targetText') -- non-variable
    private int executeSpecificEntitySearchOptimized(String entityType, String targetValue, IndexAccessInterface index,
                                                     Ner condition, AttributeRequirements requirements, QueryResultSoA resultSoA)
        throws IOException, IndexAccessException {

        logger.debug("Searching for specific entity type '{}' with target '{}' into QueryResultSoA", entityType, targetValue);
        ValueType valueType = "DATE".equals(entityType) ? ValueType.DATE : ValueType.ENTITY;
        String keyString = entityType.toUpperCase() + IndexAccessInterface.DELIMITER + targetValue;
        byte[] keyBytes = keyString.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        int bindingsAdded = 0;
        Optional<byte[]> rawBlobOptional = index.getRaw(keyBytes);

        if (rawBlobOptional.isPresent()) {
            byte[] rawBlob = rawBlobOptional.get();
            try {
                int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob);
                if (numPositions > 0) {
                    IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
                    IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
                    IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
                    IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
                    IntArrayList synonymIds = requirements.needsSynonymIds ? PositionListSoA.decompressSynonymIds(rawBlob) : null;

                    for (int i = 0; i < numPositions; i++) {
                        resultSoA.add(
                            targetValue, // Value is the specific target
                            valueType,
                            null, // No variable name
                            docIds.getInt(i),
                            sentIds != null ? sentIds.getInt(i) : -1,
                            beginChars != null ? beginChars.getInt(i) : -1,
                            endChars != null ? endChars.getInt(i) : -1,
                            synonymIds != null ? synonymIds.getInt(i) : -1,
                            bindingsAdded // This is conceptualRowId
                        );
                        bindingsAdded++;
                    }
                }
            } catch (IOException e) {
                logger.warn("IOException during selective deserialization for specific NER key '{}', falling back to full deserialization: {}",
                           keyString, e.getMessage());
                PositionListSoA positionListSoA = PositionListSoA.deserializeFromCompositeBlob(rawBlob);
                for (int i = 0; i < positionListSoA.getNumPositions(); i++) {
                     resultSoA.add(
                        targetValue,
                        valueType,
                        null,
                        positionListSoA.getDocIdAt(i),
                        positionListSoA.getSentenceIdAt(i),
                        positionListSoA.getBeginCharAt(i),
                        positionListSoA.getEndCharAt(i),
                        positionListSoA.getSynonymIdAt(i),
                        bindingsAdded
                    );
                    bindingsAdded++;
                }
            } catch (Exception e) {
                 logger.error("Error processing specific NER entry with key '{}': {}", keyString, e.getMessage(), e);
            }
        }
        logger.debug("Specific entity search for type '{}' target '{}' added {} bindings to QueryResultSoA", entityType, targetValue, bindingsAdded);
        return bindingsAdded;
    }

    // For NER(type) BIND ?var  OR NER(type, 'target', ?var)
    private int executeVariableExtractionOptimized(String entityType, String filterTargetValue, String variableName, IndexAccessInterface index,
                                                  Ner condition, AttributeRequirements requirements, QueryResultSoA resultSoA)
        throws IOException, IndexAccessException {

        String filterTargetValueLower = (filterTargetValue != null) ? filterTargetValue.toLowerCase() : null;

        logger.debug("Extracting entities of type '{}'{}{} for variable '{}' into QueryResultSoA",
            entityType,
            (filterTargetValueLower != null ? " matching filter '" + filterTargetValueLower + "'" : ""),
            variableName);

        ValueType valueType = "DATE".equals(entityType.toUpperCase()) ? ValueType.DATE : ValueType.ENTITY;

        logger.debug("Executing variable search on index '{}' for entity type '{}'", index.getIndexType(), entityType);

        int bindingsAdded = 0;
        RocksIterator iterator = null;
        String prefix = null;

        try {
            if ("DATE".equals(entityType.toUpperCase())) {
                iterator = index.iterateFromFirst();
            } else if (entityType.startsWith("?")) {
                iterator = index.iterateFromFirst();
            } else {
                prefix = entityType.toUpperCase() + IndexAccessInterface.DELIMITER;
                byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                iterator = index.seek(prefixBytes);
            }

            if (iterator == null) {
                logger.error("NerExecutor: {} returned a null iterator for entityType '{}' (index type: {})",
                             ("DATE".equals(entityType.toUpperCase()) || entityType.startsWith("?") ? "iterateFromFirst()" : "seek()"),
                             entityType, index.getIndexType());
                return 0;
            }

            for (/* iterator positioned */ ; iterator.isValid(); iterator.next()) {
                byte[] keyBytes = iterator.key();
                byte[] valueBytes = iterator.value();
                String key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);

                String currentPrefix;
                if ("DATE".equals(entityType.toUpperCase())) {
                    currentPrefix = "";
                } else if (entityType.startsWith("?")) {
                    int delimiterPos = key.indexOf(IndexAccessInterface.DELIMITER);
                    if (delimiterPos == -1) {
                        logger.warn("Skipping key '{}' in variable type extraction due to missing delimiter.", key);
                        continue;
                    }
                    currentPrefix = key.substring(0, delimiterPos + 1);
                } else {
                    currentPrefix = prefix;
                    if (!key.startsWith(currentPrefix)) {
                        break;
                    }
                }

                String value;
                if ("DATE".equals(entityType.toUpperCase())) {
                    value = key;
                } else {
                     value = key.substring(currentPrefix.length());
                }

                if (filterTargetValueLower != null && !value.toLowerCase().contains(filterTargetValueLower)) {
                    continue;
                }

                byte[] rawBlob = valueBytes;
                String valueToBind;
                String qualifiedVariableName;

                if (entityType.startsWith("?")) {
                    int delimiterPos = key.indexOf(IndexAccessInterface.DELIMITER);
                    if (delimiterPos != -1) {
                        valueToBind = key.substring(0, delimiterPos);
                    } else {
                        valueToBind = key;
                    }
                    qualifiedVariableName = variableName;
                } else {
                    valueToBind = value;
                    qualifiedVariableName = variableName;
                }

                try {
                    int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob);
                    if (numPositions == 0) {
                        continue;
                    }

                    IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
                    IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
                    IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
                    IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
                    IntArrayList synonymIds = requirements.needsSynonymIds ? PositionListSoA.decompressSynonymIds(rawBlob) : null;

                    for (int i = 0; i < numPositions; i++) {
                        resultSoA.add(
                            valueToBind,
                            valueType,
                            qualifiedVariableName,
                            docIds.getInt(i),
                            sentIds != null ? sentIds.getInt(i) : -1,
                            beginChars != null ? beginChars.getInt(i) : -1,
                            endChars != null ? endChars.getInt(i) : -1,
                            synonymIds != null ? synonymIds.getInt(i) : -1,
                            bindingsAdded
                        );
                        bindingsAdded++;
                    }
                } catch (IOException e) {
                    logger.warn("IOException during selective deserialization for NER key '{}', falling back to full deserialization: {}",
                               key, e.getMessage());
                    PositionListSoA positionListSoA = PositionListSoA.deserializeFromCompositeBlob(rawBlob);
                    for (int i = 0; i < positionListSoA.getNumPositions(); i++) {
                        resultSoA.add(
                            valueToBind,
                            valueType,
                            qualifiedVariableName,
                            positionListSoA.getDocIdAt(i),
                            positionListSoA.getSentenceIdAt(i),
                            positionListSoA.getBeginCharAt(i),
                            positionListSoA.getEndCharAt(i),
                            positionListSoA.getSynonymIdAt(i),
                            bindingsAdded
                        );
                        bindingsAdded++;
                    }
                } catch (Exception e) {
                     logger.error("Error processing NER entry with key '{}': {}", key, e.getMessage(), e);
                }
            }
        } finally {
            if (iterator != null) {
                iterator.close();
            }
        }
        logger.debug("Extracted {} bindings for entity type '{}' into QueryResultSoA", bindingsAdded, entityType);
        return bindingsAdded;
    }

    // For NER(type) -- non-variable, no specific target. Value stored is entityType.
    private int executeEntityTypeSearchOptimized(String entityType, IndexAccessInterface index,
                                             Ner condition, AttributeRequirements requirements, QueryResultSoA resultSoA)
        throws IOException, IndexAccessException {

        logger.debug("Searching for all entities of type '{}' into QueryResultSoA, storing type as value.", entityType);

        ValueType valueType = "DATE".equals(entityType.toUpperCase()) ? ValueType.DATE : ValueType.ENTITY;

        logger.debug("Executing entity type search on index '{}' for entity type '{}'", index.getIndexType(), entityType);
        int bindingsAdded = 0;
        RocksIterator iterator = null;
        String prefix = null;

        try {
            if ("DATE".equals(entityType.toUpperCase())) {
                iterator = index.iterateFromFirst();
            } else if (entityType.startsWith("?")) {
                 logger.warn("executeEntityTypeSearchOptimized called with a variable entityType: {}. This is unexpected and likely a logic error.", entityType);
                 iterator = index.iterateFromFirst();
            } else {
                prefix = entityType.toUpperCase() + IndexAccessInterface.DELIMITER;
                byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                iterator = index.seek(prefixBytes);
            }

            if (iterator == null) {
                logger.error("NerExecutor: {} returned a null iterator for entityType '{}' in executeEntityTypeSearchOptimized (index type: {})",
                             ("DATE".equals(entityType.toUpperCase()) || entityType.startsWith("?") ? "iterateFromFirst()" : "seek()"),
                             entityType, index.getIndexType());
                return 0;
            }

            for (/* iterator positioned */ ; iterator.isValid(); iterator.next()) {
                byte[] keyBytes = iterator.key();
                byte[] valueBytes = iterator.value();
                String key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);

                String currentPrefix;
                 if ("DATE".equals(entityType.toUpperCase())) {
                    currentPrefix = "";
                } else if (entityType.startsWith("?")) {
                    int delimiterPos = key.indexOf(IndexAccessInterface.DELIMITER);
                     if (delimiterPos == -1) continue;
                    currentPrefix = key.substring(0, delimiterPos + 1);
                } else {
                    currentPrefix = prefix;
                    if (!key.startsWith(currentPrefix)) {
                        break;
                    }
                }

                String specificEntityInstanceValue;
                if ("DATE".equals(entityType.toUpperCase())) {
                    specificEntityInstanceValue = key;
                } else {
                     specificEntityInstanceValue = key.substring(currentPrefix.length());
                }

                byte[] rawBlob = valueBytes;
                try {
                    int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob);
                    if (numPositions == 0) {
                        continue;
                    }

                    IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
                    IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
                    IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
                    IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
                    IntArrayList synonymIds = requirements.needsSynonymIds ? PositionListSoA.decompressSynonymIds(rawBlob) : null;

                    for (int i = 0; i < numPositions; i++) {
                        resultSoA.add(
                            specificEntityInstanceValue,
                            valueType,
                            null,
                            docIds.getInt(i),
                            sentIds != null ? sentIds.getInt(i) : -1,
                            beginChars != null ? beginChars.getInt(i) : -1,
                            endChars != null ? endChars.getInt(i) : -1,
                            synonymIds != null ? synonymIds.getInt(i) : -1,
                            bindingsAdded
                        );
                        bindingsAdded++;
                    }
                } catch (IOException e) {
                    logger.warn("IOException during selective deserialization for NER key '{}' (entity type search), falling back to full deserialization: {}",
                               key, e.getMessage());
                    PositionListSoA positionListSoA = PositionListSoA.deserializeFromCompositeBlob(rawBlob);
                    for (int i = 0; i < positionListSoA.getNumPositions(); i++) {
                        resultSoA.add(
                            specificEntityInstanceValue,
                            valueType,
                            null,
                            positionListSoA.getDocIdAt(i),
                            positionListSoA.getSentenceIdAt(i),
                            positionListSoA.getBeginCharAt(i),
                            positionListSoA.getEndCharAt(i),
                            positionListSoA.getSynonymIdAt(i),
                            bindingsAdded
                        );
                        bindingsAdded++;
                    }
                } catch (Exception e) {
                    logger.error("Error processing NER entry with key '{}': {}", key, e.getMessage(), e);
                }
            }
        } finally {
            if (iterator != null) {
                iterator.close();
            }
        }

        logger.debug("Found {} bindings matching entity type '{}' into QueryResultSoA", bindingsAdded, entityType);
        return bindingsAdded;
    }
}