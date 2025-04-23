package com.example.query.binding;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * Tests for the VariableRegistry class.
 */
@DisplayName("Variable Registry Tests")
public class VariableRegistryTest {

    private VariableRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new VariableRegistry();
    }

    @Test
    @DisplayName("Registering a producer variable")
    void testRegisterProducer() {
        registry.registerProducer("person", VariableType.ENTITY, "NER");
        assertTrue(registry.isProduced("person"));
        assertFalse(registry.getProducers("person").isEmpty());
        assertEquals("person", registry.getProducers("person").iterator().next().getName()); // Expect plain name
        assertEquals(VariableType.ENTITY, registry.getInferredType("person"));
    }

    @Test
    @DisplayName("Registering a consumer variable")
    void testRegisterConsumer() {
        registry.registerConsumer("person", VariableType.ENTITY, "DEPENDENCY");
        assertFalse(registry.isProduced("person"));
        assertFalse(registry.getConsumers("person").isEmpty());
        assertEquals("person", registry.getConsumers("person").iterator().next().getName()); // Expect plain name
        assertEquals(VariableType.ENTITY, registry.getInferredType("person"));
    }

    @Test
    @DisplayName("Getting non-existent variable returns empty set")
    void testGetNonExistent() {
        assertTrue(registry.getProducers("nonexistent").isEmpty());
        assertTrue(registry.getConsumers("nonexistent").isEmpty());
        assertEquals(VariableType.ANY, registry.getInferredType("nonexistent"));
        assertFalse(registry.isProduced("nonexistent"));
    }

    @Test
    @DisplayName("Registering both producer and consumer")
    void testRegisterBothProducerAndConsumer() {
        registry.registerProducer("person", VariableType.ENTITY, "NER");
        registry.registerConsumer("person", VariableType.ENTITY, "DEPENDENCY");

        assertTrue(registry.isProduced("person"));
        assertFalse(registry.getProducers("person").isEmpty());
        assertFalse(registry.getConsumers("person").isEmpty());
        assertEquals(VariableType.ENTITY, registry.getInferredType("person"));
        assertEquals(Set.of("person"), registry.getAllVariableNames()); // Expect plain name
    }

    @Test
    @DisplayName("Inferring type ANY when multiple types are registered")
    void testInferTypeAny() {
        registry.registerProducer("data", VariableType.ENTITY, "NER");
        registry.registerConsumer("data", VariableType.TEMPORAL, "DATE_OP"); // Conflicting type

        assertEquals(VariableType.ANY, registry.getInferredType("data"));
    }
    
    @Test
    @DisplayName("Inferring type with ANY type present")
    void testInferTypeWithAny() {
        registry.registerProducer("data", VariableType.ENTITY, "NER");
        registry.registerConsumer("data", VariableType.ANY, "GENERIC_OP");

        // Should ignore ANY and infer ENTITY
        assertEquals(VariableType.ENTITY, registry.getInferredType("data"));
    }

    @Test
    @DisplayName("Validation should pass when all consumed variables are produced")
    void testValidationPass() {
        registry.registerProducer("person", VariableType.ENTITY, "NER");
        registry.registerConsumer("person", VariableType.ENTITY, "DEPENDENCY");
        registry.registerProducer("org", VariableType.ENTITY, "NER"); // Produced but not consumed

        Set<String> errors = registry.validate();
        assertTrue(errors.isEmpty(), "Validation should pass");
    }

    @Test
    @DisplayName("Validation should fail when a consumed variable is not produced")
    void testValidation() { // Renamed from testValidationFail
        registry.registerConsumer("person", VariableType.ENTITY, "DEPENDENCY"); // Consumed, not produced
        registry.registerProducer("org", VariableType.ENTITY, "NER");

        Set<String> errors = registry.validate();
        assertFalse(errors.isEmpty(), "Validation should fail");
        assertTrue(errors.stream().anyMatch(e -> e.contains("person is consumed but never produced")), // Check plain name
                   "Error message should mention missing 'person' variable");
    }

    @Test
    public void testMultipleProducers() {
        String variableName = "date";
        registry.registerProducer(variableName, VariableType.TEMPORAL, "DATE1");
        registry.registerProducer(variableName, VariableType.TEMPORAL, "DATE2");
        
        // Verify registry state
        assertEquals(2, registry.getProducers(variableName).size());
        
        // Test all producers collection
        Collection<ProducerVariable> allProducers = registry.getAllProducers();
        assertEquals(2, allProducers.size());
    }

    @Test
    public void testInferredType() {
        // Test with single type
        registry.registerProducer("person", VariableType.ENTITY, "NER");
        assertEquals(VariableType.ENTITY, registry.getInferredType("person"));
        
        // Test with same types
        registry.registerConsumer("person", VariableType.ENTITY, "CONTAINS");
        assertEquals(VariableType.ENTITY, registry.getInferredType("person"));
        
        // Test with conflicting types
        registry.registerConsumer("person", VariableType.TEXT_SPAN, "SNIPPET");
        assertEquals(VariableType.ANY, registry.getInferredType("person"));
        
        // Test with ANY type
        registry.registerProducer("unknown", VariableType.ANY, "CUSTOM");
        registry.registerConsumer("unknown", VariableType.ENTITY, "USE");
        assertEquals(VariableType.ENTITY, registry.getInferredType("unknown"));
    }

    @Test
    public void testClear() {
        registry.registerProducer("person", VariableType.ENTITY, "NER");
        registry.registerConsumer("org", VariableType.ENTITY, "CONTAINS");
        
        // Clear and verify
        registry.clear();
        assertTrue(registry.getAllVariableNames().isEmpty());
        assertTrue(registry.getAllProducers().isEmpty());
        assertTrue(registry.getAllConsumers().isEmpty());
    }
} 