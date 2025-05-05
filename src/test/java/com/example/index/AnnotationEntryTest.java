package com.example.index;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;

/**
 * Tests for the AnnotationEntry class.
 */
class AnnotationEntryTest extends BaseIndexTest {

    @Test
    void testBasicConstructionAndGetters() throws Exception {
        // Insert test document
        LocalDate timestamp = LocalDate.now();
        TestData.insertDocument(sqliteConn, 1, timestamp);

        // Insert test annotations (assuming TestData.insertBasicAnnotations uses token)
        TestData.insertBasicAnnotations(sqliteConn);

        // Create and verify an annotation entry directly
        int annotationId = 101;
        String token = "cat";
        String pos = "NOUN";
        AnnotationEntry entry = new AnnotationEntry(annotationId, 1, 1, 0, 3, token, pos, timestamp);
        
        assertEquals(annotationId, entry.getAnnotationId());
        assertEquals(1, entry.getDocumentId());
        assertEquals(1, entry.getSentenceId());
        assertEquals(0, entry.getBeginChar());
        assertEquals(3, entry.getEndChar());
        assertEquals(token, entry.getToken()); // Check token
        assertEquals(pos, entry.getPos());
        assertEquals(timestamp, entry.getTimestamp());
    }

    @Test
    void testNullHandling() {
        // Test with null token and POS
        AnnotationEntry entry = new AnnotationEntry(1, 1, 1, 0, 3, null, null, LocalDate.now());
        assertNull(entry.getToken()); // Check token
        assertNull(entry.getPos());
    }

    @Test
    void testEmptyStrings() {
        // Test with empty strings
        AnnotationEntry entry = new AnnotationEntry(1, 1, 1, 0, 3, "", "", LocalDate.now());
        assertEquals("", entry.getToken()); // Check token
        assertEquals("", entry.getPos());
    }

    @Test
    void testTimestampHandling() {
        LocalDate timestamp = LocalDate.of(2024, 1, 20);
        AnnotationEntry entry = new AnnotationEntry(1, 1, 1, 0, 3, "test", "NOUN", timestamp);
        assertEquals(timestamp, entry.getTimestamp());
    }
    
    @Test
    void testAnnotationId() {
        // Test annotation ID getter
        int annotationId = 42;
        AnnotationEntry entry = new AnnotationEntry(annotationId, 1, 1, 0, 3, "test", "NOUN", LocalDate.now());
        assertEquals(annotationId, entry.getAnnotationId());
    }
} 