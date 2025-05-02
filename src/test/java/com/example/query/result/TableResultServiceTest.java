package com.example.query.result;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import com.example.query.executor.QueryResult;
import com.example.query.model.Query;
import com.example.query.model.SelectColumn;
import com.example.query.model.VariableColumn;
import com.example.query.executor.SubqueryContext;
import com.example.query.binding.JoinedMatch;
import com.example.query.binding.VariableType;
import com.example.query.model.StructuralColumn;
import com.example.query.model.SubquerySpec;
import com.example.query.model.JoinCondition;
import com.example.query.model.TemporalPredicate;
import com.example.query.binding.VariableRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.tablesaw.api.*;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the TableResultService class.
 */
class TableResultServiceTest {
    
    // Define constants used in tests (can be adjusted as needed)
    private static final String LEFT_DOC_ID_COL = "$main.DOCUMENT_ID";
    private static final String RIGHT_DOC_ID_COL = "sub.DOCUMENT_ID";
    private static final String LEFT_SENT_ID_COL = "$main.SENTENCE_ID";
    private static final String RIGHT_SENT_ID_COL = "sub.SENTENCE_ID";
    private static final String LEFT_FRUIT_VAR = "$main.fruitL"; // Example qualified name
    private static final String RIGHT_FRUIT_VAR = "sub.fruitR"; // Example qualified name
    
    private TableResultService tableResultService;
    private Map<String, IndexAccessInterface> indexes;
    
    @BeforeEach
    void setUp() {
        tableResultService = new TableResultService();
        indexes = new HashMap<>();
    }
    
    // Helper to create QueryResult
    private QueryResult createQueryResult(Query.Granularity granularity, List<MatchDetail> details) {
        // Assuming constructor QueryResult(granularity, details)
        return new QueryResult(granularity, details);
    }
    
    // Helper to create MatchDetail
    private MatchDetail createMatchDetail(int docId, int sentenceId, String value, ValueType type, String varName) {
        Position pos = new Position(docId, sentenceId, 0, 0, LocalDate.now());
        return new MatchDetail(value, type, pos, varName);
    }

    // Helper to create JoinedMatch
    private JoinedMatch createJoinedMatch(int leftDocId, int leftSentId, String leftValue, String leftVar,
                                          int rightDocId, int rightSentId, String rightValue, String rightVar) {
        Position leftPos = new Position(leftDocId, leftSentId, 0, 0, LocalDate.now());
        Position rightPos = new Position(rightDocId, rightSentId, 0, 0, LocalDate.now());
        MatchDetail left = new MatchDetail(leftValue, ValueType.TERM, leftPos, leftVar);
        MatchDetail right = new MatchDetail(rightValue, ValueType.TERM, rightPos, rightVar);
        return new JoinedMatch(left, right);
    }

    @Test
    void testGenerateTableDocumentGranularity() throws ResultGenerationException {
        // Explicitly select DOCUMENT_ID to test table generation
        List<SelectColumn> select = List.of(new StructuralColumn("$main", "DOCUMENT_ID"));
        Query query = new Query(
            "testSource", 
            Collections.emptyList(), 
            Collections.emptyList(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            select
        );
        List<MatchDetail> details = List.of(
            createMatchDetail(1, -1, "apple", ValueType.TERM, null),
            createMatchDetail(2, -1, "banana", ValueType.TERM, null)
        );
        QueryResult queryResult = createQueryResult(Query.Granularity.DOCUMENT, details);
        
        Table table = tableResultService.generateTable(query, queryResult, indexes);
        
        assertNotNull(table);
        assertEquals(2, table.rowCount(), "Should have 2 rows for 2 documents");
        // Assert that the explicitly selected DOCUMENT_ID column exists
        assertEquals(1, table.columnCount(), "Should have 1 column (DOCUMENT_ID)");
        assertTrue(table.columnNames().contains("$main.DOCUMENT_ID"), "Should contain selected $main.DOCUMENT_ID column");
        // Check values
        assertEquals(1, table.intColumn("$main.DOCUMENT_ID").get(0));
        assertEquals(2, table.intColumn("$main.DOCUMENT_ID").get(1));
    }

    @Test
    void testGenerateTableSentenceGranularity() throws ResultGenerationException {
        // Explicitly select DOCUMENT_ID and SENTENCE_ID to test table generation
        List<SelectColumn> select = List.of(
            new StructuralColumn("$main", "DOCUMENT_ID"),
            new StructuralColumn("$main", "SENTENCE_ID")
        );
        Query query = new Query(
            "testSource", 
            Collections.emptyList(), 
            Collections.emptyList(),
            Optional.empty(),
            Query.Granularity.SENTENCE,
            Optional.empty(),
            select
        );
         List<MatchDetail> details = List.of(
            createMatchDetail(1, 1, "apple", ValueType.TERM, null),
            createMatchDetail(1, 2, "banana", ValueType.TERM, null),
            createMatchDetail(2, 1, "cherry", ValueType.TERM, null)
        );
        QueryResult queryResult = createQueryResult(Query.Granularity.SENTENCE, details);

        Table table = tableResultService.generateTable(query, queryResult, indexes);
        
        assertNotNull(table);
        assertEquals(3, table.rowCount(), "Should have 3 rows for 3 sentences");
        // Assert that the explicitly selected ID columns exist
        assertEquals(2, table.columnCount(), "Should have 2 columns (DOCUMENT_ID, SENTENCE_ID)");
        assertTrue(table.columnNames().contains("$main.DOCUMENT_ID"), "Should contain selected $main.DOCUMENT_ID column");
        assertTrue(table.columnNames().contains("$main.SENTENCE_ID"), "Should contain selected $main.SENTENCE_ID column");
        // Check values (assuming order matches input detail list)
        assertEquals(1, table.intColumn("$main.DOCUMENT_ID").get(0));
        assertEquals(1, table.intColumn("$main.SENTENCE_ID").get(0));
        assertEquals(1, table.intColumn("$main.DOCUMENT_ID").get(1));
        assertEquals(2, table.intColumn("$main.SENTENCE_ID").get(1));
        assertEquals(2, table.intColumn("$main.DOCUMENT_ID").get(2));
        assertEquals(1, table.intColumn("$main.SENTENCE_ID").get(2));
    }
    
     @Test
    void testGenerateTableWithSelectColumns() throws ResultGenerationException {
         List<SelectColumn> select = List.of(new VariableColumn("$main.fruit"));
         Query query = new Query(
             "testSource",
             Collections.emptyList(),
             Collections.emptyList(),
             Optional.empty(),
             Query.Granularity.DOCUMENT,
             Optional.empty(),
             select
         );
         
         List<MatchDetail> details = List.of(
             createMatchDetail(1, -1, "apple", ValueType.TERM, "main.fruit"),
             createMatchDetail(2, -1, "banana", ValueType.TERM, "main.fruit")
         );
         // Define the fully qualified variable name
         String qualifiedVarName = "$main.fruit";

         QueryResult queryResult = createQueryResult(Query.Granularity.DOCUMENT, details);
 
         // Manually register the variable as produced
         query.variableRegistry().registerProducer(qualifiedVarName, com.example.query.binding.VariableType.TEXT_SPAN, "TEST");

         Table table = tableResultService.generateTable(query, queryResult, indexes);

         assertNotNull(table);
         assertEquals(2, table.rowCount());
         // Document ID should NOT be present unless selected
         assertFalse(table.columnNames().contains("document_id"), "document_id should not be present unless selected"); 
         // Check selected variable column
         assertTrue(table.columnNames().contains(qualifiedVarName), "Selected variable column $main.fruit should be present"); 
         assertEquals(1, table.columnCount(), "Should only contain the selected column");
         // Need to recreate MatchDetails with qualified name for populateColumn to work
         details = List.of(
             createMatchDetail(1, -1, "apple", ValueType.TERM, qualifiedVarName),
             createMatchDetail(2, -1, "banana", ValueType.TERM, qualifiedVarName)
         );
         queryResult = createQueryResult(Query.Granularity.DOCUMENT, details);
         table = tableResultService.generateTable(query, queryResult, indexes); // Re-generate table

         assertEquals("apple", table.stringColumn(qualifiedVarName).get(0));
         assertEquals("banana", table.stringColumn(qualifiedVarName).get(1));
     }

    @Test
    void testGenerateTableEmptyResult() throws ResultGenerationException {
        Query query = new Query("testSource", Collections.emptyList(), Query.Granularity.DOCUMENT);
        QueryResult emptyResult = createQueryResult(Query.Granularity.DOCUMENT, Collections.emptyList());
        
        Table table = tableResultService.generateTable(query, emptyResult, indexes);
        
        assertNotNull(table);
        assertEquals(0, table.rowCount());
        assertTrue(table.name().contains("EmptyQueryResults"));
    }

    @Test
    void testGenerateTableForJoinDocumentGranularity() throws ResultGenerationException {
        // Query has no explicit SELECT clause
        Query subquery = new Query("subSource", Collections.emptyList(), Query.Granularity.DOCUMENT);
        SubquerySpec subquerySpec = new SubquerySpec(subquery, "sub");
        
        // Create main query with join
        Query query = new Query(
            "testSource", 
            Collections.emptyList(), // conditions
            Collections.emptyList(), // orderBy
            Optional.empty(),        // limit
            Query.Granularity.DOCUMENT,
            Optional.empty(),        // granularitySize
            Collections.emptyList(), // selectColumns (defaults will be generated)
            new VariableRegistry(),    // variableRegistry
            List.of(subquerySpec),   // subqueries
            Optional.of(JoinCondition.createTemporalJoin("$main.date", "sub.date", JoinCondition.JoinType.INNER, TemporalPredicate.CONTAINS)), 
            Optional.empty()         // mainAlias
        );

        // Register variables as produced in respective registries
        query.variableRegistry().registerProducer(LEFT_FRUIT_VAR, VariableType.TEXT_SPAN, "TEST");
        subquery.variableRegistry().registerProducer(RIGHT_FRUIT_VAR, VariableType.TEXT_SPAN, "TEST"); // Register in subquery registry

        List<JoinedMatch> joined = List.of(
            // Pass qualified names to helper
            createJoinedMatch(1, -1, "apple", LEFT_FRUIT_VAR, 2, -1, "banana", RIGHT_FRUIT_VAR),
            createJoinedMatch(3, -1, "cherry", LEFT_FRUIT_VAR, 4, -1, "date", RIGHT_FRUIT_VAR)
        );
        Table table = tableResultService.generateTableForJoin(query, joined, indexes);
        assertNotNull(table);
        assertEquals(2, table.rowCount());
        
        // Expect default columns: IDs, Sentence IDs, Timestamps, and bound variables
        // Note: Sentence ID and Timestamp are added by default even for Document granularity
        List<String> expectedColumns = List.of(
            LEFT_FRUIT_VAR,
            "$main.DOCUMENT_ID", 
            "$main.SENTENCE_ID", 
            "$main.TIMESTAMP", 
            RIGHT_FRUIT_VAR,
            "sub.DOCUMENT_ID", 
            "sub.SENTENCE_ID", 
            "sub.TIMESTAMP"
        );
        // Sort for comparison as order isn't strictly guaranteed by the HashSet in underlying impl
        List<String> actualColumnsSorted = new ArrayList<>(table.columnNames());
        Collections.sort(actualColumnsSorted);
        List<String> expectedColumnsSorted = new ArrayList<>(expectedColumns);
        Collections.sort(expectedColumnsSorted);
        assertEquals(expectedColumnsSorted, actualColumnsSorted, "Should contain correct default JOIN columns (variables, IDs, timestamps)");
        
        // Verify ID values
        assertEquals(1, table.intColumn(LEFT_DOC_ID_COL).get(0));
        assertEquals(2, table.intColumn(RIGHT_DOC_ID_COL).get(0));
        assertEquals(3, table.intColumn(LEFT_DOC_ID_COL).get(1));
        assertEquals(4, table.intColumn(RIGHT_DOC_ID_COL).get(1));
        
        // Verify variable columns (now part of default SELECT * for JOIN)
        assertTrue(table.columnNames().contains(LEFT_FRUIT_VAR));
        assertTrue(table.columnNames().contains(RIGHT_FRUIT_VAR));
        assertEquals("apple", table.stringColumn(LEFT_FRUIT_VAR).get(0));
        assertEquals("banana", table.stringColumn(RIGHT_FRUIT_VAR).get(0));
        assertEquals("cherry", table.stringColumn(LEFT_FRUIT_VAR).get(1));
        assertEquals("date", table.stringColumn(RIGHT_FRUIT_VAR).get(1));

        // Verify structural columns (now part of default SELECT * for JOIN)
        assertTrue(table.columnNames().contains(LEFT_DOC_ID_COL));
        assertTrue(table.columnNames().contains(RIGHT_DOC_ID_COL));
        assertTrue(table.columnNames().contains(LEFT_SENT_ID_COL));
        assertTrue(table.columnNames().contains(RIGHT_SENT_ID_COL));
        assertTrue(table.columnNames().contains("$main.TIMESTAMP"));
        assertTrue(table.columnNames().contains("sub.TIMESTAMP"));
        assertFalse(table.columnNames().contains("$main.TITLE"), "TITLE should NOT be a default JOIN column");
        assertFalse(table.columnNames().contains("sub.TITLE"), "TITLE should NOT be a default JOIN column");
        
        // Check total column count reflects the new defaults
        // Expect: l_doc, r_doc, l_sent, r_sent, l_ts, r_ts, l_fruit, r_fruit (potentially)
        // Actual count depends on granularity and whether SENT_ID/TITLE/TS are added
        // Let's assert a minimum expected based on variables and doc_ids
        // Expected count based on the list above
        assertEquals(expectedColumns.size(), table.columnCount(), "Should have the correct number of default columns");
    }

    @Test
    void testGenerateTableForJoinSentenceGranularity() throws ResultGenerationException {
        // Assume a mock subquery for context
        Query subquery = new Query("subSource", Collections.emptyList(), Query.Granularity.SENTENCE);
        SubquerySpec subquerySpec = new SubquerySpec(subquery, "sub");

        // Create main query with join
        Query query = new Query(
            "testSource", 
            Collections.emptyList(), // conditions
            Collections.emptyList(), // orderBy
            Optional.empty(),        // limit
            Query.Granularity.SENTENCE, 
            Optional.empty(),        // granularitySize
            Collections.emptyList(), // selectColumns (defaults will be generated)
            new VariableRegistry(),    // variableRegistry
            List.of(subquerySpec),   // subqueries
            Optional.of(JoinCondition.createTemporalJoin("$main.date", "sub.date", JoinCondition.JoinType.INNER, TemporalPredicate.CONTAINS)), 
            Optional.empty()         // mainAlias
        );

        // Register variables as produced in respective registries
        query.variableRegistry().registerProducer(LEFT_FRUIT_VAR, com.example.query.binding.VariableType.TEXT_SPAN, "TEST");
        subquery.variableRegistry().registerProducer(RIGHT_FRUIT_VAR, com.example.query.binding.VariableType.TEXT_SPAN, "TEST"); // Register in subquery registry

        List<JoinedMatch> joined = List.of(
            // Pass qualified names to helper
            createJoinedMatch(1, 10, "apple", LEFT_FRUIT_VAR, 2, 20, "banana", RIGHT_FRUIT_VAR),
            createJoinedMatch(3, 30, "cherry", LEFT_FRUIT_VAR, 4, 40, "date", RIGHT_FRUIT_VAR)
        );
        Table table = tableResultService.generateTableForJoin(query, joined, indexes);
        assertNotNull(table);
        assertEquals(2, table.rowCount());

        // Verify sentence IDs (part of default SELECT * for JOIN)
        assertTrue(table.columnNames().contains(LEFT_SENT_ID_COL));
        assertTrue(table.columnNames().contains(RIGHT_SENT_ID_COL));
        assertEquals(10, table.intColumn(LEFT_SENT_ID_COL).get(0));
        assertEquals(20, table.intColumn(RIGHT_SENT_ID_COL).get(0));
        assertEquals(30, table.intColumn(LEFT_SENT_ID_COL).get(1));
        assertEquals(40, table.intColumn(RIGHT_SENT_ID_COL).get(1));

        // Verify other default columns (doc id, variables)
        assertTrue(table.columnNames().contains(LEFT_DOC_ID_COL));
        assertTrue(table.columnNames().contains(RIGHT_DOC_ID_COL));
        assertTrue(table.columnNames().contains(LEFT_FRUIT_VAR));
        assertTrue(table.columnNames().contains(RIGHT_FRUIT_VAR));

        // Example check for variable values
        assertEquals("apple", table.stringColumn(LEFT_FRUIT_VAR).get(0));
        assertEquals("banana", table.stringColumn(RIGHT_FRUIT_VAR).get(0));
    }

    @Test
    void testGenerateTableForJoinEmptyResult() throws ResultGenerationException {
        Query query = new Query("testSource", Collections.emptyList(), Query.Granularity.DOCUMENT);
        List<JoinedMatch> joined = Collections.emptyList();
        Table table = tableResultService.generateTableForJoin(query, joined, indexes);
        assertNotNull(table);
        assertEquals(0, table.rowCount());
        assertTrue(table.name().contains("EmptyJoinResults"));
    }

    @Test
    void testSelectDocumentIdExplicitly() throws ResultGenerationException {
        List<SelectColumn> select = List.of(new StructuralColumn("$main", "DOCUMENT_ID"));
        Query query = new Query(
            "testSource",
            Collections.emptyList(),
            Collections.emptyList(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            select
        );
        
        List<MatchDetail> details = List.of(createMatchDetail(101, -1, "val", ValueType.TERM, null));
        QueryResult queryResult = createQueryResult(Query.Granularity.DOCUMENT, details);

        Table table = tableResultService.generateTable(query, queryResult, indexes);
        assertNotNull(table);
        assertEquals(1, table.rowCount());
        assertEquals(1, table.columnCount());
        assertTrue(table.columnNames().contains("$main.DOCUMENT_ID"));
        assertEquals(101, table.intColumn("$main.DOCUMENT_ID").get(0));
    }

    @Test
    void testSelectSentenceIdExplicitly() throws ResultGenerationException {
        List<SelectColumn> select = List.of(new StructuralColumn("$main", "SENTENCE_ID"));
        Query query = new Query(
            "testSource",
            Collections.emptyList(),
            Collections.emptyList(),
            Optional.empty(),
            Query.Granularity.SENTENCE, // Must be sentence granularity for sentence id
            Optional.empty(),
            select
        );
        
        List<MatchDetail> details = List.of(createMatchDetail(1, 55, "val", ValueType.TERM, null));
        QueryResult queryResult = createQueryResult(Query.Granularity.SENTENCE, details);

        Table table = tableResultService.generateTable(query, queryResult, indexes);
        assertNotNull(table);
        assertEquals(1, table.rowCount());
        assertEquals(1, table.columnCount());
        assertTrue(table.columnNames().contains("$main.SENTENCE_ID"));
        assertEquals(55, table.intColumn("$main.SENTENCE_ID").get(0));
    }

    @Test
    void testSelectIdsInJoinExplicitly() throws ResultGenerationException {
         List<SelectColumn> select = List.of(
             new StructuralColumn("$main", "DOCUMENT_ID"), 
             new StructuralColumn("sub", "SENTENCE_ID")
         );

         Query subquery = new Query("subSource", Collections.emptyList(), Query.Granularity.SENTENCE);
         SubquerySpec subquerySpec = new SubquerySpec(subquery, "sub");

         Query query = new Query(
             "testSource", 
             Collections.emptyList(), // conditions
             Collections.emptyList(), // orderBy
             Optional.empty(),        // limit
             Query.Granularity.SENTENCE, 
             Optional.empty(),        // granularitySize
             select, // explicit selectColumns
             new VariableRegistry(),    // variableRegistry
             List.of(subquerySpec),   // subqueries
             Optional.of(JoinCondition.createTemporalJoin("$main.date", "sub.date", JoinCondition.JoinType.INNER, TemporalPredicate.CONTAINS)), 
             Optional.empty()         // mainAlias
         );

         List<JoinedMatch> joined = List.of(
             createJoinedMatch(1, 10, "a", null, 2, 20, "b", null)
         );
         
         Table table = tableResultService.generateTableForJoin(query, joined, indexes);
         assertNotNull(table);
         assertEquals(1, table.rowCount());
         assertEquals(2, table.columnCount()); // Only the two selected columns
         assertTrue(table.columnNames().contains("$main.DOCUMENT_ID"));
         assertTrue(table.columnNames().contains("sub.SENTENCE_ID"));
         assertEquals(1, table.intColumn("$main.DOCUMENT_ID").get(0));
         assertEquals(20, table.intColumn("sub.SENTENCE_ID").get(0));
    }
} 