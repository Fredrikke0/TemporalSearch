package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;
import com.example.query.model.Query;
import com.example.query.model.SelectColumn;
import com.example.query.model.SnippetColumn;
import com.example.query.model.condition.Contains;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Tests SoA optimization with very large result sets to validate
 * memory usage improvements and selective deserialization performance.
 */
// @Disabled // Temporarily disabling due to persistent compilation issues - RE-ENABLING
public class SoALargeResultSetTest {

    private static final Logger logger = LoggerFactory.getLogger(SoALargeResultSetTest.class);

    @Mock
    private IndexAccessInterface mockIndex;

    private ConditionExecutorFactory executorFactory;
    private QueryExecutor queryExecutor;
    private Map<String, IndexAccessInterface> mockIndexes;

    // Test parameters for large result sets
    private static final int LARGE_RESULT_SET_SIZE = 100_000; // 100K results
    private static final int VERY_LARGE_RESULT_SET_SIZE = 500_000; // 500K results
    private static final String TEST_TERM = "test_term";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        executorFactory = new ConditionExecutorFactory();
        queryExecutor = new QueryExecutor(executorFactory, "none");

        // Setup mock index access as needed for these tests
        // For SoA tests, we might not always need deep index interaction if focusing on QueryResultSoA structure.
        mockIndexes = new HashMap<>();
        // Example: mockIndexes.put("unigram", new MockIndexAccess("unigram", null, null, null));
    }

    @Test
    void testLargeResultSetWithDocumentGranularity() throws Exception {
        // Test document granularity (should NOT need sentence IDs, positions)
        setupMockForLargeResultSet(LARGE_RESULT_SET_SIZE, false);

        Query query = createDocumentGranularityQuery();
        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);

        // Verify requirements - document granularity should not need sentence IDs or positions
        assertFalse(requirements.needsSentenceId, "Document granularity should not need sentence IDs");
        assertFalse(requirements.needsPositions, "Document granularity should not need positions");
        assertTrue(requirements.needsDocumentId, "Document granularity should need document IDs");

        long startTime = System.nanoTime();
        long startMemory = getUsedMemory();

        Object rawResult = queryExecutor.execute(query, Map.of("unigram", mockIndex));
        assertTrue(rawResult instanceof QueryResultSoA, "Result should be QueryResultSoA");
        QueryResultSoA result = (QueryResultSoA) rawResult;

        long endTime = System.nanoTime();
        long endMemory = getUsedMemory();

        validateLargeResultSet(result, LARGE_RESULT_SET_SIZE, startTime, endTime, startMemory, endMemory, "Document Granularity");
    }

    @Test
    void testLargeResultSetWithSentenceGranularity() throws Exception {
        // Test sentence granularity (should need sentence IDs but NOT positions)
        setupMockForLargeResultSet(LARGE_RESULT_SET_SIZE, true);

        Query query = createSentenceGranularityQuery();
        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);

        // Verify requirements - sentence granularity should need sentence IDs but not positions
        assertTrue(requirements.needsSentenceId, "Sentence granularity should need sentence IDs");
        assertFalse(requirements.needsPositions, "Simple sentence granularity should not need positions");
        assertTrue(requirements.needsDocumentId, "Sentence granularity should need document IDs");

        long startTime = System.nanoTime();
        long startMemory = getUsedMemory();

        Object rawResult = queryExecutor.execute(query, Map.of("unigram", mockIndex));
        assertTrue(rawResult instanceof QueryResultSoA, "Result should be QueryResultSoA");
        QueryResultSoA result = (QueryResultSoA) rawResult;

        long endTime = System.nanoTime();
        long endMemory = getUsedMemory();

        validateLargeResultSet(result, LARGE_RESULT_SET_SIZE, startTime, endTime, startMemory, endMemory, "Sentence Granularity");
    }

    @Test
    void testLargeResultSetWithSnippetColumn() throws Exception {
        // Test with SNIPPET column (should need positions)
        setupMockForLargeResultSet(LARGE_RESULT_SET_SIZE, true);

        Query query = createSnippetQuery();
        AttributeRequirements requirements = QueryAttributeAnalyzer.analyze(query);

        // Verify requirements - snippet query should need positions
        assertTrue(requirements.needsPositions, "Snippet query should need positions");
        assertTrue(requirements.needsDocumentId, "Snippet query should need document IDs");

        long startTime = System.nanoTime();
        long startMemory = getUsedMemory();

        Object rawResult = queryExecutor.execute(query, Map.of("unigram", mockIndex));
        assertTrue(rawResult instanceof QueryResultSoA, "Result should be QueryResultSoA");
        QueryResultSoA result = (QueryResultSoA) rawResult;

        long endTime = System.nanoTime();
        long endMemory = getUsedMemory();

        validateLargeResultSet(result, LARGE_RESULT_SET_SIZE, startTime, endTime, startMemory, endMemory, "Snippet Query");
    }

    @Test
    void testVeryLargeResultSetMemoryEfficiency() throws Exception {
        // Test with very large result set to stress-test memory efficiency
        setupMockForLargeResultSet(VERY_LARGE_RESULT_SET_SIZE, false);

        Query query = createDocumentGranularityQuery();

        long startTime = System.nanoTime();

        // Force garbage collection before test
        System.gc();
        Thread.sleep(100);
        long baselineMemory = getUsedMemory();

        Object rawResult = queryExecutor.execute(query, Map.of("unigram", mockIndex));
        assertTrue(rawResult instanceof QueryResultSoA, "Result should be QueryResultSoA");
        QueryResultSoA result = (QueryResultSoA) rawResult;

        long executionTimeMeasuredMs = (System.nanoTime() - startTime) / 1_000_000; // Measure actual execution time here

        // Force GC before measuring end memory to account for temporary objects
        System.gc();
        try {
            Thread.sleep(200); // Increased sleep slightly for GC, ensure InterruptedException is handled
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupt status
            System.err.println("Thread sleep interrupted during GC pause: " + e.getMessage());
        }
        long endMemory = getUsedMemory();

        // Validate memory efficiency
        long memoryIncrease = endMemory - baselineMemory;

        logger.info("\n=== Very Large Result Set Performance ===");
        logger.info("Result set size: " + VERY_LARGE_RESULT_SET_SIZE);
        logger.info("Execution time: " + executionTimeMeasuredMs + " ms");
        logger.info("Memory increase: " + (memoryIncrease / 1024 / 1024) + " MB");
        logger.info("Memory per result: " + (memoryIncrease / VERY_LARGE_RESULT_SET_SIZE) + " bytes");

        // Reconstruct an endTime for the validateLargeResultSet logging, if needed by its internals (it only logs it)
        long endTimeForLogging = startTime + (executionTimeMeasuredMs * 1_000_000);
        validateLargeResultSet(result, VERY_LARGE_RESULT_SET_SIZE, startTime, endTimeForLogging, baselineMemory, endMemory, "Very Large Result Set");

        // Memory efficiency assertions
        long maxExpectedMemoryPerResult = 200; // bytes per result (conservative estimate)
        long actualMemoryPerResult = (memoryIncrease > 0 && VERY_LARGE_RESULT_SET_SIZE > 0) ? (memoryIncrease / VERY_LARGE_RESULT_SET_SIZE) : 0;

        assertTrue(actualMemoryPerResult < maxExpectedMemoryPerResult,
                  String.format("Memory usage too high: %d bytes/result (expected < %d)",
                               actualMemoryPerResult, maxExpectedMemoryPerResult));

        // Performance assertions
        long maxExpectedTimeMs = 5000; // 5 seconds max for 500K results
        assertTrue(executionTimeMeasuredMs < maxExpectedTimeMs,
                  String.format("Execution time too slow: %d ms (expected < %d)",
                               executionTimeMeasuredMs, maxExpectedTimeMs));
    }
    @Disabled
    @Test
    void testSelectiveDeserializationBenefit() throws Exception {
        // Compare document vs sentence granularity to show selective deserialization benefit
        setupMockForLargeResultSet(LARGE_RESULT_SET_SIZE, true);

        // Test 1: Document granularity (minimal requirements)
        Query docQuery = createDocumentGranularityQuery();
        long docStartTime = System.nanoTime();
        long docStartMemory = getUsedMemory();

        Object rawDocResult = queryExecutor.execute(docQuery, Map.of("unigram", mockIndex));
        assertTrue(rawDocResult instanceof QueryResultSoA, "Document result should be QueryResultSoA");
        QueryResultSoA docResult = (QueryResultSoA) rawDocResult;

        long docEndTime = System.nanoTime();
        long docEndMemory = getUsedMemory();
        long docExecutionTime = (docEndTime - docStartTime) / 1_000_000;
        long docMemoryUsage = docEndMemory - docStartMemory;

        // Test 2: Sentence granularity (additional requirements)
        System.gc(); // Clean up between tests
        Thread.sleep(100);

        Query sentQuery = createSentenceGranularityQuery();
        long sentStartTime = System.nanoTime();
        long sentStartMemory = getUsedMemory();

        Object rawSentResult = queryExecutor.execute(sentQuery, Map.of("unigram", mockIndex));
        assertTrue(rawSentResult instanceof QueryResultSoA, "Sentence result should be QueryResultSoA");
        QueryResultSoA sentResult = (QueryResultSoA) rawSentResult;

        long sentEndTime = System.nanoTime();
        long sentEndMemory = getUsedMemory();
        long sentExecutionTime = (sentEndTime - sentStartTime) / 1_000_000;
        long sentMemoryUsage = sentEndMemory - sentStartMemory;

        // Test 3: Snippet query (full requirements)
        System.gc(); // Clean up between tests
        Thread.sleep(100);

        Query snippetQuery = createSnippetQuery();
        long snippetStartTime = System.nanoTime();
        long snippetStartMemory = getUsedMemory();

        Object rawSnippetResult = queryExecutor.execute(snippetQuery, Map.of("unigram", mockIndex));
        assertTrue(rawSnippetResult instanceof QueryResultSoA, "Snippet result should be QueryResultSoA");
        QueryResultSoA snippetResult = (QueryResultSoA) rawSnippetResult;

        long snippetEndTime = System.nanoTime();
        long snippetEndMemory = getUsedMemory();
        long snippetExecutionTime = (snippetEndTime - snippetStartTime) / 1_000_000;
        long snippetMemoryUsage = snippetEndMemory - snippetStartMemory;

        // Print comparison
        logger.info("\n=== Selective Deserialization Comparison ===");
        logger.info("Document Query - Time: " + docExecutionTime + " ms, Memory: " + (docMemoryUsage / 1024 / 1024) + " MB");
        logger.info("Sentence Query - Time: " + sentExecutionTime + " ms, Memory: " + (sentMemoryUsage / 1024 / 1024) + " MB");
        logger.info("Snippet Query  - Time: " + snippetExecutionTime + " ms, Memory: " + (snippetMemoryUsage / 1024 / 1024) + " MB");

        // Validate that document query uses less memory (selective deserialization benefit)
        assertTrue(docMemoryUsage <= sentMemoryUsage + (1024 * 1024),
                  "Document query should use less or equal memory than sentence query (with 1MB tolerance)");
        assertTrue(sentMemoryUsage <= snippetMemoryUsage + (1024 * 1024),
                  "Sentence query should use less or equal memory than snippet query (with 1MB tolerance)");

        // All should produce same number of results
        validateLargeResultSet(docResult, LARGE_RESULT_SET_SIZE, docStartTime, docEndTime, docStartMemory, docEndMemory, "Document Query");
        validateLargeResultSet(sentResult, LARGE_RESULT_SET_SIZE, sentStartTime, sentEndTime, sentStartMemory, sentEndMemory, "Sentence Query");
        validateLargeResultSet(snippetResult, LARGE_RESULT_SET_SIZE, snippetStartTime, snippetEndTime, snippetStartMemory, snippetEndMemory, "Snippet Query");
    }

    private void setupMockForLargeResultSet(int resultCount, boolean includeSentenceData) throws Exception {
        // Create large position arrays
        IntArrayList docIds = new IntArrayList(resultCount);
        IntArrayList sentIds = new IntArrayList(resultCount);
        IntArrayList beginChars = new IntArrayList(resultCount);
        IntArrayList endChars = new IntArrayList(resultCount);
        IntArrayList synonymIds = new IntArrayList(resultCount);

        // Populate with test data
        for (int i = 0; i < resultCount; i++) {
            docIds.add(i % 1000); // Spread across 1000 documents
            sentIds.add(includeSentenceData ? (i % 10) : -1); // 10 sentences per doc or -1
            beginChars.add(i * 10); // Character positions
            endChars.add(i * 10 + 5);
            synonymIds.add(-1); // No synonyms for this test
        }

        // Create PositionListSoA and populate it
        PositionListSoA positionList = new PositionListSoA();
        for (int i = 0; i < resultCount; i++) {
            positionList.add(docIds.getInt(i), sentIds.getInt(i), beginChars.getInt(i), endChars.getInt(i), synonymIds.getInt(i));
        }
        byte[] serializedData = positionList.serializeToCompositeBlob();

        // Mock the index to return this data
        byte[] keyBytes = TEST_TERM.getBytes(StandardCharsets.UTF_8);
        when(mockIndex.getRaw(keyBytes)).thenReturn(Optional.of(serializedData));
        when(mockIndex.get(keyBytes)).thenReturn(Optional.of(positionList));
    }

    private Query createDocumentGranularityQuery() {
        Contains condition = new Contains(TEST_TERM);
        List<SelectColumn> selectColumns = Collections.emptyList();

        return new Query(
            "test_corpus",
            Collections.singletonList(condition),
            Collections.emptyList(), // orderBy
            Optional.empty(), // limit
            Query.Granularity.DOCUMENT,
            Optional.empty(), // granularitySize
            selectColumns,
            new VariableRegistry(),
            List.of(), // subqueries
            Optional.empty(), // joinCondition
            Optional.empty(), // mainAlias
            List.of() // groupByColumns
        );
    }

    private Query createSentenceGranularityQuery() {
        Contains condition = new Contains(TEST_TERM);
        List<SelectColumn> selectColumns = Collections.emptyList();

        return new Query(
            "test_corpus",
            Collections.singletonList(condition),
            Collections.emptyList(), // orderBy
            Optional.empty(), // limit
            Query.Granularity.SENTENCE,
            Optional.of(0), // granularitySize
            selectColumns,
            new VariableRegistry(),
            List.of(), // subqueries
            Optional.empty(), // joinCondition
            Optional.empty(), // mainAlias
            List.of() // groupByColumns
        );
    }

    private Query createSnippetQuery() {
        // SnippetColumn requires a qualified variable name, e.g., "$main.term_var"
        // Assuming a default alias "$main" if not specified elsewhere.
        String qualifiedVarName = "$main.term_var";
        Contains condition = new Contains(TEST_TERM); // This condition will implicitly produce for "$main.term_var"

        List<SelectColumn> selectColumns = List.of(new SnippetColumn(qualifiedVarName, 5));

        VariableRegistry registry = new VariableRegistry();
        // Manually register the producer for the snippet column to find.
        // The type is TEXT_SPAN as it's based on a CONTAINS condition.
        registry.registerProducer(qualifiedVarName, VariableType.TEXT_SPAN, Contains.class.getSimpleName());

        return new Query(
            "test_corpus",
            Collections.singletonList(condition),
            Collections.emptyList(), // orderBy
            Optional.empty(), // limit
            Query.Granularity.DOCUMENT,
            Optional.empty(), // granularitySize
            selectColumns,
            registry,
            List.of(), // subqueries
            Optional.empty(), // joinCondition
            Optional.empty(), // mainAlias
            List.of() // groupByColumns
        );
    }

    private void validateLargeResultSet(Object rawResult, int expectedSize, long startTime, long endTime,
                                      long startMemory, long endMemory, String testName) {

        assertTrue(rawResult instanceof QueryResultSoA, testName + ": Result should be QueryResultSoA");
        QueryResultSoA result = (QueryResultSoA) rawResult;

        long executionTime = (endTime - startTime) / 1_000_000; // Convert to milliseconds
        long memoryUsage = endMemory - startMemory;

        logger.info("\n=== {} Test Results ===", testName);
        logger.info("Result set size: {}", result.size());
        logger.info("Execution time: {} ms", executionTime);
        logger.info("Memory usage: {} MB", (memoryUsage / 1024 / 1024));
        if (result.size() > 0) {
            logger.info("Memory per result: {} bytes", (memoryUsage / result.size()));
        }

        assertEquals(expectedSize, result.size(), testName + ": Unexpected number of results");

        // Basic validation that QueryResultSoA is populated
        assertNotNull(result.getDocumentIds(), testName + ": Document IDs should not be null");
        assertEquals(expectedSize, result.getDocumentIds().size(), testName + ": Document ID count mismatch");

        if (result.getRequirements().needsSentenceId) {
            IntArrayList sentenceIdsList = result.getSentenceIds();
            assertNotNull(sentenceIdsList, testName + ": Sentence IDs IntArrayList should not be null if required");
            assertEquals(expectedSize, sentenceIdsList.size(), testName + ": Sentence ID count mismatch");
        }

        if (result.getRequirements().needsPositions) {
            IntArrayList beginCharsList = result.getBeginChars();
            assertNotNull(beginCharsList, testName + ": Begin chars IntArrayList should not be null if required");
            assertEquals(expectedSize, beginCharsList.size(), testName + ": Begin char count mismatch");

            IntArrayList endCharsList = result.getEndChars();
            assertNotNull(endCharsList, testName + ": End chars IntArrayList should not be null if required");
            assertEquals(expectedSize, endCharsList.size(), testName + ": End char count mismatch");
        }

        if (result.getRequirements().needsConceptualRowIds) {
            IntArrayList conceptualRowIdsList = result.getConceptualRowIds();
            assertNotNull(conceptualRowIdsList, testName + ": Conceptual Row IDs IntArrayList should not be null if required by AttributeRequirements");
            assertEquals(expectedSize, conceptualRowIdsList.size(), testName + ": Conceptual Row ID count mismatch");
        }

        // Further detailed validation can be added here if needed,
        // for example, checking specific values or structure.
        // For now, we primarily focus on size and memory.
    }

    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}