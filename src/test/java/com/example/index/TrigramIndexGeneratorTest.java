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
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 1);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
            
            pstmt.setInt(1, 2);
            pstmt.setString(2, "2024-01-28");
            pstmt.executeUpdate();
        }

        // Insert test sentences:
        // Doc 1: "The black cat sits quietly."
        // Doc 1: "It purrs softly."
        // Doc 2: "The black dog barks loudly."
        String[][] testWords = {
                // Document 1, Sentence 1
                { "1", "0", "0", "3", "The", "the", "DET" },
                { "1", "0", "4", "9", "black", "black", "ADJ" },
                { "1", "0", "10", "13", "cat", "cat", "NOUN" },
                { "1", "0", "14", "18", "sits", "sit", "VERB" },
                { "1", "0", "19", "26", "quietly", "quietly", "ADV" },
                { "1", "0", "27", "30", "now", "now", "ADV" },
                // Document 1, Sentence 2
                { "1", "1", "31", "33", "It", "it", "PRON" },
                { "1", "1", "34", "39", "purrs", "purr", "VERB" },
                { "1", "1", "40", "44", "very", "very", "ADV" },
                { "1", "1", "45", "51", "softly", "softly", "ADV" },
                { "1", "1", "52", "57", "today", "today", "ADV" },
                // Document 2, Sentence 1
                { "2", "0", "0", "3", "The", "the", "DET" },
                { "2", "0", "4", "9", "black", "black", "ADJ" },
                { "2", "0", "10", "13", "cat", "cat", "NOUN" },
                { "2", "0", "14", "18", "runs", "run", "VERB" },
                { "2", "0", "19", "26", "quickly", "quickly", "ADV" },
                { "2", "0", "27", "31", "away", "away", "ADV" }
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

    private void setupPunctuationTestData() throws SQLException {
        // Insert document 3
        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO documents (document_id, timestamp) VALUES (?, ?)")) {
            pstmt.setInt(1, 3);
            pstmt.setString(2, "2024-01-29");
            pstmt.executeUpdate();
        }

        // Insert test sentence for Doc 3: "Anne Waldman ( born 1945 ) ."
        String[][] punctuationWords = {
                // Document 3, Sentence 0
                { "3", "0", "0", "4", "Anne", "anne", "PROPN" },
                { "3", "0", "5", "12", "Waldman", "waldman", "PROPN" },
                { "3", "0", "13", "14", "(", "(", "PUNCT" }, // Punctuation
                { "3", "0", "15", "19", "born", "born", "VERB" },
                { "3", "0", "20", "24", "1945", "1945", "NUM" },
                { "3", "0", "25", "26", ")", ")", "PUNCT" }, // Punctuation
                { "3", "0", "27", "28", ".", ".", "PUNCT" }  // Punctuation
        };

        try (PreparedStatement pstmt = sqliteConn.prepareStatement(
                "INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, lemma, pos) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] word : punctuationWords) {
                pstmt.setInt(1, Integer.parseInt(word[0]));
                pstmt.setInt(2, Integer.parseInt(word[1]));
                pstmt.setInt(3, Integer.parseInt(word[2]));
                pstmt.setInt(4, Integer.parseInt(word[3]));
                pstmt.setString(5, word[4]);
                pstmt.setString(6, word[5]); // Use lemma for indexing
                pstmt.setString(7, word[6]);
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
                indexBaseDir.getPath(), TEST_STOPWORDS_PATH, sqliteConn, new ProgressTracker())) {
            indexer.generateIndex();
        }

        // Create IndexAccess instance for verification
        Options options = new Options();
        indexAccess = new IndexAccess(indexBaseDir.toPath(), "trigram", options);

        // Test trigrams with stopwords
        verifyTrigram("the" + IndexGenerator.DELIMITER + "black" + 
            IndexGenerator.DELIMITER + "cat", 1, 0, 0, 13, 2);

        // Test regular trigrams in first document
        verifyTrigram("black" + IndexGenerator.DELIMITER + "cat" + 
            IndexGenerator.DELIMITER + "sit", 1, 0, 4, 18, 1);
        verifyTrigram("cat" + IndexGenerator.DELIMITER + "sit" + 
            IndexGenerator.DELIMITER + "quietly", 1, 0, 10, 26, 1);
        verifyTrigram("sit" + IndexGenerator.DELIMITER + "quietly" + 
            IndexGenerator.DELIMITER + "now", 1, 0, 14, 30, 1);
    }

    @Test
    public void testSentenceBoundaries() throws Exception {
        // Create and run trigram indexer
        try (TrigramIndexGenerator indexer = new TrigramIndexGenerator(
                indexBaseDir.getPath(), TEST_STOPWORDS_PATH, sqliteConn, new ProgressTracker())) {
            indexer.generateIndex();
        }

        // Create IndexAccess instance for verification
        Options options = new Options();
        indexAccess = new IndexAccess(indexBaseDir.toPath(), "trigram", options);

        // Verify no trigrams cross sentence boundaries
        Optional<PositionList> quietly = indexAccess.get(bytes("quietly" + IndexGenerator.DELIMITER + "now" + 
            IndexGenerator.DELIMITER + "it"));
        Optional<PositionList> now = indexAccess.get(bytes("now" + IndexGenerator.DELIMITER + "it" + 
            IndexGenerator.DELIMITER + "purr"));
        assertTrue(quietly.isEmpty(), "Trigram should not cross sentence boundary");
        assertTrue(now.isEmpty(), "Trigram should not cross sentence boundary");
    }

    @Test
    public void testTrigramWithPunctuationSkipping() throws Exception {
        // Add specific data for this test
        setupPunctuationTestData();

        // Create and run trigram indexer
        try (TrigramIndexGenerator indexer = new TrigramIndexGenerator(
                indexBaseDir.getPath(), TEST_STOPWORDS_PATH, sqliteConn, new ProgressTracker())) {
            indexer.generateIndex();
        }

        // Create IndexAccess instance for verification
        Options options = new Options();
        indexAccess = new IndexAccess(indexBaseDir.toPath(), "trigram", options);

        // Expected trigrams (skipping punctuation)
        // "Anne Waldman born" -> spans from 'Anne' start to 'born' end
        verifyTrigram("anne" + IndexGenerator.DELIMITER + "waldman" +
            IndexGenerator.DELIMITER + "born", 3, 0, 0, 19, 1);
        // "Waldman born 1945" -> spans from 'Waldman' start to '1945' end
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

    @Test
    public void testTrigramIndexing() throws Exception {
        // ... existing code ...
    }
} 