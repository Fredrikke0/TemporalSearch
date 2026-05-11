package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
import com.example.query.model.condition.Contains;

@ExtendWith(MockitoExtension.class)
public class ContainsConditionExecutorTest {

    @Mock
    private IndexAccess mockUnigramIndex;
    @Mock
    private IndexAccess mockBigramIndex;
    @Mock
    private IndexAccess mockTrigramIndex;

    private ContainsExecutor executor;
    private Map<String, IndexAccessInterface> indexes;

    /** Helper: build a PostingList from (docId, sentId, begin, end) pairs. */
    private static PostingList makePl(int docId, int sentId, int begin, int end) throws IOException {
        long ck = PostingList.packCellKey(docId, sentId);
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        cells.add(ck);
        byte cl = (byte) Math.min(end - begin, 255);
        OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(
                new long[] { ck }, new byte[][] { { (byte) begin } }, cl);
        return PostingList.fromCellsAndOccurrences(cells, cl, occ);
    }

    /** Helper: build a PostingList with multiple cells. */
    private static PostingList makePl(int[][] cells) throws IOException {
        PostingList pl = PostingList.empty((byte) 0);
        for (int[] c : cells) {
            pl = pl.merge(makePl(c[0], c[1], c[2], c[3]));
        }
        return pl;
    }

    @BeforeEach
    void setUp() throws IndexAccessException {
        indexes = Map.of("unigram", mockUnigramIndex, "bigram", mockBigramIndex, "trigram", mockTrigramIndex);
        executor = new ContainsExecutor();
    }

    // --- Helper to build a CellResult from (docId, sentId) pairs ---
    private static CellResult cellResultOf(int... docSentPairs) {
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        for (int i = 0; i < docSentPairs.length; i += 2) {
            cells.add(PostingList.packCellKey(docSentPairs[i], docSentPairs[i + 1]));
        }
        return CellResult.of(cells, Query.Granularity.DOCUMENT);
    }

    @Test
    void testExecuteSingleTerm() throws Exception {
        PostingList pl = makePl(new int[][] { { 1, 1, 0, 5 }, { 2, 1, 10, 15 } });
        when(mockUnigramIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(pl));

        CellResult result = executor.execute(new Contains("test"), indexes,
                Query.Granularity.DOCUMENT, 0, "test_corpus",
                new AttributeRequirements(), Optional.empty());

        assertEquals(2, result.cellCount());
        long[] arr = result.cells().toArray();
        assertEquals(1, PostingList.docIdFromCellKey(arr[0]));
        assertEquals(2, PostingList.docIdFromCellKey(arr[1]));
    }

    @Test
    void testExecuteTermNotFound() throws Exception {
        when(mockUnigramIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.empty());

        CellResult result = executor.execute(new Contains("nonexistent"), indexes,
                Query.Granularity.DOCUMENT, 0, "test_corpus",
                new AttributeRequirements(), Optional.empty());

        assertTrue(result.isEmpty());
    }

    @Test
    void testExecuteMissingIndex() {
        Contains condition = new Contains("test");
        Map<String, IndexAccessInterface> emptyIndexes = new HashMap<>();
        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> executor.execute(condition, emptyIndexes, Query.Granularity.DOCUMENT, 1,
                        "test_corpus", new AttributeRequirements(), Optional.empty()));
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, ex.getErrorType());
        assertTrue(ex.getMessage().contains("Required unigram index not found"));
    }

    @Test
    void testEmptyTerms() {
        Contains condition = new Contains(Collections.emptyList());
        QueryExecutionException ex = assertThrows(QueryExecutionException.class,
                () -> executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0,
                        "test_corpus", new AttributeRequirements(), Optional.empty()));
        assertEquals("Contains condition must have at least one term.", ex.getMessage());
    }

    @Test
    void testUnigramMatch() throws Exception {
        PostingList pl = makePl(new int[][] { { 1, 1, 0, 5 }, { 2, 1, 10, 15 } });
        when(mockUnigramIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(pl));

        CellResult result = executor.execute(new Contains("unique"), indexes,
                Query.Granularity.DOCUMENT, 0, "test_corpus",
                new AttributeRequirements(), Optional.empty());

        assertNotNull(result);
        assertEquals(2, result.cellCount());
        Set<Integer> docIds = new HashSet<>();
        long[] arr = result.cells().toArray();
        for (long ck : arr)
            docIds.add(PostingList.docIdFromCellKey(ck));
        assertTrue(docIds.containsAll(Set.of(1, 2)));
    }

    @Test
    void testBigramMatch() throws Exception {
        PostingList pl = makePl(3, 1, 0, 8);
        when(mockBigramIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(pl));

        CellResult result = executor.execute(new Contains(Arrays.asList("two", "terms")), indexes,
                Query.Granularity.DOCUMENT, 0, "test_corpus",
                new AttributeRequirements(), Optional.empty());

        assertNotNull(result);
        assertEquals(1, result.cellCount());
        long[] barr = result.cells().toArray();
        assertEquals(3, PostingList.docIdFromCellKey(barr[0]));
    }

    @Test
    void testTrigramMatch() throws Exception {
        PostingList pl = makePl(4, 1, 0, 15);
        when(mockTrigramIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(pl));

        CellResult result = executor.execute(new Contains(Arrays.asList("three", "separate", "terms")), indexes,
                Query.Granularity.DOCUMENT, 0, "test_corpus",
                new AttributeRequirements(), Optional.empty());

        assertNotNull(result);
        assertEquals(1, result.cellCount());
        long[] tarr = result.cells().toArray();
        assertEquals(4, PostingList.docIdFromCellKey(tarr[0]));
    }

    @Test
    void testNoMatch() throws Exception {
        lenient().when(mockUnigramIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.empty());

        CellResult result = executor.execute(new Contains("nonexistent"), indexes,
                Query.Granularity.DOCUMENT, 0, "test_corpus",
                new AttributeRequirements(), Optional.empty());

        assertTrue(result.isEmpty());
    }

    @Test
    void testSentenceGranularity() throws Exception {
        PostingList pl = makePl(new int[][] {
                { 1, 0, 0, 4 }, // sentence 0
                { 1, 1, 5, 9 }, // sentence 1
                { 1, 3, 10, 14 } // sentence 3
        });
        when(mockUnigramIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(pl));

        CellResult result = executor.execute(new Contains("test"), indexes,
                Query.Granularity.SENTENCE, 1, "test_corpus",
                new AttributeRequirements(), Optional.empty());

        assertEquals(3, result.cellCount());
        assertEquals(Query.Granularity.SENTENCE, result.granularity());
    }
}
