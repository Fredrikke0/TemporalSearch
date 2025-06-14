package com.example.index.generators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.PrintWriter;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rocksdb.Options;

import com.example.core.IndexAccess;
import com.example.core.PositionListSoA;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;

public class DependencyIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-dep.txt";
    private DependencyIndexGenerator generator;
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

        // Create IndexAccess instance first
        try (Options options = createTestOptions()) {
            this.indexAccess = new IndexAccess(indexBaseDir.resolve("dependency"), "dependency", options, false);
        }

        // Create generator
        generator = new DependencyIndexGenerator(
            this.indexAccess,
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

        // Insert test sentences with dependencies
        String[][] testWords = {
            // "The quick brown fox jumps over the lazy dog"
            // Format: document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation
            { "1", "0", "0", "3", "fox", "The", "det" },
            { "1", "0", "4", "9", "fox", "quick", "amod" },
            { "1", "0", "10", "15", "fox", "brown", "amod" },
            { "1", "0", "16", "25", "fox", "jumps", "nsubj" },
            { "1", "0", "20", "25", "ROOT", "jumps", "root" },
            { "1", "0", "26", "30", "jumps", "over", "prep" },
            { "1", "0", "31", "34", "dog", "the", "det" },
            { "1", "0", "35", "39", "dog", "lazy", "amod" },
            { "1", "0", "40", "43", "over", "dog", "pobj" }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO dependencies (document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation) " +
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
    public void testBasicDependencyIndexing() throws Exception {
        // Fetch first batch of entries
        var entries = generator.fetchBatch(null);

        // Process batch and verify results
        ListMultimap<String, PositionListSoA> result = generator.processBatch(entries);

        // Verify subject-verb dependency
        String key1 = "fox" + IndexGenerator.DELIMITER + "nsubj" + IndexGenerator.DELIMITER + "jumps";
        assertTrue(result.containsKey(key1), "Should contain subject-verb dependency");
        assertEquals(1, result.get(key1).get(0).getNumPositions(),
            "Should have one position for subject-verb dependency");

        // Verify verb-object dependency
        String key2 = "jumps" + IndexGenerator.DELIMITER + "prep" + IndexGenerator.DELIMITER + "over";
        assertTrue(result.containsKey(key2), "Should contain verb-object dependency");
        assertEquals(1, result.get(key2).get(0).getNumPositions(),
            "Should have one position for verb-object dependency");
    }

    @Test
    public void testCaseNormalization() throws Exception {
        // Insert mixed case dependency data
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 2);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        String[][] mixedCaseWords = {
            // Format: document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation
            { "2", "0", "0", "3", "Cat", "Chases", "nsubj" },
            { "2", "0", "4", "10", "ROOT", "Chases", "root" },
            { "2", "0", "11", "16", "Chases", "Mouse", "dobj" }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO dependencies (document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation) " +
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

        // Fetch and process entries
        var entries = generator.fetchBatch(null);
        var result = generator.processBatch(entries);

        // Verify case normalization
        String key3 = "cat" + IndexGenerator.DELIMITER + "nsubj" + IndexGenerator.DELIMITER + "chases";
        assertTrue(result.containsKey(key3), "Should contain normalized subject-verb dependency");
        assertEquals(1, result.get(key3).get(0).getNumPositions(),
            "Should have one position for normalized subject-verb dependency");
    }
}