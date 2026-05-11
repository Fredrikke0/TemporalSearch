package com.example.query.executor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.rocksdb.RocksIterator;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.query.model.Query;
import com.example.query.model.condition.Dependency;

/**
 * Executor for DEPENDENCY conditions using the CellResult-based interface.
 *
 * <p>
 * Looks up dependency triples (governor, relation, dependent) in the
 * dependency index. For exact matches, a direct key lookup is performed.
 * For wildcard/variable searches, a prefix scan iterates matching keys and
 * ORs their CellResults together.
 *
 * <p>
 * The index key format is:
 * {@code governor \0 relation \0 dependent}
 */
public final class DependencyExecutor implements ConditionExecutor<Dependency> {
    private static final Logger logger = LoggerFactory.getLogger(DependencyExecutor.class);
    private static final String DEPENDENCY_INDEX_NAME = "dependency";

    public DependencyExecutor() {
    }

    @Override
    public CellResult execute(Dependency condition, Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Optional<Roaring64NavigableMap> allowedCells)
            throws QueryExecutionException {

        logger.debug(">>> Executing DependencyExecutor (granularity={}, allowedCellsPresent={})",
                granularity, allowedCells.isPresent());

        if (!indexes.containsKey(DEPENDENCY_INDEX_NAME)) {
            throw new QueryExecutionException(
                    "Missing required dependency index",
                    condition.toString(),
                    QueryExecutionException.ErrorType.MISSING_INDEX);
        }

        IndexAccessInterface index = indexes.get(DEPENDENCY_INDEX_NAME);
        if (index == null) {
            throw new QueryExecutionException(
                    "Required index not found: " + DEPENDENCY_INDEX_NAME,
                    condition.toString(),
                    QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }

        PostingList.DeserializeMode mode = requirements.toDeserializeMode();

        try {
            String governor = condition.governor();
            String dependent = condition.dependent();
            String relation = condition.relation();
            boolean isVariableBinding = condition.isVariable();

            // Determine which fields are specific literals
            boolean govIsSpecific = (governor != null && !"*".equals(governor));
            boolean relIsSpecific = (relation != null && !"*".equals(relation));
            boolean depIsSpecific = (dependent != null && !"*".equals(dependent));

            CellResult result;
            if (govIsSpecific && relIsSpecific && depIsSpecific && !isVariableBinding) {
                // All parts specific — exact key lookup
                result = executeSpecificSearch(condition, index, granularity, mode);
            } else {
                // At least one wildcard or variable — prefix scan
                result = executeVariableSearch(condition, index, granularity, mode);
            }

            // Apply allowedCells filtering at the end
            if (allowedCells.isPresent() && !result.isEmpty()) {
                Roaring64NavigableMap filtered = result.cells().clone();
                filtered.and(allowedCells.get());
                result = CellResult.of(filtered, granularity);
                logger.debug("Applied allowedCells filter: {} cells remain", filtered.getLongCardinality());
            }

            logger.debug("DEPENDENCY result: {} cells", result.cellCount());
            return result;

        } catch (IndexAccessException e) {
            throw new QueryExecutionException(
                    "Error accessing index for DEPENDENCY condition: " + e.getMessage(),
                    e,
                    condition.toString(),
                    QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }
    }

    /**
     * Performs an exact key lookup when all three parts of the dependency
     * triple are specific literals and no variable binding is active.
     */
    private CellResult executeSpecificSearch(Dependency condition, IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode)
            throws IndexAccessException {

        String normalizedGovernor = condition.governor().toLowerCase();
        String normalizedDependent = condition.dependent().toLowerCase();
        String normalizedRelation = condition.relation().toLowerCase();

        String searchKey = normalizedGovernor + IndexAccessInterface.DELIMITER
                + normalizedRelation + IndexAccessInterface.DELIMITER
                + normalizedDependent;

        logger.debug("Searching for specific dependency key: '{}'", searchKey);

        byte[] keyBytes = searchKey.getBytes(StandardCharsets.UTF_8);
        Optional<PostingList> plOpt = index.getPostingList(keyBytes, mode);

        if (plOpt.isEmpty() || plOpt.get().isEmpty()) {
            logger.debug("No positions found for dependency key: '{}'", searchKey);
            return CellResult.empty(granularity);
        }

        PostingList pl = plOpt.get();
        logger.debug("Found {} cells for dependency key '{}'", pl.cells().getLongCardinality(), searchKey);

        if (mode == PostingList.DeserializeMode.FULL) {
            return CellResult.fromPostingListWithOccurrences(pl, granularity);
        } else {
            return CellResult.fromPostingList(pl, granularity);
        }
    }

    /**
     * Performs a prefix scan when at least one part of the dependency triple
     * is a wildcard or variable binding is active.
     */
    private CellResult executeVariableSearch(Dependency condition, IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode)
            throws IndexAccessException {

        logger.debug("Executing variable/wildcard search for dependency");

        String governorFilterLower = (condition.governor() != null && !"*".equals(condition.governor()))
                ? condition.governor().toLowerCase()
                : null;
        String relationFilterLower = (condition.relation() != null && !"*".equals(condition.relation()))
                ? condition.relation().toLowerCase()
                : null;
        String dependentFilterLower = (condition.dependent() != null && !"*".equals(condition.dependent()))
                ? condition.dependent().toLowerCase()
                : null;

        // Build prefix from known parts
        StringBuilder prefixBuilder = new StringBuilder();
        if (governorFilterLower != null) {
            prefixBuilder.append(governorFilterLower).append(IndexAccessInterface.DELIMITER);
            if (relationFilterLower != null) {
                prefixBuilder.append(relationFilterLower).append(IndexAccessInterface.DELIMITER);
            }
        }
        String prefix = prefixBuilder.length() > 0 ? prefixBuilder.toString() : null;

        CellResult result = CellResult.empty(granularity);

        try (RocksIterator iterator = getIteratorForSearch(index, governorFilterLower, relationFilterLower)) {
            if (iterator == null) {
                logger.warn("RocksIterator was not initialized. Returning empty result.");
                return result;
            }

            int keysMatched = 0;
            while (iterator.isValid()) {
                byte[] keyBytes = iterator.key();
                String key = new String(keyBytes, StandardCharsets.UTF_8);

                // Stop if we've moved beyond the prefix
                if (prefix != null && !key.startsWith(prefix)) {
                    break;
                }

                // Parse the key and apply filters
                String[] parts = key.split(String.valueOf(IndexAccessInterface.DELIMITER));
                if (parts.length == 3) {
                    String currentGovernor = parts[0];
                    String currentRelation = parts[1];
                    String currentDependent = parts[2];

                    // Apply per-field filters
                    if (governorFilterLower != null && !currentGovernor.equals(governorFilterLower)) {
                        iterator.next();
                        continue;
                    }
                    if (relationFilterLower != null && !currentRelation.equals(relationFilterLower)) {
                        iterator.next();
                        continue;
                    }
                    if (dependentFilterLower != null && !currentDependent.equals(dependentFilterLower)) {
                        iterator.next();
                        continue;
                    }

                    Optional<PostingList> plOpt = index.getPostingList(keyBytes, mode);
                    if (plOpt.isPresent() && !plOpt.get().isEmpty()) {
                        PostingList pl = plOpt.get();
                        CellResult keyResult = (mode == PostingList.DeserializeMode.FULL)
                                ? CellResult.fromPostingListWithOccurrences(pl, granularity)
                                : CellResult.fromPostingList(pl, granularity);
                        result = result.or(keyResult);
                        keysMatched++;
                    }
                } else {
                    logger.warn("Skipping invalid key format in dependency index: {}", key);
                }
                iterator.next();
            }
            logger.debug("Variable search for dependency matched {} keys, result has {} cells",
                    keysMatched, result.cellCount());
        }

        return result;
    }

    /**
     * Creates and positions a RocksIterator based on the governor and relation
     * filters. If both are present, a bounded prefix seek is used.
     */
    private RocksIterator getIteratorForSearch(IndexAccessInterface index,
            String governorFilterLower,
            String relationFilterLower)
            throws IndexAccessException {

        StringBuilder prefixBuilder = new StringBuilder();
        if (governorFilterLower != null) {
            prefixBuilder.append(governorFilterLower).append(IndexAccessInterface.DELIMITER);
            if (relationFilterLower != null) {
                prefixBuilder.append(relationFilterLower).append(IndexAccessInterface.DELIMITER);
            }
        }

        if (prefixBuilder.length() > 0) {
            String prefix = prefixBuilder.toString();
            logger.debug("Using bounded prefix seek for dependency: '{}'", prefix);
            byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
            byte[] upperBound = Arrays.copyOf(prefixBytes, prefixBytes.length + 1);
            upperBound[upperBound.length - 1] = (byte) 0xFF;
            return index.seekWithBounds(prefixBytes, upperBound, 256 * 1024);
        } else {
            logger.debug("No prefix possible (wildcards or missing governor), iterating from first.");
            return index.iterateFromFirst();
        }
    }
}
