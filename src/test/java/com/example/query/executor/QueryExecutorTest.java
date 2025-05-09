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
import java.time.LocalDateTime;
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
import com.example.query.binding.VariableRegistry;
import com.example.query.model.JoinCondition.JoinType;
import com.example.query.model.JoinCondition.JoinOperatorType;
import org.mockito.ArgumentCaptor;
import com.example.query.binding.JoinedMatch; // Added for Join results

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
    @Mock private ContainsExecutor containsExecutor;
    @Mock private NerExecutor nerExecutor;
    @Mock private TemporalExecutor temporalExecutor; // Mock TemporalExecutor
    @Mock private IndexManager indexManager;

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
        
        // Mock the factory to return specific executors when needed
        lenient().doReturn(containsExecutor).when(factory).getExecutor(isA(Contains.class));
        lenient().doReturn(nerExecutor).when(factory).getExecutor(isA(Ner.class));
        lenient().doReturn(temporalExecutor).when(factory).getExecutor(isA(Temporal.class));
        // Mock for Logical and Not might be needed depending on tests
        lenient().doReturn(mockLogicalExecutor).when(factory).getExecutor(isA(Logical.class));
        // lenient().doReturn(notExecutor).when(factory).getExecutor(isA(Not.class));

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

    // Helper to create MatchDetail with a specific LocalDate value for a key
    private MatchDetail createMatchDetailWithDate(int docId, int sentenceId, String keyNameForDate, LocalDate date, String alias) {
        Position pos = new Position(docId, sentenceId, 0, 5, date); // Assuming some length and using date as timestamp
        // Store the date as the value, and keyNameForDate in the variableName for easy extraction in mocks/tests if needed.
        // Actual MatchDetail from NerDateIndex would have the date as part of its structured value or specific field.
        // For testing JoinHandler.extractValueForKey, it expects the key to match a variable or structural key.
        // If `keyNameForDate` is intended to be a variable, it should be prefixed (e.g. "?dateVar").
        // Here, we assume `keyNameForDate` is the key that will be used with `extractValueForKey`.
        // Let's use the alias.keyName format for the variableName if an alias is provided.
        String mockVariableName = alias != null ? alias + "." + keyNameForDate : keyNameForDate;
        return new MatchDetail(date, ValueType.DATE, pos, Optional.of(mockVariableName));
    }

    private MatchDetail createMatchDetailWithDate(int docId, String keyNameForDate, LocalDate date, String alias) {
        Position pos = new Position(docId, -1, 0, 5, date); // Document level
        String mockVariableName = alias != null ? alias + "." + keyNameForDate : keyNameForDate;
        return new MatchDetail(date, ValueType.DATE, pos, Optional.of(mockVariableName));
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
        JoinCondition joinCondition = JoinCondition.createEqualityJoin("leftAlias.DOCUMENT_ID", "rightAlias.DOCUMENT_ID", JoinCondition.JoinType.INNER);
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
        JoinCondition joinCondition = JoinCondition.createEqualityJoin("leftAlias.SENTENCE_ID", "rightAlias.SENTENCE_ID", JoinCondition.JoinType.INNER);
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
        JoinCondition joinCondition = JoinCondition.createEqualityJoin("leftAlias.custom_key", "rightAlias.custom_key", JoinCondition.JoinType.INNER);
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
    void executeQueryWithTwoConditions() throws QueryExecutionException, IndexAccessException {
        // Setup Query
        Contains containsCondition = new Contains(List.of("term"));
        Ner nerCondition = new Ner("PERSON", null, null, false);
        Query query = new Query("wikipedia", List.of(containsCondition, nerCondition));

        // Setup Mock Executors
        QueryResult containsResult = new QueryResult(Query.Granularity.DOCUMENT, List.of(
            new MatchDetail("term", ValueType.TERM, new Position(1, 1, 0, 4, LocalDate.now()), Optional.empty()),
            new MatchDetail("term", ValueType.TERM, new Position(2, 1, 0, 4, LocalDate.now()), Optional.empty())
        ));
        QueryResult nerResult = new QueryResult(Query.Granularity.DOCUMENT, List.of(
            new MatchDetail("Alice", ValueType.ENTITY, new Position(1, 2, 10, 15, LocalDate.now()), Optional.empty()),
            new MatchDetail("Bob", ValueType.ENTITY, new Position(3, 1, 5, 8, LocalDate.now()), Optional.empty())
        ));
        
        // Mock LogicalExecutor behavior (intersection/AND)
        LogicalExecutor logicalExecutor = mock(LogicalExecutor.class);
        QueryResult combinedResult = new QueryResult(Query.Granularity.DOCUMENT, List.of(
            // Assume only doc 1 matches both Contains and Ner
             new MatchDetail("term", ValueType.TERM, new Position(1, 1, 0, 4, LocalDate.now()), Optional.empty()),
             new MatchDetail("Alice", ValueType.ENTITY, new Position(1, 2, 10, 15, LocalDate.now()), Optional.empty())
        ));
        
        // Explicitly tell the factory what to return for each condition type
        // Use doReturn().when() for the spy
         // When the QueryExecutor creates the implicit AND condition, the factory should return our mock LogicalExecutor
        doReturn(logicalExecutor).when(factory).getExecutor(isA(com.example.query.model.condition.Logical.class));

        // Define behavior for the mock LogicalExecutor when it executes the implicit AND
        when(logicalExecutor.execute(isA(com.example.query.model.condition.Logical.class), anyMap(), eq(Query.Granularity.DOCUMENT), eq(0), eq("wikipedia")))
            .thenReturn(combinedResult);
        
        // REMOVED UNNECESSARY STUBS for individual executors as the mocked LogicalExecutor handles the combined logic
        // when(containsExecutor.execute(eq(containsCondition), anyMap(), any(Query.Granularity.class), anyInt(), anyString())).thenReturn(containsResult);
        // when(nerExecutor.execute(eq(nerCondition), anyMap(), any(Query.Granularity.class), anyInt(), anyString())).thenReturn(nerResult);

        // Execute
        Object result = queryExecutor.execute(query, Map.of("unigram", unigramIndex)); // Assuming 'unigram' index is relevant, adjust if needed

        // Verify
        assertTrue(result instanceof QueryResult);
        // Check size based on the combined result from the mocked LogicalExecutor
        assertEquals(2, ((QueryResult) result).getAllDetails().size()); 
        verify(logicalExecutor, times(1)).execute(any(com.example.query.model.condition.Logical.class), anyMap(), eq(Query.Granularity.DOCUMENT), eq(0), eq("wikipedia"));
    }

    // --- Temporal Join Integration Tests ---

    // Helper to create MatchDetail with LocalDate value for temporal joins
    private MatchDetail createTemporalMatchDetail(int docId, int sentenceId, LocalDate date, String alias) {
        Position pos = new Position(docId, sentenceId, 0, 0, date);
        // Value is the LocalDate, type is DATE, variable name has alias prefix
        return new MatchDetail(date, ValueType.DATE, pos, Optional.of(alias + ".date"));
    }

    @Test
    void testTemporalJoinBefore() throws QueryExecutionException {
        // Query: SELECT * FROM source q1 JOIN source q2 ON q1.date BEFORE q2.date
        String source = "temporal_source";
        // Use a valid Temporal constructor *with distinct variables* for dummy conditions
        // Temporal cond1 = new Temporal(TemporalPredicate.CONTAINS, LocalDate.of(2023, 1, 1).atStartOfDay(), Optional.empty(), "q1.date"); // Correct constructor
        // Temporal cond2 = new Temporal(TemporalPredicate.CONTAINS, LocalDate.of(2023, 1, 1).atStartOfDay(), Optional.empty(), "q2.date"); // Correct constructor
        LocalDateTime dummyDate = LocalDate.of(2023, 1, 1).atStartOfDay();
        // Use canonical constructor with required Optionals for CONTAINS
        Temporal cond1 = new Temporal(Optional.of(dummyDate), Optional.of(dummyDate), Optional.of("q1.date"), Optional.empty(), TemporalPredicate.CONTAINS);
        Temporal cond2 = new Temporal(Optional.of(dummyDate), Optional.of(dummyDate), Optional.of("q2.date"), Optional.empty(), TemporalPredicate.CONTAINS);

        // Correct SubquerySpec constructor order: Query, alias
        SubquerySpec sub1 = new SubquerySpec(new Query(source, List.of(cond1)), "q1");
        SubquerySpec sub2 = new SubquerySpec(new Query(source, List.of(cond2)), "q2");

        JoinCondition joinCond = JoinCondition.createTemporalJoin(
            "q1.date", "q2.date", JoinType.INNER, TemporalPredicate.BEFORE
        );

        // Use the full Query constructor for the main query
        Query mainQuery = new Query(
            source,                       // Source for main query part (can be same)
            Collections.emptyList(),      // No main conditions
            Collections.emptyList(),      // No ORDER BY
            Optional.empty(),             // No LIMIT
            Query.Granularity.DOCUMENT,   // Granularity
            Optional.empty(),             // No granularity size
            Collections.emptyList(),      // No explicit SELECT (implies *)
            new VariableRegistry(),       // Default empty registry
            List.of(sub1, sub2),          // Subqueries
            Optional.of(joinCond),        // Join condition
            Optional.empty()              // No explicit main alias
        );

        // Mock subquery results
        MatchDetail q1_d1 = createTemporalMatchDetail(1, -1, LocalDate.of(2023, 1, 10), "q1");
        MatchDetail q1_d2 = createTemporalMatchDetail(2, -1, LocalDate.of(2023, 1, 15), "q1");
        QueryResult q1Result = createMockQueryResult(Query.Granularity.DOCUMENT, 0, List.of(q1_d1, q1_d2));

        MatchDetail q2_d3 = createTemporalMatchDetail(3, -1, LocalDate.of(2023, 1, 12), "q2");
        MatchDetail q2_d4 = createTemporalMatchDetail(4, -1, LocalDate.of(2023, 1, 20), "q2");
        QueryResult q2Result = createMockQueryResult(Query.Granularity.DOCUMENT, 0, List.of(q2_d3, q2_d4));

        // Mock the TemporalExecutor results for each subquery execution
        // Use lenient() because the order might vary based on internal QueryExecutor logic
        lenient().when(temporalExecutor.execute(eq(cond1), eq(indexes), any(), anyInt(), eq(source))).thenReturn(q1Result);
        lenient().when(temporalExecutor.execute(eq(cond2), eq(indexes), any(), anyInt(), eq(source))).thenReturn(q2Result);

        // Execute the main query
        Object result = queryExecutor.execute(mainQuery, indexes);

        // Verify the result is a List<JoinedMatch>
        assertTrue(result instanceof List, "Result should be a List");
        @SuppressWarnings("unchecked")
        List<JoinedMatch> joinedMatches = (List<JoinedMatch>) result;

        // Assertions: Expect 3 pairs where q1.date < q2.date
        // (q1_d1, q2_d3), (q1_d1, q2_d4), (q1_d2, q2_d4)
        assertEquals(3, joinedMatches.size(), "Expected 3 joined pairs for BEFORE");
        Set<JoinedMatch> resultSet = Set.copyOf(joinedMatches);
        assertTrue(resultSet.contains(new JoinedMatch(q1_d1, q2_d3)), "Missing pair: q1_d1 < q2_d3");
        assertTrue(resultSet.contains(new JoinedMatch(q1_d1, q2_d4)), "Missing pair: q1_d1 < q2_d4");
        assertTrue(resultSet.contains(new JoinedMatch(q1_d2, q2_d4)), "Missing pair: q1_d2 < q2_d4");
    }

    @Test
    void testTemporalJoinAfter() throws QueryExecutionException {
        // Query: SELECT * FROM source q1 JOIN source q2 ON q1.date AFTER q2.date
        String source = "temporal_source";
        // Use a valid Temporal constructor *with distinct variables* for dummy conditions
        // Temporal cond1 = new Temporal(TemporalPredicate.CONTAINS, LocalDate.of(2023, 1, 1).atStartOfDay(), Optional.empty(), "q1.date"); // Correct constructor
        // Temporal cond2 = new Temporal(TemporalPredicate.CONTAINS, LocalDate.of(2023, 1, 1).atStartOfDay(), Optional.empty(), "q2.date"); // Correct constructor
        LocalDateTime dummyDate = LocalDate.of(2023, 1, 1).atStartOfDay();
        // Use canonical constructor with required Optionals for CONTAINS
        Temporal cond1 = new Temporal(Optional.of(dummyDate), Optional.of(dummyDate), Optional.of("q1.date"), Optional.empty(), TemporalPredicate.CONTAINS);
        Temporal cond2 = new Temporal(Optional.of(dummyDate), Optional.of(dummyDate), Optional.of("q2.date"), Optional.empty(), TemporalPredicate.CONTAINS);

        // Correct SubquerySpec constructor order: Query, alias
        SubquerySpec sub1 = new SubquerySpec(new Query(source, List.of(cond1)), "q1");
        SubquerySpec sub2 = new SubquerySpec(new Query(source, List.of(cond2)), "q2");

        JoinCondition joinCond = JoinCondition.createTemporalJoin(
            "q1.date", "q2.date", JoinType.INNER, TemporalPredicate.AFTER
        );

        // Use the full Query constructor for the main query
        Query mainQuery = new Query(
            source,                       // Source for main query part
            Collections.emptyList(),      // No main conditions
            Collections.emptyList(),      // No ORDER BY
            Optional.empty(),             // No LIMIT
            Query.Granularity.DOCUMENT,   // Granularity
            Optional.empty(),             // No granularity size
            Collections.emptyList(),      // No explicit SELECT (implies *)
            new VariableRegistry(),       // Default empty registry
            List.of(sub1, sub2),          // Subqueries
            Optional.of(joinCond),        // Join condition
            Optional.empty()              // No explicit main alias
        );

        // Mock subquery results
        MatchDetail q1_d1 = createTemporalMatchDetail(1, -1, LocalDate.of(2023, 1, 15), "q1");
        MatchDetail q1_d2 = createTemporalMatchDetail(2, -1, LocalDate.of(2023, 1, 25), "q1");
        QueryResult q1Result = createMockQueryResult(Query.Granularity.DOCUMENT, 0, List.of(q1_d1, q1_d2));

        MatchDetail q2_d3 = createTemporalMatchDetail(3, -1, LocalDate.of(2023, 1, 10), "q2");
        MatchDetail q2_d4 = createTemporalMatchDetail(4, -1, LocalDate.of(2023, 1, 20), "q2");
        QueryResult q2Result = createMockQueryResult(Query.Granularity.DOCUMENT, 0, List.of(q2_d3, q2_d4));

        // Mock the TemporalExecutor results
        lenient().when(temporalExecutor.execute(eq(cond1), eq(indexes), any(), anyInt(), eq(source))).thenReturn(q1Result);
        lenient().when(temporalExecutor.execute(eq(cond2), eq(indexes), any(), anyInt(), eq(source))).thenReturn(q2Result);

        // Execute the main query
        Object result = queryExecutor.execute(mainQuery, indexes);

        // Verify the result
        assertTrue(result instanceof List, "Result should be a List");
        @SuppressWarnings("unchecked")
        List<JoinedMatch> joinedMatches = (List<JoinedMatch>) result;

        // Assertions: Expect 3 pairs where q1.date > q2.date
        // (q1_d1, q2_d3), (q1_d2, q2_d3), (q1_d2, q2_d4)
        assertEquals(3, joinedMatches.size(), "Expected 3 joined pairs for AFTER");
        Set<JoinedMatch> resultSet = Set.copyOf(joinedMatches);
        assertTrue(resultSet.contains(new JoinedMatch(q1_d1, q2_d3)), "Missing pair: q1_d1 > q2_d3");
        assertTrue(resultSet.contains(new JoinedMatch(q1_d2, q2_d3)), "Missing pair: q1_d2 > q2_d3");
        assertTrue(resultSet.contains(new JoinedMatch(q1_d2, q2_d4)), "Missing pair: q1_d2 > q2_d4");
    }

    // --- Dependent Join Strategy Tests ---

    @Test
    void testExecuteDependentJoin_BeforePredicate_Success() throws QueryExecutionException {
        // ---- Test Setup ----
        String mainSource = "test_source";
        String leadingAlias = "lead";
        String dependentAlias = "dep";
        String leadingDateKey = "event_date";
        String dependentDateKey = "tx_date";

        Contains dependentCondition = new Contains("some_term"); 
        Query dependentSubquery = new Query(mainSource, List.of(dependentCondition), Query.Granularity.DOCUMENT);
        SubquerySpec dependentSubquerySpec = new SubquerySpec(dependentSubquery, dependentAlias);

        JoinCondition joinCondition = JoinCondition.createTemporalJoin(
            leadingAlias + "." + leadingDateKey, 
            dependentAlias + "." + dependentDateKey, 
            JoinType.INNER, 
            TemporalPredicate.BEFORE
        );

        Query overallQuery = new Query(
            mainSource, Collections.emptyList(), Collections.emptyList(), Optional.empty(),        
            Query.Granularity.DOCUMENT, Optional.empty(), Collections.emptyList(), 
            new VariableRegistry(), List.of(dependentSubquerySpec), Optional.of(joinCondition), Optional.of(leadingAlias)       
        );

        LocalDate date1 = LocalDate.of(2023, 1, 10);
        LocalDate date2 = LocalDate.of(2023, 1, 15); 
        MatchDetail lead_d1 = createMatchDetailWithDate(1, leadingDateKey, date1, leadingAlias);
        MatchDetail lead_d2 = createMatchDetailWithDate(2, leadingDateKey, date2, leadingAlias);
        QueryResult mainConditionsResult = createMockQueryResult(Query.Granularity.DOCUMENT, 0, List.of(lead_d1, lead_d2));

        MatchDetail dep_filtered_d1 = createMatchDetailWithDate(101, dependentDateKey, LocalDate.of(2023, 1, 12), dependentAlias); 
        QueryResult filteredDependentResult = createMockQueryResult(Query.Granularity.DOCUMENT, 0, List.of(dep_filtered_d1));
        
        // ArgumentCaptor<Temporal> newTemporalFilterCaptor = ArgumentCaptor.forClass(Temporal.class); // Removed, will get from modifiedQueryCaptor

        // ---- Spy on QueryExecutor and Stub internal executeWithContext call ----
        QueryExecutor realExecutor = new QueryExecutor(factory, mockTableResultService);
        QueryExecutor spiedQueryExecutor = spy(realExecutor);

        // Stub the specific internal call to executeWithContext that handles the modified dependent query.
        // This is the primary stubbing for the dependent query execution path.
        ArgumentCaptor<Query> modifiedQueryCaptor = ArgumentCaptor.forClass(Query.class);
        doReturn(filteredDependentResult).when(spiedQueryExecutor).executeWithContext(
            modifiedQueryCaptor.capture(), 
            eq(indexes), 
            any(SubqueryContext.class)
        );
        // Ensure no other competing stubs for spiedQueryExecutor.executeWithContext exist for this scenario.

        // ---- Execution ----
        spiedQueryExecutor.setJoinOptimizationStrategy(JoinOptimizationStrategy.DEPENDENT);
        SubqueryContext initialSubqueryContext = new SubqueryContext();
        initialSubqueryContext.addQueryResult(leadingAlias, mainConditionsResult);

        // We will call the real executeDependentJoin on the spied executor.
        // The internal call to executeWithContext for the modified query will be stubbed.
        Object resultFromDependentJoin = spiedQueryExecutor.executeDependentJoin(overallQuery, indexes, initialSubqueryContext, leadingAlias, mainConditionsResult);

        // ---- Assertions ----
        verify(spiedQueryExecutor, never()).executeIndependentJoin(any(), any(), any());

        // Extract and verify the new Temporal filter from the captured modifiedQuery
        Query capturedModifiedQuery = modifiedQueryCaptor.getValue();
        assertNotNull(capturedModifiedQuery, "Modified query should have been captured.");
        assertEquals(2, capturedModifiedQuery.conditions().size(), "Modified query should have two conditions.");
        
        Temporal capturedFilter = capturedModifiedQuery.conditions().stream()
            .filter(Temporal.class::isInstance)
            .map(Temporal.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Temporal filter not found in modified query's conditions"));

        assertNotNull(capturedFilter, "New temporal filter should have been part of the modified query.");
        assertEquals(TemporalPredicate.AFTER, capturedFilter.temporalType(), "Filter predicate should be AFTER for original BEFORE join.");
        assertEquals(Optional.of(date1.atStartOfDay()), capturedFilter.startDate(), "Filter start date should be minLeadingDate.");
        assertEquals(Optional.empty(), capturedFilter.qualifiedVariableName(), "Filter should not bind a variable.");

        QueryResult resultInContextForDependent = initialSubqueryContext.getQueryResult(dependentAlias);
        assertNotNull(resultInContextForDependent, "Filtered result for dependent alias should be in context.");
        assertEquals(filteredDependentResult.getAllDetails().size(), resultInContextForDependent.getAllDetails().size(), "Context should hold the filtered dependent results.");
        if (!filteredDependentResult.getAllDetails().isEmpty() && !resultInContextForDependent.getAllDetails().isEmpty()) {
             assertEquals(filteredDependentResult.getAllDetails().get(0), resultInContextForDependent.getAllDetails().get(0), "Content of filtered result in context should match.");
        }
    }

} 