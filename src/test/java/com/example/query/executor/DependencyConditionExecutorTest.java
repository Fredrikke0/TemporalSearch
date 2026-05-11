package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.OccurrencesBlock;
import com.example.core.PostingList;
import com.example.query.model.Query;
import com.example.query.model.condition.Dependency;

@ExtendWith(MockitoExtension.class)
public class DependencyConditionExecutorTest {

    @Mock
    private IndexAccess mockIndex;

    private DependencyExecutor executor;
    private Map<String, IndexAccessInterface> indexes;
    private AttributeRequirements defaultTestRequirements;
    private static final String DELIMITER_STR = String.valueOf(IndexAccessInterface.DELIMITER);

    @BeforeEach
    void setUp() {
        executor = new DependencyExecutor();
        indexes = Map.of("dependency", mockIndex);
        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsSentenceId = true;
    }

    /**
     * Helper to build a PostingList with a single cell and occurrence.
     */
    private static PostingList makePl(int docId, int sentId, int begin, int end) throws IOException {
        long ck = PostingList.packCellKey(docId, sentId);
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        cells.add(ck);
        byte cl = (byte) Math.min(end - begin, 255);
        OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(
                new long[] { ck }, new byte[][] { { (byte) begin } }, cl);
        return PostingList.fromCellsAndOccurrences(cells, cl, occ);
    }

    /**
     * Helper to build a PostingList from multiple cells, each with one or more
     * occurrences.
     * Occurrences for the same cell are merged into a single entry.
     */
    private static PostingList makePlFromCells(int[][] cellSpecs) throws IOException {
        // cellSpecs: array of {docId, sentId, begin, end}
        // Group occurrences by cell key
        java.util.LinkedHashMap<Long, java.util.List<Integer>> cellToBegins = new java.util.LinkedHashMap<>();
        byte cl = 0;
        for (int i = 0; i < cellSpecs.length; i++) {
            int docId = cellSpecs[i][0];
            int sentId = cellSpecs[i][1];
            int begin = cellSpecs[i][2];
            int end = cellSpecs[i][3];
            long ck = PostingList.packCellKey(docId, sentId);
            cellToBegins.computeIfAbsent(ck, k -> new java.util.ArrayList<>()).add(begin);
            cl = (byte) Math.max(cl, Math.min(end - begin, 255));
        }

        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        long[] cellKeys = new long[cellToBegins.size()];
        byte[][] occurArrays = new byte[cellToBegins.size()][];
        int idx = 0;
        for (var entry : cellToBegins.entrySet()) {
            cells.add(entry.getKey());
            cellKeys[idx] = entry.getKey();
            java.util.List<Integer> begins = entry.getValue();
            occurArrays[idx] = new byte[begins.size()];
            for (int j = 0; j < begins.size(); j++) {
                occurArrays[idx][j] = (byte) (int) begins.get(j);
            }
            idx++;
        }
        OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(cellKeys, occurArrays, cl);
        return PostingList.fromCellsAndOccurrences(cells, cl, occ);
    }

    @Test
    void testExecuteSpecificSearch_allLiterals_matchFound()
            throws QueryExecutionException, IndexAccessException, IOException {
        Dependency condition = new Dependency("governor", "relation", "dependent");

        PostingList pl = makePlFromCells(new int[][] {
                { 1, 1, 10, 18 },
                { 1, 2, 5, 12 }
        });
        when(mockIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(pl));

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0,
                "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.cellCount());
        assertEquals(Query.Granularity.DOCUMENT, result.granularity());

        // Verify both cells are present
        long cell1 = PostingList.packCellKey(1, 1);
        long cell2 = PostingList.packCellKey(1, 2);
        assertTrue(result.cells().contains(cell1), "Expected cell (1,1)");
        assertTrue(result.cells().contains(cell2), "Expected cell (1,2)");
    }

    @Test
    void testExecuteSpecificSearch_sentenceGranularity()
            throws QueryExecutionException, IndexAccessException, IOException {
        Dependency condition = new Dependency("subject", "nsubj", "dependent");

        PostingList pl = makePlFromCells(new int[][] {
                { 10, 1, 0, 7 },
                { 10, 1, 15, 20 },
                { 10, 2, 3, 9 }
        });
        when(mockIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(pl));

        CellResult result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0,
                "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        // Two unique cells: (10,1) and (10,2)
        assertEquals(2, result.cellCount());
        assertEquals(Query.Granularity.SENTENCE, result.granularity());

        long cell1 = PostingList.packCellKey(10, 1);
        long cell2 = PostingList.packCellKey(10, 2);
        assertTrue(result.cells().contains(cell1), "Expected cell (10,1)");
        assertTrue(result.cells().contains(cell2), "Expected cell (10,2)");
    }

    @Test
    void testExecute_bindAllLiterals_iterativePath_matchFound()
            throws QueryExecutionException, IndexAccessException, IOException {
        Dependency condition = new Dependency("city", "located_in", "country", "?where");
        String fullKey = "city" + DELIMITER_STR + "located_in" + DELIMITER_STR + "country";

        PostingList pl = makePl(5, 3, 2, 8);

        // Mock for the iterative path (executeVariableSearch)
        org.rocksdb.RocksIterator mockRocksIterator = org.mockito.Mockito.mock(org.rocksdb.RocksIterator.class);
        when(mockIndex.seekWithBounds(any(), any(), anyLong())).thenReturn(mockRocksIterator);
        when(mockRocksIterator.isValid()).thenReturn(true, false); // First call true, then false
        when(mockRocksIterator.key()).thenReturn(fullKey.toLowerCase().getBytes());
        when(mockIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(pl));

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0,
                "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(1, result.cellCount(), "Expected 1 cell from iterative path with BIND");
        assertTrue(result.cells().contains(PostingList.packCellKey(5, 3)),
                "Expected cell (5,3)");

        // Verify iterator was used
        org.mockito.Mockito.verify(mockIndex).seekWithBounds(any(), any(), anyLong());
        org.mockito.Mockito.verify(mockRocksIterator, org.mockito.Mockito.atLeastOnce()).isValid();
        org.mockito.Mockito.verify(mockRocksIterator).key();
        org.mockito.Mockito.verify(mockRocksIterator).next();
    }

    @Test
    void testExecuteSpecificSearch_noMatchFound() throws QueryExecutionException, IndexAccessException {
        Dependency condition = new Dependency("unknown", "rel", "target");
        when(mockIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.empty());

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0,
                "test_corpus", defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testExecute_missingIndex() {
        Dependency condition = new Dependency("governor", "relation", "dependent");
        Map<String, IndexAccessInterface> emptyIndexes = Collections.emptyMap();

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, emptyIndexes, Query.Granularity.DOCUMENT, 0,
                    "test_corpus", defaultTestRequirements, Optional.empty());
        });

        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
    }

    @Test
    void testExecute_indexAccessError() throws IndexAccessException {
        Dependency condition = new Dependency("governor", "relation", "dependent");
        when(mockIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenThrow(new IndexAccessException("Test error accessing index", "dependency",
                        IndexAccessException.ErrorType.READ_ERROR));

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0,
                    "test_corpus", defaultTestRequirements, Optional.empty());
        });

        assertEquals(QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR, exception.getErrorType());
    }
}
