package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.index.presence.RBPresenceIndex;
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
        // Static mocks (unigramIndex, mockSynonymManager, mockIndexManager) are injected by MockitoExtension
        factory = new ConditionExecutorFactory(mockSynonymManager, "none", Query.Granularity.SENTENCE);
        queryExecutor = new QueryExecutor(null, "none", mockSynonymManager, factory);
        queryParser = new QueryParser();
        System.out.println("Sentence Granularity Test Setup Complete.");
    }

    @AfterAll
    public static void tearDown() throws Exception {
        System.out.println("Sentence Granularity Test Teardown Complete.");
    }

    private IndexAccess setupMockIndexBehavior(Map<String, RBPresenceIndex> mockData) throws IOException, IndexAccessException {
        for (Map.Entry<String, RBPresenceIndex> entry : mockData.entrySet()) {
            byte[] bytes = entry.getValue().toBytes();
            lenient().when(unigramIndex.getRaw(eq(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                   .thenReturn(Optional.of(bytes));
        }
        lenient().when(unigramIndex.getRaw(argThat(k -> mockData.keySet().stream().noneMatch(key -> key.equals(new String(k, java.nio.charset.StandardCharsets.UTF_8))))))
               .thenReturn(Optional.empty());
        return unigramIndex;
    }

    private QueryResultSoA executeSentenceQuery(String queryString, Map<String, IndexAccessInterface> testIndexes)
        throws QueryParseException, QueryExecutionException {
        Query query = queryParser.parse(queryString);
        assertTrue(query.granularity() == Query.Granularity.SENTENCE || query.granularitySize().isPresent(),
                   "Query granularity should be SENTENCE or have a window size");

        lenient().when(mockIndexManager.getAllIndexes()).thenReturn(testIndexes);
        lenient().when(mockIndexManager.getSynonymManager()).thenReturn(mockSynonymManager);

        return queryExecutor.execute(query, mockIndexManager);
    }

    private boolean soaContainsMatch(QueryResultSoA soa, int docId, int sentId) {
        if (soa == null || !soa.getRequirements().needsSentenceId) return false;
        for (int i = 0; i < soa.size(); i++) {
            if (soa.getDocumentIdAt(i) == docId && soa.getSentenceIdAt(i) == sentId) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testSentenceGranularityBasic() throws Exception {
        String queryString = "SELECT DOCUMENT_ID FROM mockCorpusSent WHERE CONTAINS('test') GRANULARITY SENTENCE";

        Map<String, RBPresenceIndex> mockData = new HashMap<>();
        RBPresenceIndex testPresence = new RBPresenceIndex();
        testPresence.add(0, 0);
        testPresence.add(1, 1);
        mockData.put("test", testPresence);
        IndexAccess mockIndexImpl = setupMockIndexBehavior(mockData);
        Map<String, IndexAccessInterface> testIndexes = Map.of("rb_unigram", mockIndexImpl);

        QueryResultSoA results = executeSentenceQuery(queryString, testIndexes);

        assertNotNull(results);
        assertEquals(Query.Granularity.SENTENCE, results.getGranularity());
        assertEquals(2, results.size());
        assertTrue(soaContainsMatch(results, 0, 0), "Result should contain doc 0, sent 0");
        assertTrue(soaContainsMatch(results, 1, 1), "Result should contain doc 1, sent 1");
    }

    @Test
    public void testSentenceGranularityWithWindow() throws Exception {
        String queryString = "SELECT DOCUMENT_ID FROM mockCorpusSent WHERE CONTAINS('window') GRANULARITY SENTENCE 1";

        Map<String, RBPresenceIndex> mockData = new HashMap<>();
        RBPresenceIndex windowPresence = new RBPresenceIndex();
        windowPresence.add(0, 1);
        windowPresence.add(0, 3);
        mockData.put("window", windowPresence);
        IndexAccess mockIndexImpl = setupMockIndexBehavior(mockData);
        Map<String, IndexAccessInterface> testIndexes = Map.of("rb_unigram", mockIndexImpl);

        QueryResultSoA results = executeSentenceQuery(queryString, testIndexes);

        assertNotNull(results);
        assertEquals(Query.Granularity.SENTENCE, results.getGranularity());
        assertEquals(1, results.getGranularitySize(), "Granularity size should be 1 from query");
        assertEquals(2, results.size(),
                   "Expected 2 results (Sent 1 and Sent 3) as windowing is applied by QueryExecutor, not ContainsExecutor directly for single conditions");
        assertTrue(soaContainsMatch(results, 0, 1), "Should contain result for Sent 1");
        assertTrue(soaContainsMatch(results, 0, 3), "Should contain result for Sent 3");
    }

    @Test
    public void testSentenceGranularityWithLargerWindow() throws Exception {
        String queryString = "SELECT DOCUMENT_ID FROM mockCorpusSent WHERE CONTAINS('window') GRANULARITY SENTENCE 2";

        Map<String, RBPresenceIndex> mockData = new HashMap<>();
        RBPresenceIndex windowPresence = new RBPresenceIndex();
        windowPresence.add(0, 1);
        windowPresence.add(0, 3);
        mockData.put("window", windowPresence);
        IndexAccess mockIndexImpl = setupMockIndexBehavior(mockData);
        Map<String, IndexAccessInterface> testIndexes = Map.of("rb_unigram", mockIndexImpl);

        QueryResultSoA results = executeSentenceQuery(queryString, testIndexes);

        assertNotNull(results);
        assertEquals(Query.Granularity.SENTENCE, results.getGranularity());
        assertEquals(2, results.getGranularitySize(), "Granularity size should be 2 from query");
        assertEquals(2, results.size(), "Expected 2 results for window=2");
        assertTrue(soaContainsMatch(results, 0, 1), "Should contain result for Sent 1");
        assertTrue(soaContainsMatch(results, 0, 3), "Should contain result for Sent 3");
    }
}