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

import com.example.core.IndexAccessInterface;
import com.example.query.binding.ValueType;
import com.example.query.executor.AttributeRequirements;
import com.example.query.executor.QueryResultSoA;
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

    // Define constants used in tests (can be adjusted as needed)
    // private static final String LEFT_DOC_ID_COL = "$main.DOCUMENT_ID"; // Example if needed
    // ...
    private File tempDbFile;
    private AttributeRequirements defaultRequirements;

    // Helper record for test data setup
    record TestDataEntry(Object value, ValueType type, String varName, int docId, int sentId, int begin, int end, int synId, int conceptualRowId) {}

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

        defaultRequirements = new AttributeRequirements();
        defaultRequirements.needsDocumentId = true;
        defaultRequirements.needsSentenceId = true;
        defaultRequirements.needsPositions = true;
        defaultRequirements.needsConceptualRowIds = true;
        defaultRequirements.needsSynonymIds = true;
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
            stmt.execute("INSERT INTO documents VALUES (1, 'Document 1', '2023-01-01', 'This is the text of document 1')");
            stmt.execute("INSERT INTO documents VALUES (2, 'Document 2', '2023-01-02', 'This is the text of document 2')");
            stmt.execute("INSERT INTO documents VALUES (3, 'Document 3', '2023-01-03', 'This is the text of document 3')");
            stmt.execute("INSERT INTO documents VALUES (4, 'Document 4', '2023-01-04', 'This is the text of document 4')");
            stmt.execute("INSERT INTO documents VALUES (5, 'Document 5', '2023-01-05', 'This is the text of document 5')");
            stmt.execute("INSERT INTO documents VALUES (101, 'Test Document 101', '2023-05-01', 'Test document content')");
        }
    }

    // Helper to create QueryResultSoA from TestDataEntries
    private QueryResultSoA createSoAForTest(List<TestDataEntry> entries, Query.Granularity granularity, AttributeRequirements reqs) {
        QueryResultSoA soa = new QueryResultSoA(granularity, 0, reqs);
        for (TestDataEntry entry : entries) {
            soa.add(
                entry.value(), entry.type(), entry.varName(),
                entry.docId(), entry.sentId(),
                entry.begin(), entry.end(), entry.synId(),
                entry.conceptualRowId()
            );
        }
        return soa;
    }

    private QueryResultSoA createSoAForTest(List<TestDataEntry> entries, Query.Granularity granularity) {
        return createSoAForTest(entries, granularity, defaultRequirements);
    }

    @Test
    void testCreateTableFromResult_Simple() throws ResultGenerationException { // Added throws
        Query query = new Query("testSource");
        // These are the columns the Query object will have.
        // TableResultService will use query.selectColumns() and col.getColumnName() for table structure.
        List<SelectColumn> querySelectColumns = Arrays.asList(
            new StructuralColumn("$main", "DOCUMENT_ID"), // getColumnName() -> "$main.DOCUMENT_ID"
            new StructuralColumn("$main", "SENTENCE_ID"), // getColumnName() -> "$main.SENTENCE_ID"
            new VariableColumn("$main.v1")                // getColumnName() -> "$main.v1"
        );
        // Manually set these on the query object for the test's generateTable call.
        // This simulates how QueryParser/QueryModelBuilder would populate them.
        query = new Query(query.source(), query.conditions(), query.orderBy(), query.limit(),
                        query.granularity(), query.granularitySize(), querySelectColumns,
                        query.variableRegistry(), query.subqueries(), query.joinCondition(),
                        Optional.of("$main"), query.groupByColumns());

        List<TestDataEntry> entries = new ArrayList<>();
        // Conceptual Row 0: $main.DOCUMENT_ID=1, $main.SENTENCE_ID=1, $main.v1="hello"
        entries.add(new TestDataEntry("hello", ValueType.TERM, "$main.v1", 1, 1, 0, 5, -1, 0));
        entries.add(new TestDataEntry(null, ValueType.TERM, "$main.v_other", 1, 1, 0, 0, -1, 0));
        // Conceptual Row 1: $main.DOCUMENT_ID=1, $main.SENTENCE_ID=2, $main.v1="world"
        entries.add(new TestDataEntry("world", ValueType.TERM, "$main.v1", 1, 2, 6, 11, -1, 1));
        entries.add(new TestDataEntry(123, ValueType.TERM, "$main.v2", 1, 2, 0, 0, -1, 1));

        QueryResultSoA queryResultSoA = createSoAForTest(entries, Query.Granularity.DOCUMENT);
        Table table = tableResultService.generateTable(query, queryResultSoA, indexes);

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

    /*
    @Test
    void testCreateDebugTableFromMatches() throws ResultGenerationException { // If uncommented, will need throws
        List<TestDataEntry> entries = new ArrayList<>();
        entries.add(new TestDataEntry("apple", ValueType.TERM, "varA", 1, 1, 0, 5, 10, 0)); // Conceptual Row 0
        entries.add(new TestDataEntry(LocalDate.of(2023,1,1), ValueType.DATE, "varB", 1, 1, 6, 15, 10, 0)); // Conceptual Row 0
        entries.add(new TestDataEntry("banana", ValueType.TERM, "varA", 2, 3, 0, 6, 11, 1)); // Conceptual Row 1

        QueryResultSoA queryResultSoA = createSoAForTest(entries, Query.Granularity.SENTENCE);
        // Table table = tableResultService.createDebugTableFromMatches(queryResultSoA, "debugSource"); // METHOD UNDEFINED

        // assertNotNull(table);
        // List<String> expectedHeaders = Arrays.asList("DocID", "SentID", "Start", "End", "SynID", "VarName", "Type", "Value", "ConceptualRowID");
        // assertEquals(expectedHeaders, table.columnNames());
        // assertEquals(3, table.rowCount(), "Debug table should have one row per entry in SoA.");

        // r0
        // assertEquals(1, table.intColumn("DocID").get(0)); assertEquals(1, table.intColumn("SentID").get(0)); assertEquals(0, table.intColumn("Start").get(0)); assertEquals(5, table.intColumn("End").get(0));
        // assertEquals(10, table.intColumn("SynID").get(0)); assertEquals("varA", table.stringColumn("VarName").get(0)); assertEquals(ValueType.TERM.toString(), table.stringColumn("Type").get(0));
        // assertEquals("apple", table.stringColumn("Value").get(0)); assertEquals(0, table.intColumn("ConceptualRowID").get(0));
        // r1
        // assertEquals(1, table.intColumn("DocID").get(1)); assertEquals(1, table.intColumn("SentID").get(1)); assertEquals(6, table.intColumn("Start").get(1)); assertEquals(15, table.intColumn("End").get(1));
        // assertEquals(10, table.intColumn("SynID").get(1)); assertEquals("varB", table.stringColumn("VarName").get(1)); assertEquals(ValueType.DATE.toString(), table.stringColumn("Type").get(1));
        // assertEquals(LocalDate.of(2023,1,1), table.dateColumn("Value").get(1)); assertEquals(0, table.intColumn("ConceptualRowID").get(1));
        // r2
        // assertEquals(2, table.intColumn("DocID").get(2)); assertEquals(3, table.intColumn("SentID").get(2)); assertEquals(0, table.intColumn("Start").get(2)); assertEquals(6, table.intColumn("End").get(2));
        // assertEquals(11, table.intColumn("SynID").get(2)); assertEquals("varA", table.stringColumn("VarName").get(2)); assertEquals(ValueType.TERM.toString(), table.stringColumn("Type").get(2));
        // assertEquals("banana", table.stringColumn("Value").get(2)); assertEquals(1, table.intColumn("ConceptualRowID").get(2));
    }
    */

    @Test
    void testCreateTableFromResult_EmptySoA() throws ResultGenerationException { // Added throws
        Query query = new Query("testSource");
        List<SelectColumn> querySelectColumns = Collections.singletonList(
            new StructuralColumn("$main", "DOCUMENT_ID")
        );
        query = new Query(query.source(), query.conditions(), query.orderBy(), query.limit(),
                        query.granularity(), query.granularitySize(), querySelectColumns,
                        query.variableRegistry(), query.subqueries(), query.joinCondition(),
                        Optional.of("$main"), query.groupByColumns());

        QueryResultSoA emptySoA = createSoAForTest(Collections.emptyList(), Query.Granularity.DOCUMENT);

        Table table = tableResultService.generateTable(query, emptySoA, indexes);
        assertNotNull(table);
        if (!querySelectColumns.isEmpty()) {
            assertEquals(Collections.singletonList("$main.DOCUMENT_ID"), table.columnNames());
        } else {
            assertTrue(table.columnNames().isEmpty(), "Expected no columns if SoA is empty and no select columns specified.");
        }
        assertEquals(0, table.rowCount());
    }

    /*
    @Test
    void testCreateDebugTable_EmptySoA() throws ResultGenerationException { // If uncommented, will need throws
        QueryResultSoA emptySoA = createSoAForTest(Collections.emptyList(), Query.Granularity.DOCUMENT);
        // Table table = tableResultService.createDebugTableFromMatches(emptySoA, "debugSource"); // METHOD UNDEFINED
        // assertNotNull(table);
        // List<String> expectedHeaders = Arrays.asList("DocID", "SentID", "Start", "End", "SynID", "VarName", "Type", "Value", "ConceptualRowID");
        // assertEquals(expectedHeaders, table.columnNames());
        // assertEquals(0, table.rowCount());
    }
    */

    @Test
    void testCreateTableFromResult_AliasAndMultipleVariables() throws ResultGenerationException { // Added throws
        Query query = new Query("testSource");
        List<SelectColumn> querySelectColumns = Arrays.asList(
            new StructuralColumn("$main", "DOCUMENT_ID"), // Output col name: "$main.DOCUMENT_ID"
            new VariableColumn("$main.v1"),               // Output col name: "$main.v1"
            new VariableColumn("$main.v2")                // Output col name: "$main.v2"
        );
        query = new Query(query.source(), query.conditions(), query.orderBy(), query.limit(),
                        query.granularity(), query.granularitySize(), querySelectColumns,
                        query.variableRegistry(), query.subqueries(), query.joinCondition(),
                        Optional.of("$main"), query.groupByColumns());

        List<TestDataEntry> entries = new ArrayList<>();
        entries.add(new TestDataEntry("termA", ValueType.TERM, "$main.v1", 10, 1, 0, 5, -1, 0));
        entries.add(new TestDataEntry("termB", ValueType.TERM, "$main.v2", 10, 1, 6, 10, -1, 0));
        entries.add(new TestDataEntry("termC", ValueType.TERM, "$main.v1", 11, 1, 0, 5, -1, 1));

        QueryResultSoA queryResultSoA = createSoAForTest(entries, Query.Granularity.DOCUMENT);
        Table table = tableResultService.generateTable(query, queryResultSoA, indexes);

        assertEquals(Arrays.asList("$main.DOCUMENT_ID", "$main.v1", "$main.v2"), table.columnNames());
        assertEquals(2, table.rowCount());

        assertEquals(10, table.intColumn("$main.DOCUMENT_ID").get(0));
        assertEquals("termA", table.stringColumn("$main.v1").get(0));
        assertEquals("termB", table.stringColumn("$main.v2").get(0));

        assertEquals(11, table.intColumn("$main.DOCUMENT_ID").get(1));
        assertEquals("termC", table.stringColumn("$main.v1").get(1));
        assertTrue(table.stringColumn("$main.v2").isMissing(1), "Expected v2 to be missing in the second conceptual row as it is not present in SoA for that row.");
    }
} // End of class