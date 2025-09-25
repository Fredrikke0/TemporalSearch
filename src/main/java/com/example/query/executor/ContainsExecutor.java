package com.example.query.executor;

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
// Legacy PositionListSoA removed in RB-only migration
import com.example.index.presence.RBPresenceIndex;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Contains;

/**
 * Executor for CONTAINS conditions.
 * Handles n-gram pattern matching and variable binding.
 * Returns QueryResultSoA.
 *
 * Wildcards (simplified behavior): Only a trailing '*' on the entire pattern triggers a prefix scan
 * against the selected index (e.g., "apple*"). Any '*' elsewhere (including per-token) is treated
 * as a literal character.
 *
 * @see com.example.query.model.condition.Contains
 */
public final class ContainsExecutor implements ConditionExecutor<Contains> {
    private static final Logger logger = LoggerFactory.getLogger(ContainsExecutor.class);

    private static final String UNIGRAM_INDEX = "rb_unigram";
    private static final String BIGRAM_INDEX = "rb_bigram";
    private static final String TRIGRAM_INDEX = "rb_trigram";
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
                               AttributeRequirements requirements,
                               Optional<FilteringContext> context)
        throws QueryExecutionException {

        logger.debug(">>> Executing ContainsExecutor");
        logger.debug("Executing CONTAINS condition with AttributeRequirements: {}, FilteringContext isPresent: {}",
                     requirements.getRequiredSoAAttributes(), context.isPresent());
        // Early exit if the FilteringContext explicitly restricts to an empty set
        if (context.isPresent()) {
            FilteringContext fc = context.get();
            if (fc.allowedDocumentIds().isPresent() && fc.allowedDocumentIds().get().isEmpty()) {
                logger.debug("FilteringContext has empty allowedDocumentIds; returning empty result for CONTAINS.");
                return new QueryResultSoA(granularity, granularitySize, requirements);
            }
            if (requirements.needsSentenceId && fc.granularity() == Query.Granularity.SENTENCE) {
                if (fc.allowedDocumentSentenceIds().isPresent() && fc.allowedDocumentSentenceIds().get().isEmpty()) {
                    logger.debug("FilteringContext has empty allowedDocumentSentenceIds at sentence granularity; returning empty result for CONTAINS.");
                    return new QueryResultSoA(granularity, granularitySize, requirements);
                }
            }
        }

        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
        int conceptualRowIdCounter = 0;

        List<String> terms = condition.terms();
        if (terms.isEmpty()) {
            throw new QueryExecutionException(
                "Contains condition must have at least one term.",
                condition.toString(),
                QueryExecutionException.ErrorType.INVALID_CONDITION
            );
        }

        // Enforce supported n-gram sizes (1..3) for simplified behavior
        if (terms.size() > 3) {
            throw new QueryExecutionException(
                "CONTAINS supports 1 to 3 terms; received " + terms.size() + ".",
                condition.toString(),
                QueryExecutionException.ErrorType.INVALID_CONDITION
            );
        }

        // Validate individual terms
        for (String term : terms) {
            if (term == null || term.trim().isEmpty()) {
                logger.error("CONTAINS condition contains null, empty, or blank term: '{}' in terms: {}", term, terms);
                throw new QueryExecutionException(
                    "CONTAINS condition cannot have null, empty, or blank terms.",
                    condition.toString(),
                    QueryExecutionException.ErrorType.INVALID_CONDITION
                );
            }
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
                    pattern, isVariable, variableName, index, condition, resultSoA, conceptualRowIdCounter, requirements, context);
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
     * Constructs search patterns from terms (simplified wildcard behavior).
     * Builds a single lowercased pattern string. Only if the final pattern ends with '*'
     * will a prefix search be performed later; any '*' elsewhere is treated literally.
     *
     * @param terms The list of terms (may include literal '*')
     * @return Set with a single search pattern to look for
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
     * @param context FilteringContext for filtering
     * @return The updated conceptualRowIdCounter
     * @throws QueryExecutionException If an error occurs during query execution
     * @throws IndexAccessException If an error occurs during index access
     */
    private int executePrefixSearch(String prefix, boolean isVariable, String variableName,
                                        IndexAccessInterface index, Contains condition,
                                        QueryResultSoA resultSoA, int conceptualRowIdCounter,
                                        AttributeRequirements requirements,
                                        Optional<FilteringContext> context)
        throws QueryExecutionException, IndexAccessException {

        logger.debug("Executing prefix search for: {} (populating QueryResultSoA), FilteringContext isPresent: {}", prefix, context.isPresent());
        int originalConceptualRowIdCounter = conceptualRowIdCounter;
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] upperBound = java.util.Arrays.copyOf(prefixBytes, prefixBytes.length + 1);
        upperBound[upperBound.length - 1] = (byte) 0xFF; // exclusive bound covering the prefix range

        try (RocksIterator iterator = index.seekWithBounds(prefixBytes, upperBound, 256 * 1024)) {
            final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(conceptualRowIdCounter);
            ExecutorIndexUtils.iterateGroupedByBase(iterator, prefix, (baseKey, blobs) -> {
                // RB presence merge across all blobs for the base key
                try {
                    RBPresenceIndex mergedPresence = null;
                    for (byte[] b : blobs) {
                        RBPresenceIndex p = RBPresenceIndex.fromBytes(b);
                        mergedPresence = (mergedPresence == null)
                            ? p
                            : (RBPresenceIndex) mergedPresence.or(p);
                    }
                    if (mergedPresence != null) {
                        String actualValue = reconstructValue(baseKey, DELIMITER);
                        org.roaringbitmap.longlong.LongIterator it = mergedPresence.getBitmap().getLongIterator();
                        while (it.hasNext()) {
                            long pair = it.next();
                            int docId = (int)(pair >>> 16);
                            int sentId = (int)(pair & 0xFFFFL);
                            if (!passesContextFilter(context, requirements, docId, sentId)) continue;
                            resultSoA.add(
                                actualValue,
                                ValueType.TERM,
                                isVariable ? variableName : null,
                                docId,
                                requirements.needsSentenceId ? sentId : -1,
                                -1,
                                -1,
                                -1,
                                counter.getAndIncrement()
                            );
                        }
                        // processed
                    }
                } catch (Exception ignorePresence) {
                    // Mixed datasets not supported in RB-only mode; skip non-RB blobs
                }
            });
            conceptualRowIdCounter = counter.get();
        } catch (IndexAccessException iae) {
            throw iae;
        } catch (Exception e) {
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
        String[] parts = key.split(String.valueOf(delimiter));
        return String.join(" ", parts);
    }

    private boolean passesContextFilter(Optional<FilteringContext> context, AttributeRequirements requirements, int docId, int sentId) {
        if (context.isEmpty()) return true;
        FilteringContext fc = context.get();
        if (requirements.needsSentenceId && fc.granularity() == Query.Granularity.SENTENCE) {
            if (fc.allowedDocumentSentenceIds().isPresent()) {
                var allowed = fc.allowedDocumentSentenceIds().get();
                var set = allowed.get(docId);
                return set == null || set.contains(sentId);
            }
        } else if (fc.allowedDocumentIds().isPresent()) {
            return fc.allowedDocumentIds().get().contains(docId);
        }
        return true;
    }

    /**
     * Executes a search for a specific pattern using RB presence bitmaps only.
     * Simplified wildcard behavior: if and only if the entire pattern ends with '*', perform a prefix scan.
     * Otherwise, perform a direct key lookup.
     */
    private int executePatternSearchOptimized(String pattern, boolean isVariable, String variableName,
                                        IndexAccessInterface index, Contains condition, QueryResultSoA resultSoA,
                                        int conceptualRowIdCounter, AttributeRequirements requirements,
                                        Optional<FilteringContext> context)
        throws QueryExecutionException, IndexAccessException {

        logger.debug("Executing optimized pattern search for: {}, variable: {}, contextIsPresent: {}", pattern, variableName, context.isPresent());

        if (pattern == null || pattern.trim().isEmpty()) {
            logger.warn("Skipping empty pattern in CONTAINS condition");
            return conceptualRowIdCounter;
        }

        if (pattern.endsWith("*") && pattern.length() > 1) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            logger.debug("Pattern '{}' ends with '*', performing prefix search for '{}'", pattern, prefix);
            return executePrefixSearch(prefix, isVariable, variableName, index, condition, resultSoA, conceptualRowIdCounter, requirements, context);
        } else {
            // RB presence path (rb_* unigram/bigram/trigram are presence-only)
            try {
                Optional<byte[]> raw = index.getRaw(pattern.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (raw.isPresent()) {
                    try {
                        RBPresenceIndex presence = RBPresenceIndex.fromBytes(raw.get());
                        String actualValue = reconstructValue(pattern, DELIMITER);
                        org.roaringbitmap.longlong.LongIterator it = presence.getBitmap().getLongIterator();
                        while (it.hasNext()) {
                            long pair = it.next();
                            int docId = (int)(pair >>> 16);
                            int sentId = (int)(pair & 0xFFFFL);
                            if (!passesContextFilter(context, requirements, docId, sentId)) continue;
                            resultSoA.add(
                                actualValue,
                                ValueType.TERM,
                                isVariable ? variableName : null,
                                docId,
                                requirements.needsSentenceId ? sentId : -1,
                                -1,
                                -1,
                                -1,
                                conceptualRowIdCounter++
                            );
                        }
                        return conceptualRowIdCounter;
                    } catch (Exception ignorePresence) {
                        // Mixed datasets not supported in RB-only mode
                    }
                }
            } catch (IndexAccessException iae) {
                throw iae;
            }
            return conceptualRowIdCounter;
        }
    }
}