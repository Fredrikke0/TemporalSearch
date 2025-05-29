package com.example.query.model;

import org.junit.jupiter.api.Test;

import com.example.query.model.condition.Contains;

import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

@DisplayName("ContainsCondition Tests")
class ContainsConditionTest {

    @Test
    @DisplayName("Constructor should set value correctly")
    void constructorShouldSetValue() {
        String value = "test value";
        Contains condition = new Contains(value);
        assertEquals(List.of(value), condition.terms());
    }

    @Test
    @DisplayName("getType should return CONTAINS")
    void getTypeShouldReturnContains() {
        Contains condition = new Contains("test");
        assertEquals("CONTAINS", condition.getType());
    }

    @Test
    @DisplayName("toString should include value")
    void toStringShouldIncludeValue() {
        String value = "test value";
        Contains condition = new Contains(value);
        String str = condition.toString();
        assertTrue(str.contains(value));
        assertTrue(str.contains("CONTAINS"));
    }

    @Test
    @DisplayName("Constructor should not accept null value")
    void constructorShouldNotAcceptNull() {
        String nullValue = null;
        assertThrows(NullPointerException.class, () -> new Contains(nullValue));
    }

    @Test
    @DisplayName("Variable binding constructor should set fields correctly")
    void variableBindingConstructorShouldSetFieldsCorrectly() {
        String term = "test value";
        String variableName = "match";
        Contains condition = new Contains(term, variableName, true);

        assertEquals(List.of(term), condition.terms());
        assertEquals(variableName, condition.variableName());
        assertTrue(condition.isVariable());
    }

    @Test
    @DisplayName("toString should format with variable correctly (BIND-based style)")
    void toStringShouldFormatWithVariableCorrectly() {
        var condition = new Contains("test value", "match", true);
        assertEquals("CONTAINS(\"test value\") BIND match", condition.toString());
    }

    @Test
    @DisplayName("getProducedVariables should return variable when isVariable is true")
    void getProducedVariablesShouldReturnVariableWhenIsVariableIsTrue() {
        Contains condition = new Contains("keyword", "match", true);
        assertEquals(1, condition.getProducedVariables().size());
        assertTrue(condition.getProducedVariables().contains("match"));
    }

    @Test
    @DisplayName("getProducedVariables should return empty set when isVariable is false")
    void getProducedVariablesShouldReturnEmptySetWhenIsVariableIsFalse() {
        Contains condition = new Contains("keyword");
        assertTrue(condition.getProducedVariables().isEmpty());
    }

    @Test
    public void testToStringWithVariable() {
        Contains condition = new Contains(List.of("test", "value"), "match", true);
        String str = condition.toString();
        assertEquals("CONTAINS(\"test value\") BIND match", str);
    }
}