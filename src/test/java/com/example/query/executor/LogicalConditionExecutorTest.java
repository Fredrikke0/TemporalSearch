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
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private Query.Granularity testGranularity;
    private int testGranularitySize;
    private String corpusName = "test_corpus";
    private Contains condition1_term1;
    private Contains condition2_term2;
    private Contains condition3_term3;
    private AttributeRequirements defaultTestRequirements;

    // Helper record for test data, replacing MatchDetail for test setup
    record TestDataEntry(Object value, ValueType type, String varName, int docId, int sentId, int begin, int end, int synId, int conceptualRowId) {}

    @BeforeEach
    void setUp() {
        condition1_term1 = new Contains("term1");
        condition2_term2 = new Contains("term2");
        condition3_term3 = new Contains("term3");

        lenient().when(mockFactory.getExecutor(eq(condition1_term1))).thenReturn(mockSubExecutor1);
        lenient().when(mockFactory.getExecutor(eq(condition2_term2))).thenReturn(mockSubExecutor2);
        lenient().when(mockFactory.getExecutor(eq(condition3_term3))).thenReturn(mockSubExecutor3);

        // The genericFallbackExecutor is tricky. If it's truly needed for conditions
        // other than Contains, this approach of mocking ContainsExecutor won't cover it.
        // However, the original test uses ConditionExecutor<Contains> for the main mocks,
        // suggesting Contains is the primary type being tested as sub-conditions.
        // For now, we'll remove the generic fallback or assume it's not hit by these tests.
        // If a test *does* try to use a non-Contains condition via the factory, it might fail
        // if the factory doesn't know how to produce a mock for it or if it returns null.
        // This might require a more sophisticated factory mocking or test-specific factory.
        // For simplicity, and given the specific types of condition1/2/3, we assume this is okay.

        // Let's comment out the generic fallback for now, as it might not be needed
        // and would require mocking a generic (sealed) ConditionExecutor.
        // @SuppressWarnings("unchecked")
        // ConditionExecutor<Condition> genericFallbackExecutor = mock(ConditionExecutor.class);
        // when(mockFactory.getExecutor(argThat(c -> c != condition1_term1 && c != condition2_term2 && c != condition3_term3)))
        //     .thenReturn(genericFallbackExecutor);

        logicalExecutor = new LogicalExecutor(mockFactory);
        indexes = Collections.emptyMap();
        testGranularity = Query.Granularity.DOCUMENT;
        testGranularitySize = 0;
        // Default requirements for tests. Ensure needsConceptualRowIds is true.
        defaultTestRequirements = new AttributeRequirements();
        defaultTestRequirements.needsDocumentId = true;
        defaultTestRequirements.needsSentenceId = true; // Assuming sentence level for some tests if positions are used.
        defaultTestRequirements.needsPositions = true;
        defaultTestRequirements.needsConceptualRowIds = true; // Crucial for logical operations
        defaultTestRequirements.needsSynonymIds = true; // Assuming it might be needed.
    }

    // Updated helper to use TestDataEntry
    private QueryResultSoA createMockQueryResultSoA(List<TestDataEntry> entries, Query.Granularity gran, int granSize, AttributeRequirements reqs) {
        QueryResultSoA soaResult = new QueryResultSoA(gran, granSize, reqs);
        for (TestDataEntry entry : entries) {
            soaResult.add(
                entry.value(), entry.type(), entry.varName(),
                entry.docId(), entry.sentId(),
                entry.begin(), entry.end(), entry.synId(),
                entry.conceptualRowId() // Add conceptualRowId
            );
        }
        return soaResult;
    }

    // Overload to use default testGranularity, testGranularitySize, and defaultTestRequirements from setUp
    private QueryResultSoA createMockQueryResultSoA(List<TestDataEntry> entries) {
        return createMockQueryResultSoA(entries, this.testGranularity, this.testGranularitySize, this.defaultTestRequirements);
    }

    // Helper to get all bindings for a given conceptualId
    private List<Map<String, Object>> getBindingsForConceptualId(QueryResultSoA soa, int conceptualId) {
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (int i = 0; i < soa.size(); i++) {
            if (soa.getConceptualRowIdAt(i) == conceptualId) {
                Map<String, Object> binding = new HashMap<>();
                binding.put("value", soa.getValueAt(i));
                binding.put("type", soa.getValueTypeAt(i));
                binding.put("variableName", soa.getVariableNameAt(i));
                binding.put("docId", soa.getDocumentIdAt(i));
                binding.put("sentId", soa.getSentenceIdAt(i));
                // Add other fields if needed for assertions
                bindings.add(binding);
            }
        }
        return bindings;
    }

    // Helper to count unique conceptual row IDs
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

        // Conceptual Row ID 0 for term1, Conceptual Row ID 0 for term2 in their respective inputs.
        // When they AND join, they should form a new conceptual row in the output.
        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)
        ));
        QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term2", ValueType.TERM, "v2", 1, 1, 5, 9, -1, 0)
        ));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result2SoA);

        QueryResultSoA finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements);

        assertNotNull(finalResult);
        // According to QueryResultSoa.md: AND generates a NEW unique conceptualRowId for each common document/sentence ID.
        // Both bindings (term1 and term2) will share this new conceptualRowId in the output.
        assertEquals(1, countUniqueConceptualRows(finalResult), "Expected 1 conceptual row as both terms are in document 1, sentence 1");
        assertEquals(2, finalResult.size(), "Expected 2 total binding entries in the final result (one for term1, one for term2)");

        // Verify the bindings in the single conceptual row
        List<Map<String, Object>> conceptualRowBindings = getBindingsForConceptualId(finalResult, finalResult.getConceptualRowIdAt(0));
        assertEquals(2, conceptualRowBindings.size());

        boolean foundTerm1 = false;
        boolean foundTerm2 = false;
        for (Map<String, Object> binding : conceptualRowBindings) {
            assertEquals(1, binding.get("docId"));
            assertEquals(1, binding.get("sentId"));
            if ("term1".equals(binding.get("value")) && "v1".equals(binding.get("variableName"))) {
                foundTerm1 = true;
            } else if ("term2".equals(binding.get("value")) && "v2".equals(binding.get("variableName"))) {
                foundTerm2 = true;
            }
        }
        assertTrue(foundTerm1, "Binding for term1 not found in the conceptual row");
        assertTrue(foundTerm2, "Binding for term2 not found in the conceptual row");
    }

    @Test
    void testExecuteAnd_firstConditionEmpty() throws QueryExecutionException {
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2));

        QueryResultSoA emptyResultSoA = createMockQueryResultSoA(Collections.emptyList());
        QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term2", ValueType.TERM, "v2", 1, 1, 0, 4, -1, 0)
        ));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(emptyResultSoA);
        // No need to mock subExecutor2's execute if short-circuiting happens, but good practice for robustness.
        // For AND, if the first result is empty, the second sub-executor might not even be called.
        // However, the current LogicalExecutor implementation might call all then process.
        // Let's assume it might be called for now.
        lenient().when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result2SoA);


        QueryResultSoA finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements);

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

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(emptyResultSoA);

        QueryResultSoA finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements);

        assertTrue(finalResult.isEmpty());
        assertEquals(0, countUniqueConceptualRows(finalResult));
    }

    @Test
    void testExecuteOr_twoConditions_bothReturnResults() throws QueryExecutionException {
        Logical orCondition = new Logical(Logical.LogicalOperator.OR, List.of(condition1_term1, condition2_term2));

        // Input conceptual IDs: 0 for term1, 0 for term2.
        // For OR, these should be preserved (potentially offset).
        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0) // Conceptual ID 0
        ));
        QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term2", ValueType.TERM, "v2", 2, 1, 0, 4, -1, 0) // Conceptual ID 0 (will be offset)
        ));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result2SoA);

        QueryResultSoA finalResult = logicalExecutor.execute(orCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements);

        assertEquals(2, finalResult.size(), "OR should combine all binding entries.");
        assertEquals(2, countUniqueConceptualRows(finalResult), "Expected 2 unique conceptual rows after OR.");

        // Check if both original conceptual rows are present (one potentially offset)
        // This requires knowing the offset strategy, or checking the data content.
        Set<Object> values = new HashSet<>();
        Set<Integer> conceptualIds = new HashSet<>();
        for(int i=0; i < finalResult.size(); i++) {
            values.add(finalResult.getValueAt(i));
            conceptualIds.add(finalResult.getConceptualRowIdAt(i));
        }
        assertTrue(values.contains("term1"));
        assertTrue(values.contains("term2"));
        assertEquals(2, conceptualIds.size(), "Should have two distinct conceptual IDs in the OR result.");
    }

    @Test
    void testExecuteOr_firstConditionEmpty() throws QueryExecutionException {
        Logical orCondition = new Logical(Logical.LogicalOperator.OR, List.of(condition1_term1, condition2_term2));

        QueryResultSoA emptyResultSoA = createMockQueryResultSoA(Collections.emptyList());
        QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term2", ValueType.TERM, "v2", 1, 1, 0, 4, -1, 0) // Conceptual ID 0
        ));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(emptyResultSoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result2SoA);

        QueryResultSoA finalResult = logicalExecutor.execute(orCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements);

        assertFalse(finalResult.isEmpty());
        assertEquals(result2SoA.size(), finalResult.size());
        assertEquals(1, countUniqueConceptualRows(finalResult));
        assertEquals("term2", finalResult.getValueAt(0));
        assertEquals(0, finalResult.getConceptualRowIdAt(0)); // Preserved conceptual ID
    }

    @Test
    void testExecuteOr_secondConditionEmpty() throws QueryExecutionException {
        Logical orCondition = new Logical(Logical.LogicalOperator.OR, List.of(condition1_term1, condition2_term2));

        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0) // Conceptual ID 0
        ));
        QueryResultSoA emptyResultSoA = createMockQueryResultSoA(Collections.emptyList());

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(emptyResultSoA);

        QueryResultSoA finalResult = logicalExecutor.execute(orCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements);

        assertFalse(finalResult.isEmpty());
        assertEquals(result1SoA.size(), finalResult.size());
        assertEquals(1, countUniqueConceptualRows(finalResult));
        assertEquals("term1", finalResult.getValueAt(0));
        assertEquals(0, finalResult.getConceptualRowIdAt(0)); // Preserved conceptual ID
    }

    @Test
    void testExecuteAnd_threeConditions_middleIsEmpty() throws QueryExecutionException {
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2, condition3_term3));

        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0)));
        QueryResultSoA emptyResultSoA = createMockQueryResultSoA(Collections.emptyList());
        QueryResultSoA result3SoA = createMockQueryResultSoA(List.of(new TestDataEntry("term3", ValueType.TERM, "v3", 1, 1, 0, 4, -1, 0)));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(emptyResultSoA);
        // For AND, if any intermediate result is empty, the subsequent executors might not be called.
        // Mocking leniently for robustness.
        lenient().when(mockSubExecutor3.execute(eq(condition3_term3), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result3SoA);

        QueryResultSoA finalResult = logicalExecutor.execute(andCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements);

        assertTrue(finalResult.isEmpty(), "AND with an empty middle result should be empty.");
        assertEquals(0, countUniqueConceptualRows(finalResult));
    }

    @Test
    void testExecuteOr_threeConditions_middleHasUnique() throws QueryExecutionException {
        Logical orCondition = new Logical(Logical.LogicalOperator.OR, List.of(condition1_term1, condition2_term2, condition3_term3));

        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0) // Conceptual ID 0
        ));
        QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term2", ValueType.TERM, "v2", 2, 1, 0, 4, -1, 1) // Conceptual ID 1
        ));
        QueryResultSoA result3SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term3", ValueType.TERM, "v3", 1, 1, 5, 9, -1, 2) // Conceptual ID 2
        ));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result2SoA);
        when(mockSubExecutor3.execute(eq(condition3_term3), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result3SoA);

        QueryResultSoA finalResult = logicalExecutor.execute(orCondition, indexes, testGranularity, testGranularitySize, corpusName, defaultTestRequirements);

        assertEquals(3, finalResult.size(), "OR should combine all binding entries.");
        assertEquals(3, countUniqueConceptualRows(finalResult), "Expected 3 unique conceptual rows after OR with distinct inputs.");

        Set<Object> values = new HashSet<>();
        for(int i=0; i < finalResult.size(); i++) {
            values.add(finalResult.getValueAt(i));
        }
        assertTrue(values.containsAll(Set.of("term1", "term2", "term3")));

        // Check conceptual IDs are preserved (potentially offset)
        Set<Integer> finalConceptualIds = new HashSet<>();
        for (int i = 0; i < finalResult.size(); i++) {
            finalConceptualIds.add(finalResult.getConceptualRowIdAt(i));
        }
        assertEquals(3, finalConceptualIds.size());

        // Verify data associated with conceptual rows
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
        // Test basic merge join functionality
        Logical andCondition = new Logical(Logical.LogicalOperator.AND, List.of(condition1_term1, condition2_term2));

        // Left: documents [1, 3] (sorted)
        // Right: documents [1, 3] (sorted)
        // Expected result: both docs 1 and 3 should match
        QueryResultSoA result1SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term1", ValueType.TERM, "v1", 1, 1, 0, 4, -1, 0),
            new TestDataEntry("term1", ValueType.TERM, "v1", 3, 1, 0, 4, -1, 1)
        ));
        QueryResultSoA result2SoA = createMockQueryResultSoA(List.of(
            new TestDataEntry("term2", ValueType.TERM, "v2", 1, 1, 5, 9, -1, 0),
            new TestDataEntry("term2", ValueType.TERM, "v2", 3, 1, 5, 9, -1, 1)
        ));

        when(mockSubExecutor1.execute(eq(condition1_term1), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result1SoA);
        when(mockSubExecutor2.execute(eq(condition2_term2), any(), eq(testGranularity), eq(testGranularitySize), anyString(), eq(defaultTestRequirements))).thenReturn(result2SoA);

        QueryResultSoA result = logicalExecutor.execute(andCondition, indexes, Query.Granularity.DOCUMENT, 0, "test_corpus", defaultTestRequirements);

        assertEquals(4, result.size(), "Expected 4 total binding entries (2 from left + 2 from right)");
        assertEquals(2, countUniqueConceptualRows(result), "Expected 2 conceptual rows (one for each matching doc)");

        // Verify document IDs are present
        Set<Integer> actualDocIds = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            actualDocIds.add(result.getDocumentIdAt(i));
        }
        assertTrue(actualDocIds.contains(1), "Document 1 should be present");
        assertTrue(actualDocIds.contains(3), "Document 3 should be present");
        assertEquals(2, actualDocIds.size(), "Should have exactly 2 unique document IDs");
    }
}