package com.example.query.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Ner;

/**
 * Tests for the Query class, focusing on variable binding functionality.
 */
public class QueryTest {
    private static final Logger logger = LoggerFactory.getLogger(QueryTest.class);

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
        registry.registerProducer("$main.person", VariableType.ENTITY, "NER"); // Use internally qualified name

        // Verify registration via registry
        assertTrue(registry.isProduced("$main.person"));
        assertEquals(VariableType.ENTITY, registry.getInferredType("$main.person"));
        assertEquals(Set.of("$main.person"), registry.getAllVariableNames()); // Expect internally qualified name
    }

    @Test
    public void testRegisterConsumer() {
        // Register a consumer variable directly on the registry
        registry.registerConsumer("$main.person", VariableType.ENTITY, "CONTAINS"); // Use internally qualified name

        // Verify registration via registry
        assertFalse(registry.isProduced("$main.person")); // Not produced yet
        assertEquals(VariableType.ENTITY, registry.getInferredType("$main.person"));
        assertEquals(Set.of("$main.person"), registry.getAllVariableNames()); // Expect internally qualified name
        assertTrue(registry.validate().stream().anyMatch(e -> e.contains("$main.person is consumed")), "Validation should fail"); // Expect internally qualified name
    }

    @Test
    public void testRegisterBoth() {
        // Register both producer and consumer directly on the registry
        registry.registerProducer("$main.person", VariableType.ENTITY, "NER"); // Use internally qualified name
        registry.registerConsumer("$main.person", VariableType.ENTITY, "CONTAINS"); // Use internally qualified name

        // Verify registration via registry
        assertTrue(registry.isProduced("$main.person"));
        assertEquals(VariableType.ENTITY, registry.getInferredType("$main.person"));
        assertEquals(Set.of("$main.person"), registry.getAllVariableNames()); // Expect internally qualified name
        assertTrue(registry.validate().isEmpty(), "Validation should pass"); // Now it should pass
    }

    @Test
    public void testVariableValidation() {
        // Register just a consumer directly on the registry
        registry.registerConsumer("$main.person", VariableType.ENTITY, "CONTAINS"); // Use internally qualified name

        // Validation happens on the registry
        Set<String> errors = registry.validate();
        assertFalse(errors.isEmpty());
        assertTrue(errors.iterator().next().contains("$main.person is consumed but never produced")); // Check internally qualified name

        // Fix by registering a producer
        registry.registerProducer("$main.person", VariableType.ENTITY, "NER"); // Use internally qualified name
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
        registry.registerProducer("$main.person", VariableType.ENTITY, "NER"); // Use internally qualified name
        registry.registerProducer("$main.org", VariableType.ENTITY, "NER");   // Use internally qualified name
        registry.registerProducer("$main.date", VariableType.TEMPORAL, "DATE"); // Use internally qualified name

        // Verify via registry
        assertEquals(3, registry.getAllVariableNames().size());
        assertTrue(registry.getAllVariableNames().contains("$main.person")); // Expect internally qualified name
        assertTrue(registry.getAllVariableNames().contains("$main.org"));   // Expect internally qualified name
        assertTrue(registry.getAllVariableNames().contains("$main.date"));   // Expect internally qualified name
    }

    @Test
    public void testNerConditionWithVariableBinding() {
        // Create a NER condition that produces a variable
        // Condition stores the internally qualified name
        Ner nerCondition = new Ner("PERSON", null, "$main.person", true);

        // Create a query with this condition and a fresh registry
        VariableRegistry localRegistry = new VariableRegistry();
        // Manually register variables from the condition onto the registry
        nerCondition.registerVariables(localRegistry);

        // Create query with the now populated registry
        Query queryWithNer = new Query("wikipedia", List.of(nerCondition), new ArrayList<>(),
                                       Optional.empty(), Query.Granularity.DOCUMENT, Optional.empty(),
                                       new ArrayList<>(), localRegistry);

        // Check variable registration on the registry using QUALIFIED name
        assertTrue(localRegistry.isProduced("$main.person"));
        assertEquals(VariableType.ENTITY, localRegistry.getInferredType("$main.person"));

        // Check that the condition properly appears in toString (using the condition's method)
        String conditionString = nerCondition.toString();
        assertEquals("NER(PERSON) BIND $main.person", conditionString); // Expect internally qualified name in toString
    }

    @Test
    public void testContainsConditionWithVariableBinding() {
        // Create a Contains condition that produces a variable
        // Condition stores the internally qualified name
        Contains containsCondition = new Contains(List.of("search", "term"), "$main.result", true);

        // Create a query with this condition and a fresh registry
        VariableRegistry localRegistry = new VariableRegistry();
        // Manually register variables from the condition
        containsCondition.registerVariables(localRegistry);

        // Create query with the now populated registry
        Query queryWithContains = new Query("news_corpus", List.of(containsCondition), new ArrayList<>(),
                                          Optional.empty(), Query.Granularity.DOCUMENT, Optional.empty(),
                                          new ArrayList<>(), localRegistry);

        // Check variable registration on the registry using QUALIFIED name
        assertTrue(localRegistry.isProduced("$main.result"));
        assertEquals(VariableType.TEXT_SPAN, localRegistry.getInferredType("$main.result"));

        // Check that the condition properly appears in toString (using the condition's method)
        String conditionString = containsCondition.toString();
        assertEquals("CONTAINS(\"search term\") BIND $main.result", conditionString); // Expect internally qualified name in toString
    }

    @Test
    public void testComplexQueryWithMultipleConditions() {
        // Create several conditions with internally qualified names
        Ner personCondition = new Ner("PERSON", null, "$main.person", true);
        Ner orgCondition = new Ner("ORGANIZATION", null, "$main.org", true);
        Contains containsCondition = new Contains(List.of("meeting"), "$main.text", true);

        List<Condition> conditions = List.of(personCondition, orgCondition, containsCondition);

        // Create query with fresh registry
        VariableRegistry localRegistry = new VariableRegistry();
        // Register variables from conditions onto the local registry
        for (Condition condition : conditions) {
            condition.registerVariables(localRegistry);
        }

        // Check registry directly using QUALIFIED names
        Set<String> variables = localRegistry.getAllVariableNames();
        assertEquals(3, variables.size());
        assertTrue(variables.contains("$main.person")); // Expect internally qualified name
        assertTrue(variables.contains("$main.org")); // Expect internally qualified name
        assertTrue(variables.contains("$main.text")); // Expect internally qualified name

        // Check variable types via registry using QUALIFIED names
        assertEquals(VariableType.ENTITY, localRegistry.getInferredType("$main.person"));
        assertEquals(VariableType.ENTITY, localRegistry.getInferredType("$main.org"));
        assertEquals(VariableType.TEXT_SPAN, localRegistry.getInferredType("$main.text"));

        // Validate variables on the registry
        assertTrue(localRegistry.validate().isEmpty());

        // Verify condition toStrings
        assertEquals("NER(PERSON) BIND $main.person", personCondition.toString()); // Expect internally qualified name
        assertEquals("NER(ORGANIZATION) BIND $main.org", orgCondition.toString()); // Expect internally qualified name
        assertEquals("CONTAINS(\"meeting\") BIND $main.text", containsCondition.toString()); // Expect internally qualified name
    }

    // Keep these toString tests, but they assert on the Query object's representation
    @Test
    @DisplayName("toString() should include NER condition with variable")
    void testToStringWithNerVariable() {
        List<Condition> conditions = List.of(new Ner("PERSON", null, "$main.person", true)); // Internally qualified
        VariableRegistry registry = new VariableRegistry(); // Create registry
        conditions.get(0).registerVariables(registry); // Register variable
        Query query = new Query("documents", conditions, List.of(), Optional.empty(),
                              Query.Granularity.DOCUMENT, Optional.empty(),
                              List.of(new VariableColumn("$main.person")), registry); // Use registry
        String queryString = query.toString();
        assertTrue(queryString.contains("NER(PERSON) BIND $main.person")); // Expect internally qualified name
    }

    @Test
    @DisplayName("toString() should include CONTAINS condition with variable")
    void testToStringWithContainsVariable() {
        List<Condition> conditions = List.of(new Contains(List.of("search", "term"), "$main.result", true)); // Internally qualified
        VariableRegistry registry = new VariableRegistry(); // Create registry
        conditions.get(0).registerVariables(registry); // Register variable
        Query query = new Query("documents", conditions, List.of(), Optional.empty(),
                              Query.Granularity.DOCUMENT, Optional.empty(),
                              List.of(new VariableColumn("$main.result")), registry); // Use registry
        String queryString = query.toString();
        assertTrue(queryString.contains("CONTAINS(\"search term\") BIND $main.result")); // Expect internally qualified name
    }

    @Test
    @DisplayName("toString() should include multiple conditions with variables")
    void testToStringWithMultipleVariables() {
        Condition nerPerson = new Ner("PERSON", null, "$main.person", true); // Internally qualified
        Condition nerOrg = new Ner("ORGANIZATION", null, "$main.org", true);   // Internally qualified
        Condition containsText = new Contains(List.of("meeting"), "$main.text", true); // Internally qualified
        List<Condition> conditions = List.of(nerPerson, nerOrg, containsText);
        List<SelectColumn> selectCols = List.of(new VariableColumn("$main.person"), new VariableColumn("$main.org")); // Internally qualified
        VariableRegistry registry = new VariableRegistry(); // Create registry
        for(Condition c : conditions) { c.registerVariables(registry); } // Register variables
        Query query = new Query("logs", conditions, List.of("$main.person"), Optional.of(10), // Internally qualified ORDER BY
                              Query.Granularity.SENTENCE, Optional.of(2), selectCols, registry); // Use registry

        String queryString = query.toString();
        logger.debug("Generated Query String for testToStringWithMultipleVariables:\n{}", queryString);

        assertTrue(queryString.contains("NER(PERSON) BIND $main.person"), "Check 1 failed: NER(PERSON)"); // Expect internally qualified name
        assertTrue(queryString.contains("NER(ORGANIZATION) BIND $main.org"), "Check 2 failed: NER(ORGANIZATION)"); // Expect internally qualified name
        assertTrue(queryString.contains("CONTAINS(\"meeting\") BIND $main.text"), "Check 3 failed: CONTAINS"); // Expect internally qualified name
        assertTrue(queryString.contains("SELECT $main.person, $main.org"), "Check 4 failed: SELECT");
        assertTrue(queryString.contains("ORDER BY $main.person"), "Check 5 failed: ORDER BY");
        assertTrue(queryString.contains("LIMIT 10"), "Check 6 failed: LIMIT");
        assertTrue(queryString.contains("GRANULARITY SENTENCE 2"), "Check 7 failed: GRANULARITY");
    }
}