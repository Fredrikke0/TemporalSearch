package com.example.query.executor;

import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import com.example.query.binding.JoinedMatch;
import com.example.query.model.TemporalPredicate;
import com.example.core.Position;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

class JoinHandlerTest {

    private JoinHandler joinHandler;
    // Use a simple, consistent base key for testing extraction logic
    private static final String DATE_KEY = "date";

    @BeforeEach
    void setUp() {
        joinHandler = new JoinHandler();
    }

    // Helper to create a Position object
    // Using dummy IDs and char positions as they aren't directly used by the tested join logic
    private Position createPosition(int docId, int sentenceId) {
        return new Position(docId, sentenceId, 0, 0); // Using 0, 0 for char positions
    }

    // Helper to create MatchDetail with a LocalDate value bound to a variable
    private MatchDetail createMatchDetailWithDate(int docId, int sentenceId, LocalDate date, String variableAlias) {
        Position pos = createPosition(docId, sentenceId);
        // The LocalDate is the 'value', type is DATE, Position holds metadata
        // Variable name includes alias prefix expected by extractor (e.g., "l.date")
        return new MatchDetail(date, ValueType.DATE, pos, Optional.of(variableAlias + "." + DATE_KEY));
    }

    // Helper to create a simple MatchDetail without the specific date value we are joining on
    // (e.g., represents a match for a different condition)
     private MatchDetail createMatchDetailNoDateValue(int docId, int sentenceId) {
         // Still needs a Position
         Position pos = createPosition(docId, sentenceId);
         // Value is something else, not the date we query/join on
         return new MatchDetail("some other value", ValueType.TERM, pos, Optional.empty());
     }


    @Test
    void testTemporalSortMergeJoin_Before_Simple() throws QueryExecutionException {
        // Use alias prefixes 'l' and 'r' for variable names
        MatchDetail left1 = createMatchDetailWithDate(1, 1, LocalDate.of(2023, 1, 10), "l");
        MatchDetail left2 = createMatchDetailWithDate(1, 2, LocalDate.of(2023, 1, 15), "l");
        MatchDetail right1 = createMatchDetailWithDate(2, 1, LocalDate.of(2023, 1, 12), "r");
        MatchDetail right2 = createMatchDetailWithDate(2, 2, LocalDate.of(2023, 1, 20), "r");

        List<MatchDetail> leftDetails = List.of(left1, left2);
        List<MatchDetail> rightDetails = List.of(right1, right2);

        // Pass the base key "date" for extraction
        List<JoinedMatch> result = joinHandler.performTemporalSortMergeJoin(leftDetails, rightDetails, DATE_KEY, DATE_KEY, TemporalPredicate.BEFORE);

        assertEquals(3, result.size(), "Expected 3 joined pairs for BEFORE");
        Set<JoinedMatch> resultSet = Set.copyOf(result); // Use Set for easier contains check

        assertTrue(resultSet.contains(new JoinedMatch(left1, right1)), "Missing pair: left1 < right1");
        assertTrue(resultSet.contains(new JoinedMatch(left1, right2)), "Missing pair: left1 < right2");
        assertTrue(resultSet.contains(new JoinedMatch(left2, right2)), "Missing pair: left2 < right2");
    }

    @Test
    void testTemporalSortMergeJoin_After_Simple() throws QueryExecutionException {
         MatchDetail left1 = createMatchDetailWithDate(1, 1, LocalDate.of(2023, 1, 10), "l");
         MatchDetail left2 = createMatchDetailWithDate(1, 2, LocalDate.of(2023, 1, 15), "l");
         MatchDetail right1 = createMatchDetailWithDate(2, 1, LocalDate.of(2023, 1, 12), "r");
         MatchDetail right2 = createMatchDetailWithDate(2, 2, LocalDate.of(2023, 1, 8), "r"); // Earlier date

         List<MatchDetail> leftDetails = List.of(left1, left2); // Order doesn't matter for input
         List<MatchDetail> rightDetails = List.of(right1, right2);

         List<JoinedMatch> result = joinHandler.performTemporalSortMergeJoin(leftDetails, rightDetails, DATE_KEY, DATE_KEY, TemporalPredicate.AFTER);

         assertEquals(3, result.size(), "Expected 3 joined pairs for AFTER");
         Set<JoinedMatch> resultSet = Set.copyOf(result);

         assertTrue(resultSet.contains(new JoinedMatch(left1, right2)), "Missing pair: left1 > right2");
         assertTrue(resultSet.contains(new JoinedMatch(left2, right1)), "Missing pair: left2 > right1");
         assertTrue(resultSet.contains(new JoinedMatch(left2, right2)), "Missing pair: left2 > right2");
    }

     @Test
    void testTemporalSortMergeJoin_Before_EmptyLeft() throws QueryExecutionException {
        MatchDetail right1 = createMatchDetailWithDate(2, 1, LocalDate.of(2023, 1, 12), "r");
        List<MatchDetail> leftDetails = List.of();
        List<MatchDetail> rightDetails = List.of(right1);

        List<JoinedMatch> result = joinHandler.performTemporalSortMergeJoin(leftDetails, rightDetails, DATE_KEY, DATE_KEY, TemporalPredicate.BEFORE);
        assertTrue(result.isEmpty(), "Result should be empty when left side is empty");
    }

    @Test
    void testTemporalSortMergeJoin_After_EmptyRight() throws QueryExecutionException {
        MatchDetail left1 = createMatchDetailWithDate(1, 1, LocalDate.of(2023, 1, 10), "l");
        List<MatchDetail> leftDetails = List.of(left1);
        List<MatchDetail> rightDetails = List.of();

        List<JoinedMatch> result = joinHandler.performTemporalSortMergeJoin(leftDetails, rightDetails, DATE_KEY, DATE_KEY, TemporalPredicate.AFTER);
        assertTrue(result.isEmpty(), "Result should be empty when right side is empty");
    }

     @Test
    void testTemporalSortMergeJoin_Before_IdenticalDates() throws QueryExecutionException {
        // BEFORE should not include pairs with equal dates
        MatchDetail left1 = createMatchDetailWithDate(1, 1, LocalDate.of(2023, 1, 10), "l");
        MatchDetail right1 = createMatchDetailWithDate(2, 1, LocalDate.of(2023, 1, 10), "r");
        MatchDetail right2 = createMatchDetailWithDate(2, 2, LocalDate.of(2023, 1, 11), "r");

        List<MatchDetail> leftDetails = List.of(left1);
        List<MatchDetail> rightDetails = List.of(right1, right2);

        List<JoinedMatch> result = joinHandler.performTemporalSortMergeJoin(leftDetails, rightDetails, DATE_KEY, DATE_KEY, TemporalPredicate.BEFORE);

        assertEquals(1, result.size(), "Expected 1 pair for BEFORE with identical dates");
        assertEquals(new JoinedMatch(left1, right2), result.get(0), "Only left1 < right2 should match");
    }

    @Test
    void testTemporalSortMergeJoin_After_IdenticalDates() throws QueryExecutionException {
         // AFTER should not include pairs with equal dates
         MatchDetail left1 = createMatchDetailWithDate(1, 1, LocalDate.of(2023, 1, 10), "l");
         MatchDetail left2 = createMatchDetailWithDate(1, 2, LocalDate.of(2023, 1, 11), "l");
         MatchDetail right1 = createMatchDetailWithDate(2, 1, LocalDate.of(2023, 1, 10), "r");

         List<MatchDetail> leftDetails = List.of(left1, left2);
         List<MatchDetail> rightDetails = List.of(right1);

         List<JoinedMatch> result = joinHandler.performTemporalSortMergeJoin(leftDetails, rightDetails, DATE_KEY, DATE_KEY, TemporalPredicate.AFTER);

         assertEquals(1, result.size(), "Expected 1 pair for AFTER with identical dates");
         assertEquals(new JoinedMatch(left2, right1), result.get(0), "Only left2 > right1 should match");
    }

    @Test
    void testTemporalSortMergeJoin_Before_WithNonDateValuesOrWrongType() throws QueryExecutionException {
        MatchDetail left1 = createMatchDetailWithDate(1, 1, LocalDate.of(2023, 1, 10), "l");
        MatchDetail left2NoDate = createMatchDetailNoDateValue(1, 2); // Does not have DATE_KEY variable
        // Detail with correct key but wrong type (should be filtered)
        Position wrongTypePos = createPosition(1, 3);
        MatchDetail left3WrongType = new MatchDetail("not-a-date", ValueType.TERM, wrongTypePos, Optional.of("l." + DATE_KEY));

        MatchDetail right1 = createMatchDetailWithDate(2, 1, LocalDate.of(2023, 1, 12), "r");
        MatchDetail right2NoDate = createMatchDetailNoDateValue(2, 2); // Does not have DATE_KEY variable

        List<MatchDetail> leftDetails = List.of(left1, left2NoDate, left3WrongType);
        List<MatchDetail> rightDetails = List.of(right1, right2NoDate);

        // The join method should internally filter out details without the DATE_KEY variable
        // or where the value for DATE_KEY is not a LocalDate
        List<JoinedMatch> result = joinHandler.performTemporalSortMergeJoin(leftDetails, rightDetails, DATE_KEY, DATE_KEY, TemporalPredicate.BEFORE);

        assertEquals(1, result.size(), "Expected 1 pair after filtering non-date/wrong-type details");
        assertEquals(new JoinedMatch(left1, right1), result.get(0), "Only left1 < right1 should match");
    }

    @Test
    void testTemporalSortMergeJoin_After_AllLeftGreater() throws QueryExecutionException {
        MatchDetail left1 = createMatchDetailWithDate(1, 1, LocalDate.of(2023, 1, 15), "l");
        MatchDetail left2 = createMatchDetailWithDate(1, 2, LocalDate.of(2023, 1, 20), "l");
        MatchDetail right1 = createMatchDetailWithDate(2, 1, LocalDate.of(2023, 1, 10), "r");
        MatchDetail right2 = createMatchDetailWithDate(2, 2, LocalDate.of(2023, 1, 12), "r");

        List<MatchDetail> leftDetails = List.of(left1, left2);
        List<MatchDetail> rightDetails = List.of(right1, right2);

        List<JoinedMatch> result = joinHandler.performTemporalSortMergeJoin(leftDetails, rightDetails, DATE_KEY, DATE_KEY, TemporalPredicate.AFTER);

        // Expected: (left1, right1), (left1, right2), (left2, right1), (left2, right2)
        assertEquals(4, result.size(), "Expected 4 pairs when all left > all right");
        Set<JoinedMatch> resultSet = Set.copyOf(result);
        assertTrue(resultSet.contains(new JoinedMatch(left1, right1)));
        assertTrue(resultSet.contains(new JoinedMatch(left1, right2)));
        assertTrue(resultSet.contains(new JoinedMatch(left2, right1)));
        assertTrue(resultSet.contains(new JoinedMatch(left2, right2)));
    }

    @Test
    void testTemporalSortMergeJoin_Before_AllLeftSmaller() throws QueryExecutionException {
        MatchDetail left1 = createMatchDetailWithDate(1, 1, LocalDate.of(2023, 1, 5), "l");
        MatchDetail left2 = createMatchDetailWithDate(1, 2, LocalDate.of(2023, 1, 8), "l");
        MatchDetail right1 = createMatchDetailWithDate(2, 1, LocalDate.of(2023, 1, 10), "r");
        MatchDetail right2 = createMatchDetailWithDate(2, 2, LocalDate.of(2023, 1, 12), "r");

        List<MatchDetail> leftDetails = List.of(left1, left2);
        List<MatchDetail> rightDetails = List.of(right1, right2);

        List<JoinedMatch> result = joinHandler.performTemporalSortMergeJoin(leftDetails, rightDetails, DATE_KEY, DATE_KEY, TemporalPredicate.BEFORE);

        // Expected: (left1, right1), (left1, right2), (left2, right1), (left2, right2)
        assertEquals(4, result.size(), "Expected 4 pairs when all left < all right");
         Set<JoinedMatch> resultSet = Set.copyOf(result);
        assertTrue(resultSet.contains(new JoinedMatch(left1, right1)));
        assertTrue(resultSet.contains(new JoinedMatch(left1, right2)));
        assertTrue(resultSet.contains(new JoinedMatch(left2, right1)));
        assertTrue(resultSet.contains(new JoinedMatch(left2, right2)));
    }

    @Test
    void testTemporalSortMergeJoin_ThrowsExceptionForUnsupportedPredicate() {
        List<MatchDetail> leftDetails = List.of(createMatchDetailWithDate(1, 1, LocalDate.of(2023, 1, 10), "l"));
        List<MatchDetail> rightDetails = List.of(createMatchDetailWithDate(2, 1, LocalDate.of(2023, 1, 12), "r"));

        assertThrows(QueryExecutionException.class, () -> {
            joinHandler.performTemporalSortMergeJoin(leftDetails, rightDetails, DATE_KEY, DATE_KEY, TemporalPredicate.EQUAL);
        }, "Should throw QueryExecutionException for unsupported predicate EQUAL");

        assertThrows(QueryExecutionException.class, () -> {
            joinHandler.performTemporalSortMergeJoin(leftDetails, rightDetails, DATE_KEY, DATE_KEY, TemporalPredicate.CONTAINS);
        }, "Should throw QueryExecutionException for unsupported predicate CONTAINS");
    }
} 