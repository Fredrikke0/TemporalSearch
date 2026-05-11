package com.example.query.executor;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.index.KeySchema;
import com.example.index.util.SynonymManager;
import com.example.query.model.Query;
import com.example.query.model.condition.Ner;

/**
 * Executor for NER (Named Entity Recognition) conditions, excluding DATE.
 * Handles entity type matching via prefix scans and specific entity lookups
 * using the new {@code KeySchema}/{@code CellResult} interface.
 *
 * <p>
 * The NER index stores keys as {@code TYPE\0<4-byte synId>} (see
 * {@link KeySchema#encodeKey(String, int)}). A prefix scan over
 * {@code TYPE\0} retrieves all entities of that type; a specific lookup
 * encodes the synonym ID of the desired entity value.
 */
public final class NerExecutor implements ConditionExecutor<Ner> {
    private static final Logger logger = LoggerFactory.getLogger(NerExecutor.class);

    private static final String NER_INDEX_NAME = "ner";
    private final SynonymManager synonymManager;

    /**
     * Creates a new NER executor.
     *
     * @param synonymManager the synonym manager instance
     */
    public NerExecutor(SynonymManager synonymManager) {
        this.synonymManager = synonymManager;
    }

    @Override
    public CellResult execute(Ner condition, Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Optional<Roaring64NavigableMap> allowedCells)
            throws QueryExecutionException {
        logger.debug(">>> Executing NerExecutor");
        logger.debug("Executing NER condition: {}, granularity: {}", condition, granularity);

        String entityType = condition.entityType();
        String normalizedEntityType = entityType.toUpperCase();

        // This executor specifically does not handle DATE entities.
        if ("DATE".equals(normalizedEntityType)) {
            throw new QueryExecutionException(
                    "NER(DATE) queries should be handled by TemporalExecutor, not NerExecutor.",
                    condition.toString(),
                    QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }

        validateNoWildcards(normalizedEntityType, condition.targets(), condition.toString());

        IndexAccessInterface index = indexes.get(NER_INDEX_NAME);
        if (index == null) {
            throw new QueryExecutionException(
                    "Missing required NER index: " + NER_INDEX_NAME,
                    condition.toString(),
                    QueryExecutionException.ErrorType.MISSING_INDEX);
        }

        PostingList.DeserializeMode mode = requirements.toDeserializeMode();
        CellResult result;

        try {
            List<String> targets = condition.targets();
            if (!targets.isEmpty()) {
                // Specific entity search: resolve targets to synIds, exact key lookups
                result = executeSpecificEntitySearch(normalizedEntityType, targets, index, granularity, mode);
            } else {
                // Entity type only search (with or without variable binding): prefix scan
                result = executeEntityTypeSearch(normalizedEntityType, index, granularity, mode);
            }

            // Apply allowedCells filtering if present
            if (allowedCells.isPresent() && !allowedCells.get().isEmpty()) {
                logger.debug("Applying allowedCells filter with {} cells",
                        allowedCells.get().getLongCardinality());
                result = result.and(CellResult.of(allowedCells.get(), granularity));
            }

        } catch (IndexAccessException e) {
            throw new QueryExecutionException("Error accessing NER index: " + e.getMessage(),
                    e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (Exception e) {
            if (e instanceof QueryExecutionException qee) {
                throw qee;
            }
            throw new QueryExecutionException("Unexpected error executing NER condition: " + e.getMessage(),
                    e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        logger.debug("NER condition execution produced CellResult with {} cells", result.cellCount());
        return result;
    }

    private void validateNoWildcards(String entityType, List<String> targets, String conditionStr)
            throws QueryExecutionException {
        if (entityType.contains("*")) {
            throw new QueryExecutionException(
                    "Wildcard in entity type ('" + entityType + "') is not supported.",
                    conditionStr, QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }
        for (String target : targets) {
            if (target != null && target.contains("*")) {
                throw new QueryExecutionException(
                        "Wildcard in target value ('" + target + "') is not supported.",
                        conditionStr, QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
            }
        }
    }

    /**
     * Entity type only search (e.g. {@code NER(PERSON)} or
     * {@code NER(PERSON) BIND var}).
     * <p>
     * Performs a prefix scan over {@code TYPE\0} in the NER index, fetches the
     * posting list for each key, and ORs all results together.
     */
    private CellResult executeEntityTypeSearch(String type, IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode)
            throws IndexAccessException {
        logger.debug("executeEntityTypeSearch: Prefix scan for type '{}'", type);

        byte[] prefix = KeySchema.encodeTypePrefix(type);
        CellResult result = CellResult.empty(granularity);

        try (RocksIterator iter = index.seek(prefix)) {
            int keysExamined = 0;
            while (iter.isValid()) {
                byte[] key = iter.key();
                if (!startsWith(key, prefix)) {
                    break;
                }
                keysExamined++;

                Optional<PostingList> plOpt = index.getPostingList(key, mode);
                if (plOpt.isPresent() && !plOpt.get().isEmpty()) {
                    CellResult cr = toCellResult(plOpt.get(), granularity, mode);
                    result = result.or(cr);
                }
                iter.next();
            }
            logger.debug("executeEntityTypeSearch: Examined {} keys for type '{}', result has {} cells",
                    keysExamined, type, result.cellCount());
        }

        return result;
    }

    /**
     * Specific entity search (e.g. {@code NER(PERSON, ["John", "Mary"])}).
     * <p>
     * Resolves each target value to a synonym ID, builds the exact key
     * {@code TYPE\0<synId>}, fetches the posting list, and ORs all results.
     */
    private CellResult executeSpecificEntitySearch(String type, List<String> targets,
            IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode)
            throws IndexAccessException, QueryExecutionException {
        logger.debug("executeSpecificEntitySearch: Type='{}', Targets={}", type, targets);

        // Resolve targets to synonym IDs, deduplicating
        Set<Integer> synIds = new HashSet<>();
        for (String target : targets) {
            String normalized = target.toLowerCase();
            try {
                int synId = synonymManager.getId(normalized);
                synIds.add(synId);
                logger.debug("Resolved target '{}' -> synId {}", target, synId);
            } catch (RocksDBException e) {
                logger.warn("Failed to get synonym ID for target '{}': {}", target, e.getMessage());
            }
        }

        if (synIds.isEmpty()) {
            logger.debug("executeSpecificEntitySearch: No valid synonym IDs resolved for targets {}", targets);
            return CellResult.empty(granularity);
        }

        CellResult result = CellResult.empty(granularity);
        for (int synId : synIds) {
            byte[] key = KeySchema.encodeKey(type, synId);
            Optional<PostingList> plOpt = index.getPostingList(key, mode);
            if (plOpt.isPresent() && !plOpt.get().isEmpty()) {
                CellResult cr = toCellResult(plOpt.get(), granularity, mode);
                result = result.or(cr);
            }
        }

        logger.debug("executeSpecificEntitySearch: Result has {} cells", result.cellCount());
        return result;
    }

    /**
     * Converts a PostingList to a CellResult, choosing the appropriate factory
     * based on deserialization mode.
     */
    private static CellResult toCellResult(PostingList pl, Query.Granularity granularity,
            PostingList.DeserializeMode mode) {
        if (mode == PostingList.DeserializeMode.FULL) {
            return CellResult.fromPostingListWithOccurrences(pl, granularity);
        }
        return CellResult.fromPostingList(pl, granularity);
    }

    /** Checks if {@code array} starts with {@code prefix}. */
    private static boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
