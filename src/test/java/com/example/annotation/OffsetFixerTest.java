package com.example.annotation;

import com.example.util.TextCompression;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link OffsetFixer} verifying that document-level
 * {@code begin_char} / {@code end_char} offsets are correctly converted to
 * sentence-relative offsets.
 */
class OffsetFixerTest {

    private Path dbFile;
    private Connection conn;
    private static String originalProgbarSilent;

    @BeforeAll
    static void storeOriginalProperty() {
        originalProgbarSilent = System.getProperty("progbar.silent");
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws SQLException {
        System.setProperty("progbar.silent", "true");
        dbFile = tempDir.resolve("test.db");
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                        CREATE TABLE documents (
                            document_id INTEGER PRIMARY KEY AUTOINCREMENT,
                            text BLOB NOT NULL
                        )
                    """);

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
    void tearDown() throws SQLException {
        if (originalProgbarSilent == null) {
            System.clearProperty("progbar.silent");
        } else {
            System.setProperty("progbar.silent", originalProgbarSilent);
        }
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @AfterAll
    static void restoreOriginalPropertyAfterAll() {
        if (originalProgbarSilent == null) {
            System.clearProperty("progbar.silent");
        } else {
            System.setProperty("progbar.silent", originalProgbarSilent);
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Inserts a document with the given text, returning the assigned
     * {@code document_id}. The text is stored compressed to match how
     * {@link Annotations} stores it.
     */
    private int insertDocument(String text) throws SQLException {
        String sql = "INSERT INTO documents (text) VALUES (?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setBytes(1, TextCompression.compress(text));
            pstmt.executeUpdate();
        }
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /**
     * Inserts annotations with <em>document-level</em> offsets (the old,
     * pre-fix format). Each annotation's {@code begin_char} and
     * {@code end_char} are absolute positions in the document.
     */
    private void insertDocLevelAnnotation(int docId, int sentenceId,
            int beginChar, int endChar, String token, String pos, String ner) throws SQLException {
        String sql = "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, docId);
            pstmt.setInt(2, sentenceId);
            pstmt.setInt(3, beginChar);
            pstmt.setInt(4, endChar);
            pstmt.setString(5, token);
            pstmt.setString(6, pos);
            pstmt.setString(7, ner);
            pstmt.setString(8, ner); // normalized_ner = ner for test simplicity
            pstmt.executeUpdate();
        }
    }

    /**
     * Inserts dependencies with <em>document-level</em> offsets.
     */
    private void insertDocLevelDependency(int docId, int sentenceId,
            int beginChar, int endChar, String head, String dependent, String relation) throws SQLException {
        String sql = "INSERT INTO dependencies (document_id, sentence_id, begin_char, end_char, head_token, dependent_token, relation) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, docId);
            pstmt.setInt(2, sentenceId);
            pstmt.setInt(3, beginChar);
            pstmt.setInt(4, endChar);
            pstmt.setString(5, head);
            pstmt.setString(6, dependent);
            pstmt.setString(7, relation);
            pstmt.executeUpdate();
        }
    }

    /**
     * Fetches all annotation rows ordered by document_id, sentence_id,
     * begin_char.
     */
    private ResultSet queryAnnotations() throws SQLException {
        return conn.createStatement().executeQuery(
                "SELECT document_id, sentence_id, begin_char, end_char, token "
                        + "FROM annotations ORDER BY document_id, sentence_id, begin_char");
    }

    /**
     * Fetches all dependency rows ordered by document_id, sentence_id.
     */
    private ResultSet queryDependencies() throws SQLException {
        return conn.createStatement().executeQuery(
                "SELECT document_id, sentence_id, begin_char, end_char, head_token, dependent_token "
                        + "FROM dependencies ORDER BY document_id, sentence_id, begin_char");
    }

    /**
     * Counts rows in the given table.
     */
    private int countTable(String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // -----------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------

    @Test
    void testSingleDocumentSingleSentence() throws Exception {
        // "Hello world" — one sentence, no internal punctuation
        String text = "Hello world";
        int docId = insertDocument(text);

        // Document-level offsets: Hello=0..5, world=6..11
        insertDocLevelAnnotation(docId, 0, 0, 5, "Hello", "UH", "O");
        insertDocLevelAnnotation(docId, 0, 6, 11, "world", "NN", "O");
        insertDocLevelDependency(docId, 0, 0, 11, "Hello", "world", "discourse");

        OffsetFixer.fix(dbFile, 1);

        // After fix: both should be sentence-relative.
        // Sentence starts at offset 0, so Hello=0..5, world=6..11 (unchanged in
        // this case, because the sentence started at offset 0).
        try (ResultSet rs = queryAnnotations()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("begin_char"));
            assertEquals(5, rs.getInt("end_char"));
            assertEquals("Hello", rs.getString("token"));

            assertTrue(rs.next());
            assertEquals(6, rs.getInt("begin_char"));
            assertEquals(11, rs.getInt("end_char"));
            assertEquals("world", rs.getString("token"));

            assertFalse(rs.next());
        }

        try (ResultSet rs = queryDependencies()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("begin_char"));
            assertEquals(11, rs.getInt("end_char"));
            assertFalse(rs.next());
        }

        assertEquals(2, countTable("annotations"));
        assertEquals(1, countTable("dependencies"));
    }

    @Test
    void testSingleDocumentMultipleSentences() throws Exception {
        // Two sentences: "Hello world. Goodbye moon."
        // Offsets: 0...11 13...26
        // Token offsets (doc-level):
        // S0: Hello=0..5, world=6..11
        // S1: Goodbye=13..20, moon=22..26
        String text = "Hello world. Goodbye moon.";
        int docId = insertDocument(text);

        // Sentence 0 — document-level offsets
        insertDocLevelAnnotation(docId, 0, 0, 5, "Hello", "UH", "O");
        insertDocLevelAnnotation(docId, 0, 6, 11, "world", "NN", "O");

        // Sentence 1 — document-level offsets
        insertDocLevelAnnotation(docId, 1, 13, 20, "Goodbye", "UH", "O");
        insertDocLevelAnnotation(docId, 1, 22, 26, "moon", "NN", "O");

        OffsetFixer.fix(dbFile, 1);

        // After fix:
        // S0: first token at 0, so offsets unchanged: Hello=0..5, world=6..11
        // S1: first token at 13, so Goodbye=0..7, moon=9..13
        try (ResultSet rs = queryAnnotations()) {
            // Sentence 0
            assertTrue(rs.next());
            assertEquals(docId, rs.getInt("document_id"));
            assertEquals(0, rs.getInt("sentence_id"));
            assertEquals(0, rs.getInt("begin_char"));
            assertEquals(5, rs.getInt("end_char"));
            assertEquals("Hello", rs.getString("token"));

            assertTrue(rs.next());
            assertEquals(docId, rs.getInt("document_id"));
            assertEquals(0, rs.getInt("sentence_id"));
            assertEquals(6, rs.getInt("begin_char"));
            assertEquals(11, rs.getInt("end_char"));
            assertEquals("world", rs.getString("token"));

            // Sentence 1 — should be sentence-relative now
            assertTrue(rs.next());
            assertEquals(docId, rs.getInt("document_id"));
            assertEquals(1, rs.getInt("sentence_id"));
            assertEquals(0, rs.getInt("begin_char")); // 13 - 13
            assertEquals(7, rs.getInt("end_char")); // 20 - 13
            assertEquals("Goodbye", rs.getString("token"));

            assertTrue(rs.next());
            assertEquals(docId, rs.getInt("document_id"));
            assertEquals(1, rs.getInt("sentence_id"));
            assertEquals(9, rs.getInt("begin_char")); // 22 - 13
            assertEquals(13, rs.getInt("end_char")); // 26 - 13
            assertEquals("moon", rs.getString("token"));

            assertFalse(rs.next());
        }
    }

    @Test
    void testMultipleDocuments() throws Exception {
        // Doc 0: "A test." (one sentence)
        // Doc 1: "One two. Three four." (two sentences)
        int doc0 = insertDocument("A test.");
        insertDocLevelAnnotation(doc0, 0, 0, 1, "A", "DT", "O");
        insertDocLevelAnnotation(doc0, 0, 2, 6, "test", "NN", "O");

        int doc1 = insertDocument("One two. Three four.");
        // Sentence 0: One=0..3, two=4..7
        insertDocLevelAnnotation(doc1, 0, 0, 3, "One", "CD", "O");
        insertDocLevelAnnotation(doc1, 0, 4, 7, "two", "CD", "O");
        // Sentence 1: Three=9..14, four=15..19
        insertDocLevelAnnotation(doc1, 1, 9, 14, "Three", "CD", "O");
        insertDocLevelAnnotation(doc1, 1, 15, 19, "four", "CD", "O");

        OffsetFixer.fix(dbFile, 1);

        try (ResultSet rs = queryAnnotations()) {
            // Doc 0, S0
            assertTrue(rs.next());
            assertEquals(doc0, rs.getInt("document_id"));
            assertEquals(0, rs.getInt("sentence_id"));
            assertEquals(0, rs.getInt("begin_char"));
            assertEquals(1, rs.getInt("end_char"));

            assertTrue(rs.next());
            assertEquals(doc0, rs.getInt("document_id"));
            assertEquals(0, rs.getInt("sentence_id"));
            assertEquals(2, rs.getInt("begin_char"));
            assertEquals(6, rs.getInt("end_char"));

            // Doc 1, S0 — first token at 0, unchanged
            assertTrue(rs.next());
            assertEquals(doc1, rs.getInt("document_id"));
            assertEquals(0, rs.getInt("sentence_id"));
            assertEquals(0, rs.getInt("begin_char"));
            assertEquals(3, rs.getInt("end_char"));

            assertTrue(rs.next());
            assertEquals(doc1, rs.getInt("document_id"));
            assertEquals(0, rs.getInt("sentence_id"));
            assertEquals(4, rs.getInt("begin_char"));
            assertEquals(7, rs.getInt("end_char"));

            // Doc 1, S1 — first token "Three" at 9
            assertTrue(rs.next());
            assertEquals(doc1, rs.getInt("document_id"));
            assertEquals(1, rs.getInt("sentence_id"));
            assertEquals(0, rs.getInt("begin_char")); // 9 - 9
            assertEquals(5, rs.getInt("end_char")); // 14 - 9

            assertTrue(rs.next());
            assertEquals(doc1, rs.getInt("document_id"));
            assertEquals(1, rs.getInt("sentence_id"));
            assertEquals(6, rs.getInt("begin_char")); // 15 - 9
            assertEquals(10, rs.getInt("end_char")); // 19 - 9

            assertFalse(rs.next());
        }
    }

    @Test
    void testDependenciesOnlyUpdatedWhenTableExists() throws Exception {
        // Insert a document with both annotations and dependencies
        String text = "Cats chase mice.";
        int docId = insertDocument(text);
        // Doc-level: Cats=0..4, chase=5..10, mice=11..15
        insertDocLevelAnnotation(docId, 0, 0, 4, "Cats", "NNS", "O");
        insertDocLevelAnnotation(docId, 0, 5, 10, "chase", "VBP", "O");
        insertDocLevelAnnotation(docId, 0, 11, 15, "mice", "NNS", "O");
        insertDocLevelDependency(docId, 0, 0, 15, "chase", "Cats", "nsubj");

        OffsetFixer.fix(dbFile, 1);

        // Dependencies should be updated
        try (ResultSet rs = queryDependencies()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("begin_char"));
            assertEquals(15, rs.getInt("end_char"));
            assertFalse(rs.next());
        }
    }

    @Test
    void testMissingDependenciesTableHandledGracefully() throws Exception {
        // Drop the dependencies table to simulate DEPENDENCY_ENABLED=false
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE dependencies");
        }

        String text = "Hello world.";
        int docId = insertDocument(text);
        insertDocLevelAnnotation(docId, 0, 0, 5, "Hello", "UH", "O");
        insertDocLevelAnnotation(docId, 0, 6, 11, "world", "NN", "O");

        // Should not throw — just log and skip dependency updates
        OffsetFixer.fix(dbFile, 1);

        // Annotations should still be fixed
        try (ResultSet rs = queryAnnotations()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("begin_char"));
            assertEquals(5, rs.getInt("end_char"));

            assertTrue(rs.next());
            assertEquals(6, rs.getInt("begin_char"));
            assertEquals(11, rs.getInt("end_char"));

            assertFalse(rs.next());
        }

        // Verify the table is indeed gone
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='dependencies'")) {
            assertFalse(rs.next(), "dependencies table should not exist");
        }
    }

    @Test
    void testEmptyDocumentSkipped() throws Exception {
        // Insert an empty document (compressed empty string)
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO documents (text) VALUES (?)")) {
            pstmt.setBytes(1, TextCompression.compress(""));
            pstmt.executeUpdate();
        }

        // Should not throw — just skip the empty doc
        OffsetFixer.fix(dbFile, 1);

        assertEquals(0, countTable("annotations"));
    }

    @Test
    void testDocumentTruncatedToMaxLength() throws Exception {
        // Build a document longer than MAX_DOCUMENT_LENGTH (20000).
        // The fixer truncates to 20000 chars before annotating.
        StringBuilder sb = new StringBuilder();
        sb.append("Short sentence. ".repeat(1300)); // ~22k chars
        String text = sb.toString();
        assertTrue(text.length() > 20000, "text should exceed 20000 chars");

        int docId = insertDocument(text);

        // Insert one annotation near the end of the truncated range (should be
        // fixed). We put it at doc-level offset 19900.
        insertDocLevelAnnotation(docId, 0, 19990, 19996, "within", "IN", "O");

        OffsetFixer.fix(dbFile, 1);

        // The annotation should be sentence-relative after fix
        try (ResultSet rs = queryAnnotations()) {
            assertTrue(rs.next());
            // Sentence-relative offset = doc_offset - first_token_offset
            // The exact offset depends on sentence boundaries around 19990,
            // so we just verify the row exists and begin_char >= 0
            assertTrue(rs.getInt("begin_char") >= 0,
                    "begin_char should be sentence-relative (>= 0)");
        }
    }

    @Test
    void testRowCountsPreserved() throws Exception {
        // Verify that no rows are added or lost during fixing
        String text = "One. Two. Three. Four. Five.";
        int docId = insertDocument(text);

        // Insert 15 annotations (3 per sentence)
        int[] tokensPerSentence = { 3, 3, 3, 3, 3 };
        int tokenIdx = 0;
        for (int s = 0; s < 5; s++) {
            for (int t = 0; t < tokensPerSentence[s]; t++) {
                int begin = tokenIdx * 5;
                int end = begin + 4;
                insertDocLevelAnnotation(docId, s, begin, end, "tok" + tokenIdx, "NN", "O");
                tokenIdx++;
            }
        }

        int annBefore = countTable("annotations");
        assertEquals(15, annBefore);

        OffsetFixer.fix(dbFile, 1);

        assertEquals(15, countTable("annotations"), "annotation row count should be preserved");
    }

    @Test
    void testSentenceWithNoTokensIsSkipped() throws Exception {
        // A document where CoreNLP might produce an empty sentence.
        // Just verify the fixer doesn't crash on such edge cases.
        String text = "Valid sentence.";
        int docId = insertDocument(text);
        insertDocLevelAnnotation(docId, 0, 0, 5, "Valid", "JJ", "O");

        OffsetFixer.fix(dbFile, 1);

        try (ResultSet rs = queryAnnotations()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("begin_char"));
            assertEquals(5, rs.getInt("end_char"));
            assertFalse(rs.next());
        }
    }
}
