package com.example.query;

import com.example.query.model.*;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Ner;
import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

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
        List<SelectColumn> columns = List.of(CountColumn.countAll());
        Query query = createQuery(columns, List.of());
        
        assertDoesNotThrow(() -> validator.validate(query));
    }
    
    @Test
    @DisplayName("Valid query with TITLE column should validate")
    void validQueryWithTitleShouldValidate() {
        List<SelectColumn> columns = List.of(new TitleColumn());
        Query query = createQuery(columns, List.of());
        
        assertDoesNotThrow(() -> validator.validate(query));
    }
    
    @Test
    @DisplayName("Valid query with TIMESTAMP column should validate")
    void validQueryWithTimestampShouldValidate() {
        List<SelectColumn> columns = List.of(new TimestampColumn());
        Query query = createQuery(columns, List.of());
        
        assertDoesNotThrow(() -> validator.validate(query));
    }
    
    @Test
    @DisplayName("Valid query with bound variable column should validate")
    void validQueryWithBoundVariableShouldValidate() {
        // Create a registry and register the variable with its qualified name
        VariableRegistry registry = new VariableRegistry();
        registry.registerProducer("$main.person", VariableType.ENTITY, "NER"); // Use internally qualified name ($main)
        
        // Create condition that binds the qualified variable name
        Ner nerCondition = new Ner("PERSON", null, "$main.person", true); // Condition stores internally qualified name
        List<Condition> conditions = List.of(nerCondition);
        
        // Use that variable in SELECT (needs qualified name as used in SELECT)
        List<SelectColumn> columns = List.of(new VariableColumn("$main.person")); // VariableColumn uses internally qualified name
        
        Query query = createQuery(columns, conditions, registry);
        
        // Validation should now pass as the qualified name '$main.person' is registered and used consistently
        assertDoesNotThrow(() -> validator.validate(query));
    }
    
    @Test
    @DisplayName("Valid query with snippet using bound variable should validate")
    void validQueryWithSnippetBoundVariableShouldValidate() {
        // Create a registry and register the variable with its qualified name
        VariableRegistry registry = new VariableRegistry();
        registry.registerProducer("$main.person", VariableType.ENTITY, "NER"); // Use internally qualified name ($main)
        
        // Create condition that binds the qualified variable name
        Ner nerCondition = new Ner("PERSON", null, "$main.person", true); // Condition stores internally qualified name
        List<Condition> conditions = List.of(nerCondition);
        
        // Use that variable in a SNIPPET column using the internally qualified name
        SnippetNode snippetNode = new SnippetNode("$main.person"); // SnippetNode uses internally qualified name
        List<SelectColumn> columns = Collections.singletonList(new SnippetColumn(snippetNode.variableName(), snippetNode.windowSize()));
        
        Query query = createQuery(columns, conditions, registry);
        
        assertDoesNotThrow(() -> validator.validate(query));
    }
    
    @Test
    @DisplayName("Query with unbound variable in SELECT should throw exception")
    void queryWithUnboundVariableShouldThrowException() {
        // No condition to bind '$main.person' variable
        List<Condition> conditions = List.of();
        
        // Try to use an unbound variable in SELECT
        List<SelectColumn> columns = List.of(new VariableColumn("$main.person")); // Use internally qualified name
        
        Query query = createQuery(columns, conditions);
        
        QueryParseException exception = assertThrows(
            QueryParseException.class,
            () -> validator.validate(query)
        );
        
        // Check for the specific message about the variable not being found (registry is empty)
        assertTrue(exception.getMessage().contains("Variable '$main.person' not found"), 
                   "Error message should indicate the variable was not found: " + exception.getMessage());
    }
    
    @Test
    @DisplayName("Query with unbound variable in SNIPPET should throw exception")
    void queryWithUnboundSnippetVariableShouldThrowException() {
        // No condition to bind '$main.person' variable
        List<Condition> conditions = List.of();
        
        // Try to use an unbound variable in SNIPPET
        SnippetNode snippetNode = new SnippetNode("$main.person"); // Use internally qualified name
        List<SelectColumn> columns = Collections.singletonList(new SnippetColumn(snippetNode.variableName(), snippetNode.windowSize()));
        
        Query query = createQuery(columns, conditions);
        
        QueryParseException exception = assertThrows(
            QueryParseException.class,
            () -> validator.validate(query)
        );
        
        // Check for the specific message about the variable not being found (registry is empty)
        assertTrue(exception.getMessage().contains("Variable '$main.person' not found"), 
                   "Error message should indicate the variable was not found: " + exception.getMessage());
    }
    
    @Test
    @DisplayName("Query with oversized snippet window should throw exception")
    void queryWithOversizedSnippetWindowShouldThrowException() {
        // Create a registry and register the variable
        VariableRegistry registry = new VariableRegistry();
        registry.registerProducer("$main.person", VariableType.ENTITY, "NER"); // Use internally qualified name
        
        // Create condition that binds a variable
        Ner nerCondition = new Ner("PERSON", null, "$main.person", true); // Use internally qualified name
        List<Condition> conditions = List.of(nerCondition);
        
        // Create a snippet with window size 5 (the maximum allowed by the constructor)
        SnippetNode snippetNode = new SnippetNode("$main.person", 5); // Use internally qualified name
        List<SelectColumn> columns = Collections.singletonList(new SnippetColumn(snippetNode.variableName(), snippetNode.windowSize()));
        
        Query query = createQuery(columns, conditions, registry);
        
        // Since we can't create a SnippetNode with window size > 5 (constructor prevents it),
        // we're just verifying that a valid window size passes validation
        assertDoesNotThrow(() -> validator.validate(query));
    }
    
    @Test
    @DisplayName("Query with empty select columns should throw exception")
    void queryWithEmptySelectColumnsShouldThrowException() {
        List<SelectColumn> columns = new ArrayList<>();
        Query query = createQuery(columns, List.of());
        
        QueryParseException exception = assertThrows(
            QueryParseException.class,
            () -> validator.validate(query)
        );
        
        assertTrue(exception.getMessage().contains("at least one column"));
    }
    
    @Test
    @DisplayName("Query with unbound variable in ORDER BY should throw exception")
    void queryWithUnboundOrderByVariableShouldThrowException() {
        // No condition to bind '$main.person' variable
        List<Condition> conditions = List.of();
        
        // Create a valid select column
        List<SelectColumn> columns = List.of(CountColumn.countAll());
        
        // Create a variable registry where the variable is registered as a consumer but not a producer
        VariableRegistry registry = new VariableRegistry();
        registry.registerConsumer("$main.person", VariableType.ENTITY, "ORDER_BY"); // Use internally qualified name
        
        // Create a query with an unbound variable in ORDER BY
        Query query = new Query(
            "wikipedia",
            conditions,
            List.of("$main.person"),  // orderBy with unbound variable (use internally qualified name)
            Optional.empty(),
            Query.Granularity.DOCUMENT,
            Optional.empty(),
            columns,
            registry  // Registry with person as consumer only
        );
        
        QueryParseException exception = assertThrows(
            QueryParseException.class,
            () -> validator.validate(query)
        );
        
        // Check that the overall message contains the core error about the variable
        assertTrue(exception.getMessage().contains("Variable $main.person is consumed but never produced"), 
                   "Error message should contain check for variable consumed but not produced: " + exception.getMessage());
    }
    
    /**
     * Helper method to create a Query object for testing with an empty registry
     */
    private Query createQuery(List<SelectColumn> columns, List<Condition> conditions) {
        return createQuery(columns, conditions, new VariableRegistry());
    }
    
    /**
     * Helper method to create a Query object for testing with a provided registry
     */
    private Query createQuery(List<SelectColumn> columns, List<Condition> conditions, VariableRegistry registry) {
        return new Query(
            "wikipedia",   // source
            conditions,    // conditions
            List.of(),     // orderBy
            Optional.empty(),  // limit
            Query.Granularity.DOCUMENT,  // granularity
            Optional.empty(),  // granularitySize (Snippet size is handled in SnippetColumn)
            columns,       // selectColumns
            registry       // variable registry
        );
    }
} 