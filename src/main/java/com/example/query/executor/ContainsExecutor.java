package com.example.query.executor;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Contains;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Executor for CONTAINS conditions.
 * Handles n-gram pattern matching and variable binding.
 * Returns QueryResultSoA.
 *
 * @see com.example.query.model.condition.Contains
 */
public final class ContainsExecutor implements ConditionExecutor<Contains> {
    private static final Logger logger = LoggerFactory.getLogger(ContainsExecutor.class);

    private static final String UNIGRAM_INDEX = "unigram";
    private static final String BIGRAM_INDEX = "bigram";
    private static final String TRIGRAM_INDEX = "trigram";
    private static final char DELIMITER = IndexAccessInterface.DELIMITER;

    /**
     * Creates a new ContainsExecutor.
     */
    public ContainsExecutor() {
        // No initialization required
    }



    @Override
    public QueryResultSoA execute(Contains condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements)
        throws QueryExecutionException {

        logger.debug("Executing CONTAINS condition with AttributeRequirements: {}", requirements.getRequiredSoAAttributes());

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        int conceptualRowIdCounter = 0;

        List<String> terms = condition.terms();
        if (terms.isEmpty()) {
            logger.warn("CONTAINS condition has no terms, returning empty result");
            return resultSoA;
        }

        boolean isVariable = condition.isVariable();
        String variableName = condition.variableName();

        IndexAccessInterface index = null;
        if (terms.size() == 1) {
            index = indexes.get(UNIGRAM_INDEX);
        } else if (terms.size() == 2) {
            index = indexes.get(BIGRAM_INDEX);
        } else if (terms.size() == 3) {
            index = indexes.get(TRIGRAM_INDEX);
        }

        if (index == null) {
            String missingIndex = terms.size() == 1 ? UNIGRAM_INDEX : (terms.size() == 2 ? BIGRAM_INDEX : TRIGRAM_INDEX);
            logger.error("Required {}-gram index ('{}') not found in provided indexes: {}", terms.size(), missingIndex, indexes.keySet());
            throw new QueryExecutionException(
                "Required "+ missingIndex +" index not found for " + terms.size() + "-gram terms.",
                "CONTAINS(" + String.join(", ", terms) + ")",
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }

        try {
            Set<String> patterns = constructSearchPatterns(terms);

            for (String pattern : patterns) {
                conceptualRowIdCounter = executePatternSearchOptimized(
                    pattern, isVariable, variableName, index, condition, resultSoA, conceptualRowIdCounter, requirements);
            }

            logger.debug("Found {} total entries in QueryResultSoA for terms: {} using selective deserialization",
                    resultSoA.size(), terms);

            // Sort by document ID to ensure merge join optimization works correctly
            resultSoA.sort();

            return resultSoA;
        } catch (Exception e) {
            if (e instanceof QueryExecutionException qee) throw qee;
            if (e instanceof IndexAccessException iae) {
                 throw new QueryExecutionException("Index access error during CONTAINS", iae, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
            }
            throw new QueryExecutionException(
                "Error executing CONTAINS condition: " + e.getMessage(),
                e,
                condition.toString(),
                QueryExecutionException.ErrorType.INTERNAL_ERROR
            );
        }
    }

    /**
     * Constructs search patterns from terms, handling wildcards.
     * For example, ["apple", "*", "day"] would generate patterns for all trigrams
     * starting with "apple" and ending with "day".
     *
     * @param terms The list of terms, possibly containing wildcards
     * @return Set of search patterns to look for
     */
    private Set<String> constructSearchPatterns(List<String> terms) {
        Set<String> patterns = new HashSet<>();

        // NEW SIMPLIFIED LOGIC:
        // Always build one pattern string. Wildcards are treated as literal characters.
        // The decision to do a prefix search vs. direct lookup is handled in executePatternSearchOptimized.
        if (terms.isEmpty()) {
            return Collections.emptySet();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            // Terms are already validated by QuerySemanticValidator to not be null/empty individually.
            // Wildcard '*' is a valid term.
            sb.append(terms.get(i).toLowerCase()); // Convert to lowercase
            if (i < terms.size() - 1) {
                sb.append(DELIMITER);
            }
        }
        patterns.add(sb.toString());
        logger.debug("Constructed pattern: {}", sb.toString());
        return patterns;
    }

    /**
     * Executes a prefix search on the index, populating a QueryResultSoA.
     * Iterates through keys starting with the given prefix and adds to QueryResultSoA.
     *
     * @param prefix The prefix to search for (lowercase)
     * @param isVariable True if the condition involves variable binding
     * @param variableName The variable name to bind, if any
     * @param index The index to search
     * @param condition The original Contains condition
     * @param resultSoA The QueryResultSoA to add results to
     * @param conceptualRowIdCounter The current counter for conceptualRowIds
     * @param requirements AttributeRequirements for deserialization
     * @return The updated conceptualRowIdCounter
     * @throws QueryExecutionException If an error occurs during query execution
     * @throws IndexAccessException If an error occurs during index access
     */
    private int executePrefixSearch(String prefix, boolean isVariable, String variableName,
                                        IndexAccessInterface index, Contains condition,
                                        QueryResultSoA resultSoA, int conceptualRowIdCounter,
                                        AttributeRequirements requirements)
        throws QueryExecutionException, IndexAccessException {

        logger.debug("Executing prefix search for: {} (populating QueryResultSoA)", prefix);
        int originalConceptualRowIdCounter = conceptualRowIdCounter;
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try (RocksIterator iterator = index.seek(prefixBytes)) {
            while (iterator.isValid()) {
                byte[] keyBytes = iterator.key();
                byte[] valueBytes = iterator.value();
                String key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);

                if (!key.startsWith(prefix)) {
                    // We've iterated past the prefix range
                    break;
                }

                // Deserialize the value to PositionListSoA
                PositionListSoA positions = PositionListSoA.deserializeFromCompositeBlob(valueBytes);

                // Always reconstruct value for human readability if it contained delimiters
                String actualValue = reconstructValue(key, DELIMITER);

                for (int i = 0; i < positions.getNumPositions(); i++) {
                    resultSoA.add(
                        actualValue,
                        ValueType.TERM,
                        isVariable ? variableName : null,
                        positions.getDocIdAt(i),
                        // Use requirements to determine if these attributes are needed and available in PositionListSoA
                        requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                        requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                        requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                        requirements.needsSynonymIds ? positions.getSynonymIdAt(i) : -1,
                        conceptualRowIdCounter++ // Assign a new ID for each detail
                    );
                }
                iterator.next();
            }
        } catch (IOException e) {
            throw new QueryExecutionException(
                "Error during prefix search deserialization: " + e.getMessage(),
                e,
                condition.toString(),
                QueryExecutionException.ErrorType.INTERNAL_ERROR
            );
        } catch (IndexAccessException iae) {
            // Re-throw if it's already an IndexAccessException from the iterator itself
            throw iae;
        } catch (Exception e) {
            // Catch-all for other unexpected errors during iteration or processing
            throw new QueryExecutionException(
                "Unexpected error during prefix search: " + e.getMessage(),
                e,
                condition.toString(),
                QueryExecutionException.ErrorType.INTERNAL_ERROR
            );
        }

        logger.debug("Added {} entries to QueryResultSoA for prefix: '{}'", (conceptualRowIdCounter - originalConceptualRowIdCounter), prefix);
        return conceptualRowIdCounter;
    }

    /**
     * Reconstructs the space-separated value from the index key.
     * Replaces NGRAM_DELIMITER with a space.
     */
    private String reconstructValue(String key, char delimiter) {
        // Split by the delimiter and join with space
        // Need to handle the delimiter carefully if it's a regex special char
        String[] parts = key.split(String.valueOf(delimiter));
        return String.join(" ", parts);
    }

    /**
     * Executes a search for a specific pattern using selective deserialization for better performance.
     * This method only deserializes the attributes actually needed, reducing memory usage and processing time.
     *
     * @param pattern The pattern to search for
     * @param isVariable Whether this corresponds to a variable in the original condition
     * @param variableName The original variable name (if isVariable is true)
     * @param index The index to search in
     * @param condition The condition object (used for ID)
     * @param resultSoA The QueryResultSoA to populate
     * @param conceptualRowIdCounter The current conceptualRowId counter
     * @param requirements The AttributeRequirements for selective deserialization
     * @return The updated conceptualRowIdCounter
     */
    private int executePatternSearchOptimized(String pattern, boolean isVariable, String variableName,
                                        IndexAccessInterface index, Contains condition, QueryResultSoA resultSoA,
                                        int conceptualRowIdCounter, AttributeRequirements requirements)
        throws QueryExecutionException, IndexAccessException {

        // Pattern is already lowercased by constructSearchPatterns
        if (pattern == null || pattern.trim().isEmpty()) {
            logger.warn("Skipping empty pattern in CONTAINS condition");
            return conceptualRowIdCounter;
        }

        // NEW SIMPLIFIED WILDCARD HANDLING:
        if (pattern.endsWith("*") && pattern.length() > 1) {
            // Pattern ends with '*' and is not just "*". Treat as prefix search.
            // E.g., "term*", "term1<DELIMITER>term2*"
            String prefix = pattern.substring(0, pattern.length() - 1);
            logger.debug("Pattern '{}' ends with '*', performing prefix search for '{}'", pattern, prefix);
            return executePrefixSearch(prefix, isVariable, variableName, index, condition, resultSoA, conceptualRowIdCounter, requirements);
        } else {
            // Handles:
            // 1. Exact terms: "term", "term1<DELIMITER>term2"
            // 2. Literal "*": pattern is "*"
            // 3. Patterns with '*' not at the end: "*<DELIMITER>term", "term<DELIMITER>*<DELIMITER>term"
            // These will all be direct lookups.
            logger.debug("Attempting direct lookup for pattern: {}", pattern);
            byte[] keyBytes = pattern.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Optional<byte[]> rawBlob = index.getRaw(keyBytes);

            if (rawBlob.isPresent()) {
                try {
                    int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob.get());
                    if (numPositions == 0) {
                        return conceptualRowIdCounter;
                    }

                    logger.debug("Found {} positions for pattern: '{}' (using selective deserialization)", numPositions, pattern);

                    IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob.get());
                    IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob.get()) : null;
                    IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob.get()) : null;
                    IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob.get()) : null;
                    IntArrayList synonymIds = requirements.needsSynonymIds ? PositionListSoA.decompressSynonymIds(rawBlob.get()) : null;

                    String actualValue = reconstructValue(pattern, DELIMITER); // Reconstruct for display/binding

                    for (int i = 0; i < numPositions; i++) {
                        resultSoA.add(
                            actualValue,
                            ValueType.TERM,
                            isVariable ? variableName : null,
                            docIds.getInt(i),
                            sentIds != null ? sentIds.getInt(i) : -1,
                            beginChars != null ? beginChars.getInt(i) : -1,
                            endChars != null ? endChars.getInt(i) : -1,
                            synonymIds != null ? synonymIds.getInt(i) : -1,
                            conceptualRowIdCounter++
                        );
                    }

                    logger.debug("Selective deserialization for '{}': docIds={}, sentIds={}, positions={}, synonymIds={}",
                               pattern, (docIds != null && !docIds.isEmpty()), (sentIds != null && !sentIds.isEmpty()),
                               (beginChars != null && !beginChars.isEmpty()), (synonymIds != null && !synonymIds.isEmpty()));

                } catch (Exception e) {
                    logger.error("Error during selective deserialization for pattern '{}': {}", pattern, e.getMessage(), e);
                    throw new QueryExecutionException("Error during selective deserialization for pattern " + pattern, e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
                }
            } else {
                logger.debug("No positions found for pattern: '{}'", pattern);
            }
            return conceptualRowIdCounter;
        }
        // REMOVED old complex wildcard handling logic (prefix scans for *term, term*term etc.)
    }
}