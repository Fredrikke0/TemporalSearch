package com.example.query.executor;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.query.executor.QueryExecutionException;
import com.example.query.executor.QueryExecutor;
import com.example.query.executor.QueryResult;
import com.example.query.index.IndexManager;
import com.example.query.model.Query;
import com.example.query.model.condition.*;
import com.example.query.QueryParseException;
import com.example.query.QueryParser;
import com.example.query.result.TableResultService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.tablesaw.api.Table;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests focusing on sentence granularity and windowing.
 */
public class SentenceGranularityTest {

    @TempDir
    static Path tempDir;

    private static IndexManager indexManager;
    private static QueryExecutor queryExecutor;
    private static TableResultService tableResultService;
    private static QueryParser queryParser;

    @Mock
    private IndexAccess unigramIndex;
    @Mock
    private IndexAccess nerIndex;
    @Mock
    private ConditionExecutorFactory factory;

    private Map<String, IndexAccessInterface> indexes;

    @BeforeAll
    public static void setUp() throws IOException, IndexAccessException {
        // No need to set up IndexManager as tests use mocks directly
        
        // Instantiate QueryExecutor using the correct constructor (takes factory)
        queryExecutor = new QueryExecutor(new ConditionExecutorFactory()); 
        tableResultService = new TableResultService();
        queryParser = new QueryParser();
        
        System.out.println("Sentence Granularity Test Setup Complete.");
    }

    @AfterAll
    public static void tearDown() throws Exception {
        // No IndexManager to close
        System.out.println("Sentence Granularity Test Teardown Complete.");
    }

    // Helper to configure mock IndexAccess behavior for a specific test
    // Creates a pure Mockito mock, configured as needed
    private IndexAccess setupMockIndexBehavior(Map<String, PositionList> mockData) throws IndexAccessException {
        IndexAccess mockIndex = mock(IndexAccess.class);
        // Configure mock behavior based on the provided data map
        for (Map.Entry<String, PositionList> entry : mockData.entrySet()) {
            // Mock the get() method for specific keys
            when(mockIndex.get(eq(entry.getKey().getBytes()))).thenReturn(Optional.ofNullable(entry.getValue()));
        }
        // Provide a default return for any key not explicitly mocked
        when(mockIndex.get(argThat(k -> mockData.keySet().stream().noneMatch(key -> Arrays.equals(k, key.getBytes())))))
            .thenReturn(Optional.empty());
        return mockIndex;
    }

    // Updated execute method takes the map of indexes containing the mock
    private Object executeSentenceQuery(String queryString, Map<String, IndexAccessInterface> testIndexes) 
        throws QueryParseException, QueryExecutionException {
        Query query = queryParser.parse(queryString);
        assertTrue(query.granularity() == Query.Granularity.SENTENCE || query.granularitySize().isPresent(), 
                   "Query granularity should be SENTENCE or have a window size");
        return queryExecutor.execute(query, testIndexes); 
    }

    @Test
    public void testSentenceGranularityBasic() throws Exception {
        String queryString = "SELECT TITLE FROM mockCorpusSent WHERE CONTAINS(\"test\") GRANULARITY SENTENCE";
        
        // Setup mock data and mock index for this test
        Map<String, PositionList> mockData = new HashMap<>();
        PositionList testPositions = new PositionList();
        testPositions.add(new Position(0, 0, 0, 4)); // Doc 0, Sent 0
        testPositions.add(new Position(1, 1, 0, 4)); // Doc 1, Sent 1
        mockData.put("test", testPositions);
        IndexAccess mockIndex = setupMockIndexBehavior(mockData);
        Map<String, IndexAccessInterface> testIndexes = Map.of("unigram", mockIndex); // Use the mock with correct type
        
        QueryResult results = (QueryResult) executeSentenceQuery(queryString, testIndexes);
        
        assertNotNull(results);
        assertEquals(Query.Granularity.SENTENCE, results.getGranularity());
        assertEquals(2, results.getAllDetails().size());
        assertTrue(results.getAllDetails().stream().anyMatch(d -> d.getDocumentId() == 0 && d.getSentenceId() == 0));
        assertTrue(results.getAllDetails().stream().anyMatch(d -> d.getDocumentId() == 1 && d.getSentenceId() == 1));
    }

    @Test
    public void testSentenceGranularityWithWindow() throws Exception {
        String queryString = "SELECT TITLE FROM mockCorpusSent WHERE CONTAINS(\"window\") GRANULARITY SENTENCE 1";
        
        Map<String, PositionList> mockData = new HashMap<>();
        PositionList windowPositions = new PositionList();
        windowPositions.add(new Position(0, 1, 0, 6)); // Doc 0, Sent 1
        windowPositions.add(new Position(0, 3, 0, 6)); // Doc 0, Sent 3
        mockData.put("window", windowPositions);
        IndexAccess mockIndex = setupMockIndexBehavior(mockData);
        Map<String, IndexAccessInterface> testIndexes = Map.of("unigram", mockIndex); // Use the mock with correct type

        QueryResult results = (QueryResult) executeSentenceQuery(queryString, testIndexes);

        assertNotNull(results);
        assertEquals(Query.Granularity.SENTENCE, results.getGranularity());
        // Expect 2 results because window size N=1 is ignored for single condition result sets
        assertEquals(2, results.getAllDetails().size(), 
                   "Expected 2 results (Sent 1 and Sent 3) as window is ignored for single condition"); 
        assertTrue(results.getAllDetails().stream().anyMatch(d -> d.getDocumentId() == 0 && d.getSentenceId() == 1), "Should contain result for Sent 1");
        assertTrue(results.getAllDetails().stream().anyMatch(d -> d.getDocumentId() == 0 && d.getSentenceId() == 3), "Should contain result for Sent 3");
    }

    @Test
    public void testSentenceGranularityWithLargerWindow() throws Exception {
        String queryString = "SELECT TITLE FROM mockCorpusSent WHERE CONTAINS(\"window\") GRANULARITY SENTENCE 2";

        Map<String, PositionList> mockData = new HashMap<>();
        PositionList windowPositions = new PositionList();
        windowPositions.add(new Position(0, 1, 0, 6)); // Doc 0, Sent 1
        windowPositions.add(new Position(0, 3, 0, 6)); // Doc 0, Sent 3
        mockData.put("window", windowPositions);
        IndexAccess mockIndex = setupMockIndexBehavior(mockData);
        Map<String, IndexAccessInterface> testIndexes = Map.of("unigram", mockIndex); // Use the mock with correct type
        
        QueryResult results = (QueryResult) executeSentenceQuery(queryString, testIndexes);

        assertNotNull(results);
        assertEquals(Query.Granularity.SENTENCE, results.getGranularity());
        assertEquals(2, results.getAllDetails().size(), "Expected 2 results for window=2");
        assertTrue(results.getAllDetails().stream().anyMatch(d -> d.getDocumentId() == 0 && d.getSentenceId() == 1));
        assertTrue(results.getAllDetails().stream().anyMatch(d -> d.getDocumentId() == 0 && d.getSentenceId() == 3));
    }
} 