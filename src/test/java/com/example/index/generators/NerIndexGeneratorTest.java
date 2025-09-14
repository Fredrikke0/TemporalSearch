package com.example.index.generators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDBException;

import com.example.core.IndexAccess;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;

public class NerIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-ner-general.txt";
    private NerIndexGenerator generator;
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
            this.indexAccess = new IndexAccess(indexBaseDir.resolve("ner"), "ner", options, false);
        }

        SynonymManagerPath = sharedTempDir.resolve("ner_test_lookup.db");
        try {
            SynonymManager = new SynonymManager(SynonymManagerPath);
        } catch (RocksDBException e) {
            fail("Failed to initialize SynonymManager for testing: " + e.getMessage());
        }

        generator = new NerIndexGenerator(
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
        }

        String[][] testWords = {
            { "1", "0", "0", "10", "John Smith", "john smith", "NOUN", "PERSON", null },
            { "1", "0", "11", "16", "works", "work", "VERB", null, null },
            { "1", "0", "17", "19", "at", "at", "ADP", null, null },
            { "1", "0", "20", "26", "Google", "google", "NOUN", "ORGANIZATION", null },
            { "1", "0", "27", "29", "in", "in", "ADP", null, null },
            { "1", "0", "30", "43", "Mountain View", "mountain view", "NOUN", "LOCATION", null },
            { "1", "1", "44", "53", "Microsoft", "microsoft", "NOUN", "ORGANIZATION", null },
            { "1", "1", "54", "63", "announced", "announce", "VERB", null, null },
            { "1", "1", "64", "65", "a", "a", "DET", null, null },
            { "1", "1", "66", "69", "new", "new", "ADJ", null, null },
            { "1", "1", "70", "77", "product", "product", "NOUN", null, null },
            { "1", "1", "78", "87", "yesterday", "yesterday", "NOUN", null, null }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos, ner, normalized_ner) " +
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
        if (SynonymManager != null) {
            try {
                SynonymManager.deleteDatabaseFiles();
            } catch (IOException e) {
                logger.error("Error tearing down SynonymManager: " + e.getMessage(), e);
            }
        }
        super.tearDown();
        new File(TEST_STOPWORDS_PATH).delete();
    }

    @Test
    public void testEntityIndexing() throws Exception, RocksDBException {
        var entries = generator.fetchBatch(null);
        assertEquals(4, entries.size(), "Should have fetched 4 distinct entity occurrences initially");

        ListMultimap<String, PositionListSoA> result = generator.processBatch(entries);

        int johnSmithId = SynonymManager.getId("john smith");
        String personKey = "PERSON" + IndexGenerator.DELIMITER + johnSmithId;
        assertTrue(result.containsKey(personKey), "Should contain PERSON\\0synId key for 'john smith'");
        PositionListSoA personPositions = result.get(personKey).get(0);
        assertEquals(1, personPositions.getNumPositions(), "Should have one position for PERSON 'john smith'");

        int googleId = SynonymManager.getId("google");
        int microsoftId = SynonymManager.getId("microsoft");
        String orgKeyGoogle = "ORGANIZATION" + IndexGenerator.DELIMITER + googleId;
        String orgKeyMicrosoft = "ORGANIZATION" + IndexGenerator.DELIMITER + microsoftId;
        assertTrue(result.containsKey(orgKeyGoogle), "Should contain ORGANIZATION\\0synId for 'google'");
        assertTrue(result.containsKey(orgKeyMicrosoft), "Should contain ORGANIZATION\\0synId for 'microsoft'");
        assertEquals(1, result.get(orgKeyGoogle).get(0).getNumPositions(), "Google should have one position");
        assertEquals(1, result.get(orgKeyMicrosoft).get(0).getNumPositions(), "Microsoft should have one position");

        int mountainViewId = SynonymManager.getId("mountain view");
        String locKey = "LOCATION" + IndexGenerator.DELIMITER + mountainViewId;
        assertTrue(result.containsKey(locKey), "Should contain LOCATION\\0synId for 'mountain view'");
        assertEquals(1, result.get(locKey).get(0).getNumPositions(), "Should have one position for LOCATION 'mountain view'");
    }

    @Test
    public void testDateExclusion() throws Exception {
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 2);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        String[][] dateEntity = {
            { "2", "0", "0", "24", "January 15, 2024", "2024-01-15", "DATE", "DATE", "2024-01-15" }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos, ner, normalized_ner) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] word : dateEntity) {
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

        var entries = generator.fetchBatch(null);
        assertEquals(4, entries.size(), "Should still have 4 non-DATE entities fetched by fetchBatch (original test data)");

        ListMultimap<String, PositionListSoA> result = generator.processBatch(entries);
        boolean hasDateKey = result.keySet().stream().anyMatch(k -> k.startsWith("DATE" + IndexGenerator.DELIMITER));
        assertFalse(hasDateKey, "Should not contain DATE-prefixed keys from NerIndexGenerator");
    }

    @Test
    public void testEntityCaseNormalization() throws Exception, RocksDBException {
        try (PreparedStatement pstmt = sqliteConn.prepareStatement("DELETE FROM annotations")) { pstmt.executeUpdate(); }
        try (PreparedStatement pstmt = sqliteConn.prepareStatement("DELETE FROM documents")) { pstmt.executeUpdate(); }

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 3);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        String[][] caseEntities = {
            { "3", "0", "0", "5", "APPLE", "apple", "NOUN", "ORGANIZATION", null },
            { "3", "1", "0", "5", "Apple", "apple", "NOUN", "ORGANIZATION", null }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos, ner, normalized_ner) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] word : caseEntities) {
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

        var entries = generator.fetchBatch(null);
        assertEquals(2, entries.size(), "Should fetch 2 raw annotations for APPLE and Apple");

        var result = generator.processBatch(entries);

        int appleId = SynonymManager.getId("apple");
        String appleKey = "ORGANIZATION" + IndexGenerator.DELIMITER + appleId;
        assertTrue(result.containsKey(appleKey), "Should contain ORGANIZATION\\0appleId key");
        PositionListSoA applePl = result.get(appleKey).get(0);
        assertEquals(2, applePl.getNumPositions(), "Should have two positions for ORGANIZATION apple");
    }

    @Test
    public void testMultiTokenEntityContinuity() throws Exception, RocksDBException {
        try (PreparedStatement pstmt = sqliteConn.prepareStatement("DELETE FROM annotations")) { pstmt.executeUpdate(); }
        try (PreparedStatement pstmt = sqliteConn.prepareStatement("DELETE FROM documents")) { pstmt.executeUpdate(); }

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 4);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        String[][] multiTokenEntity = {
            { "4", "0", "0", "3", "New", "new", "ADJ", "ORGANIZATION", null },
            { "4", "0", "4", "11", "Zealand", "zealand", "NOUN", "ORGANIZATION", null },
            { "4", "0", "12", "16", "Army", "army", "NOUN", "ORGANIZATION", null },
            { "4", "0", "17", "22", "Corps", "corps", "NOUN", "ORGANIZATION", null },
            { "4", "0", "23", "25", "is", "be", "AUX", null, null },
            { "4", "0", "26", "27", "a", "a", "DET", null, null },
            { "4", "0", "28", "36", "military", "military", "ADJ", null, null },
            { "4", "0", "37", "49", "organization", "organization", "NOUN", null, null }
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos, ner, normalized_ner) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] word : multiTokenEntity) {
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
        var entries = generator.fetchBatch(null);
        assertEquals(4, entries.size(), "Should fetch 4 raw annotations for the tokens of 'New Zealand Army Corps'");

        var result = generator.processBatch(entries);

        int nzArmyId = SynonymManager.getId("new zealand army corps");
        String nzKey = "ORGANIZATION" + IndexGenerator.DELIMITER + nzArmyId;
        assertTrue(result.containsKey(nzKey), "Should contain ORGANIZATION key for multi-token");
        assertEquals(1, result.get(nzKey).get(0).getNumPositions(), "Should have one position entry for the combined 'New Zealand Army Corps'");

        PositionListSoA nzArmyPositions = result.get(nzKey).get(0);
        Position pos = nzArmyPositions.getPositionAt(0);
        assertEquals(0, pos.getBeginPosition(), "Begin char for 'New Zealand Army Corps' should be 0");
        assertEquals(22, pos.getEndPosition(), "End char for 'New Zealand Army Corps' should be 22");
    }
}