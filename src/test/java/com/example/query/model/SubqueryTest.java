package com.example.query.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.query.binding.VariableRegistry;
import com.example.query.model.JoinCondition.JoinOperatorType;
import com.example.query.model.JoinCondition.JoinType;
import com.example.query.model.condition.Contains;

/**
 * Tests for SubquerySpec and JoinCondition interactions.
 */
@DisplayName("Subquery and Join Model Tests")
@ExtendWith(MockitoExtension.class)
class SubqueryTest {

    @Mock
    private Query subquery;

    @Test
    @DisplayName("Test SubquerySpec creation")
    void testSubquerySpecCreation() {
        String alias = "sub1";
        List<String> projectedColumns = List.of("col1", "col2");
        SubquerySpec spec = new SubquerySpec(subquery, alias, Optional.of(projectedColumns));
        assertEquals(alias, spec.alias());
        assertEquals(subquery, spec.subquery());
        assertTrue(spec.projectedColumns().isPresent());
        assertEquals(projectedColumns, spec.projectedColumns().get());
        assertTrue(spec.toString().contains(alias));
        assertTrue(spec.toString().contains("col1"));
    }

    @Test
    @DisplayName("Test SubquerySpec creation without projected columns")
    void testSubquerySpecNoProjected() {
        String alias = "sub2";
        SubquerySpec spec = new SubquerySpec(subquery, alias);
        assertEquals(alias, spec.alias());
        assertEquals(subquery, spec.subquery());
        assertFalse(spec.projectedColumns().isPresent());
    }

    @Test
    @DisplayName("Test Query creation with subquery and join - Equality Join")
    void testQueryWithSubqueryAndJoinEquality() {
        VariableRegistry subRegistry = new VariableRegistry();
        SubquerySpec subquerySpec = new SubquerySpec(subquery, "sub");
        VariableRegistry mainRegistry = new VariableRegistry();

        JoinType actualJoinType = JoinType.INNER; // Define the JoinType
        // Use factory for equality join
        JoinCondition joinCondition = JoinCondition.createEqualityJoin("main.id", "sub.id", actualJoinType);

        // Create a JoinStep, passing actualJoinType directly
        JoinStep joinStep = new JoinStep("sub", subquerySpec.subquery(), joinCondition, actualJoinType, "main");

        Query mainQuery = new Query(
            "source",
            List.of(new Contains(List.of("main_term"))),
            List.of(), // orderBy
            Optional.empty(), // limit
            Query.Granularity.DOCUMENT, // granularity
            Optional.empty(), // granularitySize
            List.of(), // No select columns in this test
            mainRegistry, // variableRegistry
            List.of(joinStep), // joinSteps
            Optional.of("main"), // Explicit main alias
            List.of() // groupByColumns
        );

        assertEquals(1, mainQuery.joinSteps().size());
        JoinStep retrievedJoinStep = mainQuery.joinSteps().get(0);
        assertEquals(joinStep, retrievedJoinStep);
        assertEquals(joinCondition, retrievedJoinStep.onCondition());
        assertEquals(actualJoinType, retrievedJoinStep.joinType()); // Assert the joinType on the JoinStep
    }

    @Test
    @DisplayName("Test Query creation with subquery and join - Temporal Join")
    void testQueryWithSubqueryAndJoinTemporal() {
        VariableRegistry subRegistry = new VariableRegistry();
        SubquerySpec subquerySpec = new SubquerySpec(subquery, "sub");
        VariableRegistry mainRegistry = new VariableRegistry();

        JoinType actualJoinType = JoinType.INNER; // Define the JoinType
        // Use full constructor for temporal join with INTERSECT
        JoinCondition joinCondition = new JoinCondition("main.date", "sub.date", actualJoinType, JoinOperatorType.TEMPORAL, Optional.of(TemporalPredicate.INTERSECT), Optional.empty());

        // Create a JoinStep, passing actualJoinType directly
        JoinStep joinStep = new JoinStep("sub", subquerySpec.subquery(), joinCondition, actualJoinType, "main");

        Query mainQuery = new Query(
            "source",
            List.of(), // No main conditions
            List.of(), // orderBy
            Optional.empty(), // limit
            Query.Granularity.DOCUMENT, // granularity
            Optional.empty(), // granularitySize
            List.of(), // No select columns
            mainRegistry, // variableRegistry
            List.of(joinStep), // joinSteps
            Optional.of("main"), // mainAlias
            List.of() // groupByColumns
        );

        assertEquals(1, mainQuery.joinSteps().size());
        JoinStep retrievedJoinStep = mainQuery.joinSteps().get(0);
        assertEquals(joinStep, retrievedJoinStep);
        assertEquals(joinCondition, retrievedJoinStep.onCondition());
        assertEquals(actualJoinType, retrievedJoinStep.joinType()); // Assert the joinType on the JoinStep
    }

    @Test
    @DisplayName("Test JoinCondition validation")
    void testJoinConditionValidation() {
        // Test valid TEMPORAL condition (Proximity)
        assertDoesNotThrow(() -> new JoinCondition("l.col", "r.col", JoinType.INNER, JoinOperatorType.TEMPORAL, Optional.of(TemporalPredicate.PROXIMITY), Optional.of(5)));
        // Test valid TEMPORAL condition (Non-Proximity)
        assertDoesNotThrow(() -> new JoinCondition("l.col", "r.col", JoinType.INNER, JoinOperatorType.TEMPORAL, Optional.of(TemporalPredicate.INTERSECT), Optional.empty()));
        // Test factory method for Temporal
        assertDoesNotThrow(() -> JoinCondition.createTemporalJoin("l.col", "r.col", JoinType.INNER, TemporalPredicate.INTERSECT));

        // Test valid EQUALITY condition
        assertDoesNotThrow(() -> new JoinCondition("l.col", "r.col", JoinType.INNER, JoinOperatorType.EQUALITY, Optional.empty(), Optional.empty()));
        // Test factory method for Equality
        assertDoesNotThrow(() -> JoinCondition.createEqualityJoin("l.col", "r.col", JoinType.INNER));

        // Test null checks (using full constructor)
        assertThrows(NullPointerException.class, () -> new JoinCondition(null, "r.col", JoinType.INNER, JoinOperatorType.EQUALITY, Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> new JoinCondition("l.col", null, JoinType.INNER, JoinOperatorType.EQUALITY, Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> new JoinCondition("l.col", "r.col", null, JoinOperatorType.EQUALITY, Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> new JoinCondition("l.col", "r.col", JoinType.INNER, null, Optional.empty(), Optional.empty())); // Operator type
        assertThrows(NullPointerException.class, () -> new JoinCondition("l.col", "r.col", JoinType.INNER, JoinOperatorType.TEMPORAL, null, Optional.empty())); // Temporal predicate Optional

        // Test operator type / temporal predicate mismatch
        assertThrows(IllegalArgumentException.class, () -> new JoinCondition("l.col", "r.col", JoinType.INNER, JoinOperatorType.TEMPORAL, Optional.empty(), Optional.empty()), "Temporal predicate required for TEMPORAL join");
        assertThrows(IllegalArgumentException.class, () -> new JoinCondition("l.col", "r.col", JoinType.INNER, JoinOperatorType.EQUALITY, Optional.of(TemporalPredicate.INTERSECT), Optional.empty()), "Temporal predicate not allowed for EQUALITY join");

        // Test proximity validation
        assertThrows(IllegalArgumentException.class, () -> new JoinCondition("l.col", "r.col", JoinType.INNER, JoinOperatorType.TEMPORAL, Optional.of(TemporalPredicate.PROXIMITY), Optional.empty()),
            "Proximity window must be specified for PROXIMITY joins");

        assertThrows(IllegalArgumentException.class, () -> new JoinCondition("l.col", "r.col", JoinType.INNER, JoinOperatorType.TEMPORAL, Optional.of(TemporalPredicate.INTERSECT), Optional.of(5)),
            "Proximity window should not be specified for non-PROXIMITY joins");

        // Test window with EQUALITY join
        assertThrows(IllegalArgumentException.class, () -> new JoinCondition("l.col", "r.col", JoinType.INNER, JoinOperatorType.EQUALITY, Optional.empty(), Optional.of(5)),
            "Proximity window should not be specified for non-PROXIMITY joins"); // EQUALITY is implicitly non-proximity
    }
}