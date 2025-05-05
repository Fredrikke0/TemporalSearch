package com.example.index;

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
import org.iq80.leveldb.Options;

@ExtendWith(MockitoExtension.class)
class UnigramIndexGeneratorTest extends BaseIndexTest {
    private static final Logger logger = LoggerFactory.getLogger(UnigramIndexGeneratorTest.class);
    private Path stopwordsPath;
    private UnigramIndexGenerator generator;

    @Mock
    private ProgressTracker progress;

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private static final int LARGE_BATCH_SIZE = 10_000;
    private static final Random random = new Random(42); // Fixed seed for reproducibility

    @BeforeEach
    @Override
    void setUp() throws Exception {
        // Call parent setup
        super.setUp();
        
        // Create stopwords path
        stopwordsPath = tempDir.resolve("stopwords.txt");

        // Create empty stopwords file
        Files.writeString(stopwordsPath, "the\na\nan\n");

        // Create generator with new index base directory
        generator = new UnigramIndexGenerator(
            indexBaseDir.toString(),
            stopwordsPath.toString(),
            sqliteConn,
            progress
        );
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
        List<AnnotationEntry> entries = generator.fetchBatch(0);

        // Verify results
        assertEquals(2, entries.size());
        
        AnnotationEntry first = entries.get(0);
        assertEquals(1, first.getDocumentId());
        assertEquals(1, first.getSentenceId());
        assertEquals(0, first.getBeginChar());
        assertEquals(4, first.getEndChar());
        assertEquals("test", first.getToken());
        assertEquals("NN", first.getPos());
        assertEquals(LocalDate.parse("2024-03-20"), first.getTimestamp());

        AnnotationEntry second = entries.get(1);
        assertEquals(1, second.getDocumentId());
        assertEquals(1, second.getSentenceId());
        assertEquals(5, second.getBeginChar());
        assertEquals(9, second.getEndChar());
        assertEquals("word", second.getToken());
        assertEquals("NN", second.getPos());
        assertEquals(LocalDate.parse("2024-03-20"), second.getTimestamp());
    }

    @Test
    void testProcessBatch() throws IOException {
        // Create test entries
        List<AnnotationEntry> batch = List.of(
            new AnnotationEntry(1, 1, 1, 0, 4, "Test", "NN", LocalDate.parse("2024-03-20")),
            new AnnotationEntry(2, 1, 1, 5, 9, "word", "NN", LocalDate.parse("2024-03-20")),
            new AnnotationEntry(3, 1, 1, 10, 13, "the", "DT", LocalDate.parse("2024-03-20")) // stopword
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
        assertEquals(1, testPositions.getPositions().size());
        assertEquals(0, testPositions.getPositions().get(0).getBeginPosition());
        assertEquals(4, testPositions.getPositions().get(0).getEndPosition());

        var wordPositions = result.get("word").get(0);
        assertEquals(1, wordPositions.getPositions().size());
        assertEquals(5, wordPositions.getPositions().get(0).getBeginPosition());
        assertEquals(9, wordPositions.getPositions().get(0).getEndPosition());
    }

    @Test
    void testProcessBatchWithOverlaps() throws IOException {
        // Create test entries with overlapping and adjacent positions
        List<AnnotationEntry> batch = List.of(
            new AnnotationEntry(1, 1, 1, 0, 4, "test", "NN", LocalDate.parse("2024-03-20")),
            new AnnotationEntry(2, 1, 1, 2, 6, "test", "NN", LocalDate.parse("2024-03-20")), // overlaps first "test"
            new AnnotationEntry(3, 1, 1, 10, 14, "word", "NN", LocalDate.parse("2024-03-20")),
            new AnnotationEntry(4, 1, 1, 15, 19, "word", "NN", LocalDate.parse("2024-03-20")), // adjacent to first "word"
            new AnnotationEntry(5, 1, 1, 20, 26, "repeat", "NN", LocalDate.parse("2024-03-20")),
            new AnnotationEntry(6, 1, 1, 20, 26, "repeat", "NN", LocalDate.parse("2024-03-20")) // exact match with previous
        );

        // Process batch
        var result = generator.processBatch(batch);

        // Verify overlapping positions for "test"
        var testPositions = result.get("test").get(0);
        assertEquals(2, testPositions.getPositions().size(), "Expected two distinct positions for overlapping 'test' entries");
        assertEquals(0, testPositions.getPositions().get(0).getBeginPosition());
        assertEquals(4, testPositions.getPositions().get(0).getEndPosition());
        assertEquals(2, testPositions.getPositions().get(1).getBeginPosition());
        assertEquals(6, testPositions.getPositions().get(1).getEndPosition());

        // Verify adjacent positions for "word"
        var wordPositions = result.get("word").get(0);
        assertEquals(2, wordPositions.getPositions().size(), "Expected two positions for adjacent 'word' entries");
        assertEquals(10, wordPositions.getPositions().get(0).getBeginPosition());
        assertEquals(14, wordPositions.getPositions().get(0).getEndPosition());
        assertEquals(15, wordPositions.getPositions().get(1).getBeginPosition());
        assertEquals(19, wordPositions.getPositions().get(1).getEndPosition());

        // Verify deduplication of exact matches for "repeat"
        var repeatPositions = result.get("repeat").get(0);
        assertEquals(2, repeatPositions.getPositions().size(), "Expected two positions for 'repeat' as they are separate entries");
        assertEquals(20, repeatPositions.getPositions().get(0).getBeginPosition());
        assertEquals(26, repeatPositions.getPositions().get(0).getEndPosition());
        assertEquals(20, repeatPositions.getPositions().get(1).getBeginPosition());
        assertEquals(26, repeatPositions.getPositions().get(1).getEndPosition());
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
            assertEquals(1, testPositions.get().getPositions().size());

            var wordPositions = indexAccess.get("word".getBytes());
            assertTrue(wordPositions.isPresent(), "Expected positions for 'word'");
            assertEquals(1, wordPositions.get().getPositions().size());
        }

        // Verify progress tracking
        verify(progress, atLeastOnce()).updateIndex(anyLong());
    }

    private String generateRandomWord(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }
} 
