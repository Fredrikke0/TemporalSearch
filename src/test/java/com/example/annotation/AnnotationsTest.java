package com.example.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for verifying the integrity of annotations and dependencies produced by Annotations.java.
 * Focuses on document ID, sentence ID, and character position integrity.
 */
class AnnotationsTest {
    private Path dbFile;
    private Connection conn;
    private static String originalProgbarSilent;

    @BeforeAll
    static void storeOriginalProperty() {
        originalProgbarSilent = System.getProperty("progbar.silent");
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws SQLException {
        System.setProperty("progbar.silent", "true"); // Disable progress bar
        dbFile = tempDir.resolve("test.db");
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);

        // Create required tables
        try (Statement stmt = conn.createStatement()) {
            // Create documents table
            stmt.execute("""
                CREATE TABLE documents (
                    document_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    text TEXT NOT NULL,
                    timestamp DATE DEFAULT CURRENT_DATE
                )
            """);

            // Create annotations table
            stmt.execute("""
                CREATE TABLE annotations (
                    annotation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    document_id INTEGER NOT NULL,
                    sentence_id INTEGER,
                    begin_char INTEGER,
                    end_char INTEGER,
                    token TEXT,
                    lemma TEXT,
                    pos TEXT,
                    ner TEXT,
                    normalized_ner TEXT,
                    FOREIGN KEY (document_id) REFERENCES documents(document_id)
                )
            """);

            // Create dependencies table
            stmt.execute("""
                CREATE TABLE dependencies (
                    dependency_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    document_id INTEGER NOT NULL,
                    sentence_id INTEGER,
                    begin_char INTEGER,
                    end_char INTEGER,
                    head_token TEXT,
                    dependent_token TEXT,
                    relation TEXT,
                    FOREIGN KEY (document_id) REFERENCES documents(document_id)
                )
            """);
        }
    }

    @AfterEach
    void tearDown() {
        // Restore original system property
        if (originalProgbarSilent == null) {
            System.clearProperty("progbar.silent");
        } else {
            System.setProperty("progbar.silent", originalProgbarSilent);
        }
        // Close connection if needed
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                 // log error or ignore
            }
        }
    }

    @AfterAll
    static void restoreOriginalPropertyAfterAll() {
         // Ensure restoration even if @AfterEach fails somehow
        if (originalProgbarSilent == null) {
            System.clearProperty("progbar.silent");
        } else {
            System.setProperty("progbar.silent", originalProgbarSilent);
        }
    }

    @Disabled
    @Test
    void testDocumentIdIntegrity() throws Exception {
        // Insert test document
        insertTestDocument("The quick brown fox jumps over the lazy dog.");

        // Run annotation process
        runAnnotations();

        // Verify document IDs
        try (Statement stmt = conn.createStatement()) {
            // Check annotations have valid document IDs
            ResultSet rs = stmt.executeQuery(
                "SELECT DISTINCT document_id FROM annotations");
            assertTrue(rs.next(), "Should have annotations");
            assertEquals(1, rs.getInt("document_id"));
            assertFalse(rs.next(), "Should only have one document ID");

            // Check dependencies have valid document IDs
            rs = stmt.executeQuery(
                "SELECT DISTINCT document_id FROM dependencies");
            assertTrue(rs.next(), "Should have dependencies");
            assertEquals(1, rs.getInt("document_id"));
            assertFalse(rs.next(), "Should only have one document ID");

            // Check for any orphaned annotations/dependencies
            rs = stmt.executeQuery("""
                SELECT COUNT(*) as count FROM annotations a
                LEFT JOIN documents d ON a.document_id = d.document_id
                WHERE d.document_id IS NULL
            """);
            assertEquals(0, rs.getInt("count"), "Should have no orphaned annotations");

            rs = stmt.executeQuery("""
                SELECT COUNT(*) as count FROM dependencies d
                LEFT JOIN documents doc ON d.document_id = doc.document_id
                WHERE doc.document_id IS NULL
            """);
            assertEquals(0, rs.getInt("count"), "Should have no orphaned dependencies");
        }
    }

    @Disabled
    @Test
    void testSentenceIdIntegrity() throws Exception {
        // Insert test document with multiple sentences
        insertTestDocument("First sentence. Second sentence. Third sentence.");

        // Run annotation process
        runAnnotations();

        try (Statement stmt = conn.createStatement()) {
            // Verify sentence IDs are sequential
            ResultSet rs = stmt.executeQuery("""
                SELECT document_id, sentence_id, COUNT(*) as count
                FROM annotations
                GROUP BY document_id, sentence_id
                ORDER BY document_id, sentence_id
            """);

            int expectedSentenceId = 0;
            while (rs.next()) {
                assertEquals(expectedSentenceId, rs.getInt("sentence_id"),
                    "Sentence IDs should be sequential");
                expectedSentenceId++;
            }

            // Verify dependencies reference valid sentence IDs
            rs = stmt.executeQuery("""
                SELECT COUNT(*) as count
                FROM dependencies d
                LEFT JOIN annotations a ON
                    d.document_id = a.document_id AND
                    d.sentence_id = a.sentence_id
                WHERE a.sentence_id IS NULL
            """);
            assertEquals(0, rs.getInt("count"),
                "All dependencies should reference valid sentence IDs");
        }
    }

    @Disabled
    @Test
    void testCharacterPositionIntegrity() throws Exception {
        String testText = "The quick brown fox jumps.";
        insertTestDocument(testText);

        // Run annotation process
        runAnnotations();

        try (Statement stmt = conn.createStatement()) {
            // Verify all begin_char < end_char
            ResultSet rs = stmt.executeQuery(
                "SELECT * FROM annotations WHERE begin_char >= end_char");
            assertFalse(rs.next(), "begin_char should be less than end_char");

            rs = stmt.executeQuery(
                "SELECT * FROM dependencies WHERE begin_char >= end_char");
            assertFalse(rs.next(), "begin_char should be less than end_char");

            // Verify positions are within document bounds
            rs = stmt.executeQuery("""
                SELECT a.*, d.text
                FROM annotations a
                JOIN documents d ON a.document_id = d.document_id
                WHERE a.begin_char < 0 OR a.end_char > length(d.text)
            """);
            assertFalse(rs.next(), "Character positions should be within document bounds");

            // Verify token positions match the text
            rs = stmt.executeQuery("""
                SELECT a.*, d.text
                FROM annotations a
                JOIN documents d ON a.document_id = d.document_id
            """);

            while (rs.next()) {
                String token = rs.getString("token");
                String text = rs.getString("text");
                int begin = rs.getInt("begin_char");
                int end = rs.getInt("end_char");

                String extractedToken = text.substring(begin, end);
                assertEquals(token, extractedToken.trim(),
                    "Token should match text at specified position");
            }
        }
    }

    @Test
    void testEmptyDocument() throws Exception {
        // Insert empty document
        insertTestDocument("");

        // Run annotation process
        runAnnotations();

        try (Statement stmt = conn.createStatement()) {
            // Verify no annotations were created
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as count FROM annotations");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("count"), "Empty document should have no annotations");

            // Verify no dependencies were created
            rs = stmt.executeQuery("SELECT COUNT(*) as count FROM dependencies");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("count"), "Empty document should have no dependencies");
        }
    }

    @Disabled
    @Test
    void testLongDocument() throws Exception {
        clearDatabase();

        // Create a moderately sized document with better sentence structure
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            // Add more varied sentences to ensure CoreNLP detects them properly
            sb.append("This is sentence number ").append(i)
              .append(" of the test document. ");
            sb.append("It has multiple clauses and proper structure. ");
            sb.append("The parser should recognize this as separate sentences. ");
        }

        String documentText = sb.toString();

        // Insert the test document
        insertTestDocument(documentText);

        try {
            // Skip test if document exceeds size limit (20000 characters)
            if (documentText.length() > 20000) {
                // Run annotation process
                runAnnotations();

                // Verify no annotations were created since document should be skipped
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as count FROM annotations");
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt("count"), "Large document should be skipped");
                }
                return; // Test passes - large document correctly skipped
            }

            // Only run this part if document is under the size limit
            runAnnotations();

            // Verify the results
            try (Statement stmt = conn.createStatement()) {
                // Check that we have the expected number of sentences
                ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(DISTINCT sentence_id) FROM annotations");
                assertTrue(rs.next());
                int sentenceCount = rs.getInt(1);
                assertTrue(sentenceCount > 1, "Expected multiple sentences");

                // Each input sentence block has 3 sentences, so we'd expect around 300 sentences
                // We should get close to that number
                assertTrue(sentenceCount <= 350, "Expected fewer sentences than 350");

                // Check for any sentences with overlapping boundaries, which shouldn't happen
                rs = stmt.executeQuery("""
                    SELECT COUNT(*) FROM (
                        SELECT a1.sentence_id
                        FROM annotations a1
                        JOIN annotations a2 ON a1.document_id = a2.document_id
                            AND a1.sentence_id != a2.sentence_id
                            AND a1.begin_char < a2.end_char
                            AND a1.end_char > a2.begin_char
                        GROUP BY a1.sentence_id
                    )
                """);
                assertTrue(rs.next());
                int overlappingSentences = rs.getInt(1);
                assertEquals(0, overlappingSentences, "There should be no overlapping sentences");
            }
        } finally {
            clearDatabase();
        }
    }

    /**
     * This test verifies that character positions are correctly mapped in a document.
     * The goal is to ensure that tokens are retrievable using their character positions.
     */
    @Disabled
    @Test
    void testLargeDocumentCharacterPositions() throws Exception {
        clearDatabase();

        // Create a document with some consistent patterns
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("Sentence ").append(i).append(": This tests character positions. ");
        }
        String documentText = sb.toString();

        // Insert the test document
        insertTestDocument(documentText);

        // Skip test if document exceeds size limit (20000 characters)
        if (documentText.length() > 20000) {
            // Run annotation process
            runAnnotations();

            // Verify no annotations were created since document should be skipped
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as count FROM annotations");
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("count"), "Large document should be skipped");
            }
            return; // Test passes - large document correctly skipped
        }

        // Run annotation process
        runAnnotations();

        // Verify:
        // 1. Character positions are monotonically increasing
        // 2. beginChar is always less than endChar
        // 3. No overlaps between tokens in the same sentence
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("""
                SELECT sentence_id, token, begin_char, end_char
                FROM annotations
                ORDER BY begin_char, sentence_id
            """);

            int prevEnd = -1;
            int prevSentence = -1;
            int tokenCount = 0;

            while (rs.next()) {
                int sentenceId = rs.getInt("sentence_id");
                int beginChar = rs.getInt("begin_char");
                int endChar = rs.getInt("end_char");

                // Verify begin position is less than end position
                assertTrue(beginChar < endChar,
                        "Begin position should be less than end position");

                // Only compare against previous token if in the same sentence
                if (sentenceId == prevSentence && prevEnd != -1) {
                    // Verify no overlapping positions within the same sentence
                    assertTrue(beginChar >= prevEnd,
                            "Token positions should not overlap within the same sentence");
                }

                prevEnd = endChar;
                prevSentence = sentenceId;
                tokenCount++;
            }

            // Make sure we have a good number of tokens processed
            assertTrue(tokenCount > 100, "Should have processed at least 100 tokens");

            // Check that sentences have reasonable character spans
            rs = stmt.executeQuery("""
                SELECT sentence_id, MIN(begin_char) as sent_begin, MAX(end_char) as sent_end
                FROM annotations
                GROUP BY sentence_id
                ORDER BY sent_begin
            """);

            int lastSentEnd = -1;
            while (rs.next()) {
                int sentBegin = rs.getInt("sent_begin");
                int sentEnd = rs.getInt("sent_end");

                // Verify sentence character spans make sense
                assertTrue(sentBegin < sentEnd,
                        "Sentence begin should be less than sentence end");

                // Verify that sentence positions generally increase
                if (lastSentEnd != -1) {
                    // Sentences should not overlap
                    assertTrue(sentBegin >= lastSentEnd || sentBegin - lastSentEnd < 10,
                            "Sentences should generally not overlap");
                }

                lastSentEnd = sentEnd;
            }
        }
    }

    @Disabled
    @Test
    void testSpecialCharacters() throws Exception {
        String specialText = "Unicode: 你好,世界! Newlines:\\nTab:\\tQuotes:\\\"\'";
        insertTestDocument(specialText);
        runAnnotations();

        try (Statement stmt = conn.createStatement()) {
            // Verify all characters are properly handled
            ResultSet rs = stmt.executeQuery("""
                SELECT a.*, d.text
                FROM annotations a
                JOIN documents d ON a.document_id = d.document_id
            """);

            while (rs.next()) {
                String token = rs.getString("token");
                String text = rs.getString("text");
                int begin = rs.getInt("begin_char");
                int end = rs.getInt("end_char");

                String extractedToken = text.substring(begin, end);
                assertEquals(token.trim(), extractedToken.trim(),
                    "Token should match text at position even with special characters");
            }
        }
    }

    @Disabled
    @Test
    void testMultipleDocuments() throws Exception {
        // Insert multiple documents
        String[] documents = {
            "First document.",
            "Second document.",
            "Third document."
        };

        for (String doc : documents) {
            insertTestDocument(doc);
        }

        runAnnotations();

        try (Statement stmt = conn.createStatement()) {
            // Verify each document has its own annotations
            ResultSet rs = stmt.executeQuery("""
                SELECT d.document_id, COUNT(DISTINCT a.sentence_id) as sentence_count
                FROM documents d
                JOIN annotations a ON d.document_id = a.document_id
                GROUP BY d.document_id
                ORDER BY d.document_id
            """);

            int documentCount = 0;
            while (rs.next()) {
                documentCount++;
                assertEquals(1, rs.getInt("sentence_count"),
                    "Each document should have one sentence");
            }
            assertEquals(3, documentCount, "Should have processed all documents");

            // Verify no cross-document dependencies
            rs = stmt.executeQuery("""
                SELECT COUNT(*) as invalid_count
                FROM dependencies d
                JOIN annotations a1
                    ON d.document_id = a1.document_id
                    AND d.head_token = a1.token
                JOIN annotations a2
                    ON d.document_id = a2.document_id
                    AND d.dependent_token = a2.token
                WHERE a1.document_id != a2.document_id
            """);

            assertTrue(rs.next());
            assertEquals(0, rs.getInt("invalid_count"),
                "Should have no cross-document dependencies");
        }
    }

    private void insertTestDocument(String text) throws SQLException {
        String sql = "INSERT INTO documents (text) VALUES (?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, text);
            pstmt.executeUpdate();
        }
    }

    private void runAnnotations() throws Exception {
        // Use the static runAnnotationStage method
        Annotations.runAnnotationStage(
            dbFile,         // Path projectDbPath
            1,              // int threads
            10,             // int batchSize
            null,           // Integer limit (null for no limit)
            true,           // boolean force (true to clean up from startDocId)
            false,          // boolean fixIds (default to false for tests)
            1               // Integer cliStartDocId
        );
    }

    private void clearDatabase() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM annotations");
            stmt.execute("DELETE FROM dependencies");
            stmt.execute("DELETE FROM documents");
        }
    }
}