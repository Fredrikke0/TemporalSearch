package com.example.query.executor;

import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.query.model.Query;
import com.example.query.model.condition.Ner;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executor for NER (Named Entity Recognition) conditions.
 * Handles entity type matching and variable binding for named entities.
 * Returns QueryResult containing MatchDetail objects
 */
public final class NerExecutor implements ConditionExecutor<Ner> {
    private static final Logger logger = LoggerFactory.getLogger(NerExecutor.class);
    
    private static final String NER_INDEX_NAME = "ner";
    private static final String NER_DATE_INDEX_NAME = "ner_date";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_DATE;

    /**
     * Creates a new NER executor.
     */
    public NerExecutor() {
        // No initialization required
    }

    @Override
    public QueryResult execute(Ner condition, Map<String, IndexAccessInterface> indexes,
                                Query.Granularity granularity,
                               int granularitySize,
                               String corpusName)
        throws QueryExecutionException {
        
        logger.debug("Executing NER condition for type {} at {} granularity with size {} (corpus: {})", 
                condition.entityType(), granularity, granularitySize, corpusName);
        
        String entityType = condition.entityType();
        String variableName = condition.variableName();
        boolean isVariable = condition.isVariable();
        String targetValue = condition.target();
        
        // Normalize entity type (usually uppercase, except wildcard)
        String normalizedEntityType = "*".equals(entityType) ? "*" : entityType.toUpperCase();
        String indexName = "DATE".equals(normalizedEntityType) ? NER_DATE_INDEX_NAME : NER_INDEX_NAME;
        
        // --- Added: Explicitly disallow wildcard execution for now ---
        if ("*".equals(normalizedEntityType)) {
            throw new QueryExecutionException(
                "Wildcard entity type (*) is not currently supported for execution.",
                condition.toString(),
                QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION
            );
        }
        // --- End Added ---
        
        // Validate required indexes
        if (!indexes.containsKey(indexName)) {
            throw new QueryExecutionException(
                String.format("Missing required index: %s for entity type %s", indexName, normalizedEntityType),
                condition.toString(),
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }
        
        IndexAccessInterface index = indexes.get(indexName);
        if (index == null) { // Should be caught above, but double-check
            throw new QueryExecutionException("Required index not found: " + indexName, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }
        
        List<MatchDetail> details = new ArrayList<>();
        
        try {
            if (isVariable) {
                // Variable binding mode - extract entities of the given type
                details = executeVariableExtraction(normalizedEntityType, variableName, index, condition);
            } else {
                // Search mode - find documents/sentences with specific entity type
                details = executeEntitySearch(normalizedEntityType, index, condition);
            }
            
            logger.debug("NER condition produced {} MatchDetail objects. Returning QueryResult.", details.size());
            
            // Create QueryResult directly
            QueryResult finalResult = new QueryResult(granularity, granularitySize, details);

            logger.debug("NER execution complete with {} MatchDetail objects.", finalResult.getAllDetails().size());
            return finalResult;
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
    
    /**
     * Executes variable extraction for a specific entity type.
     * Finds all entities of the given type and creates MatchDetail objects.
     *
     * @param entityType The normalized entity type to extract (uppercase or *)
     * @param variableName The variable name to associate with the MatchDetail
     * @param index The index to search in (either ner or ner_date)
     * @param condition The original condition object (for ID and target)
     * @return List of MatchDetail objects
     */
    private List<MatchDetail> executeVariableExtraction(String entityType, String variableName, IndexAccessInterface index,
                                                  Ner condition)
        throws Exception {
        
        String targetValue = condition.target();
        String targetValueLower = (targetValue != null) ? targetValue.toLowerCase() : null;
        
        logger.debug("Extracting entities of type '{}'{}{} for variable '{}'", 
            entityType, 
            (targetValueLower != null ? " matching '" + targetValueLower + "'" : ""),
            variableName);
            
        List<MatchDetail> details = new ArrayList<>();
        String conditionId = String.valueOf(condition.hashCode());
        ValueType valueType = "DATE".equals(entityType) ? ValueType.DATE : ValueType.ENTITY;

        // Iterate through the index for the given type prefix
        String prefix = entityType.toUpperCase() + IndexAccessInterface.DELIMITER;
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        
        logger.debug("Executing variable search on index '{}' with prefix: {}", index.getIndexType(), prefix);
        
        try (var iterator = index.iterator()) {
            iterator.seek(prefixBytes);
            while (iterator.hasNext()) {
                Map.Entry<byte[], byte[]> entry = iterator.next();
                String key = new String(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8);

                if (!key.startsWith(prefix)) {
                    break; // Moved past relevant keys
                }
                
                // Extract value (entity text) from the key
                String value = key.substring(prefix.length());
                
                // Filter by target value if provided (case-insensitive substring match)
                if (targetValueLower != null && !value.toLowerCase().contains(targetValueLower)) {
                    continue; // Skip if value doesn't contain the target substring
                }
                
                PositionList positionList = PositionList.deserialize(entry.getValue());
                
                details.addAll(positionList.getPositions().stream()
                    .map(pos -> new MatchDetail(value, valueType, pos, conditionId, variableName))
                    .collect(Collectors.toList()));
            }
        }
        logger.debug("Extracted {} details for entity type '{}'", details.size(), entityType);
        return details;
    }
    
    /**
     * Executes an entity search for a specific entity type.
     * This mode finds documents/sentences containing entities of the given type.
     * If a target value is specified, it only finds occurrences of that specific entity text.
     *
     * @param entityType The entity type to search for
     * @param index The index to search in
     * @param condition The original condition object (for ID and target)
     * @return List of MatchDetail objects
     */
    private List<MatchDetail> executeEntitySearch(String entityType, IndexAccessInterface index,
                                                     Ner condition)
        throws Exception {
        
        String targetValue = condition.target();
        String targetValueLower = (targetValue != null) ? targetValue.toLowerCase() : null;

        logger.debug("Searching for entities of type '{}'{}", 
            entityType,
            (targetValueLower != null ? " matching '" + targetValueLower + "'" : ""));
            
        List<MatchDetail> details = new ArrayList<>();
        String conditionId = String.valueOf(condition.hashCode());
        ValueType valueType = "DATE".equals(entityType) ? ValueType.DATE : ValueType.ENTITY;

        // Use prefix iteration to find all entities of the given type
        String prefix = entityType.toUpperCase() + IndexAccessInterface.DELIMITER;
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        
        logger.debug("Executing entity search on index '{}' with prefix: {}", index.getIndexType(), prefix);

        try (var iterator = index.iterator()) {
            iterator.seek(prefixBytes);
            while (iterator.hasNext()) {
                Map.Entry<byte[], byte[]> entry = iterator.next();
                String key = new String(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8);

                if (!key.startsWith(prefix)) {
                    break; // Moved past relevant keys
                }
                
                // Extract value (entity text) from the key
                String value = key.substring(prefix.length());
                
                // Filter by target value if provided (case-insensitive substring match)
                if (targetValueLower != null && !value.toLowerCase().contains(targetValueLower)) {
                    continue; // Skip if value doesn't contain the target substring
                }

                // When searching for a type without binding, the specific value doesn't matter as much,
                // but we still need the positions. We use the actual matched value if target is specified,
                // otherwise use the entity type itself.
                String detailValue = (targetValueLower != null) ? value : entityType; 
                PositionList positionList = PositionList.deserialize(entry.getValue());
                
                details.addAll(positionList.getPositions().stream()
                    .map(pos -> new MatchDetail(detailValue, valueType, pos, conditionId, null))
                    .collect(Collectors.toList()));
            }
        }
        
        logger.debug("Found {} details matching entity type '{}'", details.size(), entityType);
        return details;
    }
} 