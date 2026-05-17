package com.example.query.result;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * A single result row. Values are accessed by column index or name via the
 * associated {@link Schema}.
 */
public class Row {

    private final Schema schema;
    private final Object[] values;

    /**
     * Creates a Row. The values array is defensively copied.
     *
     * @param schema the schema describing the columns
     * @param values the values; length must equal schema.columnCount()
     */
    public Row(Schema schema, Object[] values) {
        if (values.length != schema.columnCount())
            throw new IllegalArgumentException(
                    "Values length " + values.length + " != schema column count " + schema.columnCount());
        this.schema = schema;
        this.values = values.clone();
    }

    public Object get(int colIndex) {
        return values[colIndex];
    }

    public Object get(String colName) {
        return values[schema.indexOf(colName)];
    }

    public String getString(int col) {
        Object v = values[col];
        return v != null ? v.toString() : null;
    }

    public int getInt(int col) {
        Object v = values[col];
        if (v instanceof Number n)
            return n.intValue();
        if (v == null)
            return 0;
        throw new ClassCastException("Column " + col + " is not numeric: " + v.getClass());
    }

    public LocalDate getDate(int col) {
        Object v = values[col];
        if (v instanceof LocalDate d)
            return d;
        if (v == null)
            return null;
        throw new ClassCastException("Column " + col + " is not a LocalDate: " + v.getClass());
    }

    public int columnCount() {
        return values.length;
    }

    public Schema schema() {
        return schema;
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
