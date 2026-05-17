package com.example.query.executor;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import com.example.query.binding.ValueType;
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
 *
 * <p>
 * When a BIND clause is present, the executor extracts synonym IDs from
 * index keys, resolves them to terms via {@link SynonymManager}, and
 * produces a {@link Bindings} object mapping cell keys to term values.
 */
public final class NerExecutor implements ConditionExecutor<Ner> {
    private static final Logger logger = LoggerFactory.getLogger(NerExecutor.class);

    private static final String NER_INDEX_NAME = "ner";
    private final SynonymManager synonymManager;

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
            String varName = condition.isVariable() ? condition.qualifiedVariableName() : null;
            if (!targets.isEmpty()) {
                result = executeSpecificEntitySearch(normalizedEntityType, targets, index, granularity, mode, varName);
            } else {
                result = executeEntityTypeSearch(normalizedEntityType, index, granularity, mode, varName);
            }

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
     * Performs a prefix scan over {@code TYPE\0} in the NER index. When
     * {@code variableName} is non-null, synonym IDs are extracted from keys
     * and resolved to terms to build a {@link Bindings} object.
     */
    private CellResult executeEntityTypeSearch(String type, IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode,
            String variableName)
            throws IndexAccessException {
        logger.debug("executeEntityTypeSearch: Prefix scan for type '{}', variable={}", type, variableName);

        byte[] prefix = KeySchema.encodeTypePrefix(type);
        // Upper bound must cover all 4 bytes of the synId suffix:
        // keys are TYPE\0<4-byte synId>, so we need prefix+5 bytes of 0xFF
        // to reach one past the maximum possible key (TYPE\0\xFF\xFF\xFF\xFF).
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
                        logger.warn("Failed to deserialize posting list for NER key: {}", e.getMessage());
                    }
                }

                if (keysExamined % 100000 == 0) {
                    logger.debug("executeEntityTypeSearch: Examined {} keys so far for type '{}',"
                            + " {} with data, result has {} cells",
                            keysExamined, type, keysWithData, resultCells.getLongCardinality());
                }
                iter.next();
            }
            logger.debug("executeEntityTypeSearch: Examined {} keys for type '{}',"
                    + " {} with data, result has {} cells",
                    keysExamined, type, keysWithData, resultCells.getLongCardinality());
        }

        if (resultCells.isEmpty()) {
            return CellResult.empty(granularity);
        }

        // Build bindings if variable binding is active
        Bindings bindings = null;
        if (variableName != null && cellSynIds != null && !cellSynIds.isEmpty()) {
            bindings = buildBindings(cellSynIds, variableName);
            if (bindings != null) {
                logger.debug("executeEntityTypeSearch: built {} bindings for variable '{}'",
                        bindings.size(), variableName);
            }
        }

        return CellResult.of(resultCells, bindings, granularity);
    }

    /**
     * Specific entity search (e.g. {@code NER(PERSON, ["John", "Mary"])}).
     * When {@code variableName} is non-null, bindings are built from the
     * resolved synonym IDs.
     */
    private CellResult executeSpecificEntitySearch(String type, List<String> targets,
            IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode,
            String variableName)
            throws IndexAccessException, QueryExecutionException {
        logger.debug("executeSpecificEntitySearch: Type='{}', Targets={}, variable={}", type, targets, variableName);

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
        Map<Long, Integer> cellSynIds = variableName != null ? new LinkedHashMap<>() : null;

        for (int synId : synIds) {
            byte[] key = KeySchema.encodeKey(type, synId);
            Optional<PostingList> plOpt = index.getPostingList(key, mode);
            if (plOpt.isPresent() && !plOpt.get().isEmpty()) {
                CellResult cr = toCellResult(plOpt.get(), granularity, mode);
                result = result.or(cr);
                // Record cellKey -> synId for variable binding
                if (cellSynIds != null) {
                    var cellIter = plOpt.get().cells().getLongIterator();
                    while (cellIter.hasNext()) {
                        long ck = cellIter.next();
                        cellSynIds.putIfAbsent(ck, synId);
                    }
                }
            }
        }

        // Build bindings if variable binding is active
        Bindings bindings = null;
        if (variableName != null && cellSynIds != null && !cellSynIds.isEmpty()) {
            bindings = buildBindings(cellSynIds, variableName);
        }

        logger.debug("executeSpecificEntitySearch: Result has {} cells, {} bindings",
                result.cellCount(), bindings != null ? bindings.size() : 0);
        return CellResult.of(result.cells(), result.occurrences(), bindings, granularity);
    }

    /**
     * Builds a {@link Bindings} object from a cellKey -> synId map by
     * batch-resolving synonym IDs to terms.
     */
    private Bindings buildBindings(Map<Long, Integer> cellSynIds, String variableName) {
        // Collect unique synIds
        Set<Integer> uniqueSynIds = new HashSet<>(cellSynIds.values());

        // Batch-resolve to terms
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
                builder.withCellKey(cellKey)
                        .add(term, ValueType.ENTITY, variableName);
                added++;
            }
        }
        logger.debug("buildBindings: added {} bindings for variable '{}' ({} unique synIds, {} cells)",
                added, variableName, uniqueSynIds.size(), cellSynIds.size());
        return added > 0 ? builder.build() : null;
    }

    /** Reads a 4-byte big-endian int from {@code buf} at {@code offset}. */
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
