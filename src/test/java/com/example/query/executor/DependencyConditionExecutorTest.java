package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Dependency;

@ExtendWith(MockitoExtension.class)
public class DependencyConditionExecutorTest {

    @Mock
    private IndexAccessInterface mockIndex;

    private DependencyExecutor executor;
    private Map<String, IndexAccessInterface> indexes;
    private AttributeRequirements defaultTestRequirements;
    private static final String DELIMITER_STR = String.valueOf(IndexAccessInterface.DELIMITER);

    @BeforeEach
    void setUp() {
        executor = new DependencyExecutor();
        indexes = Map.of("rb_dependency", mockIndex);
        defaultTestRequirements = new AttributeRequirements(); // Default, all true
        defaultTestRequirements.needsSentenceId = true; // Ensure sentence IDs are required
    }

    @Test
    void testExecuteSpecificSearch_allLiterals_matchFound() throws QueryExecutionException, IndexAccessException, java.io.IOException {
        Dependency condition = new Dependency("governor", "relation", "dependent");
        String expectedKey = "governor" + DELIMITER_STR + "relation" + DELIMITER_STR + "dependent";

        // RB presence bytes for the full key
        com.example.index.presence.RBPresenceIndex presence = new com.example.index.presence.RBPresenceIndex();
        presence.add(1, 1);
        presence.add(1, 2);
        when(mockIndex.getRaw(eq(expectedKey.toLowerCase().getBytes()))).thenReturn(Optional.of(presence.toBytes()));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.size());

        // Example: Check details of the first match entry
        assertEquals("governor:relation:dependent", result.getValueAt(0));
        assertEquals(ValueType.DEPENDENCY, result.getValueTypeAt(0));
        assertEquals(1, result.getDocumentIdAt(0));
        assertEquals(1, result.getSentenceIdAt(0));
        assertNull(result.getVariableNameAt(0));

        // Check details of the second match entry
        assertEquals("governor:relation:dependent", result.getValueAt(1));
        assertEquals(1, result.getDocumentIdAt(1));
        assertEquals(2, result.getSentenceIdAt(1));
        assertNull(result.getVariableNameAt(1));

        // Verify conceptual rows if it was grouped. Here we expect 1 conceptual row for this specific match.
        // This requires a way to count unique conceptualRowIds if QueryResultSoA.size() means total bindings.
        // Assuming QueryResultSoA.size() returns total bindings, and conceptual grouping happens elsewhere or is implicit.
        // For now, we focus on the content of the bindings.
    }

    @Test
    void testExecuteSpecificSearch_sentenceGranularity() throws QueryExecutionException, IndexAccessException, java.io.IOException {
        Dependency condition = new Dependency("subject", "nsubj", "dependent");
        String expectedKey = "subject" + DELIMITER_STR + "nsubj" + DELIMITER_STR + "dependent";

        com.example.index.presence.RBPresenceIndex presence = new com.example.index.presence.RBPresenceIndex();
        presence.add(10, 1);
        presence.add(10, 1);
        presence.add(10, 2);
        when(mockIndex.getRaw(eq(expectedKey.toLowerCase().getBytes()))).thenReturn(Optional.of(presence.toBytes()));

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        // Presence-based index returns one binding per (docId,sentId) occurrence; duplicates at same sentence collapse to one
        assertEquals(2, result.size());

        // Verify sentence-level details (example for the first one)
        assertEquals(10, result.getDocumentIdAt(0));
        assertEquals(1, result.getSentenceIdAt(0));
        assertEquals("subject:nsubj:dependent", result.getValueAt(0));
    }


    @Test
    void testExecute_bindAllLiterals_iterativePath_matchFound() throws QueryExecutionException, IndexAccessException, java.io.IOException {
        Dependency condition = new Dependency("city", "located_in", "country", "?where");
        String govRelPrefixKey = "city" + DELIMITER_STR + "located_in" + DELIMITER_STR;
        String fullKey = "city" + DELIMITER_STR + "located_in" + DELIMITER_STR + "country";

        com.example.index.presence.RBPresenceIndex presence = new com.example.index.presence.RBPresenceIndex();
        presence.add(5, 3);
        byte[] serializedPositions = presence.toBytes();

        // Mock for the iterative path (executeVariableSearchOptimized)
        org.rocksdb.RocksIterator mockRocksIterator = org.mockito.Mockito.mock(org.rocksdb.RocksIterator.class);
        when(mockIndex.seekWithBounds(any(), any(), anyLong())).thenReturn(mockRocksIterator);
        when(mockRocksIterator.isValid()).thenReturn(true, false); // First call true, then false
        when(mockRocksIterator.key()).thenReturn(fullKey.toLowerCase().getBytes());
        when(mockRocksIterator.value()).thenReturn(serializedPositions);


        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(1, result.size(), "Expected 1 result from iterative path with BIND");
        assertEquals("city:located_in:country", result.getValueAt(0)); // Value is the full relation
        assertEquals(ValueType.DEPENDENCY, result.getValueTypeAt(0));
        assertEquals("?where", result.getVariableNameAt(0));
        assertEquals(5, result.getDocumentIdAt(0));

        // Verify iterator was used
        org.mockito.Mockito.verify(mockIndex).seekWithBounds(any(), any(), anyLong());
        org.mockito.Mockito.verify(mockRocksIterator, org.mockito.Mockito.atLeastOnce()).isValid();
        org.mockito.Mockito.verify(mockRocksIterator).key();
        org.mockito.Mockito.verify(mockRocksIterator).value();
        org.mockito.Mockito.verify(mockRocksIterator).next(); // ensure it was advanced
    }

     @Test
    void testExecuteSpecificSearch_noMatchFound() throws QueryExecutionException, IndexAccessException, java.io.IOException {
        Dependency condition = new Dependency("unknown", "rel", "target");
        String expectedKey = "unknown" + DELIMITER_STR + "rel" + DELIMITER_STR + "target";
        when(mockIndex.getRaw(eq(expectedKey.toLowerCase().getBytes()))).thenReturn(Optional.empty());

        QueryResultSoA result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

     @Test
    void testExecute_missingIndex() {
        Dependency condition = new Dependency("governor", "relation", "dependent");
        Map<String, IndexAccessInterface> emptyIndexes = Collections.emptyMap();

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, emptyIndexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        });

        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
    }

    @Test
    void testExecute_indexAccessError() throws IndexAccessException, java.io.IOException {
        Dependency condition = new Dependency("governor", "relation", "dependent");
        String expectedKey = "governor" + DELIMITER_STR + "relation" + DELIMITER_STR + "dependent";
        when(mockIndex.getRaw(eq(expectedKey.toLowerCase().getBytes()))).thenThrow(new IndexAccessException("Test error accessing index", "rb_dependency", IndexAccessException.ErrorType.READ_ERROR));

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements, Optional.empty());
        });

        assertEquals(QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR, exception.getErrorType());
    }
}