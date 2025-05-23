package com.example.index.generators;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.sql.*;
import java.util.Map;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.index.generators.NerDateIndexGenerator;

public class NerDateIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-ner.txt";
    private NerDateIndexGenerator generator;

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

        // Create generator
        generator = new NerDateIndexGenerator(
            tempDir.resolve("test-leveldb-ner-date").toString(),
            TEST_STOPWORDS_PATH,
            sqliteConn,
            new ProgressTracker(),
            1000
        );

        // Insert test data
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
            // "The meeting is on January 15, 2024"
            { "1", "0", "0", "3", "The", "the", "DET", null, null },
            { "1", "0", "4", "11", "meeting", "meeting", "NOUN", null, null },
            { "1", "0", "12", "14", "is", "be", "VERB", null, null },
            { "1", "0", "15", "17", "on", "on", "ADP", null, null },
            { "1", "0", "18", "33", "January 15, 2024", "2024-01-15", "DATE", "2024-01-15", "DATE" },
            // "The deadline is February 1st, 2024"
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
    public void testBasicDateIndexing() throws Exception {
        // Fetch first batch of entries
        var entries = generator.fetchBatch(null);
        
        // Process batch and verify results
        ListMultimap<String, PositionListSoA> result = generator.processBatch(entries);
        
        // Verify January date
        String key1 = "20240115";
        assertTrue(result.containsKey(key1), "Should contain January date");
        assertEquals(1, result.get(key1).get(0).getNumPositions(), 
            "Should have one position for January date");
        
        // Verify February date
        String key2 = "20240201";
        assertTrue(result.containsKey(key2), "Should contain February date");
        assertEquals(1, result.get(key2).get(0).getNumPositions(), 
            "Should have one position for February date");
    }

    @Test
    public void testDateNormalization() throws Exception {
        // Insert mixed format date data
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 2);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        String[][] mixedDateWords = {
            // Different formats for January 15, 2024
            { "2", "0", "0", "15", "Jan 15, 2024", "2024-01-15", "DATE", "2024-01-15", "DATE" },
            { "2", "0", "16", "31", "January 15 2024", "2024-01-15", "DATE", "2024-01-15", "DATE" },
            { "2", "0", "32", "42", "01/15/2024", "2024-01-15", "DATE", "2024-01-15", "DATE" }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos, normalized_ner, ner) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] word : mixedDateWords) {
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

        // Fetch and process entries
        var entries = generator.fetchBatch(null);
        var result = generator.processBatch(entries);

        // Verify date normalization
        String key3 = "20240115"; // Corresponds to "2024-01-15"
        assertTrue(result.containsKey(key3), 
            "Should contain normalized January date key '20240115'. Actual keys: " + result.keySet());
        
        // processBatch should produce one PositionList for this key within this batch
        assertEquals(1, result.get(key3).size(), 
            "Should be one PositionList for the key '" + key3 + "' in the batch result.");

        // That one PositionListSoA should contain all 4 occurrences from doc1 and doc2
        PositionListSoA positionsForDate = result.get(key3).get(0);
        assertEquals(4, positionsForDate.getNumPositions(), 
            "Should have collected 4 positions for date '2024-01-15' (1 from doc1, 3 from doc2) in the batch.");
    }
} 