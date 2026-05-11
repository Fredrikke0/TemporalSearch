package com.example.index.generators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rocksdb.Options;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.PostingList;
import com.example.logging.ProgressTracker;

public class BigramIndexGeneratorTest extends BaseIndexTest {
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-bigram.txt";
    private File indexBaseDir;
    // private IndexAccess indexAccess; // Field might be redundant if tests manage
    // IA locally

    private static byte[] bytes(String str) {
        return str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

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
                { "1", "0", "0", "3", "The", "DET" }, // Removed "the"
                { "1", "0", "4", "9", "black", "ADJ" }, // Removed "black"
                { "1", "0", "10", "13", "cat", "NOUN" }, // Removed "cat"
                { "1", "0", "14", "18", "sits", "VERB" }, // Removed "sit"
                { "1", "0", "19", "26", "quietly", "ADV" }, // Removed "quietly"
                // Document 1, Sentence 2
                { "1", "1", "27", "29", "It", "PRON" }, // Removed "it"
                { "1", "1", "30", "35", "purrs", "VERB" }, // Removed "purr"
                { "1", "1", "36", "42", "softly", "ADV" }, // Removed "softly"
                // Document 2, Sentence 1
                { "2", "0", "0", "3", "The", "DET" }, // Removed "the"
                { "2", "0", "4", "9", "black", "ADJ" }, // Removed "black"
                { "2", "0", "10", "13", "dog", "NOUN" }, // Removed "dog"
                { "2", "0", "14", "19", "barks", "VERB" }, // Removed "bark"
                { "2", "0", "20", "26", "loudly", "ADV" } // Removed "loudly"
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
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        // if (indexAccess != null) { // If field is removed, this is no longer needed
        // or needs adjustment
        // indexAccess.close();
        // }
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
        // Create IndexAccess instance first
        try (Options options = createTestOptions();
                IndexAccess ia = new IndexAccess(indexBaseDir.toPath(), "bigram", options, false)) {
            // Create and run bigram indexer, passing the IndexAccess instance
            try (BigramIndexGenerator indexer = new BigramIndexGenerator(
                    ia, TEST_STOPWORDS_PATH, sqliteConn, new ProgressTracker(), 1000)) {
                indexer.generateIndex();
            }

            // Use the same IndexAccess instance for verification, no need to re-create
            // 'options' or 'indexAccess'
            // this.indexAccess = ia; // Assign to class field if verifyBigram uses it, or
            // pass 'ia' directly - REMOVED

            // Test regular bigrams (stopwords are filtered out before bigram creation)
            // Doc 1, Sent 0: "black", "cat", "sits", "quietly"
            verifyBigram(ia, "black" + IndexGenerator.DELIMITER + "cat", 1, 0, 4, 13, 1);
            verifyBigram(ia, "cat" + IndexGenerator.DELIMITER + "sits", 1, 0, 10, 18, 1);
            verifyBigram(ia, "sits" + IndexGenerator.DELIMITER + "quietly", 1, 0, 14, 26, 1);

            // Doc 1, Sent 1: "purrs", "softly" (original "It purrs softly", "it" is a
            // stopword)
            // Stopwords file for test includes "a, is, the". Assuming "it" is NOT a
            // stopword based on default stopwords.txt
            // If "it" IS a stopword for this test's specific stopwords file, then "purrs"
            // would be the first token.
            // Let's check the test stopwords file content in setUp.
            // setUp creates TEST_STOPWORDS_PATH with "the", "a", "is". So "it" is NOT a
            // stopword.
            // Therefore, the sequence after filtering is "it", "purrs", "softly".
            // Expected bigrams: "it purrs", "purrs softly"

            // The test data for annotations: { "1", "1", "27", "29", "It", "PRON" }, { "1",
            // "1", "30", "35", "purrs", "VERB" }, { "1", "1", "36", "42", "softly", "ADV" }
            // Tokens: "It", "purrs", "softly"
            // Lowercased: "it", "purrs", "softly"
            // None are in TEST_STOPWORDS_PATH ("the", "a", "is")
            // Filtered (no letter/digit check will not remove these): "it", "purrs",
            // "softly"
            // Bigrams: "it purrs", "purrs softly"
            verifyBigram(ia, "it" + IndexGenerator.DELIMITER + "purrs", 1, 1, 27, 35, 1);
            verifyBigram(ia, "purrs" + IndexGenerator.DELIMITER + "softly", 1, 1, 30, 42, 1);

            // Doc 2, Sent 0: "black", "dog", "barks", "loudly"
            verifyBigram(ia, "black" + IndexGenerator.DELIMITER + "dog", 2, 0, 4, 13, 1);
            verifyBigram(ia, "dog" + IndexGenerator.DELIMITER + "barks", 2, 0, 10, 19, 1);
            verifyBigram(ia, "barks" + IndexGenerator.DELIMITER + "loudly", 2, 0, 14, 26, 1);
        }
    }

    @Test
    public void testSentenceBoundaries() throws Exception {
        // Create IndexAccess instance first
        try (Options options = createTestOptions();
                IndexAccess ia = new IndexAccess(indexBaseDir.toPath(), "bigram", options, false)) {
            // Create and run bigram indexer, passing the IndexAccess instance
            try (BigramIndexGenerator indexer = new BigramIndexGenerator(
                    ia, TEST_STOPWORDS_PATH, sqliteConn, new ProgressTracker(), 1000)) {
                indexer.generateIndex();
            }

            // Use the same IndexAccess instance for verification
            // this.indexAccess = ia; // REMOVED

            // Verify no bigrams cross sentence boundaries (using tokens)
            Optional<PostingList> quietly = ia.get(bytes("quietly" + IndexGenerator.DELIMITER + "it"));
            Optional<PostingList> softly = ia.get(bytes("softly" + IndexGenerator.DELIMITER + "the"));
            assertTrue(quietly.isEmpty(), "Bigram should not cross sentence boundary");
            assertTrue(softly.isEmpty(), "Bigram should not cross sentence boundary");
        }
    }

    private void verifyBigram(IndexAccess indexAccess, String bigram, int expectedDocId, int expectedSentenceId,
            int expectedBeginChar, int expectedEndChar, int expectedCount) throws IOException, IndexAccessException {
        // Ensure indexAccess is not null if it's used here and set in test methods
        assertNotNull(indexAccess, "IndexAccess instance must be provided for verification.");
        Optional<PostingList> plOpt = indexAccess.get(bytes(bigram));
        assertTrue(plOpt.isPresent(), "Bigram '" + bigram + "' should be indexed");

        PostingList pl = plOpt.get();
        assertEquals(expectedCount, pl.cells().getLongCardinality(),
                String.format("Bigram '%s' should appear %d time(s)", bigram, expectedCount));

        // Verify first cell
        long[] cellArr = pl.cells().toArray();
        long firstCell = cellArr[0];
        assertEquals(expectedDocId, PostingList.docIdFromCellKey(firstCell));
        assertEquals(expectedSentenceId, PostingList.sentIdFromCellKey(firstCell));
    }
}
