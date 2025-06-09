package com.example.index.generators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.rocksdb.Options;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationEntry;
import com.example.index.RocksDBConfig;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

/**
 * Tests the memory buffering functionality in IndexGenerator to verify
 * that it reduces the number of temporary files created during indexing.
 */
class IndexGeneratorBufferingTest extends BaseIndexTest {

    private static class TestIndexGenerator extends IndexGenerator<AnnotationEntry> {
        private final int numBatchesToSimulate;
        private int currentBatch = 0;

        public TestIndexGenerator(IndexAccessInterface indexAccess, Connection sqliteConn,
                                 ProgressTracker progress, Path customTempPath, int numBatches) throws IOException {
            super(indexAccess, null, sqliteConn, progress, 1000, customTempPath);
            this.numBatchesToSimulate = numBatches;
        }

        @Override
        protected String getTableName() { return "test_table"; }

        @Override
        protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastEntry) throws SQLException {
            if (currentBatch >= numBatchesToSimulate) {
                return List.of(); // No more batches
            }
            currentBatch++;

            // Simulate a batch with one entry
            return List.of(new AnnotationEntry(currentBatch, currentBatch, currentBatch, currentBatch, currentBatch + 5, "token" + currentBatch, "NOUN", null, null, null));
        }

        @Override
        protected ListMultimap<String, PositionListSoA> processBatch(List<AnnotationEntry> batch) {
            ListMultimap<String, PositionListSoA> result = ArrayListMultimap.create();
            for (AnnotationEntry entry : batch) {
                PositionListSoA positions = new PositionListSoA();
                // Add a small number of positions to stay well under the 256MB buffer limit
                for (int i = 0; i < 10; i++) {
                    positions.add(entry.getDocumentId(), entry.getSentenceId(),
                                entry.getBeginChar() + i, entry.getEndChar() + i);
                }
                result.put("term" + entry.getToken(), positions);
            }
            return result;
        }

        @Override
        public long getDocumentCountForIndex() throws SQLException {
            return numBatchesToSimulate;
        }

        public Path getActualTempDir() {
            try {
                java.lang.reflect.Field tempDirField = IndexGenerator.class.getDeclaredField("tempDir");
                tempDirField.setAccessible(true);
                return (Path) tempDirField.get(this);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("Could not access tempDir field", e);
            }
        }
    }

    @Test
    void testMemoryBufferingReducesTempFiles() throws Exception {
        Path customTempPath = tempDir.resolve("bufferingTest");
        Files.createDirectories(customTempPath);

        // Test with 100 small batches - without buffering this would create 100 temp files
        // With buffering, it should create far fewer files since we accumulate in memory first
        int numBatches = 100;

        // Create IndexAccess and ProgressTracker
        Path indexPath = indexBaseDir.resolve("buffering-test");
        Files.createDirectories(indexPath);

        try (Options options = RocksDBConfig.createOptimizedOptions();
             IndexAccessInterface indexAccess = new IndexAccess(indexPath, "buffering-test", options);
             TestIndexGenerator generator = new TestIndexGenerator(
                 indexAccess, sqliteConn, new ProgressTracker(), customTempPath, numBatches)) {

            generator.generateIndex();

            // Check how many batch-*.tmp files were created in the temp directory
            Path actualTempDir = generator.getActualTempDir();
            assertTrue(Files.exists(actualTempDir), "Temp directory should exist");

            long tempFileCount = Files.list(actualTempDir)
                .filter(path -> path.getFileName().toString().startsWith("batch-"))
                .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                .count();

            System.out.println("Number of temporary files created: " + tempFileCount);
            System.out.println("Number of batches processed: " + numBatches);

            // With memory buffering, we should have significantly fewer temp files than batches
            // The exact number depends on the buffer size and data size, but it should be much less than 100
            assertTrue(tempFileCount < numBatches,
                "Memory buffering should reduce temp files from " + numBatches + " to " + tempFileCount);

            // For small test data, we might even get everything in a single temp file
            assertTrue(tempFileCount <= 10,
                "With small test data, should create at most 10 temp files, but got " + tempFileCount);
        }
    }

    @Test
    void testBufferingWithNoData() throws Exception {
        Path customTempPath = tempDir.resolve("emptyTest");
        Files.createDirectories(customTempPath);

        Path indexPath = indexBaseDir.resolve("empty-test");
        Files.createDirectories(indexPath);

        try (Options options = RocksDBConfig.createOptimizedOptions();
             IndexAccessInterface indexAccess = new IndexAccess(indexPath, "empty-test", options);
             TestIndexGenerator generator = new TestIndexGenerator(
                 indexAccess, sqliteConn, new ProgressTracker(), customTempPath, 0)) {

            // This should not create any temp files
            generator.generateIndex();

            Path actualTempDir = generator.getActualTempDir();
            if (Files.exists(actualTempDir)) {
                long tempFileCount = Files.list(actualTempDir)
                    .filter(path -> path.getFileName().toString().startsWith("batch-"))
                    .count();

                assertEquals(0, tempFileCount, "No temp files should be created for empty data");
            }
        }
    }
}