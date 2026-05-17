package com.example.query.result;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Factory methods for {@link Aggregator} implementations used by
 * {@link Table#groupBy(List, java.util.Map)}.
 */
public final class Aggregators {

    private Aggregators() {
    }

    /**
     * An aggregation function that reduces a group of rows to a single value.
     */
    @FunctionalInterface
    public interface Aggregator {
        /**
         * @param groupRows   the rows in this group (non-empty)
         * @param columnIndex the column index to aggregate
         * @return the aggregated value
         */
        Object aggregate(List<Row> groupRows, int columnIndex);
    }

    /** COUNT(*) — counts all rows in the group. */
    public static Aggregator count() {
        return (rows, col) -> rows.size();
    }

    /** COUNT(col) — counts rows where the column value is non-null. */
    public static Aggregator countNonMissing(int col) {
        return (rows, ignored) -> {
            long c = 0;
            for (Row r : rows) {
                if (r.get(col) != null)
                    c++;
            }
            return (int) c;
        };
    }

    /** COUNT(UNIQUE col) — counts distinct non-null values. */
    public static Aggregator countUnique(int col) {
        return (rows, ignored) -> {
            Set<Object> seen = new HashSet<>();
            for (Row r : rows) {
                Object v = r.get(col);
                if (v != null)
                    seen.add(v);
            }
            return seen.size();
        };
    }

    /** FIRST(col) — returns the value from the first row in the group. */
    public static Aggregator first() {
        return (rows, col) -> rows.get(0).get(col);
    }
}
