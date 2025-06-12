package com.example.query.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
        // SnippetNode now allows negative window sizes (e.g., -1 for default propagation).
        // It no longer throws an IllegalArgumentException for this.
        SnippetNode node = new SnippetNode("main.var", -1);
        assertEquals(-1, node.windowSize());

        SnippetNode nodeNegativeFive = new SnippetNode("main.var", -5);
        assertEquals(-5, nodeNegativeFive.windowSize());
    }

    @Test
    public void testTooLargeWindowSize() {
        // SnippetNode no longer has an upper limit validation for windowSize.
        // Validation for a practical maximum is handled by QuerySemanticValidator.
        SnippetNode node = new SnippetNode("main.var", 6);
        assertEquals(6, node.windowSize());

        SnippetNode nodeLarge = new SnippetNode("main.var", 1000);
        assertEquals(1000, nodeLarge.windowSize());
    }

    @Test
    public void testToStringWithDefaultValues() {
        SnippetNode node = new SnippetNode("main.var");
        assertEquals("SNIPPET(main.var)", node.toString());
    }

    @Test
    public void testToStringWithCustomWindowSize() {
        SnippetNode node = new SnippetNode("main.var", 2);
        assertEquals("SNIPPET(main.var, CHAR_WINDOW=2)", node.toString());
    }
}