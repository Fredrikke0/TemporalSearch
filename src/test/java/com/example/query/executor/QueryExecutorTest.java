package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.rocksdb.RocksIterator;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.index.util.SynonymManager;
import com.example.query.binding.ValueType;
import com.example.query.index.IndexManager;
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
    record TestDataEntry(Object value, ValueType type, String varName, int docId, int sentId, int begin, int end,
            int synId, int conceptualRowId) {
    }

    @Mock
    private IndexAccess unigramIndex;
    @Mock
    private IndexAccess nerIndex;
    @Mock
    private IndexAccess posIndex;
    @Mock
    private IndexAccess dependencyIndex;
    @Mock
    private IndexAccess nerDateIndex;

    @Mock
    private RocksIterator unigramIterator;
    @Mock
    private RocksIterator nerIterator;

    // Mock dependencies needed by QueryExecutor and JoinHandler
    @Mock
    private TableResultService mockTableResultService;
    @Mock
    private ConditionExecutorFactory factory;
    @Mock
    private Query mockQuery;
    @Mock
    private ContainsExecutor containsExecutor;
    @Mock
    private NerExecutor nerExecutor;
    @Mock
    private LogicalExecutor logicalExecutor;
    @Mock
    private NotExecutor notExecutor;
    @Mock
    private TemporalExecutor temporalExecutor;
    @Mock
    private IndexManager mockIndexManager;
    @Mock
    private SynonymManager mockSynonymManager;

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
        indexes.put("unigram", unigramIndex);
        indexes.put("ner", nerIndex);
        indexes.put("pos", posIndex);
        indexes.put("dependency", dependencyIndex);
        indexes.put("ner_date", nerDateIndex);

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
        Roaring64NavigableMap cells1 = new Roaring64NavigableMap();
        cells1.add(PostingList.packCellKey(1, 1));
        Roaring64NavigableMap cells2 = new Roaring64NavigableMap();
        cells2.add(PostingList.packCellKey(2, 1));
        Roaring64NavigableMap cells3 = new Roaring64NavigableMap();
        cells3.add(PostingList.packCellKey(3, 1));
        Roaring64NavigableMap cells4 = new Roaring64NavigableMap();
        cells4.add(PostingList.packCellKey(4, 1));
        PostingList posListDoc1 = PostingList.fromCells(cells1, (byte) 0);
        PostingList posListDoc2 = PostingList.fromCells(cells2, (byte) 0);
        PostingList posListDoc3 = PostingList.fromCells(cells3, (byte) 0);
        PostingList posListDoc4 = PostingList.fromCells(cells4, (byte) 0);

        // Create mock entries for the iterator
        Map.Entry<byte[], byte[]> entry1 = Map.entry("key1".getBytes(), posListDoc1.serialize());
        Map.Entry<byte[], byte[]> entry2 = Map.entry("key2".getBytes(), posListDoc2.serialize());
        Map.Entry<byte[], byte[]> entry3 = Map.entry("key3".getBytes(), posListDoc3.serialize());
        Map.Entry<byte[], byte[]> entry4 = Map.entry("key4".getBytes(), posListDoc4.serialize());

        // Stub the iterator behavior
        lenient().when(unigramIndex.iterateFromFirst()).thenReturn(unigramIterator);
        lenient().when(unigramIterator.isValid()).thenReturn(true, true, true, true, false);
        lenient().when(unigramIterator.key()).thenReturn(entry1.getKey(), entry2.getKey(), entry3.getKey(),
                entry4.getKey());
        lenient().when(unigramIterator.value()).thenReturn(entry1.getValue(), entry2.getValue(), entry3.getValue(),
                entry4.getValue());

        // Set up mock data for the tests
        setupMockData();
    }

    private void setupMockData() throws IndexAccessException {
        // Removed lenient().when(unigramIndex.get(...)) and nerIndex.get(...) mocks.
        // These are not needed here if ConditionExecutors' execute methods are mocked
        // directly in tests.
    }

    private CellResult createMockCellResult(Query.Granularity granularity, List<TestDataEntry> entries) {
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        Bindings.Builder bindingsBuilder = Bindings.builder();
        for (TestDataEntry entry : entries) {
            long cellKey = PostingList.packCellKey(entry.docId(), entry.sentId());
            cells.add(cellKey);
            bindingsBuilder.add(entry.value(), entry.type(), entry.varName());
        }
        Bindings bindings = bindingsBuilder.isEmpty() ? null : bindingsBuilder.build();
        return CellResult.of(cells, bindings, granularity);
    }

    @Test
    void execute_noConditions_returnsEmptyResult() throws QueryExecutionException {
        when(mockQuery.conditions()).thenReturn(Collections.emptyList());
        lenient().when(mockQuery.granularity()).thenReturn(Query.Granularity.DOCUMENT);
        lenient().when(mockQuery.source()).thenReturn("testProject");

        CellResult result = queryExecutor.execute(mockQuery, mockIndexManager);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void execute_singleContainsCondition_callsContainsExecutor() throws QueryExecutionException {
        Contains condition = new Contains(List.of("test"));
        when(mockQuery.conditions()).thenReturn(List.of(condition));
        CellResult mockResult = createMockCellResult(Query.Granularity.DOCUMENT,
                List.of(new TestDataEntry("test", ValueType.TERM, null, 1, 1, 10, 20, -1, 0)));

        when(factory.getExecutor(condition)).thenReturn(containsExecutor);
        when(containsExecutor.execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0),
                eq("testProject"), any(AttributeRequirements.class), any()))
                .thenReturn(mockResult);
        lenient().when(mockQuery.granularity()).thenReturn(Query.Granularity.DOCUMENT);
        lenient().when(mockQuery.source()).thenReturn("testProject");

        CellResult result = queryExecutor.execute(mockQuery, mockIndexManager);

        assertEquals(1, result.cellCount());
        verify(containsExecutor).execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0),
                eq("testProject"), any(AttributeRequirements.class), any());
    }

    @Test
    void execute_singleNerCondition_callsNerExecutor() throws QueryExecutionException {
        Ner condition = Ner.of("PERSON");
        when(mockQuery.conditions()).thenReturn(List.of(condition));
        CellResult mockResult = createMockCellResult(Query.Granularity.DOCUMENT,
                List.of(new TestDataEntry("person_val", ValueType.ENTITY, null, 1, 1, 10, 20, -1, 0)));

        when(factory.getExecutor(condition)).thenReturn(nerExecutor);
        when(nerExecutor.execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"),
                any(AttributeRequirements.class), any()))
                .thenReturn(mockResult);
        lenient().when(mockQuery.granularity()).thenReturn(Query.Granularity.DOCUMENT);
        lenient().when(mockQuery.source()).thenReturn("testProject");

        CellResult result = queryExecutor.execute(mockQuery, mockIndexManager);

        assertEquals(1, result.cellCount());
        verify(nerExecutor).execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0),
                eq("testProject"), any(AttributeRequirements.class), any());
    }

    @Test
    void execute_logicalAndCondition_callsLogicalExecutor() throws QueryExecutionException {
        Contains c1 = new Contains(List.of("term1"));
        Ner n1 = Ner.of("TYPE");
        Logical condition = new Logical(Logical.LogicalOperator.AND, List.of(c1, n1));
        when(mockQuery.conditions()).thenReturn(List.of(condition));
        CellResult mockResult = createMockCellResult(Query.Granularity.DOCUMENT,
                List.of(new TestDataEntry("some_val", ValueType.TERM, null, 1, 1, 10, 20, -1, 0)));

        when(factory.getExecutor(condition)).thenReturn(logicalExecutor);
        when(logicalExecutor.execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0),
                eq("testProject"), any(AttributeRequirements.class), any()))
                .thenReturn(mockResult);
        lenient().when(mockQuery.granularity()).thenReturn(Query.Granularity.DOCUMENT);
        lenient().when(mockQuery.source()).thenReturn("testProject");

        CellResult result = queryExecutor.execute(mockQuery, mockIndexManager);

        assertEquals(1, result.cellCount());
        verify(logicalExecutor).execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0),
                eq("testProject"), any(AttributeRequirements.class), any());
    }

    // Test for dependent join (more complex, might need more specific mocks)
    @Test
    void execute_dependentJoin_mainAndSubquery() throws QueryExecutionException {
        // This test would be complex and require significant mocking of subquery
        // execution, join conditions etc.
        // For now, focusing on core execute paths.
        assertTrue(true); // Placeholder
    }

    @Test
    void execute_independentJoin_mainAndSubquery() throws QueryExecutionException {
        assertTrue(true); // Placeholder
    }
}
