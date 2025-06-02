package com.example.query.executor;

import java.io.IOException;
import java.time.LocalDate;
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

        logger.debug("Executing NER condition: {}, AttrReqs: {}", condition, requirements.getRequiredSoAAttributes());

        String entityType = condition.entityType();
        String normalizedEntityType = entityType.toUpperCase();
        boolean isVariable = condition.isVariable();
        String variableName = condition.qualifiedVariableName();
        String targetValue = condition.target(); // Can be null

        logger.debug("NER condition details: entityType='{}', target='{}', isVariable={}, variableName='{}'",
                     normalizedEntityType, targetValue, isVariable, variableName != null ? variableName : "(none)");

        // Check for wildcards in entityType
        if (normalizedEntityType.contains("*")) {
            throw new QueryExecutionException(
                "Wildcard in entity type ('" + entityType + "') is not currently supported for execution.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION
            );
        }

        // Check for wildcards in targetValue
        if (targetValue != null && targetValue.contains("*")) {
            throw new QueryExecutionException(
                "Wildcard in target value ('" + targetValue + "') is not currently supported for execution.",
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
                logger.debug("NER path: Variable Extraction. Type='{}', TargetVal='{}', VarName='{}'", normalizedEntityType, targetValue, variableName);
                conceptualRowsAdded = executeVariableExtractionOptimized(normalizedEntityType, targetValue, variableName, index, condition, requirements, resultSoA);
            } else {
                if (targetValue != null) {
                    logger.debug("NER path: Specific Entity Search. Type='{}', TargetVal='{}'", normalizedEntityType, targetValue);
                    conceptualRowsAdded = executeSpecificEntitySearchOptimized(normalizedEntityType, targetValue, index, condition, requirements, resultSoA);
                } else {
                    logger.debug("NER path: Entity Type Search. Type='{}'", normalizedEntityType);
                    conceptualRowsAdded = executeEntityTypeSearchOptimized(normalizedEntityType, index, condition, requirements, resultSoA);
                }
            }

            logger.debug("NER condition execution produced {} conceptual rows, total SoA size: {}", conceptualRowsAdded, resultSoA.size());
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
    private int executeSpecificEntitySearchOptimized(String entityType, String targetValueFromQuery, IndexAccessInterface index,
                                                     Ner condition, AttributeRequirements requirements, QueryResultSoA resultSoA)
        throws IOException, IndexAccessException {

        ValueType valueType = "DATE".equals(entityType) ? ValueType.DATE : ValueType.ENTITY;
        logger.debug("executeSpecificEntitySearchOptimized: Seeking for Type='{}', Target='{}' in index '{}'", entityType, targetValueFromQuery, index.getIndexType());

        byte[] keyForIndexLookup;
        String valueToStoreInSoa;

        if (valueType == ValueType.DATE) {
            LocalDate parsedDate = null;
            try {
                // Try parsing as yyyy-MM-dd first (more common user input)
                parsedDate = LocalDate.parse(targetValueFromQuery, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (java.time.format.DateTimeParseException e1) {
                try {
                    // Try parsing as yyyyMMdd if first attempt failed (index key format)
                    parsedDate = LocalDate.parse(targetValueFromQuery, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                } catch (java.time.format.DateTimeParseException e2) {
                    logger.warn("DATE targetValue '{}' could not be parsed as yyyy-MM-dd or yyyyMMdd.", targetValueFromQuery);
                }
            }

            if (parsedDate != null) {
                // Key for ner_date_index should be yyyyMMdd
                keyForIndexLookup = parsedDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                // Value stored in SoA should be yyyy-MM-dd
                valueToStoreInSoa = parsedDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            } else {
                // Fallback: use targetValueFromQuery as is for lookup and storage if unparsable
                keyForIndexLookup = targetValueFromQuery.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                valueToStoreInSoa = targetValueFromQuery;
                logger.warn("Using target DATE value '{}' directly for lookup and storage due to parsing failure.", targetValueFromQuery);
            }
        } else { // ValueType.ENTITY
            String normalizedTermForIndex = targetValueFromQuery.toLowerCase();
            String keyString = entityType + IndexAccessInterface.DELIMITER + normalizedTermForIndex;
            keyForIndexLookup = keyString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            valueToStoreInSoa = targetValueFromQuery; // Store original casing from query for ENTITY
        }

        logger.debug("executeSpecificEntitySearchOptimized: Effective key for index lookup: '{}', Value to store: '{}'",
            new String(keyForIndexLookup, java.nio.charset.StandardCharsets.UTF_8), valueToStoreInSoa);

        int bindingsAdded = 0;
        Optional<byte[]> rawBlobOptional = index.getRaw(keyForIndexLookup);
        logger.debug("executeSpecificEntitySearchOptimized: getRaw for key returned present: {}, blob length if present: {}",
                     rawBlobOptional.isPresent(), rawBlobOptional.map(b -> b.length).orElse(-1));

        if (rawBlobOptional.isPresent()) {
            byte[] rawBlob = rawBlobOptional.get();
            logger.debug("executeSpecificEntitySearchOptimized: Blob found. Attempting to get numPositions from blob.");
            int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob);
            logger.debug("executeSpecificEntitySearchOptimized: numPositions from blob: {}", numPositions);

            try {
                if (numPositions > 0) {
                    int conceptualRowIdForThisEntity = resultSoA.getNextConceptualRowId();
                    IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
                    IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
                    IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
                    IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
                    IntArrayList synonymIds = requirements.needsSynonymIds ? PositionListSoA.decompressSynonymIds(rawBlob) : null;

                    for (int i = 0; i < numPositions; i++) {
                        resultSoA.add(
                            valueToStoreInSoa, // Use the (potentially formatted) value
                            valueType,
                            null, // No variable for specific entity search
                            docIds.getInt(i),
                            sentIds != null ? sentIds.getInt(i) : -1,
                            beginChars != null ? beginChars.getInt(i) : -1,
                            endChars != null ? endChars.getInt(i) : -1,
                            synonymIds != null ? synonymIds.getInt(i) : -1,
                            conceptualRowIdForThisEntity
                        );
                        bindingsAdded++;
                    }
                } else {
                    logger.debug("executeSpecificEntitySearchOptimized: numPositions is 0, no positions to add.");
                }
            } catch (IOException e) {
                logger.warn("IOException during selective deserialization for key, falling back to full deserialization: {}", e.getMessage());
                PositionListSoA positionListSoA = PositionListSoA.deserializeFromCompositeBlob(rawBlob);
                if (positionListSoA.getNumPositions() > 0) {
                    int conceptualRowIdForThisEntity = resultSoA.getNextConceptualRowId();
                    for (int i = 0; i < positionListSoA.getNumPositions(); i++) {
                         resultSoA.add(
                            valueToStoreInSoa,
                            valueType,
                            null,
                            positionListSoA.getDocIdAt(i),
                            positionListSoA.getSentenceIdAt(i),
                            positionListSoA.getBeginCharAt(i),
                            positionListSoA.getEndCharAt(i),
                            positionListSoA.getSynonymIdAt(i),
                            conceptualRowIdForThisEntity
                        );
                        bindingsAdded++;
                    }
                }
            } catch (Exception e) {
                 logger.error("Error processing specific NER entry: {}", e.getMessage(), e);
            }
        }
        logger.debug("Specific entity search for Type '{}' Target '{}' added {} bindings to QueryResultSoA",
            entityType, targetValueFromQuery, bindingsAdded);
        return bindingsAdded;
    }

    // For NER(type) BIND ?var  OR NER(type, 'target', ?var)
    private int executeVariableExtractionOptimized(String normalizedEntityTypeFromQuery, String filterTargetValue, String queryVariableName,
                                                  IndexAccessInterface index, Ner condition,
                                                  AttributeRequirements requirements, QueryResultSoA resultSoA)
        throws IOException, IndexAccessException {

        String filterTargetValueLower = (filterTargetValue != null) ? filterTargetValue.toLowerCase() : null;
        // normalizedEntityTypeFromQuery is already .toUpperCase()

        logger.debug("executeVariableExtractionOptimized: QueryEntityType='{}', FilterTarget='{}', QueryVarName='{}', Index='{}'",
            normalizedEntityTypeFromQuery, filterTargetValueLower, queryVariableName, index.getIndexType());

        ValueType valueTypeForSoa = "DATE".equals(normalizedEntityTypeFromQuery) ? ValueType.DATE : ValueType.ENTITY;
        if (normalizedEntityTypeFromQuery.startsWith("?") && !"DATE".equals(normalizedEntityTypeFromQuery)) {
             // If query type is "?FOO" (and not DATE), the actual type comes from index key, so it's ENTITY.
             // DATE is a special case as its index structure and type are fixed.
            valueTypeForSoa = ValueType.ENTITY;
        }


        logger.debug("Executing variable search on index '{}' for query entity type '{}'. Effective ValueType for SoA: {}",
            index.getIndexType(), normalizedEntityTypeFromQuery, valueTypeForSoa);

        int bindingsAdded = 0;
        RocksIterator iterator = null;
        String prefixForSeek = null;

        try {
            if (valueTypeForSoa == ValueType.DATE) { // Query is for DATE entities
                iterator = index.iterateFromFirst(); // ner_date index contains only dates
                logger.debug("executeVariableExtractionOptimized: DATE type query, using iterateFromFirst() on index '{}'", index.getIndexType());
            } else if (normalizedEntityTypeFromQuery.startsWith("?")) { // Query is for variable type, e.g. NER(?type) BIND ?v
                iterator = index.iterateFromFirst(); // Iterate all types in ner_index
                logger.debug("executeVariableExtractionOptimized: Variable entity type query '{}', using iterateFromFirst() on index '{}'",
                             normalizedEntityTypeFromQuery, index.getIndexType());
            } else { // Query is for a specific entity type, e.g. NER(PERSON) BIND ?v
                prefixForSeek = normalizedEntityTypeFromQuery + IndexAccessInterface.DELIMITER;
                byte[] prefixBytes = prefixForSeek.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                logger.debug("executeVariableExtractionOptimized: Specific entity type query '{}', seeking prefix '{}' in index '{}'",
                             normalizedEntityTypeFromQuery, prefixForSeek, index.getIndexType());
                iterator = index.seek(prefixBytes);
            }

            if (iterator == null) {
                logger.error("NerExecutor executeVariableExtractionOptimized: Iterator is NULL after seek/iterateFromFirst for queryEntityType '{}' (index type: {})!",
                             normalizedEntityTypeFromQuery, index.getIndexType());
                return 0;
            }
            logger.debug("executeVariableExtractionOptimized: Iterator obtained. Initial isValid: {}", iterator.isValid());

            for (/* iterator positioned by seek/iterateFromFirst */ ; iterator.isValid(); iterator.next()) {
                byte[] keyBytes = iterator.key();
                byte[] valueBlobBytes = iterator.value(); // PositionListSoA blob
                String keyStringFromIndex = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
                logger.trace("executeVariableExtractionOptimized: Iterator valid. IndexKey='{}', Value blob size={}", keyStringFromIndex, valueBlobBytes.length);

                String termFromIndex;
                String actualEntityTypeFromIndexKey = null; // For logging/debugging

                if (valueTypeForSoa == ValueType.DATE) {
                    actualEntityTypeFromIndexKey = "DATE"; // For logging
                    try {
                        // Parse the key from index (yyyyMMdd)
                        LocalDate date = LocalDate.parse(keyStringFromIndex, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                        // Reformat to yyyy-MM-dd for storage in SoA
                        termFromIndex = date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
                    } catch (java.time.format.DateTimeParseException e) {
                        logger.warn("Could not parse DATE key '{}' from index using yyyyMMdd format. Storing as is. Error: {}", keyStringFromIndex, e.getMessage());
                        termFromIndex = keyStringFromIndex; // Fallback to raw key string
                    }
                } else { // ValueType.ENTITY
                    // Key from ner_index is "TYPE\0term"
                    int delimiterPos = keyStringFromIndex.indexOf(IndexAccessInterface.DELIMITER);
                    if (delimiterPos == -1) {
                        logger.warn("Skipping key '{}' from NER index in variable extraction: missing delimiter.", keyStringFromIndex);
                        continue;
                    }
                    actualEntityTypeFromIndexKey = keyStringFromIndex.substring(0, delimiterPos);
                    termFromIndex = keyStringFromIndex.substring(delimiterPos + 1);

                    // If query was for a specific type (e.g. NER(PERSON)), ensure iterator key matches.
                    // Prefix seek should handle this, but this is a safeguard.
                    if (prefixForSeek != null && !keyStringFromIndex.startsWith(prefixForSeek)) {
                        logger.debug("Iterator key '{}' no longer matches prefix '{}'. Breaking loop.", keyStringFromIndex, prefixForSeek);
                        break;
                    }
                }

                logger.trace("Extracted from index: ActualType='{}', Term='{}'", actualEntityTypeFromIndexKey, termFromIndex);

                // Filter by targetValue from query condition, if present (e.g. NER(PERSON, 'Smith', ?var))
                if (filterTargetValueLower != null && !termFromIndex.toLowerCase().contains(filterTargetValueLower)) {
                    logger.trace("Term '{}' (from key '{}') does not contain filter '{}'. Skipping.", termFromIndex, keyStringFromIndex, filterTargetValueLower);
                    continue;
                }

                logger.debug("executeVariableExtractionOptimized: Processing IndexKey='{}'. Attempting to get numPositions from value blob (size {}).",
                             keyStringFromIndex, valueBlobBytes.length);
                int numPositions = PositionListSoA.getNumPositionsFromBlob(valueBlobBytes);
                logger.debug("executeVariableExtractionOptimized: numPositions from blob for IndexKey='{}': {}", keyStringFromIndex, numPositions);

                byte[] rawBlob = valueBlobBytes;

                try {
                    logger.trace("executeVariableExtractionOptimized: IndexKey='{}', numPositions from blob: {}", keyStringFromIndex, numPositions);
                    if (numPositions == 0) {
                        logger.trace("executeVariableExtractionOptimized: IndexKey='{}', numPositions is 0, skipping.", keyStringFromIndex);
                        continue;
                    }

                    int currentConceptualRowId = resultSoA.getNextConceptualRowId();
                    IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
                    IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
                    IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
                    IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
                    IntArrayList synonymIds = requirements.needsSynonymIds ? PositionListSoA.decompressSynonymIds(rawBlob) : null;

                    for (int i = 0; i < numPositions; i++) {
                        resultSoA.add(
                            termFromIndex,        // The extracted term (formatted for DATE)
                            valueTypeForSoa,      // DATE or ENTITY
                            queryVariableName,    // The variable name from the Ner condition
                            docIds.getInt(i),
                            sentIds != null ? sentIds.getInt(i) : -1,
                            beginChars != null ? beginChars.getInt(i) : -1,
                            endChars != null ? endChars.getInt(i) : -1,
                            synonymIds != null ? synonymIds.getInt(i) : -1,
                            currentConceptualRowId
                        );
                        bindingsAdded++;
                    }
                } catch (IOException e) {
                    logger.warn("IOException during selective deserialization for NER IndexKey '{}', falling back to full deserialization: {}",
                               keyStringFromIndex, e.getMessage());
                    PositionListSoA positionListSoA = PositionListSoA.deserializeFromCompositeBlob(rawBlob);
                    if (positionListSoA.getNumPositions() > 0) {
                        int currentConceptualRowId = resultSoA.getNextConceptualRowId(); // Get new ID for this fallback block
                        for (int i = 0; i < positionListSoA.getNumPositions(); i++) {
                            resultSoA.add(
                                termFromIndex, // Use the same termFromIndex
                                valueTypeForSoa,
                                queryVariableName,
                                positionListSoA.getDocIdAt(i),
                                positionListSoA.getSentenceIdAt(i),
                                positionListSoA.getBeginCharAt(i),
                                positionListSoA.getEndCharAt(i),
                                positionListSoA.getSynonymIdAt(i),
                                currentConceptualRowId
                            );
                            bindingsAdded++;
                        }
                    }
                } catch (Exception e) {
                     logger.error("Error processing NER entry with IndexKey '{}': {}", keyStringFromIndex, e.getMessage(), e);
                }
            }
        } finally {
            if (iterator != null) {
                iterator.close();
            }
        }
        logger.debug("Extracted {} bindings for query entity type '{}' into QueryResultSoA", bindingsAdded, normalizedEntityTypeFromQuery);
        return bindingsAdded;
    }

    // For NER(type) -- non-variable, no specific target. Value stored is entityType.
    private int executeEntityTypeSearchOptimized(String entityTypeFromQuery, IndexAccessInterface index,
                                             Ner condition, AttributeRequirements requirements, QueryResultSoA resultSoA)
        throws IOException, IndexAccessException {
        // entityTypeFromQuery is already .toUpperCase()

        logger.debug("executeEntityTypeSearchOptimized: QueryEntityType='{}', Index='{}'", entityTypeFromQuery, index.getIndexType());
        ValueType valueTypeForSoa = "DATE".equals(entityTypeFromQuery) ? ValueType.DATE : ValueType.ENTITY;

        logger.debug("Executing entity type search on index '{}' for query entity type '{}'. Effective ValueType for SoA: {}",
            index.getIndexType(), entityTypeFromQuery, valueTypeForSoa);
        int bindingsAdded = 0;
        RocksIterator iterator = null;
        String prefixForSeek = null;

        try {
            if (valueTypeForSoa == ValueType.DATE) {
                logger.debug("executeEntityTypeSearchOptimized: DATE type query, using iterateFromFirst on index '{}'.", index.getIndexType());
                iterator = index.iterateFromFirst();
            } else { // ENTITY Type
                prefixForSeek = entityTypeFromQuery + IndexAccessInterface.DELIMITER;
                byte[] prefixBytes = prefixForSeek.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                logger.debug("executeEntityTypeSearchOptimized: Specific entity type query '{}', seeking prefix '{}' in index '{}'",
                             entityTypeFromQuery, prefixForSeek, index.getIndexType());
                iterator = index.seek(prefixBytes);
            }

            if (iterator == null) {
                 logger.error("NerExecutor executeEntityTypeSearchOptimized: Iterator is NULL after seek/iterateFromFirst for queryEntityType '{}' (index type: {})!",
                             entityTypeFromQuery, index.getIndexType());
                return 0;
            }
            logger.debug("executeEntityTypeSearchOptimized: Iterator obtained. Initial isValid: {}", iterator.isValid());

            for (/* iterator positioned */ ; iterator.isValid(); iterator.next()) {
                byte[] keyBytes = iterator.key();
                byte[] valueBlobBytes = iterator.value();
                String keyStringFromIndex = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
                logger.trace("executeEntityTypeSearchOptimized: Iterator valid. IndexKey='{}', Value blob size={}", keyStringFromIndex, valueBlobBytes.length);

                // If query was for a specific type (e.g. NER(PERSON)), ensure iterator key matches.
                // Prefix seek should handle this, but this is a safeguard.
                if (prefixForSeek != null && !keyStringFromIndex.startsWith(prefixForSeek)) {
                    logger.debug("executeEntityTypeSearchOptimized: IndexKey '{}' does not match prefix '{}'. Breaking loop.", keyStringFromIndex, prefixForSeek);
                    break;
                }

                String termToStoreInSoa;
                if (valueTypeForSoa == ValueType.DATE) {
                    // Key from ner_date_index is "yyyyMMdd"
                    try {
                        LocalDate date = LocalDate.parse(keyStringFromIndex, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                        termToStoreInSoa = date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE); // "yyyy-MM-dd"
                    } catch (java.time.format.DateTimeParseException e) {
                        logger.warn("Could not parse DATE key '{}' from index (entityTypeSearch) using yyyyMMdd. Storing as is. Error: {}", keyStringFromIndex, e.getMessage());
                        termToStoreInSoa = keyStringFromIndex; // Fallback
                    }
                } else { // ENTITY
                    // Key from ner_index is "TYPE\0term". We want the "term" part.
                    // prefixForSeek is "QUERY_TYPE\0"
                    termToStoreInSoa = keyStringFromIndex.substring(prefixForSeek.length());
                }
                logger.trace("executeEntityTypeSearchOptimized: Extracted term '{}' from IndexKey '{}'", termToStoreInSoa, keyStringFromIndex);


                logger.debug("executeEntityTypeSearchOptimized: Processing IndexKey '{}'. Attempting to get numPositions from value blob (size {}).",
                             keyStringFromIndex, valueBlobBytes.length);
                int numPositions = PositionListSoA.getNumPositionsFromBlob(valueBlobBytes);
                logger.debug("executeEntityTypeSearchOptimized: numPositions from blob for IndexKey '{}': {}", keyStringFromIndex, numPositions);

                byte[] rawBlob = valueBlobBytes;
                try {
                    logger.trace("executeEntityTypeSearchOptimized: IndexKey='{}', numPositions from blob: {}", keyStringFromIndex, numPositions);
                    if (numPositions == 0) {
                        logger.trace("executeEntityTypeSearchOptimized: IndexKey='{}', numPositions is 0, skipping.", keyStringFromIndex);
                        continue;
                    }
                    int currentConceptualRowId = resultSoA.getNextConceptualRowId();

                    IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
                    IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
                    IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
                    IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
                    IntArrayList synonymIds = requirements.needsSynonymIds ? PositionListSoA.decompressSynonymIds(rawBlob) : null;

                    for (int i = 0; i < numPositions; i++) {
                        resultSoA.add(
                            termToStoreInSoa, // The extracted term (formatted for DATE)
                            valueTypeForSoa,  // Correctly DATE or ENTITY
                            null,             // No variable name in this method's context (not a variable binding query)
                            docIds.getInt(i),
                            sentIds != null ? sentIds.getInt(i) : -1,
                            beginChars != null ? beginChars.getInt(i) : -1,
                            endChars != null ? endChars.getInt(i) : -1,
                            synonymIds != null ? synonymIds.getInt(i) : -1,
                            currentConceptualRowId
                        );
                        bindingsAdded++;
                    }
                } catch (IOException e) {
                    logger.warn("IOException during selective deserialization for NER IndexKey '{}' (entity type search), falling back to full deserialization: {}",
                               keyStringFromIndex, e.getMessage());
                    PositionListSoA positionListSoA = PositionListSoA.deserializeFromCompositeBlob(rawBlob);
                    if (positionListSoA.getNumPositions() > 0) {
                        int currentConceptualRowId = resultSoA.getNextConceptualRowId(); // Get new ID for this fallback
                        for (int i = 0; i < positionListSoA.getNumPositions(); i++) {
                            resultSoA.add(
                                termToStoreInSoa, // Use the same term
                                valueTypeForSoa,
                                null,
                                positionListSoA.getDocIdAt(i),
                                positionListSoA.getSentenceIdAt(i),
                                positionListSoA.getBeginCharAt(i),
                                positionListSoA.getEndCharAt(i),
                                positionListSoA.getSynonymIdAt(i),
                                currentConceptualRowId
                            );
                            bindingsAdded++;
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error processing NER entry with IndexKey '{}': {}", keyStringFromIndex, e.getMessage(), e);
                }
            }
        } finally {
            if (iterator != null) {
                iterator.close();
            }
        }

        logger.debug("Found {} bindings matching entity type '{}' into QueryResultSoA", bindingsAdded, entityTypeFromQuery);
        return bindingsAdded;
    }
}