package com.example.query.executor;

/**
 * Helper for intersecting {@link Bindings} during
 * {@link CellResult#and(CellResult)}
 * operations. Performs per-cell cross-product matching of bindings, analogous
 * to
 * the old {@code LogicalExecutor.processMatchingGranule} merge-join.
 */
public final class CsrIntersectHelper {

    private CsrIntersectHelper() {
        /* utility */ }

    /**
     * Merges two Bindings by matching rows that belong to the same cell,
     * producing a cross-product of bindings within each matched cell.
     *
     * @param leftBindings  left-side bindings
     * @param leftCellKeys  cell keys parallel to left bindings rows (sorted
     *                      ascending)
     * @param rightBindings right-side bindings
     * @param rightCellKeys cell keys parallel to right bindings rows (sorted
     *                      ascending)
     * @return a new merged Bindings, or null if no rows match
     */
    public static Bindings mergeBindings(
            Bindings leftBindings, long[] leftCellKeys,
            Bindings rightBindings, long[] rightCellKeys) {

        if (leftBindings == null && rightBindings == null)
            return null;
        if (leftBindings == null)
            return rightBindings;
        if (rightBindings == null)
            return leftBindings;

        int li = 0, ri = 0;
        int lSize = leftBindings.size();
        int rSize = rightBindings.size();

        Bindings.Builder builder = Bindings.builder();

        while (li < lSize && ri < rSize) {
            long lCell = leftCellKeys[li];
            long rCell = rightCellKeys[ri];

            if (lCell < rCell) {
                li++;
            } else if (lCell > rCell) {
                ri++;
            } else {
                // Same cell: find all rows for this cell on both sides
                int lStart = li;
                while (li < lSize && leftCellKeys[li] == lCell)
                    li++;
                int lEnd = li;

                int rStart = ri;
                while (ri < rSize && rightCellKeys[ri] == rCell)
                    ri++;
                int rEnd = ri;

                // Cross-product within this cell
                for (int l = lStart; l < lEnd; l++) {
                    for (int r = rStart; r < rEnd; r++) {
                        builder.withCellKey(lCell);
                        // Add left bindings
                        builder.add(leftBindings.valueAt(l),
                                leftBindings.valueTypeAt(l),
                                leftBindings.variableNameAt(l));
                        // Add right bindings
                        builder.add(rightBindings.valueAt(r),
                                rightBindings.valueTypeAt(r),
                                rightBindings.variableNameAt(r));
                    }
                }
            }
        }

        if (builder.isEmpty())
            return null;
        return builder.build();
    }
}
