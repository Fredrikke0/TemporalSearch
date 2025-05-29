package com.example.core;

import java.util.Optional;

import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.WriteBatch;

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
     * Creates a new iterator positioned at or after the specified key.
     * If the key is null or empty, or if seeking before the first key,
     * the behavior might depend on the underlying implementation (e.g., start from first).
     * If the key is past the end of the data, the returned iterator's {@code hasNext()}
     * method should return {@code false}.
     * The caller is responsible for closing the iterator.
     *
     * @param key The key to seek to.
     * @return A DBIterator positioned at or after the key.
     * @throws IndexAccessException if an error occurs accessing the index.
     */
    DBIterator seek(byte[] key) throws IndexAccessException;

    /**
     * Creates a new iterator positioned at the first key in the database.
     * The caller is responsible for closing the iterator.
     *
     * @return A DBIterator positioned at the first key.
     * @throws IndexAccessException if an error occurs accessing the index.
     */
    DBIterator iterateFromFirst() throws IndexAccessException;

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
    void close() throws IndexAccessException; // Allow Exception from AutoCloseable
}