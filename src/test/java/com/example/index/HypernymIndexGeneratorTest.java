package com.example.index;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.sql.*;
import java.util.Map;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;
import com.example.core.Position;
import com.example.core.PositionList;

public class HypernymIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-hyp.txt";
    private HypernymIndexGenerator generator;

    @BeforeEach
    @Override
    void setUp() throws Exception {
        super.setUp();
        
        // Create test stopwords file
        try (PrintWriter writer = new PrintWriter(TEST_STOPWORDS_PATH)) {
            writer.println("the");
            writer.println("a");
            writer.println("is");
        }

        // Create generator
        generator = new HypernymIndexGenerator(
            tempDir.resolve("test-leveldb-hyp").toString(),
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

        // First insert the annotations
        String[][] annotations = {
            // Format: document_id, sentence_id, begin_char, end_char, token, lemma, pos
            // "Animals such as cats and dogs"
            { "1", "0", "0", "7", "Animals", "animal", "NOUN" },
            { "1", "0", "8", "16", "such as", "such_as", "ADP" },
            { "1", "0", "17", "21", "cats", "cat", "NOUN" },
            { "1", "0", "22", "25", "and", "and", "CONJ" },
            { "1", "0", "26", "30", "dogs", "dog", "NOUN" }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] word : annotations) {
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

        // Then insert the dependencies that indicate hypernym relations
        String[][] dependencies = {
            // Format: document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation
            { "1", "0", "8", "16", "Animals", "cats", "nmod:such_as" },
            { "1", "0", "22", "30", "Animals", "dogs", "nmod:such_as" }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO dependencies (document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] dep : dependencies) {
                pstmt.setInt(1, Integer.parseInt(dep[0]));
                pstmt.setInt(2, Integer.parseInt(dep[1]));
                pstmt.setInt(3, Integer.parseInt(dep[2]));
                pstmt.setInt(4, Integer.parseInt(dep[3]));
                pstmt.setString(5, dep[4]);
                pstmt.setString(6, dep[5]);
                pstmt.setString(7, dep[6]);
                pstmt.executeUpdate();
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        super.tearDown();
        new File(TEST_STOPWORDS_PATH).delete();
    }

    @Test
    public void testBasicHypernymIndexing() throws Exception {
        // Fetch first batch of entries
        var entries = generator.fetchBatch(0);
        
        // Process batch and verify results
        ListMultimap<String, PositionList> result = generator.processBatch(entries);
        
        // Verify animal->cat hypernym
        String key1 = "animal" + IndexGenerator.DELIMITER + "cat";
        assertTrue(result.containsKey(key1), "Should contain animal->cat hypernym");
        assertEquals(1, result.get(key1).get(0).getPositions().size(), 
            "Should have one position for animal->cat hypernym");
        
        // Verify animal->dog hypernym
        String key2 = "animal" + IndexGenerator.DELIMITER + "dog";
        assertTrue(result.containsKey(key2), "Should contain animal->dog hypernym");
        assertEquals(1, result.get(key2).get(0).getPositions().size(), 
            "Should have one position for animal->dog hypernym");
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
        try (PreparedStatement pstmt = sqliteConn.prepareStatement("DELETE FROM dependencies")) {
            pstmt.executeUpdate();
        }

        // Insert document
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 2);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        // Insert mixed case annotations
        String[][] mixedCaseWords = {
            // Format: document_id, sentence_id, begin_char, end_char, token, lemma, pos
            { "2", "0", "0", "3", "CAT", "cat", "NOUN" },
            { "2", "0", "4", "10", "Animal", "animal", "NOUN" },
            { "2", "0", "11", "14", "DOG", "dog", "NOUN" },
            { "2", "0", "15", "21", "MAMMAL", "mammal", "NOUN" }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] word : mixedCaseWords) {
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

        // Insert dependencies that indicate hypernym relations
        String[][] dependencies = {
            // Format: document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation
            { "2", "0", "4", "10", "Animal", "CAT", "nmod:such_as" },
            { "2", "0", "15", "21", "MAMMAL", "DOG", "nmod:such_as" }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO dependencies (document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] dep : dependencies) {
                pstmt.setInt(1, Integer.parseInt(dep[0]));
                pstmt.setInt(2, Integer.parseInt(dep[1]));
                pstmt.setInt(3, Integer.parseInt(dep[2]));
                pstmt.setInt(4, Integer.parseInt(dep[3]));
                pstmt.setString(5, dep[4]);
                pstmt.setString(6, dep[5]);
                pstmt.setString(7, dep[6]);
                pstmt.executeUpdate();
            }
        }

        // Fetch and process entries
        var entries = generator.fetchBatch(0);
        var result = generator.processBatch(entries);

        // Verify case normalization
        String key1 = "animal" + IndexGenerator.DELIMITER + "cat";
        assertTrue(result.containsKey(key1), "Should contain normalized animal->cat hypernym");
        assertEquals(1, result.get(key1).get(0).getPositions().size(), 
            "Should have one position for normalized animal->cat hypernym");

        String key2 = "mammal" + IndexGenerator.DELIMITER + "dog";
        assertTrue(result.containsKey(key2), "Should contain normalized mammal->dog hypernym");
        assertEquals(1, result.get(key2).get(0).getPositions().size(), 
            "Should have one position for normalized mammal->dog hypernym");
    }
} 