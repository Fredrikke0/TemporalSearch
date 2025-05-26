package com.example.query.executor;

import com.example.query.binding.JoinedMatch;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SoAJoinOptimizerTest {

    private AttributeRequirements defaultRequirements;
    private List<MatchDetail> leftDetails;
    private List<MatchDetail> rightDetails;

    @BeforeEach
    void setUp() {
        defaultRequirements = new AttributeRequirements();
        defaultRequirements.needsSentenceId = true;
        defaultRequirements.needsPositions = true;
        defaultRequirements.needsDateValues = true;

        // Set up test data for document ID joins using correct constructor
        leftDetails = new ArrayList<>();
        leftDetails.add(new MatchDetail("term1", ValueType.TERM, "left.var", 1, 1, 10, 20));
        leftDetails.add(new MatchDetail("term2", ValueType.TERM, "left.var", 1, 2, 25, 35)); // Same doc
        leftDetails.add(new MatchDetail("term3", ValueType.TERM, "left.var", 2, 1, 40, 50));
        leftDetails.add(new MatchDetail("term4", ValueType.TERM, "left.var", 3, 1, 60, 70));

        rightDetails = new ArrayList<>();
        rightDetails.add(new MatchDetail("other1", ValueType.TERM, "right.var", 1, 1, 80, 90));
        rightDetails.add(new MatchDetail("other2", ValueType.TERM, "right.var", 2, 1, 100, 110)); // Same doc as left term3
        rightDetails.add(new MatchDetail("other3", ValueType.TERM, "right.var", 2, 2, 120, 130)); // Same doc as left term3
        rightDetails.add(new MatchDetail("other4", ValueType.TERM, "right.var", 4, 1, 140, 150)); // No match
    }

    @Test
    void testOptimizedDocumentIdJoin() {
        // Execute optimized join
        List<JoinedMatch> result = SoAJoinOptimizer.performOptimizedHashJoin(
            leftDetails, rightDetails, "document_id", "document_id", defaultRequirements);

        // Verify results
        assertEquals(4, result.size(), "Should have 4 join matches");

        // Expected matches:
        // Doc 1: term1+other1, term2+other1 (2 matches)
        // Doc 2: term3+other2, term3+other3 (2 matches)

        // Verify document 1 matches
        long doc1Matches = result.stream()
            .filter(jm -> jm.left().getDocumentId() == 1 && jm.right().getDocumentId() == 1)
            .count();
        assertEquals(2, doc1Matches, "Should have 2 matches for document 1");

        // Verify document 2 matches
        long doc2Matches = result.stream()
            .filter(jm -> jm.left().getDocumentId() == 2 && jm.right().getDocumentId() == 2)
            .count();
        assertEquals(2, doc2Matches, "Should have 2 matches for document 2");

        // Verify no matches for document 3
        long doc3Matches = result.stream()
            .filter(jm -> jm.left().getDocumentId() == 3 || jm.right().getDocumentId() == 3)
            .count();
        assertEquals(0, doc3Matches, "Should have 0 matches involving document 3");
    }

    @Test
    void testOptimizedDateJoin() {
        // Create date test data
        LocalDate date1 = LocalDate.of(2023, 1, 1);
        LocalDate date2 = LocalDate.of(2023, 1, 2);
        LocalDate date3 = LocalDate.of(2023, 1, 3);

        List<MatchDetail> leftDateDetails = new ArrayList<>();
        leftDateDetails.add(new MatchDetail(date1, ValueType.DATE, "left.date", 10, 1, 10, 20));
        leftDateDetails.add(new MatchDetail(date1, ValueType.DATE, "left.date", 11, 1, 25, 35)); // Same date
        leftDateDetails.add(new MatchDetail(date2, ValueType.DATE, "left.date", 12, 1, 40, 50));

        List<MatchDetail> rightDateDetails = new ArrayList<>();
        rightDateDetails.add(new MatchDetail(date1, ValueType.DATE, "right.date", 20, 1, 80, 90));
        rightDateDetails.add(new MatchDetail(date2, ValueType.DATE, "right.date", 21, 1, 100, 110));
        rightDateDetails.add(new MatchDetail(date3, ValueType.DATE, "right.date", 22, 1, 120, 130)); // No match

        // Execute optimized date join
        List<JoinedMatch> result = SoAJoinOptimizer.performOptimizedHashJoin(
            leftDateDetails, rightDateDetails, "date", "date", defaultRequirements);

        // Verify results: date1 (2 left * 1 right = 2) + date2 (1 left * 1 right = 1) = 3 total
        assertEquals(3, result.size(), "Should have 3 join matches");

        // Count matches by date
        long date1Matches = result.stream()
            .filter(jm -> date1.equals(jm.left().value()) && date1.equals(jm.right().value()))
            .count();
        assertEquals(2, date1Matches, "Should have 2 matches for date 2023-01-01");

        long date2Matches = result.stream()
            .filter(jm -> date2.equals(jm.left().value()) && date2.equals(jm.right().value()))
            .count();
        assertEquals(1, date2Matches, "Should have 1 match for date 2023-01-02");
    }

    @Test
    void testOptimizedSentenceIdJoin() {
        // Create test data with sentence IDs
        List<MatchDetail> leftSentenceDetails = new ArrayList<>();
        leftSentenceDetails.add(new MatchDetail("term1", ValueType.TERM, "left.var", 1, 1, 10, 20));
        leftSentenceDetails.add(new MatchDetail("term2", ValueType.TERM, "left.var", 1, 1, 25, 35)); // Same sentence

        List<MatchDetail> rightSentenceDetails = new ArrayList<>();
        rightSentenceDetails.add(new MatchDetail("other1", ValueType.TERM, "right.var", 1, 1, 80, 90));
        rightSentenceDetails.add(new MatchDetail("other2", ValueType.TERM, "right.var", 2, 2, 100, 110)); // Different sentence

        // Execute optimized sentence ID join
        List<JoinedMatch> result = SoAJoinOptimizer.performOptimizedHashJoin(
            leftSentenceDetails, rightSentenceDetails, "sentence_id", "sentence_id", defaultRequirements);

        // Verify results: only sentence 1 should match (2 left * 1 right = 2 matches)
        assertEquals(2, result.size(), "Should have 2 join matches");

        // All matches should be for sentence 1
        for (JoinedMatch match : result) {
            assertEquals(1, match.left().getSentenceId(), "Left sentence ID should be 1");
            assertEquals(1, match.right().getSentenceId(), "Right sentence ID should be 1");
        }
    }

    @Test
    void testEmptyInputHandling() {
        List<MatchDetail> emptyList = new ArrayList<>();

        // Test with empty left side
        List<JoinedMatch> result1 = SoAJoinOptimizer.performOptimizedHashJoin(
            emptyList, rightDetails, "document_id", "document_id", defaultRequirements);
        assertTrue(result1.isEmpty(), "Join with empty left side should return empty result");

        // Test with empty right side
        List<JoinedMatch> result2 = SoAJoinOptimizer.performOptimizedHashJoin(
            leftDetails, emptyList, "document_id", "document_id", defaultRequirements);
        assertTrue(result2.isEmpty(), "Join with empty right side should return empty result");

        // Test with both sides empty
        List<JoinedMatch> result3 = SoAJoinOptimizer.performOptimizedHashJoin(
            emptyList, emptyList, "document_id", "document_id", defaultRequirements);
        assertTrue(result3.isEmpty(), "Join with both sides empty should return empty result");
    }

    @Test
    void testNoMatchingValues() {
        // Create test data with no matching document IDs
        List<MatchDetail> leftNoMatch = new ArrayList<>();
        leftNoMatch.add(new MatchDetail("unique1", ValueType.TERM, "left.var", 1, 1, 10, 20));
        leftNoMatch.add(new MatchDetail("unique2", ValueType.TERM, "left.var", 2, 1, 25, 35));

        List<MatchDetail> rightNoMatch = new ArrayList<>();
        rightNoMatch.add(new MatchDetail("different1", ValueType.TERM, "right.var", 3, 1, 80, 90));
        rightNoMatch.add(new MatchDetail("different2", ValueType.TERM, "right.var", 4, 1, 100, 110));

        // Execute join
        List<JoinedMatch> result = SoAJoinOptimizer.performOptimizedHashJoin(
            leftNoMatch, rightNoMatch, "document_id", "document_id", defaultRequirements);

        assertTrue(result.isEmpty(), "Join with no matching document IDs should return empty result");
    }

    @Test
    void testAttributeRequirementsPassthrough() {
        // Test that AttributeRequirements are properly passed through
        AttributeRequirements customRequirements = new AttributeRequirements();
        customRequirements.needsPositions = false; // Different from default

        List<JoinedMatch> result = SoAJoinOptimizer.performOptimizedHashJoin(
            leftDetails, rightDetails, "document_id", "document_id", customRequirements);

        // The result should be the same regardless of requirements for this test
        // (requirements mainly affect deserialization optimization)
        assertNotNull(result, "Result should not be null");
        assertEquals(4, result.size(), "Should still have same number of matches");
    }
} 