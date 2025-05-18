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
        AnnotationEntry entry = new AnnotationEntry(annotationId, 1, 1, 0, 3, token, pos);
        
        assertEquals(annotationId, entry.getAnnotationId());
        assertEquals(1, entry.getDocumentId());
        assertEquals(1, entry.getSentenceId());
        assertEquals(0, entry.getBeginChar());
        assertEquals(3, entry.getEndChar());
        assertEquals(token, entry.getToken()); // Check token
        assertEquals(pos, entry.getPos());
    }

    @Test
    void testNullHandling() {
        // Test with null token and POS
        AnnotationEntry entry = new AnnotationEntry(1, 1, 1, 0, 3, null, null);
        assertNull(entry.getToken()); // Check token
        assertNull(entry.getPos());
    }

    @Test
    void testEmptyStrings() {
        // Test with empty strings
        AnnotationEntry entry = new AnnotationEntry(1, 1, 1, 0, 3, "", "");
        assertEquals("", entry.getToken()); // Check token
        assertEquals("", entry.getPos());
    }

    @Test
    void testTimestampHandling() {
        // This test is no longer relevant as AnnotationEntry does not handle timestamps directly.
    }
    
    @Test
    void testAnnotationId() {
        // Test annotation ID getter
        int annotationId = 42;
        AnnotationEntry entry = new AnnotationEntry(annotationId, 1, 1, 0, 3, "test", "NOUN");
        assertEquals(annotationId, entry.getAnnotationId());
    }
} 