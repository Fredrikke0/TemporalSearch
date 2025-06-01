package com.example.core;

import static org.iq80.leveldb.impl.Iq80DBFactory.factory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core class for accessing LevelDB-based indexes.
 * Provides unified access for both read and write operations.
 */
public class IndexAccess implements IndexAccessInterface {
    private static final Logger logger = LoggerFactory.getLogger(IndexAccess.class);

    // Delimiter constant inherited from IndexAccessInterface

    private final DB db;
    private final String indexPath;
    private final String indexType;
    private final AtomicBoolean isOpen;

    /**
     * Creates a new IndexAccess instance for a specific index type.
     *
     * @param indexPath Full path to the index directory
     * @param indexType The type of index (e.g., "unigram", "bigram", "dependency")
     * @param options LevelDB options for this index
     * @throws IndexAccessException if initialization fails
     */
    public IndexAccess(Path indexPath, String indexType, Options options) throws IndexAccessException {
        this.indexType = indexType;
        this.indexPath = indexPath.toString();
        this.isOpen = new AtomicBoolean(true);

        try {
            // Create index directory if it doesn't exist
            File indexDir = new File(indexPath.toString());
            if (!indexDir.exists()) {
                if (!indexDir.mkdirs()) {
                    throw new IndexAccessException(
                        "Failed to create index directory: " + indexPath,
                        indexType,
                        IndexAccessException.ErrorType.INITIALIZATION_ERROR
                    );
                }
            }

            // Initialize LevelDB
            this.db = factory.open(indexDir, options);
            logger.debug("Initialized IndexAccess for type {} at {}", indexType, indexPath);

        } catch (IOException e) {
            throw new IndexAccessException(
                "Failed to initialize index: " + e.getMessage(),
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
    public void write(WriteBatch batch) throws IndexAccessException {
        checkOpen();
        try {
            db.write(batch, new WriteOptions().sync(true));
            //logger.debug("Executed synchronous batch write for index: {}", indexType);
        } catch (Exception e) {
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
    public WriteBatch createWriteBatch() {
        return db.createWriteBatch();
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
        } catch (IOException | DBException e) { // Catch IOException from deserialize and DBException from db.get
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
    public DBIterator seek(byte[] key) throws IndexAccessException {
        checkOpen();
        DBIterator iterator = db.iterator();
        if (key != null) { // LevelDB iterator might not like null keys for seek
            iterator.seek(key);
        }

        else {
            iterator.seekToFirst(); // Explicitly seek to first if key is null
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
        } catch (DBException e) {
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
    public DBIterator iterateFromFirst() throws IndexAccessException {
        checkOpen();
        DBIterator iterator = db.iterator();
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
        } catch (DBException e) {
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
        } catch (DBException e) {
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
    public DB getDbForVerification() {
        return this.db;
    }

    /**
     * Checks if the index is still open.
     */
    @Override
    public boolean isOpen() {
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

    private void checkOpen() throws IndexAccessException {
        if (!isOpen.get()) {
            throw new IndexAccessException(
                "Index is closed",
                indexType,
                IndexAccessException.ErrorType.RESOURCE_ERROR
            );
        }
    }

    @Override
    public void close() throws IndexAccessException {
        if (isOpen.compareAndSet(true, false)) {
            try {
                db.close();
            } catch (IOException e) {
                throw new IndexAccessException(
                    "Failed to close index: " + e.getMessage(),
                    indexType,
                    IndexAccessException.ErrorType.RESOURCE_ERROR,
                    e
                );
            }
        }
    }

    // Utility methods
    public static byte[] bytes(String str) {
        return str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static String asString(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Gets the document text for a given document ID.
     *
     * @param documentId The document ID
     * @return The document text, or null if not found
     */
    public String getDocumentText(int documentId) {
        try {
            checkOpen();
            // In a real implementation, you would retrieve the document text from the index
            // For now, we'll just return a placeholder
            return "This is the text of document " + documentId + ". It contains multiple sentences. This is the second sentence.";
        } catch (Exception e) {
            logger.error("Failed to get document text: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Gets the sentences for a given document ID.
     *
     * @param documentId The document ID
     * @return Array of sentences, or null if not found
     */
    public String[] getDocumentSentences(int documentId) {
        // Placeholder implementation
        return new String[0];
    }
}