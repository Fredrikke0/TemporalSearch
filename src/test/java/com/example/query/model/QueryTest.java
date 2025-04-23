package com.example.query.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Ner;

/**
 * Tests for the Query class, focusing on variable binding functionality.
 */
public class QueryTest {

    private Query query;
    private VariableRegistry registry; // Use a separate registry for clarity

    @BeforeEach
    public void setUp() {
        registry = new VariableRegistry(); // Initialize registry here
        // Pass the registry to the Query constructor
        query = new Query("test_source", new ArrayList<>(), new ArrayList<>(), Optional.empty(), 
                        Query.Granularity.DOCUMENT, Optional.empty(), new ArrayList<>(), registry);
    }

    @Test
    public void testInitialState() {
        // A fresh query should have an empty variable registry
        assertTrue(registry.getAllVariableNames().isEmpty()); // Check registry directly
        
        // Default parameters should be set correctly
        assertEquals("test_source", query.source());
        assertTrue(query.conditions().isEmpty());
        assertTrue(query.orderBy().isEmpty());
        assertEquals(Optional.empty(), query.limit());
        assertEquals(Query.Granularity.DOCUMENT, query.granularity());
        assertEquals(Optional.empty(), query.granularitySize());
        assertTrue(query.selectColumns().isEmpty());
        assertNotNull(query.variableRegistry());
    }

    @Test
    public void testRegisterProducer() {
        // Register a producer variable directly on the registry
        registry.registerProducer("person", VariableType.ENTITY, "NER");
        
        // Verify registration via registry
        assertTrue(registry.isProduced("person"));
        assertEquals(VariableType.ENTITY, registry.getInferredType("person"));
        assertEquals(Set.of("person"), registry.getAllVariableNames()); // Expect plain name
    }

    @Test
    public void testRegisterConsumer() {
        // Register a consumer variable directly on the registry
        registry.registerConsumer("person", VariableType.ENTITY, "CONTAINS");
        
        // Verify registration via registry
        assertFalse(registry.isProduced("person")); // Not produced yet
        assertEquals(VariableType.ENTITY, registry.getInferredType("person"));
        assertEquals(Set.of("person"), registry.getAllVariableNames()); // Expect plain name
        assertTrue(registry.validate().stream().anyMatch(e -> e.contains("person is consumed")), "Validation should fail");
    }

    @Test
    public void testRegisterBoth() {
        // Register both producer and consumer directly on the registry
        registry.registerProducer("person", VariableType.ENTITY, "NER");
        registry.registerConsumer("person", VariableType.ENTITY, "CONTAINS");
        
        // Verify registration via registry
        assertTrue(registry.isProduced("person"));
        assertEquals(VariableType.ENTITY, registry.getInferredType("person"));
        assertEquals(Set.of("person"), registry.getAllVariableNames()); // Expect plain name
        assertTrue(registry.validate().isEmpty(), "Validation should pass"); // Now it should pass
    }

    @Test
    public void testVariableValidation() {
        // Register just a consumer directly on the registry
        registry.registerConsumer("person", VariableType.ENTITY, "CONTAINS");
        
        // Validation happens on the registry
        Set<String> errors = registry.validate();
        assertFalse(errors.isEmpty());
        assertTrue(errors.iterator().next().contains("person is consumed but never produced")); // Check plain name
        
        // Fix by registering a producer
        registry.registerProducer("person", VariableType.ENTITY, "NER");
        errors = registry.validate();
        assertTrue(errors.isEmpty());
    }

    @Test
    public void testVariableTypeInference() {
        // Register directly on the registry
        registry.registerProducer("mixed", VariableType.TEXT_SPAN, "SPAN");
        registry.registerConsumer("mixed", VariableType.ANY, "USE");
        
        assertEquals(VariableType.TEXT_SPAN, registry.getInferredType("mixed"));
        
        registry.registerConsumer("mixed", VariableType.ENTITY, "OTHER");
        assertEquals(VariableType.ANY, registry.getInferredType("mixed"));
    }

    @Test
    public void testMultipleVariables() {
        // Register directly on the registry
        registry.registerProducer("person", VariableType.ENTITY, "NER");
        registry.registerProducer("org", VariableType.ENTITY, "NER");
        registry.registerProducer("date", VariableType.TEMPORAL, "DATE");
        
        // Verify via registry
        assertEquals(3, registry.getAllVariableNames().size());
        assertTrue(registry.getAllVariableNames().contains("person")); // Expect plain name
        assertTrue(registry.getAllVariableNames().contains("org")); // Expect plain name
        assertTrue(registry.getAllVariableNames().contains("date")); // Expect plain name
    }

    @Test
    public void testNerConditionWithVariableBinding() {
        // Create a NER condition that produces a variable
        // Note: Ner.withVariable is likely removed or changed, create directly
        Ner nerCondition = new Ner("PERSON", null, "person", true); 
        
        // Create a query with this condition and a fresh registry
        VariableRegistry localRegistry = new VariableRegistry();
        Query queryWithNer = new Query("wikipedia", List.of(nerCondition), new ArrayList<>(), 
                                       Optional.empty(), Query.Granularity.DOCUMENT, Optional.empty(),
                                       new ArrayList<>(), localRegistry);
        
        // Manually register variables from the condition
        nerCondition.registerVariables(localRegistry); // Register on the fresh registry
        
        // Check variable registration on the registry
        assertTrue(localRegistry.isProduced("person"));
        assertEquals(VariableType.ENTITY, localRegistry.getInferredType("person"));
        
        // Check that the condition properly appears in toString (using the condition's method)
        String conditionString = nerCondition.toString();
        assertEquals("NER(PERSON) AS person", conditionString); // Expect plain name
    }

    @Test
    public void testContainsConditionWithVariableBinding() {
        // Create a Contains condition that produces a variable
        Contains containsCondition = new Contains(List.of("search", "term"), "result", true);
        
        // Create a query with this condition and a fresh registry
        VariableRegistry localRegistry = new VariableRegistry();
        Query queryWithContains = new Query("news_corpus", List.of(containsCondition), new ArrayList<>(), 
                                          Optional.empty(), Query.Granularity.DOCUMENT, Optional.empty(),
                                          new ArrayList<>(), localRegistry);
        
        // Manually register variables from the condition
        containsCondition.registerVariables(localRegistry);
        
        // Check variable registration on the registry
        assertTrue(localRegistry.isProduced("result"));
        assertEquals(VariableType.TEXT_SPAN, localRegistry.getInferredType("result"));
        
        // Check that the condition properly appears in toString (using the condition's method)
        String conditionString = containsCondition.toString();
        assertEquals("CONTAINS(\"search term\") AS result", conditionString); // Expect plain name
    }

    @Test
    public void testComplexQueryWithMultipleConditions() {
        // Create several conditions
        // Create directly, assuming Ner.withVariable is gone
        Ner personCondition = new Ner("PERSON", null, "person", true);
        Ner orgCondition = new Ner("ORGANIZATION", null, "org", true);
        Contains containsCondition = new Contains(List.of("meeting"), "text", true);
        
        List<Condition> conditions = List.of(personCondition, orgCondition, containsCondition);
        
        // Create query with fresh registry
        VariableRegistry localRegistry = new VariableRegistry();
        Query complexQuery = new Query("news_corpus", conditions, new ArrayList<>(), Optional.empty(), 
                                       Query.Granularity.DOCUMENT, Optional.empty(), new ArrayList<>(), 
                                       localRegistry);
        
        // Register variables from conditions onto the local registry
        for (Condition condition : conditions) {
            condition.registerVariables(localRegistry);
        }
        
        // Check registry directly
        Set<String> variables = localRegistry.getAllVariableNames();
        assertEquals(3, variables.size());
        assertTrue(variables.contains("person")); // Expect plain name
        assertTrue(variables.contains("org")); // Expect plain name
        assertTrue(variables.contains("text")); // Expect plain name
        
        // Check variable types via registry
        assertEquals(VariableType.ENTITY, localRegistry.getInferredType("person"));
        assertEquals(VariableType.ENTITY, localRegistry.getInferredType("org"));
        assertEquals(VariableType.TEXT_SPAN, localRegistry.getInferredType("text"));
        
        // Validate variables on the registry
        assertTrue(localRegistry.validate().isEmpty());
        
        // Verify query string representation (this test might be less relevant now)
        // String queryString = complexQuery.toString(); // Query.toString doesn't show registry state
        // Check individual condition toStrings if needed
        assertEquals("NER(PERSON) AS person", personCondition.toString());
        assertEquals("NER(ORGANIZATION) AS org", orgCondition.toString());
        assertEquals("CONTAINS(\"meeting\") AS text", containsCondition.toString());
    }

    // Keep these toString tests, but they assert on the Query object's representation
    @Test
    @DisplayName("toString() should include NER condition with variable")
    void testToStringWithNerVariable() {
        List<Condition> conditions = List.of(new Ner("PERSON", null, "person", true));
        Query query = new Query("documents", conditions, List.of(), Optional.empty(), 
                              Query.Granularity.DOCUMENT, Optional.empty(), 
                              List.of(new VariableColumn("person")), new VariableRegistry());
        String queryString = query.toString();
        assertTrue(queryString.contains("NER(PERSON) AS person")); // Expect plain name
    }
    
    @Test
    @DisplayName("toString() should include CONTAINS condition with variable")
    void testToStringWithContainsVariable() {
        List<Condition> conditions = List.of(new Contains(List.of("search", "term"), "result", true));
        Query query = new Query("documents", conditions, List.of(), Optional.empty(), 
                              Query.Granularity.DOCUMENT, Optional.empty(), 
                              List.of(new VariableColumn("result")), new VariableRegistry());
        String queryString = query.toString();
        assertTrue(queryString.contains("CONTAINS(\"search term\") AS result")); // Expect plain name
    }
    
    @Test
    @DisplayName("toString() should include multiple conditions with variables")
    void testToStringWithMultipleVariables() {
        Condition nerPerson = new Ner("PERSON", null, "person", true);
        Condition nerOrg = new Ner("ORGANIZATION", null, "org", true);
        Condition containsText = new Contains(List.of("meeting"), "text", true);
        List<Condition> conditions = List.of(nerPerson, nerOrg, containsText);
        List<SelectColumn> selectCols = List.of(new VariableColumn("person"), new VariableColumn("org"));
        Query query = new Query("logs", conditions, List.of("person"), Optional.of(10), 
                              Query.Granularity.SENTENCE, Optional.of(2), selectCols, new VariableRegistry());
                              
        String queryString = query.toString();
        System.out.println("Generated Query String for testToStringWithMultipleVariables:\n" + queryString);
        
        assertTrue(queryString.contains("NER(PERSON) AS person"), "Check 1 failed: NER(PERSON)"); // Expect plain name
        assertTrue(queryString.contains("NER(ORGANIZATION) AS org"), "Check 2 failed: NER(ORGANIZATION)"); // Expect plain name
        assertTrue(queryString.contains("CONTAINS(\"meeting\") AS text"), "Check 3 failed: CONTAINS"); // Expect plain name
        assertTrue(queryString.contains("SELECT person, org"), "Check 4 failed: SELECT");
        assertTrue(queryString.contains("ORDER BY person"), "Check 5 failed: ORDER BY");
        assertTrue(queryString.contains("LIMIT 10"), "Check 6 failed: LIMIT");
        assertTrue(queryString.contains("GRANULARITY SENTENCE 2"), "Check 7 failed: GRANULARITY");
    }
} 