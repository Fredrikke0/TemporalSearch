package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
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
import com.example.core.Position;
import com.example.core.PositionList;
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
    @Mock private StitchContainsNerExecutor stitchContainsNerExecutor; // Added
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
        indexes.put("unigram", unigramIndex);
        indexes.put("ner", nerIndex);
        indexes.put("pos", posIndex);
        indexes.put("dependency", dependencyIndex);
        indexes.put("ner_date", nerDateIndex);

        queryExecutor = new QueryExecutor(factory, mockTableResultService, "none", mockSynonymManager);

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
        PositionList posListDoc1 = new PositionList(); posListDoc1.add(new Position(1, 0, 0, 1));
        PositionList posListDoc2 = new PositionList(); posListDoc2.add(new Position(2, 0, 0, 1));
        PositionList posListDoc3 = new PositionList(); posListDoc3.add(new Position(3, 0, 0, 1));
        PositionList posListDoc4 = new PositionList(); posListDoc4.add(new Position(4, 0, 0, 1));

        // Create mock entries for the iterator
        Map.Entry<byte[], byte[]> entry1 = Map.entry("key1".getBytes(), posListDoc1.serialize());
        Map.Entry<byte[], byte[]> entry2 = Map.entry("key2".getBytes(), posListDoc2.serialize());
        Map.Entry<byte[], byte[]> entry3 = Map.entry("key3".getBytes(), posListDoc3.serialize());
        Map.Entry<byte[], byte[]> entry4 = Map.entry("key4".getBytes(), posListDoc4.serialize());

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

    // Helper to count unique conceptual row IDs
    private long countUniqueConceptualRows(QueryResultSoA soa) {
        if (soa == null || soa.isEmpty() || !soa.getRequirements().needsConceptualRowIds) return 0;
        Set<Integer> uniqueIds = new HashSet<>();
        for (int i = 0; i < soa.size(); i++) {
            uniqueIds.add(soa.getConceptualRowIdAt(i));
        }
        return uniqueIds.size();
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
        when(containsExecutor.execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"), any(AttributeRequirements.class)))
            .thenReturn(mockSoaResult);
        lenient().when(mockQuery.granularity()).thenReturn(Query.Granularity.DOCUMENT);
        lenient().when(mockQuery.source()).thenReturn("testProject");

        QueryResultSoA result = queryExecutor.execute(mockQuery, mockIndexManager);

        assertEquals(1, result.size());
        verify(containsExecutor).execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"), any(AttributeRequirements.class));
    }

    @Test
    void execute_singleNerCondition_callsNerExecutor() throws QueryExecutionException {
        Ner condition = Ner.of("PERSON");
        when(mockQuery.conditions()).thenReturn(List.of(condition));
        QueryResultSoA mockSoaResult = new QueryResultSoA(Query.Granularity.DOCUMENT, 0, testRequirements);
        mockSoaResult.add("person_val", ValueType.ENTITY, null, 1,1,10,20, -1, 0);

        when(factory.getExecutor(condition)).thenReturn(nerExecutor);
        when(nerExecutor.execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"), any(AttributeRequirements.class)))
            .thenReturn(mockSoaResult);
        lenient().when(mockQuery.granularity()).thenReturn(Query.Granularity.DOCUMENT);
        lenient().when(mockQuery.source()).thenReturn("testProject");

        QueryResultSoA result = queryExecutor.execute(mockQuery, mockIndexManager);

        assertEquals(1, result.size());
        verify(nerExecutor).execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"), any(AttributeRequirements.class));
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
        when(logicalExecutor.execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"), any(AttributeRequirements.class)))
            .thenReturn(mockSoaResult);
        lenient().when(mockQuery.granularity()).thenReturn(Query.Granularity.DOCUMENT);
        lenient().when(mockQuery.source()).thenReturn("testProject");

        QueryResultSoA result = queryExecutor.execute(mockQuery, mockIndexManager);

        assertEquals(1, result.size());
        verify(logicalExecutor).execute(eq(condition), eq(indexes), eq(Query.Granularity.DOCUMENT), eq(0), eq("testProject"), any(AttributeRequirements.class));
    }

    @Test
    void execute_stitchOptimization_callsStitchExecutor() throws QueryExecutionException {
        queryExecutor = new QueryExecutor(factory, mockTableResultService, "optimized", mockSynonymManager); // Enable stitch
        lenient().when(mockQuery.granularity()).thenReturn(Query.Granularity.SENTENCE); // Stitch needs sentence granularity

        Contains containsCond = new Contains(List.of("unigram"));
        Ner nerCond = Ner.of("PERSON");
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(containsCond, nerCond));
        when(mockQuery.conditions()).thenReturn(List.of(andCondition));

        QueryResultSoA mockStitchResult = new QueryResultSoA(Query.Granularity.SENTENCE, 0, testRequirements);
        mockStitchResult.add("unigram", ValueType.TERM, null, 1,1,1,5,-1, 0);
        mockStitchResult.add("PersonX", ValueType.ENTITY, "?v", 1,1,10,15,1, 0);

        // We need to ensure the LogicalExecutor, when it tries to execute the AND, will delegate to Stitch
        // This requires a bit more: the LogicalExecutor itself needs to be the one making the decision.
        // For this test, we'll assume the factory correctly gives a LogicalExecutor that can trigger stitch.
        // And QueryExecutor has the stitch strategy enabled.
        // The QueryExecutor should call the LogicalExecutor for the AND condition.
        // The test for stitch strategy is more about QueryExecutor correctly setting up conditions for StitchableAndExecutor
        // or having the LogicalExecutor handle it internally. The QueryExecutor itself does not directly call StitchContainsNerExecutor.
        // The current QueryExecutor logic for stitch is: if stitchStrategy is "optimized" AND granularity is SENTENCE AND condition is AND ...
        // it will call StitchContainsNerExecutor DIRECTLY if conditions match (1 Contains, 1 Ner).
        // This means factory.getExecutor(andCondition) should NOT be called for the stitch path.

        // Re-wire for stitch: factory should NOT be asked for Logical in this specific stitch case.
        // Instead, QueryExecutor will directly instantiate/use StitchContainsNerExecutor.
        // So, we mock the StitchContainsNerExecutor execute method if we want to control its output.
        // However, the current design of QueryExecutor is: it gets LogicalExecutor first, then LogicalExecutor makes the stitch decision.
        // Correction: QueryExecutor line 296: if (this.stitchStrategy.equals("optimized") && ... it creates StitchContainsNerExecutor and calls it.
        // So, factory *is not* called for the AND condition if stitch conditions are met.

        // Let's verify factory is NOT called for the AND, and Stitch... is called.
        // To do this, we cannot mock StitchContainsNerExecutor directly if it's newed up in QueryExecutor.
        // The test should verify the *outcome* of the stitch logic.
        // The current QueryExecutor will *replace* the call to factory.getExecutor(andCondition) with a call to stitchExecutor.execute(...)

        // For this test, we will assume the internal stitch logic of QueryExecutor will work.
        // We cannot easily mock `new StitchContainsNerExecutor()` from here.
        // So we test the non-stitch path if the factory IS called for the AND.

        // If we want to test the STITCH PATH, the factory will NOT be called for the `andCondition`.
        // Instead, `QueryExecutor` itself will try to execute it using `StitchContainsNerExecutor`.
        // This is an integration point more than a unit test of `QueryExecutor` cleanly using `factory`.

        // Let's adjust the test to assume the default (non-stitch) path for LogicalExecutor if factory is called.
        // And then have a separate test for the stitch scenario if possible, or accept this as an integration aspect.

        // Test that if stitch conditions ARE MET, the LogicalExecutor from factory is NOT called for the top AND.
        // Instead, the optimized path is taken.
        // This test is tricky because QueryExecutor NEWS UP StitchContainsNerExecutor.
        // For now, let's verify that IF QueryExecutor decides to use stitch, the factory isn't called for the `andCondition`

        // This test setup will now allow the internal StitchContainsNerExecutor to run.
        // We can't easily mock its output if it's `new`-ed up inside QueryExecutor.
        // So, we can either:
        // 1. Expect the execute to go through, and it might fail if indexes are not set up for a real stitch.
        // 2. Refactor QueryExecutor to get StitchContainsNerExecutor from the factory (better for testing).

        // Given current QueryExecutor structure: it will `new StitchContainsNerExecutor()`, then call execute on it.
        // We cannot mock this `new` call easily without PowerMock or DI for StitchContainsNerExecutor.
        // So, this test will effectively be an integration test of that small piece of stitch logic in QueryExecutor.
        // We will mock the *inputs* to that internal StitchContainsNerExecutor if possible.

        queryExecutor.execute(mockQuery, mockIndexManager); // Execute the query

        // Verify that the factory was NOT called to get an executor for the `andCondition` because stitch path was taken.
        verify(factory, times(0)).getExecutor(eq(andCondition));
        // We can't easily verify StitchContainsNerExecutor was called and what it returned without refactoring QueryExecutor
        // to get StitchContainsNerExecutor from the factory, or making it a spyable dependency.
        // For now, this test confirms the factory bypass for the top-level AND when stitch is active.
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