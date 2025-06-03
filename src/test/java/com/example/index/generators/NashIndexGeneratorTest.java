package com.example.index.generators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.PrintWriter;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rocksdb.Options;

import com.example.core.IndexAccess;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.index.util.NashSerializationUtils;
import com.example.logging.ProgressTracker;

public class NashIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-nash.txt";
    private NashIndexGenerator generator;
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
            this.indexAccess = new IndexAccess(indexBaseDir.resolve("nash"), "nash", options);
        }

        generator = new NashIndexGenerator(
            this.indexAccess,
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
        if (this.indexAccess != null) {
            this.indexAccess.close();
        }
    }

    @Test
    public void testNashIndexGenerationAndSerialization() throws Exception {
        // Generate the Nash index
        generator.generateIndex();
        // Check that the RocksDB index exists and contains the lookup table
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
        // Create a sample PositionListSoA as it would be stored by NashIndexGenerator
        Position pos1 = new Position(1, 0, 18, 33);
        int dateId1 = 0;
        Position pos2 = new Position(1, 1, 5, 10);
        int dateId2 = 1;

        PositionListSoA originalSoA = new PositionListSoA();
        originalSoA.add(pos1.getDocumentId(), pos1.getSentenceId(), pos1.getBeginPosition(), pos1.getEndPosition(), dateId1);
        originalSoA.add(pos2.getDocumentId(), pos2.getSentenceId(), pos2.getBeginPosition(), pos2.getEndPosition(), dateId2);

        byte[] serialized = originalSoA.serializeToCompositeBlob();
        PositionListSoA deserializedSoA = PositionListSoA.deserializeFromCompositeBlob(serialized);

        assertEquals(2, deserializedSoA.getNumPositions());

        // Check first entry
        assertEquals(pos1.getDocumentId(), deserializedSoA.getDocIdAt(0));
        assertEquals(pos1.getSentenceId(), deserializedSoA.getSentenceIdAt(0));
        assertEquals(pos1.getBeginPosition(), deserializedSoA.getBeginCharAt(0));
        assertEquals(pos1.getEndPosition(), deserializedSoA.getEndCharAt(0));
        assertEquals(dateId1, deserializedSoA.getSynonymIdAt(0)); // dateId is stored in synonymId

        // Check second entry
        assertEquals(pos2.getDocumentId(), deserializedSoA.getDocIdAt(1));
        assertEquals(pos2.getSentenceId(), deserializedSoA.getSentenceIdAt(1));
        assertEquals(pos2.getBeginPosition(), deserializedSoA.getBeginCharAt(1));
        assertEquals(pos2.getEndPosition(), deserializedSoA.getEndCharAt(1));
        assertEquals(dateId2, deserializedSoA.getSynonymIdAt(1)); // dateId is stored in synonymId
    }
}