package com.example.query.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.query.model.condition.Ner;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NerCondition Tests")
class NerConditionTest {

    @Test
    @DisplayName("Constructor should set fields correctly")
    void constructorShouldSetFields() {
        String entityType = "PERSON";
        String variableName = "scientist";

        Ner condition = new Ner(entityType, null, variableName, true);

        assertEquals(entityType, condition.entityType());
        assertEquals(variableName, condition.variableName());
        assertTrue(condition.isVariable());
    }

    @Test
    @DisplayName("getType should return NER")
    void getTypeShouldReturnNer() {
        Ner condition = new Ner("PERSON");
        assertEquals("NER", condition.getType());
    }

    @Test
    @DisplayName("toString should include all fields")
    void toStringShouldIncludeAllFields() {
        String entityType = "ORGANIZATION";
        String target = "Google";

        Ner condition = new Ner(entityType, target);
        String str = condition.toString();

        assertTrue(str.contains(entityType));
        assertTrue(str.contains(target));
        assertTrue(str.contains("NER"));
    }

    @Test
    @DisplayName("Constructor should not accept null type")
    void constructorShouldNotAcceptNullType() {
        assertThrows(NullPointerException.class, 
            () -> new Ner(null, "varName", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "PERSON", "ORGANIZATION", "LOCATION", "DATE", "TIME",
        "DURATION", "MONEY", "NUMBER", "ORDINAL", "PERCENT", "SET"
    })
    @DisplayName("Constructor should accept all supported NER types")
    void constructorShouldAcceptSupportedNerTypes(String type) {
        Ner condition = new Ner(type);
        assertEquals(type, condition.entityType());
    }

    @Test
    @DisplayName("Basic constructor should create condition without variable")
    void basicConstructorShouldCreateConditionWithoutVariable() {
        Ner condition = new Ner("PERSON");
        assertEquals("PERSON", condition.entityType());
        assertNull(condition.variableName());
        assertFalse(condition.isVariable());
    }
    
    @Test
    @DisplayName("Constructor with target should set target correctly")
    void constructorWithTargetShouldSetTargetCorrectly() {
        Ner condition = new Ner("PERSON", "John Doe");
        assertEquals("PERSON", condition.entityType());
        assertEquals("John Doe", condition.target());
        assertNull(condition.variableName());
        assertFalse(condition.isVariable());
    }

    @Test
    @DisplayName("Constructor with variable should create condition with variable")
    void constructorWithVariableShouldCreateConditionWithVariable() {
        Ner condition = new Ner("PERSON", null, "scientist", true);
        assertEquals("PERSON", condition.entityType());
        assertEquals("scientist", condition.variableName());
        assertTrue(condition.isVariable());
    }

    @Test
    @DisplayName("toString should format without variable correctly")
    void toStringShouldFormatWithoutVariableCorrectly() {
        Ner condition = new Ner("PERSON");
        assertEquals("NER(PERSON)", condition.toString());
    }

    @Test
    @DisplayName("toString should format with target correctly")
    void toStringShouldFormatWithTargetCorrectly() {
        Ner condition = new Ner("PERSON", "John Doe");
        assertEquals("NER(PERSON, John Doe)", condition.toString());
    }
    
    @Test
    @DisplayName("toString should format with variable correctly (BIND-based style)")
    void testToStringWithVariable() {
        Ner condition = new Ner("PERSON", null, "scientist", true);
        assertEquals("NER(PERSON) BIND scientist", condition.toString());
    }
    
    @Test
    @DisplayName("getProducedVariables should return variable when isVariable is true")
    void getProducedVariablesShouldReturnVariableWhenIsVariableIsTrue() {
        Ner condition = new Ner("PERSON", null, "scientist", true);
        assertEquals(1, condition.getProducedVariables().size());
        assertTrue(condition.getProducedVariables().contains("scientist"));
    }
    
    @Test
    @DisplayName("getProducedVariables should return empty set when isVariable is false")
    void getProducedVariablesShouldReturnEmptySetWhenIsVariableIsFalse() {
        Ner condition = new Ner("PERSON");
        assertTrue(condition.getProducedVariables().isEmpty());
    }
} 