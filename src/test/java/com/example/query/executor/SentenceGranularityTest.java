package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.index.util.SynonymManager;
import com.example.query.QueryParseException;
import com.example.query.QueryParser;
import com.example.query.index.IndexManager;
import com.example.query.model.Query;

/**
 * Tests focusing on sentence granularity and windowing.
 */
@ExtendWith(MockitoExtension.class)
public class SentenceGranularityTest {

    @TempDir
    static Path tempDir;

    private static QueryExecutor queryExecutor;
    private static QueryParser queryParser;
    private static ConditionExecutorFactory factory;

    @Mock
    private static IndexAccess unigramIndex;

    @Mock
    private static SynonymManager mockSynonymManager;

    @Mock
    private static IndexManager mockIndexManager;

    @BeforeAll
    public static void setUp() throws IOException, IndexAccessException {
        // Static mocks (unigramIndex, mockSynonymManager, mockIndexManager) are
        // injected by MockitoExtension
        factory = new ConditionExecutorFactory(mockSynonymManager, "none", Query.Granularity.SENTENCE);
        queryExecutor = new QueryExecutor("none", mockSynonymManager, factory);
        queryParser = new QueryParser();
        System.out.println("Sentence Granularity Test Setup Complete.");
    }

    @AfterAll
    public static void tearDown() throws Exception {
        System.out.println("Sentence Granularity Test Teardown Complete.");
    }

    private static PostingList createPostingList(int[][] cellData) throws IOException {
        PostingList pl = PostingList.empty((byte) 0);
        for (int[] c : cellData) {
            Roaring64NavigableMap cells = new Roaring64NavigableMap();
            cells.add(PostingList.packCellKey(c[0], c[1]));
            pl = pl.merge(PostingList.fromCells(cells, (byte) 0));
        }
        return pl;
    }

    private CellResult executeSentenceQuery(String queryString, Map<String, IndexAccessInterface> testIndexes)
            throws QueryParseException, QueryExecutionException {
        Query query = queryParser.parse(queryString);
        assertTrue(query.granularity() == Query.Granularity.SENTENCE || query.granularitySize().isPresent(),
                "Query granularity should be SENTENCE or have a window size");

        lenient().when(mockIndexManager.getAllIndexes()).thenReturn(testIndexes);
        lenient().when(mockIndexManager.getSynonymManager()).thenReturn(mockSynonymManager);

        return queryExecutor.execute(query, mockIndexManager);
    }

    private boolean cellResultContainsMatch(CellResult cr, int docId, int sentId) {
        if (cr == null || cr.isEmpty())
            return false;
        return cr.cells().contains(PostingList.packCellKey(docId, sentId));
    }

    @Test
    public void testSentenceGranularityBasic() throws Exception {
        String queryString = "SELECT DOCUMENT_ID FROM mockCorpusSent WHERE CONTAINS('test') GRANULARITY SENTENCE";

        PostingList pl = createPostingList(new int[][] { { 0, 0 }, { 1, 1 } });
        lenient().when(unigramIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(pl));
        Map<String, IndexAccessInterface> testIndexes = Map.of("unigram", unigramIndex);

        CellResult results = executeSentenceQuery(queryString, testIndexes);

        assertNotNull(results);
        assertEquals(Query.Granularity.SENTENCE, results.granularity());
        assertEquals(2, results.cellCount());
        assertTrue(cellResultContainsMatch(results, 0, 0), "Result should contain doc 0, sent 0");
        assertTrue(cellResultContainsMatch(results, 1, 1), "Result should contain doc 1, sent 1");
    }

    @Test
    public void testSentenceGranularityWithWindow() throws Exception {
        String queryString = "SELECT DOCUMENT_ID FROM mockCorpusSent WHERE CONTAINS('window') GRANULARITY SENTENCE 1";

        PostingList pl = createPostingList(new int[][] { { 0, 1 }, { 0, 3 } });
        lenient().when(unigramIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(pl));
        Map<String, IndexAccessInterface> testIndexes = Map.of("unigram", unigramIndex);

        CellResult results = executeSentenceQuery(queryString, testIndexes);

        assertNotNull(results);
        assertEquals(Query.Granularity.SENTENCE, results.granularity());
        assertEquals(2, results.cellCount(),
                "Expected 2 results (Sent 1 and Sent 3) as windowing is applied by QueryExecutor, not ContainsExecutor directly for single conditions");
        assertTrue(cellResultContainsMatch(results, 0, 1), "Should contain result for Sent 1");
        assertTrue(cellResultContainsMatch(results, 0, 3), "Should contain result for Sent 3");
    }

    @Test
    public void testSentenceGranularityWithLargerWindow() throws Exception {
        String queryString = "SELECT DOCUMENT_ID FROM mockCorpusSent WHERE CONTAINS('window') GRANULARITY SENTENCE 2";

        PostingList pl = createPostingList(new int[][] { { 0, 1 }, { 0, 3 } });
        lenient().when(unigramIndex.getPostingList(any(byte[].class), any(PostingList.DeserializeMode.class)))
                .thenReturn(Optional.of(pl));
        Map<String, IndexAccessInterface> testIndexes = Map.of("unigram", unigramIndex);

        CellResult results = executeSentenceQuery(queryString, testIndexes);

        assertNotNull(results);
        assertEquals(Query.Granularity.SENTENCE, results.granularity());
        assertEquals(2, results.cellCount(), "Expected 2 results for window=2");
        assertTrue(cellResultContainsMatch(results, 0, 1), "Should contain result for Sent 1");
        assertTrue(cellResultContainsMatch(results, 0, 3), "Should contain result for Sent 3");
    }
}
