package com.example.query.result;

/**
 * Describes a sort specification for {@link Table#sortBy(SortSpec...)}.
 *
 * @param column     the column name to sort by
 * @param descending true for descending order, false for ascending
 */
public record SortSpec(String column, boolean descending) {
    public SortSpec {
        if (column == null || column.isBlank())
            throw new IllegalArgumentException("column must not be blank");
    }

    public static SortSpec asc(String col) {
        return new SortSpec(col, false);
    }

    public static SortSpec desc(String col) {
        return new SortSpec(col, true);
    }
}
