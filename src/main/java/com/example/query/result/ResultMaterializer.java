package com.example.query.result;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.query.executor.Bindings;
import com.example.query.executor.CellResult;
import com.example.query.model.Query;

/**
 * Converts a {@link CellResult} into a lazy {@code Iterator<Row>}.
 *
 * <p>
 * Structural field lookups (TITLE, TIMESTAMP) are cached per docId in a shared
 * {@code docCache}, so each unique document is only fetched once from SQLite
 * even when it appears in multiple rows.
 */
public class ResultMaterializer {
    private static final Logger logger = LoggerFactory.getLogger(ResultMaterializer.class);

    private final String source;

    public ResultMaterializer(String source) {
        this.source = source;
    }

    /**
     * Returns a lazy iterator of fully-resolved rows.
     *
     * @param result the CellResult from query execution
     * @param query  the original query (for select columns and schema)
     * @return an iterator over resolved rows
     */
    public Iterator<Row> materialize(CellResult result, Query query) {
        if (result == null || result.isEmpty()) {
            logger.debug("ResultMaterializer: null or empty result, returning empty iterator");
            return Collections.emptyIterator();
        }

        Schema schema = Schema.fromQuery(query);
        List<ColumnResolver> resolvers = query.selectColumns().stream()
                .map(ColumnResolvers::fromSelectedColumn)
                .toList();
        long[] cellKeys = extractSortedCellKeys(result.cells());
        Map<Long, List<Integer>> bindingsByCell = groupBindingsByCellKey(result.bindings(), cellKeys);
        Map<String, Object> docCache = new HashMap<>();

        int numBindings = result.bindings() != null ? result.bindings().size() : 0;
        logger.debug("ResultMaterializer: {} cells, {} bindings, {} schema columns [{}]",
                cellKeys.length, numBindings, schema.columnCount(),
                String.join(", ", schema.names()));

        // Log first 3 cell keys and their binding counts
        for (int i = 0; i < Math.min(3, cellKeys.length); i++) {
            long ck = cellKeys[i];
            int docId = (int) (ck >>> 32);
            int sentId = (int) ck;
            List<Integer> bi = bindingsByCell.getOrDefault(ck, List.of());
            logger.debug("  cell[{}]: docId={}, sentId={}, bindingCount={}, bindingIndices={}",
                    i, docId, sentId, bi.size(), bi.size() > 0 ? bi.subList(0, Math.min(3, bi.size())) : "[]");
        }

        return new Iterator<>() {
            int idx = 0;

            @Override
            public boolean hasNext() {
                return idx < cellKeys.length;
            }

            @Override
            public Row next() {
                long ck = cellKeys[idx];
                List<Integer> bi = bindingsByCell.getOrDefault(ck, List.of());
                Object[] vals = new Object[schema.columnCount()];
                for (int c = 0; c < resolvers.size(); c++) {
                    vals[c] = resolvers.get(c).resolve(ck, result, bi, source, docCache);
                }
                if (idx < 3) {
                    logger.debug("  row[{}]: cellKey(docId={}, sentId={}), bindingCount={}, values={}",
                            idx, (int) (ck >>> 32), (int) ck, bi.size(), java.util.Arrays.toString(vals));
                }
                idx++;
                return new Row(schema, vals);
            }
        };
    }

    /**
     * Extracts cell keys in sorted order from the Roaring64 bitmap.
     */
    private static long[] extractSortedCellKeys(Roaring64NavigableMap cells) {
        long[] keys = new long[(int) cells.getLongCardinality()];
        int i = 0;
        var iter = cells.getLongIterator();
        while (iter.hasNext()) {
            keys[i++] = iter.next();
        }
        return keys;
    }

    /**
     * Groups bindings rows by cell key using the per-row cell keys stored
     * inside each binding row (see {@link Bindings#rowCellKeyAt(int)}).
     * This is correct even when bindings are not in cell-sorted order.
     * <p>
     * Bindings whose cell key does not appear in {@code cellKeys} are
     * silently dropped (they belong to cells that were filtered out by
     * a join or intersection).
     */
    private static Map<Long, List<Integer>> groupBindingsByCellKey(Bindings bindings, long[] cellKeys) {
        Map<Long, List<Integer>> map = new LinkedHashMap<>();
        // Pre-populate with all cell keys so every cell has an entry
        for (long ck : cellKeys) {
            map.put(ck, new ArrayList<>());
        }
        if (bindings == null || bindings.size() == 0) {
            return map;
        }

        int numBindings = bindings.size();
        int dropped = 0;

        logger.debug("groupBindingsByCellKey: {} cells, {} bindings", cellKeys.length, numBindings);

        // Assign each binding row to the cell identified by its own cell key.
        for (int i = 0; i < numBindings; i++) {
            long ck = bindings.rowCellKeyAt(i);
            List<Integer> indices = map.get(ck);
            if (indices != null) {
                indices.add(i);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            logger.debug("groupBindingsByCellKey: dropped {} bindings for cells not in result", dropped);
        }
        return map;
    }
}
