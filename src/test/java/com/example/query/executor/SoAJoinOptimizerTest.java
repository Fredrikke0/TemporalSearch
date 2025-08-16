package com.example.query.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.query.binding.ValueType;
import com.example.query.model.Query;


class SoAJoinOptimizerTest {

    record TestDataEntry(Object value, ValueType type, String varName, int docId, int sentId, int begin, int end, int synId, int conceptualRowId) {}

    private AttributeRequirements defaultRequirements;
    private QueryResultSoA leftInputSoA;
    private QueryResultSoA rightInputSoA;

    private QueryResultSoA createSoAForTest(List<TestDataEntry> entries, AttributeRequirements reqs) {
        Query.Granularity granularity = Query.Granularity.DOCUMENT;
        if (entries.stream().anyMatch(e -> e.sentId() != -1)) {
            granularity = Query.Granularity.SENTENCE;
        }
        QueryResultSoA soa = new QueryResultSoA(granularity, 0, reqs);
        for (TestDataEntry entry : entries) {
            soa.add(
                entry.value(), entry.type(), entry.varName(),
                entry.docId(), entry.sentId(),
                entry.begin(), entry.end(), entry.synId(),
                entry.conceptualRowId()
            );
        }
        return soa;
    }

    @BeforeEach
    void setUp() {
        defaultRequirements = new AttributeRequirements();
        defaultRequirements.needsDocumentId = true;
        defaultRequirements.needsSentenceId = true;
        defaultRequirements.needsPositions = true;
        defaultRequirements.needsConceptualRowIds = true;
        defaultRequirements.needsSynonymIds = false;

        List<TestDataEntry> leftEntries = new ArrayList<>();
        leftEntries.add(new TestDataEntry("term1", ValueType.TERM, "left.var", 1, 1, 10, 20, -1, 0));
        leftEntries.add(new TestDataEntry("term2", ValueType.TERM, "left.var", 1, 2, 25, 35, -1, 1));
        leftEntries.add(new TestDataEntry("term3", ValueType.TERM, "left.var", 2, 1, 40, 50, -1, 2));
        leftEntries.add(new TestDataEntry("term4", ValueType.TERM, "left.var", 3, 1, 60, 70, -1, 3));
        leftInputSoA = createSoAForTest(leftEntries, defaultRequirements);

        List<TestDataEntry> rightEntries = new ArrayList<>();
        rightEntries.add(new TestDataEntry("other1", ValueType.TERM, "right.var", 1, 1, 80, 90, -1, 0));
        rightEntries.add(new TestDataEntry("other2", ValueType.TERM, "right.var", 2, 1, 100, 110, -1, 1));
        rightEntries.add(new TestDataEntry("other3", ValueType.TERM, "right.var", 2, 2, 120, 130, -1, 2));
        rightEntries.add(new TestDataEntry("other4", ValueType.TERM, "right.var", 4, 1, 140, 150, -1, 3));
        rightInputSoA = createSoAForTest(rightEntries, defaultRequirements);
    }

    private int getDocIdForConceptualId(QueryResultSoA soa, int conceptualId) {
        for (int i = 0; i < soa.size(); i++) {
            if (soa.getConceptualRowIdAt(i) == conceptualId) {
                return soa.getDocumentIdAt(i);
            }
        }
        return -1;
    }

    private int getSentIdForConceptualId(QueryResultSoA soa, int conceptualId) {
        for (int i = 0; i < soa.size(); i++) {
            if (soa.getConceptualRowIdAt(i) == conceptualId) {
                return soa.getSentenceIdAt(i);
            }
        }
        return -1;
    }

    private Object getValueForConceptualId(QueryResultSoA soa, int conceptualId, String varName) {
        for (int i = 0; i < soa.size(); i++) {
            if (soa.getConceptualRowIdAt(i) == conceptualId && varName.equals(soa.getVariableNameAt(i))) {
                return soa.getValueAt(i);
            }
        }
        return null;
    }

    @Test
    void testOptimizedDocumentIdJoin() {
        List<SoAJoinOptimizer.SoAJoinKeyMatch> result = SoAJoinOptimizer.performOptimizedHashJoin(
            leftInputSoA, rightInputSoA, "DOCUMENT_ID", "DOCUMENT_ID");

        assertEquals(4, result.size(), "Should have 4 join matches based on Document ID");

        long doc1Matches = result.stream()
            .filter(jm -> getDocIdForConceptualId(leftInputSoA, jm.leftConceptualRowId()) == 1 &&
                           getDocIdForConceptualId(rightInputSoA, jm.rightConceptualRowId()) == 1)
            .count();
        assertEquals(2, doc1Matches, "Should have 2 matches for document 1. Left conceptual IDs 0 and 1 joined with Right conceptual ID 0.");

        long doc2Matches = result.stream()
            .filter(jm -> getDocIdForConceptualId(leftInputSoA, jm.leftConceptualRowId()) == 2 &&
                           getDocIdForConceptualId(rightInputSoA, jm.rightConceptualRowId()) == 2)
            .count();
        assertEquals(2, doc2Matches, "Should have 2 matches for document 2. Left conceptual ID 2 joined with Right conceptual IDs 1 and 2.");

        long doc3Matches = result.stream()
            .filter(jm -> getDocIdForConceptualId(leftInputSoA, jm.leftConceptualRowId()) == 3 ||
                           getDocIdForConceptualId(rightInputSoA, jm.rightConceptualRowId()) == 3)
            .count();
        assertEquals(0, doc3Matches, "Should have 0 matches involving document 3 (from left, no right match) or document 4 (from right, no left match)");
    }

    @Test
    void testOptimizedDateJoin() {
        LocalDate date1 = LocalDate.of(2023, 1, 1);
        LocalDate date2 = LocalDate.of(2023, 1, 2);
        LocalDate date3 = LocalDate.of(2023, 1, 3);

        List<TestDataEntry> leftDateEntries = new ArrayList<>();
        leftDateEntries.add(new TestDataEntry(date1, ValueType.DATE, "left.date", 10, 1, 0,0,0, 0));
        leftDateEntries.add(new TestDataEntry(date1, ValueType.DATE, "left.date", 11, 1, 0,0,0, 1));
        leftDateEntries.add(new TestDataEntry(date2, ValueType.DATE, "left.date", 12, 1, 0,0,0, 2));
        QueryResultSoA leftDateSoA = createSoAForTest(leftDateEntries, defaultRequirements);

        List<TestDataEntry> rightDateEntries = new ArrayList<>();
        rightDateEntries.add(new TestDataEntry(date1, ValueType.DATE, "right.date", 20, 1, 0,0,0, 0));
        rightDateEntries.add(new TestDataEntry(date2, ValueType.DATE, "right.date", 21, 1, 0,0,0, 1));
        rightDateEntries.add(new TestDataEntry(date3, ValueType.DATE, "right.date", 22, 1, 0,0,0, 2));
        QueryResultSoA rightDateSoA = createSoAForTest(rightDateEntries, defaultRequirements);

        List<SoAJoinOptimizer.SoAJoinKeyMatch> result = SoAJoinOptimizer.performOptimizedHashJoin(
            leftDateSoA, rightDateSoA, "left.date", "right.date");

        assertEquals(3, result.size(), "Should have 3 join matches based on date values");

        long date1Matches = result.stream()
            .filter(jm -> date1.equals(getValueForConceptualId(leftDateSoA, jm.leftConceptualRowId(), "left.date")) &&
                           date1.equals(getValueForConceptualId(rightDateSoA, jm.rightConceptualRowId(), "right.date")))
            .count();
        assertEquals(2, date1Matches, "Should have 2 matches for date 2023-01-01. L_cIDs 0,1 with R_cID 0");

        long date2Matches = result.stream()
            .filter(jm -> date2.equals(getValueForConceptualId(leftDateSoA, jm.leftConceptualRowId(), "left.date")) &&
                           date2.equals(getValueForConceptualId(rightDateSoA, jm.rightConceptualRowId(), "right.date")))
            .count();
        assertEquals(1, date2Matches, "Should have 1 match for date 2023-01-02. L_cID 2 with R_cID 1");
    }

    @Test
    void testOptimizedSentenceIdJoin() {
        List<TestDataEntry> leftSentenceEntries = new ArrayList<>();
        leftSentenceEntries.add(new TestDataEntry("term1", ValueType.TERM, "left.var", 1, 1, 0,0,0, 0));
        leftSentenceEntries.add(new TestDataEntry("term2", ValueType.TERM, "left.var", 1, 1, 0,0,0, 1));
        QueryResultSoA leftSentenceSoA = createSoAForTest(leftSentenceEntries, defaultRequirements);

        List<TestDataEntry> rightSentenceEntries = new ArrayList<>();
        rightSentenceEntries.add(new TestDataEntry("other1", ValueType.TERM, "right.var", 1, 1, 0,0,0, 0));
        rightSentenceEntries.add(new TestDataEntry("other2", ValueType.TERM, "right.var", 2, 2, 0,0,0, 1));
        QueryResultSoA rightSentenceSoA = createSoAForTest(rightSentenceEntries, defaultRequirements);

        List<SoAJoinOptimizer.SoAJoinKeyMatch> result = SoAJoinOptimizer.performOptimizedHashJoin(
            leftSentenceSoA, rightSentenceSoA, "SENTENCE_ID", "SENTENCE_ID");

        assertEquals(2, result.size(), "Should have 2 join matches based on Sentence ID (and implicitly Document ID for Sentence context)");

        for (SoAJoinOptimizer.SoAJoinKeyMatch match : result) {
            assertEquals(1, getDocIdForConceptualId(leftSentenceSoA, match.leftConceptualRowId()), "Left doc ID should be 1");
            assertEquals(1, getDocIdForConceptualId(rightSentenceSoA, match.rightConceptualRowId()), "Right doc ID should be 1");
            assertEquals(1, getSentIdForConceptualId(leftSentenceSoA, match.leftConceptualRowId()), "Left sentence ID should be 1");
            assertEquals(1, getSentIdForConceptualId(rightSentenceSoA, match.rightConceptualRowId()), "Right sentence ID should be 1");
        }
    }

    @Test
    void testEmptyInputHandling() {
        List<TestDataEntry> emptyEntries = new ArrayList<>();
        QueryResultSoA emptySoA = createSoAForTest(emptyEntries, defaultRequirements);

        List<SoAJoinOptimizer.SoAJoinKeyMatch> result1 = SoAJoinOptimizer.performOptimizedHashJoin(
            emptySoA, rightInputSoA, "DOCUMENT_ID", "DOCUMENT_ID");
        assertTrue(result1.isEmpty(), "Join with empty left side should return empty result");

        List<SoAJoinOptimizer.SoAJoinKeyMatch> result2 = SoAJoinOptimizer.performOptimizedHashJoin(
            leftInputSoA, emptySoA, "DOCUMENT_ID", "DOCUMENT_ID");
        assertTrue(result2.isEmpty(), "Join with empty right side should return empty result");

        List<SoAJoinOptimizer.SoAJoinKeyMatch> result3 = SoAJoinOptimizer.performOptimizedHashJoin(
            emptySoA, emptySoA, "DOCUMENT_ID", "DOCUMENT_ID");
        assertTrue(result3.isEmpty(), "Join with both sides empty should return empty result");
    }

    @Test
    void testNoMatchingValues() {
        List<TestDataEntry> leftNoMatchEntries = new ArrayList<>();
        leftNoMatchEntries.add(new TestDataEntry("unique1", ValueType.TERM, "left.var", 101, 1,0,0,0, 0));
        leftNoMatchEntries.add(new TestDataEntry("unique2", ValueType.TERM, "left.var", 102, 1,0,0,0, 1));
        QueryResultSoA leftNoMatchSoA = createSoAForTest(leftNoMatchEntries, defaultRequirements);

        List<TestDataEntry> rightNoMatchEntries = new ArrayList<>();
        rightNoMatchEntries.add(new TestDataEntry("different1", ValueType.TERM, "right.var", 201, 1,0,0,0, 0));
        rightNoMatchEntries.add(new TestDataEntry("different2", ValueType.TERM, "right.var", 202, 1,0,0,0, 1));
        QueryResultSoA rightNoMatchSoA = createSoAForTest(rightNoMatchEntries, defaultRequirements);

        List<SoAJoinOptimizer.SoAJoinKeyMatch> result = SoAJoinOptimizer.performOptimizedHashJoin(
            leftNoMatchSoA, rightNoMatchSoA, "DOCUMENT_ID", "DOCUMENT_ID");

        assertTrue(result.isEmpty(), "Join with no matching document IDs should return empty result");
    }

    @Test
    void testOptimizedStructuralDateJoin() {
        LocalDate date1 = LocalDate.of(2024, 2, 1);
        LocalDate date2 = LocalDate.of(2024, 2, 2);

        List<TestDataEntry> leftDateEntries = new ArrayList<>();
        leftDateEntries.add(new TestDataEntry(date1, ValueType.DATE, "left.date", 10, 1, 0,0,0, 0));
        leftDateEntries.add(new TestDataEntry(date2, ValueType.DATE, "left.date", 11, 1, 0,0,0, 1));
        QueryResultSoA leftDateSoA = createSoAForTest(leftDateEntries, defaultRequirements);

        List<TestDataEntry> rightDateEntries = new ArrayList<>();
        rightDateEntries.add(new TestDataEntry(date1, ValueType.DATE, "right.date", 20, 1, 0,0,0, 0));
        rightDateEntries.add(new TestDataEntry(date2, ValueType.DATE, "right.date", 21, 1, 0,0,0, 1));
        QueryResultSoA rightDateSoA = createSoAForTest(rightDateEntries, defaultRequirements);

        List<SoAJoinOptimizer.SoAJoinKeyMatch> result = SoAJoinOptimizer.performOptimizedHashJoin(
            leftDateSoA, rightDateSoA, "DATE", "DATE");

        assertEquals(2, result.size(), "Structural DATE join should produce 2 matches");
    }

    @Test
    void testTemporalJoinEqualsPredicate() throws QueryExecutionException {
        LocalDate d1 = LocalDate.of(2023, 5, 1);
        LocalDate d2 = LocalDate.of(2023, 5, 2);
        LocalDate d3 = LocalDate.of(2023, 5, 3);

        List<TestDataEntry> left = new ArrayList<>();
        left.add(new TestDataEntry(d1, ValueType.DATE, "left.date", 1, 1, 0,0,0, 100));
        left.add(new TestDataEntry(d2, ValueType.DATE, "left.date", 1, 1, 0,0,0, 101));
        QueryResultSoA leftSoA = createSoAForTest(left, defaultRequirements);

        List<TestDataEntry> right = new ArrayList<>();
        right.add(new TestDataEntry(d1, ValueType.DATE, "right.date", 2, 1, 0,0,0, 200));
        right.add(new TestDataEntry(d2, ValueType.DATE, "right.date", 2, 1, 0,0,0, 201));
        right.add(new TestDataEntry(d3, ValueType.DATE, "right.date", 2, 1, 0,0,0, 202));
        QueryResultSoA rightSoA = createSoAForTest(right, defaultRequirements);

        List<SoAJoinOptimizer.SoAJoinKeyMatch> result = SoAJoinOptimizer.performOptimizedTemporalJoin(
            leftSoA, rightSoA, "DATE", "DATE", "EQUALS");

        assertEquals(2, result.size(), "EQUALS should join entries with identical dates only");
    }

    @Test
    void testTemporalJoinBeforePredicate() throws QueryExecutionException {
        LocalDate d1 = LocalDate.of(2023, 6, 1);
        LocalDate d2 = LocalDate.of(2023, 6, 2);
        LocalDate d3 = LocalDate.of(2023, 6, 3);

        List<TestDataEntry> left = new ArrayList<>();
        left.add(new TestDataEntry(d1, ValueType.DATE, "left.date", 1, 1, 0,0,0, 10));
        left.add(new TestDataEntry(d2, ValueType.DATE, "left.date", 1, 1, 0,0,0, 11));
        left.add(new TestDataEntry(d3, ValueType.DATE, "left.date", 1, 1, 0,0,0, 12));
        QueryResultSoA leftSoA = createSoAForTest(left, defaultRequirements);

        List<TestDataEntry> right = new ArrayList<>();
        right.add(new TestDataEntry(d2, ValueType.DATE, "right.date", 2, 1, 0,0,0, 20));
        right.add(new TestDataEntry(d3, ValueType.DATE, "right.date", 2, 1, 0,0,0, 21));
        QueryResultSoA rightSoA = createSoAForTest(right, defaultRequirements);

        List<SoAJoinOptimizer.SoAJoinKeyMatch> result = SoAJoinOptimizer.performOptimizedTemporalJoin(
            leftSoA, rightSoA, "DATE", "DATE", "BEFORE");

        assertEquals(3, result.size(), "BEFORE should produce 3 matches (d1<d2, d1<d3, d2<d3)");
    }

    @Test
    void testTemporalJoinAfterPredicate() throws QueryExecutionException {
        LocalDate d1 = LocalDate.of(2023, 7, 1);
        LocalDate d2 = LocalDate.of(2023, 7, 2);
        LocalDate d3 = LocalDate.of(2023, 7, 3);

        List<TestDataEntry> left = new ArrayList<>();
        left.add(new TestDataEntry(d2, ValueType.DATE, "left.date", 1, 1, 0,0,0, 30));
        left.add(new TestDataEntry(d3, ValueType.DATE, "left.date", 1, 1, 0,0,0, 31));
        QueryResultSoA leftSoA = createSoAForTest(left, defaultRequirements);

        List<TestDataEntry> right = new ArrayList<>();
        right.add(new TestDataEntry(d1, ValueType.DATE, "right.date", 2, 1, 0,0,0, 40));
        right.add(new TestDataEntry(d2, ValueType.DATE, "right.date", 2, 1, 0,0,0, 41));
        QueryResultSoA rightSoA = createSoAForTest(right, defaultRequirements);

        List<SoAJoinOptimizer.SoAJoinKeyMatch> result = SoAJoinOptimizer.performOptimizedTemporalJoin(
            leftSoA, rightSoA, "DATE", "DATE", "AFTER");

        assertEquals(3, result.size(), "AFTER should produce 3 matches (d2>d1, d3>d1, d3>d2)");
    }

    @Test
    void testTemporalJoinDeduplicatesDuplicateBindings() throws QueryExecutionException {
        LocalDate d1 = LocalDate.of(2023, 8, 1);

        List<TestDataEntry> left = new ArrayList<>();
        // Two rows with the same conceptualRowId and same date
        left.add(new TestDataEntry(d1, ValueType.DATE, "left.date", 1, 1, 0,0,0, 77));
        left.add(new TestDataEntry(d1, ValueType.DATE, "left.date", 1, 1, 0,0,0, 77));
        QueryResultSoA leftSoA = createSoAForTest(left, defaultRequirements);

        List<TestDataEntry> right = new ArrayList<>();
        right.add(new TestDataEntry(d1, ValueType.DATE, "right.date", 2, 1, 0,0,0, 88));
        QueryResultSoA rightSoA = createSoAForTest(right, defaultRequirements);

        List<SoAJoinOptimizer.SoAJoinKeyMatch> result = SoAJoinOptimizer.performOptimizedTemporalJoin(
            leftSoA, rightSoA, "DATE", "DATE", "EQUALS");

        assertEquals(1, result.size(), "Duplicate bindings for the same conceptual ID and date should be deduplicated");
    }

    @Test
    void testTemporalJoinUnsupportedPredicateThrows() {
        LocalDate d1 = LocalDate.of(2023, 9, 1);

        List<TestDataEntry> left = new ArrayList<>();
        left.add(new TestDataEntry(d1, ValueType.DATE, "left.date", 1, 1, 0,0,0, 1));
        QueryResultSoA leftSoA = createSoAForTest(left, defaultRequirements);

        List<TestDataEntry> right = new ArrayList<>();
        right.add(new TestDataEntry(d1, ValueType.DATE, "right.date", 2, 1, 0,0,0, 2));
        QueryResultSoA rightSoA = createSoAForTest(right, defaultRequirements);

        try {
            SoAJoinOptimizer.performOptimizedTemporalJoin(leftSoA, rightSoA, "DATE", "DATE", "OVERLAPS");
            assertTrue(false, "Expected QueryExecutionException to be thrown for unsupported predicate");
        } catch (QueryExecutionException ex) {
            assertTrue(true);
        }
    }
}