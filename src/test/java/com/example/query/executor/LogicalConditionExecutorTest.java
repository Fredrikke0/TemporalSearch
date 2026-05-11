package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Logical;

@ExtendWith(MockitoExtension.class)
public class LogicalConditionExecutorTest {

    @Mock
    private ConditionExecutorFactory mockFactory;
    @Mock
    private ContainsExecutor mockSubExecutor1;
    @Mock
    private ContainsExecutor mockSubExecutor2;
    @Mock
    private ContainsExecutor mockSubExecutor3;

    private LogicalExecutor logicalExecutor;
    private Map<String, IndexAccessInterface> indexes;

    private Query.Granularity testGranularity;
    private int testGranularitySize;
    private String corpusName = "test_corpus";
    private Contains condition1_term1;
    private Contains condition2_term2;
    private Contains condition3_term3;
    private AttributeRequirements defaultTestRequirements;

    @Captor
    private ArgumentCaptor<Optional<Roaring64NavigableMap>> allowedCellsCaptor;

    // Helper record for test data
    record TestDataEntry(Object value, ValueType type, String varName, int docId, int sentId,
            int begin, int end, int synId, int conceptualRowId) {
    }

    @BeforeEach
    void setUp() {
        condition1_term1 = new Contains("term1");
        condition2_term2 = new Contains("term2");
        condition3_term3 = new Contains("term3");

        lenient().when(mockFactory.getExecutor(eq(condition1_term1))).thenReturn(mockSubExecutor1);
        lenient().when(mockFactory.getExecutor(eq(condition2_term2))).thenReturn(mockSubExecutor2);
        lenient().when(mockFactory.getExecutor(eq(condition3_term3))).thenReturn(mockSubExecutor3);

        logicalExecutor = new LogicalExecutor(mockFactory, "optimized", Query.Granularity.SENTENCE);
        indexes = Collections.emptyMap();
        testGranularity = Query.Granularity.DOCUMENT;
        testGranularitySize = 0;

        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsDocumentId = true;
        defaultTestRequirements.needsSentenceId = false;
        defaultTestRequirements.needsPositions = true;
        defaultTestRequirements.needsConceptualRowIds = true;
        defaultTestRequirements.needsSynonymIds = true;
    }

    // --- CellResult helpers ---

    /**
     * Creates a CellResult with cells and bindings from test data entries.
     * Each entry contributes a cell key (packed docId+sentId) and one binding row.
     */
    private CellResult createMockCellResult(List<TestDataEntry> entries, Query.Granularity gran) {
        Roaring64NavigableMap cells = new Roaring64NavigableMap();
        Bindings.Builder bindingsBuilder = Bindings.builder();
        for (TestDataEntry entry : entries) {
            long cellKey = PostingList.packCellKey(entry.docId(), entry.sentId());
            cells.add(cellKey);
            bindingsBuilder.add(entry.value(), entry.type(), entry.varName());
        }
        Bindings bindings = bindingsBuilder.isEmpty() ? null : bindingsBuilder.build();
        return CellResult.of(cells, bindings, gran);
    }

    private CellResult createMockCellResult(List<TestDataEntry> entries) {
        return createMockCellResult(entries, this.testGranularity);
    }

    // ================================================================
    // AND tests
    // ================================================================

    @Test
    void testExecuteAnd_twoConditions_bothReturnResults() throws QueryExecutionException {
        Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                List.of(condition1_term1, condition2_term2));

        CellResult result1 = createMockCellResult(List.of(
                new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)));
        CellResult result2 = createMockCellResult(List.of(
                new TestDataEntry("term2", ValueType.TERM, "v2", 1, 1, 5, 9, -1, 0)));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result1);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result2);

        CellResult finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity,
                testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertNotNull(finalResult);
        assertFalse(finalResult.isEmpty());
        // Both conditions share cell (1,1) → intersection should have exactly 1 cell
        assertEquals(1, finalResult.cellCount());
        long expectedCell = PostingList.packCellKey(1, 1);
        assertTrue(finalResult.cells().contains(expectedCell));
        // Bindings should be present (merged from both sides)
        assertNotNull(finalResult.bindings());
    }

    @Test
    void testExecuteAnd_firstConditionEmpty() throws QueryExecutionException {
        Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                List.of(condition1_term1, condition2_term2));

        CellResult emptyResult = CellResult.empty(testGranularity);
        CellResult result2 = createMockCellResult(List.of(
                new TestDataEntry("term2", ValueType.TERM, "v2", 1, 1, 0, 4, -1, 0)));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(emptyResult);
        lenient().when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result2);

        CellResult finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity,
                testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertTrue(finalResult.isEmpty());
        assertEquals(0, finalResult.cellCount());
    }

    @Test
    void testExecuteAnd_secondConditionEmpty() throws QueryExecutionException {
        Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                List.of(condition1_term1, condition2_term2));

        CellResult result1 = createMockCellResult(List.of(
                new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)));
        CellResult emptyResult = CellResult.empty(testGranularity);

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result1);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(emptyResult);

        CellResult finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity,
                testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertTrue(finalResult.isEmpty());
        assertEquals(0, finalResult.cellCount());
    }

    @Test
    void testExecuteOr_twoConditions_bothReturnResults() throws QueryExecutionException {
        Logical orCondition = new Logical(Logical.LogicalOperator.OR,
                List.of(condition1_term1, condition2_term2));

        CellResult result1 = createMockCellResult(List.of(
                new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)));
        CellResult result2 = createMockCellResult(List.of(
                new TestDataEntry("term2", ValueType.TERM, "v2", 2, 1, 0, 4, -1, 0)));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result1);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result2);

        CellResult finalResult = logicalExecutor.execute(orCondition, indexes, testGranularity,
                testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertEquals(2, finalResult.cellCount());
        long cell1 = PostingList.packCellKey(1, 1);
        long cell2 = PostingList.packCellKey(2, 1);
        assertTrue(finalResult.cells().contains(cell1));
        assertTrue(finalResult.cells().contains(cell2));
        // Bindings should be present from the union
        assertNotNull(finalResult.bindings());
    }

    @Test
    void testExecuteOr_firstConditionEmpty() throws QueryExecutionException {
        Logical orCondition = new Logical(Logical.LogicalOperator.OR,
                List.of(condition1_term1, condition2_term2));

        CellResult emptyResult = CellResult.empty(testGranularity);
        CellResult result2 = createMockCellResult(List.of(
                new TestDataEntry("term2", ValueType.TERM, "v2", 1, 1, 0, 4, -1, 0)));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(emptyResult);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result2);

        CellResult finalResult = logicalExecutor.execute(orCondition, indexes, testGranularity,
                testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertFalse(finalResult.isEmpty());
        assertEquals(1, finalResult.cellCount());
        long expectedCell = PostingList.packCellKey(1, 1);
        assertTrue(finalResult.cells().contains(expectedCell));
        assertNotNull(finalResult.bindings());
        assertEquals("term2", finalResult.bindings().valueAt(0));
    }

    @Test
    void testExecuteOr_secondConditionEmpty() throws QueryExecutionException {
        Logical orCondition = new Logical(Logical.LogicalOperator.OR,
                List.of(condition1_term1, condition2_term2));

        CellResult result1 = createMockCellResult(List.of(
                new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)));
        CellResult emptyResult = CellResult.empty(testGranularity);

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result1);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(emptyResult);

        CellResult finalResult = logicalExecutor.execute(orCondition, indexes, testGranularity,
                testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertFalse(finalResult.isEmpty());
        assertEquals(1, finalResult.cellCount());
        long expectedCell = PostingList.packCellKey(1, 1);
        assertTrue(finalResult.cells().contains(expectedCell));
        assertNotNull(finalResult.bindings());
        assertEquals("term1", finalResult.bindings().valueAt(0));
    }

    @Test
    void testExecuteAnd_threeConditions_middleIsEmpty() throws QueryExecutionException {
        Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                List.of(condition1_term1, condition2_term2, condition3_term3));

        CellResult result1 = createMockCellResult(List.of(
                new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)));
        CellResult emptyResult = CellResult.empty(testGranularity);
        CellResult result3 = createMockCellResult(List.of(
                new TestDataEntry("term3", ValueType.TERM, "v3", 1, 1, 0, 4, -1, 0)));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result1);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(emptyResult);
        lenient().when(mockSubExecutor3.execute(eq(condition3_term3), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result3);

        CellResult finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity,
                testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertTrue(finalResult.isEmpty());
        assertEquals(0, finalResult.cellCount());
    }

    @Test
    void testExecuteOr_threeConditions_middleHasUnique() throws QueryExecutionException {
        Logical orCondition = new Logical(Logical.LogicalOperator.OR,
                List.of(condition1_term1, condition2_term2, condition3_term3));

        CellResult result1 = createMockCellResult(List.of(
                new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)));
        CellResult result2 = createMockCellResult(List.of(
                new TestDataEntry("term2", ValueType.TERM, "v2", 2, 1, 0, 4, -1, 0)));
        CellResult result3 = createMockCellResult(List.of(
                new TestDataEntry("term3", ValueType.TERM, "v3", 1, 1, 5, 9, -1, 0)));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result1);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result2);
        when(mockSubExecutor3.execute(eq(condition3_term3), any(), eq(testGranularity),
                eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result3);

        CellResult finalResult = logicalExecutor.execute(orCondition, indexes, testGranularity,
                testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        // Result1: cell(1,1), Result2: cell(2,1), Result3: cell(1,1) — union = 2 unique
        // cells
        assertEquals(2, finalResult.cellCount());
        long cell1_1 = PostingList.packCellKey(1, 1);
        long cell2_1 = PostingList.packCellKey(2, 1);
        assertTrue(finalResult.cells().contains(cell1_1));
        assertTrue(finalResult.cells().contains(cell2_1));
        assertNotNull(finalResult.bindings());
    }

    @Test
    void testMergeJoinBasic() throws QueryExecutionException {
        Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                List.of(condition1_term1, condition2_term2));

        CellResult result1 = createMockCellResult(List.of(
                new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0),
                new TestDataEntry("term1", ValueType.TERM, "v1", 3, 1, 0, 4, -1, 1)),
                Query.Granularity.DOCUMENT);
        CellResult result2 = createMockCellResult(List.of(
                new TestDataEntry("term2", ValueType.TERM, "v2", 1, 1, 5, 9, -1, 0),
                new TestDataEntry("term2", ValueType.TERM, "v2", 3, 1, 5, 9, -1, 1)),
                Query.Granularity.DOCUMENT);

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(Query.Granularity.DOCUMENT),
                eq(0), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result1);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(Query.Granularity.DOCUMENT),
                eq(0), anyString(), eq(defaultTestRequirements), any()))
                .thenReturn(result2);

        CellResult result = logicalExecutor.execute(andCondition, indexes, Query.Granularity.DOCUMENT,
                0, "test_corpus", defaultTestRequirements, Optional.empty());

        // Both share cells (1,1) and (3,1) → intersection = 2 cells
        assertEquals(2, result.cellCount());
        long cell1 = PostingList.packCellKey(1, 1);
        long cell3 = PostingList.packCellKey(3, 1);
        assertTrue(result.cells().contains(cell1));
        assertTrue(result.cells().contains(cell3));
        assertNotNull(result.bindings());
    }

    // ================================================================
    // Allowed-cells filtering tests (replaces old context pushdown tests)
    // ================================================================
    @Nested
    class AllowedCellsFilteringTests {

        @BeforeEach
        void nestedSetUp() {
            // Use default setup from outer class
        }

        @Test
        void testAllowedCellsFilter_restrictsFinalResult() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                    List.of(condition1_term1));

            CellResult mockResult = createMockCellResult(List.of(
                    new TestDataEntry("term1", ValueType.TERM, null, 1, 1, 0, 0, -1, 0),
                    new TestDataEntry("term1", ValueType.TERM, null, 2, 1, 0, 0, -1, 0)));

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(mockResult);

            // allowedCells restricts to only docId=1
            Roaring64NavigableMap allowed = new Roaring64NavigableMap();
            allowed.add(PostingList.packCellKey(1, 1));

            CellResult finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity,
                    testGranularitySize, corpusName, defaultTestRequirements, Optional.of(allowed));

            assertNotNull(finalResult);
            assertFalse(finalResult.isEmpty());
            assertEquals(1, finalResult.cellCount());
            assertTrue(finalResult.cells().contains(PostingList.packCellKey(1, 1)));
            assertFalse(finalResult.cells().contains(PostingList.packCellKey(2, 1)));
        }

        @Test
        void testAllowedCellsFilter_noOverlap_resultsInEmpty() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                    List.of(condition1_term1));

            CellResult mockResult = createMockCellResult(List.of(
                    new TestDataEntry("term1", ValueType.TERM, null, 1, 1, 0, 0, -1, 0)));

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(mockResult);

            // allowedCells has no overlap with the result
            Roaring64NavigableMap allowed = new Roaring64NavigableMap();
            allowed.add(PostingList.packCellKey(99, 1));

            CellResult finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity,
                    testGranularitySize, corpusName, defaultTestRequirements, Optional.of(allowed));

            assertTrue(finalResult.isEmpty());
            assertEquals(0, finalResult.cellCount());
        }

        @Test
        void testAllowedCellsFilter_emptyAllowedCells_noFiltering() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                    List.of(condition1_term1));

            CellResult mockResult = createMockCellResult(List.of(
                    new TestDataEntry("term1", ValueType.TERM, null, 1, 1, 0, 0, -1, 0)));

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(mockResult);

            CellResult finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity,
                    testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

            // No filtering → result unchanged
            assertEquals(1, finalResult.cellCount());
            assertTrue(finalResult.cells().contains(PostingList.packCellKey(1, 1)));
        }

        @Test
        void testAnd_cellIntersection_viaCellResultAnd() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                    List.of(condition1_term1, condition2_term2));

            // Result1: cells (1,1) and (2,1)
            CellResult result1 = createMockCellResult(List.of(
                    new TestDataEntry("term1", ValueType.TERM, null, 1, 1, 0, 0, -1, 0),
                    new TestDataEntry("term1", ValueType.TERM, null, 2, 1, 0, 0, -1, 1)));

            // Result2: cells (2,1) and (3,1) — only (2,1) intersects with result1
            CellResult result2 = createMockCellResult(List.of(
                    new TestDataEntry("term2", ValueType.TERM, null, 2, 1, 0, 0, -1, 0),
                    new TestDataEntry("term2", ValueType.TERM, null, 3, 1, 0, 0, -1, 1)));

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(result1);
            when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(result2);

            CellResult finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity,
                    testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

            assertEquals(1, finalResult.cellCount(),
                    "Only cell (2,1) should be in the intersection");
            assertTrue(finalResult.cells().contains(PostingList.packCellKey(2, 1)));
            assertFalse(finalResult.cells().contains(PostingList.packCellKey(1, 1)));
            assertFalse(finalResult.cells().contains(PostingList.packCellKey(3, 1)));
        }

        @Test
        void testAnd_cellIntersection_sentenceGranularity() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                    List.of(condition1_term1, condition2_term2));
            Query.Granularity sentGran = Query.Granularity.SENTENCE;

            // Result1: (1,10), (1,11), (2,20)
            CellResult result1 = createMockCellResult(List.of(
                    new TestDataEntry("term1", ValueType.TERM, null, 1, 10, 0, 0, -1, 0),
                    new TestDataEntry("term1", ValueType.TERM, null, 1, 11, 0, 0, -1, 1),
                    new TestDataEntry("term1", ValueType.TERM, null, 2, 20, 0, 0, -1, 2)),
                    sentGran);

            // Result2: (1,11) only
            CellResult result2 = createMockCellResult(List.of(
                    new TestDataEntry("term2", ValueType.TERM, null, 1, 11, 0, 0, -1, 0)),
                    sentGran);

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(sentGran),
                    eq(testGranularitySize), anyString(), any(), any())).thenReturn(result1);
            when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(sentGran),
                    eq(testGranularitySize), anyString(), any(), any())).thenReturn(result2);

            CellResult finalResult = logicalExecutor.execute(andCondition, indexes, sentGran,
                    testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

            assertEquals(1, finalResult.cellCount(),
                    "Only cell (1,11) should be in the intersection");
            assertTrue(finalResult.cells().contains(PostingList.packCellKey(1, 11)));
        }

        @Test
        void testAnd_emptyResultFromFirstCondition_finalResultEmpty() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                    List.of(condition1_term1, condition2_term2));

            CellResult emptyResult1 = CellResult.empty(testGranularity);
            CellResult result2 = createMockCellResult(List.of(
                    new TestDataEntry("term2", ValueType.TERM, null, 1, 1, 0, 0, -1, 0)));

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(emptyResult1);
            lenient().when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(result2);

            CellResult finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity,
                    testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

            assertTrue(finalResult.isEmpty());
            assertEquals(0, finalResult.cellCount());
        }

        @Test
        void testAnd_threeConditions_chainedIntersection() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                    List.of(condition1_term1, condition2_term2, condition3_term3));

            // Result1: cells (1,1) and (2,1)
            CellResult result1 = createMockCellResult(List.of(
                    new TestDataEntry("term1", ValueType.TERM, "c1", 1, 1, 0, 0, -1, 0),
                    new TestDataEntry("term1", ValueType.TERM, "c1", 2, 1, 0, 0, -1, 1)));

            // Result2: cells (2,1) and (3,1)
            CellResult result2 = createMockCellResult(List.of(
                    new TestDataEntry("term2", ValueType.TERM, "c2", 2, 1, 0, 0, -1, 0),
                    new TestDataEntry("term2", ValueType.TERM, "c2", 3, 1, 0, 0, -1, 1)));

            // Result3: cells (1,1), (2,1), (4,1)
            CellResult result3 = createMockCellResult(List.of(
                    new TestDataEntry("term3", ValueType.TERM, "c3", 1, 1, 0, 0, -1, 0),
                    new TestDataEntry("term3", ValueType.TERM, "c3", 2, 1, 0, 0, -1, 1),
                    new TestDataEntry("term3", ValueType.TERM, "c3", 4, 1, 0, 0, -1, 2)));

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(result1);
            when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(result2);
            when(mockSubExecutor3.execute(eq(condition3_term3), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(result3);

            CellResult finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity,
                    testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

            // result1 ∩ result2 = {2,1}, then {2,1} ∩ result3 = {2,1}
            assertEquals(1, finalResult.cellCount(),
                    "Only cell (2,1) should survive all three ANDs");
            assertTrue(finalResult.cells().contains(PostingList.packCellKey(2, 1)));
            assertFalse(finalResult.cells().contains(PostingList.packCellKey(1, 1)));
        }

        @Test
        void testAnd_emptyResultFromMiddleCondition_stopsChain() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                    List.of(condition1_term1, condition2_term2, condition3_term3));

            CellResult result1 = createMockCellResult(List.of(
                    new TestDataEntry("term1", ValueType.TERM, "c1", 1, 1, 0, 0, -1, 0),
                    new TestDataEntry("term1", ValueType.TERM, "c1", 2, 1, 0, 0, -1, 1)));

            CellResult emptyResult2 = CellResult.empty(testGranularity);

            CellResult result3 = createMockCellResult(List.of(
                    new TestDataEntry("term3", ValueType.TERM, "c3", 2, 1, 0, 0, -1, 0)));

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(result1);
            when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(emptyResult2);
            lenient().when(mockSubExecutor3.execute(eq(condition3_term3), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any()))
                    .thenReturn(result3);

            CellResult finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity,
                    testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

            assertTrue(finalResult.isEmpty(),
                    "Final result should be empty if a middle condition is empty");
            assertEquals(0, finalResult.cellCount());
        }

        @Test
        void testAnd_subConditions_receiveEmptyAllowedCells() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND,
                    List.of(condition1_term1, condition2_term2));

            CellResult result1 = createMockCellResult(List.of(
                    new TestDataEntry("term1", ValueType.TERM, null, 1, 1, 0, 0, -1, 0)));
            CellResult result2 = createMockCellResult(List.of(
                    new TestDataEntry("term2", ValueType.TERM, null, 2, 1, 0, 0, -1, 0)));

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements),
                    allowedCellsCaptor.capture())).thenReturn(result1);
            when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity),
                    eq(testGranularitySize), anyString(), eq(defaultTestRequirements),
                    allowedCellsCaptor.capture())).thenReturn(result2);

            logicalExecutor.execute(andCondition, indexes, testGranularity,
                    testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

            List<Optional<Roaring64NavigableMap>> captured = allowedCellsCaptor.getAllValues();
            // Both sub-conditions should receive Optional.empty() since context
            // chaining has been replaced by CellResult.and()
            for (Optional<Roaring64NavigableMap> capturedAllowed : captured) {
                assertTrue(capturedAllowed.isEmpty(),
                        "Sub-conditions should receive empty allowedCells");
            }
        }
    }
}
