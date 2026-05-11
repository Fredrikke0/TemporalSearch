package com.example.index.generators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rocksdb.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccess;
import com.example.core.OccurrencesView;
import com.example.index.AnnotationEntry;
import com.example.logging.ProgressTracker;

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

        // Create IndexAccess instance first
        // The UnigramIndexGenerator will use this IndexAccess to create its specific
        // index directory if needed.
        // BaseIndexTest ensures indexBaseDir (this.tempDir.resolve("indexes")) exists.
        // UnigramIndexGenerator will create indexBaseDir.resolve("unigram")
        try (Options options = createTestOptions()) {
            // It seems the generator itself might be expecting to *receive* an IndexAccess
            // instance
            // that it will then use, rather than a path to create one. Let's check
            // UnigramIndexGenerator constructor.
            // Based on IndexGenerator superclass, it now takes IndexAccessInterface.
            this.indexAccess = new IndexAccess(indexBaseDir, "unigram", options, false); // Create it here for the
                                                                                         // generator
        }

        // Create generator, passing the already created IndexAccess instance
        generator = new UnigramIndexGenerator(
                this.indexAccess, // Pass the IndexAccessInterface instance
                stopwordsPath.toString(),
                sqliteConn,
                mockProgressTracker,
                1000,
                null);
        setupTestData();
    }

    @AfterEach
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (indexAccess != null) {
            indexAccess.close();
        }
        new File(TEST_STOPWORDS_PATH).delete(); // This might be redundant if super.tearDown deletes tempDir which
                                                // contains this file.
                                                // However, specific test stopwords file path is used in this class.
    }

    private void insertBasicTestData() throws SQLException {
        try (Statement stmt = sqliteConn.createStatement()) {
            stmt.execute("INSERT INTO documents (document_id, timestamp) VALUES (1, '2024-03-20')");
            stmt.execute("INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token) " +
                    "VALUES (1, 1, 0, 4, 'test')");
            stmt.execute("INSERT INTO annotations (document_id, sentence_id, begin_char, end_char, token) " +
                    "VALUES (1, 1, 5, 9, 'word')");
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

        AnnotationEntry second = entries.get(1);
        assertEquals(1, second.getDocumentId());
        assertEquals(1, second.getSentenceId());
        assertEquals(5, second.getBeginChar());
        assertEquals(9, second.getEndChar());
        assertEquals("word", second.getToken());
    }

    @Test
    void testProcessBatch() throws IOException {
        // Create test entries
        List<AnnotationEntry> batch = List.of(
                new AnnotationEntry(1, 1, 1, 0, 4, "Test", null, null, null),
                new AnnotationEntry(2, 1, 1, 5, 9, "word", null, null, null),
                new AnnotationEntry(3, 1, 1, 10, 13, "the", null, null, null) // stopword
        );

        // Process batch
        var result = generator.processBatch(batch);

        // Verify results
        assertEquals(2, result.keySet().size());
        assertTrue(result.containsKey("test"));
        assertTrue(result.containsKey("word"));
        assertFalse(result.containsKey("the")); // stopword should be filtered

        // Verify cell counts
        var testPl = result.get("test").get(0);
        assertEquals(1, testPl.cells().getLongCardinality(), "Expected one cell for 'test'");

        var wordPl = result.get("word").get(0);
        assertEquals(1, wordPl.cells().getLongCardinality(), "Expected one cell for 'word'");
    }

    @Test
    void testProcessBatchWithOverlaps() throws IOException {
        // Create test entries with overlapping and adjacent positions
        List<AnnotationEntry> batch = List.of(
                new AnnotationEntry(1, 1, 1, 0, 4, "test", null, null, null),
                new AnnotationEntry(2, 1, 1, 2, 6, "test", null, null, null), // overlaps first "test"
                new AnnotationEntry(3, 1, 1, 10, 14, "word", null, null, null),
                new AnnotationEntry(4, 1, 1, 15, 19, "word", null, null, null), // adjacent to first "word"
                new AnnotationEntry(5, 1, 1, 20, 26, "repeat", null, null, null),
                new AnnotationEntry(6, 1, 1, 22, 28, "repeat", null, null, null) // same cell, different offset
        );

        // Process batch
        var result = generator.processBatch(batch);

        // Verify overlapping positions for "test" — one cell with two occurrence
        // offsets
        var testPl = result.get("test").get(0);
        assertEquals(1, testPl.cells().getLongCardinality(), "Expected one cell for overlapping 'test' entries");
        assertNotNull(testPl.occurrences());
        OccurrencesView testOv = testPl.occurrences().occurrences(0);
        assertEquals(2, testOv.size(), "Expected two occurrence offsets for overlapping 'test'");
        assertEquals(0, testOv.begin(0));
        assertEquals(2, testOv.begin(1));

        // Verify adjacent positions for "word" — one cell with two occurrence offsets
        var wordPl = result.get("word").get(0);
        assertEquals(1, wordPl.cells().getLongCardinality(), "Expected one cell for adjacent 'word' entries");
        assertNotNull(wordPl.occurrences());
        OccurrencesView wordOv = wordPl.occurrences().occurrences(0);
        assertEquals(2, wordOv.size(), "Expected two occurrence offsets for adjacent 'word'");
        assertEquals(10, wordOv.begin(0));
        assertEquals(15, wordOv.begin(1));

        // Verify deduplication of exact matches for "repeat" — one cell with two
        // occurrence offsets
        var repeatPl = result.get("repeat").get(0);
        assertEquals(1, repeatPl.cells().getLongCardinality(), "Expected one cell for 'repeat' entries");
        assertNotNull(repeatPl.occurrences());
        OccurrencesView repeatOv = repeatPl.occurrences().occurrences(0);
        assertEquals(2, repeatOv.size(), "Expected two occurrence offsets for 'repeat'");
        assertEquals(20, repeatOv.begin(0));
        assertEquals(22, repeatOv.begin(1));
    }

    @Test
    void testGenerateIndex() throws Exception {
        // Insert test data
        insertBasicTestData();

        // Generate index
        // The generator uses the IndexAccess instance passed in its constructor.
        generator.generateIndex();

        // Close the generator to release its resources (like temp sort files).
        // IndexGenerator.close() does NOT close the IndexAccessInterface it received.
        generator.close();

        // Explicitly close the IndexAccess instance that was used by the generator
        // to ensure the lock is released before verificationIA tries to open it.
        if (this.indexAccess != null) {
            this.indexAccess.close();
        }

        // For verification, we need a *new* IndexAccess instance for the *same path* as
        // the original one.
        // The original this.indexAccess was created with baseDir=this.indexBaseDir and
        // indexName="unigram".
        // So the database is at this.indexBaseDir.resolve("unigram").
        // We need to open this exact path.
        // The IndexAccess constructor IndexAccess(Path baseDir, String indexName,
        // Options options)
        // creates the DB at baseDir.resolve(indexName).
        // So, to open the existing DB at this.indexBaseDir.resolve("unigram"),
        // we should use this.indexBaseDir as the baseDir argument and "unigram" as the
        // indexName argument.

        try (Options options = createTestOptions();
                // Update to include readOnly=false
                IndexAccess verificationIA = new IndexAccess(this.indexBaseDir, "unigram", options, false)) {
            // Verify index contents
            var testPl = verificationIA.get("test".getBytes());
            assertTrue(testPl.isPresent(), "Expected posting list for 'test'");
            assertEquals(1, testPl.get().cells().getLongCardinality());

            var wordPl = verificationIA.get("word".getBytes());
            assertTrue(wordPl.isPresent(), "Expected posting list for 'word'");
            assertEquals(1, wordPl.get().cells().getLongCardinality());
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
        entries.add(new AnnotationEntry(1, 1, 1, 0, 3, "Cat", null, null, null));
        entries.add(new AnnotationEntry(2, 1, 1, 4, 6, "is", null, null, null));
        entries.add(new AnnotationEntry(3, 1, 1, 7, 11, "cute", null, null, null));
        return entries;
    }

    private List<AnnotationEntry> createComplexSampleData() {
        List<AnnotationEntry> entries = new ArrayList<>();
        // Sentence 1
        entries.add(new AnnotationEntry(1, 1, 1, 0, 3, "The", null, null, null));
        entries.add(new AnnotationEntry(2, 1, 1, 4, 8, "quick", null, null, null));
        entries.add(new AnnotationEntry(3, 1, 1, 9, 14, "brown", null, null, null));
        entries.add(new AnnotationEntry(4, 1, 1, 15, 18, "fox", null, null, null));
        // Sentence 2 (different document)
        entries.add(new AnnotationEntry(5, 2, 1, 0, 4, "Lazy", null, null, null));
        entries.add(new AnnotationEntry(6, 2, 1, 5, 8, "dogs", null, null, null));
        // Stopword and punctuation
        entries.add(new AnnotationEntry(7, 3, 1, 0, 2, "in", null, null, null)); // stopword
        entries.add(new AnnotationEntry(8, 3, 1, 3, 4, ".", null, null, null)); // punctuation
        return entries;
    }

    private void setupTestData() throws SQLException {
        // ... existing code ...
    }
}
