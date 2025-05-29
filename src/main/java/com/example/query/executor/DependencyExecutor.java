package com.example.query.executor;

import java.io.IOException;
// import java.util.ArrayList; // Not directly needed now
// import java.util.Collections; // Not directly needed now
// import java.util.List; // Not directly needed now
import java.util.Map;
import java.util.Optional;
// import java.util.Objects; // Not directly needed now
// import java.util.stream.Collectors; // Not directly needed now

import org.iq80.leveldb.DBIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
// import com.example.query.binding.MatchDetail; // Removed
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Dependency;

import it.unimi.dsi.fastutil.ints.IntArrayList;

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
                               AttributeRequirements requirements)
        throws QueryExecutionException {

        logger.debug("Executing DEPENDENCY condition with AttributeRequirements: {}", requirements.getRequiredSoAAttributes());

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
                conceptualRowIdCounter = executeSpecificSearchOptimized(condition, index, resultSoA, conceptualRowIdCounter, requirements);
            } else if (isVariable && relation != null && !relation.startsWith("?")) {
                 conceptualRowIdCounter = executeVariableSearchOptimized(condition, index, resultSoA, conceptualRowIdCounter, requirements);
            } else {
                logger.warn("Unsupported or incomplete DEPENDENCY condition: {}. For specific search, governor, relation, and dependent must be specified literals. For variable search, relation must be a literal and variable must be true.", condition);
            }

            logger.debug("DEPENDENCY condition execution produced {} entries in QueryResultSoA ({} conceptual rows)", resultSoA.size(), conceptualRowIdCounter);
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
                                                AttributeRequirements requirements)
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
            int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob);
            if (numPositions == 0) return currentConceptualRowId;

            logger.debug("Found {} positions for dependency relation '{}'", numPositions, searchKey);

            IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
            IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
            IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
            IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;
            IntArrayList synonymIds = requirements.needsSynonymIds ? PositionListSoA.decompressSynonymIds(rawBlob) : null;

            String value = String.join(":", normalizedGovernor, normalizedRelation, normalizedDependent);
            String variableNameToUse = condition.isVariable() ? condition.variableName() : null;

            for (int i = 0; i < numPositions; i++) {
                resultSoA.add(
                    value,
                    ValueType.DEPENDENCY,
                    variableNameToUse,
                    docIds.getInt(i),
                    sentIds != null ? sentIds.getInt(i) : -1,
                    beginChars != null ? beginChars.getInt(i) : -1,
                    endChars != null ? endChars.getInt(i) : -1,
                    synonymIds != null ? synonymIds.getInt(i) : -1,
                    currentConceptualRowId // Use currentConceptualRowId for this whole match group
                );
            }
            // Only increment conceptualRowId ONCE per unique dependency match found in the index,
            // as all its positions belong to the same conceptual match.
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
                                               AttributeRequirements requirements)
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

        DBIterator initialIterator;
        String prefix = null;
        StringBuilder prefixBuilder = new StringBuilder();

        // Construct prefix for seek, stopping if a component is '*' or null (effectively a wildcard for that point onwards)
        if (governorFilterLower != null && !"*".equals(governorFilterLower)) {
            prefixBuilder.append(governorFilterLower).append(IndexAccessInterface.DELIMITER);
            // Only add relation to prefix if governor was specific
            if (relationFilterLower != null && !"*".equals(relationFilterLower)) {
                prefixBuilder.append(relationFilterLower).append(IndexAccessInterface.DELIMITER);
                // Dependent filter is not used for prefix seek, as relation must be specific if dependent is.
                // And if relation is '*', then dependent cannot make prefix more specific.
            }
        }

        if (prefixBuilder.length() > 0) {
            prefix = prefixBuilder.toString();
            logger.debug("Using prefix seek: {}", prefix);
            initialIterator = index.seek(prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else {
            logger.debug("No specific prefix possible (due to wildcards or missing governor), iterating from first.");
            initialIterator = index.iterateFromFirst();
        }

        final DBIterator iterator = initialIterator;

        if (iterator == null) {
            logger.warn("DBIterator was not initialized in DependencyExecutor.executeVariableSearchOptimized. Returning empty results.");
            return currentConceptualRowId;
        }

        try (iterator) {
            while (iterator.hasNext()) {
                Map.Entry<byte[], byte[]> entry = iterator.next();
                String key = new String(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8);

                if (prefix != null && !key.startsWith(prefix)) {
                    break;
                }

                String[] parts = key.split(String.valueOf(IndexAccessInterface.DELIMITER));
                if (parts.length == 3) {
                    String currentGovernor = parts[0];
                    String currentRelation = parts[1];
                    String currentDependent = parts[2];

                    // Apply filters, treating '*' as a wildcard match for that component.
                    // Relation filter (relationFilterLower) is assumed to be non-null and not '*' for this path based on current condition checks.
                    // However, if it were allowed to be '*', it would be:
                    // if (!"*".equals(relationFilterLower) && !currentRelation.equals(relationFilterLower)) continue;
                    if (!currentRelation.equals(relationFilterLower)) { // Relation must match exactly as per current pre-check for this method
                        continue;
                    }

                    if (governorFilterLower != null && !"*".equals(governorFilterLower) && !currentGovernor.equals(governorFilterLower)) {
                        continue;
                    }
                    if (dependentFilterLower != null && !"*".equals(dependentFilterLower) && !currentDependent.equals(dependentFilterLower)) {
                        continue;
                    }

                    String valueToBind = key.replace(IndexAccessInterface.DELIMITER, ':');

                    byte[] directRawBlob = entry.getValue();

                    int numPositions = PositionListSoA.getNumPositionsFromBlob(directRawBlob);
                    if (numPositions == 0) continue;

                    IntArrayList docIds = PositionListSoA.decompressDocIds(directRawBlob);
                    IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(directRawBlob) : null;
                    IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(directRawBlob) : null;
                    IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(directRawBlob) : null;
                    IntArrayList synonymIds = requirements.needsSynonymIds ? PositionListSoA.decompressSynonymIds(directRawBlob) : null;

                    for (int i = 0; i < numPositions; i++) {
                        resultSoA.add(
                            valueToBind,
                            ValueType.DEPENDENCY,
                            variableName,
                            docIds.getInt(i),
                            sentIds != null ? sentIds.getInt(i) : -1,
                            beginChars != null ? beginChars.getInt(i) : -1,
                            endChars != null ? endChars.getInt(i) : -1,
                            synonymIds != null ? synonymIds.getInt(i) : -1,
                            currentConceptualRowId // Use currentConceptualRowId for this whole match group
                        );
                    }
                    // Increment conceptual ID once per unique dependency key matched from the index.
                    if (numPositions > 0) {
                         currentConceptualRowId++;
                    }
                } else {
                    logger.warn("Skipping invalid key format in dependency index: {}", key);
                }
            }
        }

        logger.debug("Variable search for dependency produced {} conceptual rows into QueryResultSoA", currentConceptualRowId);
        return currentConceptualRowId;
    }
}