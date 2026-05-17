package com.example.query.executor;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Pos;

/**
 * Executor for POS (Part-of-Speech) conditions.
 * Handles matching POS tags against indexed data.
 *
 * <p>
 * The POS index stores keys as {@code TAG\0<4-byte synId>}. A prefix scan
 * over {@code TAG\0} retrieves all terms with that tag. When a BIND clause
 * is present, synonym IDs are extracted from keys and resolved to terms.
 */
public final class PosExecutor implements ConditionExecutor<Pos> {
    private static final Logger logger = LoggerFactory.getLogger(PosExecutor.class);
    private static final String ALL_POS_TAGS_WILDCARD = "*";
    private static final String POS_INDEX_NAME = "pos";
    private final SynonymManager synonymManager;

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
            String varName = condition.isVariable() ? condition.variableName() : null;
            if (termFromQuery != null) {
                result = executeSpecificTermSearch(tagFromQuery, termFromQuery, posIndex, granularity, mode, varName);
            } else {
                result = executeTagOnlySearch(tagFromQuery, posIndex, granularity, mode, varName);
            }

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

    private CellResult executeSpecificTermSearch(String tag, String term,
            IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode,
            String variableName)
            throws IndexAccessException {
        String normalizedTerm = term.toLowerCase();
        int synId;
        try {
            synId = synonymManager.getId(normalizedTerm);
            logger.debug("executeSpecificTermSearch: Tag='{}', Term='{}', SynId={}, variable={}",
                    tag, term, synId, variableName);
        } catch (RocksDBException e) {
            logger.warn("Failed to get synonym ID for term '{}': {}", term, e.getMessage());
            return CellResult.empty(granularity);
        }

        byte[] key = KeySchema.encodeKey(tag, synId);
        Optional<PostingList> plOpt = index.getPostingList(key, mode);

        if (plOpt.isPresent() && !plOpt.get().isEmpty()) {
            PostingList pl = plOpt.get();
            CellResult cr = toCellResult(pl, granularity, mode);
            if (variableName != null) {
                Bindings bindings = buildBindingsForSynId(pl.cells(), synId, variableName);
                return CellResult.of(cr.cells(), bindings, granularity);
            }
            return cr;
        }
        logger.debug("executeSpecificTermSearch: No data for tag '{}', synId {}", tag, synId);
        return CellResult.empty(granularity);
    }

    private CellResult executeTagOnlySearch(String tag, IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode,
            String variableName)
            throws IndexAccessException {
        logger.debug("executeTagOnlySearch: Prefix scan for tag '{}', variable={}", tag, variableName);

        byte[] prefix = KeySchema.encodeTypePrefix(tag);
        // Upper bound must cover all 4 bytes of the synId suffix:
        // keys are TAG\0<4-byte synId>, so we need prefix+5 bytes of 0xFF.
        byte[] upperBound = Arrays.copyOf(prefix, prefix.length + 5);
        for (int i = prefix.length; i < upperBound.length; i++) {
            upperBound[i] = (byte) 0xFF;
        }

        Roaring64NavigableMap resultCells = new Roaring64NavigableMap();
        Map<Long, Integer> cellSynIds = variableName != null ? new LinkedHashMap<>() : null;

        try (RocksIterator iter = index.seekWithBounds(prefix, upperBound, 256 * 1024)) {
            int keysExamined = 0;
            int keysWithData = 0;
            while (iter.isValid()) {
                byte[] key = iter.key();
                if (!startsWith(key, prefix)) {
                    break;
                }
                keysExamined++;

                // Extract synId from key when variable binding is active
                int synId = -1;
                if (variableName != null && key.length >= prefix.length + 4) {
                    synId = readIntBE(key, prefix.length);
                }

                // Use iterator value directly instead of a separate db.get()
                byte[] value = iter.value();
                if (value != null && value.length > 0) {
                    try {
                        PostingList pl = PostingList.deserialize(value, mode);
                        if (!pl.isEmpty()) {
                            resultCells.or(pl.cells());
                            keysWithData++;
                            // Record cellKey -> synId for variable binding
                            if (cellSynIds != null && synId >= 0) {
                                var cellIter = pl.cells().getLongIterator();
                                while (cellIter.hasNext()) {
                                    long ck = cellIter.next();
                                    cellSynIds.putIfAbsent(ck, synId);
                                }
                            }
                        }
                    } catch (IOException e) {
                        logger.warn("Failed to deserialize posting list for POS key: {}", e.getMessage());
                    }
                }

                if (keysExamined % 50000 == 0) {
                    logger.debug("executeTagOnlySearch: Examined {} keys so far for tag '{}',"
                            + " {} with data, result has {} cells",
                            keysExamined, tag, keysWithData, resultCells.getLongCardinality());
                }
                iter.next();
            }
            logger.debug("executeTagOnlySearch: Examined {} keys for tag '{}',"
                    + " {} with data, result has {} cells",
                    keysExamined, tag, keysWithData, resultCells.getLongCardinality());
        }

        if (resultCells.isEmpty()) {
            return CellResult.empty(granularity);
        }

        // Build bindings if variable binding is active
        Bindings bindings = null;
        if (variableName != null && cellSynIds != null && !cellSynIds.isEmpty()) {
            bindings = buildBindings(cellSynIds, variableName);
            if (bindings != null) {
                logger.debug("executeTagOnlySearch: built {} bindings for variable '{}'",
                        bindings.size(), variableName);
            }
        }

        return CellResult.of(resultCells, bindings, granularity);
    }

    private Bindings buildBindings(Map<Long, Integer> cellSynIds, String variableName) {
        Set<Integer> uniqueSynIds = new HashSet<>(cellSynIds.values());
        Map<Integer, String> terms;
        try {
            terms = synonymManager.getTerms(uniqueSynIds);
        } catch (RocksDBException e) {
            logger.error("Failed to resolve synonym IDs to terms: {}", e.getMessage());
            return null;
        }

        // Iterate cellSynIds in insertion order (LinkedHashMap) so that
        // ResultMaterializer.groupBindingsByCellKey can correctly distribute
        // bindings across cells in cell-sorted order.
        Bindings.Builder builder = Bindings.builder();
        int added = 0;
        for (Map.Entry<Long, Integer> entry : cellSynIds.entrySet()) {
            long cellKey = entry.getKey();
            int synId = entry.getValue();
            String term = terms.get(synId);
            if (term != null) {
                builder.withCellKey(cellKey).add(term, ValueType.TERM, variableName);
                added++;
            }
        }
        logger.debug("buildBindings: added {} bindings for variable '{}' ({} unique synIds, {} cells)",
                added, variableName, uniqueSynIds.size(), cellSynIds.size());
        return added > 0 ? builder.build() : null;
    }

    private Bindings buildBindingsForSynId(Roaring64NavigableMap cells, int synId, String variableName) {
        String term;
        try {
            Map<Integer, String> terms = synonymManager.getTerms(Set.of(synId));
            term = terms.get(synId);
        } catch (RocksDBException e) {
            logger.error("Failed to resolve synonym ID {}: {}", synId, e.getMessage());
            return null;
        }
        if (term == null)
            return null;

        Bindings.Builder builder = Bindings.builder();
        var cellIter = cells.getLongIterator();
        while (cellIter.hasNext()) {
            long ck = cellIter.next();
            builder.withCellKey(ck).add(term, ValueType.TERM, variableName);
        }
        return builder.build();
    }

    private static int readIntBE(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24)
                | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8)
                | (buf[offset + 3] & 0xFF);
    }

    private static CellResult toCellResult(PostingList pl, Query.Granularity granularity,
            PostingList.DeserializeMode mode) {
        if (mode == PostingList.DeserializeMode.FULL) {
            return CellResult.fromPostingListWithOccurrences(pl, granularity);
        }
        return CellResult.fromPostingList(pl, granularity);
    }

    private static boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length)
            return false;
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i])
                return false;
        }
        return true;
    }
}
