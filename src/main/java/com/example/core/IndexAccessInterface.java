package com.example.core;

import java.util.Map;
import java.util.Optional;

import org.rocksdb.RocksIterator;

import com.example.index.AnnotationType;
import com.example.index.TypedAnnotationSynonymStore;

/**
 * Interface defining the core access methods for indexes.
 * Implemented by both the real RocksDB-backed IndexAccess
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
     * If the key is past the end of the data, the returned iterator's {@code isValid()}
     * method should return {@code false} (RocksIterator uses isValid(), not hasNext() for this check).
     * The caller is responsible for closing the iterator.
     *
     * @param key The key to seek to.
     * @return A RocksIterator positioned at or after the key.
     * @throws IndexAccessException if an error occurs accessing the index.
     */
    RocksIterator seek(byte[] key) throws IndexAccessException;

    /**
     * Creates a new iterator positioned at the first key in the database.
     * The caller is responsible for closing the iterator.
     *
     * @return A RocksIterator positioned at the first key.
     * @throws IndexAccessException if an error occurs accessing the index.
     */
    RocksIterator iterateFromFirst() throws IndexAccessException;

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
     * The caller is responsible for closing the WriteBatch.
     */
    org.rocksdb.WriteBatch createWriteBatch() throws IndexAccessException;

    /**
     * Writes a batch of operations atomically.
     * The WriteBatch itself should be closed by the caller after use.
     */
    void write(org.rocksdb.WriteBatch batch) throws IndexAccessException;

    /**
     * Gets the type of this index (e.g., "unigram", "pos").
     */
    String getIndexType();

    /**
     * Checks if the index access is currently open and usable.
     */
    boolean isOpen();

    /**
     * Gets the index metadata.
     * @return An Optional containing the index metadata, or an empty Optional if none exists.
     */
    Optional<Map<String, String>> getIndexMetadata();

    /**
     * Gets the specific type of annotation this index provides (e.g., NER, POS, DATE).
     * This helps in understanding what kind of data to expect from the index.
     * @return The AnnotationType of the index, or {@link AnnotationType#UNKNOWN} if not specified.
     */
    default AnnotationType getAnnotationType() {
        return AnnotationType.UNKNOWN;
    }

    /**
     * Gets the synonym store associated with this index, if any.
     * This is primarily used by stitch indexes to resolve synonym IDs for stitched annotations.
     * @return An Optional containing the TypedAnnotationSynonymStore, or an empty Optional if none exists.
     */
    default Optional<TypedAnnotationSynonymStore> getSynonymStore() {
        return Optional.empty();
    }

    /**
     * Gets the root path of this index.
     * This can be used by components like ValueLookupManager to store auxiliary data
     * in a sub-directory relative to the main index data.
     * @return The Path to the index directory.
     */
    java.nio.file.Path getIndexPath();

    /**
     * Closes the index access, releasing any underlying resources.
     * Overrides AutoCloseable.close().
     */
    @Override
    void close() throws IndexAccessException; // Allow Exception from AutoCloseable
}