package com.example.query.executor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.query.binding.ValueType;

/**
 * Dedup-interned variable bindings attached to a {@link CellResult}.
 *
 * <p>
 * Bindings are indexed either per-cell or per-occurrence, matching the
 * granularity of the result. Each logical row has a value, a value type, and
 * an optional variable name. Values are deduplicated to reduce memory usage,
 * and variable names are interned.
 */
public final class Bindings {

    // Size in logical rows (cells or occurrences depending on context)
    private final int size;

    // Value deduplication
    private final List<Object> uniqueValues;
    private final int[] valueIdx; // length = size; index into uniqueValues

    // Variable name interning
    private final List<String> uniqueVariableNames;
    private final int[] varIdx; // length = size; index into uniqueVariableNames, or -1 if no variable

    // Type storage (one byte per row)
    private final byte[] valueTypes; // length = size

    // Per-row cell keys (null if not tracked)
    private final long[] rowCellKeys; // length = size, or null

    private Bindings(int size, List<Object> uniqueValues, int[] valueIdx,
            List<String> uniqueVariableNames, int[] varIdx, byte[] valueTypes,
            long[] rowCellKeys) {
        this.size = size;
        this.uniqueValues = Collections.unmodifiableList(uniqueValues);
        this.valueIdx = valueIdx;
        this.uniqueVariableNames = Collections.unmodifiableList(uniqueVariableNames);
        this.varIdx = varIdx;
        this.valueTypes = valueTypes;
        this.rowCellKeys = rowCellKeys;
    }

    // --- Getters ---

    public int size() {
        return size;
    }

    public List<Object> uniqueValues() {
        return uniqueValues;
    }

    public List<String> uniqueVariableNames() {
        return uniqueVariableNames;
    }

    public Object valueAt(int i) {
        int idx = valueIdx[i];
        return idx < 0 ? null : uniqueValues.get(idx);
    }

    public ValueType valueTypeAt(int i) {
        return ValueType.fromOrdinal(valueTypes[i]);
    }

    /** Returns the variable name, or null if no variable is bound for this row. */
    public String variableNameAt(int i) {
        int idx = varIdx[i];
        return idx < 0 ? null : uniqueVariableNames.get(idx);
    }

    /** Returns the packed value index (into uniqueValues), or -1. */
    public int valueIndexAt(int i) {
        return valueIdx[i];
    }

    /** Returns the variable name index (into uniqueVariableNames), or -1. */
    public int variableNameIndexAt(int i) {
        return varIdx[i];
    }

    /** Returns the raw value-type byte for row i. */
    public byte valueTypeByteAt(int i) {
        return valueTypes[i];
    }

    /** Returns the cell key for row i, or -1 if not tracked. */
    public long rowCellKeyAt(int i) {
        return rowCellKeys != null ? rowCellKeys[i] : -1;
    }

    /** Returns the row cell keys array, or null if not tracked. */
    public long[] rowCellKeys() {
        return rowCellKeys;
    }

    // --- Narrowing ---

    /**
     * Returns a new Bindings narrowed to those rows whose cell key is present in
     * {@code matchedCells}. The {@code cellKeys} array must parallel this
     * Bindings' rows (same length as {@link #size()}) and be sorted ascending.
     *
     * @param matchedCells the set of cell keys to retain
     * @param cellKeys     the cell key for each binding row, sorted ascending
     * @return a narrowed Bindings, or null if no rows match
     */
    public Bindings narrowToCells(Roaring64NavigableMap matchedCells, long[] cellKeys) {
        // First pass: count survivors
        int survivors = 0;
        for (int i = 0; i < size; i++) {
            if (matchedCells.contains(cellKeys[i])) {
                survivors++;
            }
        }
        if (survivors == 0)
            return null;

        int[] newValueIdx = new int[survivors];
        int[] newVarIdx = new int[survivors];
        byte[] newTypes = new byte[survivors];
        long[] newRowCellKeys = new long[survivors];
        int out = 0;
        for (int i = 0; i < size; i++) {
            if (matchedCells.contains(cellKeys[i])) {
                newValueIdx[out] = valueIdx[i];
                newVarIdx[out] = varIdx[i];
                newTypes[out] = valueTypes[i];
                newRowCellKeys[out] = cellKeys[i];
                out++;
            }
        }
        return new Bindings(survivors, uniqueValues, newValueIdx,
                uniqueVariableNames, newVarIdx, newTypes, newRowCellKeys);
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Object> valueList = new ArrayList<>();
        private final Map<Object, Integer> valueMap = new HashMap<>();
        private final List<Integer> valueIdxList = new ArrayList<>();

        private final List<String> varNameList = new ArrayList<>();
        private final Map<String, Integer> varNameMap = new HashMap<>();
        private final List<Integer> varIdxList = new ArrayList<>();

        private final List<Byte> typeList = new ArrayList<>();
        private final List<Long> cellKeyList = new ArrayList<>();
        private long currentCellKey = -1;

        /** Sets the cell key for subsequently added rows. */
        public Builder withCellKey(long cellKey) {
            this.currentCellKey = cellKey;
            return this;
        }

        public Builder add(Object value, ValueType valueType, String variableName) {
            // Dedup value
            int vIdx;
            if (value == null) {
                vIdx = -1;
            } else {
                vIdx = valueMap.computeIfAbsent(value, k -> {
                    valueList.add(k);
                    return valueList.size() - 1;
                });
            }
            valueIdxList.add(vIdx);

            // Intern variable name
            int nIdx = -1;
            if (variableName != null) {
                nIdx = varNameMap.computeIfAbsent(variableName, k -> {
                    varNameList.add(k);
                    return varNameList.size() - 1;
                });
            }
            varIdxList.add(nIdx);

            // Type
            typeList.add((byte) valueType.ordinal());

            // Cell key
            cellKeyList.add(currentCellKey);

            return this;
        }

        public Bindings build() {
            int n = valueIdxList.size();
            int[] vIdx = new int[n];
            byte[] types = new byte[n];
            int[] nIdx = new int[n];
            long[] cellKeys = new long[n];
            boolean hasCellKeys = false;
            for (int i = 0; i < n; i++) {
                vIdx[i] = valueIdxList.get(i);
                types[i] = typeList.get(i);
                nIdx[i] = varIdxList.get(i);
                cellKeys[i] = cellKeyList.get(i);
                if (cellKeys[i] != -1)
                    hasCellKeys = true;
            }
            return new Bindings(n, new ArrayList<>(valueList), vIdx,
                    new ArrayList<>(varNameList), nIdx, types,
                    hasCellKeys ? cellKeys : null);
        }

        public boolean isEmpty() {
            return valueIdxList.isEmpty();
        }
    }
}
