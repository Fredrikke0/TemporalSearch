package com.example.index.generators;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyLong;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.logging.ProgressTracker;
import com.example.core.IndexAccess;
import com.example.core.PositionList;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationEntry;
import com.example.index.generators.UnigramIndexGenerator;

import org.iq80.leveldb.Options;

import java.io.File;
import java.io.PrintWriter;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class UnigramIndexGeneratorTest extends BaseIndexTest {
    private static final Logger logger = LoggerFactory.getLogger(UnigramIndexGeneratorTest.class);
    private static final String TEST_STOPWORDS_PATH = "test-stopwords-unigram.txt";
    private Path stopwordsPath;
    private UnigramIndexGenerator generator;
    private IndexAccess indexAccess;

    @Mock
    private ProgressTracker mockProgressTracker;

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private static final int LARGE_BATCH_SIZE = 10_000;
    private static final Random random = new Random(42); // Fixed seed for reproducibility

    @BeforeEach
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // Create stopwords file for this test class
        stopwordsPath = tempDir.resolve(TEST_STOPWORDS_PATH);
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(stopwordsPath))) {
            writer.println("the");
            writer.println("a");
            writer.println("is");
        }

        // Set up index directory is NOT needed here, IndexGenerator handles it.
        // Path unigramIndexDir = indexBaseDir.resolve("unigram");

        // Create generator
        generator = new UnigramIndexGenerator(
                indexBaseDir.toString(), // Pass the actual base directory for all indexes
                stopwordsPath.toString(),
                sqliteConn,
                mockProgressTracker,
                1000,
                null
        );
        setupTestData();
    }

    @AfterEach
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (indexAccess != null) {
            indexAccess.close();
        }
        new File(TEST_STOPWORDS_PATH).delete(); //This might be redundant if super.tearDown deletes tempDir which contains this file.
                                                //However, specific test stopwords file path is used in this class.
    }

    private void insertBasicTestData() throws SQLException {
        try (Statement stmt = sqliteConn.createStatement()) {
            stmt.execute("INSERT INTO documents (document_id, timestamp) VALUES (1, '2024-03-20')");
            stmt.execute("INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, pos) " +
                        "VALUES (1, 1, 0, 4, 'test', 'NN')");
            stmt.execute("INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token, pos) " +
                        "VALUES (1, 1, 5, 9, 'word', 'NN')");
        }
    }

    @Test
    void testFetchBatch() throws SQLException {
        // Insert test data
        insertBasicTestData();

        // Fetch batch
        List<AnnotationEntry> entries = generator.fetchBatch(null);

        // Verify results
        assertEquals(2, entries.size());

        AnnotationEntry first = entries.get(0);
        assertEquals(1, first.getDocumentId());
        assertEquals(1, first.getSentenceId());
        assertEquals(0, first.getBeginChar());
        assertEquals(4, first.getEndChar());
        assertEquals("test", first.getToken());
        assertEquals("NN", first.getPos());

        AnnotationEntry second = entries.get(1);
        assertEquals(1, second.getDocumentId());
        assertEquals(1, second.getSentenceId());
        assertEquals(5, second.getBeginChar());
        assertEquals(9, second.getEndChar());
        assertEquals("word", second.getToken());
        assertEquals("NN", second.getPos());
    }

    @Test
    void testProcessBatch() throws IOException {
        // Create test entries
        List<AnnotationEntry> batch = List.of(
            new AnnotationEntry(1, 1, 1, 0, 4, "Test", "NN", null, null, "test"),
            new AnnotationEntry(2, 1, 1, 5, 9, "word", "NN", null, null, "word"),
            new AnnotationEntry(3, 1, 1, 10, 13, "the", "DT", null, null, "the") // stopword
        );

        // Process batch
        var result = generator.processBatch(batch);

        // Verify results
        assertEquals(2, result.keySet().size());
        assertTrue(result.containsKey("test"));
        assertTrue(result.containsKey("word"));
        assertFalse(result.containsKey("the")); // stopword should be filtered

        // Verify positions
        var testPositions = result.get("test").get(0);
        assertEquals(1, testPositions.getNumPositions());
        assertEquals(0, testPositions.getPositionAt(0).getBeginPosition());
        assertEquals(4, testPositions.getPositionAt(0).getEndPosition());

        var wordPositions = result.get("word").get(0);
        assertEquals(1, wordPositions.getNumPositions());
        assertEquals(5, wordPositions.getPositionAt(0).getBeginPosition());
        assertEquals(9, wordPositions.getPositionAt(0).getEndPosition());
    }

    @Test
    void testProcessBatchWithOverlaps() throws IOException {
        // Create test entries with overlapping and adjacent positions
        List<AnnotationEntry> batch = List.of(
            new AnnotationEntry(1, 1, 1, 0, 4, "test", "NN", null, null, "test"),
            new AnnotationEntry(2, 1, 1, 2, 6, "test", "NN", null, null, "test"), // overlaps first "test"
            new AnnotationEntry(3, 1, 1, 10, 14, "word", "NN", null, null, "word"),
            new AnnotationEntry(4, 1, 1, 15, 19, "word", "NN", null, null, "word"), // adjacent to first "word"
            new AnnotationEntry(5, 1, 1, 20, 26, "repeat", "NN", null, null, "repeat"),
            new AnnotationEntry(6, 1, 1, 20, 26, "repeat", "NN", null, null, "repeat") // exact match with previous
        );

        // Process batch
        var result = generator.processBatch(batch);

        // Verify overlapping positions for "test"
        var testPositions = result.get("test").get(0);
        assertEquals(2, testPositions.getNumPositions(), "Expected two distinct positions for overlapping 'test' entries");
        assertEquals(0, testPositions.getPositionAt(0).getBeginPosition());
        assertEquals(4, testPositions.getPositionAt(0).getEndPosition());
        assertEquals(2, testPositions.getPositionAt(1).getBeginPosition());
        assertEquals(6, testPositions.getPositionAt(1).getEndPosition());

        // Verify adjacent positions for "word"
        var wordPositions = result.get("word").get(0);
        assertEquals(2, wordPositions.getNumPositions(), "Expected two positions for adjacent 'word' entries");
        assertEquals(10, wordPositions.getPositionAt(0).getBeginPosition());
        assertEquals(14, wordPositions.getPositionAt(0).getEndPosition());
        assertEquals(15, wordPositions.getPositionAt(1).getBeginPosition());
        assertEquals(19, wordPositions.getPositionAt(1).getEndPosition());

        // Verify deduplication of exact matches for "repeat"
        var repeatPositions = result.get("repeat").get(0);
        assertEquals(2, repeatPositions.getNumPositions(), "Expected two positions for 'repeat' as they are separate entries");
        assertEquals(20, repeatPositions.getPositionAt(0).getBeginPosition());
        assertEquals(26, repeatPositions.getPositionAt(0).getEndPosition());
        assertEquals(20, repeatPositions.getPositionAt(1).getBeginPosition());
        assertEquals(26, repeatPositions.getPositionAt(1).getEndPosition());
    }

    @Test
    void testGenerateIndex() throws Exception {
        // Insert test data
        insertBasicTestData();

        // Generate index
        generator.generateIndex();

        // Close the generator to release the LevelDB lock
        generator.close();

        // Create a new IndexAccess instance for verification
        try (IndexAccess indexAccess = new IndexAccess(indexBaseDir, "unigram", createTestOptions())) {
            // Verify index contents
            var testPositions = indexAccess.get("test".getBytes());
            assertTrue(testPositions.isPresent(), "Expected positions for 'test'");
            assertEquals(1, testPositions.get().getNumPositions());

            var wordPositions = indexAccess.get("word".getBytes());
            assertTrue(wordPositions.isPresent(), "Expected positions for 'word'");
            assertEquals(1, wordPositions.get().getNumPositions());
        }

        // Verify progress tracking
        verify(mockProgressTracker, atLeastOnce()).updateIndex(anyLong());
    }

    private String generateRandomWord(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }

    private List<AnnotationEntry> createSampleData() {
        List<AnnotationEntry> entries = new ArrayList<>();
        entries.add(new AnnotationEntry(1, 1, 1, 0, 3, "Cat", "NN", null, null, "cat"));
        entries.add(new AnnotationEntry(2, 1, 1, 4, 6, "is", "VBZ", null, null, "be"));
        entries.add(new AnnotationEntry(3, 1, 1, 7, 11, "cute", "JJ", null, null, "cute"));
        return entries;
    }

    private List<AnnotationEntry> createComplexSampleData() {
        List<AnnotationEntry> entries = new ArrayList<>();
        // Sentence 1
        entries.add(new AnnotationEntry(1, 1, 1, 0, 3, "The", "DT", null, null, "the"));
        entries.add(new AnnotationEntry(2, 1, 1, 4, 8, "quick", "JJ", null, null, "quick"));
        entries.add(new AnnotationEntry(3, 1, 1, 9, 14, "brown", "JJ", null, null, "brown"));
        entries.add(new AnnotationEntry(4, 1, 1, 15, 18, "fox", "NN", null, null, "fox"));
        // Sentence 2 (different document)
        entries.add(new AnnotationEntry(5, 2, 1, 0, 4, "Lazy", "JJ", null, null, "lazy"));
        entries.add(new AnnotationEntry(6, 2, 1, 5, 8, "dogs", "NNS", null, null, "dog"));
        // Stopword and punctuation
        entries.add(new AnnotationEntry(7, 3, 1, 0, 2, "in", "IN", null, null, "in")); // stopword
        entries.add(new AnnotationEntry(8, 3, 1, 3, 4, ".", ".", null, null, "."));   // punctuation
        return entries;
    }

    private void setupTestData() throws SQLException {
        // ... existing code ...
    }
}
