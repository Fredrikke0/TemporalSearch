package com.example.query.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SnippetNodeTest {

    @Test
    public void testConstructorWithVariableOnly() {
        SnippetNode node = new SnippetNode("main.var");
        assertEquals("main.var", node.variableName());
        assertEquals(SnippetNode.DEFAULT_WINDOW_SIZE, node.windowSize());
    }

    @Test
    public void testConstructorWithVariableAndWindowSize() {
        SnippetNode node = new SnippetNode("main.var", 2);
        assertEquals("main.var", node.variableName());
        assertEquals(2, node.windowSize());
    }

    @Test
    public void testInvalidVariable() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new SnippetNode("var", 1);
        });
        assertTrue(exception.getMessage().contains("qualifiedVariableName must be a valid qualified name"));
    }

    @Test
    public void testNullVariable() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new SnippetNode(null, 1);
        });
        assertTrue(exception.getMessage().contains("qualifiedVariableName must be a valid qualified name"));
    }

    @Test
    public void testEmptyVariable() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new SnippetNode("", 1);
        });
        assertTrue(exception.getMessage().contains("qualifiedVariableName must be a valid qualified name"));
    }

    @Test
    public void testNegativeWindowSize() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new SnippetNode("main.var", -1);
        });
        assertTrue(exception.getMessage().contains("windowSize must be between 0 and 5"));
    }

    @Test
    public void testTooLargeWindowSize() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new SnippetNode("main.var", 6);
        });
        assertTrue(exception.getMessage().contains("windowSize must be between 0 and 5"));
    }

    @Test
    public void testToStringWithDefaultValues() {
        SnippetNode node = new SnippetNode("main.var");
        assertEquals("SNIPPET(main.var)", node.toString());
    }

    @Test
    public void testToStringWithCustomWindowSize() {
        SnippetNode node = new SnippetNode("main.var", 2);
        assertEquals("SNIPPET(main.var, window=2)", node.toString());
    }
} 