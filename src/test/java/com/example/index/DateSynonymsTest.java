package com.example.index;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the simplified DateSynonyms implementation.
 */
public class DateSynonymsTest {

    @TempDir
    Path tempDir;

    private DateSynonyms dateSynonyms;

    @BeforeEach
    void setUp() throws IOException {
        dateSynonyms = new DateSynonyms(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (dateSynonyms != null) {
            dateSynonyms.close();
        }
    }

    @Test
    void testGetOrCreateId_NewDate() {
        // When getting an ID for a new date
        int id = dateSynonyms.getOrCreateId("2023-01-01");

        // Then a new ID should be assigned
        assertTrue(id > 0);
        assertEquals("2023-01-01", dateSynonyms.getDateValue(id));
    }

    @Test
    void testGetOrCreateId_ExistingDate() {
        // Given an existing date mapping
        int id1 = dateSynonyms.getOrCreateId("2023-01-01");

        // When getting the ID again for the same date
        int id2 = dateSynonyms.getOrCreateId("2023-01-01");

        // Then the same ID should be returned
        assertEquals(id1, id2);
    }

    @Test
    void testGetOrCreateId_MultipleDates() {
        // When getting IDs for multiple dates
        int id1 = dateSynonyms.getOrCreateId("2023-01-01");
        int id2 = dateSynonyms.getOrCreateId("2023-02-01");
        int id3 = dateSynonyms.getOrCreateId("2023-03-01");

        // Then each should have a unique ID
        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
        assertNotEquals(id1, id3);

        // And retrieving the dates should work
        assertEquals("2023-01-01", dateSynonyms.getDateValue(id1));
        assertEquals("2023-02-01", dateSynonyms.getDateValue(id2));
        assertEquals("2023-03-01", dateSynonyms.getDateValue(id3));
    }

    @Test
    void testGetOrCreateId_InvalidDate() {
        // When trying to get an ID for an invalid date
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            dateSynonyms.getOrCreateId("not-a-date");
        });

        // Then an exception should be thrown
        assertTrue(exception.getMessage().contains("Date must be in YYYY-MM-DD format"));
    }

    @Test
    void testGetDateValue_NonExistentId() {
        // When getting a date for a non-existent ID
        String date = dateSynonyms.getDateValue(999);

        // Then null should be returned
        assertNull(date);
    }

    @Test
    void testPersistence() throws IOException {
        // Given some date mappings
        int id1 = dateSynonyms.getOrCreateId("2023-01-01");
        int id2 = dateSynonyms.getOrCreateId("2023-02-01");

        // When closing and reopening the dateSynonyms
        dateSynonyms.close();
        dateSynonyms = new DateSynonyms(tempDir);

        // Then the mappings should be persisted
        assertEquals("2023-01-01", dateSynonyms.getDateValue(id1));
        assertEquals("2023-02-01", dateSynonyms.getDateValue(id2));
        assertEquals(id1, dateSynonyms.getOrCreateId("2023-01-01"));
        assertEquals(id2, dateSynonyms.getOrCreateId("2023-02-01"));
    }

    @Test
    void testValidateSynonyms() {
        // Given some date mappings
        dateSynonyms.getOrCreateId("2023-01-01");
        dateSynonyms.getOrCreateId("2023-02-01");

        // When validating the synonyms
        // Then no exception should be thrown
        assertDoesNotThrow(() -> dateSynonyms.validateSynonyms());
    }
}