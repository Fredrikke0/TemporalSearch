package com.example.core.index;

// Note: Does NOT implement IndexAccess directly to avoid complex mocking/inheritance
// It provides a compatible API for testing purposes where an IndexAccess object is expected.
// import com.example.core.IndexAccess; 
import com.example.core.IndexAccessException;
import com.example.core.Position;
import com.example.core.PositionList;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.WriteBatch;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

// Implement the interface
import com.example.core.IndexAccessInterface; 

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
     * Converts the string key to bytes and creates/serializes a PositionList.
     */
    public void addTestData(String key, int docId, int sentenceId, int begin, int end) throws IOException {
        if (closed) throw new IllegalStateException("Index is closed");
        ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key.getBytes(StandardCharsets.UTF_8));
        Position pos = new Position(docId, sentenceId, begin, end);
        
        // Retrieve existing list if present, or create a new one
        PositionList list;
        byte[] existingValue = dataStore.get(wrappedKey);
        if (existingValue != null) {
            list = PositionList.deserialize(existingValue);
        } else {
            list = new PositionList();
        }
        list.add(pos);
        dataStore.put(wrappedKey, list.serialize());
    }

    /**
     * Helper method to add pre-serialized test data.
     * If the key already exists, the new positions are merged with the existing ones.
     */
    public void addTestData(String key, PositionList newPositions) throws IOException {
        if (closed) throw new IllegalStateException("Index is closed");
        ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key.getBytes(StandardCharsets.UTF_8));

        PositionList mergedList;
        byte[] existingValue = dataStore.get(wrappedKey);
        if (existingValue != null) {
            mergedList = PositionList.deserialize(existingValue);
            for (Position pos : newPositions.getPositions()) { // Assuming PositionList has a getPositions() or is iterable
                mergedList.add(pos);
            }
        } else {
            mergedList = newPositions;
        }
        dataStore.put(wrappedKey, mergedList.serialize());
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
    public Optional<PositionList> get(byte[] key) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed", indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        byte[] value = dataStore.get(new ByteArrayWrapper(key));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(PositionList.deserialize(value));
        } catch (IOException e) { // Catch IOException specifically
            throw new IndexAccessException(
                "Failed to deserialize PositionList due to IO error for key", 
                indexType, 
                IndexAccessException.ErrorType.READ_ERROR,
                e
            );
        } catch (RuntimeException e) { // Catch other potential deserialization errors
            throw new IndexAccessException(
                "Failed to deserialize PositionList for key", 
                indexType, 
                IndexAccessException.ErrorType.READ_ERROR, // Use READ_ERROR
                e // Pass the original exception as the cause
            ); 
        }
    }

    @Override
    public Optional<byte[]> getRaw(byte[] key) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed", indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        return Optional.ofNullable(dataStore.get(new ByteArrayWrapper(key)));
    }

    @Override
    public DBIterator iterator() throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed", indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        // Return a simple iterator over the current snapshot of the map
        return new MockDBIterator(dataStore);
    }

    @Override
    public DBIterator iterator(ReadOptions options) throws IndexAccessException {
         if (closed) throw new IndexAccessException("Index is closed", indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        // Ignore ReadOptions for this mock
        return iterator();
    }

    @Override
    public void put(byte[] key, byte[] value) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed", indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        dataStore.put(new ByteArrayWrapper(key), value);
    }

    @Override
    public void delete(byte[] key) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed", indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        dataStore.remove(new ByteArrayWrapper(key));
    }

    @Override
    public WriteBatch createWriteBatch() throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed", indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
        // WriteBatch operations are complex to mock properly, throw unsupported for now
        throw new UnsupportedOperationException("WriteBatch not supported by MockIndexAccess");
    }

    @Override
    public void write(WriteBatch batch) throws IndexAccessException {
        if (closed) throw new IndexAccessException("Index is closed", indexType, IndexAccessException.ErrorType.RESOURCE_ERROR);
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
    public void close() throws Exception {
        if (!closed) {
            closed = true;
            dataStore.clear(); // Clear data on close
            System.out.println("MockIndexAccess [" + indexType + "] closed.");
        }
    }

    // --- Inner Mock Iterator Class ---

    private static class MockDBIterator implements DBIterator {
        private final NavigableMap<ByteArrayWrapper, byte[]> originalMap;
        private Iterator<Map.Entry<ByteArrayWrapper, byte[]>> iterator;
        private Map.Entry<ByteArrayWrapper, byte[]> currentEntry;
        private Map.Entry<ByteArrayWrapper, byte[]> nextEntryBuffer = null;

        MockDBIterator(NavigableMap<ByteArrayWrapper, byte[]> map) {
            // Store the original map for seeking
            this.originalMap = map; 
            // Initialize iterator over a copy of the full map
            this.iterator = new TreeMap<>(this.originalMap).entrySet().iterator(); 
            this.currentEntry = null;
        }

        @Override
        public void seek(byte[] key) {
             // Wrap the key for comparison
             ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key);
             // Get the portion of the map >= key
             NavigableMap<ByteArrayWrapper, byte[]> tailMap = originalMap.tailMap(wrappedKey, true);
             // Reset iterator to the tailMap (using a copy)
             this.iterator = new TreeMap<>(tailMap).entrySet().iterator();
             this.currentEntry = null; // Invalidate current entry after seek
        }

        @Override
        public void seekToFirst() {
            // Reset the iterator to the beginning of the original map (using a copy)
            this.iterator = new TreeMap<>(this.originalMap).entrySet().iterator();
            this.currentEntry = null; // Invalidate current entry
        }

        @Override
        public void seekToLast() {
            throw new UnsupportedOperationException("seekToLast not implemented in MockDBIterator");
        }

        @Override
        public Map.Entry<byte[], byte[]> peekNext() {
            if (nextEntryBuffer == null) {
                if (!iterator.hasNext()) {
                    throw new NoSuchElementException();
                }
                nextEntryBuffer = iterator.next();
            }
            // Return a copy as Map.Entry<byte[], byte[]>
            return Map.entry(nextEntryBuffer.getKey().getData(), nextEntryBuffer.getValue());
        }
        
        @Override
        public boolean hasNext() {
            return nextEntryBuffer != null || iterator.hasNext();
        }

        @Override
        public Map.Entry<byte[], byte[]> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (nextEntryBuffer != null) {
                currentEntry = nextEntryBuffer;
                nextEntryBuffer = null;
            } else {
                currentEntry = iterator.next();
            }
            return Map.entry(currentEntry.getKey().getData(), currentEntry.getValue());
        }
        
        @Override
        public Map.Entry<byte[], byte[]> peekPrev() {
             throw new UnsupportedOperationException("peekPrev not implemented in MockDBIterator");
        }

        @Override
        public boolean hasPrev() {
            throw new UnsupportedOperationException("hasPrev not implemented in MockDBIterator");
        }

        @Override
        public Map.Entry<byte[], byte[]> prev() {
            throw new UnsupportedOperationException("prev not implemented in MockDBIterator");
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("remove not supported");
        }

        @Override
        public void close() throws IOException {
            // No resources to close for this simple mock iterator
        }
    }
} 