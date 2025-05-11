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
import com.example.core.IndexAccess;
import com.example.core.IndexAccessInterface;

public class NerIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-ner-general.txt";
    private NerIndexGenerator generator;

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
        generator = new NerIndexGenerator(
            tempDir.resolve("test-leveldb-ner").toString(),
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

        // Insert test sentences with named entities
        String[][] testWords = {
            // "John Smith works at Google in Mountain View"
            { "1", "0", "0", "10", "John Smith", "john smith", "NOUN", "PERSON", null },
            { "1", "0", "11", "16", "works", "work", "VERB", null, null },
            { "1", "0", "17", "19", "at", "at", "ADP", null, null },
            { "1", "0", "20", "26", "Google", "google", "NOUN", "ORGANIZATION", null },
            { "1", "0", "27", "29", "in", "in", "ADP", null, null },
            { "1", "0", "30", "43", "Mountain View", "mountain view", "NOUN", "LOCATION", null },
            // "Microsoft announced a new product yesterday"
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
    void tearDown() throws Exception {
        super.tearDown();
        new File(TEST_STOPWORDS_PATH).delete();
    }

    @Test
    public void testEntityIndexing() throws Exception {
        // Fetch first batch of entries
        var entries = generator.fetchBatch(null);
        
        // Check entity count
        assertEquals(4, entries.size(), "Should have fetched 4 entities");
        
        // Process batch and verify results
        ListMultimap<String, PositionList> result = generator.processBatch(entries);
        
        // Verify person entity
        String personKey = "PERSON" + IndexAccessInterface.DELIMITER + "john smith";
        assertTrue(result.containsKey(personKey), "Should contain PERSON entity");
        assertEquals(1, result.get(personKey).get(0).getPositions().size(), 
            "Should have one position for PERSON entity");
        
        // Verify organization entities
        String orgKey1 = "ORGANIZATION" + IndexAccessInterface.DELIMITER + "google";
        assertTrue(result.containsKey(orgKey1), "Should contain ORGANIZATION entity (Google)");
        assertEquals(1, result.get(orgKey1).get(0).getPositions().size(),
            "Should have one position for ORGANIZATION entity (Google)");
        
        String orgKey2 = "ORGANIZATION" + IndexAccessInterface.DELIMITER + "microsoft";
        assertTrue(result.containsKey(orgKey2), "Should contain ORGANIZATION entity (Microsoft)");
        assertEquals(1, result.get(orgKey2).get(0).getPositions().size(),
            "Should have one position for ORGANIZATION entity (Microsoft)");
        
        // Verify location entity
        String locKey = "LOCATION" + IndexAccessInterface.DELIMITER + "mountain view";
        assertTrue(result.containsKey(locKey), "Should contain LOCATION entity");
        assertEquals(1, result.get(locKey).get(0).getPositions().size(),
            "Should have one position for LOCATION entity");
    }

    @Test
    public void testDateExclusion() throws Exception {
        // Add a DATE entity
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 2);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        String[][] dateEntity = {
            // "The event happened on January 15, 2024"
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

        // Fetch and process entries
        var entries = generator.fetchBatch(null);
        
        // Check that DATE entity is excluded
        assertEquals(4, entries.size(), "Should still have 4 entities (DATE excluded)");
        
        // Verify no DATE entity in results
        ListMultimap<String, PositionList> result = generator.processBatch(entries);
        String dateKey = "DATE" + IndexAccessInterface.DELIMITER + "january 15, 2024";
        assertFalse(result.containsKey(dateKey), "Should not contain DATE entity");
    }
    
    @Test
    public void testEntityCaseNormalization() throws Exception {
        // Add entities with varied casing
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 3);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        String[][] caseEntities = {
            // "APPLE announced new iPhones"
            { "3", "0", "0", "5", "APPLE", "apple", "NOUN", "ORGANIZATION", null },
            // "Apple also released MacBooks"
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

        // Fetch and process entries
        var entries = generator.fetchBatch(null);
        var result = generator.processBatch(entries);
        
        // Verify case normalization - both APPLE and Apple should be mapped to the same key
        String appleKey = "ORGANIZATION" + IndexAccessInterface.DELIMITER + "apple";
        assertTrue(result.containsKey(appleKey), "Should contain normalized ORGANIZATION entity (apple)");
        assertEquals(2, result.get(appleKey).get(0).getPositions().size(),
            "Should have two positions for normalized ORGANIZATION entity (apple)");
    }

    @Test
    public void testMultiTokenEntityContinuity() throws Exception {
        // Add a multi-token entity spread across sequential annotations
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 4);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        String[][] multiTokenEntity = {
            // "New Zealand Army Corps is a military organization"
            { "4", "0", "0", "3", "New", "new", "ADJ", "ORGANIZATION", null },
            { "4", "0", "4", "11", "Zealand", "zealand", "NOUN", "ORGANIZATION", null },
            { "4", "0", "12", "16", "Army", "army", "NOUN", "ORGANIZATION", null },
            { "4", "0", "17", "22", "Corps", "corps", "NOUN", "ORGANIZATION", null },
            { "4", "0", "23", "25", "is", "be", "VERB", null, null },
            { "4", "0", "26", "27", "a", "a", "DET", null, null },
            { "4", "0", "28", "36", "military", "military", "ADJ", null, null },
            { "4", "0", "37", "48", "organization", "organization", "NOUN", null, null }
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

        // Fetch and process entries
        var entries = generator.fetchBatch(null);
        var result = generator.processBatch(entries);
        
        // The multi-token entity should be combined into a single entry
        String multiTokenKey = "ORGANIZATION" + IndexAccessInterface.DELIMITER + "new zealand army corps";
        assertTrue(result.containsKey(multiTokenKey), "Should contain the combined multi-token ORGANIZATION entity");
        
        // The combined entity should have one position
        assertEquals(1, result.get(multiTokenKey).get(0).getPositions().size(),
            "Should have one position for the combined entity");
            
        // Individual tokens should not exist as separate entities
        String tokenKey1 = "ORGANIZATION" + IndexAccessInterface.DELIMITER + "new";
        String tokenKey2 = "ORGANIZATION" + IndexAccessInterface.DELIMITER + "zealand";
        String tokenKey3 = "ORGANIZATION" + IndexAccessInterface.DELIMITER + "army";
        String tokenKey4 = "ORGANIZATION" + IndexAccessInterface.DELIMITER + "corps";
        
        assertFalse(result.containsKey(tokenKey1), "Should not contain individual token 'new'");
        assertFalse(result.containsKey(tokenKey2), "Should not contain individual token 'zealand'");
        assertFalse(result.containsKey(tokenKey3), "Should not contain individual token 'army'");
        assertFalse(result.containsKey(tokenKey4), "Should not contain individual token 'corps'");
        
        // Verify the position data for the combined entity
        Position entityPosition = result.get(multiTokenKey).get(0).getPositions().get(0);
        assertEquals(4, entityPosition.getDocumentId(), "Document ID should match");
        assertEquals(0, entityPosition.getSentenceId(), "Sentence ID should match");
        assertEquals(0, entityPosition.getBeginPosition(), "Begin position should be from first token");
        assertEquals(22, entityPosition.getEndPosition(), "End position should be from last token");
    }
} 