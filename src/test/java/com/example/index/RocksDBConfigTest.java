package com.example.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

public class RocksDBConfigTest {
    private Path tempDir;
    private RocksDB db;
    private Options options;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("rocksdb-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (db != null && db.isOwningHandle()) {
            db.close();
        }
        if (options != null && options.isOwningHandle()) {
            options.close();
        }
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(path -> {
                     try {
                         Files.deleteIfExists(path);
                     } catch (IOException e) {
                         // Ignore cleanup errors
                     }
                 });
        }
    }

    @Test
    void testOptimizedConfiguration() throws IOException, RocksDBException {
        options = RocksDBConfig.createOptimizedOptions();
        Path dbPath = tempDir.resolve("optimized");
        Files.createDirectories(dbPath);
        db = RocksDB.open(options, dbPath.toString());

        final int BATCH_SIZE = 10_000;

        try (WriteBatch batch = new WriteBatch()) {
            for (int i = 0; i < 100_000; i++) {
                String key = String.format("key-%06d", i);
                String value = "value-" + i;
                batch.put(key.getBytes(), value.getBytes());

                if ((i + 1) % BATCH_SIZE == 0 || i == 99_999) {
                    if (batch.count() > 0) {
                         try (WriteOptions wo = new WriteOptions()) {
                            db.write(wo, batch);
                         }
                        batch.clear();
                    }
                }
            }
        }

        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            int keyNum = random.nextInt(100_000);
            String key = String.format("key-%06d", keyNum);
            byte[] valueBytes = db.get(key.getBytes());
            assertNotNull(valueBytes, "Value should exist for key: " + key);
            assertEquals("value-" + keyNum, new String(valueBytes));
        }
    }
}