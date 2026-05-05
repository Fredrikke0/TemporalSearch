package com.example.query.executor;

import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.core.OccurrencesBlock;
import com.example.core.PostingList;
import com.example.query.model.Query;

/**
 * The query-side result type that replaces {@code QueryResultSoA}.
 *
 * <p>
 * A CellResult carries a set of cell keys (packed {@code (docId, sentId)}),
 * optional occurrence-level detail, optional variable bindings, and a
 * granularity that determines the semantics of each cell key.
 *
 * <h3>Granularity</h3>
 * <ul>
 * <li>{@link Query.Granularity#DOCUMENT DOCUMENT}: cells are
 * {@code (docId, 0)}</li>
 * <li>{@link Query.Granularity#SENTENCE SENTENCE}: cells are
 * {@code (docId, sentId)}</li>
 * </ul>
 */
public final class CellResult {

    private final Roaring64NavigableMap cells;
    private final OccurrencesBlock occurrences; // null if not needed
    private final Bindings bindings; // null if no variable bindings
    private final Query.Granularity granularity;

    private CellResult(Roaring64NavigableMap cells, OccurrencesBlock occurrences,
            Bindings bindings, Query.Granularity granularity) {
        this.cells = cells;
        this.occurrences = occurrences;
        this.bindings = bindings;
        this.granularity = granularity;
    }

    // --- Factory methods ---

    public static CellResult empty(Query.Granularity granularity) {
        return new CellResult(new Roaring64NavigableMap(), null, null, granularity);
    }

    public static CellResult of(Roaring64NavigableMap cells, Query.Granularity granularity) {
        if (cells == null)
            throw new IllegalArgumentException("cells must not be null");
        return new CellResult(cells, null, null, granularity);
    }

    public static CellResult of(Roaring64NavigableMap cells, OccurrencesBlock occurrences,
            Query.Granularity granularity) {
        if (cells == null)
            throw new IllegalArgumentException("cells must not be null");
        return new CellResult(cells, occurrences, null, granularity);
    }

    public static CellResult of(Roaring64NavigableMap cells, Bindings bindings,
            Query.Granularity granularity) {
        if (cells == null)
            throw new IllegalArgumentException("cells must not be null");
        return new CellResult(cells, null, bindings, granularity);
    }

    public static CellResult of(Roaring64NavigableMap cells, OccurrencesBlock occurrences,
            Bindings bindings, Query.Granularity granularity) {
        if (cells == null)
            throw new IllegalArgumentException("cells must not be null");
        return new CellResult(cells, occurrences, bindings, granularity);
    }

    /**
     * Creates a CellResult directly from a PostingList (CELLS_ONLY mode — no
     * occurrences).
     */
    public static CellResult fromPostingList(PostingList pl, Query.Granularity granularity) {
        return new CellResult(pl.cells(), null, null, granularity);
    }

    /** Creates a CellResult from a PostingList with occurrences. */
    public static CellResult fromPostingListWithOccurrences(PostingList pl, Query.Granularity granularity) {
        return new CellResult(pl.cells(), pl.occurrences(), null, granularity);
    }

    // --- Getters ---

    public Roaring64NavigableMap cells() {
        return cells;
    }

    public OccurrencesBlock occurrences() {
        return occurrences;
    }

    public Bindings bindings() {
        return bindings;
    }

    public Query.Granularity granularity() {
        return granularity;
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    public long cellCount() {
        return cells.getLongCardinality();
    }

    // --- Combinators ---

    /**
     * Returns a new CellResult representing the AND of this and {@code other}.
     * Both must have the same granularity.
     * Occurrences are narrowed to matched cells; bindings are intersected if
     * present.
     */
    public CellResult and(CellResult other) {
        if (!this.granularity.equals(other.granularity)) {
            throw new IllegalArgumentException("Cannot AND CellResults with different granularities");
        }
        Roaring64NavigableMap resultCells = this.cells.clone();
        resultCells.and(other.cells);

        OccurrencesBlock resultOcc = null;
        if (this.occurrences != null) {
            resultOcc = this.occurrences.intersect(resultCells);
        }

        Bindings resultBindings = null;
        // Merge bindings from both sides into a new combined Bindings
        // For now, just concatenate both bindings if present, narrowed to matched
        // cells.
        // A full implementation would do per-cell cross-product, matching the old
        // LogicalExecutor.processMatchingGranule semantics. We'll implement that
        // as a helper in a later phase.
        if (this.bindings != null && other.bindings != null) {
            resultBindings = mergeBindings(this.bindings, other.bindings, this.cells, other.cells, resultCells);
        } else if (this.bindings != null) {
            resultBindings = narrowBindingsToCells(this.bindings, resultCells);
        } else if (other.bindings != null) {
            resultBindings = narrowBindingsToCells(other.bindings, resultCells);
        }

        return new CellResult(resultCells, resultOcc, resultBindings, granularity);
    }

    /**
     * Returns a new CellResult representing the OR of this and {@code other}.
     * Both must have the same granularity.
     */
    public CellResult or(CellResult other) {
        if (!this.granularity.equals(other.granularity)) {
            throw new IllegalArgumentException("Cannot OR CellResults with different granularities");
        }
        Roaring64NavigableMap resultCells = this.cells.clone();
        resultCells.or(other.cells);

        // For OR, bindings from both sides are concatenated (each side's bindings
        // are associated with cells that survived the OR). Occurrences are not
        // merged for OR (they would need per-cell identification of source).
        Bindings resultBindings = null;
        if (this.bindings != null || other.bindings != null) {
            // This is a simplification; proper OR binding merging would need
            // to keep track of which cells came from which side. For now,
            // we concatenate, similar to performOrSoA semantics.
            if (this.bindings != null) {
                resultBindings = this.bindings; // For simplicity; full impl later
            }
            if (other.bindings != null && resultBindings == null) {
                resultBindings = other.bindings;
            }
        }

        return new CellResult(resultCells, null, resultBindings, granularity);
    }

    // --- Private helpers ---

    private static Bindings narrowBindingsToCells(Bindings b, Roaring64NavigableMap matchedCells) {
        if (b == null)
            return null;
        // Build a parallel cellKeys array from the cells bitmap
        long[] cellKeys = new long[(int) matchedCells.getLongCardinality()];
        int i = 0;
        var iter = matchedCells.getLongIterator();
        while (iter.hasNext()) {
            cellKeys[i++] = iter.next();
        }
        return b.narrowToCells(matchedCells, cellKeys);
    }

    private static Bindings mergeBindings(Bindings left, Bindings right,
            Roaring64NavigableMap leftCells,
            Roaring64NavigableMap rightCells,
            Roaring64NavigableMap resultCells) {
        // Simplified: just narrow left bindings to result cells.
        // The full cross-product semantics from LogicalExecutor will be
        // implemented in the CsrIntersectHelper in a later phase.
        return narrowBindingsToCells(left, resultCells);
    }

    @Override
    public String toString() {
        return "CellResult{cells=" + cells.getLongCardinality()
                + ", granularity=" + granularity
                + ", occurrences=" + (occurrences != null ? "present" : "none")
                + ", bindings=" + (bindings != null ? bindings.size() + " rows" : "none") + "}";
    }
}
