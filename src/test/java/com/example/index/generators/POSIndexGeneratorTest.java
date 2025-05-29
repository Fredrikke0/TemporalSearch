package com.example.index.generators;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.index.generators.POSIndexGenerator;
import com.example.core.IndexAccess;

public class POSIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-pos.txt";
    private POSIndexGenerator generator;
    private IndexAccess indexAccess;

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
        generator = new POSIndexGenerator(
            tempDir.resolve("test-leveldb-pos").toString(),
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

            pstmt.setInt(1, 2);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        // Insert test sentences:
        // Doc 1: "The black cat sits quietly."
        // Doc 1: "It purrs softly."
        // Doc 2: "The black dog barks loudly."
        String[][] testWords = {
                // Document 1, Sentence 1
                { "1", "0", "0", "3", "The", "the", "DET" },
                { "1", "0", "4", "9", "quick", "quick", "ADJ" },
                { "1", "0", "10", "15", "brown", "brown", "ADJ" },
                { "1", "0", "16", "19", "fox", "fox", "NOUN" },
                { "1", "0", "20", "26", "jumps", "jump", "VERB" },
                // Document 1, Sentence 2
                { "1", "1", "27", "32", "over", "over", "ADP" },
                { "1", "1", "33", "36", "the", "the", "DET" },
                { "1", "1", "37", "41", "lazy", "lazy", "ADJ" },
                { "1", "1", "42", "45", "dog", "dog", "NOUN" },
                // Document 2, Sentence 1
                { "2", "0", "0", "2", "It", "it", "PRON" },
                { "2", "0", "3", "6", "was", "be", "AUX" },
                { "2", "0", "7", "8", "a", "a", "DET" },
                { "2", "0", "9", "13", "dark", "dark", "ADJ" },
                { "2", "0", "14", "19", "night", "night", "NOUN" }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] word : testWords) {
                pstmt.setInt(1, Integer.parseInt(word[0]));
                pstmt.setInt(2, Integer.parseInt(word[1]));
                pstmt.setInt(3, Integer.parseInt(word[2]));
                pstmt.setInt(4, Integer.parseInt(word[3]));
                pstmt.setString(5, word[4]);
                pstmt.setString(6, word[5]);
                pstmt.setString(7, word[6]);
                pstmt.executeUpdate();
            }
        }
    }

    @AfterEach
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        new File(TEST_STOPWORDS_PATH).delete();
        if (indexAccess != null) {
            indexAccess.close();
        }
    }

    @Test
    public void testBasicPOSIndexing() throws Exception {
        // Fetch first batch of entries
        var entries = generator.fetchBatch(null);

        // Process batch and verify results
        ListMultimap<String, PositionListSoA> result = generator.processBatch(entries);

        // Check that POS tags exist in composite keys
        String[] expectedTags = {"NOUN", "VERB", "ADJ", "DET", "ADP", "PRON", "AUX"};
        for (String tag : expectedTags) {
            boolean hasTagKey = result.keySet().stream()
                .anyMatch(key -> key.startsWith(tag + com.example.core.IndexAccessInterface.DELIMITER));
            assertTrue(hasTagKey, "Should contain at least one key with POS tag: " + tag);
        }

        // Verify NOUN has multiple positions
        int totalNounPositions = result.keySet().stream()
            .filter(key -> key.startsWith("NOUN" + com.example.core.IndexAccessInterface.DELIMITER))
            .flatMap(key -> result.get(key).stream())
            .mapToInt(pl -> pl.getNumPositions())
            .sum();
        assertEquals(3, totalNounPositions, "Should have 3 NOUN positions");

        // Verify DET is indexed despite being a stopword
        int totalDetPositions = result.keySet().stream()
            .filter(key -> key.startsWith("DET" + com.example.core.IndexAccessInterface.DELIMITER))
            .flatMap(key -> result.get(key).stream())
            .mapToInt(pl -> pl.getNumPositions())
            .sum();
        assertEquals(3, totalDetPositions, "Should have 3 DET positions");
    }

    @Test
    public void testCaseNormalization() throws Exception {
        // Clear existing data
        try (PreparedStatement pstmt = sqliteConn.prepareStatement("DELETE FROM documents")) {
            pstmt.executeUpdate();
        }
        try (PreparedStatement pstmt = sqliteConn.prepareStatement("DELETE FROM annotations")) {
            pstmt.executeUpdate();
        }

        // Insert document record for mixed case test data
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 3);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        // Insert mixed case POS tags
        Object[][] mixedCaseWords = {
            { 3, 0, 0, 4, "test", "test", "NOUN" },
            { 3, 0, 5, 9, "word", "word", "noun" },
            { 3, 0, 10, 14, "run", "run", "VERB" },
            { 3, 0, 15, 19, "fast", "fast", "verb" }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (Object[] word : mixedCaseWords) {
                pstmt.setInt(1, (Integer) word[0]);
                pstmt.setInt(2, (Integer) word[1]);
                pstmt.setInt(3, (Integer) word[2]);
                pstmt.setInt(4, (Integer) word[3]);
                pstmt.setString(5, (String) word[4]);
                pstmt.setString(6, (String) word[5]);
                pstmt.setString(7, (String) word[6]);
                pstmt.executeUpdate();
            }
        }

        // Fetch and process entries
        var entries = generator.fetchBatch(null);
        var result = generator.processBatch(entries);

        // Verify case normalization
        int totalNounPositions = result.keySet().stream()
            .filter(key -> key.startsWith("NOUN" + com.example.core.IndexAccessInterface.DELIMITER))
            .flatMap(key -> result.get(key).stream())
            .mapToInt(pl -> pl.getNumPositions())
            .sum();
        assertEquals(2, totalNounPositions, "Should have 2 NOUN positions after case normalization");

        int totalVerbPositions = result.keySet().stream()
            .filter(key -> key.startsWith("VERB" + com.example.core.IndexAccessInterface.DELIMITER))
            .flatMap(key -> result.get(key).stream())
            .mapToInt(pl -> pl.getNumPositions())
            .sum();
        assertEquals(2, totalVerbPositions, "Should have 2 VERB positions after case normalization");
    }
}