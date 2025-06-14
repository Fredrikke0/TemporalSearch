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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.core.IndexAccessInterface;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Logical;

@ExtendWith(MockitoExtension.class)
public class LogicalConditionExecutorTest {

    @Mock private ConditionExecutorFactory mockFactory;
    @Mock private ContainsExecutor mockSubExecutor1;
    @Mock private ContainsExecutor mockSubExecutor2;
    @Mock private ContainsExecutor mockSubExecutor3;

    private LogicalExecutor logicalExecutor;
    private Map<String, IndexAccessInterface> indexes;

    private Query.Granularity testGranularity; // Default set in setUp
    private int testGranularitySize;
    private String corpusName = "test_corpus";
    private Contains condition1_term1;
    private Contains condition2_term2;
    private Contains condition3_term3;
    private AttributeRequirements defaultTestRequirements; // Default set in setUp

    @Captor
    private ArgumentCaptor<Optional<FilteringContext>> contextCaptor;

    // Helper record for test data
    record TestDataEntry(Object value, ValueType type, String varName, int docId, int sentId, int begin, int end, int synId, int conceptualRowId) {}

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
        testGranularity = Query.Granularity.DOCUMENT; // Default for most existing tests
        testGranularitySize = 0;

        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsDocumentId = true;
        defaultTestRequirements.needsSentenceId = false; // Default to document-level context behavior
        defaultTestRequirements.needsPositions = true;
        defaultTestRequirements.needsConceptualRowIds = true;
        defaultTestRequirements.needsSynonymIds = true;
    }

    // Base helper to create QueryResultSoA for testing.
    // The `reqs` parameter's `needsSentenceId` field determines how FilteringContext.intersect behaves.
    private QueryResultSoA createMockQueryResultSoA(List<TestDataEntry> entries, Query.Granularity gran, int granSize, AttributeRequirements reqs) {
        QueryResultSoA soaResult = new QueryResultSoA(gran, granSize, reqs);
        for (TestDataEntry entry : entries) {
            soaResult.add(
                entry.value(), entry.type(), entry.varName(),
                entry.docId(), entry.sentId(),
                entry.begin(), entry.end(), entry.synId(),
                entry.conceptualRowId()
            );
        }
        return soaResult;
    }

    // Overload using default test granularity and size from setUp, with specified requirements
    private QueryResultSoA createMockQueryResultSoA(List<TestDataEntry> entries, AttributeRequirements customReqs) {
        return createMockQueryResultSoA(entries, this.testGranularity, this.testGranularitySize, customReqs);
    }

    // Overload using all default test settings from setUp (default granularity, size, and requirements)
    private QueryResultSoA createMockQueryResultSoA(List<TestDataEntry> entries) {
        return createMockQueryResultSoA(entries, this.testGranularity, this.testGranularitySize, this.defaultTestRequirements);
    }

    private List<Map<String, Object>> getBindingsForConceptualId(QueryResultSoA soa, int conceptualId) {
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (int i = 0; i < soa.size(); i++) {
            if (soa.getConceptualRowIdAt(i) == conceptualId) {
                Map<String, Object> binding = new HashMap<>();
                binding.put("value", soa.getValueAt(i));
                binding.put("type", soa.getValueTypeAt(i));
                binding.put("variableName", soa.getVariableNameAt(i));
                binding.put("docId", soa.getDocumentIdAt(i));
                if (soa.getRequirements().needsSentenceId) {
                binding.put("sentId", soa.getSentenceIdAt(i));
                } else {
                    binding.put("sentId", -1); // Placeholder if sentence ID is not available/required
                }
                bindings.add(binding);
            }
        }
        return bindings;
    }

    private long countUniqueConceptualRows(QueryResultSoA soa) {
        if (soa.isEmpty()) return 0;
        Set<Integer> uniqueIds = new HashSet<>();
        for (int i = 0; i < soa.size(); i++) {
            uniqueIds.add(soa.getConceptualRowIdAt(i));
        }
        return uniqueIds.size();
    }

    @Test
    void testExecuteAnd_twoConditions_bothReturnResults() throws QueryExecutionException {
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2));
        // Using defaultTestRequirements (document granularity, needsSentenceId=false)
        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)
        ));
        QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term2", ValueType.TERM, "v2", 1, 1, 5, 9, -1, 0)
        ));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result2SoA);

        QueryResultSoA finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertNotNull(finalResult);
        assertEquals(1, countUniqueConceptualRows(finalResult));
        assertEquals(2, finalResult.size());
        List<Map<String, Object>> conceptualRowBindings = getBindingsForConceptualId(finalResult, finalResult.getConceptualRowIdAt(0));
        assertEquals(2, conceptualRowBindings.size());
        boolean foundTerm1 = false;
        boolean foundTerm2 = false;
        for (Map<String, Object> binding : conceptualRowBindings) {
            assertEquals(1, binding.get("docId"));
            assertEquals(-1, binding.get("sentId"));
            if ("term1".equals(binding.get("value")) && "v1".equals(binding.get("variableName"))) {
                foundTerm1 = true;
            } else if ("term2".equals(binding.get("value")) && "v2".equals(binding.get("variableName"))) {
                foundTerm2 = true;
            }
        }
        assertTrue(foundTerm1);
        assertTrue(foundTerm2);
    }

    @Test
    void testExecuteAnd_firstConditionEmpty() throws QueryExecutionException {
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2));
        QueryResultSoA emptyResultSoA = createMockQueryResultSoA(Collections.emptyList());
        QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term2", ValueType.TERM, "v2", 1, 1, 0, 4, -1, 0)
        ));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(emptyResultSoA);
        lenient().when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result2SoA);

        QueryResultSoA finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertTrue(finalResult.isEmpty());
        assertEquals(0, countUniqueConceptualRows(finalResult));
    }

    @Test
    void testExecuteAnd_secondConditionEmpty() throws QueryExecutionException {
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2));
        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)
        ));
        QueryResultSoA emptyResultSoA = createMockQueryResultSoA(Collections.emptyList());

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(emptyResultSoA);

        QueryResultSoA finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertTrue(finalResult.isEmpty());
        assertEquals(0, countUniqueConceptualRows(finalResult));
    }

    @Test
    void testExecuteOr_twoConditions_bothReturnResults() throws QueryExecutionException {
        Logical orCondition = new Logical(Logical.LogicalOperator.OR, List.of(condition1_term1, condition2_term2));
        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)
        ));
        QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term2", ValueType.TERM, "v2", 2, 1, 0, 4, -1, 0)
        ));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result2SoA);

        QueryResultSoA finalResult = logicalExecutor.execute(orCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertEquals(2, finalResult.size());
        assertEquals(2, countUniqueConceptualRows(finalResult));
        Set<Object> values = new HashSet<>();
        Set<Integer> conceptualIds = new HashSet<>();
        for(int i=0; i < finalResult.size(); i++) {
            values.add(finalResult.getValueAt(i));
            conceptualIds.add(finalResult.getConceptualRowIdAt(i));
        }
        assertTrue(values.contains("term1"));
        assertTrue(values.contains("term2"));
        assertEquals(2, conceptualIds.size());
    }

    @Test
    void testExecuteOr_firstConditionEmpty() throws QueryExecutionException {
        Logical orCondition = new Logical(Logical.LogicalOperator.OR, List.of(condition1_term1, condition2_term2));
        QueryResultSoA emptyResultSoA = createMockQueryResultSoA(Collections.emptyList());
        QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term2", ValueType.TERM, "v2", 1, 1, 0, 4, -1, 0)
        ));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(emptyResultSoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result2SoA);

        QueryResultSoA finalResult = logicalExecutor.execute(orCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertFalse(finalResult.isEmpty());
        assertEquals(result2SoA.size(), finalResult.size());
        assertEquals(1, countUniqueConceptualRows(finalResult));
        assertEquals("term2", finalResult.getValueAt(0));
        assertEquals(0, finalResult.getConceptualRowIdAt(0));
    }

    @Test
    void testExecuteOr_secondConditionEmpty() throws QueryExecutionException {
        Logical orCondition = new Logical(Logical.LogicalOperator.OR, List.of(condition1_term1, condition2_term2));
        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)
        ));
        QueryResultSoA emptyResultSoA = createMockQueryResultSoA(Collections.emptyList());

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(emptyResultSoA);

        QueryResultSoA finalResult = logicalExecutor.execute(orCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertFalse(finalResult.isEmpty());
        assertEquals(result1SoA.size(), finalResult.size());
        assertEquals(1, countUniqueConceptualRows(finalResult));
        assertEquals("term1", finalResult.getValueAt(0));
        assertEquals(0, finalResult.getConceptualRowIdAt(0));
    }

    @Test
    void testExecuteAnd_threeConditions_middleIsEmpty() throws QueryExecutionException {
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2, condition3_term3));
        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)));
        QueryResultSoA emptyResultSoA = createMockQueryResultSoA(Collections.emptyList());
        QueryResultSoA result3SoA = createMockQueryResultSoA(List.of(new TestDataEntry("term3", ValueType.TERM, "v3", 1, 1, 0, 4, -1, 0)));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(emptyResultSoA);
        lenient().when(mockSubExecutor3.execute(eq(condition3_term3), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result3SoA);

        QueryResultSoA finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertTrue(finalResult.isEmpty());
        assertEquals(0, countUniqueConceptualRows(finalResult));
    }

    @Test
    void testExecuteOr_threeConditions_middleHasUnique() throws QueryExecutionException {
        Logical orCondition = new Logical(Logical.LogicalOperator.OR, List.of(condition1_term1, condition2_term2, condition3_term3));
        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)
        ));
        QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term2", ValueType.TERM, "v2", 2, 1, 0, 4, -1, 1)
        ));
        QueryResultSoA result3SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term3", ValueType.TERM, "v3", 1, 1, 5, 9, -1, 2)
        ));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result2SoA);
        when(mockSubExecutor3.execute(eq(condition3_term3), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements), any())).thenReturn(result3SoA);

        QueryResultSoA finalResult = logicalExecutor.execute(orCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements, Optional.empty());

        assertEquals(3, finalResult.size());
        assertEquals(3, countUniqueConceptualRows(finalResult));
        Set<Object> values = new HashSet<>();
        for(int i=0; i < finalResult.size(); i++) {
            values.add(finalResult.getValueAt(i));
        }
        assertTrue(values.containsAll(Set.of("term1", "term2", "term3")));
        Set<Integer> finalConceptualIds = new HashSet<>();
        for (int i = 0; i < finalResult.size(); i++) {
            finalConceptualIds.add(finalResult.getConceptualRowIdAt(i));
        }
        assertEquals(3, finalConceptualIds.size());
        boolean term1Found = false;
        for(int i=0; i<finalResult.size(); i++){
            if("term1".equals(finalResult.getValueAt(i))) {
                assertTrue(finalConceptualIds.contains(finalResult.getConceptualRowIdAt(i)));
                term1Found = true;
            }
        }
        assertTrue(term1Found);
    }

    @Test
    void testMergeJoinBasic() throws QueryExecutionException {
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2));

        AttributeRequirements docGranularityReqs = new AttributeRequirements();
        docGranularityReqs.needsDocumentId = true;
        docGranularityReqs.needsSentenceId = false; // Explicitly document level for this test
        docGranularityReqs.needsPositions = true;
        docGranularityReqs.needsConceptualRowIds = true;

        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0),
            new TestDataEntry("term1", ValueType.TERM, "v1", 3, 1, 0, 4, -1, 1)
        ), Query.Granularity.DOCUMENT, 0, docGranularityReqs);
        QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term2", ValueType.TERM, "v2", 1, 1, 5, 9, -1, 0),
            new TestDataEntry("term2", ValueType.TERM, "v2", 3, 1, 5, 9, -1, 1)
        ), Query.Granularity.DOCUMENT, 0, docGranularityReqs);

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(Query.Granularity.DOCUMENT), eq(0), anyString(), eq(docGranularityReqs), any())).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(Query.Granularity.DOCUMENT), eq(0), anyString(), eq(docGranularityReqs), any())).thenReturn(result2SoA);

        QueryResultSoA result = logicalExecutor.execute(andCondition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", docGranularityReqs, Optional.empty());

        assertEquals(4, result.size());
        assertEquals(2, countUniqueConceptualRows(result));
        Set<Integer> actualDocIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            actualDocIds.add(result.getDocumentIdAt(i));
        }
        assertTrue(actualDocIds.contains(1));
        assertTrue(actualDocIds.contains(3));
        assertEquals(2, actualDocIds.size());
    }

    // --- Predicate Pushdown Tests ---
    @Nested
    class PredicatePushdownTests {

        private AttributeRequirements docReqs;
        private AttributeRequirements sentenceReqs;

        @BeforeEach
        void nestedSetUp() {
            docReqs = new AttributeRequirements();
            docReqs.needsDocumentId = true;
            docReqs.needsSentenceId = false; // For document level context
            docReqs.needsPositions = true;
            docReqs.needsConceptualRowIds = true;

            sentenceReqs = new AttributeRequirements();
            sentenceReqs.needsDocumentId = true;
            sentenceReqs.needsSentenceId = true; // For sentence level context
            sentenceReqs.needsPositions = true;
            sentenceReqs.needsConceptualRowIds = true;
        }

        @Test
        void testAndPushdown_initialContextIsPassedToFirstCondition() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1));
            Query.Granularity currentTestGranularity = Query.Granularity.DOCUMENT;
            QueryResultSoA mockResult = createMockQueryResultSoA(List.of(new TestDataEntry("term1", ValueType.TERM, null, 1, 1, 0, 0, -1, 0)), currentTestGranularity, 0, docReqs);

            FilteringContext initialFilteringContext = FilteringContext.unrestricted(currentTestGranularity);

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(docReqs), contextCaptor.capture())).thenReturn(mockResult);

            logicalExecutor.execute(andCondition, indexes, currentTestGranularity, testGranularitySize, corpusName, docReqs, Optional.of(initialFilteringContext));

            assertEquals(1, contextCaptor.getAllValues().size());
            Optional<FilteringContext> capturedContext = contextCaptor.getValue();
            assertTrue(capturedContext.isPresent());
            assertEquals(initialFilteringContext, capturedContext.get());
        }

        @Test
        void testAndPushdown_firstConditionResultShapesContextForSecond_DocGranularity() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2));
            Query.Granularity currentTestGranularity = Query.Granularity.DOCUMENT;

            QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
                new TestDataEntry("term1", ValueType.TERM, null, 1, -1, 0, 0, -1, 0),
                new TestDataEntry("term1", ValueType.TERM, null, 2, -1, 0, 0, -1, 1)
            ), currentTestGranularity, 0, docReqs);

            QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
                new TestDataEntry("term2", ValueType.TERM, null, 2, -1, 0, 0, -1, 0),
                new TestDataEntry("term2", ValueType.TERM, null, 3, -1, 0, 0, -1, 1)
            ), currentTestGranularity, 0, docReqs);

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(docReqs), any())).thenReturn(result1SoA);
            when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(docReqs), contextCaptor.capture())).thenReturn(result2SoA);

            logicalExecutor.execute(andCondition, indexes, currentTestGranularity, testGranularitySize, corpusName, docReqs, Optional.empty());

            assertEquals(1, contextCaptor.getAllValues().size());
            Optional<FilteringContext> capturedContextForSecond = contextCaptor.getValue();
            assertTrue(capturedContextForSecond.isPresent());
            FilteringContext fc = capturedContextForSecond.get();
            assertFalse(fc.isUnrestricted());
            assertTrue(fc.allowedDocumentIds().isPresent());
            assertEquals(Set.of(1, 2), fc.allowedDocumentIds().get());
            assertFalse(fc.allowedDocumentSentenceIds().isPresent());
        }

        @Test
        void testAndPushdown_firstConditionResultShapesContextForSecond_SentenceGranularity() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2));
            Query.Granularity currentTestGranularity = Query.Granularity.SENTENCE;

            QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
                new TestDataEntry("term1", ValueType.TERM, null, 1, 10, 0, 0, -1, 0),
                new TestDataEntry("term1", ValueType.TERM, null, 1, 11, 0, 0, -1, 1),
                new TestDataEntry("term1", ValueType.TERM, null, 2, 20, 0, 0, -1, 2)
            ), currentTestGranularity, 0, sentenceReqs);

            QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
                new TestDataEntry("term2", ValueType.TERM, null, 1, 11, 0,0,-1,0)
            ), currentTestGranularity, 0, sentenceReqs);

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(sentenceReqs), any())).thenReturn(result1SoA);
            when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(sentenceReqs), contextCaptor.capture())).thenReturn(result2SoA);

            logicalExecutor.execute(andCondition, indexes, currentTestGranularity, testGranularitySize, corpusName, sentenceReqs, Optional.empty());

            assertEquals(1, contextCaptor.getAllValues().size());
            Optional<FilteringContext> capturedContextForSecond = contextCaptor.getValue();
            assertTrue(capturedContextForSecond.isPresent());
            FilteringContext fc = capturedContextForSecond.get();

            assertFalse(fc.isUnrestricted());
            assertTrue(fc.allowedDocumentIds().isPresent());
            assertEquals(Set.of(1, 2), fc.allowedDocumentIds().get());

            assertTrue(fc.allowedDocumentSentenceIds().isPresent());
            Map<Integer, Set<Integer>> expectedSentIds = Map.of(
                1, Set.of(10, 11),
                2, Set.of(20)
            );
            assertEquals(expectedSentIds, fc.allowedDocumentSentenceIds().get());
        }

        @Test
        void testAndPushdown_emptyResultFromFirstCondition_FinalResultEmpty() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2));
            Query.Granularity currentTestGranularity = Query.Granularity.DOCUMENT;

            QueryResultSoA emptyResult1SoA = createMockQueryResultSoA(Collections.emptyList(), currentTestGranularity, 0, docReqs);
            QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(new TestDataEntry("term2", ValueType.TERM, null, 1, -1, 0,0,-1,0)), currentTestGranularity, 0, docReqs);

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(docReqs), any())).thenReturn(emptyResult1SoA);
            lenient().when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(docReqs), contextCaptor.capture())).thenReturn(result2SoA);

            QueryResultSoA finalResult = logicalExecutor.execute(andCondition, indexes, currentTestGranularity, testGranularitySize, corpusName, docReqs, Optional.empty());
            assertTrue(finalResult.isEmpty());
        }

        @Test
        void testAndPushdown_initialEmptyContext_leadsToUnrestrictedContextForFirstCondition() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1));
            Query.Granularity currentTestGranularity = Query.Granularity.DOCUMENT;
            QueryResultSoA mockResult = createMockQueryResultSoA(List.of(new TestDataEntry("term1", ValueType.TERM, null, 1, 1, 0, 0, -1, 0)), currentTestGranularity, 0, docReqs);

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(docReqs), contextCaptor.capture())).thenReturn(mockResult);

            logicalExecutor.execute(andCondition, indexes, currentTestGranularity, testGranularitySize, corpusName, docReqs, Optional.empty());

            assertEquals(1, contextCaptor.getAllValues().size());
            Optional<FilteringContext> capturedContext = contextCaptor.getValue();
            assertTrue(capturedContext.isPresent());
            assertTrue(capturedContext.get().isUnrestricted(), "Expected an unrestricted context for the first condition when initial context is empty.");
            assertEquals(currentTestGranularity, capturedContext.get().granularity(), "Context granularity should match query granularity.");
        }

        @Test
        void testAndPushdown_threeConditions_contextChaining_DocGranularity() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2, condition3_term3));
            Query.Granularity currentTestGranularity = Query.Granularity.DOCUMENT;

            QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
                new TestDataEntry("term1", ValueType.TERM, "c1", 1, -1, 0, 0, -1, 0),
                new TestDataEntry("term1", ValueType.TERM, "c1", 2, -1, 0, 0, -1, 1)
            ), currentTestGranularity, 0, docReqs);

            QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
                new TestDataEntry("term2", ValueType.TERM, "c2", 2, -1, 0, 0, -1, 0),
                new TestDataEntry("term2", ValueType.TERM, "c2", 3, -1, 0, 0, -1, 1)
            ), currentTestGranularity, 0, docReqs);

             QueryResultSoA result3SoA = createMockQueryResultSoA(List.of(
                new TestDataEntry("term3", ValueType.TERM, "c3", 1, -1, 0, 0, -1, 0),
                new TestDataEntry("term3", ValueType.TERM, "c3", 2, -1, 0, 0, -1, 1),
                new TestDataEntry("term3", ValueType.TERM, "c3", 4, -1, 0, 0, -1, 2)
            ), currentTestGranularity, 0, docReqs);

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(docReqs), contextCaptor.capture())).thenReturn(result1SoA);
            when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(docReqs), contextCaptor.capture())).thenReturn(result2SoA);
            when(mockSubExecutor3.execute(eq(condition3_term3), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(docReqs), contextCaptor.capture())).thenReturn(result3SoA);

            logicalExecutor.execute(andCondition, indexes, currentTestGranularity, testGranularitySize, corpusName, docReqs, Optional.empty());

            List<Optional<FilteringContext>> allCapturedContexts = contextCaptor.getAllValues();
            assertEquals(3, allCapturedContexts.size(), "Expected context to be captured for all three sub-conditions.");

            Optional<FilteringContext> contextForC1 = allCapturedContexts.get(0);
            assertTrue(contextForC1.isPresent());
            assertTrue(contextForC1.get().isUnrestricted());
            assertEquals(currentTestGranularity, contextForC1.get().granularity());

            Optional<FilteringContext> contextForC2 = allCapturedContexts.get(1);
            assertTrue(contextForC2.isPresent());
            assertFalse(contextForC2.get().isUnrestricted());
            assertTrue(contextForC2.get().allowedDocumentIds().isPresent());
            assertEquals(Set.of(1, 2), contextForC2.get().allowedDocumentIds().get());
            assertFalse(contextForC2.get().allowedDocumentSentenceIds().isPresent());

            Optional<FilteringContext> contextForC3 = allCapturedContexts.get(2);
            assertTrue(contextForC3.isPresent());
            assertFalse(contextForC3.get().isUnrestricted());
            assertTrue(contextForC3.get().allowedDocumentIds().isPresent());
            assertEquals(Set.of(2), contextForC3.get().allowedDocumentIds().get(), "Context for C3 should be intersection of C1's output and C2's output filtered by C1 context.");
            assertFalse(contextForC3.get().allowedDocumentSentenceIds().isPresent());
        }

        @Test
        void testAndPushdown_emptyResultFromMiddleCondition_stopsChainAndPropagatesEmptyContext() throws QueryExecutionException {
            Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2, condition3_term3));
            Query.Granularity currentTestGranularity = Query.Granularity.DOCUMENT;

            QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
                new TestDataEntry("term1", ValueType.TERM, "c1", 1, -1, 0, 0, -1, 0),
                new TestDataEntry("term1", ValueType.TERM, "c1", 2, -1, 0, 0, -1, 1)
            ), currentTestGranularity, 0, docReqs);

            QueryResultSoA emptyResult2SoA = createMockQueryResultSoA(Collections.emptyList(), currentTestGranularity, 0, docReqs);

            QueryResultSoA result3SoA = createMockQueryResultSoA(List.of(
                new TestDataEntry("term3", ValueType.TERM, "c3", 2, -1, 0, 0, -1, 0)
            ), currentTestGranularity, 0, docReqs);

            when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(docReqs), contextCaptor.capture())).thenReturn(result1SoA);
            when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(docReqs), contextCaptor.capture())).thenReturn(emptyResult2SoA);
            lenient().when(mockSubExecutor3.execute(eq(condition3_term3), any(), eq(currentTestGranularity), eq(testGranularitySize), anyString(), eq(docReqs), contextCaptor.capture())).thenReturn(result3SoA);

            QueryResultSoA finalResult = logicalExecutor.execute(andCondition, indexes, currentTestGranularity, testGranularitySize, corpusName, docReqs, Optional.empty());

            assertTrue(finalResult.isEmpty(), "Final result should be empty if a middle condition is empty.");

            List<Optional<FilteringContext>> allCapturedContexts = contextCaptor.getAllValues();
            assertEquals(2, allCapturedContexts.size(), "Expected context to be captured for C1 and C2 only.");

            Optional<FilteringContext> contextForC1 = allCapturedContexts.get(0);
            assertTrue(contextForC1.isPresent() && contextForC1.get().isUnrestricted());

            Optional<FilteringContext> contextForC2 = allCapturedContexts.get(1);
            assertTrue(contextForC2.isPresent());
            assertFalse(contextForC2.get().isUnrestricted());
            assertEquals(Set.of(1, 2), contextForC2.get().allowedDocumentIds().orElse(Collections.emptySet()));
        }
    }
}