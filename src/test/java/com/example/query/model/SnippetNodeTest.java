package com.example.query.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SnippetNodeTest {

    @Test
    public void testConstructorWithVariableOnly() {
        SnippetNode node = new SnippetNode("main.var");
        assertEquals("main.var", node.variableName());
        assertEquals(SnippetNode.DEFAULT_WINDOW_SIZE, node.windowSize());
        assertEquals(SnippetNode.DEFAULT_HIGHLIGHT_STYLE, node.highlightStyle());
        assertEquals(SnippetNode.DEFAULT_SHOW_SENTENCE_BOUNDARIES, node.showSentenceBoundaries());
    }

    @Test
    public void testConstructorWithVariableAndWindowSize() {
        SnippetNode node = new SnippetNode("main.var", 2);
        assertEquals("main.var", node.variableName());
        assertEquals(2, node.windowSize());
        assertEquals(SnippetNode.DEFAULT_HIGHLIGHT_STYLE, node.highlightStyle());
        assertEquals(SnippetNode.DEFAULT_SHOW_SENTENCE_BOUNDARIES, node.showSentenceBoundaries());
    }

    @Test
    public void testConstructorWithAllParameters() {
        SnippetNode node = new SnippetNode("main.var", 3, "__", true);
        assertEquals("main.var", node.variableName());
        assertEquals(3, node.windowSize());
        assertEquals("__", node.highlightStyle());
        assertTrue(node.showSentenceBoundaries());
    }

    @Test
    public void testNullVariable() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new SnippetNode(null, 1, "**", false);
        });
        assertTrue(exception.getMessage().contains("qualifiedVariableName must be a valid qualified name"));
    }

    @Test
    public void testEmptyVariable() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new SnippetNode("", 1, "**", false);
        });
        assertTrue(exception.getMessage().contains("qualifiedVariableName must be a valid qualified name"));
    }

    @Test
    public void testNegativeWindowSize() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new SnippetNode("main.var", -1, "**", false);
        });
        assertTrue(exception.getMessage().contains("windowSize must be between 0 and 5"));
    }

    @Test
    public void testTooLargeWindowSize() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new SnippetNode("main.var", 6, "**", false);
        });
        assertTrue(exception.getMessage().contains("windowSize must be between 0 and 5"));
    }

    @Test
    public void testNullHighlightStyle() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new SnippetNode("main.var", 1, null, false);
        });
        assertTrue(exception.getMessage().contains("highlightStyle must not be null or empty"));
    }

    @Test
    public void testEmptyHighlightStyle() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new SnippetNode("main.var", 1, "", false);
        });
        assertTrue(exception.getMessage().contains("highlightStyle must not be null or empty"));
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

    @Test
    public void testToStringWithAllCustomValues() {
        SnippetNode node = new SnippetNode("main.var", 3, "__", true);
        assertEquals("SNIPPET(main.var, window=3, style=__, boundaries=true)", node.toString());
    }
} 