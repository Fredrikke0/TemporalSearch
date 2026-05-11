package com.example.query.executor;

import java.util.Map;
import java.util.Optional;

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
import com.example.query.model.condition.Pos;

/**
 * Executor for POS (Part-of-Speech) conditions.
 * Handles matching POS tags against indexed data using the new
 * {@code KeySchema}/{@code CellResult} interface.
 *
 * <p>
 * The POS index stores keys as {@code TAG\0<4-byte synId>} (see
 * {@link KeySchema#encodeKey(String, int)}). A prefix scan over
 * {@code TAG\0} retrieves all terms with that tag; a specific lookup
 * encodes the synonym ID of the desired term value.
 */
public final class PosExecutor implements ConditionExecutor<Pos> {
    private static final Logger logger = LoggerFactory.getLogger(PosExecutor.class);
    private static final String ALL_POS_TAGS_WILDCARD = "*";
    private static final String POS_INDEX_NAME = "pos";
    private final SynonymManager synonymManager;

    /**
     * Creates a new POS executor.
     *
     * @param synonymManager the synonym manager instance
     */
    public PosExecutor(SynonymManager synonymManager) {
        this.synonymManager = synonymManager;
    }

    @Override
    public CellResult execute(Pos condition, Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Optional<Roaring64NavigableMap> allowedCells)
            throws QueryExecutionException {
        logger.debug(">>> Executing PosExecutor");
        logger.debug("Executing POS condition: {}, granularity: {}", condition, granularity);

        IndexAccessInterface posIndex = indexes.get(POS_INDEX_NAME);
        if (posIndex == null) {
            throw new QueryExecutionException("POS index not found in provided indexes.",
                    condition.toString(), QueryExecutionException.ErrorType.MISSING_INDEX);
        }

        String tagFromQuery = condition.posTag().toUpperCase();
        String termFromQuery = condition.term();

        logger.debug("POS condition details: tag='{}', term='{}', isVariable={}, variableName='{}'",
                tagFromQuery, termFromQuery,
                condition.isVariable(),
                condition.isVariable() ? condition.variableName() : "(none)");

        if (ALL_POS_TAGS_WILDCARD.equals(tagFromQuery)) {
            throw new QueryExecutionException(
                    "Wildcard POS tag (*) is not supported for direct execution.",
                    condition.toString(),
                    QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }
        if (termFromQuery != null && termFromQuery.contains("*")) {
            throw new QueryExecutionException(
                    "Wildcard in target term ('" + termFromQuery + "') for POS condition is not supported.",
                    condition.toString(),
                    QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION);
        }

        PostingList.DeserializeMode mode = requirements.toDeserializeMode();
        CellResult result;

        try {
            if (termFromQuery != null) {
                // Specific term search: resolve term to synId, exact key lookup
                result = executeSpecificTermSearch(tagFromQuery, termFromQuery, posIndex, granularity, mode);
            } else {
                // Tag-only or variable binding (bind any term with that tag): prefix scan
                result = executeTagOnlySearch(tagFromQuery, posIndex, granularity, mode);
            }

            // Apply allowedCells filtering if present
            if (allowedCells.isPresent() && !allowedCells.get().isEmpty()) {
                logger.debug("Applying allowedCells filter with {} cells",
                        allowedCells.get().getLongCardinality());
                result = result.and(CellResult.of(allowedCells.get(), granularity));
            }

        } catch (IndexAccessException e) {
            logger.error("IndexAccessException during POS condition execution: {}", e.getMessage(), e);
            throw new QueryExecutionException(
                    "Unexpected index access error executing POS condition: " + e.getMessage(),
                    e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        } catch (Exception e) {
            if (e instanceof QueryExecutionException qee) {
                throw qee;
            }
            logger.error("Unexpected error during POS condition execution: {}", e.getMessage(), e);
            throw new QueryExecutionException(
                    "Unexpected error executing POS condition: " + e.getMessage(),
                    e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }

        logger.debug("POS condition execution produced CellResult with {} cells", result.cellCount());
        return result;
    }

    /**
     * Specific term search: {@code POS(tag, term)} or
     * {@code POS(tag, term) BIND var}.
     * <p>
     * Resolves the term to a synonym ID, builds the exact key
     * {@code TAG\0<synId>}, and fetches the posting list.
     */
    private CellResult executeSpecificTermSearch(String tag, String term,
            IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode)
            throws IndexAccessException {
        String normalizedTerm = term.toLowerCase();
        int synId;
        try {
            synId = synonymManager.getId(normalizedTerm);
            logger.debug("executeSpecificTermSearch: Tag='{}', Term='{}', SynId={}", tag, term, synId);
        } catch (RocksDBException e) {
            logger.warn("Failed to get synonym ID for term '{}': {}", term, e.getMessage());
            return CellResult.empty(granularity);
        }

        byte[] key = KeySchema.encodeKey(tag, synId);
        Optional<PostingList> plOpt = index.getPostingList(key, mode);

        if (plOpt.isPresent() && !plOpt.get().isEmpty()) {
            return toCellResult(plOpt.get(), granularity, mode);
        }
        logger.debug("executeSpecificTermSearch: No data for tag '{}', synId {}", tag, synId);
        return CellResult.empty(granularity);
    }

    /**
     * Tag-only search: {@code POS(tag)} or {@code POS(tag) BIND var} (bind
     * any term with that tag).
     * <p>
     * Performs a prefix scan over {@code TAG\0} in the POS index, fetches the
     * posting list for each key, and ORs all results together.
     */
    private CellResult executeTagOnlySearch(String tag, IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode)
            throws IndexAccessException {
        logger.debug("executeTagOnlySearch: Prefix scan for tag '{}'", tag);

        byte[] prefix = KeySchema.encodeTypePrefix(tag);
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
            logger.debug("executeTagOnlySearch: Examined {} keys for tag '{}', result has {} cells",
                    keysExamined, tag, result.cellCount());
        }

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
