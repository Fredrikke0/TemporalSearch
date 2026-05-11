package com.example.query.executor;

import java.util.Optional;

import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.query.model.Query;

public record FilteringContext(
        Optional<Roaring64NavigableMap> allowedCells,
        Query.Granularity granularity) {
    public static FilteringContext unrestricted(Query.Granularity granularity) {
        return new FilteringContext(Optional.empty(), granularity);
    }

    public boolean isUnrestricted() {
        return allowedCells.map(cells -> cells.isEmpty()).orElse(true);
    }

    /**
     * Derives a new context by intersecting with new constraints from a CellResult.
     */
    public FilteringContext intersect(CellResult newConstraints) {
        if (newConstraints == null || newConstraints.isEmpty()) {
            return new FilteringContext(Optional.of(new Roaring64NavigableMap()), this.granularity);
        }
        Roaring64NavigableMap resultCells = newConstraints.cells().clone();
        if (this.allowedCells.isPresent()) {
            resultCells.and(this.allowedCells.get());
        }
        return new FilteringContext(Optional.of(resultCells), this.granularity);
    }

}
