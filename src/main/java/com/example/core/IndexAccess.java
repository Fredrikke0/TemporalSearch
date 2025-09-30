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
import org.rocksdb.IngestExternalFileOptions;
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
     * @param readOnly true to open in read-only mode, false for read-write
     * @throws IndexAccessException if initialization fails
     */
    public IndexAccess(Path indexPath, String indexType, Options options, boolean readOnly) throws IndexAccessException {
        this.indexType = indexType;
        this.indexPath = indexPath.toString();
        this.isOpen = new AtomicBoolean(true);
        this.options = options;


        try {
            File indexDir = new File(this.indexPath);
            if (!indexDir.exists()) {
                if (readOnly) {
                    throw new IndexAccessException(
                        "Index directory does not exist and cannot be created in read-only mode: " + this.indexPath,
                        indexType,
                        IndexAccessException.ErrorType.INITIALIZATION_ERROR
                    );
                }
                if (!indexDir.mkdirs()) {
                    throw new IndexAccessException(
                        "Failed to create index directory: " + this.indexPath,
                        indexType,
                        IndexAccessException.ErrorType.INITIALIZATION_ERROR
                    );
                }
            }

            if (readOnly) {
                this.db = RocksDB.openReadOnly(options, indexDir.getAbsolutePath());
                logger.trace("Opened IndexAccess in READ-ONLY mode for type {} at {}", indexType, this.indexPath);
            } else {
                this.db = RocksDB.open(options, indexDir.getAbsolutePath());
                logger.trace("Opened IndexAccess in read-write mode for type {} at {}", indexType, this.indexPath);
            }
        } catch (RocksDBException e) {
            this.isOpen.set(false);
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
     * Ingest external SST files into the DB.
     */
    @Override
    public void ingestExternalFiles(java.util.List<String> sstFilePaths) throws IndexAccessException {
        checkOpen();
        try (IngestExternalFileOptions ifo = new IngestExternalFileOptions()) {
            // Use defaults; caller should have disabled compactions if desired
            db.ingestExternalFile(sstFilePaths, ifo);
        } catch (RocksDBException e) {
            throw new IndexAccessException(
                "Failed to ingest external SST files: " + e.getMessage(),
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
        org.rocksdb.ReadOptions ro = new org.rocksdb.ReadOptions();
        RocksIterator iterator = db.newIterator(ro);
        if (key != null) {
            iterator.seek(key);
        } else {
            iterator.seekToFirst();
        }
        return iterator;
    }

    /**
     * Creates a new iterator positioned at or after the specified prefix and bounded by an upper bound.
     * Optionally sets a readahead size for sequential scans.
     */
    public RocksIterator seekWithBounds(byte[] prefix, byte[] upperBoundExclusive, long readaheadBytes) throws IndexAccessException {
        checkOpen();
        org.rocksdb.ReadOptions ro = new org.rocksdb.ReadOptions();
        if (upperBoundExclusive != null) {
            ro.setIterateUpperBound(new org.rocksdb.Slice(upperBoundExclusive));
        }
        if (readaheadBytes > 0) {
            ro.setReadaheadSize(readaheadBytes);
        }
        RocksIterator iterator = db.newIterator(ro);
        iterator.seek(prefix);
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

    @Override
    public void compactRange() throws IndexAccessException {
        checkOpen();
        try {
            db.compactRange();
        } catch (RocksDBException e) {
            throw new IndexAccessException(
                "Failed to compact range: " + e.getMessage(),
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



    /**
     * Checks if the index is still open.
     */
    @Override
    public boolean isOpen() {
        // relies on db being non-null if isOpen is true after constructor success
        return isOpen.get();
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

    @Override
    public Options getOptionsForSstWriter() {
        return this.options;
    }

    @Override
    public Optional<PositionListSoA> getMergedPositions(String baseTerm,
                                                        Optional<com.example.query.executor.FilteringContext> context,
                                                        com.example.query.executor.AttributeRequirements requirements)
            throws IOException, IndexAccessException {
        checkOpen();
        byte[] baseTermBytes = bytes(baseTerm);
        Optional<byte[]> rawBaseData = getRaw(baseTermBytes);

        // If base term exists, merge base + #1, #2, ...
        if (rawBaseData.isPresent()) {
            PositionListSoA merged = PositionListSoA.deserializeWithFilters(rawBaseData.get(), context, requirements);

            // Batch segment fetch using MultiGet (segments are contiguous: #1, #2, ...)
            final int batchSize = 16;
            int nextSeg = 1;
            try {
                while (true) {
                    java.util.ArrayList<byte[]> keys = new java.util.ArrayList<>(batchSize);
                    for (int i = 0; i < batchSize; i++) {
                        String segKey = baseTerm + "#" + (nextSeg + i);
                        keys.add(bytes(segKey));
                    }
                    java.util.List<byte[]> values = db.multiGetAsList(keys);

                    boolean anyFoundInBatch = false;
                    for (int i = 0; i < values.size(); i++) {
                        byte[] v = values.get(i);
                        if (v == null) {
                            // First miss implies no further segments (contiguous numbering)
                            return (merged != null && !merged.isEmpty()) ? Optional.of(merged) : Optional.empty();
                        }
                        anyFoundInBatch = true;
                        PositionListSoA segSoA = PositionListSoA.deserializeWithFilters(v, context, requirements);
                        if (!segSoA.isEmpty()) {
                            if (merged == null || merged.isEmpty()) {
                                merged = segSoA;
                            } else {
                                merged.addAll(segSoA);
                            }
                        }
                    }
                    if (!anyFoundInBatch) {
                        break;
                    }
                    nextSeg += batchSize;
                }
            } catch (org.rocksdb.RocksDBException e) {
                throw new IndexAccessException(
                    "MultiGet failed during merged segment fetch: " + e.getMessage(),
                    indexType,
                    IndexAccessException.ErrorType.READ_ERROR,
                    e
                );
            }
            return (merged != null && !merged.isEmpty()) ? Optional.of(merged) : Optional.empty();
        }

        return Optional.empty();
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
            try {
                if (db != null) {
                    db.close();
                }
            } catch (Exception e) {
                logger.error("Error closing RocksDB instance for index {}: {}", indexType, e.getMessage(), e);
            } finally {
                if (options != null) {
                    options.close();
                }
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





}