package com.example.query.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.query.binding.ValueType;
import com.example.query.executor.Bindings;
import com.example.query.executor.CellResult;
import com.example.query.model.Query;
import com.example.query.model.SelectColumn;
import com.example.query.model.StructuralColumn;
import com.example.query.model.VariableColumn;
import com.example.query.sqlite.SqliteAccessor;

import tech.tablesaw.api.Table;

/**
 * Tests for the TableResultService class.
 */
class TableResultServiceTest {

    private TableResultService tableResultService;
    private Map<String, IndexAccessInterface> indexes;

    private File tempDbFile;

    // Helper record for test data setup
    record TestDataEntry(Object value, ValueType type, String varName, int docId, int sentId, int begin, int end,
            int synId, int conceptualRowId) {
    }

    @BeforeEach
    void setUp() throws Exception {
        tableResultService = new TableResultService();
        indexes = new HashMap<>();

        // Create a temporary SQLite database for testing
        tempDbFile = Files.createTempFile("test_db", ".sqlite").toFile();
        tempDbFile.deleteOnExit();

        // Initialize the test database with sample data
        setupTestDatabase(tempDbFile.getAbsolutePath());

        // Initialize SqliteAccessor with the test database
        SqliteAccessor.initialize(tempDbFile.getAbsolutePath());
    }

    @AfterEach
    void tearDown() {
        if (tempDbFile != null && tempDbFile.exists()) {
            tempDbFile.delete();
        }
    }

    private void setupTestDatabase(String dbPath) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                Statement stmt = conn.createStatement()) {

            // Create documents table
            stmt.execute("""
                        CREATE TABLE documents (
                            document_id INTEGER PRIMARY KEY,
                            title TEXT,
                            timestamp TEXT,
                            text TEXT
                        )
                    """);

            // Insert sample data for testing
            stmt.execute(
                    "INSERT INTO documents VALUES (1, 'Document 1', '2023-01-01', 'This is the text of document 1')");
            stmt.execute(
                    "INSERT INTO documents VALUES (2, 'Document 2', '2023-01-02', 'This is the text of document 2')");
            stmt.execute(
                    "INSERT INTO documents VALUES (3, 'Document 3', '2023-01-03', 'This is the text of document 3')");
            stmt.execute(
                    "INSERT INTO documents VALUES (4, 'Document 4', '2023-01-04', 'This is the text of document 4')");
            stmt.execute(
                    "INSERT INTO documents VALUES (5, 'Document 5', '2023-01-05', 'This is the text of document 5')");
            stmt.execute(
                    "INSERT INTO documents VALUES (101, 'Test Document 101', '2023-05-01', 'Test document content')");
        }
    }

    // Helper to create CellResult from TestDataEntries
    private CellResult createCellResultForTest(List<TestDataEntry> entries, Query.Granularity granularity) {
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        Bindings.Builder bindingsBuilder = Bindings.builder();
        for (TestDataEntry entry : entries) {
            long cellKey = PostingList.packCellKey(entry.docId(), entry.sentId());
            cells.add(cellKey);
            bindingsBuilder.add(entry.value(), entry.type(), entry.varName());
        }
        Bindings bindings = bindingsBuilder.isEmpty() ? null : bindingsBuilder.build();
        return CellResult.of(cells, bindings, granularity);
    }

    @Test
    void testCreateTableFromResult_Simple() throws ResultGenerationException {
        Query query = new Query("testSource");
        List<SelectColumn> querySelectColumns = Arrays.asList(
                new StructuralColumn("$main", "DOCUMENT_ID"),
                new StructuralColumn("$main", "SENTENCE_ID"),
                new VariableColumn("$main.v1"));
        query = new Query(query.source(), query.conditions(), query.orderBy(), query.limit(),
                query.granularity(), query.granularitySize(), querySelectColumns,
                query.variableRegistry(),
                Collections.emptyList(),
                Optional.of("$main"),
                query.groupByColumns());

        List<TestDataEntry> entries = new ArrayList<>();
        entries.add(new TestDataEntry("hello", ValueType.TERM, "$main.v1", 1, 1, 0, 5, -1, 0));
        entries.add(new TestDataEntry(null, ValueType.TERM, "$main.v_other", 1, 1, 0, 0, -1, 0));
        entries.add(new TestDataEntry("world", ValueType.TERM, "$main.v1", 1, 2, 6, 11, -1, 1));
        entries.add(new TestDataEntry(123, ValueType.TERM, "$main.v2", 1, 2, 0, 0, -1, 1));

        CellResult cellResult = createCellResultForTest(entries, Query.Granularity.DOCUMENT);
        Table table = tableResultService.generateTable(query, cellResult, indexes);

        assertNotNull(table);
        assertEquals(Arrays.asList("$main.DOCUMENT_ID", "$main.SENTENCE_ID", "$main.v1"), table.columnNames());
        assertEquals(2, table.rowCount(), "Expected two distinct conceptual rows.");

        assertEquals(1, table.intColumn("$main.DOCUMENT_ID").get(0));
        assertEquals(1, table.intColumn("$main.SENTENCE_ID").get(0));
        assertEquals("hello", table.stringColumn("$main.v1").get(0));

        assertEquals(1, table.intColumn("$main.DOCUMENT_ID").get(1));
        assertEquals(2, table.intColumn("$main.SENTENCE_ID").get(1));
        assertEquals("world", table.stringColumn("$main.v1").get(1));
    }

    @Test
    void testCreateTableFromResult_EmptySoA() throws ResultGenerationException {
        Query query = new Query("testSource");
        List<SelectColumn> querySelectColumns = Collections.singletonList(
                new StructuralColumn("$main", "DOCUMENT_ID"));
        query = new Query(query.source(), query.conditions(), query.orderBy(), query.limit(),
                query.granularity(), query.granularitySize(), querySelectColumns,
                query.variableRegistry(),
                Collections.emptyList(),
                Optional.of("$main"),
                query.groupByColumns());

        CellResult emptyResult = createCellResultForTest(Collections.emptyList(), Query.Granularity.DOCUMENT);

        Table table = tableResultService.generateTable(query, emptyResult, indexes);
        assertNotNull(table);
        if (!querySelectColumns.isEmpty()) {
            assertEquals(Collections.singletonList("$main.DOCUMENT_ID"), table.columnNames());
        } else {
            assertTrue(table.columnNames().isEmpty(),
                    "Expected no columns if SoA is empty and no select columns specified.");
        }
        assertEquals(0, table.rowCount());
    }

    @Test
    void testCreateTableFromResult_AliasAndMultipleVariables() throws ResultGenerationException {
        Query query = new Query("testSource");
        List<SelectColumn> querySelectColumns = Arrays.asList(
                new StructuralColumn("$main", "DOCUMENT_ID"),
                new VariableColumn("$main.v1"),
                new VariableColumn("$main.v2"));
        query = new Query(query.source(), query.conditions(), query.orderBy(), query.limit(),
                query.granularity(), query.granularitySize(), querySelectColumns,
                query.variableRegistry(),
                Collections.emptyList(),
                Optional.of("$main"),
                query.groupByColumns());

        List<TestDataEntry> entries = new ArrayList<>();
        entries.add(new TestDataEntry("termA", ValueType.TERM, "$main.v1", 10, 1, 0, 5, -1, 0));
        entries.add(new TestDataEntry("termB", ValueType.TERM, "$main.v2", 10, 1, 6, 10, -1, 0));
        entries.add(new TestDataEntry("termC", ValueType.TERM, "$main.v1", 11, 1, 0, 5, -1, 1));

        CellResult cellResult = createCellResultForTest(entries, Query.Granularity.DOCUMENT);
        Table table = tableResultService.generateTable(query, cellResult, indexes);

        assertEquals(Arrays.asList("$main.DOCUMENT_ID", "$main.v1", "$main.v2"), table.columnNames());
        assertEquals(2, table.rowCount());

        assertEquals(10, table.intColumn("$main.DOCUMENT_ID").get(0));
        assertEquals("termA", table.stringColumn("$main.v1").get(0));
        assertEquals("termB", table.stringColumn("$main.v2").get(0));

        assertEquals(11, table.intColumn("$main.DOCUMENT_ID").get(1));
        assertEquals("termC", table.stringColumn("$main.v1").get(1));
        assertTrue(table.stringColumn("$main.v2").isMissing(1),
                "Expected v2 to be missing in the second conceptual row as it is not present in SoA for that row.");
    }
}
