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
            this.indexAccess = new IndexAccess(indexBaseDir.resolve("ner"), "ner", options);
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

        assertTrue(result.containsKey("PERSON"), "Should contain PERSON entity type key");
        assertEquals(1, result.get("PERSON").size(), "Should be one PositionListSoA for PERSON type");
        PositionListSoA personPositions = result.get("PERSON").get(0);
        assertEquals(1, personPositions.getNumPositions(), "Should have one position in total for PERSON type");
        int johnSmithId = SynonymManager.getId("john smith");
        assertEquals(johnSmithId, personPositions.getSynonymIdAt(0), "SynonymId should match ID for 'john smith'");

        assertTrue(result.containsKey("ORGANIZATION"), "Should contain ORGANIZATION entity type key");
        assertEquals(1, result.get("ORGANIZATION").size(), "Should be one PositionListSoA for ORGANIZATION type");
        PositionListSoA orgPositions = result.get("ORGANIZATION").get(0);
        assertEquals(2, orgPositions.getNumPositions(), "Should have two positions in total for ORGANIZATION type (Google, Microsoft)");

        int googleId = SynonymManager.getId("google");
        int microsoftId = SynonymManager.getId("microsoft");

        List<Integer> orgSynonymIds = orgPositions.getSynonymIds().intStream().boxed().collect(Collectors.toList());
        assertTrue(orgSynonymIds.contains(googleId), "Synonym ID for 'google' should be present in ORGANIZATION positions");
        assertTrue(orgSynonymIds.contains(microsoftId), "Synonym ID for 'microsoft' should be present in ORGANIZATION positions");

        long googleCount = IntStream.range(0, orgPositions.getNumPositions())
                                  .filter(i -> orgPositions.getSynonymIdAt(i) == googleId)
                                  .count();
        assertEquals(1, googleCount, "Should be one position entry for Google");

        long microsoftCount = IntStream.range(0, orgPositions.getNumPositions())
                                   .filter(i -> orgPositions.getSynonymIdAt(i) == microsoftId)
                                   .count();
        assertEquals(1, microsoftCount, "Should be one position entry for Microsoft");

        assertTrue(result.containsKey("LOCATION"), "Should contain LOCATION entity type key");
        assertEquals(1, result.get("LOCATION").size(), "Should be one PositionListSoA for LOCATION type");
        PositionListSoA locPositions = result.get("LOCATION").get(0);
        assertEquals(1, locPositions.getNumPositions(), "Should have one position in total for LOCATION type");
        int mountainViewId = SynonymManager.getId("mountain view");
        assertEquals(mountainViewId, locPositions.getSynonymIdAt(0), "SynonymId should match ID for 'mountain view'");
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
        assertFalse(result.containsKey("DATE"), "Should not contain DATE entity type key from NerIndexGenerator");
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

        assertTrue(result.containsKey("ORGANIZATION"), "Should contain ORGANIZATION entity type key for apple/APPLE");
        assertEquals(1, result.get("ORGANIZATION").size(), "Should be one PositionListSoA for ORGANIZATION type");
        PositionListSoA applePl = result.get("ORGANIZATION").get(0);

        assertEquals(2, applePl.getNumPositions(), "Should have two positions for normalized ORGANIZATION entity (apple)");

        int appleId = SynonymManager.getId("apple");
        boolean allMatchAppleId = IntStream.range(0, applePl.getNumPositions())
                                           .allMatch(i -> applePl.getSynonymIdAt(i) == appleId);
        assertTrue(allMatchAppleId, "All positions in the list should have synonymId for 'apple'");
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

        String nzArmyKey = "ORGANIZATION";
        assertTrue(result.containsKey(nzArmyKey), "Should contain ORGANIZATION key for multi-token");
        assertEquals(1, result.get(nzArmyKey).size(), "Should be one PositionListSoA for ORGANIZATION type");

        PositionListSoA nzArmyPositions = result.get(nzArmyKey).get(0);

        assertEquals(1, nzArmyPositions.getNumPositions(), "Should have one position entry for the combined 'New Zealand Army Corps'");

        int nzArmyId = SynonymManager.getId("new zealand army corps");
        Position pos = nzArmyPositions.getPositionAt(0);

        assertEquals(nzArmyId, nzArmyPositions.getSynonymIdAt(0), "SynonymId should match ID for 'new zealand army corps'");
        assertEquals(0, pos.getBeginPosition(), "Begin char for 'New Zealand Army Corps' should be 0");
        assertEquals(22, pos.getEndPosition(), "End char for 'New Zealand Army Corps' should be 22");
    }
}