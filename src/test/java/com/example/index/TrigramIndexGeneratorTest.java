package com.example.index;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.iq80.leveldb.Options;

import java.io.*;
import java.sql.*;
import java.nio.file.Path;
import java.util.Optional;
import com.example.logging.ProgressTracker;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import java.util.List;
import java.util.stream.Collectors;

public class TrigramIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-trigram.txt";
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
        indexBaseDir = tempDir.resolve("test-index-trigram").toFile();
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
        // Doc 1: "The black cat sits quietly now."
        // Doc 1: "It purrs very softly today."
        // Doc 2: "The black cat runs quickly away."
        String[][] testWords = {
                // Document 1, Sentence 1
                { "1", "0", "0", "3", "The", "DET" },         // Removed "the"
                { "1", "0", "4", "9", "black", "ADJ" },       // Removed "black"
                { "1", "0", "10", "13", "cat", "NOUN" },      // Removed "cat"
                { "1", "0", "14", "18", "sits", "VERB" },     // Removed "sit"
                { "1", "0", "19", "26", "quietly", "ADV" },   // Removed "quietly"
                { "1", "0", "27", "30", "now", "ADV" },       // Removed "now"
                // Document 1, Sentence 2
                { "1", "1", "31", "33", "It", "PRON" },        // Removed "it"
                { "1", "1", "34", "39", "purrs", "VERB" },     // Removed "purr"
                { "1", "1", "40", "44", "very", "ADV" },       // Removed "very"
                { "1", "1", "45", "51", "softly", "ADV" },    // Removed "softly"
                { "1", "1", "52", "57", "today", "ADV" },     // Removed "today"
                // Document 2, Sentence 1
                { "2", "0", "0", "3", "The", "DET" },         // Removed "the"
                { "2", "0", "4", "9", "black", "ADJ" },       // Removed "black"
                { "2", "0", "10", "13", "cat", "NOUN" },      // Removed "cat"
                { "2", "0", "14", "18", "runs", "VERB" },     // Removed "run"
                { "2", "0", "19", "26", "quickly", "ADV" },   // Removed "quickly"
                { "2", "0", "27", "31", "away", "ADV" }       // Removed "away"
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

    private void setupPunctuationTestData() throws SQLException {
        // Insert document 3
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?) ")) {
            pstmt.setInt(1, 3);
            pstmt.setString(2, "2024-01-29");
            pstmt.executeUpdate();
        }

        // Insert test sentence for Doc 3: (Removed lemma column index 5)
        // "Anne Waldman ( born 1945 ) ."
        String[][] punctuationWords = {
                // Document 3, Sentence 0
                { "3", "0", "0", "4", "Anne", "PROPN" },     // Removed "anne"
                { "3", "0", "5", "12", "Waldman", "PROPN" },  // Removed "waldman"
                { "3", "0", "13", "14", "(", "PUNCT" },      // Removed "("
                { "3", "0", "15", "19", "born", "VERB" },     // Removed "born"
                { "3", "0", "20", "24", "1945", "NUM" },      // Removed "1945"
                { "3", "0", "25", "26", ")", "PUNCT" },      // Removed ")"
                { "3", "0", "27", "28", ".", "PUNCT" }       // Removed "."
        };

        // Updated INSERT statement to exclude lemma
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, pos) " +
                "VALUES (?, ?, ?, ?, ?, ?) ")) {
            for (String[] word : punctuationWords) {
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
    public void testBasicTrigramIndexing() throws Exception {
        // Create and run trigram indexer
        try (TrigramIndexGenerator indexer = new TrigramIndexGenerator(
                indexBaseDir.getPath(), TEST_STOPWORDS_PATH, sqliteConn, new ProgressTracker(), 1000)) {
            indexer.generateIndex();
        }

        // Create IndexAccess instance for verification
        Options options = new Options();
        indexAccess = new IndexAccess(indexBaseDir.toPath(), "trigram", options);

        // Test trigrams with stopwords (using tokens)
        verifyTrigram("the" + IndexGenerator.DELIMITER + "black" + 
            IndexGenerator.DELIMITER + "cat", 1, 0, 0, 13, 2);

        // Test regular trigrams in first document (using tokens: "black" + "cat" + "sits", etc.)
        verifyTrigram("black" + IndexGenerator.DELIMITER + "cat" + 
            IndexGenerator.DELIMITER + "sits", 1, 0, 4, 18, 1); // Use token "sits"
        verifyTrigram("cat" + IndexGenerator.DELIMITER + "sits" + 
            IndexGenerator.DELIMITER + "quietly", 1, 0, 10, 26, 1); // Use token "sits"
        verifyTrigram("sits" + IndexGenerator.DELIMITER + "quietly" + 
            IndexGenerator.DELIMITER + "now", 1, 0, 14, 30, 1); // Use token "sits"
    }

    @Test
    public void testSentenceBoundaries() throws Exception {
        // Create and run trigram indexer
        try (TrigramIndexGenerator indexer = new TrigramIndexGenerator(
                indexBaseDir.getPath(), TEST_STOPWORDS_PATH, sqliteConn, new ProgressTracker(), 1000)) {
            indexer.generateIndex();
        }

        // Create IndexAccess instance for verification
        Options options = new Options();
        indexAccess = new IndexAccess(indexBaseDir.toPath(), "trigram", options);

        // Verify no trigrams cross sentence boundaries (using tokens)
        Optional<PositionList> quietly = indexAccess.get(bytes("quietly" + IndexGenerator.DELIMITER + "now" + 
            IndexGenerator.DELIMITER + "it"));
        Optional<PositionList> now = indexAccess.get(bytes("now" + IndexGenerator.DELIMITER + "it" + 
            IndexGenerator.DELIMITER + "purrs")); // Use token "purrs"
        assertTrue(quietly.isEmpty(), "Trigram should not cross sentence boundary");
        assertTrue(now.isEmpty(), "Trigram should not cross sentence boundary");
    }

    @Test
    public void testTrigramWithPunctuationSkipping() throws Exception {
        // Add specific data for this test
        setupPunctuationTestData();

        // Create and run trigram indexer
        try (TrigramIndexGenerator indexer = new TrigramIndexGenerator(
                indexBaseDir.getPath(), TEST_STOPWORDS_PATH, sqliteConn, new ProgressTracker(), 1000)) {
            indexer.generateIndex();
        }

        // Create IndexAccess instance for verification
        Options options = new Options();
        indexAccess = new IndexAccess(indexBaseDir.toPath(), "trigram", options);

        // Expected trigrams (using tokens)
        // "Anne Waldman born" 
        verifyTrigram("anne" + IndexGenerator.DELIMITER + "waldman" +
            IndexGenerator.DELIMITER + "born", 3, 0, 0, 19, 1);
        // "Waldman born 1945" 
        verifyTrigram("waldman" + IndexGenerator.DELIMITER + "born" +
            IndexGenerator.DELIMITER + "1945", 3, 0, 5, 24, 1);

        // Verify trigrams *including* punctuation are NOT present
        verifyTrigramAbsent("waldman" + IndexGenerator.DELIMITER + "(" +
            IndexGenerator.DELIMITER + "born", "Trigram should skip '('");
        verifyTrigramAbsent("(" + IndexGenerator.DELIMITER + "born" +
            IndexGenerator.DELIMITER + "1945", "Trigram should skip '('");
        verifyTrigramAbsent("born" + IndexGenerator.DELIMITER + "1945" +
            IndexGenerator.DELIMITER + ")", "Trigram should skip ')'");
        verifyTrigramAbsent("1945" + IndexGenerator.DELIMITER + ")" +
            IndexGenerator.DELIMITER + ".", "Trigram should skip ')' and '.'");

    }

    private void verifyTrigram(String trigram, int expectedDocId, int expectedSentenceId,
            int expectedBeginChar, int expectedEndChar, int expectedCount) throws IOException, IndexAccessException {
        Optional<PositionList> positions = indexAccess.get(bytes(trigram));
        assertTrue(positions.isPresent(), "Trigram '" + trigram + "' should be indexed");
        
        assertEquals(expectedCount, positions.get().size(),
                String.format("Trigram '%s' should appear %d time(s)", trigram, expectedCount));
        
        Position pos = positions.get().getPositions().get(0);
        assertEquals(expectedDocId, pos.getDocumentId());
        assertEquals(expectedSentenceId, pos.getSentenceId());
        assertEquals(expectedBeginChar, pos.getBeginPosition());
        assertEquals(expectedEndChar, pos.getEndPosition());
    }

    private void verifyTrigramAbsent(String trigram, String message) throws IOException, IndexAccessException {
        Optional<PositionList> positions = indexAccess.get(bytes(trigram));
        assertTrue(positions.isEmpty(), message + " - Found unexpected trigram: '" + trigram + "'");
    }
} 