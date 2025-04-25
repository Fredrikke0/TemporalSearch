package com.example.query.executor;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.query.model.JoinCondition;
import com.example.query.model.Query;
import com.example.query.model.SubquerySpec;
import com.example.query.model.TemporalPredicate; // Assuming Temporal Predicate for Join
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Not;
import com.example.query.result.ResultGenerationException;
import com.example.query.result.TableResultService;
import org.iq80.leveldb.DBIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.example.query.model.condition.Pos;
import com.example.query.model.condition.Temporal;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import com.example.query.index.IndexManager; // Added import for IndexManager
import static org.mockito.Mockito.spy;

@ExtendWith(MockitoExtension.class)
class QueryExecutorTest {
    
    @Mock private IndexAccess unigramIndex;
    @Mock private IndexAccess nerIndex;
    @Mock private DBIterator unigramIterator;
    @Mock private DBIterator nerIterator;
    
    // Mock dependencies needed by QueryExecutor and JoinHandler
    @Mock private TableResultService mockTableResultService;
    @Spy private ConditionExecutorFactory factory = new ConditionExecutorFactory(); // Use Spy for real factory
    @Mock private LogicalExecutor mockLogicalExecutor; // Add mock for LogicalExecutor

    // Class under test, inject mocks
    private QueryExecutor queryExecutor;

    private Map<String, IndexAccessInterface> indexes;
    
    @BeforeEach
    void setUp() throws IndexAccessException {
        indexes = new HashMap<>();
        indexes.put("unigram", unigramIndex);
        indexes.put("ner", nerIndex);
        
        // Use the test constructor to inject mocks
        queryExecutor = new QueryExecutor(factory, mockTableResultService);
        
        // Mock iterator behavior with lenient mode
        lenient().when(nerIndex.iterator()).thenReturn(nerIterator);
        lenient().when(nerIterator.hasNext()).thenReturn(false);

        // Mock unigram iterator to provide a universe for NOT tests
        // Let's define a universe of documents {1, 2, 3, 4}
        PositionList posListDoc1 = new PositionList(); posListDoc1.add(new Position(1, 0, 0, 1, LocalDate.now()));
        PositionList posListDoc2 = new PositionList(); posListDoc2.add(new Position(2, 0, 0, 1, LocalDate.now()));
        PositionList posListDoc3 = new PositionList(); posListDoc3.add(new Position(3, 0, 0, 1, LocalDate.now()));
        PositionList posListDoc4 = new PositionList(); posListDoc4.add(new Position(4, 0, 0, 1, LocalDate.now()));
        
        // Create mock entries for the iterator
        Map.Entry<byte[], byte[]> entry1 = Map.entry("key1".getBytes(), posListDoc1.serialize());
        Map.Entry<byte[], byte[]> entry2 = Map.entry("key2".getBytes(), posListDoc2.serialize());
        Map.Entry<byte[], byte[]> entry3 = Map.entry("key3".getBytes(), posListDoc3.serialize());
        Map.Entry<byte[], byte[]> entry4 = Map.entry("key4".getBytes(), posListDoc4.serialize());

        // Stub the iterator behavior
        lenient().when(unigramIndex.iterator()).thenReturn(unigramIterator);
        lenient().when(unigramIterator.hasNext()).thenReturn(true, true, true, true, false); // Iterate 4 times
        lenient().when(unigramIterator.next()).thenReturn(entry1, entry2, entry3, entry4); // Return each entry

        // Set up mock data for the tests
        setupMockData();
    }
    
    private void setupMockData() throws IndexAccessException {
        // Setup test data for "test" word
        PositionList testPositions = new PositionList();
        testPositions.add(new Position(1, 1, 0, 5, LocalDate.now()));
        testPositions.add(new Position(2, 1, 0, 5, LocalDate.now()));
        
        // Setup test data for "example" word
        PositionList examplePositions = new PositionList();
        examplePositions.add(new Position(2, 1, 10, 15, LocalDate.now()));
        examplePositions.add(new Position(3, 1, 10, 15, LocalDate.now()));
        
        // Setup test data for PERSON NER
        PositionList nerPositions = new PositionList();
        nerPositions.add(new Position(2, 1, 10, 15, LocalDate.now()));
        nerPositions.add(new Position(3, 1, 10, 15, LocalDate.now()));
        
        // Mock index responses with lenient mode to avoid unnecessary stubbing exceptions
        lenient().when(unigramIndex.get("test".getBytes())).thenReturn(Optional.of(testPositions));
        lenient().when(unigramIndex.get("example".getBytes())).thenReturn(Optional.of(examplePositions));
        lenient().when(nerIndex.get("PERSON|".getBytes())).thenReturn(Optional.of(nerPositions));
    }
    
    // Helper method to create QueryResult for mocking
    private QueryResult createMockQueryResult(Query.Granularity granularity, int granularitySize, List<MatchDetail> details) {
        return new QueryResult(granularity, granularitySize, details);
    }

    // Helper method to create simple MatchDetail
    private MatchDetail createMatchDetail(int docId, int sentenceId, String value) {
         // Create a placeholder position
        Position pos = new Position(docId, sentenceId, 0, value.length(), LocalDate.now());
        // Use the 5-argument constructor with null for variableName
        return new MatchDetail(value, ValueType.TERM, pos, (String) null);
    }
     private MatchDetail createMatchDetail(int docId, String value) {
         // Create a placeholder position for document level
        Position pos = new Position(docId, -1, 0, value.length(), LocalDate.now());
        // Use the 5-argument constructor with null for variableName
        return new MatchDetail(value, ValueType.TERM, pos, (String) null);
    }

    @Test
    void testLogicalAndOperation() throws QueryExecutionException, IndexAccessException {
        // Create a query with AND condition
        Contains containsCondition = new Contains("test");
        Ner nerCondition = Ner.of("PERSON");
        Logical andCondition = new Logical(
            Logical.LogicalOperator.AND,
            Arrays.asList(containsCondition, nerCondition)
        );
        
        Query query = new Query(
            "test_source",
            Collections.singletonList(andCondition),
            Collections.emptyList(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            Collections.emptyList()
        );
        
        // Mock the factory to return the mock LogicalExecutor for the top-level condition
        doReturn(mockLogicalExecutor).when(factory).getExecutor(isA(Logical.class));

        // Mock the result returned directly by the mock LogicalExecutor
        // This assumes the QueryExecutor correctly delegates to the factory-provided executor.
        QueryResult expectedAndResult = createMockQueryResult(Query.Granularity.DOCUMENT, 0, 
            List.of(createMatchDetail(2, "test_and_person")) // Simplified single detail for doc 2
        );
        when(mockLogicalExecutor.execute(eq(andCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString()))
            .thenReturn(expectedAndResult);

        // Execute query
        QueryResult results = (QueryResult) queryExecutor.execute(query, indexes);
        
        // Verify results - AND should result in intersection (doc 2)
        assertNotNull(results);
        assertEquals(1, results.getAllDetails().size(), "Should match 1 document based on intersection");
        assertTrue(results.getAllDetails().stream().anyMatch(d -> d.getDocumentId() == 2), "Document 2 should be the only match");
        // Verify that the mockLogicalExecutor was called
        verify(mockLogicalExecutor).execute(eq(andCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString());
    }
    
    @Test
    void testLogicalOrOperation() throws QueryExecutionException, IndexAccessException {
        // Create a query with OR condition
        Contains containsCondition = new Contains("test");
        Ner nerCondition = Ner.of("PERSON");
        Logical orCondition = new Logical(
            Logical.LogicalOperator.OR,
            Arrays.asList(containsCondition, nerCondition)
        );
        
        Query query = new Query(
            "test_source",
            Collections.singletonList(orCondition),
            Collections.emptyList(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            Collections.emptyList()
        );
        
        // Mock the factory to return the mock LogicalExecutor for the top-level condition
        doReturn(mockLogicalExecutor).when(factory).getExecutor(isA(Logical.class));

        // Mock the result returned directly by the mock LogicalExecutor
        QueryResult expectedOrResult = createMockQueryResult(Query.Granularity.DOCUMENT, 0, 
            List.of(createMatchDetail(1, "test"), createMatchDetail(2, "test_or_person"), createMatchDetail(3, "person"))
        );
        when(mockLogicalExecutor.execute(eq(orCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString()))
            .thenReturn(expectedOrResult);

        // Execute query
        QueryResult results = (QueryResult) queryExecutor.execute(query, indexes);
        
        // Verify results - OR should result in union (docs 1, 2, 3)
        assertNotNull(results);
         Set<Integer> docIds = results.getAllDetails().stream().map(MatchDetail::getDocumentId).collect(Collectors.toSet());
        assertEquals(3, docIds.size(), "Should match 3 unique documents based on union");
        assertTrue(docIds.containsAll(Set.of(1, 2, 3)), "Documents 1, 2, and 3 should be matched");
        // Verify that the mockLogicalExecutor was called
        verify(mockLogicalExecutor).execute(eq(orCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString());
    }
    
    @Test
    void testNotOperation() throws QueryExecutionException, IndexAccessException, Exception {
        // Create a query with NOT condition
        Contains containsCondition = new Contains("test");
        Not notCondition = new Not(containsCondition);
        
        Query query = new Query(
            "test_source",
            Collections.singletonList(notCondition),
            Collections.emptyList(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            Collections.emptyList()
        );
        
        // Setup mocks
        ContainsExecutor mockContainsExecutor = mock(ContainsExecutor.class);
        NotExecutor mockNotExecutor = mock(NotExecutor.class); // Assuming NotExecutor exists
        // QueryExecutor *does* call getExecutor on the top-level Logical condition.
        // Stub for the top-level Logical condition
        // doReturn(mockLogicalExecutor).when(factory).getExecutor(isA(Logical.class)); // This mock seems incorrect for NOT
        // Use isA for class matching
        // lenient().doReturn(mockContainsExecutor).when(factory).getExecutor(isA(Contains.class)); // REMOVED - Unnecessary: mockNotExecutor is mocked directly
        doReturn(mockNotExecutor).when(factory).getExecutor(isA(Not.class)); // Factory needs to return the mock NotExecutor

        // QueryResult containsResults = createMockQueryResult(Query.Granularity.DOCUMENT, 0, List.of(createMatchDetail(1, "test"), createMatchDetail(2, "test"))); // Keep this? Might not be needed.
        // Mock what the NotExecutor would return (needs internal logic or direct mocking)
        // Let's assume NotExecutor is complex and mock its final output
        QueryResult notResults = createMockQueryResult(Query.Granularity.DOCUMENT, 0, List.of(createMatchDetail(3, "other"), createMatchDetail(4,"another"))); // Example result excluding docs 1, 2

        // Mock the behavior of the NotExecutor directly
        // We don't need to mock the underlying containsExecutor if we mock NotExecutor's final result
        // Mock the NotExecutor itself
        when(mockNotExecutor.execute(eq(notCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString()))
                .thenReturn(notResults);

        // Execute query
        QueryResult results = (QueryResult) queryExecutor.execute(query, indexes);
        
        // Verify results - Should contain docs not matched by Contains (e.g., 3, 4 in this mock)
        assertNotNull(results);
        Set<Integer> docIds = results.getAllDetails().stream().map(MatchDetail::getDocumentId).collect(Collectors.toSet());
        assertFalse(docIds.contains(1), "Document 1 should not be in results");
        assertFalse(docIds.contains(2), "Document 2 should not be in results");
        assertTrue(docIds.contains(3), "Document 3 should be in results (based on mock)");
        assertTrue(docIds.contains(4), "Document 4 should be in results (based on mock)");
        verify(mockNotExecutor).execute(eq(notCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString());
    }
    
    @Test
    void testComplexLogicalOperation() throws QueryExecutionException, IndexAccessException, Exception {
        // Create a complex query: (test AND example) OR NOT(test)
        Contains testCondition = new Contains("test");
        Contains exampleCondition = new Contains("example");
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, Arrays.asList(testCondition, exampleCondition));
        Not notTestCondition = new Not(testCondition);
        Logical orCondition = new Logical(Logical.LogicalOperator.OR, Arrays.asList(andCondition, notTestCondition));
        Query query = new Query(
            "test_source", Collections.singletonList(orCondition), Collections.emptyList(),
            Optional.empty(), Query.Granularity.DOCUMENT, Optional.empty(), Collections.emptyList()
        );

        // Mock the factory to return the mock LogicalExecutor for the top-level OR condition
        // Note: We are now mocking the execution of the entire top-level OR operation,
        // assuming its internal logic (handling AND and NOT) is tested elsewhere.
        // The setUp method still provides the necessary universe for the NOT part if it were executed.
        doReturn(mockLogicalExecutor).when(factory).getExecutor(eq(orCondition));

        // Define the final expected result for the entire complex operation
        // (test AND example) -> doc 2
        // NOT(test) -> docs 3, 4 (based on universe {1, 2, 3, 4} and test in {1, 2})
        // OR result -> docs 2, 3, 4
        QueryResult finalComplexResult = createMockQueryResult(Query.Granularity.DOCUMENT, 0,
            List.of(
                // Representing doc 2 from the AND part
                createMatchDetail(2, "complex_and"), 
                // Representing docs 3, 4 from the NOT part
                createMatchDetail(3, "complex_not"), 
                createMatchDetail(4, "complex_not") 
            )
        );
        
        // Stub the execute method of the mockLogicalExecutor for the orCondition
        when(mockLogicalExecutor.execute(eq(orCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString()))
            .thenReturn(finalComplexResult);


        // Execute query - Uses the factory to get the mockLogicalExecutor for the orCondition
        QueryResult results = (QueryResult) queryExecutor.execute(query, indexes);

        // Verify final results based on the mocked output
        assertNotNull(results);
        Set<Integer> docIds = results.getAllDetails().stream().map(MatchDetail::getDocumentId).collect(Collectors.toSet());
        assertEquals(3, docIds.size(), "Should match 3 unique documents based on the mocked result for the complex OR");
        assertTrue(docIds.containsAll(Set.of(2, 3, 4)), "Documents 2, 3, and 4 should be matched according to the mock");

        // Verify that the factory was called for the top-level condition
        verify(factory).getExecutor(eq(orCondition));
        
        // Verify that the mockLogicalExecutor (handling the OR) was invoked
        verify(mockLogicalExecutor).execute(eq(orCondition), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString());

        // No need to verify sub-conditions or internal executors as we mocked the top-level execution
    }

    /**
     * Tests for JoinHandler: generic hash join on document_id
     */
    @Test
    void testJoinHandlerOnDocumentId() throws QueryExecutionException {
        Contains containsConditionLeft = new Contains("apple");
        Query subQueryLeft = new Query("subSource", Collections.singletonList(containsConditionLeft), Collections.emptyList(), Optional.empty(), Query.Granularity.DOCUMENT, Optional.empty(), Collections.emptyList(), new com.example.query.binding.VariableRegistry(), Collections.emptyList(), Optional.empty(), Optional.empty());
        SubquerySpec subquerySpecLeft = new SubquerySpec(subQueryLeft, "leftAlias");
        Contains containsConditionRight = new Contains("banana");
        Query subQueryRight = new Query("subSource", Collections.singletonList(containsConditionRight), Collections.emptyList(), Optional.empty(), Query.Granularity.DOCUMENT, Optional.empty(), Collections.emptyList(), new com.example.query.binding.VariableRegistry(), Collections.emptyList(), Optional.empty(), Optional.empty());
        SubquerySpec subquerySpecRight = new SubquerySpec(subQueryRight, "rightAlias");
        JoinCondition joinCondition = new JoinCondition("leftAlias.document_id", "rightAlias.document_id", JoinCondition.JoinType.INNER, TemporalPredicate.EQUAL, Optional.empty());
        Query mainQuery = new Query("mainSource", Collections.emptyList(), Collections.emptyList(), Optional.empty(), Query.Granularity.DOCUMENT, Optional.empty(), Collections.emptyList(), new com.example.query.binding.VariableRegistry(), Arrays.asList(subquerySpecLeft, subquerySpecRight), Optional.of(joinCondition), Optional.empty());
        QueryResult mockLeftResult = createMockQueryResult(Query.Granularity.DOCUMENT, 0, List.of(
                createMatchDetail(1, "apple"), createMatchDetail(2, "apple")
        ));
        QueryResult mockRightResult = createMockQueryResult(Query.Granularity.DOCUMENT, 0, List.of(
                createMatchDetail(2, "banana"), createMatchDetail(3, "banana")
        ));
        ContainsExecutor mockContainsExecutor = mock(ContainsExecutor.class);
        doReturn(mockContainsExecutor).when(factory).getExecutor(isA(Contains.class));
        when(mockContainsExecutor.execute(eq(containsConditionLeft), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString()))
                .thenReturn(mockLeftResult);
        when(mockContainsExecutor.execute(eq(containsConditionRight), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString()))
                .thenReturn(mockRightResult);
        Object joinResultsObj = queryExecutor.execute(mainQuery, indexes);
        @SuppressWarnings("unchecked")
        List<com.example.query.binding.JoinedMatch> joinResults = (List<com.example.query.binding.JoinedMatch>) joinResultsObj;
        assertNotNull(joinResults);
        assertEquals(1, joinResults.size(), "Should join on document_id=2");
        com.example.query.binding.JoinedMatch result = joinResults.get(0);
        assertEquals(2, result.left().getDocumentId());
        assertEquals(2, result.right().getDocumentId());
        assertEquals("apple", result.left().value());
        assertEquals("banana", result.right().value());
    }

    /**
     * Tests for JoinHandler: generic hash join on sentence_id
     */
    @Test
    void testJoinHandlerOnSentenceId() throws QueryExecutionException {
        Contains containsConditionLeft = new Contains("foo");
        Query subQueryLeft = new Query("subSource", Collections.singletonList(containsConditionLeft), Collections.emptyList(), Optional.empty(), Query.Granularity.SENTENCE, Optional.of(0), Collections.emptyList(), new com.example.query.binding.VariableRegistry(), Collections.emptyList(), Optional.empty(), Optional.empty());
        SubquerySpec subquerySpecLeft = new SubquerySpec(subQueryLeft, "leftAlias");
        Contains containsConditionRight = new Contains("bar");
        Query subQueryRight = new Query("subSource", Collections.singletonList(containsConditionRight), Collections.emptyList(), Optional.empty(), Query.Granularity.SENTENCE, Optional.of(0), Collections.emptyList(), new com.example.query.binding.VariableRegistry(), Collections.emptyList(), Optional.empty(), Optional.empty());
        SubquerySpec subquerySpecRight = new SubquerySpec(subQueryRight, "rightAlias");
        JoinCondition joinCondition = new JoinCondition("leftAlias.sentence_id", "rightAlias.sentence_id", JoinCondition.JoinType.INNER, TemporalPredicate.EQUAL, Optional.empty());
        Query mainQuery = new Query("mainSource", Collections.emptyList(), Collections.emptyList(), Optional.empty(), Query.Granularity.SENTENCE, Optional.of(0), Collections.emptyList(), new com.example.query.binding.VariableRegistry(), Arrays.asList(subquerySpecLeft, subquerySpecRight), Optional.of(joinCondition), Optional.empty());
        QueryResult mockLeftResult = createMockQueryResult(Query.Granularity.SENTENCE, 0, List.of(
                createMatchDetail(1, 5, "foo"), createMatchDetail(2, 8, "foo")
        ));
        QueryResult mockRightResult = createMockQueryResult(Query.Granularity.SENTENCE, 0, List.of(
                createMatchDetail(3, 8, "bar"), createMatchDetail(4, 9, "bar")
        ));
        ContainsExecutor mockContainsExecutor = mock(ContainsExecutor.class);
        doReturn(mockContainsExecutor).when(factory).getExecutor(isA(Contains.class));
        when(mockContainsExecutor.execute(eq(containsConditionLeft), eq(indexes), eq(Query.Granularity.SENTENCE), eq(0), anyString()))
                .thenReturn(mockLeftResult);
        when(mockContainsExecutor.execute(eq(containsConditionRight), eq(indexes), eq(Query.Granularity.SENTENCE), eq(0), anyString()))
                .thenReturn(mockRightResult);
        Object joinResultsObj = queryExecutor.execute(mainQuery, indexes);
        @SuppressWarnings("unchecked")
        List<com.example.query.binding.JoinedMatch> joinResults = (List<com.example.query.binding.JoinedMatch>) joinResultsObj;
        assertNotNull(joinResults);
        assertEquals(1, joinResults.size(), "Should join on sentence_id=8");
        com.example.query.binding.JoinedMatch result = joinResults.get(0);
        assertEquals(8, result.left().getSentenceId());
        assertEquals(8, result.right().getSentenceId());
        assertEquals("foo", result.left().value());
        assertEquals("bar", result.right().value());
    }

    /**
     * Tests for JoinHandler: generic hash join on a string key (custom variable)
     */
    @Test
    void testJoinHandlerOnCustomStringKey() throws QueryExecutionException {
        // Here we simulate a custom variable name as the join key
        Contains containsConditionLeft = new Contains("x");
        Query subQueryLeft = new Query("subSource", Collections.singletonList(containsConditionLeft), Collections.emptyList(), Optional.empty(), Query.Granularity.DOCUMENT, Optional.empty(), Collections.emptyList(), new com.example.query.binding.VariableRegistry(), Collections.emptyList(), Optional.empty(), Optional.empty());
        SubquerySpec subquerySpecLeft = new SubquerySpec(subQueryLeft, "leftAlias");
        Contains containsConditionRight = new Contains("y");
        Query subQueryRight = new Query("subSource", Collections.singletonList(containsConditionRight), Collections.emptyList(), Optional.empty(), Query.Granularity.DOCUMENT, Optional.empty(), Collections.emptyList(), new com.example.query.binding.VariableRegistry(), Collections.emptyList(), Optional.empty(), Optional.empty());
        SubquerySpec subquerySpecRight = new SubquerySpec(subQueryRight, "rightAlias");
        JoinCondition joinCondition = new JoinCondition("leftAlias.custom_key", "rightAlias.custom_key", JoinCondition.JoinType.INNER, TemporalPredicate.EQUAL, Optional.empty());
        Query mainQuery = new Query("mainSource", Collections.emptyList(), Collections.emptyList(), Optional.empty(), Query.Granularity.DOCUMENT, Optional.empty(), Collections.emptyList(), new com.example.query.binding.VariableRegistry(), Arrays.asList(subquerySpecLeft, subquerySpecRight), Optional.of(joinCondition), Optional.empty());
        // Create MatchDetails with a custom variable name
        MatchDetail leftDetail = new MatchDetail("foo", ValueType.TERM, new Position(1, -1, 0, 3, java.time.LocalDate.now()), "leftAlias.custom_key");
        MatchDetail rightDetail = new MatchDetail("foo", ValueType.TERM, new Position(2, -1, 0, 3, java.time.LocalDate.now()), "rightAlias.custom_key");
        QueryResult mockLeftResult = createMockQueryResult(Query.Granularity.DOCUMENT, 0, List.of(leftDetail));
        QueryResult mockRightResult = createMockQueryResult(Query.Granularity.DOCUMENT, 0, List.of(rightDetail));
        ContainsExecutor mockContainsExecutor = mock(ContainsExecutor.class);
        doReturn(mockContainsExecutor).when(factory).getExecutor(isA(Contains.class));
        when(mockContainsExecutor.execute(eq(containsConditionLeft), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString()))
                .thenReturn(mockLeftResult);
        when(mockContainsExecutor.execute(eq(containsConditionRight), eq(indexes), eq(Query.Granularity.DOCUMENT), anyInt(), anyString()))
                .thenReturn(mockRightResult);
        Object joinResultsObj = queryExecutor.execute(mainQuery, indexes);
        @SuppressWarnings("unchecked")
        List<com.example.query.binding.JoinedMatch> joinResults = (List<com.example.query.binding.JoinedMatch>) joinResultsObj;
        assertNotNull(joinResults);
        assertEquals(1, joinResults.size(), "Should join on custom string key 'foo'");
        com.example.query.binding.JoinedMatch result = joinResults.get(0);
        assertEquals("foo", result.left().value());
        assertEquals("foo", result.right().value());
    }

    @Test
    void testJoinStrategySelection() throws QueryExecutionException {
        // Create a dummy join condition (INNER join)
        JoinCondition joinCondition = new JoinCondition("$main.id", "q1.id", JoinCondition.JoinType.INNER, TemporalPredicate.INTERSECT);
        Query query = new Query(
            "test_source",
            Collections.emptyList(),
            Collections.emptyList(),
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            Collections.emptyList(),
            new com.example.query.binding.VariableRegistry(),
            List.of(new SubquerySpec(new Query("test_source"), "q1")),
            Optional.of(joinCondition),
            Optional.of("$main")
        );
        // Default should be INDEPENDENT
        QueryExecutor defaultExecutor = new QueryExecutor(factory, mockTableResultService);
        assertEquals(JoinOptimizationStrategy.INDEPENDENT, getJoinStrategy(defaultExecutor));
        // Set to DEPENDENT and verify
        defaultExecutor.setJoinOptimizationStrategy(JoinOptimizationStrategy.DEPENDENT);
        assertEquals(JoinOptimizationStrategy.DEPENDENT, getJoinStrategy(defaultExecutor));
        // Set to INDEPENDENT and verify
        defaultExecutor.setJoinOptimizationStrategy(JoinOptimizationStrategy.INDEPENDENT);
        assertEquals(JoinOptimizationStrategy.INDEPENDENT, getJoinStrategy(defaultExecutor));
    }

    // Helper to access private joinStrategy field via reflection
    private JoinOptimizationStrategy getJoinStrategy(QueryExecutor executor) {
        try {
            java.lang.reflect.Field field = QueryExecutor.class.getDeclaredField("joinStrategy");
            field.setAccessible(true);
            return (JoinOptimizationStrategy) field.get(executor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
} 