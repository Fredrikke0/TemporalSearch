package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.mockito.stubbing.OngoingStubbing;
import org.rocksdb.RocksIterator;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Pos;

@ExtendWith(MockitoExtension.class)
public class PosExecutorTest {

    @Mock private IndexAccessInterface mockPosIndex;
    @Mock private RocksIterator mockIterator;

    private PosExecutor executor;
    private Map<String, IndexAccessInterface> indexes;
    private AttributeRequirements defaultTestRequirements;
    private static final String POS_INDEX_NAME = "pos";

    @BeforeEach
    void setUp() {
        executor = new PosExecutor();
        indexes = Map.of(POS_INDEX_NAME, mockPosIndex);
        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsConceptualRowIds = true;
        defaultTestRequirements.needsDocumentId = true;
        defaultTestRequirements.needsSentenceId = true;
        defaultTestRequirements.needsPositions = true;

        try {
            lenient().when(mockIterator.isValid()).thenReturn(false);

            lenient().when(mockPosIndex.getRaw(any(byte[].class))).thenReturn(Optional.empty());
            lenient().when(mockPosIndex.seek(any(byte[].class))).thenReturn(mockIterator);
            lenient().when(mockPosIndex.iterateFromFirst()).thenReturn(mockIterator);
        } catch (IndexAccessException e) {
            fail("Setup failed for mockPosIndex: " + e.getMessage());
        }
    }

    private void setupSpecificKeyMock(String tag, String term, PositionListSoA positions) throws IndexAccessException, IOException {
        String key = tag.toUpperCase() + String.valueOf(IndexAccessInterface.DELIMITER) + term.toLowerCase();
        lenient().when(mockPosIndex.getRaw(eq(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                 .thenReturn(Optional.of(positions.serializeToCompositeBlob()));
    }

    private void setupIteratorMock(String tagPrefix, List<Map.Entry<String, PositionListSoA>> termEntries) throws IndexAccessException, IOException {
        String fullPrefix = tagPrefix.toUpperCase() + String.valueOf(IndexAccessInterface.DELIMITER);
        byte[] prefixBytes = fullPrefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        List<Map.Entry<byte[], byte[]>> mockDbEntries = new ArrayList<>();
        for (Map.Entry<String, PositionListSoA> termEntry : termEntries) {
            String fullKey = fullPrefix + termEntry.getKey().toLowerCase();
            mockDbEntries.add(new java.util.AbstractMap.SimpleEntry<>(fullKey.getBytes(), termEntry.getValue().serializeToCompositeBlob()));
                }

        lenient().when(mockPosIndex.seek(argThat(k -> Arrays.equals(k, prefixBytes)))).thenReturn(mockIterator);

        if (mockDbEntries.isEmpty()) {
            when(mockIterator.isValid()).thenReturn(false);
            lenient().when(mockIterator.key()).thenThrow(new java.util.NoSuchElementException());
        } else {
            Boolean[] hasNextBooleans = new Boolean[mockDbEntries.size() + 1];
            Arrays.fill(hasNextBooleans, true);
            hasNextBooleans[mockDbEntries.size()] = false;
            OngoingStubbing<Boolean> hasNextStub = when(mockIterator.isValid());
            for (Boolean b : hasNextBooleans) {
                hasNextStub = hasNextStub.thenReturn(b);
            }

            OngoingStubbing<byte[]> nextStub = when(mockIterator.key());
            for (Map.Entry<byte[], byte[]> dbEntry : mockDbEntries) {
                nextStub = nextStub.thenReturn(dbEntry.getKey());
            }
            when(mockIterator.value()).thenReturn(mockDbEntries.get(0).getValue());
            nextStub.thenThrow(new java.util.NoSuchElementException());
        }
    }

    @Test
    void testExecuteSpecificSearch() throws QueryExecutionException, IndexAccessException, IOException {
        Pos condition = new Pos("NN", "cat", null, false);
        PositionListSoA positions = new PositionListSoA();
        positions.add(new Position(1, 1, 0, 3));
        positions.add(new Position(2, 1, 5, 8));
        setupSpecificKeyMock("NN", "cat", positions);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("cat", result.getValueAt(0));
        assertEquals(ValueType.POS_TERM, result.getValueTypeAt(0));
        assertNull(result.getVariableNameAt(0));
        assertEquals(1, result.getDocumentIdAt(0));
        assertEquals(1, result.getSentenceIdAt(0));
        assertEquals(0, result.getBeginCharAt(0));
        assertEquals(3, result.getEndCharAt(0));

        assertEquals("cat", result.getValueAt(1));
        assertEquals(2, result.getDocumentIdAt(1));
        assertEquals(1, result.getSentenceIdAt(1));
        assertEquals(5, result.getBeginCharAt(1));
        assertEquals(8, result.getEndCharAt(1));

        String expectedKey = "NN" + String.valueOf(IndexAccessInterface.DELIMITER) + "cat";
        verify(mockPosIndex).getRaw(eq(expectedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void testExecuteWildcardSearch_allPosTags() throws IndexAccessException, QueryExecutionException {
        Pos condition = new Pos("*", null, null, false);

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        });
        assertEquals(QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION, exception.getErrorType());
        assertTrue(exception.getMessage().contains("Wildcard POS tag (*) is not supported"));
    }

    @Test
    void testExecuteSpecificSearch_withVariable() throws QueryExecutionException, IndexAccessException, IOException {
        Pos condition = new Pos("JJ", "happy", "?adj", true);
        PositionListSoA positions = new PositionListSoA();
        positions.add(new Position(3, 1, 10, 15));
        setupSpecificKeyMock("JJ", "happy", positions);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("happy", result.getValueAt(0));
        assertEquals(ValueType.POS_TERM, result.getValueTypeAt(0));
        assertEquals("?adj", result.getVariableNameAt(0));
        assertEquals(3, result.getDocumentIdAt(0));
        assertEquals(1, result.getSentenceIdAt(0));
        assertEquals(10, result.getBeginCharAt(0));
        assertEquals(15, result.getEndCharAt(0));

        String expectedKey = "JJ" + String.valueOf(IndexAccessInterface.DELIMITER) + "happy";
        verify(mockPosIndex).getRaw(eq(expectedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void testExecute_noMatchFound_specificTerm() throws QueryExecutionException, IndexAccessException {
        Pos condition = new Pos("XYZ", "term", null, false);
        String expectedKey = "XYZ" + String.valueOf(IndexAccessInterface.DELIMITER) + "term";
        when(mockPosIndex.getRaw(eq(expectedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).thenReturn(Optional.empty());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(mockPosIndex).getRaw(eq(expectedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void testExecute_noMatchFound_iterator() throws QueryExecutionException, IndexAccessException, IOException {
        Pos condition = new Pos("ABC", null, "?someVar", true);
        setupIteratorMock("ABC", Collections.emptyList());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        assertNotNull(result);
        assertTrue(result.isEmpty());

        String expectedPrefix = "ABC" + String.valueOf(IndexAccessInterface.DELIMITER);
        verify(mockPosIndex).seek(eq(expectedPrefix.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void testExecuteVariableSearch_NoSpecificTerm() throws QueryExecutionException, IndexAccessException, IOException {
        Pos condition = new Pos("VB", null, "?verb", true);

        PositionListSoA posRun = new PositionListSoA(); posRun.add(new Position(1,1,0,3));
        PositionListSoA posEat = new PositionListSoA(); posEat.add(new Position(1,2,5,8));
        PositionListSoA posSing = new PositionListSoA(); posSing.add(new Position(2,1,0,4));

        List<Map.Entry<String, PositionListSoA>> terms = new ArrayList<>();
        terms.add(Map.entry("run", posRun));
        terms.add(Map.entry("eat", posEat));
        terms.add(Map.entry("sing", posSing));
        setupIteratorMock("VB", terms);

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(3, result.size());

        Set<String> foundVerbs = new HashSet<>();
        for(int i=0; i<result.size(); i++){
            assertEquals("?verb", result.getVariableNameAt(i));
            assertEquals(ValueType.POS_TERM, result.getValueTypeAt(i));
            foundVerbs.add((String)result.getValueAt(i));
        }
        assertEquals(Set.of("run", "eat", "sing"), foundVerbs);

        String expectedPrefix = "VB" + String.valueOf(IndexAccessInterface.DELIMITER);
        verify(mockPosIndex).seek(eq(expectedPrefix.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        verify(mockIterator, times(terms.size() + 1)).isValid();
        verify(mockIterator, times(terms.size())).key();
    }

    @Test
    void testExecute_missingPosIndex() {
        Pos condition = new Pos("NN", "noun", null, false);
        Map<String, IndexAccessInterface> incompleteIndexes = Collections.emptyMap();

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, incompleteIndexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);
        });
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
    }
}