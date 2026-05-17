package com.example.query.executor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.rocksdb.RocksIterator;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Dependency;

/**
 * Executor for DEPENDENCY conditions.
 *
 * <p>
 * The index key format is {@code governor \0 relation \0 dependent}.
 * When a BIND clause is present, the value of the wildcard position is
 * extracted directly from the key string and bound to the variable.
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
            String varName = isVariableBinding ? condition.qualifiedVariableName() : null;

            boolean govIsSpecific = (governor != null && !"*".equals(governor));
            boolean relIsSpecific = (relation != null && !"*".equals(relation));
            boolean depIsSpecific = (dependent != null && !"*".equals(dependent));

            CellResult result;
            if (govIsSpecific && relIsSpecific && depIsSpecific && !isVariableBinding) {
                result = executeSpecificSearch(condition, index, granularity, mode, varName);
            } else {
                result = executeVariableSearch(condition, index, granularity, mode, varName);
            }

            if (allowedCells.isPresent() && !result.isEmpty()) {
                Roaring64NavigableMap filtered = result.cells().clone();
                filtered.and(allowedCells.get());
                result = CellResult.of(filtered, result.bindings(), granularity);
                logger.debug("Applied allowedCells filter: {} cells remain", filtered.getLongCardinality());
            }

            logger.debug("DEPENDENCY result: {} cells", result.cellCount());
            return result;

        } catch (IndexAccessException e) {
            throw new QueryExecutionException(
                    "Error accessing index for DEPENDENCY condition: " + e.getMessage(),
                    e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }
    }

    private CellResult executeSpecificSearch(Dependency condition, IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode,
            String variableName)
            throws IndexAccessException {

        String normalizedGovernor = condition.governor().toLowerCase();
        String normalizedDependent = condition.dependent().toLowerCase();
        String normalizedRelation = condition.relation().toLowerCase();

        String searchKey = normalizedGovernor + IndexAccessInterface.DELIMITER
                + normalizedRelation + IndexAccessInterface.DELIMITER
                + normalizedDependent;

        byte[] keyBytes = searchKey.getBytes(StandardCharsets.UTF_8);
        Optional<PostingList> plOpt = index.getPostingList(keyBytes, mode);

        if (plOpt.isEmpty() || plOpt.get().isEmpty()) {
            return CellResult.empty(granularity);
        }

        PostingList pl = plOpt.get();
        CellResult cr = (mode == PostingList.DeserializeMode.FULL)
                ? CellResult.fromPostingListWithOccurrences(pl, granularity)
                : CellResult.fromPostingList(pl, granularity);

        // Bind the dependent value (the only position that can legally be variable)
        if (variableName != null) {
            Bindings bindings = buildBindingsForValue(pl.cells(), normalizedDependent, variableName);
            if (bindings != null) {
                return CellResult.of(cr.cells(), bindings, granularity);
            }
        }
        return cr;
    }

    private CellResult executeVariableSearch(Dependency condition, IndexAccessInterface index,
            Query.Granularity granularity,
            PostingList.DeserializeMode mode,
            String variableName)
            throws IndexAccessException {

        String governorFilterLower = (condition.governor() != null && !"*".equals(condition.governor()))
                ? condition.governor().toLowerCase()
                : null;
        String relationFilterLower = (condition.relation() != null && !"*".equals(condition.relation()))
                ? condition.relation().toLowerCase()
                : null;
        String dependentFilterLower = (condition.dependent() != null && !"*".equals(condition.dependent()))
                ? condition.dependent().toLowerCase()
                : null;

        // Determine which position to bind (the one that is wildcard)
        String bindPosition = null;
        if (variableName != null) {
            if (!condition.governor().equals("*") && governorFilterLower != null)
                bindPosition = "gov";
            else if (!condition.relation().equals("*") && relationFilterLower != null)
                bindPosition = "rel";
            else if (!condition.dependent().equals("*") && dependentFilterLower != null)
                bindPosition = "dep";
            else
                bindPosition = "dep"; // default: bind dependent
        }

        StringBuilder prefixBuilder = new StringBuilder();
        if (governorFilterLower != null) {
            prefixBuilder.append(governorFilterLower).append(IndexAccessInterface.DELIMITER);
            if (relationFilterLower != null) {
                prefixBuilder.append(relationFilterLower).append(IndexAccessInterface.DELIMITER);
            }
        }
        String prefix = prefixBuilder.length() > 0 ? prefixBuilder.toString() : null;

        CellResult result = CellResult.empty(granularity);
        Map<Long, String> cellValues = variableName != null ? new HashMap<>() : null;

        try (RocksIterator iterator = getIteratorForSearch(index, governorFilterLower, relationFilterLower)) {
            if (iterator == null) {
                logger.warn("RocksIterator was not initialized. Returning empty result.");
                return result;
            }

            int keysMatched = 0;
            while (iterator.isValid()) {
                byte[] keyBytes = iterator.key();
                String key = new String(keyBytes, StandardCharsets.UTF_8);

                if (prefix != null && !key.startsWith(prefix)) {
                    break;
                }

                String[] parts = key.split(String.valueOf(IndexAccessInterface.DELIMITER));
                if (parts.length == 3) {
                    String currentGovernor = parts[0];
                    String currentRelation = parts[1];
                    String currentDependent = parts[2];

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

                        // Extract bind value from key
                        if (cellValues != null) {
                            String bindValue = switch (bindPosition) {
                                case "gov" -> currentGovernor;
                                case "rel" -> currentRelation;
                                default -> currentDependent;
                            };
                            var cellIter = pl.cells().getLongIterator();
                            while (cellIter.hasNext()) {
                                long ck = cellIter.next();
                                cellValues.putIfAbsent(ck, bindValue);
                            }
                        }
                    }
                }
                iterator.next();
            }
            logger.debug("Variable search for dependency matched {} keys, result has {} cells",
                    keysMatched, result.cellCount());
        }

        Bindings bindings = null;
        if (variableName != null && cellValues != null && !cellValues.isEmpty()) {
            bindings = buildBindingsFromMap(cellValues, variableName);
        }

        return CellResult.of(result.cells(), bindings, granularity);
    }

    private Bindings buildBindingsForValue(Roaring64NavigableMap cells, String value, String variableName) {
        Bindings.Builder builder = Bindings.builder();
        var cellIter = cells.getLongIterator();
        while (cellIter.hasNext()) {
            long ck = cellIter.next();
            builder.withCellKey(ck).add(value, ValueType.DEPENDENCY, variableName);
        }
        return builder.build();
    }

    private Bindings buildBindingsFromMap(Map<Long, String> cellValues, String variableName) {
        Bindings.Builder builder = Bindings.builder();
        int added = 0;
        for (Map.Entry<Long, String> entry : cellValues.entrySet()) {
            builder.withCellKey(entry.getKey())
                    .add(entry.getValue(), ValueType.DEPENDENCY, variableName);
            added++;
        }
        logger.debug("buildBindingsFromMap: added {} bindings for variable '{}'", added, variableName);
        return added > 0 ? builder.build() : null;
    }

    private RocksIterator getIteratorForSearch(IndexAccessInterface index,
            String governorFilterLower, String relationFilterLower)
            throws IndexAccessException {
        StringBuilder prefixBuilder = new StringBuilder();
        if (governorFilterLower != null) {
            prefixBuilder.append(governorFilterLower).append(IndexAccessInterface.DELIMITER);
            if (relationFilterLower != null) {
                prefixBuilder.append(relationFilterLower).append(IndexAccessInterface.DELIMITER);
            }
        }
        if (prefixBuilder.length() > 0) {
            String p = prefixBuilder.toString();
            byte[] prefixBytes = p.getBytes(StandardCharsets.UTF_8);
            byte[] upperBound = Arrays.copyOf(prefixBytes, prefixBytes.length + 1);
            upperBound[upperBound.length - 1] = (byte) 0xFF;
            return index.seekWithBounds(prefixBytes, upperBound, 256 * 1024);
        } else {
            return index.iterateFromFirst();
        }
    }
}
