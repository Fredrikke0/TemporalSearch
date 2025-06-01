package com.example.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rocksdb.Options;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;

import com.example.index.RocksDBConfig;

/**
 * Tests for IndexAccess implementation.
 * Verifies core functionality including:
 * - Basic CRUD operations
 * - Batch operations
 * - Resource management
 * - Error handling
 */
public class IndexAccessTest {
    private static final String TEST_INDEX_PATH = "test-indexes";
    private IndexAccess indexAccess;
    private Path indexPath;
    private Options options;

    @BeforeEach
    void setUp() throws Exception {
        indexPath = Path.of(TEST_INDEX_PATH);
        if (Files.exists(indexPath)) {
            deleteDirectory(indexPath.toFile());
        }
        Files.createDirectories(indexPath);

        options = RocksDBConfig.createOptimizedOptions();

        indexAccess = new IndexAccess(indexPath, "test", options);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (indexAccess != null) {
            indexAccess.close();
        }
        if (options != null) {
            options.close();
        }
        if (Files.exists(indexPath)) {
            deleteDirectory(indexPath.toFile());
        }
    }

    @Test
    void testBasicOperations() throws Exception {
        // Create test data
        Position pos1 = new Position(1, 1, 0, 5);
        Position pos2 = new Position(1, 2, 6, 10);
        PositionListSoA positions = new PositionListSoA();
        positions.add(pos1);
        positions.add(pos2);

        // Test put and get
        byte[] key = "test-key".getBytes();
        indexAccess.put(key, positions.serializeToCompositeBlob());

        Optional<PositionListSoA> retrieved = indexAccess.get(key);
        assertTrue(retrieved.isPresent(), "Should retrieve stored positions");
        assertEquals(2, retrieved.get().getNumPositions(), "Should have correct number of positions");

        // Verify position details
        Position retrievedPos = retrieved.get().getPositionAt(0);
        assertEquals(pos1.getDocumentId(), retrievedPos.getDocumentId());
        assertEquals(pos1.getSentenceId(), retrievedPos.getSentenceId());
        assertEquals(pos1.getBeginPosition(), retrievedPos.getBeginPosition());
        assertEquals(pos1.getEndPosition(), retrievedPos.getEndPosition());
    }

    @Test
    void testBatchOperations() throws Exception {
        // Create test data
        Map<String, PositionListSoA> entries = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            Position pos = new Position(i, 1, 0, 5);
            PositionListSoA positions = new PositionListSoA();
            positions.add(pos);
            entries.put("key" + i, positions);
        }

        // Write batch
        try (WriteBatch batch = indexAccess.createWriteBatch()) {
            for (Map.Entry<String, PositionListSoA> entry : entries.entrySet()) {
                batch.put(
                    entry.getKey().getBytes(),
                    entry.getValue().serializeToCompositeBlob()
                );
            }
            indexAccess.write(batch);
        }

        // Verify entries
        for (String key : entries.keySet()) {
            Optional<PositionListSoA> retrieved = indexAccess.get(key.getBytes());
            assertTrue(retrieved.isPresent(), "Should retrieve entry for key: " + key);
            assertEquals(1, retrieved.get().getNumPositions(),
                "Should have correct number of positions for key: " + key);
        }
    }

    @Test
    void testMergePositions() throws Exception {
        byte[] key = "merge-test".getBytes();

        // Create first position list
        Position pos1 = new Position(1, 1, 0, 5);
        PositionListSoA positions1 = new PositionListSoA();
        positions1.add(pos1);
        indexAccess.put(key, positions1.serializeToCompositeBlob());

        // Create second position list
        Position pos2 = new Position(1, 2, 6, 10);
        PositionListSoA positions2 = new PositionListSoA();
        positions2.add(pos2);

        // Simulate merge: get existing, deserialize, merge, serialize, then put
        Optional<PositionListSoA> existingListOpt = indexAccess.get(key);
        assertTrue(existingListOpt.isPresent(), "Existing list should be present for merge");
        PositionListSoA mergedList = existingListOpt.get();
        mergedList.merge(positions2);
        indexAccess.put(key, mergedList.serializeToCompositeBlob());

        // Verify merge
        Optional<PositionListSoA> mergedResult = indexAccess.get(key);
        assertTrue(mergedResult.isPresent(), "Should retrieve merged positions");
        assertEquals(2, mergedResult.get().getNumPositions(), "Should contain all positions");
    }

    @Test
    void testIterator() throws Exception {
        // Create test data
        for (int i = 0; i < 5; i++) {
            Position pos = new Position(i, 1, 0, 5);
            PositionListSoA positions = new PositionListSoA();
            positions.add(pos);
            indexAccess.put(("key" + i).getBytes(), positions.serializeToCompositeBlob());
        }

        // Test iteration
        int count = 0;
        try (RocksIterator iterator = indexAccess.iterateFromFirst()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                byte[] value = iterator.value();
                assertNotNull(key, "Key should not be null");
                assertNotNull(value, "Value should not be null");

                PositionListSoA positions = PositionListSoA.deserializeFromCompositeBlob(value);
                assertEquals(1, positions.getNumPositions(),
                    "Should have correct number of positions");
                count++;
            }
        }
        assertEquals(5, count, "Should iterate over all entries");
    }

    // @Test
    // void testClosedOperations() throws Exception {
    //     indexAccess.close();
    //     PositionListSoA positions = new PositionListSoA();

    //     // Verify operations throw appropriate exceptions
    //     assertThrows(RocksDBException.class, () ->
    //         indexAccess.put("test".getBytes(), positions.serializeToCompositeBlob()));
    //     assertThrows(RocksDBException.class, () ->
    //         indexAccess.get("test".getBytes()));
    //     assertThrows(RocksDBException.class, () -> indexAccess.createWriteBatch());
    //     assertThrows(RocksDBException.class, () ->
    //         indexAccess.iterateFromFirst());
    //     assertThrows(RocksDBException.class, () ->
    //         indexAccess.seek("anykey".getBytes()));
    // }

    private void deleteDirectory(File directory) throws IOException {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        if (!file.delete()) {
                            throw new IOException("Failed to delete file: " + file);
                        }
                    }
                }
            }
            if (!directory.delete()) {
                throw new IOException("Failed to delete directory: " + directory);
            }
        }
    }
}