package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException; // Added import for IOException
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.iq80.leveldb.DBIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.query.binding.ValueType;
import com.example.query.binding.VariableRegistry;
import com.example.query.index.IndexManager; // Added import for IndexManager
import com.example.query.model.JoinCondition;
import com.example.query.model.JoinCondition.JoinType;
import com.example.query.model.Query;
import com.example.query.model.SubquerySpec;
import com.example.query.model.TemporalPredicate; // Assuming Temporal Predicate for Join
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Dependency; // Added
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Not;
import com.example.query.model.condition.Pos;
import com.example.query.model.condition.Temporal;
import com.example.query.result.TableResultService;

@ExtendWith(MockitoExtension.class)
class QueryExecutorTest {

    // Helper record for test data, replacing MatchDetail for test setup
    record TestDataEntry(Object value, ValueType type, String varName, int docId, int sentId, int begin, int end, int synId, int conceptualRowId) {}

    @Mock private IndexAccess unigramIndex;
    @Mock private IndexAccess nerIndex;
    @Mock private IndexAccess posIndex; // Added for completeness
    @Mock private IndexAccess dependencyIndex; // Added for completeness
    @Mock private IndexAccess nerDateIndex; // Added for NerDate

    @Mock private DBIterator unigramIterator;
    @Mock private DBIterator nerIterator;

    // Mock dependencies needed by QueryExecutor and JoinHandler
    @Mock private TableResultService mockTableResultService;
    @Spy private ConditionExecutorFactory factory = new ConditionExecutorFactory(); // Use Spy for real factory
    @Mock private LogicalExecutor mockLogicalExecutor; // Add mock for LogicalExecutor
    @Mock private ContainsExecutor containsExecutor;
    @Mock private NerExecutor nerExecutor;
    @Mock private PosExecutor posExecutor; // Added
    @Mock private DependencyExecutor dependencyExecutor; // Added
    @Mock private NotExecutor notExecutor; // Added
    @Mock private TemporalExecutor temporalExecutor; // Mock TemporalExecutor
    @Mock private IndexManager indexManager;

    // Class under test, inject mocks
    private QueryExecutor queryExecutor;

    private Map<String, IndexAccessInterface> indexes;
    private AttributeRequirements defaultTestRequirements; // Declare here

    @BeforeEach
    void setUp() throws IOException, IndexAccessException {
        // Initialize defaultTestRequirements first
        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsDocumentId = true;
        defaultTestRequirements.needsSentenceId = true;
        defaultTestRequirements.needsPositions = true;
        defaultTestRequirements.needsConceptualRowIds = true;
        defaultTestRequirements.needsSynonymIds = false;

        indexes = new HashMap<>();
        indexes.put("unigram", unigramIndex);
        indexes.put("ner", nerIndex);
        indexes.put("pos", posIndex);
        indexes.put("dependency", dependencyIndex);
        indexes.put("ner_date", nerDateIndex);

        queryExecutor = new QueryExecutor(factory, mockTableResultService, "none");

        // Mock the factory to return specific executors when needed
        lenient().doReturn(containsExecutor).when(factory).getExecutor(isA(Contains.class));
        lenient().doReturn(nerExecutor).when(factory).getExecutor(isA(Ner.class));
        lenient().doReturn(temporalExecutor).when(factory).getExecutor(isA(Temporal.class));
        lenient().doReturn(mockLogicalExecutor).when(factory).getExecutor(isA(Logical.class));
        lenient().doReturn(notExecutor).when(factory).getExecutor(isA(Not.class));
        lenient().doReturn(posExecutor).when(factory).getExecutor(isA(Pos.class));
        lenient().doReturn(dependencyExecutor).when(factory).getExecutor(isA(Dependency.class));

        // Mock iterator behavior with lenient mode
        lenient().when(nerIndex.iterateFromFirst()).thenReturn(nerIterator);
        lenient().when(nerIterator.hasNext()).thenReturn(false);

        // Mock unigram iterator to provide a universe for NOT tests
        // Let's define a universe of documents {1, 2, 3, 4}
        PositionList posListDoc1 = new PositionList(); posListDoc1.add(new Position(1, 0, 0, 1));
        PositionList posListDoc2 = new PositionList(); posListDoc2.add(new Position(2, 0, 0, 1));
        PositionList posListDoc3 = new PositionList(); posListDoc3.add(new Position(3, 0, 0, 1));
        PositionList posListDoc4 = new PositionList(); posListDoc4.add(new Position(4, 0, 0, 1));

        // Create mock entries for the iterator
        Map.Entry<byte[], byte[]> entry1 = Map.entry("key1".getBytes(), posListDoc1.serialize());
        Map.Entry<byte[], byte[]> entry2 = Map.entry("key2".getBytes(), posListDoc2.serialize());
        Map.Entry<byte[], byte[]> entry3 = Map.entry("key3".getBytes(), posListDoc3.serialize());
        Map.Entry<byte[], byte[]> entry4 = Map.entry("key4".getBytes(), posListDoc4.serialize());

        // Stub the iterator behavior
        lenient().when(unigramIndex.iterateFromFirst()).thenReturn(unigramIterator);
        lenient().when(unigramIterator.hasNext()).thenReturn(true, true, true, true, false); // Iterate 4 times
        lenient().when(unigramIterator.next()).thenReturn(entry1).thenReturn(entry2).thenReturn(entry3).thenReturn(entry4); // Return each entry

        // Set up mock data for the tests
        setupMockData();
    }

    private void setupMockData() throws IndexAccessException {
        // Removed lenient().when(unigramIndex.get(...)) and nerIndex.get(...) mocks.
        // These are not needed here if ConditionExecutors' execute methods are mocked directly in tests.
    }

    // Helper to count unique conceptual row IDs
    private long countUniqueConceptualRows(QueryResultSoA soa) {
        if (soa == null || soa.isEmpty() || !soa.getRequirements().needsConceptualRowIds) return 0;
        Set<Integer> uniqueIds = new HashSet<>();
        for (int i = 0; i < soa.size(); i++) {
            uniqueIds.add(soa.getConceptualRowIdAt(i));
        }
        return uniqueIds.size();
    }

    private QueryResultSoA createMockQueryResultSoA(Query.Granularity granularity, int granularitySize, List<TestDataEntry> entries, AttributeRequirements reqs) {
        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, reqs != null ? reqs : defaultTestRequirements);
        for (TestDataEntry entry : entries) {
            resultSoA.add(
                entry.value(), entry.type(), entry.varName(),
                entry.docId(), entry.sentId(),
                entry.begin(), entry.end(), entry.synId(),
                entry.conceptualRowId()
            );
        }
        return resultSoA;
    }

    private QueryResultSoA createMockQueryResultSoA(Query.Granularity granularity, int granularitySize, List<TestDataEntry> entries) {
        return createMockQueryResultSoA(granularity, granularitySize, entries, defaultTestRequirements);
    }

    @Test
    void testLogicalAndOperation() throws QueryExecutionException {
        Contains containsCondition = new Contains("test");
        Ner nerCondition = Ner.of("PERSON");
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, Arrays.asList(containsCondition, nerCondition));

        Query query = new Query("test_source", List.of(andCondition));

        doReturn(mockLogicalExecutor).when(factory).getExecutor(isA(Logical.class));

        List<TestDataEntry> expectedEntries = new ArrayList<>(Arrays.asList(
            new TestDataEntry("test_val_from_contains", ValueType.TERM, "v_test", 2, 1, 0, 4, -1, 0),
            new TestDataEntry("PERSON_val_from_ner", ValueType.ENTITY, "v_person", 2, 1, 10, 15, -1, 0)
        ));
        QueryResultSoA expectedAndResultSoA = createMockQueryResultSoA(Query.Granularity.DOCUMENT, 0, expectedEntries);

        when(mockLogicalExecutor.execute(eq(andCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("test_source"), any(AttributeRequirements.class)))
            .thenReturn(expectedAndResultSoA);

        QueryResultSoA results = queryExecutor.execute(query, indexes);

        assertNotNull(results);
        assertEquals(1, countUniqueConceptualRows(results), "Expected 1 unique conceptual row for the AND match.");
        assertEquals(2, results.size(), "Expected 2 binding entries in the result.");
        assertEquals(2, results.getDocumentIdAt(0));
        assertEquals(2, results.getDocumentIdAt(1));
        // Check variable names if they are relevant to the test.
        // For example, to ensure they come from the correct original bindings.
        Set<String> varNames = new HashSet<>();
        varNames.add(results.getVariableNameAt(0));
        varNames.add(results.getVariableNameAt(1));
        assertTrue(varNames.contains("v_test"));
        assertTrue(varNames.contains("v_person"));
    }

    @Test
    void testLogicalOrOperation() throws QueryExecutionException {
        Contains containsCondition = new Contains("test"); // doc 1, 2
        Ner nerCondition = Ner.of("PERSON"); // doc 2, 3
        Logical orCondition = new Logical(Logical.LogicalOperator.OR, Arrays.asList(containsCondition, nerCondition));

        Query query = new Query("test_source", List.of(orCondition));

        doReturn(mockLogicalExecutor).when(factory).getExecutor(isA(Logical.class));

        // Expected: doc 1 (from 'test'), doc 2 (from 'test' and 'PERSON'), doc 3 (from 'PERSON')
        // Conceptual IDs should be preserved & offset by LogicalExecutor for OR.
        List<TestDataEntry> expectedEntries = new ArrayList<>(Arrays.asList(
            new TestDataEntry("test_val1", ValueType.TERM, "test", 1, 1, 0, 4, -1, 0), // conceptualId 0 from 'test'
            new TestDataEntry("test_val2", ValueType.TERM, "test", 2, 1, 0, 4, -1, 1), // conceptualId 1 from 'test'
            new TestDataEntry("PERSON_val2", ValueType.ENTITY, "PERSON", 2, 1, 10, 15, -1, 2), // conceptualId 2 from 'PERSON'
            new TestDataEntry("PERSON_val3", ValueType.ENTITY, "PERSON", 3, 1, 10, 15, -1, 3)  // conceptualId 3 from 'PERSON'
        ));
        QueryResultSoA expectedOrResultSoA = createMockQueryResultSoA(Query.Granularity.DOCUMENT, 0, expectedEntries);

        when(mockLogicalExecutor.execute(eq(orCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("test_source"), any(AttributeRequirements.class)))
            .thenReturn(expectedOrResultSoA);

        QueryResultSoA results = queryExecutor.execute(query, indexes);

        assertNotNull(results);
        assertEquals(4, results.size(), "Total binding entries for OR.");
        assertEquals(4, countUniqueConceptualRows(results), "Expected 4 unique conceptual rows from OR.");
        // Doc 2 should appear twice with different conceptual IDs if 'test' and 'PERSON' were originally separate conceptual rows.
        // However, the mock expectedOrResultSoA has unique conceptual IDs for all entries.
        // This test depends heavily on how mockLogicalExecutor is stubbed.
    }

    @Test
    void testNotOperation() throws QueryExecutionException {
        Contains containsCondition = new Contains("test"); // test is in doc 1, 2
        Not notCondition = new Not(containsCondition);

        Query query = new Query("test_source", List.of(notCondition));

        doReturn(notExecutor).when(factory).getExecutor(isA(Not.class));

        // Assuming universe of docs is {1,2,3,4}. 'test' is in 1,2. So NOT 'test' is 3,4.
        List<TestDataEntry> notEntries = new ArrayList<>(Arrays.asList( // Changed to ArrayList(Arrays.asList()) and ValueType.TERM
            new TestDataEntry("doc_marker_3", ValueType.TERM, null, 3, -1, -1, -1, -1, 0),
            new TestDataEntry("doc_marker_4", ValueType.TERM, null, 4, -1, -1, -1, -1, 1)
        ));
        QueryResultSoA notResultsSoA = createMockQueryResultSoA(Query.Granularity.DOCUMENT, 0, notEntries);

        when(notExecutor.execute(eq(notCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("test_source"), any(AttributeRequirements.class)))
                .thenReturn(notResultsSoA);

        QueryResultSoA results = queryExecutor.execute(query, indexes);

        assertNotNull(results);
        assertEquals(2, results.size());
        assertEquals(2, countUniqueConceptualRows(results));
        Set<Integer> docIds = new HashSet<>();
        for (int i = 0; i < results.size(); i++) {
            docIds.add(results.getDocumentIdAt(i));
        }
        assertFalse(docIds.contains(1));
        assertFalse(docIds.contains(2));
        assertTrue(docIds.contains(3));
        assertTrue(docIds.contains(4));
    }

    @Test
    void testComplexLogicalOperation() throws QueryExecutionException {
        Contains testCondition = new Contains("test");                 // d1, d2
        Contains exampleCondition = new Contains("example");           // d2, d3
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, Arrays.asList(testCondition, exampleCondition)); // d2
        Not notTestCondition = new Not(testCondition);                // d3, d4 (assuming universe d1,d2,d3,d4)
        Logical orCondition = new Logical(Logical.LogicalOperator.OR, Arrays.asList(andCondition, notTestCondition)); // d2 (from AND) OR (d3, d4 from NOT)

        Query query = new Query("test_source", List.of(orCondition));

        doReturn(mockLogicalExecutor).when(factory).getExecutor(eq(orCondition)); // Mock the top-level OR

        List<TestDataEntry> complexEntries = new ArrayList<>(Arrays.asList( // Changed to ArrayList(Arrays.asList()) and ValueType.TERM
            new TestDataEntry("val_d2", ValueType.TERM, "from_and", 2, 1, 0, 0, -1, 0), // From AND part
            new TestDataEntry("marker_d3", ValueType.TERM, "from_not", 3, -1, -1, -1, -1, 1), // From NOT part
            new TestDataEntry("marker_d4", ValueType.TERM, "from_not", 4, -1, -1, -1, -1, 2)  // From NOT part
        ));
        QueryResultSoA finalComplexResultSoA = createMockQueryResultSoA(Query.Granularity.DOCUMENT, 0, complexEntries);

        when(mockLogicalExecutor.execute(eq(orCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("test_source"), any(AttributeRequirements.class)))
            .thenReturn(finalComplexResultSoA);

        QueryResultSoA results = queryExecutor.execute(query, indexes);

        assertNotNull(results);
        assertEquals(3, results.size());
        assertEquals(3, countUniqueConceptualRows(results));
    }

    @Test
    void testJoinHandlerOnDocumentId() throws QueryExecutionException {
        Contains containsConditionLeft = new Contains("apple");
        Query subQueryLeft = new Query("subSource", List.of(containsConditionLeft), Query.Granularity.DOCUMENT);
        SubquerySpec subquerySpecLeft = new SubquerySpec(subQueryLeft, "leftAlias");

        Contains containsConditionRight = new Contains("banana");
        Query subQueryRight = new Query("subSource", List.of(containsConditionRight), Query.Granularity.DOCUMENT);
        SubquerySpec subquerySpecRight = new SubquerySpec(subQueryRight, "rightAlias");

        JoinCondition joinCondition = JoinCondition.createEqualityJoin("leftAlias.DOCUMENT_ID", "rightAlias.DOCUMENT_ID", JoinType.INNER);

        // Using full Query constructor
        Query mainQuery = new Query("mainSource",
            Collections.emptyList(),
            Collections.emptyList(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            Collections.emptyList(),
            new VariableRegistry(),
            Arrays.asList(subquerySpecLeft, subquerySpecRight), // Use Arrays.asList for subqueries
            Optional.of(joinCondition),
            Optional.empty(),
            Collections.emptyList()
        );

        // Mock results from individual subquery executors (ContainsExecutor in this case)
        List<TestDataEntry> leftEntries = new ArrayList<>(Arrays.asList( // Changed to ArrayList(Arrays.asList())
            new TestDataEntry("apple", ValueType.TERM, "vL", 1, -1, 0, 5, -1, 0),
            new TestDataEntry("apple", ValueType.TERM, "vL", 2, -1, 0, 5, -1, 1)
        ));
        QueryResultSoA mockLeftResultSoA = createMockQueryResultSoA(Query.Granularity.DOCUMENT, 0, leftEntries);

        List<TestDataEntry> rightEntries = new ArrayList<>(Arrays.asList( // Changed to ArrayList(Arrays.asList())
            new TestDataEntry("banana", ValueType.TERM, "vR", 2, -1, 0, 6, -1, 0),
            new TestDataEntry("banana", ValueType.TERM, "vR", 3, -1, 0, 6, -1, 1)
        ));
        QueryResultSoA mockRightResultSoA = createMockQueryResultSoA(Query.Granularity.DOCUMENT, 0, rightEntries);

        when(containsExecutor.execute(eq(containsConditionLeft), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("subSource"), any(AttributeRequirements.class)))
                .thenReturn(mockLeftResultSoA);
        when(containsExecutor.execute(eq(containsConditionRight), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("subSource"), any(AttributeRequirements.class)))
                .thenReturn(mockRightResultSoA);

        // Mock the JoinHandler within QueryExecutor if it's a separate component, or expect QueryExecutor to handle join internally.
        // For QueryExecutor to perform the join, it would use JoinHandler.
        // The test should verify the *final* QueryResultSoA from queryExecutor.execute(mainQuery...)
        // This final result will be the joined one.

        // Create the expected joined QueryResultSoA
        // Doc 2: apple (left, cID_L=1) joins with banana (right, cID_R=0)
        // New conceptual ID for the join: 0
        List<TestDataEntry> expectedJoinedEntries = new ArrayList<>(Arrays.asList(
             new TestDataEntry("apple", ValueType.TERM, "vL", 2, -1, 0, 5, -1, 0), // Output conceptual ID 0
             new TestDataEntry("banana", ValueType.TERM, "vR", 2, -1, 0, 6, -1, 0)  // Output conceptual ID 0
        ));
        QueryResultSoA expectedFinalJoinedResult = createMockQueryResultSoA(Query.Granularity.DOCUMENT, 0, expectedJoinedEntries);

        // To properly test this, QueryExecutor's internal JoinHandler call needs to be either:
        // 1. Mocked if JoinHandler is an injected dependency of QueryExecutor.
        // 2. Tested by providing sub-results and asserting the final combined result if JoinHandler is internal.
        // The design (QueryResultSoa.md) implies JoinHandler is used by QueryExecutor.
        // QueryExecutor.java likely instantiates or gets JoinHandler. Let's assume QueryExecutor orchestrates this.
        // For this test, we'll mock the *final* result that QueryExecutor's join logic would produce.
        // This is a bit of an integration test for QueryExecutor's join path.

        // If we directly mock JoinHandler.handleJoin, it would be:
        // JoinHandler mockJoinHandler = mock(JoinHandler.class);
        // queryExecutor = new QueryExecutor(factory, mockTableResultService, mockJoinHandler); // If constructor allows
        // when(mockJoinHandler.handleJoin(any(Query.class), any(SubqueryContext.class))).thenReturn(expectedFinalJoinedResult);

        // Since JoinHandler isn't directly mockable via constructor here, we test the outcome of QueryExecutor's execution.
        // This means the actual JoinHandler logic inside QueryExecutor will run. This makes the test more of an integration test for the join part.

        QueryResultSoA finalJoinedResult = queryExecutor.execute(mainQuery, indexes);

        verify(containsExecutor).execute(eq(containsConditionLeft), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("subSource"), any(AttributeRequirements.class));
        verify(containsExecutor).execute(eq(containsConditionRight), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("subSource"), any(AttributeRequirements.class));

        assertNotNull(finalJoinedResult);
        assertEquals(1, countUniqueConceptualRows(finalJoinedResult), "Expected 1 conceptual row from the join.");
        assertEquals(2, finalJoinedResult.size(), "Expected 2 binding entries in the joined result.");
        assertEquals(2, finalJoinedResult.getDocumentIdAt(0));
        assertEquals(2, finalJoinedResult.getDocumentIdAt(1));
        // Further assertions on values/variables if necessary
        // e.g., check that one binding is 'apple' and other is 'banana' for conceptual row 0
    }

    @Test
    void testTemporalJoinBefore() throws QueryExecutionException {
        String source = "temporal_source";
        // Dummy date values for Temporal condition (not directly used if TemporalExecutor is mocked)
        // Provide dummy dates to satisfy Temporal constructor validation for CONTAINS
        LocalDateTime dummyStart = LocalDateTime.of(2000, 1, 1, 0, 0);
        LocalDateTime dummyEnd = LocalDateTime.of(2000, 1, 2, 0, 0);

        Temporal cond1 = new Temporal(Optional.of(dummyStart), Optional.of(dummyEnd), Optional.of("q1.date"), Optional.empty(), TemporalPredicate.CONTAINS);
        Temporal cond2 = new Temporal(Optional.of(dummyStart), Optional.of(dummyEnd), Optional.of("q2.date"), Optional.empty(), TemporalPredicate.CONTAINS);

        Query subQuery1 = new Query(source, List.of(cond1), Query.Granularity.DOCUMENT);
        Query subQuery2 = new Query(source, List.of(cond2), Query.Granularity.DOCUMENT);

        SubquerySpec sub1 = new SubquerySpec(subQuery1, "q1");
        SubquerySpec sub2 = new SubquerySpec(subQuery2, "q2");
        JoinCondition joinCond = JoinCondition.createTemporalJoin("q1.date", "q2.date", JoinType.INNER, TemporalPredicate.BEFORE);

        // Using full Query constructor
        Query mainQuery = new Query(source,
            Collections.emptyList(),
            Collections.emptyList(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            Collections.emptyList(),
            new VariableRegistry(),
            Arrays.asList(sub1, sub2), // Use Arrays.asList for subqueries
            Optional.of(joinCond),
            Optional.empty(),
            Collections.emptyList()
        );
        List<TestDataEntry> q1Entries = new ArrayList<>(Arrays.asList( // Changed to ArrayList(Arrays.asList())
            new TestDataEntry(LocalDate.of(2023, 1, 10), ValueType.DATE, "q1.date", 1, -1, 0, 0, -1, 0),
            new TestDataEntry(LocalDate.of(2023, 1, 15), ValueType.DATE, "q1.date", 2, -1, 0, 0, -1, 1)
        ));
        QueryResultSoA q1ResultSoA = createMockQueryResultSoA(Query.Granularity.DOCUMENT, 0, q1Entries);

        List<TestDataEntry> q2Entries = new ArrayList<>(Arrays.asList( // Changed to ArrayList(Arrays.asList())
            new TestDataEntry(LocalDate.of(2023, 1, 12), ValueType.DATE, "q2.date", 3, -1, 0, 0, -1, 0),
            new TestDataEntry(LocalDate.of(2023, 1, 20), ValueType.DATE, "q2.date", 4, -1, 0, 0, -1, 1)
        ));
        QueryResultSoA q2ResultSoA = createMockQueryResultSoA(Query.Granularity.DOCUMENT, 0, q2Entries);

        lenient().when(temporalExecutor.execute(eq(cond1), eq(indexes), any(), anyInt(), eq(source), any(AttributeRequirements.class))).thenReturn(q1ResultSoA);
        lenient().when(temporalExecutor.execute(eq(cond2), eq(indexes), any(), anyInt(), eq(source), any(AttributeRequirements.class))).thenReturn(q2ResultSoA);

        // Expected joins: (10th,12th), (10th,20th), (15th,20th) -> 3 conceptual output rows
        // Each output row will contain two bindings (one from q1, one from q2)
        // Conceptual IDs: 0, 1, 2 for the output
        List<TestDataEntry> expectedJoinedEntries = new ArrayList<>(Arrays.asList(
            new TestDataEntry(LocalDate.of(2023,1,10), ValueType.DATE, "q1.date", 1,-1,0,0,-1, 0),
            new TestDataEntry(LocalDate.of(2023,1,12), ValueType.DATE, "q2.date", 3,-1,0,0,-1, 0),
            new TestDataEntry(LocalDate.of(2023,1,10), ValueType.DATE, "q1.date", 1,-1,0,0,-1, 1),
            new TestDataEntry(LocalDate.of(2023,1,20), ValueType.DATE, "q2.date", 4,-1,0,0,-1, 1),
            new TestDataEntry(LocalDate.of(2023,1,15), ValueType.DATE, "q1.date", 2,-1,0,0,-1, 2),
            new TestDataEntry(LocalDate.of(2023,1,20), ValueType.DATE, "q2.date", 4,-1,0,0,-1, 2)
        ));
        QueryResultSoA expectedFinalResult = createMockQueryResultSoA(Query.Granularity.DOCUMENT, 0, expectedJoinedEntries);
        // This test, like the one above, relies on the actual JoinHandler logic within QueryExecutor.

        QueryResultSoA finalJoinedResult = queryExecutor.execute(mainQuery, indexes);

        verify(temporalExecutor).execute(eq(cond1), eq(indexes), any(), anyInt(), eq(source), any(AttributeRequirements.class));
        verify(temporalExecutor).execute(eq(cond2), eq(indexes), any(), anyInt(), eq(source), any(AttributeRequirements.class));

        assertNotNull(finalJoinedResult);
        assertEquals(3, countUniqueConceptualRows(finalJoinedResult), "Expected 3 conceptual rows from BEFORE join.");
        assertEquals(6, finalJoinedResult.size(), "Expected 6 total binding entries for the 3 joined pairs.");
        // Further detailed assertions on the content of joined rows can be added here.
    }
}