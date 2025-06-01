package com.example.core.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;

// Note: Does NOT implement IndexAccess directly to avoid complex mocking/inheritance
// It provides a compatible API for testing purposes where an IndexAccess object is expected.
// import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
// Implement the interface
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationType;
import com.example.index.TypedAnnotationSynonymStore;

/**
 * A mock implementation providing an IndexAccess-like API for testing purposes.
 * Implements IndexAccessInterface.
 * Stores data in an in-memory sorted map.
 */
public class MockIndexAccess implements IndexAccessInterface {

    private final String indexType;
    private final NavigableMap<ByteArrayWrapper, byte[]> dataStore;
    private boolean closed = false;
    private final AnnotationType annotationType;
    private final TypedAnnotationSynonymStore synonymStore;
    private final Map<String, String> metadata;

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

    public MockIndexAccess(String indexType, AnnotationType annotationType, TypedAnnotationSynonymStore synonymStore, Map<String, String> metadata) {
        this.indexType = indexType;
        this.annotationType = annotationType;
        this.synonymStore = synonymStore;
        this.metadata = metadata;
        // Use ConcurrentSkipListMap for thread safety and sorting (like LevelDB)
        this.dataStore = new ConcurrentSkipListMap<>();
    }

    // Convenience constructor for common "unigram" type
    public MockIndexAccess() {
        this("unigram", null, null, null);
    }

    /**
     * Helper method to add test data.
     * Converts the string key to bytes and creates/serializes a PositionListSoA.
     */
    public void addTestData(String key, int docId, int sentenceId, int begin, int end) throws IOException {
        if (closed) throw new IllegalStateException("Index is closed");
        ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key.getBytes(StandardCharsets.UTF_8));
        Position pos = new Position(docId, sentenceId, begin, end);

        // Retrieve existing list if present, or create a new one
        PositionListSoA list;
        byte[] existingValue = dataStore.get(wrappedKey);
        if (existingValue != null) {
            list = PositionListSoA.deserializeFromCompositeBlob(existingValue);
        } else {
            list = new PositionListSoA();
        }
        list.add(pos);
        dataStore.put(wrappedKey, list.serializeToCompositeBlob());
    }

    /**
     * Helper method to add pre-serialized test data.
     * If the key already exists, the new positions are merged with the existing ones.
     */
    public void addTestData(String key, PositionListSoA newPositions) throws IOException {
        if (closed) throw new IllegalStateException("Index is closed");
        ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key.getBytes(StandardCharsets.UTF_8));

        PositionListSoA mergedList;
        byte[] existingValue = dataStore.get(wrappedKey);
        if (existingValue != null) {
            mergedList = PositionListSoA.deserializeFromCompositeBlob(existingValue);
            mergedList.addAll(newPositions);
        } else {
            mergedList = newPositions;
        }
        dataStore.put(wrappedKey, mergedList.serializeToCompositeBlob());
    }

     /**
     * Helper method to add pre-serialized test data with byte key.
     * Renamed from addTestData to addRawTestData.
     */
    public void addRawTestData(byte[] key, byte[] value) {
        if (closed) throw new IllegalStateException("Index is closed");
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
        byte[] value = dataStore.get(new ByteArrayWrapper(key));
        if (value == null) {
            return Optional.empty();
        }
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
        return Optional.ofNullable(dataStore.get(new ByteArrayWrapper(key)));
    }

    @Override
    public RocksIterator seek(byte[] key) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        return new MockRocksIterator(dataStore, key, false);
    }

    @Override
    public RocksIterator iterateFromFirst() throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        return new MockRocksIterator(dataStore, null, true);
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
    public void close() {
        if (!closed) {
            closed = true;
            dataStore.clear(); // Clear data on close
            System.out.println("MockIndexAccess [" + indexType + "] closed.");
        }
    }

    public int getStoreSize() {
        return dataStore.size();
    }

    @Override
    public AnnotationType getAnnotationType() {
        return this.annotationType;
    }

    @Override
    public Optional<TypedAnnotationSynonymStore> getSynonymStore() {
        return Optional.ofNullable(this.synonymStore);
    }

    @Override
    public Optional<Map<String, String>> getIndexMetadata() {
        return Optional.ofNullable(this.metadata);
    }

    // --- Inner Mock Iterator Class ---

    private static class MockRocksIterator extends RocksIterator {
        private final List<Map.Entry<ByteArrayWrapper, byte[]>> entryList;
        private int currentIndex;
        private boolean valid = false;

        MockRocksIterator(NavigableMap<ByteArrayWrapper, byte[]> map, byte[] seekKey, boolean iterateAll) {
            super(null, 0L); // Pass null for RocksDB and 0L for nativeHandle
            this.entryList = new ArrayList<>(new TreeMap<>(map).entrySet());

            if (iterateAll) {
                seekToFirstInternal();
            } else if (seekKey != null) {
                performSeek(seekKey);
            } else {
                seekToFirstInternal();
            }
        }

        private void performSeek(byte[] key) {
            ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key);
            for (int i = 0; i < entryList.size(); i++) {
                if (entryList.get(i).getKey().compareTo(wrappedKey) >= 0) {
                    this.currentIndex = i;
                    this.valid = true;
                    return;
                }
            }
            this.currentIndex = entryList.size();
            this.valid = false;
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
                valid = false;
                return;
            }
            currentIndex++;
            if (currentIndex >= entryList.size()) {
                valid = false;
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
                 throw new NoSuchElementException("Iterator is not valid or out of bounds at key()");
            }
            // Use getData() from ByteArrayWrapper
            return entryList.get(currentIndex).getKey().getData();
        }

        @Override
        public byte[] value() {
            if (!isValid()) {
                 throw new NoSuchElementException("Iterator is not valid or out of bounds at value()");
            }
            return entryList.get(currentIndex).getValue();
        }

        @Override
        public void status() throws RocksDBException {
            // No-op for mock
        }

        // close() is inherited from RocksObject, which RocksIterator extends.
        // The default close() in RocksObject handles the native handle if isOwningHandle() is true.
        // We called super(0L), so the native handle is 0.
        // isOwningHandle() is initially true. RocksObject.close() will set closed=true.
        // We can add valid = false here if desired.
        @Override
        public synchronized void close() {
            super.close(); // Calls RocksObject.close()
            this.valid = false; // Mark as invalid after close
        }
    }
}