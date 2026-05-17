package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.OccurrencesBlock;
import com.example.core.PostingList;
import com.example.index.KeySchema;
import com.example.index.util.SynonymManager;
import com.example.query.model.Query;
import com.example.query.model.condition.Pos;

@ExtendWith(MockitoExtension.class)
class PosExecutorTest {

    @Mock
    private IndexAccessInterface posIndex;
    @Mock
    private RocksIterator posIterator;
    @Mock
    private ConditionExecutorFactory factory;
    @Mock
    private SynonymManager synonymManager;
    @InjectMocks
    private PosExecutor executor;

    private Map<String, IndexAccessInterface> indexes;
    private AttributeRequirements defaultTestRequirements;
    private static final String POS_INDEX_NAME = "pos";

    @BeforeEach
    void setUp() throws IndexAccessException, RocksDBException, IOException {
        indexes = Map.of(POS_INDEX_NAME, posIndex);
        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsSentenceId = true;
        defaultTestRequirements.needsPositions = true;
        defaultTestRequirements.needsSynonymIds = true;
        defaultTestRequirements.needsConceptualRowIds = true;

        lenient().when(posIterator.isValid()).thenReturn(false);

        lenient().when(synonymManager.getId(anyString())).thenReturn(-1);
        lenient().when(synonymManager.getTerm(anyInt())).thenReturn(Optional.empty());
    }

    // --- Helper: build a PostingList with a single cell and occurrence ---
    private static PostingList makePl(int docId, int sentId, int begin, int end) throws IOException {
        long ck = PostingList.packCellKey(docId, sentId);
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        cells.add(ck);
        byte cl = (byte) Math.min(end - begin, 255);
        OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(new long[] { ck }, new byte[][] { { (byte) begin } }, cl);
        return PostingList.fromCellsAndOccurrences(cells, cl, occ);
    }

    // --- Helper: configure a RocksIterator mock to iterate over entries ---
    private void configureRocksIteratorMock(RocksIterator iterator, final List<Map.Entry<byte[], byte[]>> entries) {
        final AtomicInteger currentIndex = new AtomicInteger(-1);

        lenient().when(iterator.isValid()).thenAnswer(inv -> {
            int i = currentIndex.get();
            return i >= 0 && i < entries.size();
        });

        lenient().when(iterator.key()).thenAnswer(inv -> {
            int i = currentIndex.get();
            if (i >= 0 && i < entries.size()) {
                return entries.get(i).getKey();
            }
            throw new IllegalStateException(
                    "Iterator not valid or out of bounds for key(). Index: " + i + ", Size: " + entries.size());
        });

        lenient().when(iterator.value()).thenAnswer(inv -> {
            int i = currentIndex.get();
            if (i >= 0 && i < entries.size()) {
                return entries.get(i).getValue();
            }
            throw new IllegalStateException(
                    "Iterator not valid or out of bounds for value(). Index: " + i + ", Size: " + entries.size());
        });

        lenient().doAnswer(inv -> {
            int i = currentIndex.get();
            if (i >= 0 && i < entries.size()) {
                currentIndex.incrementAndGet();
            }
            return null;
        }).when(iterator).next();

        lenient().doAnswer(inv -> {
            currentIndex.set(entries.isEmpty() ? 0 : 0);
            return null;
        }).when(iterator).seekToFirst();

        lenient().doAnswer(inv -> {
            byte[] targetKey = inv.getArgument(0);
            currentIndex.set(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                if (Arrays.compare(entries.get(i).getKey(), targetKey) >= 0) {
                    currentIndex.set(i);
                    break;
                }
            }
            return null;
        }).when(iterator).seek(any(byte[].class));
    }

    /**
     * Sets up a prefix-scan mock: the iterator yields keys for each synId,
     * and getPostingList returns the corresponding PostingList.
     */
    private void setupPrefixScan(RocksIterator iterator, IndexAccessInterface index,
            String tag, Map<Integer, PostingList> synIdToPl) throws IOException, IndexAccessException {
        byte[] prefix = KeySchema.encodeTypePrefix(tag);

        List<Map.Entry<byte[], byte[]>> rawEntries = new ArrayList<>();
        for (var entry : synIdToPl.entrySet()) {
            byte[] key = KeySchema.encodeKey(tag, entry.getKey());
            rawEntries.add(Map.entry(key, entry.getValue().serialize()));
        }

        configureRocksIteratorMock(iterator, rawEntries);

        lenient().when(index.seek(argThat(k -> Arrays.equals(k, prefix))))
                .thenAnswer(inv -> {
                    iterator.seek(prefix);
                    return iterator;
                });

        // Also mock seekWithBounds (used by the production code after the
        // performance refactor). For tests we ignore the upper-bound and
        // readahead args and delegate to the same iterator logic.
        lenient().when(index.seekWithBounds(argThat(k -> Arrays.equals(k, prefix)),
                any(byte[].class), anyLong()))
                .thenAnswer(inv -> {
                    iterator.seek(prefix);
                    return iterator;
                });

        // Mock getPostingList to return the right PostingList for each key
        lenient().when(index.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenAnswer(inv -> {
                    byte[] key = inv.getArgument(0);
                    for (var entry : synIdToPl.entrySet()) {
                        if (Arrays.equals(key, KeySchema.encodeKey(tag, entry.getKey()))) {
                            return Optional.of(entry.getValue());
                        }
                    }
                    return Optional.empty();
                });
    }

    // ==================== Specific term search tests ====================

    @Test
    void testExecuteSpecificTermDocumentGranularity() throws Exception {
        Pos condition = new Pos("NN", "test");
        String expectedTagString = "NN";
        int testTermSynonymId = 123;

        when(synonymManager.getId("test")).thenReturn(testTermSynonymId);

        PostingList pl1 = makePl(1, 0, 5, 10);
        PostingList pl2 = makePl(2, 1, 15, 20);
        PostingList pl3 = makePl(1, 1, 25, 30);
        PostingList merged = pl1.merge(pl2).merge(pl3);

        byte[] key = KeySchema.encodeKey(expectedTagString, testTermSynonymId);
        when(posIndex.getPostingList(argThat(k -> Arrays.equals(k, key)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(merged));

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.granularity());
        // All cells have different (docId, sentId) combinations; DOCUMENT granularity
        // keeps sentId info
        assertEquals(3, result.cellCount(), "Specific term search should yield 3 cells for 3 matches of 'test'");
        Set<Integer> docIds = new HashSet<>();
        result.cells().forEach((long ck) -> docIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(docIds.containsAll(Set.of(1, 2)), "Should contain doc IDs 1 and 2. Found: " + docIds);

        verify(synonymManager).getId("test");
        verify(posIndex).getPostingList(argThat(k -> Arrays.equals(k, key)), any(PostingList.DeserializeMode.class));
    }

    @Test
    void testExecuteSpecificTermSentenceGranularity() throws Exception {
        Pos condition = new Pos("VB", "run");
        String expectedTagString = "VB";
        int runTermSynonymId = 789;

        when(synonymManager.getId("run")).thenReturn(runTermSynonymId);

        PostingList pl1 = makePl(1, 1, 1, 2);
        PostingList pl2 = makePl(1, 2, 3, 4);
        PostingList pl3 = makePl(2, 1, 5, 6);
        PostingList pl4 = makePl(1, 1, 10, 15);
        PostingList pl5 = makePl(1, 3, 20, 25);
        PostingList merged = pl1.merge(pl2).merge(pl3).merge(pl4).merge(pl5);

        byte[] key = KeySchema.encodeKey(expectedTagString, runTermSynonymId);
        when(posIndex.getPostingList(argThat(k -> Arrays.equals(k, key)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(merged));

        CellResult result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.granularity());
        assertEquals(4, result.cellCount(), "Specific term search should yield 4 cells for 4 matches of 'run'");

        boolean match1_1 = false, match1_2 = false, match2_1 = false, match1_3 = false;
        var iter = result.cells().getLongIterator();
        while (iter.hasNext()) {
            long ck = iter.next();
            int docId = PostingList.docIdFromCellKey(ck);
            int sentId = PostingList.sentIdFromCellKey(ck);
            if (docId == 1 && sentId == 1)
                match1_1 = true;
            if (docId == 1 && sentId == 2)
                match1_2 = true;
            if (docId == 2 && sentId == 1)
                match2_1 = true;
            if (docId == 1 && sentId == 3)
                match1_3 = true;
        }
        assertTrue(match1_1, "Should contain doc 1 sent 1");
        assertTrue(match1_2, "Should contain doc 1 sent 2");
        assertTrue(match2_1, "Should contain doc 2 sent 1");
        assertTrue(match1_3, "Should contain doc 1 sent 3 (cell from pl5 with synId 999)");

        verify(synonymManager).getId("run");
        verify(posIndex).getPostingList(argThat(k -> Arrays.equals(k, key)), any(PostingList.DeserializeMode.class));
    }

    @Test
    void testSentenceGranularityWithWindow() throws Exception {
        Pos condition = new Pos("NN", "noun");
        String expectedTagString = "NN";
        int nounTermSynonymId = 101;

        when(synonymManager.getId("noun")).thenReturn(nounTermSynonymId);

        PostingList pl1 = makePl(1, 0, 1, 2);
        PostingList pl2 = makePl(1, 2, 3, 4);
        PostingList pl3 = makePl(1, 3, 5, 6);
        PostingList pl4 = makePl(2, 1, 7, 8);
        PostingList merged = pl1.merge(pl2).merge(pl3).merge(pl4);

        byte[] key = KeySchema.encodeKey(expectedTagString, nounTermSynonymId);
        when(posIndex.getPostingList(argThat(k -> Arrays.equals(k, key)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(merged));

        CellResult result = executor.execute(condition, indexes, Query.Granularity.SENTENCE, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.SENTENCE, result.granularity());
        assertEquals(4, result.cellCount(), "Specific term search should yield 4 cells for 4 matches of 'noun'");

        boolean match1_0 = false, match1_2 = false, match1_3 = false, match2_1 = false;
        var iter = result.cells().getLongIterator();
        while (iter.hasNext()) {
            long ck = iter.next();
            int docId = PostingList.docIdFromCellKey(ck);
            int sentId = PostingList.sentIdFromCellKey(ck);
            if (docId == 1 && sentId == 0)
                match1_0 = true;
            if (docId == 1 && sentId == 2)
                match1_2 = true;
            if (docId == 1 && sentId == 3)
                match1_3 = true;
            if (docId == 2 && sentId == 1)
                match2_1 = true;
        }
        assertTrue(match1_0, "Should contain doc 1 sent 0");
        assertTrue(match1_2, "Should contain doc 1 sent 2");
        assertTrue(match1_3, "Should contain doc 1 sent 3");
        assertTrue(match2_1, "Should contain doc 2 sent 1");

        verify(synonymManager).getId("noun");
        verify(posIndex).getPostingList(argThat(k -> Arrays.equals(k, key)), any(PostingList.DeserializeMode.class));
    }

    @Test
    void testExecuteSpecificSearch_withVariable()
            throws QueryExecutionException, IndexAccessException, IOException, RocksDBException {
        Pos condition = new Pos("JJ", "happy", "?adj");
        String expectedTagString = "JJ";
        int happySynId = 555;

        when(synonymManager.getId("happy")).thenReturn(happySynId);

        PostingList pl1 = makePl(1, 1, 10, 15);
        PostingList pl2 = makePl(1, 2, 20, 25);
        PostingList merged = pl1.merge(pl2);

        byte[] key = KeySchema.encodeKey(expectedTagString, happySynId);
        when(posIndex.getPostingList(argThat(k -> Arrays.equals(k, key)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(merged));

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.granularity());
        assertEquals(2, result.cellCount(), "Should find 2 cells for 'happy' with tag 'JJ' (includes synId 666)");
        Set<Integer> docIds = new HashSet<>();
        result.cells().forEach((long ck) -> docIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(docIds.contains(1), "Should contain doc 1");

        verify(synonymManager).getId("happy");
        verify(posIndex).getPostingList(argThat(k -> Arrays.equals(k, key)), any(PostingList.DeserializeMode.class));
    }

    @Test
    void testExecute_noMatchFound_specificTerm()
            throws QueryExecutionException, IndexAccessException, RocksDBException, IOException {
        Pos condition = new Pos("XYZ", "term_that_does_not_exist");
        String expectedTagString = "XYZ";
        int nonExistentTermSynId = 999;

        when(synonymManager.getId("term_that_does_not_exist")).thenReturn(nonExistentTermSynId);

        byte[] key = KeySchema.encodeKey(expectedTagString, nonExistentTermSynId);
        when(posIndex.getPostingList(argThat(k -> Arrays.equals(k, key)), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.empty());

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(0, result.cellCount());

        verify(synonymManager).getId("term_that_does_not_exist");
        verify(posIndex).getPostingList(argThat(k -> Arrays.equals(k, key)), any(PostingList.DeserializeMode.class));
    }

    // ==================== Tag-only / prefix scan tests ====================

    @Test
    void testVariableBindingDocumentGranularityNoSpecificTerm() throws Exception {
        Pos condition = new Pos("JJ", null, "?adjVar");
        String expectedTagString = "JJ";

        int goodSynId = 10;
        int badSynId = 20;
        int uglySynId = 30;

        PostingList plGood1 = makePl(1, 1, 5, 10);
        PostingList plBad = makePl(2, 1, 15, 20);
        PostingList plUgly = makePl(1, 2, 25, 30);
        PostingList plGood2 = makePl(3, 0, 35, 40);

        Map<Integer, PostingList> synIdToPl = new HashMap<>();
        synIdToPl.put(goodSynId, plGood1.merge(plGood2));
        synIdToPl.put(badSynId, plBad);
        synIdToPl.put(uglySynId, plUgly);

        setupPrefixScan(posIterator, posIndex, expectedTagString, synIdToPl);

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.granularity());
        assertEquals(4, result.cellCount(), "Should be 4 cells for 4 matches");

        Set<Integer> docIds = new HashSet<>();
        result.cells().forEach((long ck) -> docIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(docIds.containsAll(Set.of(1, 2, 3)), "Docs 1, 2, and 3 should be present. Found: " + docIds);
    }

    @Test
    void testTagOnlySearchDocumentGranularity() throws Exception {
        Pos condition = new Pos("NNP", null);
        String expectedTagString = "NNP";

        PostingList pl1 = makePl(1, 0, 5, 10);
        PostingList pl2 = makePl(1, 1, 15, 20);
        PostingList pl3 = makePl(2, 0, 25, 30);

        Map<Integer, PostingList> synIdToPl = new HashMap<>();
        synIdToPl.put(111, pl1.merge(pl3));
        synIdToPl.put(222, pl2);

        setupPrefixScan(posIterator, posIndex, expectedTagString, synIdToPl);

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());

        assertNotNull(result);
        assertEquals(Query.Granularity.DOCUMENT, result.granularity());
        assertEquals(3, result.cellCount(), "Should be 3 cells for the 3 matches of the tag 'NNP'");

        Set<Integer> docIds = new HashSet<>();
        result.cells().forEach((long ck) -> docIds.add(PostingList.docIdFromCellKey(ck)));
        assertTrue(docIds.containsAll(Set.of(1, 2)), "Should contain doc IDs 1 and 2");

        verify(synonymManager, times(0)).getTerm(anyInt());
    }

    @Test
    void testExecuteWildcardSearch_allPosTags() throws Exception {
        Pos condition = new Pos("*", null);
        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements,
                    Optional.empty());
        });
        assertEquals(QueryExecutionException.ErrorType.UNSUPPORTED_OPERATION, exception.getErrorType());
        verify(posIndex, times(0)).getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class));
    }

    @Test
    void testExecute_variableSearch_tagNotFound()
            throws QueryExecutionException, IndexAccessException, RocksDBException, IOException {
        Pos condition = new Pos("ABC", null, "?var");
        String expectedTagString = "ABC";

        setupPrefixScan(posIterator, posIndex, expectedTagString, Collections.emptyMap());

        CellResult result = executor.execute(condition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                defaultTestRequirements, Optional.empty());
        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(0, result.cellCount());
    }

    @Test
    void testExecute_missingPosIndex() throws QueryExecutionException {
        Pos condition = new Pos("NN", "noun");
        Map<String, IndexAccessInterface> emptyIndexes = Collections.emptyMap();

        QueryExecutionException exception = assertThrows(QueryExecutionException.class, () -> {
            executor.execute(condition, emptyIndexes, Query.Granularity.DOCUMENT, 0, "test_corpus",
                    defaultTestRequirements, Optional.empty());
        });
        assertEquals(QueryExecutionException.ErrorType.MISSING_INDEX, exception.getErrorType());
    }
}
