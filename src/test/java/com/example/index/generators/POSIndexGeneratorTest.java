package com.example.index.generators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDBException;

import com.example.core.IndexAccess;
import com.example.core.PostingList;
import com.example.index.IndexKey;
import com.example.index.KeySchema;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;

public class POSIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-pos.txt";
    private POSIndexGenerator generator;
    private IndexAccess indexAccess;
    private SynonymManager SynonymManager;
    private Path SynonymManagerPath;

    private static boolean hasKeyStartingWith(ListMultimap<IndexKey, PostingList> result, String type) {
        byte[] prefix = KeySchema.encodeTypePrefix(type.toUpperCase());
        for (IndexKey key : result.keySet()) {
            byte[] kb = key.bytes();
            if (kb.length >= prefix.length) {
                boolean match = true;
                for (int i = 0; i < prefix.length; i++) {
                    if (kb[i] != prefix[i]) {
                        match = false;
                        break;
                    }
                }
                if (match)
                    return true;
            }
        }
        return false;
    }

    private static long countCellsForType(ListMultimap<IndexKey, PostingList> result, String type) {
        byte[] prefix = KeySchema.encodeTypePrefix(type.toUpperCase());
        long total = 0;
        for (IndexKey key : result.keySet()) {
            byte[] kb = key.bytes();
            if (kb.length >= prefix.length) {
                boolean match = true;
                for (int i = 0; i < prefix.length; i++) {
                    if (kb[i] != prefix[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    for (PostingList pl : result.get(key)) {
                        total += pl.cells().getLongCardinality();
                    }
                }
            }
        }
        return total;
    }

    @TempDir
    Path sharedTempDir;

    @BeforeEach
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        try (PrintWriter writer = new PrintWriter(TEST_STOPWORDS_PATH)) {
            writer.println("the");
            writer.println("a");
            writer.println("is");
        }

        try (Options options = createTestOptions()) {
            this.indexAccess = new IndexAccess(indexBaseDir.resolve("pos"), "pos", options, false);
        }

        SynonymManagerPath = sharedTempDir.resolve("pos_test_lookup.db");
        try {
            SynonymManager = new SynonymManager(SynonymManagerPath);
        } catch (RocksDBException e) {
            fail("Failed to initialize SynonymManager for POS testing: " + e.getMessage());
        }

        generator = new POSIndexGenerator(
                this.indexAccess,
                TEST_STOPWORDS_PATH,
                sqliteConn,
                new ProgressTracker(),
                1000,
                null,
                SynonymManager);

        setupTestData();
    }

    private void setupTestData() throws SQLException {
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 1);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();

            pstmt.setInt(1, 2);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        String[][] testWords = {
                { "1", "0", "0", "3", "The", "the", "DET" },
                { "1", "0", "4", "9", "quick", "quick", "ADJ" },
                { "1", "0", "10", "15", "brown", "brown", "ADJ" },
                { "1", "0", "16", "19", "fox", "fox", "NOUN" },
                { "1", "0", "20", "26", "jumps", "jump", "VERB" },
                { "1", "1", "27", "32", "over", "over", "ADP" },
                { "1", "1", "33", "36", "the", "the", "DET" },
                { "1", "1", "37", "41", "lazy", "lazy", "ADJ" },
                { "1", "1", "42", "45", "dog", "dog", "NOUN" },
                { "2", "0", "0", "2", "It", "it", "PRON" },
                { "2", "0", "3", "6", "Was", "be", "AUX" },
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
        if (SynonymManager != null) {
            try {
                SynonymManager.deleteDatabaseFiles();
            } catch (IOException e) {
                logger.error("Error tearing down SynonymManager for POS: " + e.getMessage(), e);
            }
        }
        super.tearDown();
        new File(TEST_STOPWORDS_PATH).delete();
    }

    @Test
    public void testBasicPOSIndexing() throws Exception, RocksDBException {
        var entries = generator.fetchBatch(null);
        assertEquals(14, entries.size(), "Should fetch all 14 relevant annotations from test data");

        ListMultimap<IndexKey, PostingList> result = generator.processBatch(entries);

        String[] expectedTags = { "NOUN", "VERB", "ADJ", "DET", "ADP", "PRON", "AUX" };
        for (String tag : expectedTags) {
            assertTrue(hasKeyStartingWith(result, tag), "Should contain POS tag as key: " + tag);
        }

        assertEquals(3, countCellsForType(result, "NOUN"), "Should have 3 cells for NOUN type (fox, dog, night)");

        assertEquals(2, countCellsForType(result, "DET"), "Should have 2 cells for DET type (the, the)");
    }

    @Test
    public void testCaseNormalization() throws Exception, RocksDBException {
        try (PreparedStatement pstmt = sqliteConn.prepareStatement("DELETE FROM annotations")) {
            pstmt.executeUpdate();
        }
        try (PreparedStatement pstmt = sqliteConn.prepareStatement("DELETE FROM documents")) {
            pstmt.executeUpdate();
        }

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 3);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        Object[][] mixedCaseWords = {
                { 3, 0, 0, 4, "Test", "test", "NOUN" },
                { 3, 0, 5, 9, "Word", "word", "noun" },
                { 3, 0, 10, 14, "Run", "run", "VERB" },
                { 3, 0, 15, 19, "Fast", "fast", "verb" }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (Object[] wordData : mixedCaseWords) {
                pstmt.setInt(1, (Integer) wordData[0]);
                pstmt.setInt(2, (Integer) wordData[1]);
                pstmt.setInt(3, (Integer) wordData[2]);
                pstmt.setInt(4, (Integer) wordData[3]);
                pstmt.setString(5, (String) wordData[4]);
                pstmt.setString(6, (String) wordData[5]);
                pstmt.setString(7, (String) wordData[6]);
                pstmt.executeUpdate();
            }
        }

        var entries = generator.fetchBatch(null);
        assertEquals(4, entries.size(), "Should fetch all 4 annotations for case normalization test");

        var result = generator.processBatch(entries);

        assertTrue(hasKeyStartingWith(result, "NOUN"), "Result should contain key NOUN");
        assertEquals(2, countCellsForType(result, "NOUN"), "Should have 2 cells for NOUN type (test, word)");

        assertTrue(hasKeyStartingWith(result, "VERB"), "Result should contain key VERB");
        assertEquals(2, countCellsForType(result, "VERB"), "Should have 2 cells for VERB type (run, fast)");
    }
}
