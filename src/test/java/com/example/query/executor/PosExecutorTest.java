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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rocksdb.RocksIterator;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Pos;

@ExtendWith(MockitoExtension.class)
public class PosExecutorTest {

    @Mock private IndexAccessInterface mockPosIndex;
    @Mock private RocksIterator mockIterator;
    @Mock private SynonymManager mockSynonymManager;

    private PosExecutor executor;
    private Map<String, IndexAccessInterface> indexes;
    private AttributeRequirements defaultTestRequirements;
    private static final String POS_INDEX_NAME = "pos";

    @BeforeEach
    void setUp() {
        executor = new PosExecutor(mockSynonymManager);
        indexes = Map.of(POS_INDEX_NAME, mockPosIndex);
        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsConceptualRowIds = true;
        defaultTestRequirements.needsDocumentId = true;
        defaultTestRequirements.needsSentenceId = true;
        defaultTestRequirements.needsPositions = true;

        try {
            // Default lenient stubs for the global mockIterator
            lenient().when(mockIterator.isValid()).thenReturn(false);
            // lenient().when(mockIterator.key()).thenThrow(new IllegalStateException("Default key access on unconfigured iterator")); // Keep removed
            // lenient().when(mockIterator.value()).thenThrow(new IllegalStateException("Default value access on unconfigured iterator")); // Keep removed
            lenient().doNothing().when(mockIterator).next(); // Corrected: Use doNothing() for void methods
            lenient().doNothing().when(mockIterator).seekToFirst(); // Corrected: Use doNothing() for void methods
            lenient().doNothing().when(mockIterator).seek(any(byte[].class)); // Corrected: Use doNothing() for void methods

            // Lenient stubs for mockPosIndex
            lenient().when(mockPosIndex.getRaw(any(byte[].class))).thenReturn(Optional.empty());
            lenient().when(mockPosIndex.iterateFromFirst()).thenReturn(mockIterator);
            lenient().when(mockPosIndex.seek(any(byte[].class))).thenReturn(mockIterator); // Ensure seek on index also returns the mockIterator
        } catch (IndexAccessException e) {
            fail("Setup failed for mockPosIndex: " + e.getMessage());
        }
    }

    private void setupSpecificKeyMock(String tag, String term, PositionListSoA positions) throws IndexAccessException, IOException {
        String key = tag.toUpperCase() + String.valueOf(IndexAccessInterface.DELIMITER) + term.toLowerCase();
        // This can remain lenient or be made strict depending on test needs, lenient is safer for general purpose mock
        lenient().when(mockPosIndex.getRaw(eq(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                 .thenReturn(Optional.of(positions.serializeToCompositeBlob()));
    }

    private void configureRocksIteratorMock(RocksIterator iterator, final List<Map.Entry<byte[], byte[]>> entries) {
        final AtomicInteger currentIndex = new AtomicInteger(-1);

        when(iterator.isValid()).thenAnswer(inv -> { // NO lenient() for isValid, as it's often critical for test logic
            int i = currentIndex.get();
            return i >= 0 && i < entries.size();
        });

        lenient().when(iterator.key()).thenAnswer(inv -> { // Made lenient
            int i = currentIndex.get();
            if (i >= 0 && i < entries.size()) {
                return entries.get(i).getKey();
            }
            throw new IllegalStateException("Iterator not valid or out of bounds for key(). Current index: " + i + ", Size: " + entries.size());
        });

        lenient().when(iterator.value()).thenAnswer(inv -> { // Made lenient
            int i = currentIndex.get();
            if (i >= 0 && i < entries.size()) {
                return entries.get(i).getValue();
            }
            throw new IllegalStateException("Iterator not valid or out of bounds for value(). Current index: " + i + ", Size: " + entries.size());
        });

        lenient().doAnswer(inv -> { // Made lenient
            int i = currentIndex.get();
            if (i >= 0 && i < entries.size()) {
                currentIndex.incrementAndGet();
            }
            return null;
        }).when(iterator).next();

        lenient().doAnswer(inv -> { // Made lenient
            if (entries.isEmpty()) {
                currentIndex.set(-1); // Set to -1 if empty, so isValid() returns false
            } else {
                currentIndex.set(0);
            }
            return null;
        }).when(iterator).seekToFirst();

        lenient().doAnswer(inv -> { // Made lenient
            byte[] targetKey = inv.getArgument(0);
            currentIndex.set(entries.size()); // Default to end (invalid position)
            for (int i = 0; i < entries.size(); i++) {
                int cmp = compareByteArrays(entries.get(i).getKey(), targetKey);
                if (cmp >= 0) {
                    currentIndex.set(i);
                    break;
                }
            }
            return null;
        }).when(iterator).seek(any(byte[].class));
    }

    private int compareByteArrays(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return a.length - b.length;
    }

    private void setupIteratorMock(String tagPrefix, List<Map.Entry<String, PositionListSoA>> termEntries) throws IndexAccessException, IOException {
        String fullPrefix = tagPrefix.toUpperCase() + String.valueOf(IndexAccessInterface.DELIMITER);
        byte[] prefixBytes = fullPrefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        List<Map.Entry<byte[], byte[]>> mockDbEntries = new ArrayList<>();
        for (Map.Entry<String, PositionListSoA> termEntry : termEntries) {
            String fullKey = fullPrefix + termEntry.getKey().toLowerCase();
            byte[] valueBytes = termEntry.getValue() != null ? termEntry.getValue().serializeToCompositeBlob() : new byte[0];
            mockDbEntries.add(new java.util.AbstractMap.SimpleEntry<>(fullKey.getBytes(), valueBytes));
        }

        configureRocksIteratorMock(mockIterator, mockDbEntries);

        when(mockPosIndex.seek(argThat(k -> Arrays.equals(k, prefixBytes)))).thenAnswer(invocation -> {
            mockIterator.seek(prefixBytes);
            return mockIterator;
        });

        lenient().when(mockPosIndex.iterateFromFirst()).thenAnswer(invocation -> { // Made lenient
            mockIterator.seekToFirst();
            return mockIterator;
        });
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

    // @Test // Commented out as per user request
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
        String tagPrefix = "VB";
        String fullPrefix = tagPrefix.toUpperCase() + String.valueOf(IndexAccessInterface.DELIMITER);
        byte[] prefixBytes = fullPrefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        PositionListSoA positions1 = new PositionListSoA(); positions1.add(new Position(1, 1, 0, 5));
        PositionListSoA positions2 = new PositionListSoA(); positions2.add(new Position(1, 2, 10, 15));
        PositionListSoA positions3 = new PositionListSoA(); positions3.add(new Position(2, 1, 20, 25));

        RocksIterator specificTestIterator = org.mockito.Mockito.mock(RocksIterator.class, "specificTestIteratorFor_testExecuteVariableSearch_NoSpecificTerm");
        // Crucially, ensure this more specific stubbing with eq() overrides the lenient any() in setUp.
        when(mockPosIndex.seek(eq(prefixBytes))).thenReturn(specificTestIterator);

        final List<Map.Entry<byte[], byte[]>> entries = List.of(
            Map.entry((fullPrefix + "run").getBytes(java.nio.charset.StandardCharsets.UTF_8), positions1.serializeToCompositeBlob()),
            Map.entry((fullPrefix + "jump").getBytes(java.nio.charset.StandardCharsets.UTF_8), positions2.serializeToCompositeBlob()),
            Map.entry((fullPrefix + "swim").getBytes(java.nio.charset.StandardCharsets.UTF_8), positions3.serializeToCompositeBlob())
        );

        final AtomicInteger positionTracker = new AtomicInteger(0); // Start at 0, valid indices are 0, 1, 2
        final int totalItems = entries.size();

        // Mocking a stateful iterator
        // isValid() is true if current position is < totalItems. PosExecutor calls this 6 times for 3 items.
        when(specificTestIterator.isValid()).thenAnswer(inv -> positionTracker.get() < totalItems);

        // key() and value() return data at current positionTracker. Called 3 times each.
        when(specificTestIterator.key()).thenAnswer(inv -> {
            if (positionTracker.get() >= totalItems) throw new IllegalStateException("key() called out of bounds. Index: " + positionTracker.get() + ", Total: " + totalItems);
            return entries.get(positionTracker.get()).getKey();
        });
        when(specificTestIterator.value()).thenAnswer(inv -> {
            if (positionTracker.get() >= totalItems) throw new IllegalStateException("value() called out of bounds. Index: " + positionTracker.get() + ", Total: " + totalItems);
            return entries.get(positionTracker.get()).getValue();
        });

        // next() advances the positionTracker. Called 3 times.
        org.mockito.Mockito.doAnswer(inv -> {
            positionTracker.incrementAndGet();
            return null;
        }).when(specificTestIterator).next();

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertNotNull(result);
        assertEquals(3, result.size());
        Set<String> foundVerbs = new HashSet<>();
        Set<Integer> foundDocIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            assertEquals("?verb", result.getVariableNameAt(i));
            assertEquals(ValueType.POS_TERM, result.getValueTypeAt(i));
            foundVerbs.add((String)result.getValueAt(i));
            foundDocIds.add(result.getDocumentIdAt(i));
        }
        assertEquals(Set.of("run", "jump", "swim"), foundVerbs);
        assertEquals(Set.of(1, 2), foundDocIds);

        verify(mockPosIndex).seek(eq(prefixBytes)); // Verify with eq()
        verify(specificTestIterator, times(6)).isValid();
        verify(specificTestIterator, times(3)).key();
        verify(specificTestIterator, times(3)).value();
        verify(specificTestIterator, times(3)).next();
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