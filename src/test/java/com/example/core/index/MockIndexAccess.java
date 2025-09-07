package com.example.core.index;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

import org.apache.commons.codec.binary.Hex; // For logging byte arrays
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Note: Does NOT implement IndexAccess directly to avoid complex mocking/inheritance
// It provides a compatible API for testing purposes where an IndexAccess object is expected.
// import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
// Implement the interface
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.query.executor.FilteringContext;

/**
 * A mock implementation providing an IndexAccess-like API for testing purposes.
 * Implements IndexAccessInterface.
 * Stores data in an in-memory sorted map.
 */
public class MockIndexAccess implements IndexAccessInterface {

    private static final Logger logger = LoggerFactory.getLogger(MockIndexAccess.class);

    private final String indexType;
    private final NavigableMap<ByteArrayWrapper, byte[]> dataStore;
    private boolean closed = false;

    private RocksDB mockRocksDbInstance;
    private Path mockRocksDbPath;

    // Define an interface for WriteBatch operations to aid in mocking
    private interface WriteOperation {
        void apply(NavigableMap<ByteArrayWrapper, byte[]> store);
    }

    // Concrete operation classes
    private static class PutOperation implements WriteOperation {
        private final byte[] key;
        private final byte[] value;

        PutOperation(byte[] key, byte[] value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public void apply(NavigableMap<ByteArrayWrapper, byte[]> store) {
            store.put(new ByteArrayWrapper(key), value);
        }
    }

    private static class DeleteOperation implements WriteOperation {
        private final byte[] key;

        DeleteOperation(byte[] key) {
            this.key = key;
        }

        @Override
        public void apply(NavigableMap<ByteArrayWrapper, byte[]> store) {
            store.remove(new ByteArrayWrapper(key));
        }
    }

    // Mock implementation of WriteBatch
    private static class MockRocksWriteBatch extends WriteBatch {
        private final List<WriteOperation> operations = new ArrayList<>();

        public MockRocksWriteBatch() {
            super(); // Call to super constructor
        }

        @Override
        public void put(byte[] key, byte[] value) {
            operations.add(new PutOperation(key, value));
        }

        @Override
        public void delete(byte[] key) {
            operations.add(new DeleteOperation(key));
        }

        // Other WriteBatch methods (e.g., merge, putCF, deleteCF, clear) could be implemented if needed
        // For now, we'll keep it simple for the existing test failures.

        List<WriteOperation> getOperations() {
            return operations;
        }

        @Override
        public void close() {
            // In a real RocksDB WriteBatch, this releases native resources.
            // For the mock, we can clear the operations list if desired, or do nothing.
            operations.clear(); // Example: clear operations on close
            super.close(); // Important to call super.close()
        }
    }

    public MockIndexAccess(String indexType) {
        this.indexType = indexType;
        this.dataStore = new ConcurrentSkipListMap<>();
        RocksDB.loadLibrary();
        try {
            this.mockRocksDbPath = Files.createTempDirectory("mockrocksdb_" + indexType + "_");
            Options options = new Options().setCreateIfMissing(true);
            this.mockRocksDbInstance = RocksDB.open(options, this.mockRocksDbPath.toString());
            logger.info("MockIndexAccess [{}]: Initialized dummy RocksDB at {}", indexType, mockRocksDbPath.toString());
        } catch (RocksDBException | IOException e) {
            logger.error("MockIndexAccess [{}]: Failed to initialize dummy RocksDB: {}", indexType, e.getMessage(), e);
            throw new RuntimeException("Failed to initialize dummy RocksDB for mock index: " + indexType, e);
        }
    }

    // Convenience constructor for common "unigram" type
    public MockIndexAccess() {
        this("mock");
    }

    /**
     * Helper method to add test data.
     * Converts the string key to bytes and creates/serializes a PositionListSoA.
     */
    public void addTestData(String key, int docId, int sentenceId, int begin, int end) throws IOException {
        if (closed) throw new IllegalStateException("Index is closed");
        logger.debug("MockIndexAccess [{}]: addTestData for key='{}'", indexType, key);
        ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key.getBytes(StandardCharsets.UTF_8));
        Position pos = new Position(docId, sentenceId, begin, end);

        PositionListSoA list;
        byte[] existingValue = dataStore.get(wrappedKey);
        int positionsBefore = 0;
        if (existingValue != null) {
            list = PositionListSoA.deserializeFromCompositeBlob(existingValue);
            positionsBefore = list.getNumPositions();
            logger.debug("MockIndexAccess [{}]: Key='{}' existed. Positions before add: {}", indexType, key, positionsBefore);
        } else {
            list = new PositionListSoA();
            logger.debug("MockIndexAccess [{}]: Key='{}' is new.", indexType, key);
        }
        list.add(pos);
        // Sort to mimic real index behavior where data is sorted by document ID
        list.sort();
        int positionsAfter = list.getNumPositions();
        byte[] serializedValue = list.serializeToCompositeBlob();
        dataStore.put(wrappedKey, serializedValue);
        logger.debug("MockIndexAccess [{}]: Key='{}', Positions after add: {}, Serialized size: {} bytes", indexType, key, positionsAfter, serializedValue.length);
    }

    /**
     * Helper method to add pre-serialized test data.
     * If the key already exists, the new positions are merged with the existing ones.
     */
    public void addTestData(String key, PositionListSoA newPositions) throws IOException {
        if (closed) throw new IllegalStateException("Index is closed");
        logger.debug("MockIndexAccess [{}]: addTestData (pre-serialized) for key='{}', newPositions count: {}", indexType, key, newPositions.getNumPositions());
        ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key.getBytes(StandardCharsets.UTF_8));

        PositionListSoA mergedList;
        byte[] existingValue = dataStore.get(wrappedKey);
        int positionsBefore = 0;
        if (existingValue != null) {
            mergedList = PositionListSoA.deserializeFromCompositeBlob(existingValue);
            positionsBefore = mergedList.getNumPositions();
            logger.debug("MockIndexAccess [{}]: Key='{}' existed (pre-serialized). Positions before merge: {}", indexType, key, positionsBefore);
            mergedList.addAll(newPositions);
            // Sort to mimic real index behavior where data is sorted by document ID
            mergedList.sort();
        } else {
            mergedList = newPositions;
            // Sort to mimic real index behavior where data is sorted by document ID
            mergedList.sort();
            logger.debug("MockIndexAccess [{}]: Key='{}' is new (pre-serialized).", indexType, key);
        }
        byte[] serializedValue = mergedList.serializeToCompositeBlob();
        dataStore.put(wrappedKey, serializedValue);
        logger.debug("MockIndexAccess [{}]: Key='{}' (pre-serialized), Positions after merge: {}, Serialized size: {} bytes", indexType, key, mergedList.getNumPositions(), serializedValue.length);
    }

     /**
     * Helper method to add pre-serialized test data with byte key.
     * Renamed from addTestData to addRawTestData.
     */
    public void addRawTestData(byte[] key, byte[] value) {
        if (closed) throw new IllegalStateException("Index is closed");
        logger.debug("MockIndexAccess [{}]: addRawTestData for key (length {}), value size: {} bytes", indexType, key.length, value.length);
        dataStore.put(new ByteArrayWrapper(key), value);
    }

    /**
     * Clears all data from the mock index.
     */
    public void clearAllData() {
        if (closed) throw new IllegalStateException("Index is closed. Cannot clear data.");
        dataStore.clear();
        System.out.println("MockIndexAccess [" + indexType + "] data cleared.");
    }

    @Override
    public Optional<PositionListSoA> get(byte[] key) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key);
        String keyHex = Hex.encodeHexString(key);
        logger.debug("MockIndexAccess [{}]: get() called for key (hex): {}", indexType, keyHex);
        byte[] value = dataStore.get(wrappedKey);
        if (value == null) {
            logger.debug("MockIndexAccess [{}]: get() key (hex): {} NOT FOUND", indexType, keyHex);
            return Optional.empty();
        }
        logger.debug("MockIndexAccess [{}]: get() key (hex): {} FOUND, value length: {}", indexType, keyHex, value.length);
        try {
            // Deserialize to PositionListSoA
            return Optional.of(PositionListSoA.deserializeFromCompositeBlob(value));
        } catch (IOException e) {
            throw new IndexAccessException(
                "Failed to deserialize PositionListSoA due to IO error for key",
                indexType,
                IndexAccessException.ErrorType.READ_ERROR,
                e
            );
        } catch (RuntimeException e) {
            throw new IndexAccessException(
                "Failed to deserialize PositionListSoA for key",
                indexType,
                IndexAccessException.ErrorType.READ_ERROR,
                e
            );
        }
    }

    @Override
    public Optional<byte[]> getRaw(byte[] key) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key);
        String keyHex = Hex.encodeHexString(key);
        logger.debug("MockIndexAccess [{}]: getRaw() called for key (hex): {}", indexType, keyHex);
        byte[] value = dataStore.get(wrappedKey);
        if (value == null) {
            logger.debug("MockIndexAccess [{}]: getRaw() key (hex): {} NOT FOUND", indexType, keyHex);
            return Optional.empty();
        }
        logger.debug("MockIndexAccess [{}]: getRaw() key (hex): {} FOUND, value length: {}", indexType, keyHex, value.length);
        return Optional.of(value);
    }

    @Override
    public RocksIterator seek(byte[] key) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        logger.debug("MockIndexAccess [{}]: seek() called for key (hex): {}", indexType, Hex.encodeHexString(key));
        return new MockRocksIterator(this.mockRocksDbInstance, dataStore, key, false, this.indexType);
    }

    @Override
    public RocksIterator iterateFromFirst() throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        logger.debug("MockIndexAccess [{}]: iterateFromFirst() called", indexType);
        return new MockRocksIterator(this.mockRocksDbInstance, dataStore, null, true, this.indexType);
    }

    @Override
    public void put(byte[] key, byte[] value) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        dataStore.put(new ByteArrayWrapper(key), value);
    }

    @Override
    public void delete(byte[] key) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        dataStore.remove(new ByteArrayWrapper(key));
    }

    @Override
    public WriteBatch createWriteBatch() throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        // WriteBatch operations are complex to mock properly, throw unsupported for now
        // throw new UnsupportedOperationException("WriteBatch not supported by MockIndexAccess");
        return new MockRocksWriteBatch();
    }

    @Override
    public void write(WriteBatch batch) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        // throw new UnsupportedOperationException("WriteBatch not supported by MockIndexAccess");
        if (!(batch instanceof MockRocksWriteBatch)) {
            throw new IllegalArgumentException("Provided WriteBatch is not a MockRocksWriteBatch instance.");
        }
        MockRocksWriteBatch mockBatch = (MockRocksWriteBatch) batch;
        for (WriteOperation op : mockBatch.getOperations()) {
            op.apply(this.dataStore);
        }
        // According to RocksDB JNI, WriteBatch is often single-use and should be closed by the caller.
        // However, if the batch is managed by this class (e.g., if createWriteBatch always returned
        // the same instance or a reusable one), then this class might also close it.
        // For now, we assume the caller manages the lifecycle of the batch passed to write().
    }

    @Override
    public String getIndexType() {
        return indexType;
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            logger.debug("Closing MockIndexAccess for index type: {}", indexType);
            closed = true;
            dataStore.clear();
            if (this.mockRocksDbInstance != null) {
                this.mockRocksDbInstance.close();
                logger.info("MockIndexAccess [{}]: Closed dummy RocksDB instance.", indexType);
            }
            if (this.mockRocksDbPath != null) {
                try {
                    // Recursively delete the temp directory
                    Files.walk(mockRocksDbPath)
                         .sorted(Comparator.reverseOrder())
                         .map(Path::toFile)
                         .forEach(File::delete);
                    logger.info("MockIndexAccess [{}]: Deleted dummy RocksDB directory {}", indexType, mockRocksDbPath.toString());
                } catch (IOException e) {
                    logger.warn("MockIndexAccess [{}]: Failed to delete dummy RocksDB directory {}: {}", indexType, mockRocksDbPath.toString(), e.getMessage());
                }
            }
        }
    }

    public int getStoreSize() {
        return dataStore.size();
    }

    @Override
    public Path getIndexPath() {
        return this.mockRocksDbPath;
    }

    @Override
    public Optional<PositionListSoA> getMergedPositions(String baseTerm, Optional<FilteringContext> context,
                                                        com.example.query.executor.AttributeRequirements requirements) throws IOException, IndexAccessException {
        if (closed) {
            throw new IndexAccessException("MockIndexAccess is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        }
        logger.debug("MockIndexAccess [{}]: getMergedPositions for baseTerm='{}', with context: {}", indexType, baseTerm, context.isPresent());

        byte[] baseTermBytes = baseTerm.getBytes(StandardCharsets.UTF_8);
        ByteArrayWrapper baseKeyWrapper = new ByteArrayWrapper(baseTermBytes);

        byte[] blob = dataStore.get(baseKeyWrapper);

        if (blob != null) { // Base term exists
            if (blob.length == 0) {
                logger.warn("Base term key '{}' found in MockIndexAccess but has empty data.", baseTerm);
                return Optional.empty(); // Or an empty PositionListSoA if context is present
            }
            try {
                PositionListSoA resultSoa = PositionListSoA.deserializeWithFilters(blob, context, requirements);
                // resultSoa could be empty after filtering
                return resultSoa.isEmpty() ? Optional.empty() : Optional.of(resultSoa);
            } catch (IOException e) {
                throw new IndexAccessException("Failed to deserialize base term with filters: " + baseTerm, indexType, IndexAccessException.ErrorType.READ_ERROR, e);
            }
        }

        // Base term missing: writers no longer produce "term#0". Return empty.
        return Optional.empty();
    }

    // --- Inner Mock Iterator Class ---

    private static class MockRocksIterator extends RocksIterator {
        private static final Logger iterLogger = LoggerFactory.getLogger(MockRocksIterator.class);

        private final NavigableMap<ByteArrayWrapper, byte[]> originalMapRef;
        private final List<Map.Entry<ByteArrayWrapper, byte[]>> entryList;
        private int currentIndex;
        private boolean valid = false;
        private final RocksDB parentRocksDbInstance; // Store the parent DB for the iterator
        private final String parentIndexType; // Store parent's index type for logging

        MockRocksIterator(RocksDB parentDb, NavigableMap<ByteArrayWrapper, byte[]> map, byte[] seekKey, boolean iterateAll, String parentIndexType) {
            super(parentDb, 0L); // Call super constructor with actual parentDb and a dummy handle
            this.parentRocksDbInstance = parentDb;
            this.parentIndexType = parentIndexType;
            this.originalMapRef = map;
            this.entryList = new ArrayList<>(map.entrySet());
            iterLogger.debug("MockRocksIterator: Initialized with {} entries. IterateAll: {}", entryList.size(), iterateAll);

            if (iterateAll || seekKey == null) {
                seekToFirstInternal();
            } else {
                iterLogger.debug("MockRocksIterator: Performing initial seek for key (length {})", seekKey.length);
                performSeek(seekKey);
            }
        }

        private void performSeek(byte[] key) {
            ByteArrayWrapper wrappedTarget = new ByteArrayWrapper(key);
            String targetKeyHex = Hex.encodeHexString(key);
            iterLogger.debug("Iterator for [{}]: performSeek for key (hex): {}", parentIndexType, targetKeyHex);
            currentIndex = 0;
            valid = false;
            for (Map.Entry<ByteArrayWrapper, byte[]> entry : entryList) {
                if (wrappedTarget.compareTo(entry.getKey()) <= 0) {
                    valid = true;
                    String foundKeyHex = Hex.encodeHexString(entry.getKey().getData());
                    iterLogger.debug("Iterator for [{}]: performSeek found entry at index {}. Seek target (hex): {}, Found key (hex): {}", parentIndexType, currentIndex, targetKeyHex, foundKeyHex);
                    return;
                }
                currentIndex++;
            }
            // If loop finishes, no key >= target was found
            iterLogger.debug("Iterator for [{}]: performSeek target (hex): {} NOT FOUND (or beyond end of list)", parentIndexType, targetKeyHex);
            valid = false; // No valid entry found or reached end
        }

        private void seekToFirstInternal() {
            if (entryList.isEmpty()) {
                this.currentIndex = 0;
                this.valid = false;
            } else {
                this.currentIndex = 0;
                this.valid = true;
            }
        }

        @Override
        public boolean isValid() {
            return valid && currentIndex >= 0 && currentIndex < entryList.size();
        }

        @Override
        public void seekToFirst() {
            seekToFirstInternal();
        }

        @Override
        public void seekToLast() {
            if (entryList.isEmpty()) {
                this.currentIndex = 0;
                this.valid = false;
            } else {
                this.currentIndex = entryList.size() - 1;
                this.valid = true;
            }
        }

        @Override
        public void seek(byte[] target) {
            performSeek(target);
        }

        @Override
        public void seekForPrev(byte[] target) {
            ByteArrayWrapper wrappedKey = new ByteArrayWrapper(target);
            for (int i = entryList.size() - 1; i >= 0; i--) {
                if (entryList.get(i).getKey().compareTo(wrappedKey) <= 0) {
                    this.currentIndex = i;
                    this.valid = true;
                    return;
                }
            }
            this.currentIndex = -1;
            this.valid = false;
        }

        @Override
        public void next() {
            if (!isValid()) {
                iterLogger.warn("Iterator for [{}]: next() called on invalid iterator.", parentIndexType);
                return; // Or throw, consistent with RocksIterator behavior if known
            }
            currentIndex++;
            if (currentIndex >= entryList.size()) {
                iterLogger.debug("Iterator for [{}]: next() moved beyond end of list.", parentIndexType);
                valid = false;
            } else {
                valid = true; // Still valid
                String currentKeyHex = Hex.encodeHexString(entryList.get(currentIndex).getKey().getData());
                iterLogger.debug("Iterator for [{}]: next() moved to index {}, key (hex): {}", parentIndexType, currentIndex, currentKeyHex);
            }
        }

        @Override
        public void prev() {
            if (!isValid()) {
                valid = false;
                return;
            }
            currentIndex--;
            if (currentIndex < 0) {
                valid = false;
            }
        }

        @Override
        public byte[] key() {
            if (!isValid()) {
                throw new NoSuchElementException("Iterator is not valid or past the end.");
            }
            byte[] keyBytes = entryList.get(currentIndex).getKey().getData();
            iterLogger.debug("MockRocksIterator: key() called. currentIndex={}, key (length {}): '{}'", currentIndex, keyBytes.length, new String(keyBytes, StandardCharsets.UTF_8));
            return keyBytes;
        }

        @Override
        public byte[] value() {
            if (!isValid()) {
                throw new NoSuchElementException("Iterator is not valid or past the end.");
            }
            byte[] valueBytes = entryList.get(currentIndex).getValue();
            iterLogger.debug("MockRocksIterator: value() called. currentIndex={}, value size: {}", currentIndex, valueBytes.length);
            return valueBytes;
        }

        @Override
        public void status() throws RocksDBException {
            // No-op for mock
        }

        @Override
        public synchronized void close() {
            super.close();
            this.valid = false;
        }
    }
}