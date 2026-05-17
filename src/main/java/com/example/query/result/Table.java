package com.example.query.result;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * An in-memory table composed of a {@link Schema} and a list of {@link Row}s.
 * Supports sorting, limiting, projection, grouping, and output formatting.
 */
public class Table {

    private final Schema schema;
    private final List<Row> rows;

    private Table(Schema schema, List<Row> rows) {
        this.schema = schema;
        this.rows = rows;
    }

    // --- Factories ---

    /** Collects all rows from an iterator into a Table. */
    public static Table collect(Iterator<Row> rows, Schema schema) {
        List<Row> list = new ArrayList<>();
        while (rows.hasNext()) {
            list.add(rows.next());
        }
        return new Table(schema, list);
    }

    /** Creates an empty Table with the given schema. */
    public static Table empty(Schema schema) {
        return new Table(schema, List.of());
    }

    // --- Access ---

    public Schema schema() {
        return schema;
    }

    public int rowCount() {
        return rows.size();
    }

    public Row row(int i) {
        return rows.get(i);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    // --- Transformations ---

    /**
     * Returns a new Table containing only the first {@code n} rows.
     */
    public Table first(int n) {
        if (n <= 0)
            return empty(schema);
        if (n >= rows.size())
            return this;
        return new Table(schema, new ArrayList<>(rows.subList(0, n)));
    }

    /**
     * Returns a new Table with only the named columns, in order.
     */
    public Table select(List<String> colNames) {
        Schema newSchema = schema.select(colNames);
        List<Row> newRows = new ArrayList<>();
        int[] indices = colNames.stream().mapToInt(schema::indexOf).toArray();
        for (Row row : rows) {
            Object[] vals = new Object[indices.length];
            for (int i = 0; i < indices.length; i++) {
                vals[i] = row.get(indices[i]);
            }
            newRows.add(new Row(newSchema, vals));
        }
        return new Table(newSchema, newRows);
    }

    /**
     * Returns a new Table sorted by the given specifications.
     * Null values sort last regardless of direction.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public Table sortBy(SortSpec... specs) {
        if (specs.length == 0)
            return this;
        Comparator<Row> cmp = null;
        for (SortSpec spec : specs) {
            int ci = schema.indexOf(spec.column());
            Comparator<Row> colCmp = Comparator.comparing(
                    r -> (Comparable) r.get(ci),
                    Comparator.nullsLast(Comparator.naturalOrder()));
            if (spec.descending())
                colCmp = colCmp.reversed();
            cmp = (cmp == null) ? colCmp : cmp.thenComparing(colCmp);
        }
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(cmp);
        return new Table(schema, sorted);
    }

    /**
     * Groups rows and applies aggregators.
     *
     * @param groupCols column names to group by
     * @param aggSpecs  map from output column name to aggregator
     * @return a new Table with group columns + aggregated columns
     */
    public Table groupBy(List<String> groupCols, Map<String, Aggregators.Aggregator> aggSpecs) {
        int[] gi = groupCols.stream().mapToInt(schema::indexOf).toArray();

        // Group rows
        Map<List<Object>, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            List<Object> key = new ArrayList<>();
            for (int i : gi)
                key.add(row.get(i));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        // Build output schema
        List<Schema.Column> outCols = new ArrayList<>();
        for (String gc : groupCols)
            outCols.add(schema.column(gc));
        for (String name : aggSpecs.keySet())
            outCols.add(new Schema.Column(name, Integer.class));
        Schema outSchema = new Schema(outCols);

        // Build output rows
        List<Row> outRows = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Row>> e : groups.entrySet()) {
            Object[] vals = new Object[outSchema.columnCount()];
            int vi = 0;
            for (Object kv : e.getKey())
                vals[vi++] = kv;
            for (Aggregators.Aggregator agg : aggSpecs.values())
                vals[vi++] = agg.aggregate(e.getValue(), 0);
            outRows.add(new Row(outSchema, vals));
        }
        return new Table(outSchema, outRows);
    }

    // --- Output ---

    /**
     * Returns a formatted string representation of the first {@code maxRows} rows.
     */
    public String print(int maxRows) {
        StringBuilder sb = new StringBuilder();
        int displayRows = Math.min(rows.size(), maxRows);

        // Header
        StringJoiner header = new StringJoiner(" | ");
        for (String name : schema.names()) {
            header.add(name);
        }
        String headerLine = header.toString();
        sb.append(headerLine).append("\n");
        sb.append("-".repeat(headerLine.length())).append("\n");

        // Rows
        for (int i = 0; i < displayRows; i++) {
            StringJoiner rowJoiner = new StringJoiner(" | ");
            Row row = rows.get(i);
            for (int c = 0; c < schema.columnCount(); c++) {
                Object val = row.get(c);
                rowJoiner.add(val != null ? val.toString() : "NULL");
            }
            sb.append(rowJoiner.toString()).append("\n");
        }

        if (rows.size() > maxRows) {
            sb.append("\n... and ").append(rows.size() - maxRows).append(" more rows (");
            sb.append(rows.size()).append(" total)\n");
        }

        sb.append("\n").append(rows.size()).append(" row(s)\n");
        return sb.toString();
    }

    /**
     * Writes the table as CSV to the given path.
     */
    public void writeCsv(Path path) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            // Header
            w.println(schema.names().stream()
                    .map(this::csvEscape)
                    .collect(Collectors.joining(",")));

            // Rows
            for (Row row : rows) {
                StringJoiner line = new StringJoiner(",");
                for (int c = 0; c < schema.columnCount(); c++) {
                    Object val = row.get(c);
                    line.add(csvEscape(val != null ? val.toString() : ""));
                }
                w.println(line.toString());
            }
        }
    }

    private String csvEscape(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public StringWriter stringWriter() {
        StringWriter sw = new StringWriter();
        return sw;
    }
}
