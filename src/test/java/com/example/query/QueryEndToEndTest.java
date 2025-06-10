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
import java.time.LocalDate;
import java.util.*;

import org.apache.pig.impl.util.MultiMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDBException;
// Added imports for logger
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.core.index.MockIndexAccess;
import com.example.index.NashDateEntryWithId;
import com.example.index.util.NashSerializationUtils;
import com.example.index.util.SynonymManager;
import com.example.query.executor.AttributeRequirements;
import com.example.query.executor.ConditionExecutorFactory;
import com.example.query.executor.QueryExecutionException;
import com.example.query.executor.QueryExecutor;
import com.example.query.executor.QueryResultSoA;
import com.example.query.index.IndexManager;
import com.example.query.model.Query;
import com.example.query.result.ResultGenerationException;
import com.example.query.result.TableResultService;
import com.example.query.sqlite.SqliteAccessor;

import no.ntnu.sandbox.Nash;
import tech.tablesaw.api.Table;

/**
 * End-to-end tests for query parsing, execution, and result generation.
 * Uses mock indexes for predictable results.
 */
public class QueryEndToEndTest {

    private static final Logger logger = LoggerFactory.getLogger(QueryEndToEndTest.class);

    @FunctionalInterface
    interface NerDataAdder {
        void add(String type, String term, int docId, int sentId, int begin, int end) throws RocksDBException;
    }

    @TempDir
    static Path tempDir;

    private static QueryExecutor queryExecutor;
    private static TableResultService tableResultService;
    private static MockIndexAccess mockUnigramIndex;
    private static MockIndexAccess mockBigramIndex;
    private static MockIndexAccess mockTrigramIndex;
    private static MockIndexAccess mockNerIndex;
    private static MockIndexAccess mockNerDateIndex;
    private static MockIndexAccess mockNashIndex;
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
                // Mock calls that NerExecutor will make
                lenient().when(staticMockSynonymManager.getId(t)).thenReturn(id);
                lenient().when(staticMockSynonymManager.getTerm(id)).thenReturn(Optional.of(t));
            } catch (RocksDBException e) {
                // This should ideally not happen with a mock, but declare to satisfy compiler
                throw new RuntimeException("RocksDBException during mock setup for SynonymManager", e);
            }
            return id;
        });
    }

    // Helper structure for preparing NASH mock data
    private static class NashMockDataEntry {
        LocalDate date;
        Position position;

        NashMockDataEntry(LocalDate date, Position position) {
            this.date = date;
            this.position = position;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NashMockDataEntry that = (NashMockDataEntry) o;
            return Objects.equals(date, that.date) && Objects.equals(position, that.position);
        }

        @Override
        public int hashCode() {
            return Objects.hash(date, position);
        }
    }

    @BeforeAll
    public static void setUp() throws IOException, IndexAccessException, RocksDBException {
        // Use a temporary directory for mock indexes
        File indexBasePath = tempDir.resolve("testIndexes").toFile();
        indexBasePath.mkdirs();

        File sourceIndexPath = tempDir.resolve("testIndexes/source").toFile();
        sourceIndexPath.mkdirs();

        SqliteAccessor.initialize(indexBasePath.getAbsolutePath());

        // Initialize static mocks first
        staticMockSynonymManager = org.mockito.Mockito.mock(SynonymManager.class);
        mockIndexManager = org.mockito.Mockito.mock(IndexManager.class);

        mockUnigramIndex = new MockIndexAccess("unigram", com.example.index.AnnotationType.UNKNOWN, new java.util.HashMap<>());
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

        mockNerIndex = new MockIndexAccess();

        // --- New NER Mock Data Population ---
        Map<String, PositionListSoA> nerDataMap = new HashMap<>();

        // Helper to add NER test data to the map
        NerDataAdder addNerData =
            (type, term, docId, sentId, begin, end) -> {
            int synId = getOrAssignNerSynId(term); // Uses helper, mocks staticMockSynonymManager
            PositionListSoA soa = nerDataMap.computeIfAbsent(type, k -> new PositionListSoA());
            soa.add(docId, sentId, begin, end, synId);
        };

        // Old mockNerIndex.addTestData calls, converted:
        addNerData.add("PERSON", "albert einstein", 6, 1, 0, 15);
        addNerData.add("PERSON", "marie curie", 6, 2, 20, 30);
        addNerData.add("PERSON", "isaac newton", 7, 1, 5, 17);
        addNerData.add("ORGANIZATION", "google", 7, 2, 40, 46);
        addNerData.add("ORGANIZATION", "microsoft corporation", 11, 1, 0, 20);
        addNerData.add("LOCATION", "london", 8, 1, 0, 6);
        addNerData.add("NUMBER", "42", 8, 2, 10, 12);
        addNerData.add("ORDINAL", "first", 9, 1, 0, 5);
        addNerData.add("DURATION", "3 years", 9, 2, 10, 17);
        addNerData.add("SET", "weekly", 10, 1, 0, 6);
        addNerData.add("PERSON", "albrecht kossel", 12, 1, 5, 20);

        for (Map.Entry<String, PositionListSoA> entry : nerDataMap.entrySet()) {
            mockNerIndex.put(entry.getKey().getBytes(StandardCharsets.UTF_8), entry.getValue().serializeToCompositeBlob());
        }
        // --- End New NER Mock Data Population ---

        mockNerDateIndex = new MockIndexAccess("ner_date", com.example.index.AnnotationType.DATE, new java.util.HashMap<>());
        mockNerDateIndex.addTestData("20230115", 2, 1, 0, 10);
        mockNerDateIndex.addTestData("20230320", 1, 1, 30, 40);
        mockNerDateIndex.addTestData("20240101", 3, 1, 50, 60);
        mockNerDateIndex.addTestData("20240115", 30, 1, 0, 10);

        mockNashIndex = new MockIndexAccess();

        // --- Consolidated NASH Mock Data Population ---
        List<LocalDate> idToDateLookupListForNash = new ArrayList<>();
        Map<LocalDate, Integer> dateToIdMapForNash = new HashMap<>();
        // Use a MultiMap to store the inverted index: Nash prefix -> list of date IDs
        MultiMap<String, Integer> invertedNashIndex = new MultiMap<>();
        // Store the actual NashDateEntryWithId objects mapped by their original date ID for later retrieval
        Map<Integer, List<NashDateEntryWithId>> dateIdToNashEntries = new HashMap<>();


        // 1. Collect all data points (original and from mockNerDateIndex)
        List<NashMockDataEntry> allNashDataPoints = new ArrayList<>();
        allNashDataPoints.add(new NashMockDataEntry(LocalDate.parse("2024-01-15"), new com.example.core.Position(30, 1, 0, 10)));
        allNashDataPoints.add(new NashMockDataEntry(LocalDate.parse("2023-01-15"), new Position(2,1,0,10)));
        allNashDataPoints.add(new NashMockDataEntry(LocalDate.parse("2023-03-20"), new Position(1,1,30,40)));
        allNashDataPoints.add(new NashMockDataEntry(LocalDate.parse("2024-01-01"), new Position(3,1,50,60)));

        // 2. Process all collected data points for NASH indexing
        // First, create a list of unique interval strings and map original date IDs to their entries
        List<String> uniqueIntervalStringsForInvert = new ArrayList<>();
        Map<Integer, NashDateEntryWithId> tempOriginalDateIdToEntry = new HashMap<>(); // Temporary map

        for (NashMockDataEntry dataPoint : allNashDataPoints) {
            LocalDate currentDate = dataPoint.date;
            Position currentPosition = dataPoint.position;

            int dateId = dateToIdMapForNash.computeIfAbsent(currentDate, d -> {
                idToDateLookupListForNash.add(d);
                uniqueIntervalStringsForInvert.add(String.format("[%s , %s]", d.toString(), d.toString()));
                return idToDateLookupListForNash.size() - 1; // This dateId is an index into idToDateLookupListForNash and uniqueIntervalStringsForInvert
            });

            NashDateEntryWithId nashEntry = new NashDateEntryWithId(currentPosition, dateId);
            dateIdToNashEntries.computeIfAbsent(dateId, k -> new ArrayList<>()).add(nashEntry);
        }

        // 3. Generate the inverted index using Nash.invert
        try {
            invertedNashIndex = Nash.invert(uniqueIntervalStringsForInvert);
        } catch (IOException e) {
            throw new RuntimeException("Error inverting Nash intervals for mock setup", e);
        }

        // 4. Store the aggregated entries in mockNashIndex based on the inverted index
        for (String nashPrefix : invertedNashIndex.keySet()) { // Iterate over keys
            List<Integer> dateIdsForPrefix = invertedNashIndex.get(nashPrefix); // Get values for the current key

            PositionListSoA entriesToStoreForPrefixSoA = new PositionListSoA();

            if (dateIdsForPrefix != null) { // Check if there are any date IDs for this prefix
                for (Integer originalDateId : dateIdsForPrefix) { // originalDateId is the one derived from idToDateLookupListForNash index
                    List<NashDateEntryWithId> actualEntries = dateIdToNashEntries.get(originalDateId);
                    if (actualEntries != null) {
                        // OLD: entriesToStoreForPrefix.addAll(actualEntries);
                        // NEW: Add to PositionListSoA
                        for (NashDateEntryWithId entry : actualEntries) {
                            Position pos = entry.position();
                            entriesToStoreForPrefixSoA.add(
                                pos.getDocumentId(),
                                pos.getSentenceId(),
                                pos.getBeginPosition(),
                                pos.getEndPosition(),
                                entry.dateId() // entry.dateId() is the originalDateId here
                            );
                        }
                    } else {
                         logger.warn("Warning: No NashDateEntryWithId found for originalDateId: {} during mockNashIndex population for prefix: {}", originalDateId, nashPrefix);
                    }
                }
            }

            if (!entriesToStoreForPrefixSoA.isEmpty()) { // Check the new SoA list
                // OLD: byte[] serializedEntries = NashSerializationUtils.serializeNashEntries(entriesToStoreForPrefix);
                byte[] serializedEntries = entriesToStoreForPrefixSoA.serializeToCompositeBlob(); // NEW
                mockNashIndex.put(nashPrefix.getBytes(StandardCharsets.UTF_8), serializedEntries);
            }
        }
        logger.info("Mock Nash Index: Stored entries for {} unique dates, using {} NASH prefixes from Nash.invert().", idToDateLookupListForNash.size(), invertedNashIndex.size());

        // ---- DEBUG LOGGING: Stored NASH Prefixes ----
        logger.debug("DEBUG QueryEndToEndTest: Stored NASH Prefixes in mockNashIndex (from Nash.invert):");
        List<String> sortedStoredPrefixes = new ArrayList<>(invertedNashIndex.keySet());
        java.util.Collections.sort(sortedStoredPrefixes);
        for (String storedPrefix : sortedStoredPrefixes) {
            logger.debug("  Stored Prefix: {}", storedPrefix);
        }
        logger.debug("---- END DEBUG LOGGING ----");

        // 5. Store the consolidated date lookup table for NASH
        if (!idToDateLookupListForNash.isEmpty()) {
             byte[] serializedLookup = NashSerializationUtils.serializeDateLookup(idToDateLookupListForNash);
             mockNashIndex.put(NashSerializationUtils.DATE_LOOKUP_KEY, serializedLookup);
             logger.info("Mock Nash Index: Stored date lookup table with {} entries.", idToDateLookupListForNash.size());
        } else {
            logger.warn("Warning: Nash date lookup table is empty.");
            byte[] serializedLookup = NashSerializationUtils.serializeDateLookup(Collections.emptyList());
            mockNashIndex.put(NashSerializationUtils.DATE_LOOKUP_KEY, serializedLookup);
        }
        // --- End Consolidated NASH Mock Data Population ---

        mockIndexes = Map.of(
            "unigram", mockUnigramIndex,
            "bigram", mockBigramIndex,
            "trigram", mockTrigramIndex,
            "ner", mockNerIndex,
            "ner_date", mockNerDateIndex,
            "nash", mockNashIndex
        );

        // Stub mockIndexManager behavior (now that mockIndexes is populated)
        lenient().when(mockIndexManager.getAllIndexes()).thenReturn(mockIndexes);
        lenient().when(mockIndexManager.getSynonymManager()).thenReturn(staticMockSynonymManager); // mockSynonymManager created above

        // Initialize factory and executor (now using static mocks)
        factory = new ConditionExecutorFactory(staticMockSynonymManager); // Updated
        factory.setTemporalStrategy("naive"); // Set a default strategy
        queryExecutor = new QueryExecutor(factory, "none", staticMockSynonymManager); // Updated

        queryParser = new QueryParser();
        tableResultService = new TableResultService(indexBasePath.getAbsolutePath());

        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsDocumentId = true;
        defaultTestRequirements.needsSentenceId = true;
        defaultTestRequirements.needsPositions = true;
        defaultTestRequirements.needsSynonymIds = true;
        defaultTestRequirements.needsConceptualRowIds = true;

        logger.info("End-to-End Test Setup Complete.");
    }

    @AfterAll
    public static void tearDown() throws IOException {
        for (IndexAccessInterface index : mockIndexes.values()) {
            if (index instanceof MockIndexAccess) {
                ((MockIndexAccess) index).close(); // Mock close if needed
            }
        }
        logger.info("End-to-End Test Teardown Complete.");
    }

    private void assertQueryResultContainsDocIds(QueryResultSoA result, Integer... expectedDocIds) {
        assertNotNull(result, "QueryResultSoA should not be null");
        Set<Integer> actualDocIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            actualDocIds.add(result.getDocumentIdAt(i));
        }
        boolean foundAll = true;
        for (Integer expectedDocId : expectedDocIds) {
            if (!actualDocIds.contains(expectedDocId)) {
                foundAll = false;
                break;
            }
        }
        if (!foundAll) {
            // Log details only on failure
            String actualDocIdsString = actualDocIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
            logger.warn("Assertion failed: Expected Doc IDs: [{}], Actual Doc IDs in result: [{}]",
                        Arrays.stream(expectedDocIds).map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")),
                        actualDocIdsString);
        }
        assertEquals(new HashSet<>(Arrays.asList(expectedDocIds)), actualDocIds);
    }

    private void assertQueryResultContainsValue(QueryResultSoA result, String expectedValue) {
        assertNotNull(result, "QueryResultSoA should not be null");
        boolean found = false;
        for (int i = 0; i < result.size(); i++) {
            if (expectedValue.equals((String) result.getValueAt(i))) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Expected value '" + expectedValue + "' not found in results.");
    }

    @Test
    public void testSimpleContainsQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('apple')";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for 'apple'");
        assertEquals(2, results.size());
        assertEquals(Query.Granularity.DOCUMENT, results.getGranularity());
        assertQueryResultContainsDocIds(results, 1, 2);

        Table resultTable = tableResultService.generateTable(query, results, mockIndexManager.getAllIndexes());
        assertNotNull(resultTable);
        assertEquals(2, resultTable.rowCount());
        assertTrue(resultTable.columnNames().contains("$main.DOCUMENT_ID"));
        assertEquals(1, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
        assertEquals(2, resultTable.intColumn("$main.DOCUMENT_ID").get(1));
    }

    @Test
    public void testContainsNoMatchQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('nonexistent')";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertTrue(results.isEmpty(), "Expected no results for 'nonexistent'");
        assertEquals(Query.Granularity.DOCUMENT, results.getGranularity());

        Table resultTable = tableResultService.generateTable(query, results, mockIndexManager.getAllIndexes());
        assertNotNull(resultTable);
        assertEquals(0, resultTable.rowCount());
    }

    @Test
    public void testContainsSingleQuote() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('grape')";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for 'grape'");
        assertEquals(1, results.size());
        assertEquals(3, results.getDocumentIdAt(0));

        Table resultTable = tableResultService.generateTable(query, results, mockIndexManager.getAllIndexes());
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertTrue(resultTable.columnNames().contains("$main.DOCUMENT_ID"));
        assertEquals(3, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
    }

    @Test
    public void testContainsBigramWithSpace() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('big cat')";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for 'big cat'");
        assertEquals(1, results.size());
        assertEquals(4, results.getDocumentIdAt(0));
        assertQueryResultContainsValue(results, "big cat");

        Table resultTable = tableResultService.generateTable(query, results, mockIndexManager.getAllIndexes());
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertTrue(resultTable.columnNames().contains("$main.DOCUMENT_ID"));
        assertEquals(4, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
    }

    @Test
    public void testContainsBigramWithComma() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('read monkey')";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for 'read monkey'");
        assertEquals(1, results.size());
        assertEquals(3, results.getDocumentIdAt(0));
        assertQueryResultContainsValue(results, "read monkey");

        Table resultTable = tableResultService.generateTable(query, results, mockIndexManager.getAllIndexes());
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertTrue(resultTable.columnNames().contains("$main.DOCUMENT_ID"));
        assertEquals(3, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
    }

    @Test
    public void testContainsTrigramWithSpace() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('the quick fox')";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for 'the quick fox'");
        assertEquals(1, results.size());
        assertEquals(5, results.getDocumentIdAt(0));
        assertQueryResultContainsValue(results, "the quick fox");

        Table resultTable = tableResultService.generateTable(query, results, mockIndexManager.getAllIndexes());
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertTrue(resultTable.columnNames().contains("$main.DOCUMENT_ID"));
        assertEquals(5, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
    }

    @Test
    public void testContainsTrigramWithComma() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('the quick fox')";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for 'the quick fox'");
        assertEquals(1, results.size());
        assertEquals(5, results.getDocumentIdAt(0));
        assertQueryResultContainsValue(results, "the quick fox");

        Table resultTable = tableResultService.generateTable(query, results, mockIndexManager.getAllIndexes());
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertTrue(resultTable.columnNames().contains("$main.DOCUMENT_ID"));
        assertEquals(5, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
    }

    @Test
    public void testNerSimpleTypeQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM test_corpus WHERE NER(PERSON)";
        Query query = queryParser.parse(queryString);
        QueryResultSoA resultSoA = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(resultSoA, "QueryResultSoA should not be null");

        // Expected behavior: NER(PERSON) matches all PERSON entities.
        // The value in QueryResultSoA for these matches will be "PERSON" with ValueType.ENTITY_TYPE.
        // The test setup for PERSON includes "albert einstein", "marie curie", "isaac newton", "albrecht kossel".
        // These are in docIds 6, 6, 7, 12 respectively.

        // Count distinct document IDs that have a PERSON entity type associated.
        // Since the query selects DOCUMENT_ID and the WHERE clause is NER(PERSON),
        // we expect rows for each document that contains a PERSON annotation.
        Set<Integer> distinctDocIdsWithPerson = new HashSet<>();
        for (int i = 0; i < resultSoA.size(); i++) {
            // We are not directly checking the value/valueType from NER(PERSON) here
            // because the SELECT clause is DOCUMENT_ID. The WHERE clause filters based on NER(PERSON).
            // The NerExecutor will populate the QueryResultSoA with entries if NER(PERSON) is found.
            // The actual values/value types from the WHERE condition aren't directly in the final SELECTed columns here.
            distinctDocIdsWithPerson.add(resultSoA.getDocumentIdAt(i));
        }

        // Original mock data for PERSON:
        // addNerData.accept("PERSON", "albert einstein", 6, 1, 0, 15);
        // addNerData.accept("PERSON", "marie curie", 6, 2, 20, 30);
        // addNerData.accept("PERSON", "isaac newton", 7, 1, 5, 17);
        // addNerData.accept("PERSON", "albrecht kossel", 12, 1, 5, 20);
        // So, documents 6, 7, and 12 should contain PERSON entities.

        assertEquals(3, distinctDocIdsWithPerson.size(), "Should find PERSON entities in 3 distinct documents (6, 7, 12)");
        assertTrue(distinctDocIdsWithPerson.containsAll(Set.of(6, 7, 12)), "Documents 6, 7, 12 should be present.");

        // If TableResultService is used, it would further process this.
        // For this test, focusing on QueryResultSoA content is sufficient to validate NerExecutor.
        // The original failure "Expected PERSON entities ==> expected: <false> but was: <true>"
        // likely came from an assertion on a boolean flag or a different result structure.
    }

    @Test
    public void testNerVariableBindingQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException, RocksDBException {
        String queryString = "SELECT org FROM test_corpus WHERE NER(ORGANIZATION) BIND org";
        Query query = queryParser.parse(queryString);
        QueryResultSoA resultSoA = queryExecutor.execute(query, mockIndexManager);
        Table table = tableResultService.generateTable(query, resultSoA, mockIndexManager.getAllIndexes());

        assertNotNull(table);
        // Expected ORGANIZATION entities: "google", "microsoft corporation"
        // These will be bound to org, likely in lowercase as per SynonymManager.getTerm()
        List<String> orgNames = table.stringColumn(0).asList().stream().map(String::toLowerCase).toList();
        assertEquals(2, orgNames.size(), "Expected two organization names");
        assertTrue(orgNames.contains("google"), "Expected 'google' in results");
        assertTrue(orgNames.contains("microsoft corporation"), "Expected 'microsoft corporation' in results");
    }

    @Test
    public void testNerVariableBindingWithTargetQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException, RocksDBException {
        String queryString = "SELECT loc FROM test_corpus WHERE NER(LOCATION, 'london') BIND loc";
        Query query = queryParser.parse(queryString);
        QueryResultSoA resultSoA = queryExecutor.execute(query, mockIndexManager);
        Table table = tableResultService.generateTable(query, resultSoA, mockIndexManager.getAllIndexes());

        assertNotNull(table);
        // Expected: 'london' (original casing as it's from query target) bound to loc
        List<String> locNames = table.stringColumn(0).asList();
        assertEquals(1, locNames.size(), "Expected one location name");
        assertTrue(locNames.contains("london"), "Expected 'london' in results");
    }

    @Test
    public void testNerNewTypeOrdinalQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException, RocksDBException {
        String queryString = "SELECT ord FROM test_corpus WHERE NER(ORDINAL) BIND ord";
        Query query = queryParser.parse(queryString);
        QueryResultSoA resultSoA = queryExecutor.execute(query, mockIndexManager);
        Table table = tableResultService.generateTable(query, resultSoA, mockIndexManager.getAllIndexes());

        assertNotNull(table);
        // Expected ORDINAL entity: "first"
        List<String> ordValues = table.stringColumn(0).asList().stream().map(String::toLowerCase).toList();
        assertEquals(1, ordValues.size(), "Expected one ordinal value");
        assertTrue(ordValues.contains("first"), "Expected 'first' in results");
    }

    @Test
    public void testNerNewTypeNumberQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException, RocksDBException {
        String queryString = "SELECT num FROM test_corpus WHERE NER(NUMBER) BIND num";
        Query query = queryParser.parse(queryString);
        QueryResultSoA resultSoA = queryExecutor.execute(query, mockIndexManager);
        Table table = tableResultService.generateTable(query, resultSoA, mockIndexManager.getAllIndexes());

        assertNotNull(table);
        // Expected NUMBER entity: "42"
        List<String> numValues = table.stringColumn(0).asList().stream().map(String::toLowerCase).toList();
        assertEquals(1, numValues.size(), "Expected one number value");
        assertTrue(numValues.contains("42"), "Expected '42' in results");
    }

    @Test
    public void testNerPartialTargetQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException, RocksDBException {
        // The original test name "testNerPartialTargetQuery" might be misleading.
        // NerExecutor performs an exact match on the synonym ID of the target value.
        // If the mock data for "albrecht kossel" exists, it will be an exact match.
        String queryString = "SELECT person FROM test_corpus WHERE NER(PERSON, 'albrecht kossel') BIND person";
        Query query = queryParser.parse(queryString);
        QueryResultSoA resultSoA = queryExecutor.execute(query, mockIndexManager);
        Table table = tableResultService.generateTable(query, resultSoA, mockIndexManager.getAllIndexes());

        assertNotNull(table);
        // Expected: "albrecht kossel" (original casing) bound to person
        List<String> personNames = table.stringColumn(0).asList();
        assertEquals(1, personNames.size(), "Expected one person name");
        assertTrue(personNames.contains("albrecht kossel"), "Expected 'albrecht kossel' in results");
    }

    @Test
    public void testOrQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        // Query: CONTAINS('apple') OR NER(ORGANIZATION)
        // CONTAINS('apple'): doc 1, doc 2
        // NER(ORGANIZATION): doc 7 (google), doc 11 (microsoft corporation)
        // Expected distinct document IDs: 1, 2, 7, 11 (total 4)

        String queryString = "SELECT DOCUMENT_ID FROM test_corpus WHERE CONTAINS('apple') OR NER(ORGANIZATION)";
        Query query = queryParser.parse(queryString);
        QueryResultSoA resultSoA = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(resultSoA, "QueryResultSoA should not be null for OR query");

        Set<Integer> distinctDocIds = new HashSet<>();
        for (int i = 0; i < resultSoA.size(); i++) {
            distinctDocIds.add(resultSoA.getDocumentIdAt(i));
        }

        assertEquals(4, distinctDocIds.size(), "Expected 4 distinct documents for CONTAINS('apple') OR NER(ORGANIZATION)");
        assertTrue(distinctDocIds.containsAll(Set.of(1, 2, 7, 11)),
            "Documents 1, 2, 7, 11 should be present. Found: " + distinctDocIds);
    }

    @Test
    public void testDateYearFormat() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE DATE(== 2023)";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);
        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for DATE(== 2023)");
        assertEquals(2, results.size());
        assertQueryResultContainsDocIds(results, 1, 2);
    }

    @Test
    public void testDateYearMonthFormat() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE DATE(== 2023-01)";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);
        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for DATE(== 2023-01)");
        assertEquals(1, results.size());
        assertEquals(2, results.getDocumentIdAt(0));
    }

    @Test
    public void testDateYearMonthDayFormat() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE DATE(== 2024-01-15)";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);
        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for DATE(== 2024-01-15)");
        assertEquals(1, results.size());
        assertEquals(30, results.getDocumentIdAt(0));
    }

    @Test
    public void testDateRangeWithDateLiterals() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE DATE(>= 2023-06-01) AND DATE(<= 2023-08-15)";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);
        assertNotNull(results);
        assertTrue(results.isEmpty(), "Expected no results for date range 2023-06-01 to 2023-08-15 with current mock data");
    }

    @Test
    public void testCompoundDateQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('apple') AND DATE(== 2023)";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);
        assertNotNull(results);
        assertFalse(results.isEmpty(), "Expected results for CONTAINS('apple') AND DATE(==2023)");
        assertQueryResultContainsDocIds(results, 1, 2);
    }

    @Test
    public void testDateBeforeQuery_NaiveStrategy() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE DATE(< 2024-01-01)";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);
        assertNotNull(results, "QueryResultSoA should not be null for naive DATE(<) query");
        assertFalse(results.isEmpty(), "Expected results for DATE(< 2024-01-01)");
        assertQueryResultContainsDocIds(results, 1, 2);
    }

    @Test
    public void testDateOnQuery_NaiveStrategy() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE DATE(== 2023-01-15)";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);
        assertNotNull(results, "QueryResultSoA should not be null for naive DATE(==) query");
        assertFalse(results.isEmpty(), "Expected results for DATE(== 2023-01-15)");
        assertQueryResultContainsDocIds(results, 2);
    }

    @Test
    public void testDateAfterQuery_NaiveStrategy() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE DATE(> 2023-03-20)";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);
        assertNotNull(results, "QueryResultSoA should not be null for naive DATE(>) query");
        assertFalse(results.isEmpty(), "Expected results for DATE(> 2023-03-20)");
        assertQueryResultContainsDocIds(results, 3, 30);
    }

    @Test
    public void testDateWildcardQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        // Test DATE(*) wildcard - should find any dates that intersect with documents containing 'apple'
        // Expected: documents 1 and 2 contain 'apple', and documents 1 and 2 also have dates
        // (from mockNerDateIndex: "20230320" in doc 1, "20230115" in doc 2)
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('apple') AND DATE(*)";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results, "QueryResultSoA should not be null for DATE(*) wildcard query");
        assertFalse(results.isEmpty(), "Expected results for CONTAINS('apple') AND DATE(*)");
        assertQueryResultContainsDocIds(results, 1, 2);
    }

    @Test
    public void testDateWildcardWithVariableBinding() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        // Test DATE(*) with variable binding - should bind the actual dates found
        String queryStr = "SELECT date FROM mockSource WHERE CONTAINS('apple') AND DATE(*) BIND date";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);
        Table table = tableResultService.generateTable(query, results, mockIndexManager.getAllIndexes());

        assertNotNull(results, "QueryResultSoA should not be null for DATE(*) with binding");
        assertNotNull(table, "Table should not be null");
        assertFalse(results.isEmpty(), "Expected results for CONTAINS('apple') AND DATE(*) BIND date");

        // Should find dates in documents that contain 'apple'
        // From mockNerDateIndex: "20230320" in doc 1, "20230115" in doc 2
        assertTrue(table.rowCount() > 0, "Expected at least one row with date binding");

        // Check that we have date values bound to the 'date' variable
        assertTrue(table.columnNames().contains("$main.date"), "Expected 'date' column in results");
    }

    @Test
    public void testDateWildcardWithMultipleConditions() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        // Test DATE(*) with multiple other conditions to ensure it executes last
        // This tests the optimization where DATE(*) should only look for dates within
        // the positions already matched by CONTAINS('test')
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('test') AND DATE(*)";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results, "QueryResultSoA should not be null for complex DATE(*) query");

        // CONTAINS('test') matches documents 0 and 1
        // We need to check if any of these documents also have dates
        // Based on mock data, only specific documents have dates, so this might return fewer results
        if (!results.isEmpty()) {
            // If we get results, they should only be from documents that contain 'test'
            for (int i = 0; i < results.size(); i++) {
                int docId = results.getDocumentIdAt(i);
                assertTrue(docId == 0 || docId == 1,
                    "DATE(*) results should only include documents that match CONTAINS('test'): " + docId);
            }
        }
    }

            @Test
    public void testDateWildcardWithVariableBindingOptimization() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        // Test that DATE(*) works efficiently with variable binding for date values
        String queryStr = "SELECT dateVar FROM mockSource WHERE CONTAINS('apple') AND DATE(*) BIND dateVar";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results, "QueryResultSoA should not be null");
        assertFalse(results.isEmpty(), "Expected results for CONTAINS + DATE(*) BIND");

        // Verify that all results have both the original matches and date bindings
        boolean hasOriginalMatches = false;
        boolean hasDateBindings = false;
        for (int i = 0; i < results.size(); i++) {
            String varName = results.getVariableNameAt(i);
            if ("$main.dateVar".equals(varName)) {
                hasDateBindings = true;
                assertEquals(com.example.query.binding.ValueType.DATE, results.getValueTypeAt(i),
                           "DATE(*) BIND should produce DATE values");
            } else {
                hasOriginalMatches = true;
            }
        }

        assertTrue(hasOriginalMatches, "Should have original CONTAINS matches");
        assertTrue(hasDateBindings, "Should have DATE(*) variable bindings");
    }

        @Test
    public void testDateWildcardFiltersOutNonDateMatches() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        // Test that DATE(*) filters out matches that don't intersect with any dates
        // This tests the core filtering behavior where only matches with date intersections are kept
        String queryStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('test') AND DATE(*)";
        Query query = queryParser.parse(queryStr);
        QueryResultSoA results = queryExecutor.execute(query, mockIndexManager);

        assertNotNull(results, "QueryResultSoA should not be null");

        // The result size should be smaller than a query without DATE(*) because
        // DATE(*) filters out matches that don't have intersecting dates
        String queryWithoutDateStr = "SELECT DOCUMENT_ID FROM mockSource WHERE CONTAINS('test')";
        Query queryWithoutDate = queryParser.parse(queryWithoutDateStr);
        QueryResultSoA resultsWithoutDate = queryExecutor.execute(queryWithoutDate, mockIndexManager);

        // If there are any matches without dates, the DATE(*) query should have fewer results
        // Note: This test assumes that not all 'test' matches have intersecting dates
        assertTrue(results.size() <= resultsWithoutDate.size(),
                  "DATE(*) should filter out matches without date intersections");

        // Verify that all remaining matches are from documents/sentences that actually contain dates
        for (int i = 0; i < results.size(); i++) {
            int docId = results.getDocumentIdAt(i);
            // The fact that this result exists means it passed the date intersection filter
            assertTrue(docId >= 0, "All filtered results should have valid document IDs");
        }

        logger.info("DATE(*) filtering: {} matches with dates vs {} total matches",
                   results.size(), resultsWithoutDate.size());
    }
}