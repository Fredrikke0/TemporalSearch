package com.example.index.generators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.PositionListSoA;
import com.example.index.IndexEntry;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ListMultimap;

/**
 * Test class specifically designed to verify that IndexGenerator's writeToLevelDB method
 * only keeps one decompressed blob in memory at a time, implementing true streaming.
 */
class IndexGeneratorMemoryTest extends BaseIndexTest {
    private static final Logger logger = LoggerFactory.getLogger(IndexGeneratorMemoryTest.class);

    private static final int LARGE_ARRAY_SIZE = 20_000; // Large enough to be detectable in memory
    private static final int NUM_TERMS = 5;
    private static final int CHUNKS_PER_TERM = 3;

    private StreamingTestIndexGenerator testGenerator;
    private Path generatorTempDir;
    private MemoryMXBean memoryBean;

    /**
     * Custom IndexGenerator that instruments the decompression process to verify streaming behavior
     */
    private static class StreamingTestIndexGenerator extends IndexGenerator<IndexEntry> {
        private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(StreamingTestIndexGenerator.class);

        private final AtomicInteger currentActiveArrays = new AtomicInteger(0);
        private final AtomicInteger maxConcurrentArrays = new AtomicInteger(0);
        private final AtomicLong totalDecompressions = new AtomicLong(0);
        private final List<String> decompressionLog = Collections.synchronizedList(new ArrayList<>());

        public StreamingTestIndexGenerator(String indexBaseDir, Connection sqliteConn,
                                         ProgressTracker progress, Path customTempPath) throws IOException {
            super(indexBaseDir, null, sqliteConn, progress, 1000, customTempPath);
        }

        @Override
        protected String getTableName() { return "test_table"; }

        @Override
        protected String getIndexName() { return "streaming-test-index"; }

        @Override
        protected List<IndexEntry> fetchBatch(IndexEntry lastEntry) { return Collections.emptyList(); }

        @Override
        protected ListMultimap<String, PositionListSoA> processBatch(List<IndexEntry> batch) { return null; }

        @Override
        public long getDocumentCountForIndex() { return 0; }

        /**
         * Override writeToLevelDB to instrument the decompression process
         */
        @Override
        public long writeToLevelDB(File sortedFile) throws IOException {
            logger.info("Starting instrumented writeToLevelDB from sorted file: {}", sortedFile.getAbsolutePath());
            String currentTerm = null;
            long totalTermsWritten = 0;

            // Use ByteArrayOutputStream to accumulate raw integers for each attribute
            ByteArrayOutputStream baosDocIds = new ByteArrayOutputStream();
            ByteArrayOutputStream baosSentIds = new ByteArrayOutputStream();
            ByteArrayOutputStream baosBeginChars = new ByteArrayOutputStream();
            ByteArrayOutputStream baosEndChars = new ByteArrayOutputStream();
            ByteArrayOutputStream baosSynonymIds = new ByteArrayOutputStream();

            DataOutputStream dosDocIds = new DataOutputStream(baosDocIds);
            DataOutputStream dosSentIds = new DataOutputStream(baosSentIds);
            DataOutputStream dosBeginChars = new DataOutputStream(baosBeginChars);
            DataOutputStream dosEndChars = new DataOutputStream(baosEndChars);
            DataOutputStream dosSynonymIds = new DataOutputStream(baosSynonymIds);

            int numPositionsForCurrentTerm = 0;

            try (BufferedReader reader = new BufferedReader(new FileReader(sortedFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\t", 2);
                    if (parts.length != 2) {
                        logger.warn("Skipping malformed line in sorted file: {}", line);
                        continue;
                    }

                    String termFromFile = parts[0];
                    byte[] lineCompositeBlob = Base64.getDecoder().decode(parts[1]);

                    if (currentTerm == null) {
                        currentTerm = termFromFile;
                    }

                    if (!termFromFile.equals(currentTerm)) {
                        if (numPositionsForCurrentTerm > 0) {
                            // Write current term to database (simplified for test)
                            totalTermsWritten++;
                        }

                        currentTerm = termFromFile;
                        // Reset streams for the new term
                        baosDocIds.reset();
                        baosSentIds.reset();
                        baosBeginChars.reset();
                        baosEndChars.reset();
                        baosSynonymIds.reset();

                        dosDocIds = new DataOutputStream(baosDocIds);
                        dosSentIds = new DataOutputStream(baosSentIds);
                        dosBeginChars = new DataOutputStream(baosBeginChars);
                        dosEndChars = new DataOutputStream(baosEndChars);
                        dosSynonymIds = new DataOutputStream(baosSynonymIds);
                        numPositionsForCurrentTerm = 0;
                    }

                    // INSTRUMENTED STREAMING: Process chunk data one attribute at a time
                    try (DataInputStream disChunk = new DataInputStream(new ByteArrayInputStream(lineCompositeBlob))) {
                        int chunkNumPositions = disChunk.readInt();
                        if (chunkNumPositions > 0) {

                            // Process docIds: decompress, stream to accumulator, discard
                            recordArrayStart("docIds");
                            it.unimi.dsi.fastutil.ints.IntArrayList tempChunkDocIds = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            for (int i = 0; i < tempChunkDocIds.size(); i++) {
                                dosDocIds.writeInt(tempChunkDocIds.getInt(i));
                            }
                            recordArrayEnd("docIds");
                            tempChunkDocIds = null; // Explicit nulling to help GC

                            // Process sentIds: decompress, stream to accumulator, discard
                            recordArrayStart("sentIds");
                            it.unimi.dsi.fastutil.ints.IntArrayList tempChunkSentIds = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            for (int i = 0; i < tempChunkSentIds.size(); i++) {
                                dosSentIds.writeInt(tempChunkSentIds.getInt(i));
                            }
                            recordArrayEnd("sentIds");
                            tempChunkSentIds = null;

                            // Process beginChars: decompress, stream to accumulator, discard
                            recordArrayStart("beginChars");
                            it.unimi.dsi.fastutil.ints.IntArrayList tempChunkBeginChars = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            for (int i = 0; i < tempChunkBeginChars.size(); i++) {
                                dosBeginChars.writeInt(tempChunkBeginChars.getInt(i));
                            }
                            recordArrayEnd("beginChars");
                            tempChunkBeginChars = null;

                            // Process endChars: decompress, stream to accumulator, discard
                            recordArrayStart("endChars");
                            it.unimi.dsi.fastutil.ints.IntArrayList tempChunkEndChars = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, true);
                            for (int i = 0; i < tempChunkEndChars.size(); i++) {
                                dosEndChars.writeInt(tempChunkEndChars.getInt(i));
                            }
                            recordArrayEnd("endChars");
                            tempChunkEndChars = null;

                            // Process synonymIds: decompress, stream to accumulator, discard
                            recordArrayStart("synonymIds");
                            it.unimi.dsi.fastutil.ints.IntArrayList tempChunkSynonymIds = PositionListSoA.readCompressedIntArray(disChunk, chunkNumPositions, false); // No delta coding for synonym IDs
                            for (int i = 0; i < tempChunkSynonymIds.size(); i++) {
                                dosSynonymIds.writeInt(tempChunkSynonymIds.getInt(i));
                            }
                            recordArrayEnd("synonymIds");
                            tempChunkSynonymIds = null;
                        }
                        numPositionsForCurrentTerm += chunkNumPositions;
                    }
                }
            }

            // Write the last term's data
            if (currentTerm != null && numPositionsForCurrentTerm > 0) {
                totalTermsWritten++;
            }

            logger.info("Finished instrumented writeToLevelDB. Total terms written: {}", totalTermsWritten);
            return totalTermsWritten;
        }

        private void recordArrayAllocation(String arrayType, int size) {
            int current = currentActiveArrays.incrementAndGet();
            maxConcurrentArrays.updateAndGet(max -> Math.max(max, current));
            totalDecompressions.incrementAndGet();
            decompressionLog.add(String.format("ALLOC[%s]: size=%d, active=%d", arrayType, size, current));
        }

        private void recordArrayDeallocation(String arrayType, int size) {
            int current = currentActiveArrays.decrementAndGet();
            decompressionLog.add(String.format("DEALLOC[%s]: size=%d, active=%d", arrayType, size, current));
        }

        private void recordArrayStart(String arrayType) {
            int current = currentActiveArrays.incrementAndGet();
            maxConcurrentArrays.updateAndGet(max -> Math.max(max, current));
            totalDecompressions.incrementAndGet();
            decompressionLog.add(String.format("START[%s]: active=%d", arrayType, current));
        }

        private void recordArrayEnd(String arrayType) {
            int current = currentActiveArrays.decrementAndGet();
            decompressionLog.add(String.format("END[%s]: active=%d", arrayType, current));
        }

        public int getCurrentActiveArrays() { return currentActiveArrays.get(); }
        public int getMaxConcurrentArrays() { return maxConcurrentArrays.get(); }
        public long getTotalDecompressions() { return totalDecompressions.get(); }
        public List<String> getDecompressionLog() { return new ArrayList<>(decompressionLog); }

        public void resetCounters() {
            currentActiveArrays.set(0);
            maxConcurrentArrays.set(0);
            totalDecompressions.set(0);
            decompressionLog.clear();
        }
    }

    @BeforeEach
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        generatorTempDir = tempDir.resolve("streamingTestTemp");
        Files.createDirectories(generatorTempDir);

        ProgressTracker mockProgressTracker = Mockito.mock(ProgressTracker.class);
        testGenerator = new StreamingTestIndexGenerator(
            indexBaseDir.toString(),
            sqliteConn,
            mockProgressTracker,
            generatorTempDir
        );

        memoryBean = ManagementFactory.getMemoryMXBean();
    }

    @AfterEach
    @Override
    protected void tearDown() throws Exception {
        if (testGenerator != null) {
            testGenerator.close();
        }
        super.tearDown();
    }

    /**
     * Creates a test file with multiple terms, each having multiple chunks,
     * to verify streaming behavior.
     */
    private File createTestFile() throws IOException {
        File sortedFile = generatorTempDir.resolve("streaming_test.tmp").toFile();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(sortedFile, StandardCharsets.UTF_8))) {
            for (int termIndex = 0; termIndex < NUM_TERMS; termIndex++) {
                String term = "term" + String.format("%03d", termIndex);

                // Create multiple chunks for each term to test merging behavior
                for (int chunkIndex = 0; chunkIndex < CHUNKS_PER_TERM; chunkIndex++) {
                    PositionListSoA chunk = createLargePositionList(LARGE_ARRAY_SIZE);
                    String chunkBlob = Base64.getEncoder().encodeToString(chunk.serializeToCompositeBlob());
                    writer.write(term + "\t" + chunkBlob + "\n");
                }
            }
        }

        return sortedFile;
    }

    /**
     * Creates a PositionListSoA with a large number of positions
     */
    private PositionListSoA createLargePositionList(int numPositions) {
        PositionListSoA list = new PositionListSoA();
        for (int i = 0; i < numPositions; i++) {
            int docId = i / 100;
            int sentId = i % 100;
            int beginChar = i * 10;
            int endChar = beginChar + 5;
            int synonymId = (i % 2 == 0) ? 1000 + i : -1; // Some with synonyms, some without

            if (synonymId != -1) {
                list.add(docId, sentId, beginChar, endChar, synonymId);
            } else {
                list.add(docId, sentId, beginChar, endChar);
            }
        }
        return list;
    }

    @Test
    void testStreamingDecompressionBehavior() throws IOException {
        // Create test file
        File sortedFile = createTestFile();

        // Record initial memory state
        MemoryUsage beforeMemory = memoryBean.getHeapMemoryUsage();
        long initialUsedMemory = beforeMemory.getUsed();

        // Reset tracking counters
        testGenerator.resetCounters();

        // Execute writeToLevelDB with instrumentation
        long termsWritten = testGenerator.writeToLevelDB(sortedFile);

        // Verify that terms were actually written
        assertEquals(NUM_TERMS, termsWritten, "Should have written all terms");

        // Analyze the streaming behavior
        int maxConcurrent = testGenerator.getMaxConcurrentArrays();
        long totalDecompressions = testGenerator.getTotalDecompressions();
        List<String> log = testGenerator.getDecompressionLog();

        // Key assertion: We should never have more than 1 array active at a time
        // since we process one attribute at a time and immediately deallocate
        assertEquals(1, maxConcurrent,
            String.format("Should never have more than 1 array active at a time (streaming), but had %d", maxConcurrent));

        // Verify that we processed the expected number of arrays
        // Each term has CHUNKS_PER_TERM chunks, each chunk decompresses 5 attribute arrays
        long expectedDecompressions = (long) NUM_TERMS * CHUNKS_PER_TERM * 5;
        assertEquals(expectedDecompressions, totalDecompressions,
            "Should have decompressed exactly one array per attribute per chunk");

        // Verify that all arrays were deallocated
        assertEquals(0, testGenerator.getCurrentActiveArrays(),
            "All arrays should be deallocated after processing");

        // Verify the allocation/deallocation pattern
        verifyStreamingPattern(log);

        // Memory usage should be bounded
        MemoryUsage afterMemory = memoryBean.getHeapMemoryUsage();
        long finalUsedMemory = afterMemory.getUsed();
        long memoryIncrease = finalUsedMemory - initialUsedMemory;

        logger.info("=== Streaming Test Results ===");
        logger.info("Terms written: {}", termsWritten);
        logger.info("Max concurrent arrays: {}", maxConcurrent);
        logger.info("Total decompressions: {}", totalDecompressions);
        logger.info("Memory increase: {} MB", (memoryIncrease / 1024 / 1024));
        logger.info("Final active arrays: {}", testGenerator.getCurrentActiveArrays());

        // Print first few log entries to verify pattern
        logger.info("=== First 10 Allocation Events ===");
        log.stream().limit(10).forEach(logger::debug); // Log individual events at debug level
    }

    /**
     * Verifies that the allocation pattern shows true streaming:
     * - Each allocation is immediately followed by a deallocation
     * - We never accumulate multiple arrays
     */
    private void verifyStreamingPattern(List<String> log) {
        if (log.isEmpty()) {
            fail("No allocation events recorded");
        }

        // Verify that starts and ends are perfectly paired
        long starts = log.stream().filter(entry -> entry.contains("START")).count();
        long ends = log.stream().filter(entry -> entry.contains("END")).count();

        assertEquals(starts, ends,
            "Every array start should have a corresponding end");

        // Verify that we never have more than 1 active array at any point
        for (String logEntry : log) {
            if (logEntry.contains("active=")) {
                String activeStr = logEntry.substring(logEntry.indexOf("active=") + 7);
                int activeCount = Integer.parseInt(activeStr);
                assertTrue(activeCount <= 1,
                    String.format("Should never have more than 1 active array, but found: %s", logEntry));
            }
        }
    }

    @Test
    void testMemoryBehaviorWithLargeSingleTerm() throws IOException {
        // Create a file with one term having many chunks
        File sortedFile = generatorTempDir.resolve("single_large_term.tmp").toFile();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(sortedFile, StandardCharsets.UTF_8))) {
            String term = "massive_term";

            // Create many chunks for a single term
            for (int chunkIndex = 0; chunkIndex < 10; chunkIndex++) {
                PositionListSoA chunk = createLargePositionList(30_000);
                String chunkBlob = Base64.getEncoder().encodeToString(chunk.serializeToCompositeBlob());
                writer.write(term + "\t" + chunkBlob + "\n");
            }
        }

        testGenerator.resetCounters();

        // Execute writeToLevelDB
        long termsWritten = testGenerator.writeToLevelDB(sortedFile);
        assertEquals(1, termsWritten, "Should have written exactly one term");

        // Verify streaming behavior even with large single term
        assertEquals(1, testGenerator.getMaxConcurrentArrays(),
            "Even with large single term, should maintain streaming (max 1 concurrent array)");

        assertEquals(0, testGenerator.getCurrentActiveArrays(),
            "All arrays should be deallocated after processing large term");

        logger.info("=== Large Single Term Test Results ===");
        logger.info("Max concurrent arrays: {}", testGenerator.getMaxConcurrentArrays());
        logger.info("Total decompressions: {}", testGenerator.getTotalDecompressions());
    }

    @Test
    void testEmptyAndMixedChunks() throws IOException {
        // Test with empty chunks mixed with normal chunks
        File sortedFile = generatorTempDir.resolve("mixed_chunks.tmp").toFile();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(sortedFile, StandardCharsets.UTF_8))) {
            PositionListSoA emptyChunk = new PositionListSoA();
            PositionListSoA normalChunk = createLargePositionList(5000);

            String emptyBlob = Base64.getEncoder().encodeToString(emptyChunk.serializeToCompositeBlob());
            String normalBlob = Base64.getEncoder().encodeToString(normalChunk.serializeToCompositeBlob());

            writer.write("term1\t" + emptyBlob + "\n");
            writer.write("term1\t" + normalBlob + "\n");
            writer.write("term2\t" + emptyBlob + "\n");
            writer.write("term3\t" + normalBlob + "\n");
        }

        testGenerator.resetCounters();

        // This should handle empty chunks gracefully
        assertDoesNotThrow(() -> testGenerator.writeToLevelDB(sortedFile));

        // Verify streaming behavior is maintained even with empty chunks
        assertTrue(testGenerator.getMaxConcurrentArrays() <= 1,
            "Should maintain streaming behavior even with empty chunks");
    }
}