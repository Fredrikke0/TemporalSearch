package com.example.query.executor;

import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.query.binding.MatchDetail;
import com.example.query.model.Query;
import com.example.query.model.condition.Contains;
import com.example.query.model.SelectColumn;
import com.example.query.model.SnippetColumn;
import com.example.query.model.StructuralColumn;
import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests SoA optimization with very large result sets to validate
 * memory usage improvements and selective deserialization performance.
 */
public class SoALargeResultSetTest {

    @Mock
    private IndexAccessInterface mockIndex;

    private ConditionExecutorFactory executorFactory;
    private QueryExecutor queryExecutor;

    // Test parameters for large result sets
    private static final int LARGE_RESULT_SET_SIZE = 100_000; // 100K results
    private static final int VERY_LARGE_RESULT_SET_SIZE = 500_000; // 500K results
    private static final String TEST_TERM = "test_term";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        executorFactory = new ConditionExecutorFactory();
        queryExecutor = new QueryExecutor(executorFactory);
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

        Object result = queryExecutor.execute(query, Map.of("unigram", mockIndex));

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

        Object result = queryExecutor.execute(query, Map.of("unigram", mockIndex));

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

        Object result = queryExecutor.execute(query, Map.of("unigram", mockIndex));

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
        long startMemory = getUsedMemory();

        // Force garbage collection before test
        System.gc();
        Thread.sleep(100);
        long baselineMemory = getUsedMemory();

        Object result = queryExecutor.execute(query, Map.of("unigram", mockIndex));

        long endTime = System.nanoTime();
        long endMemory = getUsedMemory();

        // Validate memory efficiency
        long memoryIncrease = endMemory - baselineMemory;
        long executionTime = (endTime - startTime) / 1_000_000; // Convert to milliseconds

        System.out.println("\n=== Very Large Result Set Performance ===");
        System.out.println("Result set size: " + VERY_LARGE_RESULT_SET_SIZE);
        System.out.println("Execution time: " + executionTime + " ms");
        System.out.println("Memory increase: " + (memoryIncrease / 1024 / 1024) + " MB");
        System.out.println("Memory per result: " + (memoryIncrease / VERY_LARGE_RESULT_SET_SIZE) + " bytes");

        // Validate that result is correct
        validateLargeResultSet(result, VERY_LARGE_RESULT_SET_SIZE, startTime, endTime, baselineMemory, endMemory, "Very Large Result Set");

        // Memory efficiency assertions
        long maxExpectedMemoryPerResult = 200; // bytes per result (conservative estimate)
        long actualMemoryPerResult = memoryIncrease / VERY_LARGE_RESULT_SET_SIZE;
        
        assertTrue(actualMemoryPerResult < maxExpectedMemoryPerResult, 
                  String.format("Memory usage too high: %d bytes/result (expected < %d)", 
                               actualMemoryPerResult, maxExpectedMemoryPerResult));

        // Performance assertions
        long maxExpectedTimeMs = 5000; // 5 seconds max for 500K results
        assertTrue(executionTime < maxExpectedTimeMs, 
                  String.format("Execution time too slow: %d ms (expected < %d)", 
                               executionTime, maxExpectedTimeMs));
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
        
        Object docResult = queryExecutor.execute(docQuery, Map.of("unigram", mockIndex));
        
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
        
        Object sentResult = queryExecutor.execute(sentQuery, Map.of("unigram", mockIndex));
        
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
        
        Object snippetResult = queryExecutor.execute(snippetQuery, Map.of("unigram", mockIndex));
        
        long snippetEndTime = System.nanoTime();
        long snippetEndMemory = getUsedMemory();
        long snippetExecutionTime = (snippetEndTime - snippetStartTime) / 1_000_000;
        long snippetMemoryUsage = snippetEndMemory - snippetStartMemory;

        // Print comparison
        System.out.println("\n=== Selective Deserialization Comparison ===");
        System.out.println("Document Query - Time: " + docExecutionTime + " ms, Memory: " + (docMemoryUsage / 1024 / 1024) + " MB");
        System.out.println("Sentence Query - Time: " + sentExecutionTime + " ms, Memory: " + (sentMemoryUsage / 1024 / 1024) + " MB");
        System.out.println("Snippet Query  - Time: " + snippetExecutionTime + " ms, Memory: " + (snippetMemoryUsage / 1024 / 1024) + " MB");

        // Validate that document query uses less memory (selective deserialization benefit)
        assertTrue(docMemoryUsage <= sentMemoryUsage, 
                  "Document query should use less or equal memory than sentence query");
        assertTrue(sentMemoryUsage <= snippetMemoryUsage, 
                  "Sentence query should use less or equal memory than snippet query");

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

    private void validateLargeResultSet(Object result, int expectedSize, long startTime, long endTime, 
                                      long startMemory, long endMemory, String testName) {
        assertNotNull(result, "Result should not be null");
        assertTrue(result instanceof QueryResult, "Result should be QueryResult for " + testName);
        
        QueryResult queryResult = (QueryResult) result;
        assertEquals(expectedSize, queryResult.getAllDetails().size(), 
                    "Should have " + expectedSize + " results for " + testName);

        long executionTime = (endTime - startTime) / 1_000_000; // Convert to ms
        long memoryUsage = (endMemory - startMemory) / 1024 / 1024; // Convert to MB

        System.out.println("\n=== " + testName + " Performance ===");
        System.out.println("Results: " + queryResult.getAllDetails().size());
        System.out.println("Execution time: " + executionTime + " ms");
        System.out.println("Memory usage: " + memoryUsage + " MB");
        System.out.println("Time per result: " + ((double) executionTime / expectedSize) + " ms");

        // Validate some sample results
        List<MatchDetail> details = queryResult.getAllDetails();
        for (int i = 0; i < Math.min(10, details.size()); i++) {
            MatchDetail detail = details.get(i);
            assertEquals(TEST_TERM, detail.value(), "Sample result should have correct value");
            assertTrue(detail.getDocumentId() >= 0, "Sample result should have valid document ID");
        }
    }

    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
} 