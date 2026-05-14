package com.example.query.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.rocksdb.RocksIterator;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.query.model.Query;
import com.example.query.model.condition.Contains;

/**
 * Executor for CONTAINS conditions using the CellResult-based interface.
 *
 * <p>
 * Handles n-gram pattern matching and variable binding.
 * Cells are built from {@link PostingList}s via
 * {@link CellResult#fromPostingList}
 * or {@link CellResult#fromPostingListWithOccurrences}, and combined with
 * {@link CellResult#or(CellResult)} for prefix/wildcard scans.
 *
 * <p>
 * Wildcards: only a trailing '*' on the entire pattern triggers a prefix scan
 * against the selected index (e.g., "apple*"). Any '*' elsewhere is treated
 * as a literal character.
 */
public final class ContainsExecutor implements ConditionExecutor<Contains> {
    private static final Logger logger = LoggerFactory.getLogger(ContainsExecutor.class);

    private static final String UNIGRAM_INDEX = "unigram";
    private static final String BIGRAM_INDEX = "bigram";
    private static final String TRIGRAM_INDEX = "trigram";
    private static final char DELIMITER = IndexAccessInterface.DELIMITER;

    public ContainsExecutor() {
    }

    @Override
    public CellResult execute(Contains condition, Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Optional<Roaring64NavigableMap> allowedCells)
            throws QueryExecutionException {

        logger.debug(">>> Executing ContainsExecutor (granularity={}, allowedCellsPresent={})",
                granularity, allowedCells.isPresent());

        // Validate terms
        List<String> terms = condition.terms();
        if (terms.isEmpty()) {
            throw new QueryExecutionException(
                    "Contains condition must have at least one term.",
                    condition.toString(),
                    QueryExecutionException.ErrorType.INVALID_CONDITION);
        }
        if (terms.size() > 3) {
            throw new QueryExecutionException(
                    "CONTAINS supports 1 to 3 terms; received " + terms.size() + ".",
                    condition.toString(),
                    QueryExecutionException.ErrorType.INVALID_CONDITION);
        }
        for (String term : terms) {
            if (term == null || term.trim().isEmpty()) {
                throw new QueryExecutionException(
                        "CONTAINS condition cannot have null, empty, or blank terms.",
                        condition.toString(),
                        QueryExecutionException.ErrorType.INVALID_CONDITION);
            }
        }

        // Select the correct n-gram index
        IndexAccessInterface index = switch (terms.size()) {
            case 1 -> indexes.get(UNIGRAM_INDEX);
            case 2 -> indexes.get(BIGRAM_INDEX);
            case 3 -> indexes.get(TRIGRAM_INDEX);
            default -> throw new QueryExecutionException(
                    "Unsupported n-gram size: " + terms.size(),
                    condition.toString(),
                    QueryExecutionException.ErrorType.INVALID_CONDITION);
        };
        if (index == null) {
            String missing = terms.size() == 1 ? UNIGRAM_INDEX
                    : (terms.size() == 2 ? BIGRAM_INDEX : TRIGRAM_INDEX);
            throw new QueryExecutionException(
                    "Required " + missing + " index not found.",
                    "CONTAINS(" + String.join(", ", terms) + ")",
                    QueryExecutionException.ErrorType.MISSING_INDEX);
        }

        PostingList.DeserializeMode mode = requirements.toDeserializeMode();
        CellResult result = CellResult.empty(granularity);

        try {
            Set<String> patterns = constructSearchPatterns(terms);

            for (String pattern : patterns) {
                CellResult patternResult = executePattern(pattern, index, granularity, mode);
                result = result.or(patternResult);
            }

            // Apply allowedCells filtering at the end
            if (allowedCells.isPresent() && !result.isEmpty()) {
                Roaring64NavigableMap filtered = result.cells().clone();
                filtered.and(allowedCells.get());
                result = CellResult.of(filtered, granularity);
                logger.debug("Applied allowedCells filter: {} cells remain", filtered.getLongCardinality());
            }

            logger.debug("CONTAINS result: {} cells", result.cellCount());
            return result;
        } catch (IndexAccessException e) {
            throw new QueryExecutionException(
                    "Index access error during CONTAINS execution.",
                    e,
                    condition.toString(),
                    QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }
    }

    /**
     * Constructs search patterns from terms. Builds a single lowercased pattern
     * string from all terms joined by the n-gram delimiter.
     */
    private Set<String> constructSearchPatterns(List<String> terms) {
        if (terms.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> patterns = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            sb.append(terms.get(i).toLowerCase());
            if (i < terms.size() - 1) {
                sb.append(DELIMITER);
            }
        }
        patterns.add(sb.toString());
        return patterns;
    }

    /**
     * Executes a single pattern. If the pattern ends with '*', a prefix scan is
     * performed; otherwise an exact key lookup is used.
     */
    private CellResult executePattern(String pattern, IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode)
            throws IndexAccessException {

        if (pattern == null || pattern.trim().isEmpty()) {
            logger.warn("Skipping empty pattern in CONTAINS condition");
            return CellResult.empty(granularity);
        }

        if (pattern.endsWith("*") && pattern.length() > 1) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            logger.debug("Pattern '{}' ends with '*', performing prefix scan for '{}'", pattern, prefix);
            return executePrefixScan(prefix, index, granularity, mode);
        } else {
            logger.debug("Performing exact lookup for pattern: '{}'", pattern);
            return executeExactLookup(pattern, index, granularity, mode);
        }
    }

    /**
     * Performs an exact key lookup and returns a CellResult built from the
     * PostingList.
     */
    private CellResult executeExactLookup(String key, IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode)
            throws IndexAccessException {

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        Optional<PostingList> plOpt = index.getPostingList(keyBytes, mode);

        if (plOpt.isEmpty() || plOpt.get().isEmpty()) {
            logger.debug("No positions found for exact key: '{}'", key);
            return CellResult.empty(granularity);
        }

        PostingList pl = plOpt.get();
        if (mode == PostingList.DeserializeMode.FULL) {
            return CellResult.fromPostingListWithOccurrences(pl, granularity);
        } else {
            return CellResult.fromPostingList(pl, granularity);
        }
    }

    /**
     * Performs a prefix scan over the index using a bounded RocksIterator.
     * Uses the iterator's value directly (avoiding a separate db.get() per key)
     * and ORs all bitmap data into a single accumulator to avoid O(n²) clone
     * cost from repeated {@link CellResult#or(CellResult)} calls.
     */
    private CellResult executePrefixScan(String prefix, IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode)
            throws IndexAccessException {

        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] upperBound = Arrays.copyOf(prefixBytes, prefixBytes.length + 1);
        upperBound[upperBound.length - 1] = (byte) 0xFF;

        Roaring64NavigableMap resultCells = new Roaring64NavigableMap();

        try (RocksIterator iterator = index.seekWithBounds(prefixBytes, upperBound, 256 * 1024)) {
            int keysMatched = 0;
            while (iterator.isValid()) {
                byte[] keyBytes = iterator.key();
                String currentKey = new String(keyBytes, StandardCharsets.UTF_8);
                if (!currentKey.startsWith(prefix)) {
                    break;
                }

                // Use iterator value directly instead of a separate db.get()
                byte[] value = iterator.value();
                if (value != null && value.length > 0) {
                    try {
                        PostingList pl = PostingList.deserialize(value, mode);
                        if (!pl.isEmpty()) {
                            resultCells.or(pl.cells());
                            keysMatched++;
                        }
                    } catch (IOException e) {
                        logger.warn("Failed to deserialize posting list for prefix key '{}': {}",
                                currentKey, e.getMessage());
                    }
                }
                iterator.next();
            }
            logger.debug("Prefix scan for '{}' matched {} keys, result has {} cells",
                    prefix, keysMatched, resultCells.getLongCardinality());
        }

        if (resultCells.isEmpty()) {
            return CellResult.empty(granularity);
        }
        return CellResult.of(resultCells, granularity);
    }
}
