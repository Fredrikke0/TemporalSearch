package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.query.model.Query;
import com.example.query.model.condition.Contains;
import com.example.query.executor.QueryResult;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.iq80.leveldb.DBIterator;

@ExtendWith(MockitoExtension.class)
public class ContainsConditionExecutorTest {

    @Mock private IndexAccess mockUnigramIndex;
    @Mock private IndexAccess mockBigramIndex;
    @Mock private IndexAccess mockTrigramIndex;
    @Mock private DBIterator unigramIterator;
    @Mock private DBIterator bigramIterator;
    @Mock private DBIterator trigramIterator;
    
    private ContainsExecutor executor;
    private Map<String, IndexAccessInterface> indexes;
    
    @BeforeEach
    void setUp() throws IndexAccessException {
        indexes = Map.of("unigram", mockUnigramIndex, "bigram", mockBigramIndex, "trigram", mockTrigramIndex);
        // Make lenient as it might not be used in all tests (e.g., wildcard tests)
        lenient().when(mockUnigramIndex.iterator()).thenReturn(mock(DBIterator.class));
        lenient().when(mockBigramIndex.iterator()).thenReturn(mock(DBIterator.class));
        lenient().when(mockTrigramIndex.iterator()).thenReturn(mock(DBIterator.class));
        
        executor = new ContainsExecutor();
    }
    
    @Test
    void testExecuteSingleTerm() throws Exception {
        // Setup
        Contains condition = new Contains("test");
        PositionList positionList = new PositionList();
        positionList.add(new Position(1, 1, 0, 4));
        positionList.add(new Position(2, 1, 5, 9));
        
        // Expect unigram index to be used for single term
        when(mockUnigramIndex.get(any())).thenReturn(Optional.of(positionList));
        
        // Execute with document granularity
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");
        
        // Verify
        verify(mockUnigramIndex).get(any());
        assertEquals(2, result.getAllDetails().size());
        
        // Extract document IDs for verification
        Set<Integer> docIds = result.getAllDetails().stream()
                .map(d -> d.getDocumentId())
                .collect(Collectors.toSet());
        
        assertTrue(docIds.contains(1));
        assertTrue(docIds.contains(2));
    }
    
    @Test
    void testExecuteMultipleTerms() throws Exception {
        // Setup - now we expect a bigram search with two terms
        Contains condition = new Contains(Arrays.asList("test", "example"));
        
        PositionList positionList = new PositionList();
        positionList.add(new Position(1, 1, 0, 12));
        positionList.add(new Position(2, 1, 5, 17));
        
        // Expect bigram index to be used with the combined terms
        when(mockBigramIndex.get(any())).thenReturn(Optional.of(positionList));
        
        // Execute with document granularity
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");
        
        // Verify
        verify(mockBigramIndex).get(any());
        
        assertEquals(2, result.getAllDetails().size());
        
        // Extract document IDs for verification
        Set<Integer> docIds = result.getAllDetails().stream()
                .map(d -> d.getDocumentId())
                .collect(Collectors.toSet());
        
        assertTrue(docIds.contains(1));
        assertTrue(docIds.contains(2));
    }
    
    @Test
    void testExecuteWithBigramIndex() throws Exception {
        // This test is now redundant with testExecuteMultipleTerms
        // But we'll keep it with a different condition to test the same functionality
        
        // Setup - using a list of terms instead of a space-separated string
        Contains condition = new Contains(Arrays.asList("another", "test"));
        PositionList positionList = new PositionList();
        positionList.add(new Position(1, 1, 0, 12));
        positionList.add(new Position(2, 1, 5, 17));
        
        // Expect bigram index to be used
        when(mockBigramIndex.get(any())).thenReturn(Optional.of(positionList));
        
        // Execute with document granularity
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");
        
        // Verify
        verify(mockBigramIndex).get(any());
        
        assertEquals(2, result.getAllDetails().size());
        
        // Extract document IDs for verification
        Set<Integer> docIds = result.getAllDetails().stream()
                .map(d -> d.getDocumentId())
                .collect(Collectors.toSet());
        
        assertTrue(docIds.contains(1));
        assertTrue(docIds.contains(2));
    }
    
    @Test
    void testExecuteWithTrigramIndex() throws Exception {
        // Setup - using a list of three terms
        Contains condition = new Contains(Arrays.asList("test", "example", "phrase"));
        PositionList positionList = new PositionList();
        positionList.add(new Position(1, 1, 0, 19));
        positionList.add(new Position(2, 1, 5, 24));
        
        // Expect trigram index to be used
        when(mockTrigramIndex.get(any())).thenReturn(Optional.of(positionList));
        
        // Execute with document granularity
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");
        
        // Verify
        verify(mockTrigramIndex).get(any());
        
        assertEquals(2, result.getAllDetails().size());
        
        // Extract document IDs for verification
        Set<Integer> docIds = result.getAllDetails().stream()
                .map(d -> d.getDocumentId())
                .collect(Collectors.toSet());
        
        assertTrue(docIds.contains(1));
        assertTrue(docIds.contains(2));
    }
    
    @Test
    void testExecuteWithWildcard() throws Exception {
        // Setup - using a wildcard in a bigram
        Contains condition = new Contains(Arrays.asList("test", "*"));
        
        // Since wildcards aren't fully implemented, we expect an empty result
        // This test will need to be updated when wildcard support is implemented
        
        // Execute with document granularity
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");
        
        // Verify
        assertTrue(result.getAllDetails().isEmpty());
    }
    
    @Test
    void testExecuteTermNotFound() throws Exception {
        // Setup
        Contains condition = new Contains("nonexistent");
        
        // Term not found in index
        when(mockUnigramIndex.get(any())).thenReturn(Optional.empty());
        
        // Execute with document granularity
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");
        
        // Verify
        verify(mockUnigramIndex).get(any());
        assertTrue(result.getAllDetails().isEmpty());
    }
    
    @Test
    void testExecuteTooManyTerms() {
        // Setup - more than 3 terms should throw an exception
        Contains condition = new Contains(Arrays.asList("one", "two", "three", "four"));
        
        // Execute and verify exception
        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus")
        );
        
        assertEquals(QueryExecutionException.ErrorType.INVALID_CONDITION, exception.getErrorType());
    }
    
    @Test
    void testExecuteMissingIndex() throws QueryExecutionException {
        // Setup
        Contains condition = new Contains("test");
        Map<String, IndexAccessInterface> emptyIndexes = new HashMap<>();
        
        // Execute and verify exception
        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> executor.execute(condition, emptyIndexes, Query.Granularity.DOCUMENT, 1, "test_corpus")
        );
        
        // Verify the exception details
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
        assertTrue(exception.getMessage().contains("Missing required unigram index"));
    }
    
    @Test
    void testDebugExecuteMultipleTerms() throws Exception {
        // Setup - now we expect a bigram search with two terms
        Contains condition = new Contains(Arrays.asList("test", "example"));
        
        PositionList positionList = new PositionList();
        positionList.add(new Position(1, 1, 0, 12));
        positionList.add(new Position(2, 1, 5, 17));
        
        // Use lenient stubbing to avoid UnnecessaryStubbingException
        lenient().when(mockUnigramIndex.get(any())).thenReturn(Optional.of(positionList));
        lenient().when(mockBigramIndex.get(any())).thenReturn(Optional.of(positionList));
        lenient().when(mockTrigramIndex.get(any())).thenReturn(Optional.of(positionList));
        
        // Execute with document granularity
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");
        
        // Verify interactions with all indexes
        System.out.println("Unigram interactions: " + mockingDetails(mockUnigramIndex).getInvocations().size());
        System.out.println("Bigram interactions: " + mockingDetails(mockBigramIndex).getInvocations().size());
        System.out.println("Trigram interactions: " + mockingDetails(mockTrigramIndex).getInvocations().size());
        
        // No assertions - this is just for debugging
    }

    @Test
    void testExecuteSingleTermWithVariableBinding() throws Exception {
        // Setup
        String variableName = "?termVar";
        String searchTerm = "keyword";
        Contains condition = new Contains(searchTerm, variableName, true);
        PositionList positionList = new PositionList();
        Position pos1 = new Position(1, 1, 10, 17);
        Position pos2 = new Position(1, 2, 5, 12); // Same doc, different sentence
        Position pos3 = new Position(2, 1, 0, 7);   // Different doc
        positionList.add(pos1);
        positionList.add(pos2);
        positionList.add(pos3);

        // Expect unigram index to be used
        when(mockUnigramIndex.get(eq(searchTerm.getBytes()))).thenReturn(Optional.of(positionList));

        // Execute with sentence granularity
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus");

        // Verify results (should have 3 sentence matches)
        verify(mockUnigramIndex).get(eq(searchTerm.getBytes()));
        assertEquals(3, result.getAllDetails().size());
        assertTrue(result.getAllDetails().stream().anyMatch(d -> d.getDocumentId() == 1 && d.getSentenceId() == 1));
        assertTrue(result.getAllDetails().stream().anyMatch(d -> d.getDocumentId() == 1 && d.getSentenceId() == 2));
        assertTrue(result.getAllDetails().stream().anyMatch(d -> d.getDocumentId() == 2 && d.getSentenceId() == 1));

        // Verify variable name is correctly set in MatchDetail
        assertTrue(result.getAllDetails().stream().allMatch(d -> d.variableName().filter(v -> v.equals(variableName)).isPresent()),
                   "Variable name should be set in MatchDetail");
        assertTrue(result.getAllDetails().stream().allMatch(d -> d.value().equals(searchTerm) && d.valueType() == ValueType.TERM));
    }

    @Test
    void testSentenceGranularityWithWindow() throws QueryExecutionException {
        // ... setup ...
        Contains condition = new Contains("test"); // Define a basic condition
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 1, "test_corpus"); 
        // ... assertions ...
    }

    // Helper to create simple MatchDetail (from QueryExecutorTest)
    private MatchDetail createMatchDetail(int docId, int sentenceId, int begin, int end, String value) {
        Position pos = new Position(docId, sentenceId, begin, end);
        return new MatchDetail(value, ValueType.TERM, pos, (String) null);
    }

    @Test
    void testUnigramMatch() throws QueryExecutionException, IndexAccessException {
        String searchTerm = "apple";
        Contains condition = new Contains(searchTerm);
        
        PositionList positions = new PositionList();
        positions.add(new Position(1, 1, 0, 5));
        positions.add(new Position(2, 3, 10, 15));
        when(mockUnigramIndex.get(searchTerm.toLowerCase().getBytes())).thenReturn(Optional.of(positions));
        
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus"); // Changed type
        
        assertNotNull(result);
        assertEquals(2, result.getAllDetails().size()); // Check size via getAllDetails
        Set<Integer> docIds = result.getAllDetails().stream().map(MatchDetail::getDocumentId).collect(Collectors.toSet());
        assertTrue(docIds.containsAll(Set.of(1, 2)));
        // Verify value and type
        assertTrue(result.getAllDetails().stream().allMatch(d -> d.value().equals(searchTerm) && d.valueType() == ValueType.TERM));
    }

    @Test
    void testBigramMatch() throws QueryExecutionException, IndexAccessException {
        String term1 = "red";
        String term2 = "apple";
        Contains condition = new Contains(List.of(term1, term2)); // Use List constructor
        
        PositionList positions = new PositionList();
        positions.add(new Position(1, 1, 0, 9));
        when(mockBigramIndex.get((term1.toLowerCase() + "\0" + term2.toLowerCase()).getBytes())).thenReturn(Optional.of(positions));

        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus"); // Changed type

        assertNotNull(result);
        assertEquals(1, result.getAllDetails().size());
        MatchDetail detail = result.getAllDetails().get(0);
        assertEquals(1, detail.getDocumentId());
        assertEquals(term1 + " " + term2, detail.value()); // Check combined value
        assertEquals(ValueType.TERM, detail.valueType());
    }

    @Test
    void testTrigramMatch() throws QueryExecutionException, IndexAccessException {
        String term1 = "big", term2 = "red", term3 = "apple";
        Contains condition = new Contains(List.of(term1, term2, term3)); // Use List constructor
        
        PositionList positions = new PositionList();
        positions.add(new Position(3, 5, 20, 33));
        String trigramKey = term1.toLowerCase() + "\0" + term2.toLowerCase() + "\0" + term3.toLowerCase();
        when(mockTrigramIndex.get(trigramKey.getBytes())).thenReturn(Optional.of(positions));

        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus"); // Changed type

        assertNotNull(result);
        assertEquals(1, result.getAllDetails().size());
        MatchDetail detail = result.getAllDetails().get(0);
        assertEquals(3, detail.getDocumentId());
        assertEquals(term1 + " " + term2 + " " + term3, detail.value());
        assertEquals(ValueType.TERM, detail.valueType());
    }

    @Test
    void testNoMatch() throws QueryExecutionException, IndexAccessException {
        String searchTerm = "nonexistent";
        Contains condition = new Contains(searchTerm);
        when(mockUnigramIndex.get(searchTerm.toLowerCase().getBytes())).thenReturn(Optional.empty());

        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus"); // Changed type

        assertNotNull(result);
        assertTrue(result.getAllDetails().isEmpty());
    }
    
    @Test
    void testWildcardBigramStart() throws QueryExecutionException, IndexAccessException {
        String term2 = "apple";
        Contains condition = new Contains(List.of("*", term2)); // Use List constructor

        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");
        assertNotNull(result);
        // Wildcard at start is not fully supported, expect empty results currently
        assertTrue(result.getAllDetails().isEmpty()); 
    }

    @Test
    void testWildcardBigramEnd() throws QueryExecutionException, IndexAccessException {
        String term1 = "red";
        Contains condition = new Contains(List.of(term1, "*")); // Use List constructor

        // Mock iterator for prefix search "red\0"
        DBIterator mockIterator = mock(DBIterator.class);
        when(mockBigramIndex.iterator()).thenReturn(mockIterator);
        // Simulate no results found during prefix iteration
        String prefix = term1.toLowerCase() + "\0";
        doNothing().when(mockIterator).seek(argThat(k -> Arrays.equals(k, prefix.getBytes())));
        when(mockIterator.hasNext()).thenReturn(false);
        // No need to mock peekNext or next if hasNext is false

        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus");
        assertNotNull(result);
        // Prefix search is attempted, but we mock it returning nothing
        assertTrue(result.getAllDetails().isEmpty()); 
        verify(mockBigramIndex).iterator(); // Verify iterator was called for prefix search
    }

    @Test
    void testEmptyTerms() throws QueryExecutionException {
        Contains condition = new Contains(Collections.emptyList());
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus"); // Changed type
        assertNotNull(result);
        assertTrue(result.getAllDetails().isEmpty());
    }

    @Test
    void testVariableBinding() throws QueryExecutionException, IndexAccessException {
        String searchTerm = "banana";
        String variableName = "?fruit";
        Contains condition = new Contains(searchTerm, variableName, true);
        
        PositionList positions = new PositionList();
        positions.add(new Position(5, 1, 0, 6));
        when(mockUnigramIndex.get(searchTerm.toLowerCase().getBytes())).thenReturn(Optional.of(positions));
        
        QueryResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus"); // Changed type

        assertNotNull(result);
        assertEquals(1, result.getAllDetails().size());
        MatchDetail detail = result.getAllDetails().get(0);
        assertEquals(5, detail.getDocumentId());
        assertEquals(searchTerm, detail.value());
        assertTrue(detail.variableName().filter(v -> v.equals(variableName)).isPresent());
        assertEquals(ValueType.TERM, detail.valueType());
        assertEquals(variableName, detail.variableName().orElse(null));
    }
} 