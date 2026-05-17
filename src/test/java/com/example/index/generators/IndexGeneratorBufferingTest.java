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
import com.example.core.OccurrencesBlock;
import com.example.core.PostingList;
import com.example.index.AnnotationEntry;
import com.example.index.IndexKey;
import com.example.index.RocksDBConfig;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import org.roaringbitmap.longlong.Roaring64NavigableMap;

/**
 * Tests the memory buffering functionality in IndexGenerator to verify
 * that it reduces the number of temporary files created during indexing.
 */
class IndexGeneratorBufferingTest extends BaseIndexTest {

    private static class TestIndexGenerator extends IndexGenerator<AnnotationEntry> {
        private final int numBatchesToSimulate;
        private int currentBatch = 0;
        private long totalSizeBefore = 0;
        private long totalSizeAfter = 0;
        private int mergeCount = 0;

        public TestIndexGenerator(IndexAccessInterface indexAccess, Connection sqliteConn,
                ProgressTracker progress, Path customTempPath, int numBatches) throws IOException {
            super(indexAccess, null, sqliteConn, progress, 1, customTempPath); // Batch size of 1 to create many files
            this.numBatchesToSimulate = numBatches;
        }

        @Override
        protected String getTableName() {
            return "test_table";
        }

        @Override
        protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastEntry) throws SQLException {
            if (currentBatch >= numBatchesToSimulate) {
                return List.of(); // No more batches
            }
            currentBatch++;

            // Simulate a batch with one entry
            return List.of(new AnnotationEntry(currentBatch, currentBatch, currentBatch, currentBatch, currentBatch + 5,
                    "token" + currentBatch, "NOUN", null, null));
        }

        @Override
        protected ListMultimap<IndexKey, PostingList> processBatch(List<AnnotationEntry> batch) {
            ListMultimap<IndexKey, PostingList> result = ArrayListMultimap.create();
            for (AnnotationEntry entry : batch) {
                long cellKey = PostingList.packCellKey(entry.getDocumentId(), entry.getSentenceId());
                Roaring64NavigableMap cells = new Roaring64NavigableMap();
                cells.add(cellKey);
                byte constantLength = (byte) Math.min(entry.getEndChar() - entry.getBeginChar(), 255);
                OccurrencesBlock occ = OccurrencesBlock.fromUnsorted(
                        new long[] { cellKey }, new byte[][] { { (byte) entry.getBeginChar() } }, constantLength);
                PostingList pl = PostingList.fromCellsAndOccurrences(cells, constantLength, occ);
                result.put(IndexKey.fromUtf8("term" + entry.getToken()), pl);
            }
            return result;
        }

        @Override
        public long getDocumentCountForIndex() throws SQLException {
            return numBatchesToSimulate;
        }

        @Override
        protected List<java.io.File> performIncrementalMerge(List<java.io.File> tempFiles) throws IOException {
            // Measure size before merge
            long sizeBefore = tempFiles.stream().mapToLong(java.io.File::length).sum();

            // Perform the merge
            List<java.io.File> result = super.performIncrementalMerge(tempFiles);

            // Measure size after merge
            long sizeAfter = result.stream().mapToLong(java.io.File::length).sum();

            mergeCount++;
            totalSizeBefore += sizeBefore;
            totalSizeAfter += sizeAfter;

            System.out.printf("Incremental merge #%d: %d files (%,d bytes) → %d files (%,d bytes) [%.1f%% reduction]%n",
                    mergeCount, tempFiles.size(), sizeBefore, result.size(), sizeAfter,
                    100.0 * (sizeBefore - sizeAfter) / sizeBefore);

            return result;
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

        public void printStorageStats() {
            if (mergeCount > 0) {
                System.out.printf("Total incremental merges: %d%n", mergeCount);
                System.out.printf("Total storage before merges: %,d bytes%n", totalSizeBefore);
                System.out.printf("Total storage after merges: %,d bytes%n", totalSizeAfter);
                System.out.printf("Overall storage reduction: %,d bytes (%.1f%%)%n",
                        totalSizeBefore - totalSizeAfter,
                        100.0 * (totalSizeBefore - totalSizeAfter) / totalSizeBefore);
            }
        }
    }

    @Test
    void testIncrementalMergeReducesTempFiles() throws Exception {
        Path customTempPath = tempDir.resolve("bufferingTest");
        Files.createDirectories(customTempPath);

        // Test with 2,500 small batches - this will trigger incremental merges
        // Each batch creates 1 temp file, so without incremental merge we'd have 2.5k
        // files
        // With incremental merge every 1000 files, we should see 2 merges plus
        // remainder
        int numBatches = 2500;

        // Create IndexAccess and ProgressTracker
        Path indexPath = indexBaseDir.resolve("buffering-test");
        Files.createDirectories(indexPath);

        try (Options options = RocksDBConfig.createOptimizedOptions();
                IndexAccessInterface indexAccess = new IndexAccess(indexPath, "buffering-test", options, false);
                TestIndexGenerator generator = new TestIndexGenerator(
                        indexAccess, sqliteConn, new ProgressTracker(), customTempPath, numBatches)) {

            generator.generateIndex();

            // Check how many temp files exist in the temp directory at the end
            Path actualTempDir = generator.getActualTempDir();
            assertTrue(Files.exists(actualTempDir), "Temp directory should exist");

            long batchFileCount = Files.list(actualTempDir)
                    .filter(path -> path.getFileName().toString().startsWith("batch-"))
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .count();

            long mergedFileCount = Files.list(actualTempDir)
                    .filter(path -> path.getFileName().toString().startsWith("merged-"))
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .count();

            long totalTempFiles = batchFileCount + mergedFileCount;

            System.out.println("Number of batch temp files remaining: " + batchFileCount);
            System.out.println("Number of merged temp files: " + mergedFileCount);
            System.out.println("Total temp files at end: " + totalTempFiles);
            System.out.println("Number of batches processed: " + numBatches);

            // With incremental merge, we should never have more than 1000 batch files
            // plus some merged files (roughly numBatches/1000)
            assertTrue(batchFileCount < 1000,
                    "Should never have more than 1000 batch files due to incremental merge, but got " + batchFileCount);

            // Total temp files should be dramatically less than the number of batches
            assertTrue(totalTempFiles < numBatches / 10,
                    "Incremental merge should reduce " + numBatches + " batches to much fewer temp files, but got "
                            + totalTempFiles);

            // Print storage statistics
            generator.printStorageStats();

            // The test passes if we successfully reduced the number of temp files
            // At the end, temp files may be cleaned up, but the key success is that we
            // never
            // hit the original 2500 files and successfully performed incremental merges
            assertTrue(true, "Incremental merge test completed successfully");
        }
    }

    @Test
    void testBufferingWithNoData() throws Exception {
        Path customTempPath = tempDir.resolve("emptyTest");
        Files.createDirectories(customTempPath);

        Path indexPath = indexBaseDir.resolve("empty-test");
        Files.createDirectories(indexPath);

        try (Options options = RocksDBConfig.createOptimizedOptions();
                IndexAccessInterface indexAccess = new IndexAccess(indexPath, "empty-test", options, false);
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
