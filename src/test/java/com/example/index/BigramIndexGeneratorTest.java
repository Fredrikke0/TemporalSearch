package com.example.index;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.iq80.leveldb.Options;

import java.io.*;
import java.sql.*;
import java.nio.file.Path;
import java.util.Optional;
import com.example.logging.ProgressTracker;
import com.example.index.IndexConfig;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;

public class BigramIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-bigram.txt";
    private File indexBaseDir;
    private IndexAccess indexAccess;

    private static byte[] bytes(String str) {
        return str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

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

        // Set up index directory
        indexBaseDir = tempDir.resolve("test-index-bigram").toFile();
        if (indexBaseDir.exists()) {
            deleteDirectory(indexBaseDir);
        }
        indexBaseDir.mkdir();

        // Insert test data
        setupTestData();
    }

    private void setupTestData() throws SQLException {
        // Insert documents with timestamps
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?) ")) {
            pstmt.setInt(1, 1);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
            
            pstmt.setInt(1, 2);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        // Insert test sentences: (Removed lemma column index 5)
        // Doc 1: "The black cat sits quietly."
        // Doc 1: "It purrs softly."
        // Doc 2: "The black dog barks loudly."
        String[][] testWords = {
                // Document 1, Sentence 1
                { "1", "0", "0", "3", "The", "DET" },        // Removed "the"
                { "1", "0", "4", "9", "black", "ADJ" },       // Removed "black"
                { "1", "0", "10", "13", "cat", "NOUN" },      // Removed "cat"
                { "1", "0", "14", "18", "sits", "VERB" },     // Removed "sit"
                { "1", "0", "19", "26", "quietly", "ADV" },   // Removed "quietly"
                // Document 1, Sentence 2
                { "1", "1", "27", "29", "It", "PRON" },        // Removed "it"
                { "1", "1", "30", "35", "purrs", "VERB" },     // Removed "purr"
                { "1", "1", "36", "42", "softly", "ADV" },    // Removed "softly"
                // Document 2, Sentence 1
                { "2", "0", "0", "3", "The", "DET" },        // Removed "the"
                { "2", "0", "4", "9", "black", "ADJ" },       // Removed "black"
                { "2", "0", "10", "13", "dog", "NOUN" },      // Removed "dog"
                { "2", "0", "14", "19", "barks", "VERB" },     // Removed "bark"
                { "2", "0", "20", "26", "loudly", "ADV" }     // Removed "loudly"
        };

        // Updated INSERT statement to exclude lemma
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, pos) " +
                "VALUES (?, ?, ?, ?, ?, ?) ")) {
            for (String[] word : testWords) {
                pstmt.setInt(1, Integer.parseInt(word[0]));
                pstmt.setInt(2, Integer.parseInt(word[1]));
                pstmt.setInt(3, Integer.parseInt(word[2]));
                pstmt.setInt(4, Integer.parseInt(word[3]));
                pstmt.setString(5, word[4]); // Token
                pstmt.setString(6, word[5]); // POS
                pstmt.executeUpdate();
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        super.tearDown();
        if (indexAccess != null) {
            indexAccess.close();
        }
        new File(TEST_STOPWORDS_PATH).delete();
    }

    private void deleteDirectory(File dir) {
        if (dir.exists()) {
            for (File file : dir.listFiles()) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
            dir.delete();
        }
    }

    @Test
    public void testBasicIndexing() throws Exception {
        // Create and run bigram indexer
        try (BigramIndexGenerator indexer = new BigramIndexGenerator(
                indexBaseDir.getPath(), TEST_STOPWORDS_PATH, sqliteConn, new ProgressTracker())) {
            indexer.generateIndex();
        }

        // Create IndexAccess instance for verification
        Options options = new Options();
        indexAccess = new IndexAccess(indexBaseDir.toPath(), "bigram", options);

        // Test bigrams with stopwords (using tokens)
        verifyBigram("the" + IndexGenerator.DELIMITER + "black", 1, 0, 0, 9, 2); // Appears in both documents

        // Test regular bigrams (using tokens: "cat" + "sits", "sits" + "quietly")
        verifyBigram("black" + IndexGenerator.DELIMITER + "cat", 1, 0, 4, 13, 1);
        verifyBigram("cat" + IndexGenerator.DELIMITER + "sits", 1, 0, 10, 18, 1); // Use token "sits"
        verifyBigram("sits" + IndexGenerator.DELIMITER + "quietly", 1, 0, 14, 26, 1); // Use token "sits"

        // Test bigrams in second document (using tokens: "black" + "dog", "dog" + "barks")
        verifyBigram("black" + IndexGenerator.DELIMITER + "dog", 2, 0, 4, 13, 1);
        verifyBigram("dog" + IndexGenerator.DELIMITER + "barks", 2, 0, 10, 19, 1); // Use token "barks"
    }

    @Test
    public void testSentenceBoundaries() throws Exception {
        // Create and run bigram indexer
        try (BigramIndexGenerator indexer = new BigramIndexGenerator(
                indexBaseDir.getPath(), TEST_STOPWORDS_PATH, sqliteConn, new ProgressTracker())) {
            indexer.generateIndex();
        }

        // Create IndexAccess instance for verification
        Options options = new Options();
        indexAccess = new IndexAccess(indexBaseDir.toPath(), "bigram", options);

        // Verify no bigrams cross sentence boundaries (using tokens)
        Optional<PositionList> quietly = indexAccess.get(bytes("quietly" + IndexGenerator.DELIMITER + "it"));
        Optional<PositionList> softly = indexAccess.get(bytes("softly" + IndexGenerator.DELIMITER + "the"));
        assertTrue(quietly.isEmpty(), "Bigram should not cross sentence boundary");
        assertTrue(softly.isEmpty(), "Bigram should not cross sentence boundary");
    }

    private void verifyBigram(String bigram, int expectedDocId, int expectedSentenceId,
            int expectedBeginChar, int expectedEndChar, int expectedCount) throws IOException, IndexAccessException {
        Optional<PositionList> positions = indexAccess.get(bytes(bigram));
        assertTrue(positions.isPresent(), "Bigram '" + bigram + "' should be indexed");
        
        assertEquals(expectedCount, positions.get().size(),
                String.format("Bigram '%s' should appear %d time(s)", bigram, expectedCount));
        
        Position pos = positions.get().getPositions().get(0);
        assertEquals(expectedDocId, pos.getDocumentId());
        assertEquals(expectedSentenceId, pos.getSentenceId());
        assertEquals(expectedBeginChar, pos.getBeginPosition());
        assertEquals(expectedEndChar, pos.getEndPosition());
    }
} 