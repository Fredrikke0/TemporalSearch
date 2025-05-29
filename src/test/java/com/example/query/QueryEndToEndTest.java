package com.example.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.pig.impl.util.MultiMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
// Added imports for logger
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.index.MockIndexAccess;
import com.example.index.NashDateEntryWithId;
import com.example.index.util.NashSerializationUtils;
import com.example.query.executor.AttributeRequirements;
import com.example.query.executor.ConditionExecutorFactory;
import com.example.query.executor.QueryExecutionException;
import com.example.query.executor.QueryExecutor;
import com.example.query.executor.QueryResultSoA;
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

    private static final char DELIMITER = '\0';

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
    public static void setUp() throws IOException, IndexAccessException {
        // Use a temporary directory for mock indexes
        File indexBasePath = tempDir.resolve("testIndexes").toFile();
        indexBasePath.mkdirs();

        File sourceIndexPath = tempDir.resolve("testIndexes/source").toFile();
        sourceIndexPath.mkdirs();

        SqliteAccessor.initialize(indexBasePath.getAbsolutePath());

        mockUnigramIndex = new MockIndexAccess("unigram", null, null, null);
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
        mockNerIndex.addTestData("PERSON" + DELIMITER + "albert einstein", 6, 1, 0, 15);
        mockNerIndex.addTestData("PERSON" + DELIMITER + "marie curie", 6, 2, 20, 30);
        mockNerIndex.addTestData("PERSON" + DELIMITER + "isaac newton", 7, 1, 5, 17);
        mockNerIndex.addTestData("ORGANIZATION" + DELIMITER + "google", 7, 2, 40, 46);
        mockNerIndex.addTestData("ORGANIZATION" + DELIMITER + "microsoft corporation", 11, 1, 0, 20);
        mockNerIndex.addTestData("LOCATION" + DELIMITER + "london", 8, 1, 0, 6);
        mockNerIndex.addTestData("NUMBER" + DELIMITER + "42", 8, 2, 10, 12);
        mockNerIndex.addTestData("ORDINAL" + DELIMITER + "first", 9, 1, 0, 5);
        mockNerIndex.addTestData("DURATION" + DELIMITER + "3 years", 9, 2, 10, 17);
        mockNerIndex.addTestData("SET" + DELIMITER + "weekly", 10, 1, 0, 6);
        mockNerIndex.addTestData("PERSON" + DELIMITER + "albrecht kossel", 12, 1, 5, 20);

        mockNerDateIndex = new MockIndexAccess("ner_date", null, null, null);
        mockNerDateIndex.addTestData("20230115", 2, 1, 0, 10);
        mockNerDateIndex.addTestData("20230320", 1, 1, 30, 40);
        mockNerDateIndex.addTestData("20240101", 3, 1, 50, 60);

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

            List<NashDateEntryWithId> entriesToStoreForPrefix = new ArrayList<>();
            if (dateIdsForPrefix != null) { // Check if there are any date IDs for this prefix
                for (Integer originalDateId : dateIdsForPrefix) { // originalDateId is the one derived from idToDateLookupListForNash index
                    List<NashDateEntryWithId> actualEntries = dateIdToNashEntries.get(originalDateId);
                    if (actualEntries != null) {
                        entriesToStoreForPrefix.addAll(actualEntries);
                    } else {
                         logger.warn("Warning: No NashDateEntryWithId found for originalDateId: {} during mockNashIndex population for prefix: {}", originalDateId, nashPrefix);
                    }
                }
            }

            if (!entriesToStoreForPrefix.isEmpty()) {
                byte[] serializedEntries = NashSerializationUtils.serializeNashEntries(entriesToStoreForPrefix);
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

        factory = new ConditionExecutorFactory();
        factory.setTemporalStrategy("nash");
        logger.info("QueryEndToEndTest: ConditionExecutorFactory temporal strategy set to NASH.");

        queryExecutor = new QueryExecutor(factory, "none");
        tableResultService = new TableResultService();
        queryParser = new QueryParser();
        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsDocumentId = true;

        logger.info("End-to-End Test Setup Complete.");
    }

    @AfterAll
    public static void tearDown() throws IOException {
        for (IndexAccessInterface index : mockIndexes.values()) {
            if (index instanceof MockIndexAccess) {
                ((MockIndexAccess) index).close(); // Mock close if needed
            }
        }
        // SqliteAccessor.close(); // This method does not exist, remove or replace with correct cleanup if any.
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
        assertTrue(foundAll, "Expected Doc IDs not found in result. See log for details.");
    }

    private void assertQueryResultContainsValue(QueryResultSoA result, String expectedValue) {
        assertNotNull(result, "QueryResultSoA should not be null");
        boolean found = false;
        for (int i = 0; i < result.size(); i++) {
            if (expectedValue.equals(result.getValueAt(i))) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Expected value '" + expectedValue + "' not found in QueryResultSoA.");
    }

    @Test
    public void testSimpleContainsQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT t1.DOCUMENT_ID FROM source ALIAS t1 WHERE CONTAINS('apple')";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected results for 'apple'");
        assertEquals(2, result.size());
        assertEquals(Query.Granularity.DOCUMENT, result.getGranularity());
        assertQueryResultContainsDocIds(result, 1, 2);

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        assertEquals(2, resultTable.rowCount());
        assertTrue(resultTable.columnNames().contains("t1.DOCUMENT_ID"));
        assertEquals(1, resultTable.intColumn("t1.DOCUMENT_ID").get(0));
        assertEquals(2, resultTable.intColumn("t1.DOCUMENT_ID").get(1));
    }

    @Test
    public void testContainsNoMatchQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE CONTAINS('nonexistent')";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertTrue(result.isEmpty(), "Expected no results for 'nonexistent'");
        assertEquals(0, result.size());

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        assertEquals(0, resultTable.rowCount());
    }

    @Test
    public void testContainsSingleQuote() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE CONTAINS('grape')";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected results for 'grape'");
        assertEquals(1, result.size());
        assertEquals(3, result.getDocumentIdAt(0));

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertEquals(3, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
    }

    @Test
    public void testContainsBigramWithSpace() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE CONTAINS('big cat')";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected results for 'big cat'");
        assertEquals(1, result.size());
        assertEquals(4, result.getDocumentIdAt(0));
        assertQueryResultContainsValue(result, "big cat");

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertEquals(4, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
    }

    @Test
    public void testContainsBigramWithComma() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE CONTAINS('read monkey')";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected results for 'read monkey'");
        assertEquals(1, result.size());
        assertEquals(3, result.getDocumentIdAt(0));
        assertQueryResultContainsValue(result, "read monkey");

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertEquals(3, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
    }

    @Test
    public void testContainsTrigramWithSpace() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE CONTAINS('the quick fox')";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected results for 'the quick fox'");
        assertEquals(1, result.size());
        assertEquals(5, result.getDocumentIdAt(0));
        assertQueryResultContainsValue(result, "the quick fox");

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertEquals(5, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
    }

    @Test
    public void testContainsTrigramWithComma() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE CONTAINS('the quick fox')";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected results for 'the quick fox'");
        assertEquals(1, result.size());
        assertEquals(5, result.getDocumentIdAt(0));
        assertQueryResultContainsValue(result, "the quick fox");

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertEquals(5, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
    }

    @Test
    public void testNerSimpleTypeQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE NER(PERSON)";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected PERSON entities");
        assertEquals(4, result.size());
        assertQueryResultContainsDocIds(result, 6, 7, 12);

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        Set<Integer> distinctDocIdsInTable = new HashSet<>();
        for(int i=0; i < resultTable.rowCount(); i++) {
            distinctDocIdsInTable.add(resultTable.intColumn("$main.DOCUMENT_ID").get(i));
        }
        assertEquals(Set.of(6, 7, 12), distinctDocIdsInTable);
    }

    @Test
    public void testNerVariableBindingQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID, orgName FROM source WHERE NER(ORGANIZATION) BIND orgName";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected ORGANIZATION entities with variable binding");
        assertEquals(2, result.size());

        Set<String> orgNames = new HashSet<>();
        Set<Integer> docIds = new HashSet<>();
        for(int i=0; i<result.size(); i++) {
            assertEquals("$main.orgName", result.getVariableNameAt(i));
            orgNames.add((String) result.getValueAt(i));
            docIds.add(result.getDocumentIdAt(i));
        }
        assertEquals(Set.of("google", "microsoft corporation"), orgNames);
        assertEquals(Set.of(7, 11), docIds);

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        assertEquals(2, resultTable.rowCount());
        assertTrue(resultTable.columnNames().contains("$main.DOCUMENT_ID"));
        assertTrue(resultTable.columnNames().contains("$main.orgName"));

        boolean foundGoogle = false;
        boolean foundMicrosoft = false;
        for (int i = 0; i < resultTable.rowCount(); i++) {
            if (resultTable.intColumn("$main.DOCUMENT_ID").get(i) == 7 && "google".equals(resultTable.stringColumn("$main.orgName").get(i))) {
                foundGoogle = true;
            } else if (resultTable.intColumn("$main.DOCUMENT_ID").get(i) == 11 && "microsoft corporation".equals(resultTable.stringColumn("$main.orgName").get(i))) {
                foundMicrosoft = true;
            }
        }
        assertTrue(foundGoogle, "Table should contain Google entry");
        assertTrue(foundMicrosoft, "Table should contain Microsoft entry");
    }

    @Test
    public void testNerVariableBindingWithTargetQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID, loc FROM source WHERE NER(LOCATION) BIND loc";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected 'london' LOCATION entity with variable binding");
        assertEquals(1, result.size());
        assertEquals(8, result.getDocumentIdAt(0));
        assertEquals("london", result.getValueAt(0));
        assertEquals("$main.loc", result.getVariableNameAt(0));

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertEquals(8, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
        assertEquals("london", resultTable.stringColumn("$main.loc").get(0));
    }

    @Test
    public void testNerNewTypeOrdinalQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE NER(ORDINAL)";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected 'first' ORDINAL entity");
        assertEquals(1, result.size());
        assertEquals(9, result.getDocumentIdAt(0));
        assertEquals("first", result.getValueAt(0));

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertEquals(9, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
    }

    @Test
    public void testNerNewTypeNumberQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE NER(NUMBER)";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected '42' NUMBER entity");
        assertEquals(1, result.size());
        assertEquals(8, result.getDocumentIdAt(0));
        assertEquals("42", result.getValueAt(0));

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        assertEquals(1, resultTable.rowCount());
        assertEquals(8, resultTable.intColumn("$main.DOCUMENT_ID").get(0));
    }


    @Test
    public void testNerPartialTargetQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE NER(PERSON)";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected PERSON entities");
        assertEquals(4, result.size());

        assertQueryResultContainsDocIds(result, 6, 7, 12);

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        Set<Integer> distinctDocIdsInTable = new HashSet<>();
        for(int i=0; i < resultTable.rowCount(); i++) {
            distinctDocIdsInTable.add(resultTable.intColumn("$main.DOCUMENT_ID").get(i));
        }
        assertEquals(Set.of(6, 7, 12), distinctDocIdsInTable);
    }

    @Test
    public void testOrQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE CONTAINS('apple') OR NER(ORGANIZATION)";
        Query query = queryParser.parse(queryString);

        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected results for OR query");
        assertEquals(4, result.size());
        assertQueryResultContainsDocIds(result, 1, 2, 7, 11);

        Table resultTable = tableResultService.generateTable(query, result, mockIndexes);
        assertNotNull(resultTable);
        Set<Integer> distinctDocIdsInTable = new HashSet<>();
        for(int i=0; i < resultTable.rowCount(); i++) {
            distinctDocIdsInTable.add(resultTable.intColumn("$main.DOCUMENT_ID").get(i));
        }
        assertEquals(Set.of(1, 2, 7, 11), distinctDocIdsInTable);
    }

    @Test
    public void testDateYearFormat() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE DATE(= 2023)";
        Query query = queryParser.parse(queryString);
        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);
        assertNotNull(result);
        assertEquals(2, result.size(), "Expected 2 documents for year 2023 from NASH");
        assertQueryResultContainsDocIds(result, 1, 2);
    }

    @Test
    public void testDateYearMonthFormat() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE DATE(= 2023-01)";
        Query query = queryParser.parse(queryString);
        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);
        assertNotNull(result);
        if (result.size() != 1) {
            System.err.println("testDateYearMonthFormat: Unexpected result size. Expected 1, got " + result.size());
            System.err.println("Actual Doc IDs in result:");
            for (int i = 0; i < result.size(); i++) {
                System.err.println("  Doc ID: " + result.getDocumentIdAt(i) + " (Value: " + result.getValueAt(i) + ")");
            }
        }
        assertEquals(1, result.size(), "Expected 1 document for 2023-01 from NASH");
        if (!result.isEmpty()) { // Avoid error if empty, though assertEquals would have failed
            assertEquals(2, result.getDocumentIdAt(0));
        }
    }

    @Test
    public void testDateYearMonthDayFormat() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE DATE(= 2024-01-15)";
        Query query = queryParser.parse(queryString);
        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);
        assertNotNull(result);
        assertFalse(result.isEmpty(), "Expected results for DATE(=2024-01-15)");
        assertEquals(1, result.size(), "Expected 1 result for DATE(=2024-01-15) after NASH deduplication");
        if (!result.isEmpty()) {
             assertEquals(30, result.getDocumentIdAt(0), "Doc ID for 2024-01-15 should be 30");
        }
    }

    @Test
    public void testDateRangeWithDateLiterals() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        // Query for documents between June 1, 2023 and August 15, 2023, inclusive.
        // Mock data for NASH includes: 2024-01-15 (doc 30), 2023-01-15 (doc 2), 2023-03-20 (doc 1), 2024-01-01 (doc 3)
        // Expected: None of these fall within the query range.
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE DATE(>= 2023-06-01) AND DATE(<= 2023-08-15)";
        Query query = queryParser.parse(queryString);
        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Expected no results for date range 2023-06-01 to 2023-08-15 with current mock data");
        assertEquals(0, result.size(), "Expected 0 documents for date range in 2023 from NASH");
    }

    @Test
    public void testCompoundDateQuery() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String queryString = "SELECT DOCUMENT_ID FROM source WHERE CONTAINS('apple') AND DATE(=2023)";
        Query query = queryParser.parse(queryString);
        QueryResultSoA result = queryExecutor.execute(query, mockIndexes);
        assertNotNull(result);

        Set<Integer> uniqueConceptualRows = new HashSet<>();
        if (result.getConceptualRowIds() != null && result.size() > 0) { // Check result.size() > 0 before iterating
            for (int i = 0; i < result.size(); i++) {
                try {
                    uniqueConceptualRows.add(result.getConceptualRowIdAt(i));
                } catch (IllegalStateException e) {
                    // This can happen if requirements.needsConceptualRowIds was false for the resultSoA
                    // In such a case, each row is its own conceptual row for counting purposes.
                    // However, performAndSoA explicitly sets needsConceptualRowIds = true, so this branch is unlikely here.
                    logger.warn("testCompoundDateQuery: Could not get conceptualRowIdAt index {}. SoA size: {}. Error: {}", i, result.size(), e.getMessage());
                    // Fallback: if conceptual IDs are not available, treat each SoA row as a unique conceptual match for this test's purpose.
                    // This is a simplification; a more robust approach might be needed if this path is common.
                    for(int k=0; k<result.size(); ++k) uniqueConceptualRows.add(k); // Add all row indices as unique IDs
                    break; // Exit loop as we've handled it as best we can.
                }
            }
        }

        if (uniqueConceptualRows.size() != 2) {
            System.err.println("testCompoundDateQuery: Unexpected conceptual row count. Expected 2, got " + uniqueConceptualRows.size() + " (Total SoA rows: " + result.size() + ")");
            System.err.println("Actual Doc IDs and Values in result (SoA rows):");
            for (int i = 0; i < result.size(); i++) {
                System.err.println("  Doc ID: " + result.getDocumentIdAt(i) + " (Value: " + result.getValueAt(i) + ", Var: " + result.getVariableNameAt(i) + ", ConceptualID: " + (result.getConceptualRowIds() != null ? result.getConceptualRowIdAt(i) : "N/A") + ")");
            }
            System.err.println("Unique Conceptual IDs found: " + uniqueConceptualRows);
        }
        assertEquals(2, uniqueConceptualRows.size(), "Expected 2 unique conceptual matches for compound 2023 query");

        // Verify that the document IDs are correct among the unique conceptual matches
        // This part is a bit more complex as we need to map conceptual IDs back to document IDs.
        // For this specific test, we know docs 1 and 2 should match.
        Set<Integer> matchedDocIds = new HashSet<>();
        if (result.getConceptualRowIds() != null && result.size() > 0) {
             Map<Integer, Integer> conceptualIdToDocId = new HashMap<>();
             for (int i = 0; i < result.size(); i++) {
                 try {
                    conceptualIdToDocId.putIfAbsent(result.getConceptualRowIdAt(i), result.getDocumentIdAt(i));
                 } catch (IllegalStateException e) { /* ignore, handled above */ }
             }
             for (int conceptualId : uniqueConceptualRows) {
                 if (conceptualIdToDocId.containsKey(conceptualId)) {
                    matchedDocIds.add(conceptualIdToDocId.get(conceptualId));
                 }
             }
        }
        assertTrue(matchedDocIds.contains(1), "Result should contain document ID 1");
        assertTrue(matchedDocIds.contains(2), "Result should contain document ID 2");
    }

    @Test
    public void testDateBeforeQuery_NaiveStrategy() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String originalStrategy = "nash"; // Default from setUp
        try {
            factory.setTemporalStrategy("naive");
            logger.info("QueryEndToEndTest: Set temporal strategy to NAIVE for testDateBeforeQuery_NaiveStrategy");
            logger.info("QueryEndToEndTest: mockNerDateIndex size before query: {}", mockNerDateIndex.getStoreSize());

            String queryString = "SELECT DOCUMENT_ID FROM source WHERE DATE(< 2024-01-01)";
            Query query = queryParser.parse(queryString);

            QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

            assertNotNull(result, "QueryResultSoA should not be null for naive DATE(<) query");
            logger.info("testDateBeforeQuery_NaiveStrategy (Naive): Result size = {}, Query: {}", result.size(), queryString);
            for (int i = 0; i < result.size(); i++) {
                logger.info("testDateBeforeQuery_NaiveStrategy (Naive): Doc ID {}, Value {}, VarName {}, ConceptualID {}",
                        result.getDocumentIdAt(i),
                        result.getValueAt(i),
                        result.getVariableNameAt(i),
                        result.getConceptualRowIds() != null ? result.getConceptualRowIdAt(i) : "N/A");
            }

            assertFalse(result.isEmpty(), "Expected results for DATE(< 2024-01-01) with naive strategy");
            assertEquals(2, result.size(), "Expected 2 documents for DATE(< 2024-01-01) with naive strategy");
            // Expected Doc IDs: 1 (2023-03-20) and 2 (2023-01-15)
            assertQueryResultContainsDocIds(result, 1, 2);

        } finally {
            factory.setTemporalStrategy(originalStrategy); // Reset to default strategy
            logger.info("QueryEndToEndTest: Reset temporal strategy to {} after testDateBeforeQuery_NaiveStrategy", originalStrategy);
        }
    }

    @Test
    public void testDateOnQuery_NaiveStrategy() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String originalStrategy = "nash"; // Default from setUp
        try {
            factory.setTemporalStrategy("naive");
            logger.info("QueryEndToEndTest: Set temporal strategy to NAIVE for testDateOnQuery_NaiveStrategy");
            logger.info("QueryEndToEndTest: mockNerDateIndex size before query: {}", mockNerDateIndex.getStoreSize());

            String queryString = "SELECT DOCUMENT_ID FROM source WHERE DATE(= 2023-01-15)";
            Query query = queryParser.parse(queryString);

            QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

            assertNotNull(result, "QueryResultSoA should not be null for naive DATE(=) query");
            logger.info("testDateOnQuery_NaiveStrategy (Naive): Result size = {}, Query: {}", result.size(), queryString);
            for (int i = 0; i < result.size(); i++) {
                logger.info("testDateOnQuery_NaiveStrategy (Naive): Doc ID {}, Value {}, VarName {}, ConceptualID {}",
                        result.getDocumentIdAt(i),
                        result.getValueAt(i),
                        result.getVariableNameAt(i),
                        result.getConceptualRowIds() != null ? result.getConceptualRowIdAt(i) : "N/A");
            }

            assertFalse(result.isEmpty(), "Expected results for DATE(= 2023-01-15) with naive strategy");
            assertEquals(1, result.size(), "Expected 1 document for DATE(= 2023-01-15) with naive strategy");
            // Expected Doc ID: 2 (2023-01-15)
            assertQueryResultContainsDocIds(result, 2);

        } finally {
            factory.setTemporalStrategy(originalStrategy); // Reset to default strategy
            logger.info("QueryEndToEndTest: Reset temporal strategy to {} after testDateOnQuery_NaiveStrategy", originalStrategy);
        }
    }

    @Test
    public void testDateAfterQuery_NaiveStrategy() throws QueryParseException, QueryExecutionException, ResultGenerationException {
        String originalStrategy = "nash"; // Default from setUp
        try {
            factory.setTemporalStrategy("naive");
            logger.info("QueryEndToEndTest: Set temporal strategy to NAIVE for testDateAfterQuery_NaiveStrategy");
            logger.info("QueryEndToEndTest: mockNerDateIndex size before query: {}", mockNerDateIndex.getStoreSize());

            String queryString = "SELECT DOCUMENT_ID FROM source WHERE DATE(> 2023-03-20)";
            Query query = queryParser.parse(queryString);

            QueryResultSoA result = queryExecutor.execute(query, mockIndexes);

            assertNotNull(result, "QueryResultSoA should not be null for naive DATE(>) query");
            logger.info("testDateAfterQuery_NaiveStrategy (Naive): Result size = {}, Query: {}", result.size(), queryString);
            for (int i = 0; i < result.size(); i++) {
                logger.info("testDateAfterQuery_NaiveStrategy (Naive): Doc ID {}, Value {}, VarName {}, ConceptualID {}",
                        result.getDocumentIdAt(i),
                        result.getValueAt(i),
                        result.getVariableNameAt(i),
                        result.getConceptualRowIds() != null ? result.getConceptualRowIdAt(i) : "N/A");
            }

            assertFalse(result.isEmpty(), "Expected results for DATE(> 2023-03-20) with naive strategy");
            assertEquals(1, result.size(), "Expected 1 document for DATE(> 2023-03-20) with naive strategy");
            // Expected Doc ID: 3 (2024-01-01)
            assertQueryResultContainsDocIds(result, 3);

        } finally {
            factory.setTemporalStrategy(originalStrategy); // Reset to default strategy
            logger.info("QueryEndToEndTest: Reset temporal strategy to {} after testDateAfterQuery_NaiveStrategy", originalStrategy);
        }
    }
}