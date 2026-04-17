package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.query.binding.ValueType;
import com.example.query.model.JoinCondition;
import com.example.query.model.Query;
import com.example.query.model.TemporalPredicate;

class JoinHandlerTest {

    private JoinHandler joinHandler;
    private static final String DATE_KEY = "date";
    private static final String LEFT_ALIAS = "l";
    private static final String RIGHT_ALIAS = "r";

    @BeforeEach
    void setUp() {
        joinHandler = new JoinHandler();
    }

    private QueryResultSoA createInputSoA(
        String alias,
        String valueKey,
        List<Object> values,
        ValueType valueType,
        List<Integer> docIds,
        List<Integer> sentenceIds,
        Query.Granularity granularity,
        AttributeRequirements requirements
    ) {
        QueryResultSoA soa = new QueryResultSoA(granularity, 0, requirements);
        for (int i = 0; i < values.size(); i++) {
            int conceptualRowId = i;
            soa.add(
                values.get(i),
                valueType,
                alias + "." + valueKey,
                docIds.get(i),
                (requirements.needsSentenceId && sentenceIds != null) ? sentenceIds.get(i) : -1,
                requirements.needsPositions ? 0 : -1,
                requirements.needsPositions ? 0 : -1,
                requirements.needsSynonymIds ? -1 : -1,
                conceptualRowId
            );
        }
        return soa;
    }

    private Map<Integer, Object> getValuesByConceptualRowId(QueryResultSoA soa, String alias, String key) {
        Map<Integer, Object> map = new HashMap<>();
        String targetVariableName = alias + "." + key;
        if (soa == null || soa.isEmpty()) {
            return map;
        }
        for (int i = 0; i < soa.size(); i++) {
            if (targetVariableName.equals(soa.getVariableNameAt(i))) {
                map.put(soa.getConceptualRowIdAt(i), soa.getValueAt(i));
            }
        }
        return map;
    }

    private Map<Integer, List<Map<String, Object>>> groupBindingsByConceptualRowId(QueryResultSoA soa) {
        Map<Integer, List<Map<String, Object>>> grouped = new HashMap<>();
        if (soa == null || !soa.getRequirements().needsConceptualRowIds) {
            return grouped;
        }
        for (int i = 0; i < soa.size(); i++) {
            int conceptualId = soa.getConceptualRowIdAt(i);
            List<Map<String, Object>> bindings = grouped.computeIfAbsent(conceptualId, k -> new ArrayList<>());

            Map<String, Object> binding = new HashMap<>();
            binding.put("variable", soa.getVariableNameAt(i));
            binding.put("value", soa.getValueAt(i));
            binding.put("docId", soa.getDocumentIdAt(i));

            bindings.add(binding);
        }
        return grouped;
    }

    @Test
    void testHandleJoin_TemporalBefore_Simple() throws QueryExecutionException {
        AttributeRequirements reqs = new AttributeRequirements();
        reqs.needsDocumentId = true;
        reqs.needsSentenceId = true;
        reqs.needsConceptualRowIds = true;

        Query.Granularity granularity = Query.Granularity.SENTENCE;

        List<Object> leftDates = List.of(LocalDate.of(2023, 1, 10), LocalDate.of(2023, 1, 15));
        List<Integer> leftDocIds = List.of(1, 1);
        List<Integer> leftSentIds = List.of(1, 2);
        QueryResultSoA leftSoA = createInputSoA(LEFT_ALIAS, DATE_KEY, leftDates, ValueType.DATE, leftDocIds, leftSentIds, granularity, reqs);

        List<Object> rightDates = List.of(LocalDate.of(2023, 1, 12), LocalDate.of(2023, 1, 20));
        List<Integer> rightDocIds = List.of(2, 2);
        List<Integer> rightSentIds = List.of(1, 2);
        QueryResultSoA rightSoA = createInputSoA(RIGHT_ALIAS, DATE_KEY, rightDates, ValueType.DATE, rightDocIds, rightSentIds, granularity, reqs);

        JoinCondition joinCondition = new JoinCondition(
            LEFT_ALIAS + "." + DATE_KEY,
            RIGHT_ALIAS + "." + DATE_KEY,
            JoinCondition.JoinType.INNER,
            JoinCondition.JoinOperatorType.TEMPORAL,
            Optional.of(TemporalPredicate.BEFORE)
        );

        QueryResultSoA resultSoA = joinHandler.performBinaryJoin(
            leftSoA,
            LEFT_ALIAS,
            rightSoA,
            RIGHT_ALIAS,
            joinCondition,
            joinCondition.type(),
            granularity,
            0,
            reqs
        );

        assertNotNull(resultSoA);
        Map<Integer, List<Map<String, Object>>> groupedResults = groupBindingsByConceptualRowId(resultSoA);

        assertEquals(3, groupedResults.size(), "Expected 3 conceptual rows (joined pairs) for BEFORE");

        boolean found_L10_R12 = false;
        boolean found_L10_R20 = false;
        boolean found_L15_R20 = false;

        for (List<Map<String, Object>> bindingsInRow : groupedResults.values()) {
            assertEquals(2, bindingsInRow.size(), "Each joined conceptual row should have two bindings (one from left, one from right)");

            Object leftVal = null, rightVal = null;
            for (Map<String, Object> binding : bindingsInRow) {
                String varName = (String) binding.get("variable");
                if ((LEFT_ALIAS + "." + DATE_KEY).equals(varName)) {
                    leftVal = binding.get("value");
                } else if ((RIGHT_ALIAS + "." + DATE_KEY).equals(varName)) {
                    rightVal = binding.get("value");
                }
            }
            assertNotNull(leftVal, "Joined row missing left binding");
            assertNotNull(rightVal, "Joined row missing right binding");
            assertTrue(leftVal instanceof LocalDate && rightVal instanceof LocalDate, "Joined values should be LocalDates");

            LocalDate ldLeft = (LocalDate) leftVal;
            LocalDate ldRight = (LocalDate) rightVal;
            assertTrue(ldLeft.isBefore(ldRight), "Join condition (BEFORE) not met");

            if (ldLeft.equals(LocalDate.of(2023,1,10)) && ldRight.equals(LocalDate.of(2023,1,12))) found_L10_R12 = true;
            if (ldLeft.equals(LocalDate.of(2023,1,10)) && ldRight.equals(LocalDate.of(2023,1,20))) found_L10_R20 = true;
            if (ldLeft.equals(LocalDate.of(2023,1,15)) && ldRight.equals(LocalDate.of(2023,1,20))) found_L15_R20 = true;
        }
        assertTrue(found_L10_R12, "Missing expected join: 2023-01-10 BEFORE 2023-01-12");
        assertTrue(found_L10_R20, "Missing expected join: 2023-01-10 BEFORE 2023-01-20");
        assertTrue(found_L15_R20, "Missing expected join: 2023-01-15 BEFORE 2023-01-20");
    }

    @Test
    void testHandleJoin_TemporalAfter_Simple() throws QueryExecutionException {
        AttributeRequirements reqs = new AttributeRequirements();
        reqs.needsDocumentId = true;
        reqs.needsSentenceId = true;
        reqs.needsConceptualRowIds = true;
        Query.Granularity granularity = Query.Granularity.SENTENCE;

        List<Object> leftDates = List.of(LocalDate.of(2023, 1, 10), LocalDate.of(2023, 1, 15));
        List<Integer> leftDocIds = List.of(1, 1);
        List<Integer> leftSentIds = List.of(1, 2);
        QueryResultSoA leftSoA = createInputSoA(LEFT_ALIAS, DATE_KEY, leftDates, ValueType.DATE, leftDocIds, leftSentIds, granularity, reqs);

        List<Object> rightDates = List.of(LocalDate.of(2023, 1, 12), LocalDate.of(2023, 1, 8));
        List<Integer> rightDocIds = List.of(2, 2);
        List<Integer> rightSentIds = List.of(1, 2);
        QueryResultSoA rightSoA = createInputSoA(RIGHT_ALIAS, DATE_KEY, rightDates, ValueType.DATE, rightDocIds, rightSentIds, granularity, reqs);

        JoinCondition joinCondition = new JoinCondition(
            LEFT_ALIAS + "." + DATE_KEY,
            RIGHT_ALIAS + "." + DATE_KEY,
            JoinCondition.JoinType.INNER,
            JoinCondition.JoinOperatorType.TEMPORAL,
            Optional.of(TemporalPredicate.AFTER)
        );

        QueryResultSoA resultSoA = joinHandler.performBinaryJoin(
            leftSoA,
            LEFT_ALIAS,
            rightSoA,
            RIGHT_ALIAS,
            joinCondition,
            joinCondition.type(),
            granularity,
            0,
            reqs
        );

        assertNotNull(resultSoA);
        Map<Integer, List<Map<String, Object>>> groupedResults = groupBindingsByConceptualRowId(resultSoA);

        assertEquals(3, groupedResults.size(), "Expected 3 conceptual rows for AFTER");

        boolean found_L10_R08 = false;
        boolean found_L15_R12 = false;
        boolean found_L15_R08 = false;

        for (List<Map<String, Object>> bindingsInRow : groupedResults.values()) {
            assertEquals(2, bindingsInRow.size());
            Object leftVal = null, rightVal = null;
            for (Map<String, Object> binding : bindingsInRow) {
                String varName = (String) binding.get("variable");
                if ((LEFT_ALIAS + "." + DATE_KEY).equals(varName)) leftVal = binding.get("value");
                else if ((RIGHT_ALIAS + "." + DATE_KEY).equals(varName)) rightVal = binding.get("value");
            }
            assertNotNull(leftVal); assertNotNull(rightVal);
            assertTrue(leftVal instanceof LocalDate && rightVal instanceof LocalDate);
            LocalDate ldLeft = (LocalDate) leftVal;
            LocalDate ldRight = (LocalDate) rightVal;
            assertTrue(ldLeft.isAfter(ldRight), "Join condition (AFTER) not met");

            if (ldLeft.equals(LocalDate.of(2023,1,10)) && ldRight.equals(LocalDate.of(2023,1,8))) found_L10_R08 = true;
            if (ldLeft.equals(LocalDate.of(2023,1,15)) && ldRight.equals(LocalDate.of(2023,1,12))) found_L15_R12 = true;
            if (ldLeft.equals(LocalDate.of(2023,1,15)) && ldRight.equals(LocalDate.of(2023,1,8))) found_L15_R08 = true;
        }
        assertTrue(found_L10_R08, "Missing expected join: 2023-01-10 AFTER 2023-01-08");
        assertTrue(found_L15_R12, "Missing expected join: 2023-01-15 AFTER 2023-01-12");
        assertTrue(found_L15_R08, "Missing expected join: 2023-01-15 AFTER 2023-01-08");
    }

     @Test
    void testHandleJoin_TemporalBefore_EmptyLeft() throws QueryExecutionException {
        AttributeRequirements reqs = new AttributeRequirements();
        reqs.needsDocumentId = true; reqs.needsSentenceId = true; reqs.needsConceptualRowIds = true;
        Query.Granularity granularity = Query.Granularity.SENTENCE;

        QueryResultSoA leftSoA = createInputSoA(LEFT_ALIAS, DATE_KEY, new ArrayList<>(), ValueType.DATE, new ArrayList<>(), new ArrayList<>(), granularity, reqs);

        List<Object> rightDates = List.of(LocalDate.of(2023, 1, 12));
        List<Integer> rightDocIds = List.of(2);
        List<Integer> rightSentIds = List.of(1);
        QueryResultSoA rightSoA = createInputSoA(RIGHT_ALIAS, DATE_KEY, rightDates, ValueType.DATE, rightDocIds, rightSentIds, granularity, reqs);

        JoinCondition joinCondition = new JoinCondition(
            LEFT_ALIAS + "." + DATE_KEY,
            RIGHT_ALIAS + "." + DATE_KEY,
            JoinCondition.JoinType.INNER,
            JoinCondition.JoinOperatorType.TEMPORAL,
            Optional.of(TemporalPredicate.BEFORE)
        );

        QueryResultSoA resultSoA = joinHandler.performBinaryJoin(
            leftSoA,
            LEFT_ALIAS,
            rightSoA,
            RIGHT_ALIAS,
            joinCondition,
            joinCondition.type(),
            granularity,
            0,
            reqs
        );

        assertNotNull(resultSoA);
        assertTrue(resultSoA.isEmpty(), "Result SoA should be empty when left side is empty");
        Map<Integer, List<Map<String, Object>>> groupedResults = groupBindingsByConceptualRowId(resultSoA);
        assertTrue(groupedResults.isEmpty(), "Grouped results should be empty");
    }

    @Test
    void testHandleJoin_TemporalAfter_EmptyRight() throws QueryExecutionException {
        AttributeRequirements reqs = new AttributeRequirements();
        reqs.needsDocumentId = true; reqs.needsSentenceId = true; reqs.needsConceptualRowIds = true;
        Query.Granularity granularity = Query.Granularity.SENTENCE;

        List<Object> leftDates = List.of(LocalDate.of(2023, 1, 10));
        List<Integer> leftDocIds = List.of(1);
        List<Integer> leftSentIds = List.of(1);
        QueryResultSoA leftSoA = createInputSoA(LEFT_ALIAS, DATE_KEY, leftDates, ValueType.DATE, leftDocIds, leftSentIds, granularity, reqs);

        QueryResultSoA rightSoA = createInputSoA(RIGHT_ALIAS, DATE_KEY, new ArrayList<>(), ValueType.DATE, new ArrayList<>(), new ArrayList<>(), granularity, reqs);

        JoinCondition joinCondition = new JoinCondition(
            LEFT_ALIAS + "." + DATE_KEY,
            RIGHT_ALIAS + "." + DATE_KEY,
            JoinCondition.JoinType.INNER,
            JoinCondition.JoinOperatorType.TEMPORAL,
            Optional.of(TemporalPredicate.AFTER)
        );

        QueryResultSoA resultSoA = joinHandler.performBinaryJoin(
            leftSoA,
            LEFT_ALIAS,
            rightSoA,
            RIGHT_ALIAS,
            joinCondition,
            joinCondition.type(),
            granularity,
            0,
            reqs
        );

        assertNotNull(resultSoA);
        assertTrue(resultSoA.isEmpty(), "Result SoA should be empty when right side is empty");
    }

     @Test
    void testHandleJoin_TemporalBefore_IdenticalDates() throws QueryExecutionException {
        AttributeRequirements reqs = new AttributeRequirements();
        reqs.needsDocumentId = true; reqs.needsSentenceId = true; reqs.needsConceptualRowIds = true;
        Query.Granularity granularity = Query.Granularity.SENTENCE;

        List<Object> leftDates = List.of(LocalDate.of(2023, 1, 10));
        QueryResultSoA leftSoA = createInputSoA(LEFT_ALIAS, DATE_KEY, leftDates, ValueType.DATE, List.of(1), List.of(1), granularity, reqs);

        List<Object> rightDates = List.of(LocalDate.of(2023, 1, 10), LocalDate.of(2023, 1, 11));
        QueryResultSoA rightSoA = createInputSoA(RIGHT_ALIAS, DATE_KEY, rightDates, ValueType.DATE, List.of(2,2), List.of(1,2), granularity, reqs);

        JoinCondition joinCondition = new JoinCondition(
            LEFT_ALIAS + "." + DATE_KEY,
            RIGHT_ALIAS + "." + DATE_KEY,
            JoinCondition.JoinType.INNER,
            JoinCondition.JoinOperatorType.TEMPORAL,
            Optional.of(TemporalPredicate.BEFORE)
        );

        QueryResultSoA resultSoA = joinHandler.performBinaryJoin(
            leftSoA,
            LEFT_ALIAS,
            rightSoA,
            RIGHT_ALIAS,
            joinCondition,
            joinCondition.type(),
            granularity,
            0,
            reqs
        );
        assertNotNull(resultSoA);
        Map<Integer, List<Map<String, Object>>> grouped = groupBindingsByConceptualRowId(resultSoA);

        assertEquals(1, grouped.size(), "Expected 1 joined pair for BEFORE with identical dates (10th before 11th)");

        boolean found_L10_R11 = false;
        for(List<Map<String, Object>> bindings : grouped.values()){
            LocalDate ldLeft = (LocalDate) bindings.stream().filter(b -> ((String)b.get("variable")).startsWith(LEFT_ALIAS)).findFirst().get().get("value");
            LocalDate ldRight = (LocalDate) bindings.stream().filter(b -> ((String)b.get("variable")).startsWith(RIGHT_ALIAS)).findFirst().get().get("value");
            if(ldLeft.equals(LocalDate.of(2023,1,10)) && ldRight.equals(LocalDate.of(2023,1,11))) {
                assertTrue(ldLeft.isBefore(ldRight));
                found_L10_R11 = true;
            }
        }
        assertTrue(found_L10_R11, "The pair (10th, 11th) should be the only one joined.");
    }

    @Test
    void testHandleJoin_TemporalAfter_IdenticalDates() throws QueryExecutionException {
        AttributeRequirements reqs = new AttributeRequirements();
        reqs.needsDocumentId = true; reqs.needsSentenceId = true; reqs.needsConceptualRowIds = true;
        Query.Granularity granularity = Query.Granularity.SENTENCE;

        List<Object> leftDates = List.of(LocalDate.of(2023, 1, 10), LocalDate.of(2023, 1, 11));
        QueryResultSoA leftSoA = createInputSoA(LEFT_ALIAS, DATE_KEY, leftDates, ValueType.DATE, List.of(1,1), List.of(1,2), granularity, reqs);

        List<Object> rightDates = List.of(LocalDate.of(2023, 1, 10));
        QueryResultSoA rightSoA = createInputSoA(RIGHT_ALIAS, DATE_KEY, rightDates, ValueType.DATE, List.of(2), List.of(1), granularity, reqs);

        JoinCondition joinCondition = new JoinCondition(
            LEFT_ALIAS + "." + DATE_KEY,
            RIGHT_ALIAS + "." + DATE_KEY,
            JoinCondition.JoinType.INNER,
            JoinCondition.JoinOperatorType.TEMPORAL,
            Optional.of(TemporalPredicate.AFTER)
        );

        QueryResultSoA resultSoA = joinHandler.performBinaryJoin(
            leftSoA,
            LEFT_ALIAS,
            rightSoA,
            RIGHT_ALIAS,
            joinCondition,
            joinCondition.type(),
            granularity,
            0,
            reqs
        );
        assertNotNull(resultSoA);
        Map<Integer, List<Map<String, Object>>> grouped = groupBindingsByConceptualRowId(resultSoA);

        assertEquals(1, grouped.size(), "Expected 1 joined pair for AFTER with identical dates (11th after 10th)");
        boolean found_L11_R10 = false;
        for(List<Map<String, Object>> bindings : grouped.values()){
            LocalDate ldLeft = (LocalDate) bindings.stream().filter(b -> ((String)b.get("variable")).startsWith(LEFT_ALIAS)).findFirst().get().get("value");
            LocalDate ldRight = (LocalDate) bindings.stream().filter(b -> ((String)b.get("variable")).startsWith(RIGHT_ALIAS)).findFirst().get().get("value");
            if(ldLeft.equals(LocalDate.of(2023,1,11)) && ldRight.equals(LocalDate.of(2023,1,10))) {
                assertTrue(ldLeft.isAfter(ldRight));
                found_L11_R10 = true;
            }
        }
        assertTrue(found_L11_R10, "The pair (11th, 10th) should be the only one joined.");
    }

    @Test
    void testHandleJoin_TemporalBefore_WithNonDateValuesOrWrongType() throws QueryExecutionException {
        AttributeRequirements reqs = new AttributeRequirements();
        reqs.needsDocumentId = true; reqs.needsSentenceId = true; reqs.needsConceptualRowIds = true;
        Query.Granularity granularity = Query.Granularity.SENTENCE;

        List<Object> leftValues = List.of("not a date", LocalDate.of(2023, 1, 5));
        QueryResultSoA leftSoA = createInputSoA(LEFT_ALIAS, DATE_KEY, leftValues, ValueType.TERM, List.of(1,1), List.of(1,2), granularity, reqs);

        List<Object> rightDates = List.of(LocalDate.of(2023, 1, 10));
        QueryResultSoA rightSoA = createInputSoA(RIGHT_ALIAS, DATE_KEY, rightDates, ValueType.DATE, List.of(2), List.of(1), granularity, reqs);

        JoinCondition joinCondition = new JoinCondition(
            LEFT_ALIAS + "." + DATE_KEY,
            RIGHT_ALIAS + "." + DATE_KEY,
            JoinCondition.JoinType.INNER,
            JoinCondition.JoinOperatorType.TEMPORAL,
            Optional.of(TemporalPredicate.BEFORE)
        );

        QueryResultSoA resultSoA = joinHandler.performBinaryJoin(
            leftSoA,
            LEFT_ALIAS,
            rightSoA,
            RIGHT_ALIAS,
            joinCondition,
            joinCondition.type(),
            granularity,
            0,
            reqs
        );
        assertNotNull(resultSoA);
    }

    @Test
    void testHandleJoin_TemporalAfter_AllLeftGreater() throws QueryExecutionException {
        AttributeRequirements reqs = new AttributeRequirements();
        reqs.needsDocumentId = true; reqs.needsSentenceId = true; reqs.needsConceptualRowIds = true;
        Query.Granularity granularity = Query.Granularity.SENTENCE;

        List<Object> leftDates = List.of(LocalDate.of(2023, 1, 15), LocalDate.of(2023, 1, 20));
        QueryResultSoA leftSoA = createInputSoA(LEFT_ALIAS, DATE_KEY, leftDates, ValueType.DATE, List.of(1,1), List.of(1,2), granularity, reqs);

        List<Object> rightDates = List.of(LocalDate.of(2023, 1, 10), LocalDate.of(2023, 1, 12));
        QueryResultSoA rightSoA = createInputSoA(RIGHT_ALIAS, DATE_KEY, rightDates, ValueType.DATE, List.of(2,2), List.of(1,2), granularity, reqs);

        JoinCondition joinCondition = new JoinCondition(
            LEFT_ALIAS + "." + DATE_KEY,
            RIGHT_ALIAS + "." + DATE_KEY,
            JoinCondition.JoinType.INNER,
            JoinCondition.JoinOperatorType.TEMPORAL,
            Optional.of(TemporalPredicate.AFTER)
        );

        QueryResultSoA resultSoA = joinHandler.performBinaryJoin(
            leftSoA,
            LEFT_ALIAS,
            rightSoA,
            RIGHT_ALIAS,
            joinCondition,
            joinCondition.type(),
            granularity,
            0,
            reqs
        );
        assertNotNull(resultSoA);
    }

    @Test
    void testHandleJoin_TemporalBefore_AllLeftSmaller() throws QueryExecutionException {
        AttributeRequirements reqs = new AttributeRequirements();
        reqs.needsDocumentId = true; reqs.needsSentenceId = true; reqs.needsConceptualRowIds = true;
        Query.Granularity granularity = Query.Granularity.SENTENCE;

        List<Object> leftDates = List.of(LocalDate.of(2023, 1, 5), LocalDate.of(2023, 1, 8));
        QueryResultSoA leftSoA = createInputSoA(LEFT_ALIAS, DATE_KEY, leftDates, ValueType.DATE, List.of(1,1), List.of(1,2), granularity, reqs);

        List<Object> rightDates = List.of(LocalDate.of(2023, 1, 10), LocalDate.of(2023, 1, 12));
        QueryResultSoA rightSoA = createInputSoA(RIGHT_ALIAS, DATE_KEY, rightDates, ValueType.DATE, List.of(2,2), List.of(1,2), granularity, reqs);

        JoinCondition joinCondition = new JoinCondition(
            LEFT_ALIAS + "." + DATE_KEY,
            RIGHT_ALIAS + "." + DATE_KEY,
            JoinCondition.JoinType.INNER,
            JoinCondition.JoinOperatorType.TEMPORAL,
            Optional.of(TemporalPredicate.BEFORE)
        );

        QueryResultSoA resultSoA = joinHandler.performBinaryJoin(
            leftSoA,
            LEFT_ALIAS,
            rightSoA,
            RIGHT_ALIAS,
            joinCondition,
            joinCondition.type(),
            granularity,
            0,
            reqs
        );
        assertNotNull(resultSoA);
    }

    @Test
    void testHandleJoin_ThrowsExceptionForUnsupportedPredicateInJoinCondition() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new JoinCondition(
                LEFT_ALIAS + "." + DATE_KEY,
                RIGHT_ALIAS + "." + DATE_KEY,
                JoinCondition.JoinType.INNER,
                JoinCondition.JoinOperatorType.TEMPORAL,
                Optional.empty()
            );
        });
        assertTrue(exception.getMessage().contains("Temporal predicate must be provided for TEMPORAL joins"));
    }

    @Test
    void testHandleJoin_Equality_Simple() throws QueryExecutionException {
        AttributeRequirements reqs = new AttributeRequirements();
        reqs.needsDocumentId = true;
        reqs.needsConceptualRowIds = true;
        Query.Granularity granularity = Query.Granularity.DOCUMENT;

        List<Object> leftIds = List.of("A", "B", "C");
        QueryResultSoA leftSoA = createInputSoA(LEFT_ALIAS, "ID", leftIds, ValueType.TERM, List.of(1, 2, 3), null, granularity, reqs);

        List<Object> rightIds = List.of("B", "C", "D");
        QueryResultSoA rightSoA = createInputSoA(RIGHT_ALIAS, "ID", rightIds, ValueType.TERM, List.of(10, 20, 30), null, granularity, reqs);

        JoinCondition joinCondition = new JoinCondition(
            LEFT_ALIAS + ".ID",
            RIGHT_ALIAS + ".ID",
            JoinCondition.JoinType.INNER,
            JoinCondition.JoinOperatorType.EQUALITY,
            Optional.empty()
        );

        QueryResultSoA resultSoA = joinHandler.performBinaryJoin(
            leftSoA, LEFT_ALIAS, rightSoA, RIGHT_ALIAS,
            joinCondition, joinCondition.type(), granularity, 0, reqs
        );

        assertNotNull(resultSoA);
        Map<Integer, List<Map<String, Object>>> groupedResults = groupBindingsByConceptualRowId(resultSoA);
        assertEquals(2, groupedResults.size(), "Expected 2 joined rows for equality (B, C)");
    }
}