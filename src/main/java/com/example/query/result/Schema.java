package com.example.query.result;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.query.model.Query;
import com.example.query.model.SelectedColumn;
import com.example.query.model.SelectedCount;
import com.example.query.model.SelectedSnippet;
import com.example.query.model.SelectedStructural;
import com.example.query.model.SelectedVariable;

/**
 * Describes the column structure of a {@link Table} or {@link Row}.
 * Immutable once created.
 */
public class Schema {

    /**
     * A column descriptor with name and Java type.
     */
    public record Column(String name, Class<?> type) {
        public Column {
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("Column name must not be blank");
            if (type == null)
                throw new IllegalArgumentException("Column type must not be null");
        }
    }

    private final List<Column> columns;
    private final Map<String, Integer> indexByName;

    /**
     * Creates a Schema from a list of columns.
     *
     * @param columns the column definitions; must have unique names
     */
    public Schema(List<Column> columns) {
        if (columns == null || columns.isEmpty())
            throw new IllegalArgumentException("Schema must have at least one column");
        this.columns = List.copyOf(columns);
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i).name();
            if (idx.containsKey(name))
                throw new IllegalArgumentException("Duplicate column name: " + name);
            idx.put(name, i);
        }
        this.indexByName = Collections.unmodifiableMap(idx);
    }

    public int columnCount() {
        return columns.size();
    }

    public Column column(int i) {
        return columns.get(i);
    }

    public Column column(String name) {
        Integer i = indexByName.get(name);
        if (i == null)
            throw new IllegalArgumentException("Column not found: " + name);
        return columns.get(i);
    }

    public int indexOf(String name) {
        Integer i = indexByName.get(name);
        if (i == null)
            throw new IllegalArgumentException("Column not found: " + name);
        return i;
    }

    public List<Column> columns() {
        return columns;
    }

    public List<String> names() {
        return columns.stream().map(Column::name).toList();
    }

    /**
     * Returns a new Schema containing only the named columns, in the given order.
     */
    public Schema select(List<String> names) {
        List<Column> selected = new ArrayList<>();
        for (String name : names) {
            selected.add(column(name));
        }
        return new Schema(selected);
    }

    /**
     * Builds a Schema from a Query by inspecting its select columns.
     */
    public static Schema fromQuery(Query query) {
        List<Column> cols = new ArrayList<>();
        for (SelectedColumn sc : query.selectColumns()) {
            cols.add(new Column(sc.columnName(), columnTypeFor(sc)));
        }
        if (cols.isEmpty())
            throw new IllegalArgumentException("Query has no select columns");
        return new Schema(cols);
    }

    private static Class<?> columnTypeFor(SelectedColumn sc) {
        return switch (sc) {
            case SelectedVariable __ -> String.class;
            case SelectedStructural s -> switch (s.field()) {
                case TITLE -> String.class;
                case TIMESTAMP -> LocalDate.class;
                case DOCUMENT_ID, SENTENCE_ID, BEGIN, END -> Integer.class;
            };
            case SelectedCount __ -> Integer.class;
            case SelectedSnippet __ -> String.class;
        };
    }

    @Override
    public String toString() {
        return "Schema" + columns;
    }
}
