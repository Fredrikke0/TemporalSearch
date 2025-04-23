package com.example.query.executor;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.query.model.Query;
import com.example.query.model.condition.*;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import com.example.query.executor.QueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LogicalConditionExecutorTest {

    @Mock private ConditionExecutorFactory mockFactory;
    @Mock private ContainsExecutor mockContainsExecutor;
    @Mock private PosExecutor mockPosExecutor;
    // Mock other executors if needed for more complex tests

    @Mock private Contains mockContainsCond1;
    @Mock private Pos mockPosCond2;

    private LogicalExecutor logicalExecutor;
    private Map<String, IndexAccessInterface> indexes;
    private final LocalDate testDate = LocalDate.now();
    private static final Query.Granularity TEST_GRANULARITY = Query.Granularity.SENTENCE;
    private static final int TEST_WINDOW_SIZE = 0;

    // Helper to create a simple MatchDetail
    private MatchDetail createDetail(int docId, int sentId, String varName, Object value, int begin, int end) {
        Position pos = new Position(docId, sentId, begin, end, testDate);
        // Determine ValueType based on simple inspection of value for testing
        ValueType type = (value instanceof String) ? ValueType.TERM : ValueType.ENTITY; 
        return new MatchDetail(value, type, pos, varName);
    }

    @BeforeEach
    void setUp() {
        logicalExecutor = new LogicalExecutor(mockFactory);
        indexes = new HashMap<>(); // Add mock IndexAccess if needed by sub-executors

        // Basic factory stubbing - Return the mocks of concrete types
        // The factory method likely returns ConditionExecutor, but the mocked
        // instances (mockContainsExecutor, mockPosExecutor) are compatible.
    }

    @Test
    void testExecuteAnd() throws Exception {
        Logical condition = new Logical(Logical.LogicalOperator.AND, List.of(mockContainsCond1, mockPosCond2));
        String var1 = "?term";
        String var2 = "?posTag";
        String val1 = "apple";
        String val2 = "NN"; // POS tag value

        // Mock results using MatchDetail and QueryResult
        List<MatchDetail> containsDetails = List.of(
            createDetail(1, 1, var1, val1, 0, 5), // Match
            createDetail(1, 2, var1, val1, 10, 15),
            createDetail(2, 1, var1, val1, 0, 5)  // Match
        );
        QueryResult containsResult = new QueryResult(TEST_GRANULARITY, TEST_WINDOW_SIZE, containsDetails);

        List<MatchDetail> posDetails = List.of(
            createDetail(1, 1, var2, val2, 6, 10),  // Match
            createDetail(2, 1, var2, val2, 6, 10),  // Match
            createDetail(3, 1, var2, val2, 0, 5)
        );
        QueryResult posResult = new QueryResult(TEST_GRANULARITY, TEST_WINDOW_SIZE, posDetails);

        // Stub factory calls for this test
        when(mockFactory.getExecutor(eq(mockContainsCond1))).thenReturn(mockContainsExecutor);
        when(mockFactory.getExecutor(eq(mockPosCond2))).thenReturn(mockPosExecutor);

        // Stub sub-executor calls to return QueryResult directly
        when(mockContainsExecutor.execute(eq(mockContainsCond1), any(), any(Query.Granularity.class), anyInt(), anyString()))
            .thenReturn(containsResult);

        when(mockPosExecutor.execute(eq(mockPosCond2), any(), any(Query.Granularity.class), anyInt(), anyString()))
            .thenReturn(posResult);

        // Execute - Now returns QueryResult
        QueryResult finalResult = logicalExecutor.execute(condition, indexes, TEST_GRANULARITY, TEST_WINDOW_SIZE, "test_corpus");

        // Verify final result (intersection)
        assertNotNull(finalResult);
        assertEquals(TEST_GRANULARITY, finalResult.getGranularity());
        assertEquals(TEST_WINDOW_SIZE, finalResult.getGranularitySize());

        // Intersection should contain details from both sub-results for matching (docId, sentId) pairs
        // Doc 1, Sent 1: Should have ?term and ?posTag details
        // Doc 2, Sent 1: Should have ?term and ?posTag details
        // Other docs/sents should be excluded

        // Expected details in the intersection (combining details for matching doc/sent)
        List<MatchDetail> expectedDetails = List.of(
             createDetail(1, 1, var1, val1, 0, 5), createDetail(1, 1, var2, val2, 6, 10),
             createDetail(2, 1, var1, val1, 0, 5), createDetail(2, 1, var2, val2, 6, 10) 
        );
        
        // Use sets for comparison as order doesn't matter within QueryResult
        Set<MatchDetail> expectedDetailSet = new HashSet<>(expectedDetails);
        Set<MatchDetail> actualDetailSet = new HashSet<>(finalResult.getAllDetails());

        assertEquals(expectedDetailSet.size(), actualDetailSet.size());
        assertEquals(expectedDetailSet, actualDetailSet);

        // Verify bindings within QueryResult (optional, but good practice)
        Map<Integer, List<MatchDetail>> detailsByDoc = finalResult.getDetailsByDocId();
        assertTrue(detailsByDoc.containsKey(1));
        assertTrue(detailsByDoc.containsKey(2));
        assertFalse(detailsByDoc.containsKey(3)); // Doc 3 should be excluded

        // Check specific bindings for Doc 1
        List<MatchDetail> doc1Details = detailsByDoc.get(1);
        assertTrue(doc1Details.stream().anyMatch(d -> var1.equals(d.variableName().orElse(null)) && val1.equals(d.value())));
        assertTrue(doc1Details.stream().anyMatch(d -> var2.equals(d.variableName().orElse(null)) && val2.equals(d.value())));
    }

    @Test
    void testExecuteOr() throws Exception {
        Logical condition = new Logical(Logical.LogicalOperator.OR, List.of(mockContainsCond1, mockPosCond2));
        String var1 = "?term";
        String var2 = "?posTag";
        String val1 = "apple";
        String val2 = "NN";

        // Mock results using MatchDetail and QueryResult
        List<MatchDetail> containsDetails = List.of(
            createDetail(1, 1, var1, val1, 0, 5),   // Overlap
            createDetail(1, 2, var1, val1, 10, 15) // Only Contains
        );
        QueryResult containsResult = new QueryResult(TEST_GRANULARITY, TEST_WINDOW_SIZE, containsDetails);

        List<MatchDetail> posDetails = List.of(
            createDetail(1, 1, var2, val2, 6, 10),  // Overlap
            createDetail(3, 1, var2, val2, 0, 5)   // Only POS
        );
        QueryResult posResult = new QueryResult(TEST_GRANULARITY, TEST_WINDOW_SIZE, posDetails);

        // Stub factory calls for this test
        when(mockFactory.getExecutor(eq(mockContainsCond1))).thenReturn(mockContainsExecutor);
        when(mockFactory.getExecutor(eq(mockPosCond2))).thenReturn(mockPosExecutor);

        // Stub sub-executor calls
        when(mockContainsExecutor.execute(eq(mockContainsCond1), any(), any(Query.Granularity.class), anyInt(), anyString()))
            .thenReturn(containsResult);
        when(mockPosExecutor.execute(eq(mockPosCond2), any(), any(Query.Granularity.class), anyInt(), anyString()))
            .thenReturn(posResult);

        // Execute
        QueryResult finalResult = logicalExecutor.execute(condition, indexes, TEST_GRANULARITY, TEST_WINDOW_SIZE, "test_corpus");

        // Verify final result (union)
        assertNotNull(finalResult);
        assertEquals(TEST_GRANULARITY, finalResult.getGranularity());
        assertEquals(TEST_WINDOW_SIZE, finalResult.getGranularitySize());

        // Expected details (union of inputs, duplicates handled by Set conversion)
        List<MatchDetail> expectedDetails = List.of(
            createDetail(1, 1, var1, val1, 0, 5), createDetail(1, 1, var2, val2, 6, 10), // Combined from overlap
            createDetail(1, 2, var1, val1, 10, 15),
            createDetail(3, 1, var2, val2, 0, 5)
        );
        Set<MatchDetail> expectedDetailSet = new HashSet<>(expectedDetails);
        Set<MatchDetail> actualDetailSet = new HashSet<>(finalResult.getAllDetails());

        assertEquals(expectedDetailSet.size(), actualDetailSet.size());
        assertEquals(expectedDetailSet, actualDetailSet);

        // Verify specific details are present
        assertTrue(actualDetailSet.stream().anyMatch(d -> d.getDocumentId() == 1 && d.getSentenceId() == 1 && var1.equals(d.variableName().orElse(null))));
        assertTrue(actualDetailSet.stream().anyMatch(d -> d.getDocumentId() == 1 && d.getSentenceId() == 1 && var2.equals(d.variableName().orElse(null))));
        assertTrue(actualDetailSet.stream().anyMatch(d -> d.getDocumentId() == 1 && d.getSentenceId() == 2 && var1.equals(d.variableName().orElse(null))));
        assertTrue(actualDetailSet.stream().anyMatch(d -> d.getDocumentId() == 3 && d.getSentenceId() == 1 && var2.equals(d.variableName().orElse(null))));
    }

    @Test
    void testExecuteAndShortCircuit() throws QueryExecutionException {
        Logical condition = new Logical(Logical.LogicalOperator.AND, List.of(mockContainsCond1, mockPosCond2));

        // Stub factory calls for this test
        when(mockFactory.getExecutor(eq(mockContainsCond1))).thenReturn(mockContainsExecutor);

        // Mock first executor returns empty QueryResult
        when(mockContainsExecutor.execute(eq(mockContainsCond1), eq(indexes), eq(TEST_GRANULARITY), eq(TEST_WINDOW_SIZE), anyString()))
            .thenReturn(new QueryResult(TEST_GRANULARITY, TEST_WINDOW_SIZE, Collections.emptyList()));

        // Setup second executor leniently (should not be called due to short-circuit)
        verify(mockPosExecutor, never()).execute(eq(mockPosCond2), eq(indexes), eq(TEST_GRANULARITY), eq(TEST_WINDOW_SIZE), anyString());

        // Execute
        QueryResult finalResult = logicalExecutor.execute(condition, indexes, TEST_GRANULARITY, TEST_WINDOW_SIZE, "test_corpus");

        // Verify
        assertNotNull(finalResult);
        assertTrue(finalResult.getAllDetails().isEmpty(), "Result should be empty due to short-circuit");
        
        // Verify first executor was called
        verify(mockContainsExecutor).execute(eq(mockContainsCond1), eq(indexes), eq(TEST_GRANULARITY), eq(TEST_WINDOW_SIZE), anyString());
    }

    @Test
    void testExecuteEmptySubconditions() throws QueryExecutionException {
        Logical condition = new Logical(Logical.LogicalOperator.OR, Collections.emptyList());

        // Execute should handle empty conditions gracefully (return empty result)
        QueryResult result = logicalExecutor.execute(condition, indexes, TEST_GRANULARITY, TEST_WINDOW_SIZE, "test_corpus");

        assertNotNull(result);
        assertTrue(result.getAllDetails().isEmpty(), "Expected empty result for empty conditions");
        // Verify factory was not called
        verify(mockFactory, never()).getExecutor(any());
    }

    // --- Tests for intersectQueryResultsSortMerge --- 
    
    @Test
    void intersectSortMerge_DocumentGranularity_BothEmpty() {
        QueryResult r1 = new QueryResult(Query.Granularity.DOCUMENT, Collections.emptyList());
        QueryResult r2 = new QueryResult(Query.Granularity.DOCUMENT, Collections.emptyList());
        QueryResult result = logicalExecutor.intersectQueryResultsSortMerge(r1, r2);
        assertTrue(result.getAllDetails().isEmpty());
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
    }

    @Test
    void intersectSortMerge_DocumentGranularity_OneEmpty() {
        QueryResult r1 = new QueryResult(Query.Granularity.DOCUMENT, List.of(md(1), md(2)));
        QueryResult r2 = new QueryResult(Query.Granularity.DOCUMENT, Collections.emptyList());
        QueryResult result1 = logicalExecutor.intersectQueryResultsSortMerge(r1, r2);
        QueryResult result2 = logicalExecutor.intersectQueryResultsSortMerge(r2, r1);
        assertTrue(result1.getAllDetails().isEmpty());
        assertTrue(result2.getAllDetails().isEmpty());
    }

    @Test
    void intersectSortMerge_DocumentGranularity_NoCommonDocs() {
        QueryResult r1 = new QueryResult(Query.Granularity.DOCUMENT, List.of(md(1), md(3)));
        QueryResult r2 = new QueryResult(Query.Granularity.DOCUMENT, List.of(md(2), md(4)));
        QueryResult result = logicalExecutor.intersectQueryResultsSortMerge(r1, r2);
        assertTrue(result.getAllDetails().isEmpty());
    }

    @Test
    void intersectSortMerge_DocumentGranularity_SomeCommonDocs() {
        MatchDetail d1_1 = md(1);
        MatchDetail d1_2 = md(1); // Duplicate in same doc is possible
        MatchDetail d2_1 = md(2);
        MatchDetail d3_1 = md(3);
        MatchDetail d4_1 = md(4);
        MatchDetail d2_2 = md(2); 

        QueryResult r1 = new QueryResult(Query.Granularity.DOCUMENT, List.of(d1_1, d1_2, d3_1)); // Docs 1, 3
        QueryResult r2 = new QueryResult(Query.Granularity.DOCUMENT, List.of(d2_1, d2_2, d4_1)); // Docs 2, 4
        // Intentionally swapped order for r2 input
        QueryResult r3 = new QueryResult(Query.Granularity.DOCUMENT, List.of(d4_1, d2_1, d2_2)); // Docs 2, 4
        QueryResult r4 = new QueryResult(Query.Granularity.DOCUMENT, List.of(d1_1, d2_1)); // Docs 1, 2

        QueryResult result_1_3 = logicalExecutor.intersectQueryResultsSortMerge(r1, r3); // Docs 1,3 INTERSECT 2,4 -> {} 
        QueryResult result_1_4 = logicalExecutor.intersectQueryResultsSortMerge(r1, r4); // Docs 1,3 INTERSECT 1,2 -> {Doc 1}
        QueryResult result_3_4 = logicalExecutor.intersectQueryResultsSortMerge(r3, r4); // Docs 2,4 INTERSECT 1,2 -> {Doc 2}

        assertTrue(result_1_3.getAllDetails().isEmpty());

        // Use sorted lists for comparison
        assertEquals(sortDetails(List.of(d1_1, d1_2, d1_1)), getSortedDetails(result_1_4), "Doc 1 intersection failed"); 
        assertEquals(sortDetails(List.of(d2_1, d2_2, d2_1)), getSortedDetails(result_3_4), "Doc 2 intersection failed"); 
    }

     @Test
    void intersectSortMerge_DocumentGranularity_IdenticalResults() {
        MatchDetail d1 = md(1);
        MatchDetail d2 = md(2);
        List<MatchDetail> details = List.of(d1, d2);
        QueryResult r1 = new QueryResult(Query.Granularity.DOCUMENT, details);
        QueryResult r2 = new QueryResult(Query.Granularity.DOCUMENT, details);
        QueryResult result = logicalExecutor.intersectQueryResultsSortMerge(r1, r2);

        assertEquals(sortDetails(List.of(d1, d2, d1, d2)), getSortedDetails(result)); 
    }

    @Test
    void intersectSortMerge_DocumentGranularity_Subset() {
        MatchDetail d1 = md(1);
        MatchDetail d2 = md(2);
        MatchDetail d3 = md(3);

        QueryResult r1 = new QueryResult(Query.Granularity.DOCUMENT, List.of(d1, d2, d3)); // Docs 1, 2, 3
        QueryResult r2 = new QueryResult(Query.Granularity.DOCUMENT, List.of(d1, d3));    // Docs 1, 3

        QueryResult result1 = logicalExecutor.intersectQueryResultsSortMerge(r1, r2);
        QueryResult result2 = logicalExecutor.intersectQueryResultsSortMerge(r2, r1);

        assertEquals(sortDetails(List.of(d1, d3, d1, d3)), getSortedDetails(result1));
        assertEquals(sortDetails(List.of(d1, d3, d1, d3)), getSortedDetails(result2));
    }

    // Helper to compare details ignoring order and potential duplicates from merge
    private List<MatchDetail> getSortedDetails(QueryResult result) {
        // Need a consistent way to sort MatchDetail for comparison
        // Sorting by docId, then sentId, then value.toString()
        List<MatchDetail> details = new ArrayList<>(result.getAllDetails());
        details.sort(Comparator.<MatchDetail, Integer>comparing(MatchDetail::getDocumentId)
                              .thenComparing(MatchDetail::getSentenceId)
                              .thenComparing(d -> d.value().toString()) 
                              // Add more criteria if needed for uniqueness in tests
                              .thenComparing(System::identityHashCode)); // Tie-breaker
        return details;
    }

    // Helper to sort details for comparison in tests
    private List<MatchDetail> sortDetails(List<MatchDetail> details) {
        List<MatchDetail> sorted = new ArrayList<>(details);
        // Use the same comparator as in getSortedDetails
        sorted.sort(Comparator.<MatchDetail, Integer>comparing(MatchDetail::getDocumentId)
                            .thenComparing(MatchDetail::getSentenceId)
                            .thenComparing(d -> d.value().toString())
                            .thenComparing(System::identityHashCode)); // Tie-breaker
        return sorted;
    }

    // Helper to create a basic MatchDetail for testing intersection logic
    private MatchDetail md(int docId, int sentId) { 
        return new MatchDetail(
            "value_" + docId + "_" + sentId, // Dummy value
            ValueType.TERM,                  // Dummy type
            new Position(docId, sentId, 0, 0, null), // Position object
            (String) null
        );
    }

    private MatchDetail md(int docId) { // Helper for document granularity
        return md(docId, -1); // Use -1 or a consistent marker for doc level
    }

    // --- Tests for Sentence Granularity --- 
    @Test
    void intersectSortMerge_SentenceGranularity_NoCommonDocs() {
        QueryResult r1 = new QueryResult(Query.Granularity.SENTENCE, 0, List.of(md(1, 1), md(1, 2)));
        QueryResult r2 = new QueryResult(Query.Granularity.SENTENCE, 0, List.of(md(2, 1), md(2, 2)));
        QueryResult result = logicalExecutor.intersectQueryResultsSortMerge(r1, r2);
        assertTrue(result.getAllDetails().isEmpty());
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        assertEquals(0, result.getGranularitySize());
    }

    @Test
    void intersectSortMerge_SentenceGranularity_CommonDocs_NoOverlap_Window0() {
        QueryResult r1 = new QueryResult(Query.Granularity.SENTENCE, 0, List.of(md(1, 1), md(1, 3)));
        QueryResult r2 = new QueryResult(Query.Granularity.SENTENCE, 0, List.of(md(1, 2), md(1, 4), md(2,1)));
        QueryResult result = logicalExecutor.intersectQueryResultsSortMerge(r1, r2);
        assertTrue(result.getAllDetails().isEmpty(), "Expected no overlap with window size 0");
    }

    @Test
    void intersectSortMerge_SentenceGranularity_CommonDocs_Overlap_Window0() {
        MatchDetail d1_1 = md(1, 1);
        MatchDetail d1_3 = md(1, 3);
        MatchDetail d1_3_alt = md(1, 3); // From r2
        MatchDetail d2_1 = md(2, 1);
        QueryResult r1 = new QueryResult(Query.Granularity.SENTENCE, 0, List.of(d1_1, d1_3));        // Doc 1, Sents 1, 3
        QueryResult r2 = new QueryResult(Query.Granularity.SENTENCE, 0, List.of(md(1, 2), d1_3_alt, d2_1)); // Doc 1, Sents 2, 3; Doc 2, Sent 1
        QueryResult result = logicalExecutor.intersectQueryResultsSortMerge(r1, r2);

        assertEquals(sortDetails(List.of(d1_3, d1_3_alt)), getSortedDetails(result), "Expected overlap only for sentence 3 with window 0");
    }

    @Test
    void intersectSortMerge_SentenceGranularity_CommonDocs_Overlap_Window1() {
        // Window 1 means allowedDistance = (1-1)/2 = 0 -> Adjacent not allowed, only exact match
        // Let's redefine window size interpretation or test case.
        // Assuming window N means +/- (N-1)/2 sentences distance.
        // So window size 1 -> distance 0 (exact match)
        // Window size 3 -> distance 1 (adjacent allowed)
        int windowSize = 3; // +/- 1 sentence
        MatchDetail d1_1 = md(1, 1);
        MatchDetail d1_2 = md(1, 2);
        MatchDetail d1_3 = md(1, 3);
        MatchDetail d1_4 = md(1, 4);
        MatchDetail d1_5 = md(1, 5);
        MatchDetail d2_1 = md(2, 1);
        MatchDetail d2_3 = md(2, 3);

        QueryResult r1 = new QueryResult(Query.Granularity.SENTENCE, windowSize, List.of(d1_1, d1_4, d2_1));         // D1:S1,S4; D2:S1
        QueryResult r2 = new QueryResult(Query.Granularity.SENTENCE, windowSize, List.of(d1_3, d1_5, md(1, 8), d2_3)); // D1:S3,S5,S8; D2:S3

        QueryResult result = logicalExecutor.intersectQueryResultsSortMerge(r1, r2);

        // Expected: 
        // Doc 1: S1(r1) no match. S4(r1) matches S3(r2) & S5(r2). S3(r2) matches S4(r1). S5(r2) matches S4(r1). S8(r2) no match.
        // Doc 2: S1(r1) no match. S3(r2) no match.
        // Result units: (1,3), (1,4), (1,5) 
        // Use sorted lists for comparison, collecting details from involved units
        List<MatchDetail> expectedDetails = List.of(d1_3, d1_4, d1_5); 

        assertEquals(sortDetails(expectedDetails), getSortedDetails(result), "Expected overlap for sentences 3, 4, 5 in Doc 1 with window " + windowSize);
        assertEquals(windowSize, result.getGranularitySize());
    }

    @Test
    void intersectSortMerge_SentenceGranularity_IdenticalResults() {
        int windowSize = 1; // Exact match
        MatchDetail d1_1 = md(1, 1);
        MatchDetail d2_2 = md(2, 2);
        List<MatchDetail> details = List.of(d1_1, d2_2);
        QueryResult r1 = new QueryResult(Query.Granularity.SENTENCE, windowSize, details);
        QueryResult r2 = new QueryResult(Query.Granularity.SENTENCE, windowSize, details);
        QueryResult result = logicalExecutor.intersectQueryResultsSortMerge(r1, r2);

        assertEquals(sortDetails(List.of(d1_1, d2_2, d1_1, d2_2)), getSortedDetails(result));
        assertEquals(windowSize, result.getGranularitySize());
    }

     @Test
    void intersectSortMerge_MismatchedGranularity_ShouldReturnEmpty() {
        QueryResult r1 = new QueryResult(Query.Granularity.DOCUMENT, List.of(md(1)));
        QueryResult r2 = new QueryResult(Query.Granularity.SENTENCE, 0, List.of(md(1, 1)));
        QueryResult result1 = logicalExecutor.intersectQueryResultsSortMerge(r1, r2);
        QueryResult result2 = logicalExecutor.intersectQueryResultsSortMerge(r2, r1);

        assertTrue(result1.getAllDetails().isEmpty(), "Intersection of different granularities should be empty");
        assertEquals(Query.Granularity.DOCUMENT, result1.getGranularity()); // Returns first granularity on error

        assertTrue(result2.getAllDetails().isEmpty(), "Intersection of different granularities should be empty");
        assertEquals(Query.Granularity.SENTENCE, result2.getGranularity()); // Returns first granularity on error
    }

    // TODO: Add tests for executeAnd/executeOr with mocked sub-executors
} 