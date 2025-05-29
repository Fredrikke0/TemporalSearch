package com.example.core.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.WriteBatch;

// Note: Does NOT implement IndexAccess directly to avoid complex mocking/inheritance
// It provides a compatible API for testing purposes where an IndexAccess object is expected.
// import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
// Implement the interface
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;

/**
 * A mock implementation providing an IndexAccess-like API for testing purposes.
 * Implements IndexAccessInterface.
 * Stores data in an in-memory sorted map.
 */
public class MockIndexAccess implements IndexAccessInterface {

    private final String indexType;
    private final NavigableMap<ByteArrayWrapper, byte[]> dataStore;
    private boolean closed = false;

    public MockIndexAccess(String indexType) {
        this.indexType = indexType;
        // Use ConcurrentSkipListMap for thread safety and sorting (like LevelDB)
        this.dataStore = new ConcurrentSkipListMap<>();
    }

    // Convenience constructor for common "unigram" type
    public MockIndexAccess() {
        this("unigram");
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
    public DBIterator seek(byte[] key) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        return new MockDBIterator(dataStore, key, false);
    }

    @Override
    public DBIterator iterateFromFirst() throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        return new MockDBIterator(dataStore, null, true);
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
        throw new UnsupportedOperationException("WriteBatch not supported by MockIndexAccess");
    }

    @Override
    public void write(WriteBatch batch) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed: " + indexType, indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        throw new UnsupportedOperationException("WriteBatch not supported by MockIndexAccess");
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

    // --- Inner Mock Iterator Class ---

    private static class MockDBIterator implements DBIterator {
        private final List<Map.Entry<ByteArrayWrapper, byte[]>> entryList; // Holds all entries, sorted
        private int currentIndex; // Index of the next element to be returned by next()

        MockDBIterator(NavigableMap<ByteArrayWrapper, byte[]> map, byte[] seekKey, boolean iterateAll) {
            // Always create a sorted list of all entries
            this.entryList = new ArrayList<>(new TreeMap<>(map).entrySet());

            if (iterateAll) {
                this.currentIndex = 0; // Position at the beginning
            } else if (seekKey != null) {
                performSeek(seekKey);
            } else { // Should not happen: seekKey is null but not iterateAll
                this.currentIndex = 0;
            }
        }

        private void performSeek(byte[] key) {
            ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key);
            for (int i = 0; i < entryList.size(); i++) {
                if (entryList.get(i).getKey().compareTo(wrappedKey) >= 0) {
                    this.currentIndex = i;
                    return;
                }
            }
            this.currentIndex = entryList.size(); // Position after the last element if key is > all keys
        }

        @Override
        public void seek(byte[] key) {
            performSeek(key);
        }

        @Override
        public void seekToFirst() {
            this.currentIndex = 0;
        }

        @Override
        public void seekToLast() {
            if (entryList.isEmpty()) {
                this.currentIndex = 0;
            } else {
                this.currentIndex = entryList.size() - 1; // Position AT the last element
            }
        }

        @Override
        public Map.Entry<byte[], byte[]> peekNext() {
            if (currentIndex < entryList.size()) {
                Map.Entry<ByteArrayWrapper, byte[]> entry = entryList.get(currentIndex);
                return new SimpleImmutableEntry<>(entry.getKey().getData(), entry.getValue());
            }
            throw new NoSuchElementException();
        }

        @Override
        public boolean hasNext() {
            return currentIndex < entryList.size();
        }

        @Override
        public Map.Entry<byte[], byte[]> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Map.Entry<ByteArrayWrapper, byte[]> entry = entryList.get(currentIndex);
            currentIndex++;
            return new SimpleImmutableEntry<>(entry.getKey().getData(), entry.getValue());
        }

        @Override
        public Map.Entry<byte[], byte[]> peekPrev() {
            if (currentIndex > 0) {
                Map.Entry<ByteArrayWrapper, byte[]> entry = entryList.get(currentIndex - 1);
                return new SimpleImmutableEntry<>(entry.getKey().getData(), entry.getValue());
            }
            throw new NoSuchElementException();
        }

        @Override
        public boolean hasPrev() {
            return currentIndex > 0;
        }

        @Override
        public Map.Entry<byte[], byte[]> prev() {
            if (!hasPrev()) {
                throw new NoSuchElementException();
            }
            currentIndex--;
            Map.Entry<ByteArrayWrapper, byte[]> entry = entryList.get(currentIndex);
            return new SimpleImmutableEntry<>(entry.getKey().getData(), entry.getValue());
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Remove not supported by MockDBIterator");
        }

        @Override
        public void close() throws IOException {
            // No-op for mock
            // entryList.clear(); // Keep if iterator might be reused, though typically not.
        }
    }
}