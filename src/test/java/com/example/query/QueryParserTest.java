package com.example.query;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.query.model.JoinCondition;
import com.example.query.model.JoinStep;
import com.example.query.model.Query;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.VariableColumn;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Dependency;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Not;
import com.example.query.model.condition.Temporal;

@DisplayName("Query Parser Tests")
class QueryParserTest {
    private QueryParser parser;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_DATE;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;

    @BeforeEach
    void setUp() {
        parser = new QueryParser();
    }

    @Test
    @DisplayName("Parse simple query without conditions")
    void parseSimpleQuery() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia";
        Query query = parser.parse(queryStr);

        assertEquals("wikipedia", query.source());
        assertTrue(query.conditions().isEmpty());
        assertTrue(query.orderBy().isEmpty());
        assertFalse(query.limit().isPresent());
    }

    @Test
    @DisplayName("Parse query with CONTAINS condition")
    void parseContainsCondition() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia WHERE CONTAINS(\"artificial intelligence\")";
        Query query = parser.parse(queryStr);

        assertEquals("wikipedia", query.source());
        assertEquals(1, query.conditions().size());

        Condition condition = query.conditions().get(0);
        assertTrue(condition instanceof Contains);

        // Check the terms list instead of the 'value' field
        List<String> expectedTerms = List.of("artificial", "intelligence");
        assertEquals(expectedTerms, ((Contains) condition).terms());
    }

    @Test
    @DisplayName("Parse query with NER condition")
    void parseNerCondition() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia WHERE NER(PERSON)";
        Query query = parser.parse(queryStr);

        assertEquals(1, query.conditions().size());
        assertTrue(query.conditions().get(0) instanceof Ner);

        Ner condition = (Ner) query.conditions().get(0);
        assertEquals("PERSON", condition.entityType());
        assertNull(condition.qualifiedVariableName());
        assertFalse(condition.isVariable());
    }

    @Test
    @DisplayName("Parse query with NER wildcard type")
    void parseNerWildcardType() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia WHERE NER(*)";
        Query query = parser.parse(queryStr);

        Ner condition = (Ner) query.conditions().get(0);
        assertEquals("*", condition.entityType());
        assertNull(condition.qualifiedVariableName());
        assertFalse(condition.isVariable());
    }

    @Test
    @DisplayName("Parse query with NER variable binding")
    void parseNerVariableBinding() throws QueryParseException {
        String queryStr = "SELECT t1.scientist FROM wikipedia ALIAS t1 WHERE NER(PERSON) BIND scientist";
        Query query = parser.parse(queryStr);

        Ner condition = (Ner) query.conditions().get(0);
        assertEquals("PERSON", condition.entityType());
        assertEquals("t1.scientist", condition.qualifiedVariableName());
        assertTrue(condition.isVariable());

        // Check select column
        assertEquals(1, query.selectColumns().size());
        assertTrue(query.selectColumns().get(0) instanceof VariableColumn);
        assertEquals("t1.scientist", ((VariableColumn) query.selectColumns().get(0)).getColumnName());
    }

    @Test
    @DisplayName("Parse query with date comparison <")
    void parseDateComparison() throws QueryParseException {
        String queryStr = "SELECT  t1.date FROM wikipedia ALIAS t1 WHERE DATE(< 2000) BIND date";
        Query query = parser.parse(queryStr);

        assertTrue(query.conditions().get(0) instanceof Temporal);
        Temporal condition = (Temporal) query.conditions().get(0);

        // Updated Assertions for BEFORE predicate
        assertEquals(TemporalPredicate.BEFORE, condition.temporalType());
        assertTrue(condition.startDate().isPresent(), "Start date should be present for literal comparison");
        assertEquals(LocalDateTime.of(2000, 1, 1, 0, 0), condition.startDate().get());
        assertFalse(condition.endDate().isPresent(), "End date should not be present for comparison predicate");

        assertNotNull(condition.variableName());
        assertEquals("t1.date", condition.variableName());

        // Check select column
        assertEquals(1, query.selectColumns().size());
        assertTrue(query.selectColumns().get(0) instanceof VariableColumn);
        assertEquals("t1.date", ((VariableColumn) query.selectColumns().get(0)).getColumnName());
    }

    @Test
    @DisplayName("Parse query with date comparison >")
    void parseDateNearRange() throws QueryParseException { // Renaming might be good later
        String queryStr = "SELECT t1.founding FROM wikipedia ALIAS t1 WHERE DATE(> 1980) BIND founding";
        Query query = parser.parse(queryStr);

        Temporal condition = (Temporal) query.conditions().get(0);

        // Updated Assertions for AFTER predicate
        assertEquals(TemporalPredicate.AFTER, condition.temporalType());
        assertTrue(condition.startDate().isPresent(), "Start date should be present for literal comparison");
        assertEquals(LocalDateTime.of(1980, 1, 1, 0, 0), condition.startDate().get()); // Literal date is 1980
        assertFalse(condition.endDate().isPresent(), "End date should not be present for comparison predicate");

        assertNotNull(condition.variableName());
        assertEquals("t1.founding", condition.variableName());

        // Check select column
        assertEquals(1, query.selectColumns().size());
        assertTrue(query.selectColumns().get(0) instanceof VariableColumn);
        assertEquals("t1.founding", ((VariableColumn) query.selectColumns().get(0)).getColumnName());
    }

    @Test
    @DisplayName("Parse query with dependency condition")
    void parseDependencyCondition() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia WHERE DEPENDS(\"cat\", \"nsubj\", \"eats\")";
        Query query = parser.parse(queryStr);

        assertTrue(query.conditions().get(0) instanceof Dependency);
        Dependency condition = (Dependency) query.conditions().get(0);

        assertEquals("cat", condition.governor());
        assertEquals("nsubj", condition.relation());
        assertEquals("eats", condition.dependent());
    }

    @Test
    @DisplayName("Parse query with multiple conditions using AND")
    void parseMultipleConditions() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia " +
                         "WHERE CONTAINS(\"physics\") " +
                         "AND NER(PERSON)";
        Query query = parser.parse(queryStr);

        assertEquals(1, query.conditions().size());
        assertTrue(query.conditions().get(0) instanceof Logical);

        Logical condition = (Logical) query.conditions().get(0);
        assertEquals(Logical.LogicalOperator.AND, condition.operator());
        assertEquals(2, condition.conditions().size());
        assertTrue(condition.conditions().get(0) instanceof Contains);
        assertTrue(condition.conditions().get(1) instanceof Ner);
    }

    @Test
    @DisplayName("Parse query with order by clause")
    void parseOrderByClause() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS), date FROM wikipedia WHERE DATE(< 2000) BIND date ORDER BY date DESC";
        Query query = parser.parse(queryStr);

        assertEquals(1, query.orderBy().size());
        assertEquals("-$main.date", query.orderBy().get(0));
    }

    @Test
    @DisplayName("Parse query with limit clause")
    void parseLimitClause() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia LIMIT 10";
        Query query = parser.parse(queryStr);

        assertTrue(query.limit().isPresent());
        assertEquals(10, query.limit().get());
    }

    @Test
    @DisplayName("Parse complex query with all features")
    void parseComplexQuery() throws QueryParseException {
        String queryStr = "SELECT t1.TITLE, t1.publication FROM wikipedia ALIAS t1 WHERE " +
                          "CONTAINS(\"theory of relativity\") " +
                          "AND NER(\"PERSON\") BIND scientist " +
                          "AND DATE(< 2000) BIND publication " +
                          "ORDER BY t1.TITLE " +
                          "LIMIT 5";
        Query query = parser.parse(queryStr);

        assertEquals("wikipedia", query.source());
        assertEquals("t1", query.mainAlias().get());
        assertEquals(1, query.conditions().size());
        assertEquals(1, query.orderBy().size());
        assertEquals("-t1.TITLE", query.orderBy().get(0));
        assertEquals(5, query.limit().get());

        // Verify the conditions in detail
        assertTrue(query.conditions().get(0) instanceof Logical);
        Logical condition = (Logical) query.conditions().get(0);
        assertEquals(Logical.LogicalOperator.AND, condition.operator());
        assertEquals(2, condition.conditions().size());

        // First condition should be a nested logical condition with CONTAINS and NER
        assertTrue(condition.conditions().get(0) instanceof Logical);
        // Second condition should be the temporal condition
        assertTrue(condition.conditions().get(1) instanceof Temporal);

        // Check the nested logical condition
        Logical nestedCondition = (Logical) condition.conditions().get(0);
        assertEquals(Logical.LogicalOperator.AND, nestedCondition.operator());
        assertEquals(2, nestedCondition.conditions().size());
        assertTrue(nestedCondition.conditions().get(0) instanceof Contains);
        assertTrue(nestedCondition.conditions().get(1) instanceof Ner);

        // Extract and check the NER condition from the nested AND
        Ner nerCondition = (Ner) nestedCondition.conditions().get(1);
        assertEquals("PERSON", nerCondition.entityType());
        assertEquals("t1.scientist", nerCondition.qualifiedVariableName());
        assertTrue(nerCondition.isVariable());

        // Extract and check the Temporal condition from the outer AND
        Temporal temporalCondition = (Temporal) condition.conditions().get(1);
        assertEquals(TemporalPredicate.BEFORE, temporalCondition.temporalType());
        assertEquals("t1.publication", temporalCondition.qualifiedVariableName().get());
        // Add more detailed checks on the temporal range if necessary
        assertTrue(temporalCondition.startDate().isPresent());
        assertEquals(LocalDateTime.of(2000, 1, 1, 0, 0), temporalCondition.startDate().get());
        assertFalse(temporalCondition.endDate().isPresent());
    }

    @Test
    @DisplayName("Parse query with nested conditions")
    void parseNestedConditions() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia " +
                         "WHERE (CONTAINS(\"physics\") AND NER(PERSON))";
        Query query = parser.parse(queryStr);

        assertEquals(1, query.conditions().size());
        assertTrue(query.conditions().get(0) instanceof Logical);

        Logical condition = (Logical) query.conditions().get(0);
        assertEquals(Logical.LogicalOperator.AND, condition.operator());
        assertEquals(2, condition.conditions().size());
    }

    @Test
    @DisplayName("Parse query with subquery - Requires JOIN")
    void parseSubquery() throws QueryParseException {
        String queryStr = "SELECT t1.TITLE FROM wikipedia AS t1 " +
                         "JOIN (SELECT docId FROM corpus WHERE NER(\"DATE\") BIND date) ALIAS t2 " +
                         "ON t1.docId CONTAINS t2.docId";

        // Expecting QueryParseException because the grammar doesn't support FROM alias or JOIN column comparison yet
        // This test might need adjustment after Stage 3 (variable qualification)
        assertThrows(QueryParseException.class, () -> {
            parser.parse(queryStr);
        }, "Parsing should fail due to unsupported FROM alias/JOIN column format");

        // If/when Stage 3 is implemented, the below checks would be relevant
        /*
        Query query = parser.parse(queryStr);
        assertEquals("wikipedia", query.source());
        assertEquals("t1", query.mainAlias().orElse(null));
        assertEquals(1, query.subqueries().size());
        assertEquals(1, query.joinCondition().size());

        SubquerySpec subquery = query.subqueries().get(0);
        assertEquals("t2", subquery.alias());
        assertEquals("corpus", subquery.subquery().source());
        assertTrue(subquery.subquery().conditions().get(0) instanceof Ner);

        JoinCondition join = query.joinCondition().get();
        assertEquals("t1.DOCID", join.leftColumn()); // Assuming qualified names are handled
        assertEquals("t2.DOCID", join.rightColumn());
        assertEquals(TemporalPredicate.CONTAINS, join.temporalPredicate().orElse(null));
        */
    }

    @Test
    @DisplayName("Parse query with OR condition")
    void parseOrCondition() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia WHERE CONTAINS(\"physics\") OR NER(PERSON)";
        Query query = parser.parse(queryStr);

        assertEquals(1, query.conditions().size());
        assertTrue(query.conditions().get(0) instanceof Logical);

        Logical condition = (Logical) query.conditions().get(0);
        assertEquals(Logical.LogicalOperator.OR, condition.operator());
        assertEquals(2, condition.conditions().size());

        assertTrue(condition.conditions().get(0) instanceof Contains);
        assertTrue(condition.conditions().get(1) instanceof Ner);

        Contains containsCondition = (Contains) condition.conditions().get(0);
        assertEquals(List.of("physics"), containsCondition.terms());

        Ner nerCondition = (Ner) condition.conditions().get(1);
        assertEquals("PERSON", nerCondition.entityType());
        assertNull(nerCondition.qualifiedVariableName());
        assertFalse(nerCondition.isVariable());
    }

    @Test
    @DisplayName("Parse query with NOT condition")
    void parseNotCondition() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia WHERE NOT CONTAINS(\"irrelevant\")";
        Query query = parser.parse(queryStr);

        assertEquals(1, query.conditions().size());
        assertTrue(query.conditions().get(0) instanceof Not);

        Not condition = (Not) query.conditions().get(0);
        assertTrue(condition.condition() instanceof Contains);

        Contains containsCondition = (Contains) condition.condition();
        assertEquals(List.of("irrelevant"), containsCondition.terms());
    }

    @Test
    @DisplayName("Parse query with mixed logical operators")
    void parseMixedLogicalOperators() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia ALIAS t1 " +
                         "WHERE (CONTAINS(\"physics\") OR CONTAINS(\"chemistry\")) " +
                         "AND NER(PERSON) BIND scientist";
        Query query = parser.parse(queryStr);

        assertEquals(1, query.conditions().size());
        assertTrue(query.conditions().get(0) instanceof Logical);

        Logical andCondition = (Logical) query.conditions().get(0);
        assertEquals(Logical.LogicalOperator.AND, andCondition.operator());
        assertEquals(2, andCondition.conditions().size());

        // Corrected assertions: First operand of AND is the OR, second is NER
        assertTrue(andCondition.conditions().get(0) instanceof Logical, "First part of AND should be the OR condition");
        assertTrue(andCondition.conditions().get(1) instanceof Ner, "Second part of AND should be the NER condition");

        // Check the nested OR condition (which is the first operand of AND)
        Logical orCondition = (Logical) andCondition.conditions().get(0);
        assertEquals(Logical.LogicalOperator.OR, orCondition.operator());
        assertEquals(2, orCondition.conditions().size());

        assertTrue(orCondition.conditions().get(0) instanceof Contains);
        assertTrue(orCondition.conditions().get(1) instanceof Contains);
    }

    @Test
    @DisplayName("Query with invalid column name should parse (validation happens later)")
    void invalidColumnNameShouldFail() {
        String queryStr = "SELECT not_real_column FROM wikipedia";

        // Parser should now accept this, as 'not_real_column' is a valid IDENTIFIER.
        // Semantic validation will catch that it's not bound.
        // Update: Validation now happens during parsing, so we expect an exception.
        QueryParseException exception = assertThrows(QueryParseException.class, () -> parser.parse(queryStr));
        assertTrue(exception.getMessage().contains("Variable $main.not_real_column is consumed but never produced"),
                   "Exception message should indicate that 'not_real_column' was not produced. Actual: " + exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT COUNT(DOCUMENTS) wikipedia",  // Missing FROM
        "SELECT COUNT(DOCUMENTS) FROM",       // Missing source
        "SELECT COUNT(DOCUMENTS) FROM wikipedia WHERE",  // Incomplete WHERE clause
        "SELECT COUNT(DOCUMENTS) FROM wikipedia ORDER",  // Incomplete ORDER BY
        "SELECT COUNT(DOCUMENTS) FROM wikipedia LIMIT",  // Missing limit value
        "SELECT COUNT(DOCUMENTS) FROM wikipedia WHERE CONTAINS(\"physics\") NER(\"PERSON\")",  // Missing AND
        "SELECT COUNT(DOCUMENTS) FROM wikipedia WHERE DATE(?date) < abc"  // Invalid date format
    })
    @DisplayName("Parse invalid queries should throw exception")
    void parseInvalidQueriesShouldThrowException(String queryStr) {
        assertThrows(QueryParseException.class, () -> parser.parse(queryStr));
    }

    @Test
    @DisplayName("Parse query with subquery and join")
    void testSubqueryWithJoin() throws QueryParseException {
        String queryStr = "SELECT t1.TITLE FROM wikipedia ALIAS t1 " +
                         "JOIN (SELECT DOCUMENT_ID FROM archive WHERE CONTAINS(\"report\")) ALIAS sub " +
                         "ON t1.DOCUMENT_ID CONTAINS sub.DOCUMENT_ID";

        // Assuming Stage 3 fully implemented JOIN ON qualified.qualified
        // If JOIN ON still expects variable names, this needs adjustment.
        Query query = assertDoesNotThrow(() -> parser.parse(queryStr),
            "Parsing should succeed if JOIN ON supports qualified columns");

        // Basic checks after parsing
        assertEquals("wikipedia", query.source());
        assertEquals("t1", query.mainAlias().orElse(null));
        assertEquals(1, query.joinSteps().size());
        assertFalse(query.joinSteps().isEmpty());

        JoinStep joinStep = query.joinSteps().get(0);
        Query subqueryAst = joinStep.subquery();

        assertEquals("sub", joinStep.rightSourceAlias());
        assertEquals("archive", subqueryAst.source());
        assertTrue(subqueryAst.conditions().get(0) instanceof Contains);

        JoinCondition joinCondition = joinStep.onCondition();
        assertEquals("t1.DOCUMENT_ID", joinCondition.leftColumn());
        assertEquals("sub.DOCUMENT_ID", joinCondition.rightColumn());
        assertEquals(TemporalPredicate.CONTAINS, joinCondition.temporalPredicate().orElse(null));
    }

    @Test
    @DisplayName("Parse query selecting qualified identifier - Requires FROM alias")
    void parseSelectQualifiedIdentifier() throws QueryParseException {
        String queryStr = "SELECT t1.scientist FROM wikipedia ALIAS t1 WHERE NER(\"PERSON\") BIND scientist";

        // Grammar now supports FROM...ALIAS, so parsing should succeed.
        // Remove assertThrows. Full validation of qualified identifiers is in Stage 3.
        Query query = assertDoesNotThrow(() -> parser.parse(queryStr),
            "Parsing should succeed now that FROM...ALIAS is supported grammatically");

        // Optional: Add basic checks that reflect successful parsing,
        // keeping in mind Stage 3 will handle deeper validation.
        assertEquals("wikipedia", query.source());
        assertTrue(query.mainAlias().isPresent(), "Main alias should be present");
        assertEquals("t1", query.mainAlias().get(), "Main alias should be 't1'");
        assertEquals(1, query.selectColumns().size());
        assertTrue(query.selectColumns().get(0) instanceof com.example.query.model.VariableColumn, "Select column should be a VariableColumn");
        assertEquals("t1.scientist", ((com.example.query.model.VariableColumn) query.selectColumns().get(0)).getColumnName(), "Select column name should be 'scientist'");
        assertEquals(1, query.conditions().size());
        assertTrue(query.conditions().get(0) instanceof Ner);

        // Commented out checks that rely on Stage 3 implementation details
        /*
        assertEquals("t1", query.mainAlias().orElse(null)); // Check alias
        SelectColumn column = query.selectColumns().get(0);
        assertTrue(column instanceof VariableColumn); // Assuming VariableColumn handles qualified names for now
        assertEquals("scientist", ((VariableColumn) column).getColumnName());
        */
    }

    // --- GROUP BY Parsing Tests ---
    @Test
    @DisplayName("Parse query with GROUP BY single column")
    void parseGroupBySingleColumn() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia GROUP BY category";
        Query query = parser.parse(queryStr);
        assertNotNull(query.groupByColumns(), "groupByColumns list should not be null");
        assertEquals(1, query.groupByColumns().size());
        assertEquals("$main.category", query.groupByColumns().get(0));
    }

    @Test
    @DisplayName("Parse query with GROUP BY multiple columns")
    void parseGroupByMultipleColumns() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia GROUP BY category, region";
        Query query = parser.parse(queryStr);
        assertNotNull(query.groupByColumns());
        assertEquals(2, query.groupByColumns().size());
        assertEquals(List.of("$main.category", "$main.region"), query.groupByColumns());
    }

    @Test
    @DisplayName("Parse query with GROUP BY qualified column")
    void parseGroupByQualifiedColumn() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia ALIAS w GROUP BY w.category";
        Query query = parser.parse(queryStr);
        assertNotNull(query.groupByColumns());
        assertEquals(1, query.groupByColumns().size());
        assertEquals("w.category", query.groupByColumns().get(0));
    }

    @Test
    @DisplayName("Parse query with GROUP BY unqualified column in aliased query")
    void parseGroupByUnqualifiedInAliasedQuery() throws QueryParseException {
        String queryStr = "SELECT COUNT(DOCUMENTS) FROM wikipedia ALIAS w GROUP BY category";
        // QueryModelBuilder should throw an error if an unqualified variable is used in GROUP BY with an alias.
        QueryParseException exception = assertThrows(QueryParseException.class, () -> parser.parse(queryStr));
        assertTrue(exception.getMessage().contains("Unqualified variable 'category' used in GROUP BY where qualification is required. Use 'alias.category'."),
                   "Error message should indicate 'category' needs qualification as 'alias.category'. Actual: " + exception.getMessage());
    }

    @Test
    @DisplayName("Parse query with GROUP BY structural column (e.g., DOCUMENT_ID)")
    void parseGroupByStructuralColumn() throws QueryParseException {
        String queryStr = "SELECT COUNT(*) FROM wikipedia GROUP BY DOCUMENT_ID";
        Query query = parser.parse(queryStr);
        assertNotNull(query.groupByColumns());
        assertEquals(1, query.groupByColumns().size());
        assertEquals("$main.DOCUMENT_ID", query.groupByColumns().get(0));
    }

    @Test
    @DisplayName("Parse query with GROUP BY structural column in aliased query")
    void parseGroupByStructuralColumnAliased() throws QueryParseException {
        String queryStr = "SELECT COUNT(*) FROM wikipedia ALIAS w GROUP BY w.DOCUMENT_ID";
        Query query = parser.parse(queryStr);
        assertNotNull(query.groupByColumns());
        assertEquals(1, query.groupByColumns().size());
        assertEquals("w.DOCUMENT_ID", query.groupByColumns().get(0));
    }

    @Test
    @DisplayName("Parse query with GROUP BY unqualified structural column in aliased query")
    void parseGroupByUnqualifiedStructuralColumnAliased() throws QueryParseException {
        String queryStr = "SELECT COUNT(*) FROM wikipedia ALIAS w GROUP BY DOCUMENT_ID";
        // QueryModelBuilder should throw an error for unqualified structural columns as well if an alias is present.
        QueryParseException exception = assertThrows(QueryParseException.class, () -> parser.parse(queryStr));
        assertTrue(exception.getMessage().contains("Unqualified variable 'DOCUMENT_ID' used in GROUP BY where qualification is required. Use 'alias.DOCUMENT_ID'."),
                   "Error message should indicate 'DOCUMENT_ID' needs qualification as 'alias.DOCUMENT_ID'. Actual: " + exception.getMessage());
    }
    // --- END GROUP BY Parsing Tests ---
}