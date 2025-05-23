package com.example.core;

import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.WriteBatch;

import java.util.Optional;

/**
 * Interface defining the core access methods for indexes.
 * Implemented by both the real LevelDB-backed IndexAccess 
 * and test mocks like MockIndexAccess.
 */
public interface IndexAccessInterface extends AutoCloseable {

    // Delimiter constants moved from IndexAccess class
    char DELIMITER = '\0';  // Null byte delimiter used for n-grams

    /**
     * Retrieves positions for a given key.
     * Deserializes the stored byte[] value into a PositionListSoA.
     */
    Optional<PositionListSoA> get(byte[] key) throws IndexAccessException;

    /**
     * Retrieves the raw byte[] value for a given key.
     */
    Optional<byte[]> getRaw(byte[] key) throws IndexAccessException;

    /**
     * Creates a new iterator over the database.
     * The caller is responsible for closing the iterator.
     */
    DBIterator iterator() throws IndexAccessException;

    /**
     * Creates a new iterator over the database with specific read options.
     * The caller is responsible for closing the iterator.
     */
    DBIterator iterator(ReadOptions options) throws IndexAccessException;

    /**
     * Stores or updates a key-value pair.
     * Note: Behavior for existing keys might differ between implementations 
     * (e.g., overwrite vs. merge for PositionLists).
     */
    void put(byte[] key, byte[] value) throws IndexAccessException;

    /**
     * Deletes a key-value pair.
     */
    void delete(byte[] key) throws IndexAccessException;

    /**
     * Creates a new write batch for atomic operations.
     */
    WriteBatch createWriteBatch() throws IndexAccessException;

    /**
     * Writes a batch of operations atomically.
     */
    void write(WriteBatch batch) throws IndexAccessException;

    /**
     * Gets the type of this index (e.g., "unigram", "pos").
     */
    String getIndexType();

    /**
     * Checks if the index access is currently open and usable.
     */
    boolean isOpen();

    /**
     * Closes the index access, releasing any underlying resources.
     * Overrides AutoCloseable.close().
     */
    @Override
    void close() throws Exception; // Allow Exception from AutoCloseable
} 