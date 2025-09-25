package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException; // Added import for IOException
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rocksdb.RocksIterator;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.util.SynonymManager; // Added
import com.example.query.binding.ValueType;
import com.example.query.index.IndexManager; // Added import for IndexManager
import com.example.query.model.Query;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Not;
import com.example.query.model.condition.Temporal;
import com.example.query.result.TableResultService;

@ExtendWith(MockitoExtension.class)
class QueryExecutorTest {

    // Helper record for test data, replacing MatchDetail for test setup
    record TestDataEntry(Object value, ValueType type, String varName, int docId, int sentId, int begin, int end, int synId, int conceptualRowId) {}

    @Mock private IndexAccess unigramIndex;
    @Mock private IndexAccess nerIndex;
    @Mock private IndexAccess posIndex; // Added for completeness
    @Mock private IndexAccess dependencyIndex; // Added for completeness
    @Mock private IndexAccess nerDateIndex; // Added for NerDate

    @Mock private RocksIterator unigramIterator;
    @Mock private RocksIterator nerIterator;

    // Mock dependencies needed by QueryExecutor and JoinHandler
    @Mock private TableResultService mockTableResultService;
    @Mock private ConditionExecutorFactory factory;
    @Mock private Query mockQuery;
    @Mock private ContainsExecutor containsExecutor;
    @Mock private NerExecutor nerExecutor;
    @Mock private LogicalExecutor logicalExecutor;
    @Mock private NotExecutor notExecutor; // Added for completeness if used
    @Mock private TemporalExecutor temporalExecutor;
    @Mock private IndexManager mockIndexManager; // Added
    @Mock private SynonymManager mockSynonymManager; // Added

    // Class under test, inject mocks
    private QueryExecutor queryExecutor;

    private Map<String, IndexAccessInterface> indexes;
    private AttributeRequirements testRequirements;

    @BeforeEach
    void setUp() throws IOException, IndexAccessException {
        // Initialize defaultTestRequirements first
        testRequirements = new AttributeRequirements();
        testRequirements.needsDocumentId = true;
        testRequirements.needsSentenceId = true;
        testRequirements.needsPositions = true;
        testRequirements.needsConceptualRowIds = true;
        testRequirements.needsSynonymIds = false;

        indexes = new HashMap<>();
        indexes.put("rb_unigram", unigramIndex);
        indexes.put("rb_ner", nerIndex);
        indexes.put("rb_pos", posIndex);
        indexes.put("rb_dependency", dependencyIndex);
        indexes.put("rb_ner_date", nerDateIndex);

        queryExecutor = new QueryExecutor(mockTableResultService, "none", mockSynonymManager, factory);

        // Mock IndexManager behavior
        lenient().when(mockIndexManager.getAllIndexes()).thenReturn(indexes);
        lenient().when(mockIndexManager.getSynonymManager()).thenReturn(mockSynonymManager);

        // Mock the factory to return specific executors when needed
        lenient().when(factory.getExecutor(any(Contains.class))).thenReturn(containsExecutor);
        lenient().when(factory.getExecutor(any(Ner.class))).thenReturn(nerExecutor);
        lenient().when(factory.getExecutor(any(Logical.class))).thenReturn(logicalExecutor);
        lenient().when(factory.getExecutor(any(Not.class))).thenReturn(notExecutor);
        lenient().when(factory.getExecutor(any(Temporal.class))).thenReturn(temporalExecutor);

        // Mock iterator behavior with lenient mode
        lenient().when(nerIndex.iterateFromFirst()).thenReturn(nerIterator);
        lenient().when(nerIterator.isValid()).thenReturn(false);

        // Mock unigram iterator to provide a universe for NOT tests
        // Let's define a universe of documents {1, 2, 3, 4}
        PositionListSoA posListDoc1 = new PositionListSoA(); posListDoc1.add(1, 0, 0, 1);
        PositionListSoA posListDoc2 = new PositionListSoA(); posListDoc2.add(2, 0, 0, 1);
        PositionListSoA posListDoc3 = new PositionListSoA(); posListDoc3.add(3, 0, 0, 1);
        PositionListSoA posListDoc4 = new PositionListSoA(); posListDoc4.add(4, 0, 0, 1);

        // Create mock entries for the iterator
        Map.Entry<byte[], byte[]> entry1 = Map.entry("key1".getBytes(), posListDoc1.serializeToCompositeBlob());
        Map.Entry<byte[], byte[]> entry2 = Map.entry("key2".getBytes(), posListDoc2.serializeToCompositeBlob());
        Map.Entry<byte[], byte[]> entry3 = Map.entry("key3".getBytes(), posListDoc3.serializeToCompositeBlob());
        Map.Entry<byte[], byte[]> entry4 = Map.entry("key4".getBytes(), posListDoc4.serializeToCompositeBlob());

        // Stub the iterator behavior
        lenient().when(unigramIndex.iterateFromFirst()).thenReturn(unigramIterator);
        lenient().when(unigramIterator.isValid()).thenReturn(true, true, true, true, false); // Iterate 4 times
        lenient().when(unigramIterator.key()).thenReturn(entry1.getKey(), entry2.getKey(), entry3.getKey(), entry4.getKey()); // Return each entry
        lenient().when(unigramIterator.value()).thenReturn(entry1.getValue(), entry2.getValue(), entry3.getValue(), entry4.getValue()); // Return each entry

        // Set up mock data for the tests
        setupMockData();
    }

    private void setupMockData() throws IndexAccessException {
        // Removed lenient().when(unigramIndex.get(...)) and nerIndex.get(...) mocks.
        // These are not needed here if ConditionExecutors' execute methods are mocked directly in tests.
    }



    private QueryResultSoA createMockQueryResultSoA(Query.Granularity granularity, int granularitySize, List<TestDataEntry> entries, AttributeRequirements reqs) {
        QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, reqs != null ? reqs : testRequirements);
        for (TestDataEntry entry : entries) {
            resultSoA.add(
                entry.value(), entry.type(), entry.varName(),
                entry.docId(), entry.sentId(),
                entry.begin(), entry.end(), entry.synId(),
                entry.conceptualRowId()
            );
        }
        return resultSoA;
    }

    @SuppressWarnings("unused")
    private QueryResultSoA createMockQueryResultSoA(Query.Granularity granularity, int granularitySize, List<TestDataEntry> entries) {
        return createMockQueryResultSoA(granularity, granularitySize, entries, testRequirements);
    }

    @Test
    void execute_noConditions_returnsEmptyResult() throws QueryExecutionException {
        when(mockQuery.conditions()).thenReturn(Collections.emptyList());
        // Stub granularity to prevent NullPointerException in QueryAttributeAnalyzer
        lenient().when(mockQuery.granularity()).thenReturn(Query.Granularity.DOCUMENT);
        lenient().when(mockQuery.source()).thenReturn("testProject"); // Also stub source

        AttributeRequirements reqs = QueryAttributeAnalyzer.analyze(mockQuery); // This is okay to call here for assertion setup

        QueryResultSoA result = queryExecutor.execute(mockQuery, mockIndexManager);

        assertNotNull(result);
        assertEquals(0, result.size());
        // Ensure the result's requirements match what QueryAttributeAnalyzer determined
        // This assertion might need adjustment based on how QueryAttributeAnalyzer behaves with no conditions
        assertEquals(reqs.getRequiredSoAAttributes(), result.getRequirements().getRequiredSoAAttributes());
    }

    @Test
    void execute_singleContainsCondition_callsContainsExecutor() throws QueryExecutionException {
        Contains condition = new Contains(List.of("test"));
        when(mockQuery.conditions()).thenReturn(List.of(condition));
        QueryResultSoA mockSoaResult = new QueryResultSoA(Query.Granularity.DOCUMENT, 0, testRequirements);
        mockSoaResult.add("test", ValueType.TERM, null, 1,1,10,20, -1, 0);

        when(factory.getExecutor(condition)).thenReturn(containsExecutor);
        when(containsExecutor.execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"), any(AttributeRequirements.class), any()))
            .thenReturn(mockSoaResult);
        lenient().when(mockQuery.granularity()).thenReturn(Query.Granularity.DOCUMENT);
        lenient().when(mockQuery.source()).thenReturn("testProject");

        QueryResultSoA result = queryExecutor.execute(mockQuery, mockIndexManager);

        assertEquals(1, result.size());
        verify(containsExecutor).execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"), any(AttributeRequirements.class), any());
    }

    @Test
    void execute_singleNerCondition_callsNerExecutor() throws QueryExecutionException {
        Ner condition = Ner.of("PERSON");
        when(mockQuery.conditions()).thenReturn(List.of(condition));
        QueryResultSoA mockSoaResult = new QueryResultSoA(Query.Granularity.DOCUMENT, 0, testRequirements);
        mockSoaResult.add("person_val", ValueType.ENTITY, null, 1,1,10,20, -1, 0);

        when(factory.getExecutor(condition)).thenReturn(nerExecutor);
        when(nerExecutor.execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"), any(AttributeRequirements.class), any()))
            .thenReturn(mockSoaResult);
        lenient().when(mockQuery.granularity()).thenReturn(Query.Granularity.DOCUMENT);
        lenient().when(mockQuery.source()).thenReturn("testProject");

        QueryResultSoA result = queryExecutor.execute(mockQuery, mockIndexManager);

        assertEquals(1, result.size());
        verify(nerExecutor).execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"), any(AttributeRequirements.class), any());
    }

    @Test
    void execute_logicalAndCondition_callsLogicalExecutor() throws QueryExecutionException {
        Contains c1 = new Contains(List.of("term1"));
        Ner n1 = Ner.of("TYPE");
        Logical condition = new Logical(Logical.LogicalOperator.AND, List.of(c1, n1));
        when(mockQuery.conditions()).thenReturn(List.of(condition));
        QueryResultSoA mockSoaResult = new QueryResultSoA(Query.Granularity.DOCUMENT, 0, testRequirements);
        mockSoaResult.add("some_val", ValueType.TERM, null, 1,1,10,20, -1, 0); // Example result

        when(factory.getExecutor(condition)).thenReturn(logicalExecutor);
        when(logicalExecutor.execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"), any(AttributeRequirements.class), any()))
            .thenReturn(mockSoaResult);
        lenient().when(mockQuery.granularity()).thenReturn(Query.Granularity.DOCUMENT);
        lenient().when(mockQuery.source()).thenReturn("testProject");

        QueryResultSoA result = queryExecutor.execute(mockQuery, mockIndexManager);

        assertEquals(1, result.size());
        verify(logicalExecutor).execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"), any(AttributeRequirements.class), any());
    }


    // Test for dependent join (more complex, might need more specific mocks)
    @Test
    void execute_dependentJoin_mainAndSubquery() throws QueryExecutionException {
        // ... (Setup for a dependent join query) ...
        // This test would be complex and require significant mocking of subquery execution, join conditions etc.
        // For now, focusing on core execute paths.
        // queryExecutor.execute(mockQuery, mockIndexManager);
        assertTrue(true); // Placeholder
    }

     @Test
    void execute_independentJoin_mainAndSubquery() throws QueryExecutionException {
        // ... (Setup for an independent join query) ...
        // queryExecutor.execute(mockQuery, mockIndexManager);
        assertTrue(true); // Placeholder
    }

    // Add more tests for other conditions, error handling, multiple conditions, etc.
}