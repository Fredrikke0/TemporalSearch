package com.example.query.executor;

import java.io.IOException;
// import java.util.ArrayList; // Not directly needed now
// import java.util.Collections; // Not directly needed now
// import java.util.List; // Not directly needed now
import java.util.Map;
import java.util.Optional;
// import java.util.Objects; // Not directly needed now
// import java.util.stream.Collectors; // Not directly needed now

import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
// import com.example.query.binding.MatchDetail; // Removed
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Dependency;

/**
 * Executor for DEPENDENCY conditions.
 */
public final class DependencyExecutor implements ConditionExecutor<Dependency> {
    private static final Logger logger = LoggerFactory.getLogger(DependencyExecutor.class);
    private static final String DEPENDENCY_INDEX_NAME = "dependency";

    public DependencyExecutor() {}

    @Override
    public QueryResultSoA execute(Dependency condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements,
                               Optional<FilteringContext> context)
        throws QueryExecutionException {

        logger.debug("Executing DEPENDENCY condition with AttributeRequirements: {}, FilteringContext isPresent: {}",
                     requirements.getRequiredSoAAttributes(), context.isPresent());

        if (!indexes.containsKey(DEPENDENCY_INDEX_NAME)) {
            throw new QueryExecutionException(
                "Missing required dependency index",
                condition.toString(),
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }

        IndexAccessInterface index = indexes.get(DEPENDENCY_INDEX_NAME);
        if (index == null) {
            throw new QueryExecutionException(
                "Required index not found: " + DEPENDENCY_INDEX_NAME,
                condition.toString(),
                QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR
            );
        }

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        int conceptualRowIdCounter = 0; // Initialize conceptual row ID counter

        try {
            String governor = condition.governor();
            String dependent = condition.dependent();
            String relation = condition.relation();
            boolean isVariable = condition.isVariable();
            // String variableName = condition.variableName(); // variableName is used inside helpers

            if (governor != null && !governor.startsWith("?") &&
                dependent != null && !dependent.startsWith("?") &&
                relation != null && !relation.startsWith("?")) {
                conceptualRowIdCounter = executeSpecificSearchOptimized(condition, index, resultSoA, conceptualRowIdCounter, requirements, context);
            } else if (isVariable && relation != null && !relation.startsWith("?")) {
                 conceptualRowIdCounter = executeVariableSearchOptimized(condition, index, resultSoA, conceptualRowIdCounter, requirements, context);
            } else {
                logger.warn("Unsupported or incomplete DEPENDENCY condition: {}. For specific search, governor, relation, and dependent must be specified literals. For variable search, relation must be a literal and variable must be true.", condition);
            }

            logger.debug("DEPENDENCY condition execution produced {} entries in QueryResultSoA ({} conceptual rows)", resultSoA.size(), conceptualRowIdCounter);

            // Sort by document ID to ensure merge join optimization works correctly
            resultSoA.sort();

            return resultSoA;

        } catch (IndexAccessException | IOException e) { // Catch specific IO/Index exceptions from helpers
             throw new QueryExecutionException(
                "Error accessing index or deserializing data for DEPENDENCY condition: " + e.getMessage(),
                e,
                condition.toString(),
                QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR // Or a more general internal error
            );
        } catch (Exception e) {
            throw new QueryExecutionException(
                "Error executing DEPENDENCY condition: " + e.getMessage(),
                e,
                condition.toString(),
                QueryExecutionException.ErrorType.INTERNAL_ERROR
            );
        }
    }

    private int executeSpecificSearchOptimized(Dependency condition, IndexAccessInterface index,
                                                QueryResultSoA resultSoA, int currentConceptualRowId,
                                                AttributeRequirements requirements,
                                                Optional<FilteringContext> context)
        throws IndexAccessException, IOException {

        String normalizedGovernor = condition.governor().toLowerCase();
        String normalizedDependent = condition.dependent().toLowerCase();
        String normalizedRelation = condition.relation().toLowerCase();

        String searchKey = normalizedGovernor + String.valueOf(IndexAccessInterface.DELIMITER) +
                           normalizedRelation + String.valueOf(IndexAccessInterface.DELIMITER) +
                           normalizedDependent;
        byte[] keyBytes = searchKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        logger.debug("Searching for specific dependency relation: {} (into QueryResultSoA)", searchKey);
        Optional<byte[]> rawBlobOptional = index.getRaw(keyBytes);

        if (rawBlobOptional.isPresent()) {
            byte[] rawBlob = rawBlobOptional.get();
            PositionListSoA positions = PositionListSoA.deserializeWithFilters(rawBlob, context);

            if (positions.isEmpty()) {
                logger.debug("No positions for dependency relation '{}' after applying context filters.", searchKey);
                return currentConceptualRowId;
            }
            int numPositions = positions.getNumPositions();
            logger.debug("Found {} positions for dependency relation '{}' after context filtering.", numPositions, searchKey);

            String value = String.join(":", normalizedGovernor, normalizedRelation, normalizedDependent);
            String variableNameToUse = condition.isVariable() ? condition.variableName() : null;

            for (int i = 0; i < numPositions; i++) {
                resultSoA.add(
                    value,
                    ValueType.DEPENDENCY,
                    variableNameToUse,
                    positions.getDocIdAt(i),
                    requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                    requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                    requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                    requirements.needsSynonymIds ? positions.getSynonymIdAt(i) : -1,
                    currentConceptualRowId
                );
            }
            if (numPositions > 0) {
                currentConceptualRowId++;
            }
            logger.debug("Added {} bindings for dependency '{}' under conceptual ID {}", numPositions, searchKey, currentConceptualRowId -1);
        } else {
            logger.debug("No positions found for dependency relation: '{}'", searchKey);
        }
        return currentConceptualRowId;
    }

    private int executeVariableSearchOptimized(Dependency condition, IndexAccessInterface index,
                                               QueryResultSoA resultSoA, int currentConceptualRowId,
                                               AttributeRequirements requirements,
                                               Optional<FilteringContext> context)
        throws IndexAccessException, IOException {

        String variableName = condition.variableName();
        // Relation filter must be present and not a variable for this optimized path
        String relationFilterLower = condition.relation().toLowerCase();

        // Governor and Dependent filters can be null (variable), a literal, or '*'
        String governorFilterLower = condition.governor() != null && !condition.governor().startsWith("?")
                                     ? condition.governor().toLowerCase() : null;
        String dependentFilterLower = condition.dependent() != null && !condition.dependent().startsWith("?")
                                      ? condition.dependent().toLowerCase() : null;

        logger.debug("Executing variable search for dependency relations with relation filter: '{}', gov filter: '{}', dep filter: '{}'",
            relationFilterLower, governorFilterLower, dependentFilterLower);

        if (index == null) {
            logger.error("IndexAccessInterface is null in DependencyExecutor.executeVariableSearchOptimized");
            throw new IndexAccessException("IndexAccessInterface cannot be null", null, null);
        }

        try (RocksIterator iterator = getIteratorForVariableSearch(index, governorFilterLower, relationFilterLower)) {
            if (iterator == null) {
                 logger.warn("RocksIterator was not initialized in DependencyExecutor.executeVariableSearchOptimized. Returning empty results.");
                 return currentConceptualRowId;
            }

            String prefix = null;
            StringBuilder prefixBuilder = new StringBuilder();
            if (governorFilterLower != null && !"*".equals(governorFilterLower)) {
                prefixBuilder.append(governorFilterLower).append(IndexAccessInterface.DELIMITER);
                if (relationFilterLower != null && !"*".equals(relationFilterLower)) {
                    prefixBuilder.append(relationFilterLower).append(IndexAccessInterface.DELIMITER);
                }
            }
            if (prefixBuilder.length() > 0) {
                prefix = prefixBuilder.toString();
            }

            while (iterator.isValid()) {
                byte[] keyBytes = iterator.key();
                byte[] valueBytes = iterator.value();
                String key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);

                if (prefix != null && !key.startsWith(prefix)) {
                    break;
                }

                String[] parts = key.split(String.valueOf(IndexAccessInterface.DELIMITER));
                if (parts.length == 3) {
                    String currentGovernor = parts[0];
                    String currentRelation = parts[1];
                    String currentDependent = parts[2];

                    if (!currentRelation.equals(relationFilterLower)) {
                        iterator.next();
                        continue;
                    }

                    if (governorFilterLower != null && !"*".equals(governorFilterLower) && !currentGovernor.equals(governorFilterLower)) {
                        iterator.next();
                        continue;
                    }
                    if (dependentFilterLower != null && !"*".equals(dependentFilterLower) && !currentDependent.equals(dependentFilterLower)) {
                        iterator.next();
                        continue;
                    }

                    String valueToBind = String.join(":", currentGovernor, currentRelation, currentDependent);
                    PositionListSoA positions = PositionListSoA.deserializeWithFilters(valueBytes, context);

                    if (positions.isEmpty()) {
                        iterator.next();
                        continue;
                    }
                    int numPositions = positions.getNumPositions();

                    for (int i = 0; i < numPositions; i++) {
                        resultSoA.add(
                            valueToBind,
                            ValueType.DEPENDENCY,
                            variableName,
                            positions.getDocIdAt(i),
                            requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                            requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                            requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                            requirements.needsSynonymIds ? positions.getSynonymIdAt(i) : -1,
                            currentConceptualRowId
                        );
                    }
                    if (numPositions > 0) {
                        currentConceptualRowId++;
                    }
                    logger.debug("Added {} bindings for dependency variable '{}' (key: '{}') under conceptual ID range ending {}",
                                 numPositions, variableName, key, currentConceptualRowId -1);
                } else {
                    logger.warn("Skipping invalid key format in dependency index: {}", key);
                }
                iterator.next();
            }
        }

        logger.debug("Variable search for dependency produced {} conceptual rows into QueryResultSoA", currentConceptualRowId);
        return currentConceptualRowId;
    }

    /**
     * Helper method to create and position a RocksIterator based on filters.
     * This consolidates the iterator creation logic.
     */
    private RocksIterator getIteratorForVariableSearch(IndexAccessInterface index, String governorFilterLower, String relationFilterLower) throws IndexAccessException {
        StringBuilder prefixBuilder = new StringBuilder();
        if (governorFilterLower != null && !"*".equals(governorFilterLower)) {
            prefixBuilder.append(governorFilterLower).append(IndexAccessInterface.DELIMITER);
            if (relationFilterLower != null && !"*".equals(relationFilterLower)) {
                prefixBuilder.append(relationFilterLower).append(IndexAccessInterface.DELIMITER);
            }
        }

        if (prefixBuilder.length() > 0) {
            String prefix = prefixBuilder.toString();
            logger.debug("Using prefix seek for RocksIterator: {}", prefix);
            return index.seek(prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else {
            logger.debug("No specific prefix possible (due to wildcards or missing governor), creating RocksIterator from first.");
            return index.iterateFromFirst();
        }
    }
}