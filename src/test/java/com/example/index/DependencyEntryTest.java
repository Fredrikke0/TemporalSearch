package com.example.index;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import com.example.index.generators.BaseIndexTest;

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
    }

    @Test
    void testNullHandling() {
        // Test with null tokens and relation
        DependencyEntry entry = new DependencyEntry(0, 1, 1, 0, 10, null, null, null);
        assertNull(entry.getHeadToken());
        assertNull(entry.getDependentToken());
        assertNull(entry.getRelation());
    }

    @Test
    void testEmptyStrings() {
        // Test with empty strings
        DependencyEntry entry = new DependencyEntry(0, 1, 1, 0, 10, "", "", "");
        assertEquals("", entry.getHeadToken());
        assertEquals("", entry.getDependentToken());
        assertEquals("", entry.getRelation());
    }

    @Test
    void testTimestampHandling() {
        // This test is no longer relevant as DependencyEntry does not handle timestamps directly.
    }
} 