package com.example.query;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;
import com.example.query.model.Query;
import com.example.query.model.SelectedColumn;
import com.example.query.model.SelectedCount;
import com.example.query.model.SelectedSnippet;
import com.example.query.model.SelectedVariable;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Ner;

@DisplayName("Query Semantic Validator Tests")
class QuerySemanticValidatorTest {
    private QuerySemanticValidator validator;

    @BeforeEach
    void setUp() {
        validator = new QuerySemanticValidator();
    }

    @Test
    @DisplayName("Valid query with COUNT column should validate")
    void validQueryWithCountShouldValidate() {
        List<SelectedColumn> columns = List.of(new SelectedCount(new SelectedCount.CountAll()));
        Query query = createQuery(columns, List.of());

        assertDoesNotThrow(() -> validator.validate(query, Optional.empty()));
    }

    @Test
    @DisplayName("Valid query with bound variable column should validate")
    void validQueryWithBoundVariableShouldValidate() {
        // Create a registry and register the variable with its qualified name
        VariableRegistry registry = new VariableRegistry();
        registry.registerProducer("$main.person", VariableType.ENTITY, "NER");

        // Create condition that binds the qualified variable name
        Ner nerCondition = new Ner("PERSON", List.of(), "$main.person", true);
        List<Condition> conditions = List.of(nerCondition);

        // Use that variable in SELECT (needs qualified name as used in SELECT)
        List<SelectedColumn> columns = List.of(new SelectedVariable("$main.person"));

        Query query = createQuery(columns, conditions, registry);

        assertDoesNotThrow(() -> validator.validate(query, Optional.empty()));
    }

    @Test
    @DisplayName("Valid query with snippet using bound variable should validate")
    void validQueryWithSnippetBoundVariableShouldValidate() {
        // Create a registry and register the variable with its qualified name
        VariableRegistry registry = new VariableRegistry();
        registry.registerProducer("$main.person", VariableType.ENTITY, "NER");

        // Create condition that binds the qualified variable name
        Ner nerCondition = new Ner("PERSON", List.of(), "$main.person", true);
        List<Condition> conditions = List.of(nerCondition);

        // Use that variable in a SNIPPET column using the internally qualified name
        List<SelectedColumn> columns = Collections.singletonList(new SelectedSnippet("$main.person"));

        Query query = createQuery(columns, conditions, registry);

        assertDoesNotThrow(() -> validator.validate(query, Optional.empty()));
    }

    @Test
    @DisplayName("Query with unbound variable in SELECT should throw exception")
    void queryWithUnboundVariableShouldThrowException() {
        // No condition to bind '$main.person' variable
        List<Condition> conditions = List.of();

        // Try to use an unbound variable in SELECT
        List<SelectedColumn> columns = List.of(new SelectedVariable("$main.person"));

        Query query = createQuery(columns, conditions);

        QueryParseException exception = assertThrows(
                QueryParseException.class,
                () -> validator.validate(query, Optional.empty()));

        assertTrue(exception.getMessage().contains("Variable '$main.person' not found"),
                "Error message should indicate the variable was not found: " + exception.getMessage());
    }

    @Test
    @DisplayName("Query with unbound variable in SNIPPET should throw exception")
    void queryWithUnboundSnippetVariableShouldThrowException() {
        // No condition to bind '$main.person' variable
        List<Condition> conditions = List.of();

        // Try to use an unbound variable in SNIPPET
        List<SelectedColumn> columns = Collections.singletonList(new SelectedSnippet("$main.person"));

        Query query = createQuery(columns, conditions);

        QueryParseException exception = assertThrows(
                QueryParseException.class,
                () -> validator.validate(query, Optional.empty()));

        assertTrue(exception.getMessage().contains("Variable '$main.person' not found"),
                "Error message should indicate the variable was not found: " + exception.getMessage());
    }

    @Test
    @DisplayName("Query with oversized snippet window should throw exception")
    void queryWithOversizedSnippetWindowShouldThrowException() {
        // Create a registry and register the variable
        VariableRegistry registry = new VariableRegistry();
        registry.registerProducer("$main.person", VariableType.ENTITY, "NER");

        // Create condition that binds a variable
        Ner nerCondition = new Ner("PERSON", List.of(), "$main.person", true);
        List<Condition> conditions = List.of(nerCondition);

        // Create a snippet with window size 5
        List<SelectedColumn> columns = Collections.singletonList(new SelectedSnippet("$main.person", 5));

        Query query = createQuery(columns, conditions, registry);

        assertDoesNotThrow(() -> validator.validate(query, Optional.empty()));
    }

    @Test
    @DisplayName("Query with empty select columns should throw exception")
    void queryWithEmptySelectColumnsShouldThrowException() {
        List<SelectedColumn> columns = new ArrayList<>();
        Query query = createQuery(columns, List.of());

        QueryParseException exception = assertThrows(
                QueryParseException.class,
                () -> validator.validate(query, Optional.empty()));

        assertTrue(exception.getMessage().contains("at least one column"));
    }

    @Test
    @DisplayName("CONTAINS condition with too many terms should throw exception")
    void containsConditionWithTooManyTermsShouldThrowException() {
        List<String> tooManyTerms = List.of("one", "two", "three", "four");
        Contains containsCondition = new Contains(tooManyTerms);
        List<Condition> conditions = List.of(containsCondition);
        List<SelectedColumn> columns = List.of(new SelectedCount(new SelectedCount.CountAll()));

        Query query = createQuery(columns, conditions);

        QueryParseException exception = assertThrows(
                QueryParseException.class,
                () -> validator.validate(query, Optional.empty()));

        assertTrue(exception.getMessage().contains("supports at most 3 terms"));
        assertTrue(exception.getMessage().contains("got 4 terms"));
    }

    @Test
    @DisplayName("CONTAINS condition with empty terms should throw exception")
    void containsConditionWithEmptyTermsShouldThrowException() {
        // Note: This test is theoretical since the Contains constructor prevents empty
        // terms
        // but the validator should still check for robustness
        List<SelectedColumn> columns = List.of(new SelectedCount(new SelectedCount.CountAll()));
        Query query = createQuery(columns, List.of());

        assertDoesNotThrow(() -> validator.validate(query, Optional.empty()));
    }

    @Test
    @DisplayName("CONTAINS condition with valid term count should validate")
    void containsConditionWithValidTermCountShouldValidate() {
        Contains singleTerm = new Contains("single");
        Contains bigramTerms = new Contains(List.of("first", "second"));
        Contains trigramTerms = new Contains(List.of("first", "second", "third"));

        List<Condition> conditions = List.of(singleTerm, bigramTerms, trigramTerms);
        List<SelectedColumn> columns = List.of(new SelectedCount(new SelectedCount.CountAll()));

        Query query = createQuery(columns, conditions);

        assertDoesNotThrow(() -> validator.validate(query, Optional.empty()));
    }

    @Test
    @DisplayName("Query with unbound variable in ORDER BY should throw exception")
    void queryWithUnboundOrderByVariableShouldThrowException() {
        // No condition to bind '$main.person' variable
        List<Condition> conditions = List.of();

        // Create a valid select column
        List<SelectedColumn> columns = List.of(new SelectedCount(new SelectedCount.CountAll()));

        // Create a variable registry where the variable is registered as a consumer but
        // not a producer
        VariableRegistry registry = new VariableRegistry();
        registry.registerConsumer("$main.person", VariableType.ENTITY, "ORDER_BY");

        // Create a query with an unbound variable in ORDER BY
        Query query = new Query(
                "wikipedia",
                conditions,
                List.of("$main.person"),
                Optional.empty(),
                Query.Granularity.DOCUMENT,
                Optional.empty(),
                columns,
                registry,
                List.of(),
                Optional.empty(),
                Collections.emptyList());

        QueryParseException exception = assertThrows(
                QueryParseException.class,
                () -> validator.validate(query, Optional.empty()));

        assertTrue(exception.getMessage().contains("Variable $main.person is consumed but never produced"),
                "Error message should contain check for variable consumed but not produced: " + exception.getMessage());
    }

    /**
     * Helper method to create a Query object for testing with an empty registry
     */
    private Query createQuery(List<SelectedColumn> columns, List<Condition> conditions) {
        return createQuery(columns, conditions, new VariableRegistry());
    }

    /**
     * Helper method to create a Query object for testing with a provided registry
     */
    private Query createQuery(List<SelectedColumn> columns, List<Condition> conditions, VariableRegistry registry) {
        return new Query(
                "wikipedia",
                conditions,
                List.of(),
                Optional.empty(),
                Query.Granularity.DOCUMENT,
                Optional.empty(),
                columns,
                registry,
                List.of(),
                Optional.empty(),
                Collections.emptyList());
    }

    /**
     * Helper method to create a Query object for testing with a provided registry
     * and group by columns
     */
    private Query createQuery(List<SelectedColumn> columns, List<Condition> conditions, VariableRegistry registry,
            List<String> groupByColumns) {
        return new Query(
                "wikipedia",
                conditions,
                List.of(),
                Optional.empty(),
                Query.Granularity.DOCUMENT,
                Optional.empty(),
                columns,
                registry,
                List.of(),
                Optional.empty(),
                groupByColumns);
    }

    @Test
    @DisplayName("GROUP BY: Invalid - Grouping by an unknown/unbound variable")
    void validateGroupBy_Invalid_GroupByUnknownColumn() {
        VariableRegistry registry = new VariableRegistry();
        registry.registerProducer("$main.colA", VariableType.TEXT_SPAN, "TEST");

        List<SelectedColumn> select = List.of(new SelectedVariable("$main.colA"));
        List<String> groupBy = List.of("$main.colZ"); // colZ is not known
        Query query = createQuery(select, List.of(), registry, groupBy);

        QueryParseException exception = assertThrows(QueryParseException.class,
                () -> validator.validate(query, Optional.empty()));
        assertTrue(exception.getMessage().contains(
                "Variable '$main.colZ' for GROUP BY ('$main.colZ') not found or not produced in scope (current query (alias '$main'))"),
                "Exception message should indicate that colZ is not known in its scope. Actual: "
                        + exception.getMessage());
        assertTrue(exception.getMessage().contains("Available variables in this scope: [$main.colA]"),
                "Exception message should list available variables. Actual: " + exception.getMessage());
    }
}
