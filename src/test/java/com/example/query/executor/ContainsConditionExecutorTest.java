package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rocksdb.RocksIterator;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Contains;

@ExtendWith(MockitoExtension.class)
public class ContainsConditionExecutorTest {

    @Mock private IndexAccess mockUnigramIndex;
    @Mock private IndexAccess mockBigramIndex;
    @Mock private IndexAccess mockTrigramIndex;
    @Mock private RocksIterator unigramIterator;
    @Mock private RocksIterator bigramIterator;
    @Mock private RocksIterator trigramIterator;

    private ContainsExecutor executor;
    private Map<String, IndexAccessInterface> indexes;
    private AttributeRequirements defaultTestRequirements;

    // Helper to convert PositionListSoA to byte[] for mocking getRaw()
    private byte[] soaToBlob(PositionListSoA soa) throws IOException {
        if (soa == null) return null;
        return soa.serializeToCompositeBlob();
    }

    @BeforeEach
    void setUp() throws IndexAccessException {
        indexes = Map.of("unigram", mockUnigramIndex, "bigram", mockBigramIndex, "trigram", mockTrigramIndex);
        // The following lines are no longer needed as .iterator() is removed
        // lenient().when(mockUnigramIndex.iterator()).thenReturn(mock(DBIterator.class));
        // lenient().when(mockBigramIndex.iterator()).thenReturn(mock(DBIterator.class));
        // lenient().when(mockTrigramIndex.iterator()).thenReturn(mock(DBIterator.class));

        executor = new ContainsExecutor();
        defaultTestRequirements = new AttributeRequirements();
        // Configure default requirements as needed for most tests, e.g.:
        defaultTestRequirements.needsDocumentId = true;
        defaultTestRequirements.needsSentenceId = true;
        defaultTestRequirements.needsPositions = true;
        defaultTestRequirements.needsConceptualRowIds = true;
    }

    @Test
    void testExecuteSingleTerm() throws Exception {
        Contains condition = new Contains("test");
        PositionListSoA positionList = new PositionListSoA();
        positionList.add(new Position(1, 1, 0, 4));
        positionList.add(new Position(2, 1, 5, 9));
        byte[] keyBytes = "test".toLowerCase().getBytes();

        when(mockUnigramIndex.getRaw(eq(keyBytes))).thenReturn(Optional.of(soaToBlob(positionList)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        verify(mockUnigramIndex).getRaw(eq(keyBytes));
        assertEquals(2, result.size());

        Set<Integer> docIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            docIds.add(result.getDocumentIdAt(i));
        }
        assertTrue(docIds.contains(1));
        assertTrue(docIds.contains(2));
    }

    @Test
    void testExecuteMultipleTerms() throws Exception {
        Contains condition = new Contains(Arrays.asList("test", "example"));
        PositionListSoA positionList = new PositionListSoA();
        positionList.add(new Position(1, 1, 0, 12));
        positionList.add(new Position(2, 1, 5, 17));
        byte[] keyBytes = ("test" + IndexAccessInterface.DELIMITER + "example").toLowerCase().getBytes();

        when(mockBigramIndex.getRaw(eq(keyBytes))).thenReturn(Optional.of(soaToBlob(positionList)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        verify(mockBigramIndex).getRaw(eq(keyBytes));
        assertEquals(2, result.size());

        Set<Integer> docIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            docIds.add(result.getDocumentIdAt(i));
        }
        assertTrue(docIds.contains(1));
        assertTrue(docIds.contains(2));
    }

    @Test
    void testExecuteWithBigramIndex() throws Exception {
        Contains condition = new Contains(Arrays.asList("another", "test"));
        PositionListSoA positionList = new PositionListSoA();
        positionList.add(new Position(1, 1, 0, 12));
        positionList.add(new Position(2, 1, 5, 17));
        byte[] keyBytes = ("another" + IndexAccessInterface.DELIMITER + "test").toLowerCase().getBytes();

        when(mockBigramIndex.getRaw(eq(keyBytes))).thenReturn(Optional.of(soaToBlob(positionList)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        verify(mockBigramIndex).getRaw(eq(keyBytes));
        assertEquals(2, result.size());

        Set<Integer> docIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            docIds.add(result.getDocumentIdAt(i));
        }
        assertTrue(docIds.contains(1));
        assertTrue(docIds.contains(2));
    }

    @Test
    void testExecuteWithTrigramIndex() throws Exception {
        Contains condition = new Contains(Arrays.asList("test", "example", "phrase"));
        PositionListSoA positionList = new PositionListSoA();
        positionList.add(new Position(1, 1, 0, 19));
        positionList.add(new Position(2, 1, 5, 24));
        byte[] keyBytes = ("test" + IndexAccessInterface.DELIMITER + "example" + IndexAccessInterface.DELIMITER + "phrase").toLowerCase().getBytes();

        when(mockTrigramIndex.getRaw(eq(keyBytes))).thenReturn(Optional.of(soaToBlob(positionList)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        verify(mockTrigramIndex).getRaw(eq(keyBytes));
        assertEquals(2, result.size());

        Set<Integer> docIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            docIds.add(result.getDocumentIdAt(i));
        }
        assertTrue(docIds.contains(1));
        assertTrue(docIds.contains(2));
    }

    // @Test
    // void testExecuteWithWildcard() throws Exception {
    //     // Wildcard searches in ContainsExecutor fall back to executePatternSearch, which might use iterator or get().
    //     // For now, this test assumes wildcard leads to an empty result as per current ContainsExecutor logic for unsupported wildcards.
    //     // If wildcard handling improves, this test will need adjustment.
    //     Contains condition = new Contains(Arrays.asList("test", "*"));
    //     // Mocking the prefix search via iterator
    //     String prefix = "test" + IndexAccessInterface.DELIMITER;
    //     byte[] prefixBytes = prefix.toLowerCase().getBytes();
    //     // lenient().when(mockBigramIndex.iterator()).thenReturn(bigramIterator); // Old way
    //     lenient().when(mockBigramIndex.seek(eq(prefixBytes))).thenReturn(bigramIterator); // New way
    //     // doNothing().when(bigramIterator).seek(eq(prefixBytes)); // No longer needed, seek is on mockBigramIndex
    //     lenient().when(bigramIterator.hasNext()).thenReturn(false); // No matches for this prefix for simplicity

    //     QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
    //     assertTrue(result.isEmpty());
    // }

    @Test
    void testExecuteTermNotFound() throws Exception {
        Contains condition = new Contains("nonexistent");
        byte[] keyBytes = "nonexistent".toLowerCase().getBytes();
        when(mockUnigramIndex.getRaw(eq(keyBytes))).thenReturn(Optional.empty());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        verify(mockUnigramIndex).getRaw(eq(keyBytes));
        assertTrue(result.isEmpty());
    }

    @Test
    void testExecuteMissingIndex() throws QueryExecutionException {
        Contains condition = new Contains("test");
        Map<String, IndexAccessInterface> emptyIndexes = new HashMap<>();
        QueryExecutionException exception = assertThrows(
            QueryExecutionException.class,
            () -> executor.execute(condition, emptyIndexes, Query.Granularity.DOCUMENT, 1, "test_corpus", defaultTestRequirements)
        );
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
        assertTrue(exception.getMessage().contains("Required unigram index not found"));
    }

    @Test
    void testExecuteSingleTermWithVariableBinding() throws Exception {
        Contains condition = new Contains(Collections.singletonList("test"), "myVar", true);
        PositionListSoA positionList = new PositionListSoA();
        positionList.add(new Position(1, 1, 0, 4));
        byte[] keyBytes = "test".toLowerCase().getBytes();

        when(mockUnigramIndex.getRaw(eq(keyBytes))).thenReturn(Optional.of(soaToBlob(positionList)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus", defaultTestRequirements);

        assertEquals(1, result.size());
        assertEquals("test", result.getValueAt(0));
        assertEquals(ValueType.TERM, result.getValueTypeAt(0));
        assertEquals("myVar", result.getVariableNameAt(0));
        assertEquals(1, result.getDocumentIdAt(0));
        assertEquals(1, result.getSentenceIdAt(0));
    }

    @Test
    void testExecuteMultipleTermsWithVariableBinding() throws Exception {
        List<String> terms = Arrays.asList("hello", "world");
        Contains condition = new Contains(terms, "phraseVar", true);
        PositionListSoA positionList = new PositionListSoA();
        positionList.add(new Position(1, 1, 10, 20));
        byte[] keyBytes = ("hello" + IndexAccessInterface.DELIMITER + "world").toLowerCase().getBytes();

        when(mockBigramIndex.getRaw(eq(keyBytes))).thenReturn(Optional.of(soaToBlob(positionList)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertEquals(1, result.size());
        assertEquals("hello world", result.getValueAt(0)); // Value is space-separated
        assertEquals(ValueType.TERM, result.getValueTypeAt(0));
        assertEquals("phraseVar", result.getVariableNameAt(0));
        assertEquals(1, result.getDocumentIdAt(0));
    }

    @Test
    void testSentenceGranularityWithWindow() throws Exception {
        Contains condition = new Contains("test");
        PositionListSoA positionList = new PositionListSoA();
        positionList.add(new Position(1, 0, 0, 4)); // sentence 0
        positionList.add(new Position(1, 1, 5, 9)); // sentence 1
        positionList.add(new Position(1, 3, 10, 14)); // sentence 3, outside window of s=1, w=1
        byte[] keyBytes = "test".toLowerCase().getBytes();

        when(mockUnigramIndex.getRaw(eq(keyBytes))).thenReturn(Optional.of(soaToBlob(positionList)));

        // Granularity SENTENCE, window size 1.
        // This test focuses on the ContainsExecutor returning all sentence matches;
        // windowing is applied later by QueryExecutor/JoinHandler if applicable.
        // For ContainsExecutor, granularity and window size mainly affect QueryResultSoA metadata.
        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 1, "test_corpus", defaultTestRequirements);

        assertEquals(3, result.size()); // Expect all 3 matches from the PositionListSoA
        assertEquals(Query.Granularity.SENTENCE, result.getGranularity());
        assertEquals(1, result.getGranularitySize());

        // Verify details (example for first match)
        assertEquals(1, result.getDocumentIdAt(0));
        assertEquals(0, result.getSentenceIdAt(0));
        assertEquals("test", result.getValueAt(0));
    }

    @Test
    void testUnigramMatch() throws QueryExecutionException, IndexAccessException, IOException {
        String searchTerm = "unique";
        Contains condition = new Contains(searchTerm);
        byte[] keyBytes = searchTerm.toLowerCase().getBytes();

        PositionListSoA positions = new PositionListSoA();
        positions.add(new Position(1,1,0,5));
        positions.add(new Position(2,1,10,15));
        when(mockUnigramIndex.getRaw(eq(keyBytes))).thenReturn(Optional.of(soaToBlob(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(2, result.size());
        Set<Integer> docIds = new HashSet<>();
        for(int i=0; i<result.size(); i++) docIds.add(result.getDocumentIdAt(i));
        assertTrue(docIds.containsAll(Set.of(1,2)));
    }

    @Test
    void testBigramMatch() throws QueryExecutionException, IndexAccessException, IOException {
        String term1 = "two"; String term2 = "terms";
        Contains condition = new Contains(Arrays.asList(term1, term2));
        byte[] keyBytes = (term1 + IndexAccessInterface.DELIMITER + term2).toLowerCase().getBytes();

        PositionListSoA positions = new PositionListSoA();
        positions.add(new Position(3,1,0,8));
        when(mockBigramIndex.getRaw(eq(keyBytes))).thenReturn(Optional.of(soaToBlob(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(3, result.getDocumentIdAt(0));
        assertEquals(term1+" "+term2, result.getValueAt(0));
    }

    @Test
    void testTrigramMatch() throws QueryExecutionException, IndexAccessException, IOException {
        String t1="three", t2="separate", t3="terms";
        Contains condition = new Contains(Arrays.asList(t1,t2,t3));
        byte[] keyBytes = (t1+IndexAccessInterface.DELIMITER+t2+IndexAccessInterface.DELIMITER+t3).toLowerCase().getBytes();

        PositionListSoA positions = new PositionListSoA();
        positions.add(new Position(4,1,0,15));
        when(mockTrigramIndex.getRaw(eq(keyBytes))).thenReturn(Optional.of(soaToBlob(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(4, result.getDocumentIdAt(0));
        assertEquals(t1+" "+t2+" "+t3, result.getValueAt(0));
    }

    @Test
    void testNoMatch() throws QueryExecutionException, IndexAccessException {
        Contains condition = new Contains("nonexistent");
        byte[] keyBytes = "nonexistent".toLowerCase().getBytes();
        lenient().when(mockUnigramIndex.getRaw(keyBytes)).thenReturn(Optional.empty());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        assertTrue(result.isEmpty());
        verify(mockUnigramIndex).getRaw(keyBytes);
    }

    @Test
    void testEmptyTerms() throws QueryExecutionException {
        Contains condition = new Contains(Collections.emptyList());
        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        assertTrue(result.isEmpty());
        assertEquals(0, result.size());
    }

    @Test
    void testVariableBinding() throws QueryExecutionException, IndexAccessException, IOException {
        String term = "bindme";
        Contains condition = new Contains(Collections.singletonList(term), "varX", true);
        byte[] keyBytes = term.toLowerCase().getBytes();

        PositionListSoA positions = new PositionListSoA();
        positions.add(new Position(5,1,2,7));
        when(mockUnigramIndex.getRaw(eq(keyBytes))).thenReturn(Optional.of(soaToBlob(positions)));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(5, result.getDocumentIdAt(0));
        assertEquals(term, result.getValueAt(0)); // For variable binding, value is the matched term
        assertEquals("varX", result.getVariableNameAt(0));
    }
}