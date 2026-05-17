package com.example.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.OccurrencesBlock;
import com.example.core.PostingList;
import com.example.core.index.MockIndexAccess;
import com.example.index.KeySchema;
import com.example.index.util.SynonymManager;
import com.example.query.executor.AttributeRequirements;
import com.example.query.executor.CellResult;
import com.example.query.executor.ConditionExecutorFactory;
import com.example.query.executor.QueryExecutionException;
import com.example.query.executor.QueryExecutor;
import com.example.query.index.IndexManager;
import com.example.query.model.Query;
import com.example.query.model.condition.Ner;
import com.example.query.result.ResultGenerationException;
import com.example.query.result.ResultMaterializer;
import com.example.query.result.Schema;
import com.example.query.result.Table;
import com.example.query.sqlite.SqliteAccessor;

/**
 * End-to-end tests for query parsing, execution, and result generation.
 * Uses mock indexes for predictable results.
 */
public class QueryEndToEndTest {

    private static final Logger logger = LoggerFactory.getLogger(QueryEndToEndTest.class);

    @TempDir
    static Path tempDir;

    private static QueryExecutor queryExecutor;
    private static MockIndexAccess mockUnigramIndex;
    private static MockIndexAccess mockBigramIndex;
    private static MockIndexAccess mockTrigramIndex;
    private static MockIndexAccess mockNerIndex;
    private static MockIndexAccess mockNerDateIndex;
    private static Map<String, IndexAccessInterface> mockIndexes;
    private static QueryParser queryParser;
    private static ConditionExecutorFactory factory;
    private static AttributeRequirements defaultTestRequirements;

    private static SynonymManager staticMockSynonymManager;
    private static IndexManager mockIndexManager;

    private static final char DELIMITER = '\0';

    // Helper for managing synonym IDs and mocking SynonymManager for NER tests
    private static Map<String, Integer> termToSynIdMap = new HashMap<>();
    private static Map<Integer, String> synIdToTermMap = new HashMap<>();
    private static int nextNerSynId = 1;

    private static int getOrAssignNerSynId(String term) {
        String lowerTerm = term.toLowerCase();
        return termToSynIdMap.computeIfAbsent(lowerTerm, t -> {
            int id = nextNerSynId++;
            synIdToTermMap.put(id, t);
            try {
                lenient().when(staticMockSynonymManager.getId(t)).thenReturn(id);
                lenient().when(staticMockSynonymManager.getTerm(id)).thenReturn(Optional.of(t));
            } catch (RocksDBException e) {
                throw new RuntimeException("RocksDBException during mock setup for SynonymManager", e);
            }
            return id;
        });
    }

    /**
     * Helper: build a PostingList for a single (docId, sentId) with one occurrence.
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

    @BeforeAll
    public static void setUp() throws IOException, IndexAccessException, RocksDBException {
        File indexBasePath = tempDir.resolve("testIndexes").toFile();
        indexBasePath.mkdirs();
        File sourceIndexPath = tempDir.resolve("testIndexes/source").toFile();
        sourceIndexPath.mkdirs();

        staticMockSynonymManager = org.mockito.Mockito.mock(SynonymManager.class);
        mockIndexManager = org.mockito.Mockito.mock(IndexManager.class);

        queryParser = new QueryParser();
        factory = new ConditionExecutorFactory(staticMockSynonymManager, "optimized", Query.Granularity.DOCUMENT);
        queryExecutor = new QueryExecutor("optimized", staticMockSynonymManager, factory);

        mockUnigramIndex = new MockIndexAccess("unigram");
        mockUnigramIndex.addTestData("apple", 1, 1, 0, 5);
        mockUnigramIndex.addTestData("apple", 2, 1, 10, 15);
        mockUnigramIndex.addTestData("banana", 2, 2, 20, 25);
        mockUnigramIndex.addTestData("test", 0, 0, 0, 4);
        mockUnigramIndex.addTestData("test", 1, 1, 0, 4);
        mockUnigramIndex.addTestData("window", 0, 1, 0, 6);
        mockUnigramIndex.addTestData("window", 0, 3, 0, 6);
        mockUnigramIndex.addTestData("grape", 3, 1, 5, 10);

        mockBigramIndex = new MockIndexAccess();
        mockBigramIndex.addTestData("read" + DELIMITER + "monkey", 3, 1, 10, 20);
        mockBigramIndex.addTestData("big" + DELIMITER + "cat", 4, 1, 0, 6);

        mockTrigramIndex = new MockIndexAccess();
        mockTrigramIndex.addTestData("the" + DELIMITER + "quick" + DELIMITER + "fox", 5, 1, 0, 15);

        mockNerIndex = new MockIndexAccess("ner");

        // NER Mock Data: entity type + synId encoded as key, PostingList as value
        addNerEntry("PERSON", "albert einstein", 6, 1, 0, 15);
        addNerEntry("PERSON", "marie curie", 6, 2, 20, 30);
        addNerEntry("PERSON", "isaac newton", 7, 1, 5, 17);
        addNerEntry("ORGANIZATION", "google", 7, 2, 40, 46);
        addNerEntry("ORGANIZATION", "microsoft corporation", 11, 1, 0, 20);
        addNerEntry("LOCATION", "london", 8, 1, 0, 6);
        addNerEntry("NUMBER", "42", 8, 2, 10, 12);
        addNerEntry("ORDINAL", "first", 9, 1, 0, 5);
        addNerEntry("DURATION", "3 years", 9, 2, 10, 17);
        addNerEntry("SET", "weekly", 10, 1, 0, 6);
        addNerEntry("PERSON", "albrecht kossel", 12, 1, 5, 20);

        mockNerDateIndex = new MockIndexAccess("ner_date");
        mockNerDateIndex.addTestData("20230320", 1, 1, 30, 40);
        mockNerDateIndex.addTestData("20230115", 2, 1, 0, 10);
        mockNerDateIndex.addTestData("20240101", 3, 1, 50, 60);
        mockNerDateIndex.addTestData("20240115", 30, 1, 0, 10);

        mockIndexes = Map.of(
                "unigram", mockUnigramIndex,
                "bigram", mockBigramIndex,
                "trigram", mockTrigramIndex,
                "ner", mockNerIndex,
                "ner_date", mockNerDateIndex);

        lenient().when(mockIndexManager.getAllIndexes()).thenReturn(mockIndexes);
        lenient().when(mockIndexManager.getSynonymManager()).thenReturn(staticMockSynonymManager);

        try {
            lenient().when(staticMockSynonymManager.getTerms(ArgumentMatchers.<Set<Integer>>any()))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        Set<Integer> ids = invocation.getArgument(0);
                        Map<Integer, String> result = new HashMap<>();
                        for (Integer id : ids) {
                            String term = synIdToTermMap.get(id);
                            if (term != null)
                                result.put(id, term);
                        }
                        return result;
                    });
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    private static void addNerEntry(String type, String term, int docId, int sentId, int begin, int end)
            throws IOException {
        int synId = getOrAssignNerSynId(term);
        byte[] key = KeySchema.encodeKey(type.toUpperCase(), synId);
        PostingList pl = makePl(docId, sentId, begin, end);
        mockNerIndex.addRawTestData(key, pl.serialize());
    }

    @AfterAll
    public static void tearDown() {
        if (mockUnigramIndex != null)
            mockUnigramIndex.close();
        if (mockBigramIndex != null)
            mockBigramIndex.close();
        if (mockTrigramIndex != null)
            mockTrigramIndex.close();
        if (mockNerIndex != null)
            mockNerIndex.close();
        if (mockNerDateIndex != null)
            mockNerDateIndex.close();
        logger.info("End-to-End Test Teardown Complete.");
    }

    // --- Helper to extract doc IDs from CellResult ---
    private static Set<Integer> extractDocIds(CellResult result) {
        Set<Integer> ids = new HashSet<>();
        long[] arr = result.cells().toArray();
        for (long ck : arr)
            ids.add(PostingList.docIdFromCellKey(ck));
        return ids;
    }

    // --- Tests ---

    @Test
    public void testSimpleContainsQuery()
            throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('apple')";
        Query query = queryParser.parse(queryStr);
        CellResult results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for 'apple'");
        assertEquals(2, results.cellCount());
        assertEquals(Query.Granularity.DOCUMENT, results.granularity());
        assertTrue(extractDocIds(results).containsAll(Set.of(1, 2)));

        var materializer = new ResultMaterializer(query.source());
        var rows = materializer.materialize(results, query);
        Table resultTable = Table.collect(rows, Schema.fromQuery(query));
        assertNotNull(resultTable);
        assertEquals(2, resultTable.rowCount());
    }

    @Test
    public void testContainsNoMatchQuery()
            throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('nonexistent')";
        Query query = queryParser.parse(queryStr);
        CellResult results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertTrue(results.isEmpty(), "Expected no results for 'nonexistent'");
        assertEquals(Query.Granularity.DOCUMENT, results.granularity());

        var materializer = new ResultMaterializer(query.source());
        var rows = materializer.materialize(results, query);
        Table resultTable = Table.collect(rows, Schema.fromQuery(query));
        assertNotNull(resultTable);
        assertEquals(0, resultTable.rowCount());
    }

    @Test
    public void testContainsSingleQuote()
            throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('grape')";
        Query query = queryParser.parse(queryStr);
        CellResult results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for 'grape'");
        assertEquals(1, results.cellCount());
        assertEquals(3, PostingList.docIdFromCellKey(results.cells().select(0)));

        var materializer = new ResultMaterializer(query.source());
        var rows = materializer.materialize(results, query);
        Table resultTable = Table.collect(rows, Schema.fromQuery(query));
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
    }

    @Test
    public void testContainsBigramWithSpace()
            throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('big cat')";
        Query query = queryParser.parse(queryStr);
        CellResult results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for 'big cat'");
        assertEquals(1, results.cellCount());
        assertEquals(4, PostingList.docIdFromCellKey(results.cells().select(0)));

        var materializer = new ResultMaterializer(query.source());
        var rows = materializer.materialize(results, query);
        Table resultTable = Table.collect(rows, Schema.fromQuery(query));
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
    }

    @Test
    public void testContainsBigramWithComma()
            throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('read monkey')";
        Query query = queryParser.parse(queryStr);
        CellResult results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for 'read monkey'");
        assertEquals(1, results.cellCount());
        assertEquals(3, PostingList.docIdFromCellKey(results.cells().select(0)));

        var materializer = new ResultMaterializer(query.source());
        var rows = materializer.materialize(results, query);
        Table resultTable = Table.collect(rows, Schema.fromQuery(query));
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
    }

    @Test
    public void testContainsTrigramWithSpace()
            throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('the quick fox')";
        Query query = queryParser.parse(queryStr);
        CellResult results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for 'the quick fox'");
        assertEquals(1, results.cellCount());
        assertEquals(5, PostingList.docIdFromCellKey(results.cells().select(0)));

        var materializer = new ResultMaterializer(query.source());
        var rows = materializer.materialize(results, query);
        Table resultTable = Table.collect(rows, Schema.fromQuery(query));
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
    }

    @Test
    public void testNerSimpleTypeQuery()
            throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM test_corpus WHERE NER(PERSON)";
        Query query = queryParser.parse(queryString);
        CellResult result = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(result, "CellResult should not be null");
        Set<Integer> docIds = extractDocIds(result);
        assertEquals(3, docIds.size(), "Should find PERSON entities in 3 distinct documents (6, 7, 12)");
        assertTrue(docIds.containsAll(Set.of(6, 7, 12)), "Documents 6, 7, 12 should be present.");
    }
}
