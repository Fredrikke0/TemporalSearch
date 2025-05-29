package com.example.index.generators;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import com.example.logging.ProgressTracker;
import com.example.index.NashDateEntryWithId;
import com.example.index.generators.NashIndexGenerator;
import com.example.index.util.NashSerializationUtils;

public class NashIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-nash.txt";
    private NashIndexGenerator generator;

    @BeforeEach
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Create test stopwords file
        try (PrintWriter writer = new PrintWriter(TEST_STOPWORDS_PATH)) {
            writer.println("the");
            writer.println("a");
            writer.println("is");
        }
        generator = new NashIndexGenerator(
            tempDir.resolve("test-leveldb-nash").toString(),
            TEST_STOPWORDS_PATH,
            sqliteConn,
            new ProgressTracker(),
            1000
        );
        setupTestData();
    }

    private void setupTestData() throws SQLException {
        // Insert documents with timestamps
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 1);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }
        // Insert test sentences with dates
        String[][] testWords = {
            { "1", "0", "0", "3", "The", "the", "DET", null, null },
            { "1", "0", "4", "11", "meeting", "meeting", "NOUN", null, null },
            { "1", "0", "12", "14", "is", "be", "VERB", null, null },
            { "1", "0", "15", "17", "on", "on", "ADP", null, null },
            { "1", "0", "18", "33", "January 15, 2024", "2024-01-15", "DATE", "2024-01-15", "DATE" },
            { "1", "1", "34", "37", "The", "the", "DET", null, null },
            { "1", "1", "38", "46", "deadline", "deadline", "NOUN", null, null },
            { "1", "1", "47", "49", "is", "be", "VERB", null, null },
            { "1", "1", "50", "67", "February 1st, 2024", "2024-02-01", "DATE", "2024-02-01", "DATE" }
        };
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos, normalized_ner, ner) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] word : testWords) {
                pstmt.setInt(1, Integer.parseInt(word[0]));
                pstmt.setInt(2, Integer.parseInt(word[1]));
                pstmt.setInt(3, Integer.parseInt(word[2]));
                pstmt.setInt(4, Integer.parseInt(word[3]));
                pstmt.setString(5, word[4]);
                pstmt.setString(6, word[5]);
                pstmt.setString(7, word[6]);
                pstmt.setString(8, word[7]);
                pstmt.setString(9, word[8]);
                pstmt.executeUpdate();
            }
        }
    }

    @AfterEach
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        new File(TEST_STOPWORDS_PATH).delete();
    }

    @Test
    public void testNashIndexGenerationAndSerialization() throws Exception {
        // Generate the Nash index
        generator.generateIndex();
        // Check that the LevelDB index exists and contains the lookup table
        var nashIndex = generator.indexAccess;
        var rawLookup = nashIndex.getRaw(NashSerializationUtils.DATE_LOOKUP_KEY);
        assertTrue(rawLookup.isPresent(), "Date lookup table should be present in Nash index");
        List<LocalDate> lookup = NashSerializationUtils.deserializeDateLookup(rawLookup.get());
        assertEquals(2, lookup.size(), "Should have two unique dates in lookup table");
        assertTrue(lookup.contains(LocalDate.parse("2024-01-15")));
        assertTrue(lookup.contains(LocalDate.parse("2024-02-01")));
    }

    @Test
    public void testNashEntrySerializationRoundTrip() throws Exception {
        // Create a sample NashDateEntryWithId list
        var pos = new com.example.core.Position(1, 0, 18, 33);
        var entry = new NashDateEntryWithId(pos, 0);
        var entries = List.of(entry);
        byte[] serialized = NashSerializationUtils.serializeNashEntries(entries);
        List<NashDateEntryWithId> deserialized = NashSerializationUtils.deserializeNashEntries(serialized);
        assertEquals(1, deserialized.size());
        assertEquals(entry.position(), deserialized.get(0).position());
        assertEquals(entry.dateId(), deserialized.get(0).dateId());
    }
}