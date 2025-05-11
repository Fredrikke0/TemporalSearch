package com.example.index;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;

/**
 * Tests for the DependencyEntry class.
 */
class DependencyEntryTest extends BaseIndexTest {

    @Test
    void testBasicIndexing() throws Exception {
        // Insert test document
        LocalDate timestamp = LocalDate.now();
        TestData.insertDocument(sqliteConn, 1, timestamp);

        // Insert test dependencies
        TestData.insertBasicDependencies(sqliteConn);

        // Create and verify a dependency entry
        DependencyEntry entry = TestData.createDependency(1, "chases", "cat", "nsubj");
        assertEquals(1, entry.getDocumentId());
        assertEquals(1, entry.getSentenceId());
        assertEquals(0, entry.getBeginChar());
        assertEquals(10, entry.getEndChar());
        assertEquals("chases", entry.getHeadToken());
        assertEquals("cat", entry.getDependentToken());
        assertEquals("nsubj", entry.getRelation());
        assertNotNull(entry.getTimestamp());
    }

    @Test
    void testNullHandling() {
        // Test with null tokens and relation
        DependencyEntry entry = new DependencyEntry(0, 1, 1, 0, 10, null, null, null, LocalDate.now());
        assertNull(entry.getHeadToken());
        assertNull(entry.getDependentToken());
        assertNull(entry.getRelation());
    }

    @Test
    void testEmptyStrings() {
        // Test with empty strings
        DependencyEntry entry = new DependencyEntry(0, 1, 1, 0, 10, "", "", "", LocalDate.now());
        assertEquals("", entry.getHeadToken());
        assertEquals("", entry.getDependentToken());
        assertEquals("", entry.getRelation());
    }

    @Test
    void testTimestampHandling() {
        LocalDate timestamp = LocalDate.of(2024, 1, 20);
        DependencyEntry entry = new DependencyEntry(0, 1, 1, 0, 10, "head", "dep", "rel", timestamp);
        assertEquals(timestamp, entry.getTimestamp());
    }
} 