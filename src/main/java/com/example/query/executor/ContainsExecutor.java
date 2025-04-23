package com.example.query.executor;

import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.core.IndexAccessException;
import com.example.query.model.Query;
import com.example.query.model.condition.Contains;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import com.example.query.executor.QueryResult;
import org.iq80.leveldb.DBIterator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executor for CONTAINS conditions.
 * Handles n-gram pattern matching and variable binding.
 * Returns QueryResult containing MatchDetail objects.
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
    public QueryResult execute(Contains condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName)
        throws QueryExecutionException {
        
        logger.debug("Executing CONTAINS condition with {} terms at {} granularity with size {} (corpus: {})", 
                condition.terms().size(), granularity, granularitySize, corpusName);
        
        // Validate required indexes
        if (!indexes.containsKey(UNIGRAM_INDEX)) {
            throw new QueryExecutionException(
                "Missing required unigram index",
                condition.toString(),
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }
        
        List<MatchDetail> allDetails = new ArrayList<>();
        
        List<String> terms = condition.terms();
        if (terms.isEmpty()) {
            logger.warn("CONTAINS condition has no terms, returning empty result");
            // Return empty QueryResult
            return new QueryResult(granularity, granularitySize, Collections.emptyList());
        }
        
        // Check if there are too many terms
        if (terms.size() > 3) {
            throw new QueryExecutionException(
                "CONTAINS condition supports at most 3 terms, but got " + terms.size() + " terms",
                "CONTAINS(" + String.join(", ", terms) + ")",
                QueryExecutionException.ErrorType.INVALID_CONDITION
            );
        }
        
        // Determine if this is a variable binding
        boolean isVariable = condition.isVariable();
        String variableName = condition.variableName();
        
        // Get the appropriate ngram index based on the size of the terms
        IndexAccessInterface index = null;
        if (terms.size() == 1) {
            index = indexes.get(UNIGRAM_INDEX);
        } else if (terms.size() == 2) {
            index = indexes.get(BIGRAM_INDEX);
        } else if (terms.size() == 3) {
            index = indexes.get(TRIGRAM_INDEX);
        }
        
        if (index == null) {
            // Log which specific index was missing
            String missingIndex = terms.size() == 1 ? UNIGRAM_INDEX : (terms.size() == 2 ? BIGRAM_INDEX : TRIGRAM_INDEX);
            logger.error("Required {}-gram index ('{}') not found in provided indexes: {}", terms.size(), missingIndex, indexes.keySet());
            throw new QueryExecutionException(
                "Required "+ missingIndex +" index not found for " + terms.size() + "-gram terms.",
                "CONTAINS(" + String.join(", ", terms) + ")",
                QueryExecutionException.ErrorType.MISSING_INDEX
            );
        }
        
        try {
            // Construct search patterns based on the terms
            Set<String> patterns = constructSearchPatterns(terms);
            
            // Execute search for each pattern and collect MatchDetail results
            for (String pattern : patterns) {
                List<MatchDetail> patternDetails = executePatternSearch(
                    pattern, isVariable, variableName, index, granularity, condition);
                
                if (!patternDetails.isEmpty()) {
                    allDetails.addAll(patternDetails);
                }
            }
            
            logger.debug("Found {} total details for terms: {}. Returning QueryResult.", 
                    allDetails.size(), terms);
            
            // Create and return QueryResult directly from collected details
            QueryResult finalResult = new QueryResult(granularity, granularitySize, allDetails);
            
            logger.debug("CONTAINS execution complete with {} MatchDetail objects in QueryResult.", 
                         finalResult.getAllDetails().size());
            return finalResult;
        } catch (Exception e) {
            // Rethrow specific exceptions, wrap others
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
        
        // Check if there are any wildcards
        boolean hasWildcard = terms.stream().anyMatch(term -> "*".equals(term));
        
        if (!hasWildcard) {
            // No wildcards, join the terms with the appropriate delimiter based on term count
            if (terms.size() == 1) {
                // For unigrams, just use the term itself
                patterns.add(terms.get(0).toLowerCase());
            } else if (terms.size() == 2) {
                // For bigrams, use null byte delimiter
                patterns.add(terms.get(0).toLowerCase() + DELIMITER + terms.get(1).toLowerCase());
            } else if (terms.size() == 3) {
                // For trigrams, use null byte delimiter
                patterns.add(terms.get(0).toLowerCase() + DELIMITER + terms.get(1).toLowerCase() + DELIMITER + terms.get(2).toLowerCase());
            }
            return patterns;
        }
        
        // Handle wildcards based on the number of terms
        if (terms.size() == 2) {
            // Bigram with one wildcard
            if ("*".equals(terms.get(0))) {
                // Wildcard in first position - we'd need to scan all bigrams ending with the second term
                // This is not efficient, so we'll log a warning
                logger.warn("Wildcard in first position of bigram is not efficiently supported: {}", terms);
                patterns.add("*" + DELIMITER + terms.get(1).toLowerCase());
            } else if ("*".equals(terms.get(1))) {
                // Wildcard in second position - we'd need to scan all bigrams starting with the first term
                // This is not efficient, so we'll log a warning
                logger.warn("Wildcard in second position of bigram is not efficiently supported: {}", terms);
                patterns.add(terms.get(0).toLowerCase() + DELIMITER + "*");
            }
        } else if (terms.size() == 3) {
            // Trigram with wildcards
            if ("*".equals(terms.get(1))) {
                // Middle term is wildcard - we'd need to scan for all trigrams with first and last terms
                // This is not efficient, so we'll log a warning
                logger.warn("Wildcard in middle position of trigram is not efficiently supported: {}", terms);
                patterns.add(terms.get(0).toLowerCase() + DELIMITER + "*" + DELIMITER + terms.get(2).toLowerCase());
            } else if ("*".equals(terms.get(0))) {
                // First term is wildcard
                logger.warn("Wildcard in first position of trigram is not efficiently supported: {}", terms);
                patterns.add("*" + DELIMITER + terms.get(1).toLowerCase() + DELIMITER + terms.get(2).toLowerCase());
            } else if ("*".equals(terms.get(2))) {
                // Last term is wildcard
                logger.warn("Wildcard in last position of trigram is not efficiently supported: {}", terms);
                patterns.add(terms.get(0).toLowerCase() + DELIMITER + terms.get(1).toLowerCase() + DELIMITER + "*");
            }
        }
        
        // If we couldn't create any patterns (shouldn't happen), use the original terms with appropriate delimiter
        if (patterns.isEmpty()) {
            if (terms.size() == 1) {
                patterns.add(terms.get(0).toLowerCase());
            } else if (terms.size() == 2) {
                patterns.add(terms.get(0).toLowerCase() + DELIMITER + terms.get(1).toLowerCase());
            } else if (terms.size() == 3) {
                patterns.add(terms.get(0).toLowerCase() + DELIMITER + terms.get(1).toLowerCase() + DELIMITER + terms.get(2).toLowerCase());
            }
        }
        
        return patterns;
    }
    
    /**
     * Executes a search for a specific pattern, returning MatchDetail objects.
     *
     * @param pattern The pattern to search for
     * @param isVariable Whether this corresponds to a variable in the original condition
     * @param variableName The original variable name (if isVariable is true)
     * @param index The index to search in
     * @param granularity The query granularity (for logging)
     * @param condition The condition object (used for ID)
     * @return List of MatchDetail objects found for the pattern
     */
    private List<MatchDetail> executePatternSearch(String pattern, boolean isVariable, String variableName,
                                        IndexAccessInterface index, Query.Granularity granularity,
                                        Contains condition)
        throws QueryExecutionException, IndexAccessException {
        
        List<MatchDetail> details = new ArrayList<>();
        
        if (pattern == null || pattern.trim().isEmpty()) {
            logger.warn("Skipping empty pattern in CONTAINS condition");
            return details;
        }
        
        if (pattern.contains("*")) {
            // Basic wildcard handling (prefix only for now)
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1).toLowerCase();
                logger.debug("Searching for prefix pattern '{}' in index type {}", prefix, index.getIndexType());
                return executePrefixSearch(prefix, isVariable, variableName, index, condition);
            } else {
                 logger.warn("Wildcard patterns ('{}') other than suffix ('*') are not implemented yet.", pattern);
                return details;
            }
        }
        
        String normalizedPattern = pattern.toLowerCase();
        logger.debug("Searching for exact pattern '{}' in index type {} at {} granularity",
                    normalizedPattern, index.getIndexType(), granularity);
        
        byte[] patternBytes = normalizedPattern.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Optional<PositionList> positionsOpt = index.get(patternBytes);
        
        if (!positionsOpt.isPresent()) {
            logger.debug("Pattern '{}' not found in any documents", normalizedPattern);
            return details;
        }
        
        PositionList positionList = positionsOpt.get();
        String condId = String.valueOf(condition.hashCode());
        
        // Reconstruct the human-readable value (space-separated)
        String valueString = reconstructValue(pattern, DELIMITER);

        for (Position position : positionList.getPositions()) {
            // Use 5-arg constructor for non-join results
            MatchDetail detail = new MatchDetail(
                valueString, // Always use the reconstructed value with spaces
                ValueType.TERM,
                position,
                isVariable ? variableName : null // Bind variable if needed
            );
            details.add(detail);
        }
        
        logger.trace("Found {} details for pattern '{}' with conditionId {}", 
                     details.size(), normalizedPattern, condId);
        return details;
    }
    
    // Added method for prefix search to handle suffix wildcard
    private List<MatchDetail> executePrefixSearch(String prefix, boolean isVariable, String variableName,
                                        IndexAccessInterface index, Contains condition)
        throws QueryExecutionException, IndexAccessException {
            
        List<MatchDetail> details = new ArrayList<>();
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String condId = String.valueOf(condition.hashCode());
        
        try (DBIterator iterator = index.iterator()) {
            iterator.seek(prefixBytes);
            while (iterator.hasNext()) {
                Map.Entry<byte[], byte[]> entry = iterator.next();
                String key = new String(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8);
                
                if (!key.startsWith(prefix)) {
                    break; // Moved past relevant keys
                }
                
                PositionList positionList = PositionList.deserialize(entry.getValue());
                
                // Reconstruct the human-readable value (space-separated)
                String valueString = reconstructValue(key, DELIMITER);

                for (Position position : positionList.getPositions()) {
                    // Use 5-arg constructor for non-join results
                    MatchDetail detail = new MatchDetail(
                        valueString, // Always use the reconstructed value with spaces
                        ValueType.TERM,
                        position,
                        isVariable ? variableName : null // Bind variable if needed
                    );
                    details.add(detail);
                }
            }
        } catch (IOException e) {
             throw new QueryExecutionException("IO error during prefix search for CONTAINS", e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (RuntimeException e) { // Catch deserialization errors
             throw new QueryExecutionException("Deserialization error during prefix search for CONTAINS", e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }
        logger.trace("Found {} details for prefix '{}' with conditionId {}", 
                     details.size(), prefix, condId);
        return details;
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
} 