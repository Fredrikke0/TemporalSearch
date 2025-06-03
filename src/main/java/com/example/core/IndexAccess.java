package com.example.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core class for accessing RocksDB-based indexes.
 * Provides unified access for both read and write operations.
 */
public class IndexAccess implements IndexAccessInterface {
    private static final Logger logger = LoggerFactory.getLogger(IndexAccess.class);

    // Delimiter constant inherited from IndexAccessInterface

    private final RocksDB db;
    private final String indexPath;
    private final String indexType;
    private final AtomicBoolean isOpen;
    private final Options options; // Store options for closing

    /**
     * Creates a new IndexAccess instance for a specific index type.
     *
     * @param indexPath Full path to the index directory
     * @param indexType The type of index (e.g., "unigram", "bigram", "dependency")
     * @param options RocksDB options for this index
     * @throws IndexAccessException if initialization fails
     */
    public IndexAccess(Path indexPath, String indexType, Options options) throws IndexAccessException {
        this.indexType = indexType;
        this.indexPath = indexPath.toString();
        this.isOpen = new AtomicBoolean(true);
        this.options = options;

        try {
            File indexDir = new File(this.indexPath);
            if (!indexDir.exists()) {
                if (!indexDir.mkdirs()) {
                    throw new IndexAccessException(
                        "Failed to create index directory: " + this.indexPath,
                        indexType,
                        IndexAccessException.ErrorType.INITIALIZATION_ERROR
                    );
                }
            }
            this.db = RocksDB.open(options, indexDir.getAbsolutePath());
            //logger.debug("Initialized IndexAccess for type {} at {}", indexType, this.indexPath);
        } catch (RocksDBException e) {
            this.isOpen.set(false); // Ensure isOpen reflects the failure
            if(this.options != null) { // Attempt to close options if open failed
                this.options.close();
            }
            throw new IndexAccessException(
                "Failed to initialize RocksDB index: " + e.getMessage(),
                indexType,
                IndexAccessException.ErrorType.INITIALIZATION_ERROR,
                e
            );
        }
    }

    /**
     * Writes a batch of operations atomically.
     * Implements the interface method.
     */
    @Override
    public void write(org.rocksdb.WriteBatch batch) throws IndexAccessException {
        checkOpen();
        try (WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(true);
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw new IndexAccessException(
                "Failed to write batch: " + e.getMessage(),
                indexType,
                IndexAccessException.ErrorType.WRITE_ERROR,
                e
            );
        }
    }

    /**
     * Creates a new write batch.
     * Implements the interface method.
     */
    @Override
    public org.rocksdb.WriteBatch createWriteBatch() throws IndexAccessException {
        checkOpen();
        return new WriteBatch();
    }

    /**
     * Retrieves positions for a given key, deserializing to PositionListSoA.
     */
    @Override
    public Optional<PositionListSoA> get(byte[] key) throws IndexAccessException {
        checkOpen();
        try {
            byte[] value = db.get(key);
            if (value == null) {
                return Optional.empty();
            }
            // Deserialize to PositionListSoA instead of PositionList
            return Optional.of(PositionListSoA.deserializeFromCompositeBlob(value));
        } catch (IOException | RocksDBException e) {
            throw new IndexAccessException(
                "Failed to get entry: " + e.getMessage(),
                indexType,
                IndexAccessException.ErrorType.READ_ERROR,
                e
            );
        }
    }

    /**
     * Creates a new iterator positioned at or after the specified key.
     * The caller is responsible for closing the iterator.
     */
    @Override
    public RocksIterator seek(byte[] key) throws IndexAccessException {
        checkOpen();
        RocksIterator iterator = db.newIterator();
        if (key != null) {
            iterator.seek(key);
        } else {
            iterator.seekToFirst();
        }
        return iterator;
    }

    /**
     * Retrieves the raw byte[] value for a given key.
     */
    @Override
    public Optional<byte[]> getRaw(byte[] key) throws IndexAccessException {
        checkOpen();
        try {
            return Optional.ofNullable(db.get(key));
        } catch (RocksDBException e) {
             throw new IndexAccessException(
                "Failed to get raw entry: " + e.getMessage(),
                indexType,
                IndexAccessException.ErrorType.READ_ERROR,
                e
            );
        }
    }

    /**
     * Creates a new iterator positioned at the first key in the database.
     * The caller is responsible for closing the iterator.
     */
    @Override
    public RocksIterator iterateFromFirst() throws IndexAccessException {
        checkOpen();
        RocksIterator iterator = db.newIterator();
        iterator.seekToFirst();
        return iterator;
    }

    /**
     * Stores or updates a key-value pair.
     */
    @Override
    public void put(byte[] key, byte[] value) throws IndexAccessException {
        checkOpen();
        try {
            db.put(key, value);
        } catch (RocksDBException e) {
            throw new IndexAccessException(
                "Failed to put entry: " + e.getMessage(),
                indexType,
                IndexAccessException.ErrorType.WRITE_ERROR,
                e
            );
        }
    }

    /**
     * Deletes a key-value pair.
     */
    @Override
    public void delete(byte[] key) throws IndexAccessException {
        checkOpen();
        try {
            db.delete(key);
        } catch (RocksDBException e) {
            throw new IndexAccessException(
                "Failed to delete entry: " + e.getMessage(),
                indexType,
                IndexAccessException.ErrorType.WRITE_ERROR,
                e
            );
        }
    }

    /**
     * Gets the type of this index.
     */
    @Override
    public String getIndexType() {
        return indexType;
    }

    // Method for IndexGenerator internal verification
    public RocksDB getDbForVerification() {
        return this.db;
    }

    /**
     * Checks if the index is still open.
     */
    @Override
    public boolean isOpen() {
        // relies on db being non-null if isOpen is true after constructor success
        return isOpen.get();
    }

    /**
     * Gets the index metadata.
     * For the generic IndexAccess, this currently returns empty.
     * Specific subclasses or wrappers might provide metadata.
     * @return An Optional containing the index metadata, or an empty Optional if none exists.
     */
    @Override
    public Optional<java.util.Map<String, String>> getIndexMetadata() {
        return Optional.empty(); // Basic implementation
    }

    /**
     * Gets the root path of this index.
     * Implements the interface method.
     * @return The Path to the index directory.
     */
    @Override
    public Path getIndexPath() {
        return java.nio.file.Path.of(this.indexPath);
    }

    private void checkOpen() throws IndexAccessException {
        if (!isOpen()) {
            throw new IndexAccessException(
                "Index is closed or not properly initialized",
                indexType,
                IndexAccessException.ErrorType.RESOURCE_ERROR
            );
        }
    }

    @Override
    public void close() throws IndexAccessException {
        if (isOpen.compareAndSet(true, false)) {
            //logger.debug("Closing IndexAccess for type {} at {}", indexType, indexPath);
            // RocksDB's own resources (like iterators, batches) should be closed by their users before closing the DB.
            // The DB should be closed before its associated Options object.
            try {
                if (db != null) {
                    db.close();
                }
            } catch (Exception e) {
                logger.error("Error closing RocksDB instance for index {}: {}", indexType, e.getMessage(), e);
                // Decide if this should throw or be suppressed, plan mentions try-finally for options.
                // For now, log and proceed to close options.
            } finally {
                if (options != null) {
                    options.close();
                }
                logger.info("Successfully closed RocksDB and associated options for index: {}", indexType);
            }
        } else {
            logger.warn("IndexAccess for type {} at {} was already closed or never fully opened.", indexType, indexPath);
        }
    }

    // Utility methods
    public static byte[] bytes(String str) {
        if (str == null) {
            return null;
        }
        return str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static String asString(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }


    /**
     * Gets the sentences for a given document ID.
     *
     * @param documentId The document ID
     * @return Array of sentences, or null if not found
     */
    public String[] getDocumentSentences(int documentId) {
        logger.warn("getDocumentSentences(int) is a placeholder and not fully implemented for RocksDB.");
        return new String[]{"Sentence 1 for " + documentId, "Sentence 2 for " + documentId};
    }
}