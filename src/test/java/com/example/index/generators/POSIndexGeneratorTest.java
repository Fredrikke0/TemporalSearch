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
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDBException;

import com.example.core.IndexAccess;
import com.example.core.PositionListSoA;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;

public class POSIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-pos.txt";
    private POSIndexGenerator generator;
    private IndexAccess indexAccess;
    private SynonymManager SynonymManager;
    private Path SynonymManagerPath;

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
            this.indexAccess = new IndexAccess(indexBaseDir.resolve("pos"), "pos", options);
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
            SynonymManager
        );

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

        ListMultimap<String, PositionListSoA> result = generator.processBatch(entries);

        String[] expectedTags = {"NOUN", "VERB", "ADJ", "DET", "ADP", "PRON", "AUX"};
        for (String tag : expectedTags) {
            assertTrue(result.containsKey(tag), "Should contain POS tag as key: " + tag);
            assertEquals(1, result.get(tag).size(), "Should be one PositionListSoA for tag: " + tag);
        }

        PositionListSoA nounPositions = result.get("NOUN").get(0);
        assertEquals(3, nounPositions.getNumPositions(), "Should have 3 positions in total for NOUN type (fox, dog, night)");

        int foxId = SynonymManager.getId("fox");
        int dogId = SynonymManager.getId("dog");
        int nightId = SynonymManager.getId("night");

        assertEquals(1, IntStream.range(0, nounPositions.getNumPositions()).filter(i -> nounPositions.getSynonymIdAt(i) == foxId).count(), "Count for 'fox' should be 1");
        assertEquals(1, IntStream.range(0, nounPositions.getNumPositions()).filter(i -> nounPositions.getSynonymIdAt(i) == dogId).count(), "Count for 'dog' should be 1");
        assertEquals(1, IntStream.range(0, nounPositions.getNumPositions()).filter(i -> nounPositions.getSynonymIdAt(i) == nightId).count(), "Count for 'night' should be 1");

        PositionListSoA detPositions = result.get("DET").get(0);
        assertEquals(2, detPositions.getNumPositions(), "Should have 2 positions in total for DET type (the, the)");

        int theId = SynonymManager.getId("the");

        assertEquals(2, IntStream.range(0, detPositions.getNumPositions()).filter(i -> detPositions.getSynonymIdAt(i) == theId).count(), "Term 'the' (DET) should have 2 positions");
    }

    @Test
    public void testCaseNormalization() throws Exception, RocksDBException {
        try (PreparedStatement pstmt = sqliteConn.prepareStatement("DELETE FROM annotations")) { pstmt.executeUpdate(); }
        try (PreparedStatement pstmt = sqliteConn.prepareStatement("DELETE FROM documents")) { pstmt.executeUpdate(); }

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

        assertTrue(result.containsKey("NOUN"), "Result should contain key NOUN");
        assertEquals(1, result.get("NOUN").size(), "Should be one PositionListSoA for NOUN type");
        PositionListSoA nounPositions = result.get("NOUN").get(0);
        assertEquals(2, nounPositions.getNumPositions(), "Should have 2 positions in total for NOUN type (test, word)");

        int testId = SynonymManager.getId("test");
        int wordId = SynonymManager.getId("word");

        assertEquals(1, IntStream.range(0, nounPositions.getNumPositions()).filter(i -> nounPositions.getSynonymIdAt(i) == testId).count(), "Count for 'test' (NOUN) should be 1");
        assertEquals(1, IntStream.range(0, nounPositions.getNumPositions()).filter(i -> nounPositions.getSynonymIdAt(i) == wordId).count(), "Count for 'word' (NOUN) should be 1");

        assertTrue(result.containsKey("VERB"), "Result should contain key VERB");
        assertEquals(1, result.get("VERB").size(), "Should be one PositionListSoA for VERB type");
        PositionListSoA verbPositions = result.get("VERB").get(0);
        assertEquals(2, verbPositions.getNumPositions(), "Should have 2 positions in total for VERB type (run, fast)");

        int runId = SynonymManager.getId("run");
        int fastId = SynonymManager.getId("fast");

        assertEquals(1, IntStream.range(0, verbPositions.getNumPositions()).filter(i -> verbPositions.getSynonymIdAt(i) == runId).count(), "Count for 'run' (VERB) should be 1");
        assertEquals(1, IntStream.range(0, verbPositions.getNumPositions()).filter(i -> verbPositions.getSynonymIdAt(i) == fastId).count(), "Count for 'fast' (VERB) should be 1");
    }
}