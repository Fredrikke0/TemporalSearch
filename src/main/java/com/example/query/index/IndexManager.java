package com.example.query.index;

import com.example.core.IndexAccessInterface;
import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Dependency;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Temporal;

import org.iq80.leveldb.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Manages access to LevelDB indexes for a specific index set.
 * Responsible for initializing indexes, resolving paths, and mapping conditions to indexes.
 */
public class IndexManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(IndexManager.class);
    
    private final Map<String, IndexAccessInterface> indexes;
    private final Path indexBaseDir;
    private final String indexSetName;
    private boolean isClosed = false;

    /**
     * Creates a new IndexManager for a specific index set.
     *
     * @param baseDir The base directory for all index sets
     * @param indexSetName The name of the index set to use (from FROM clause)
     * @throws IndexAccessException if initialization fails
     */
    public IndexManager(Path baseDir, String indexSetName) throws IndexAccessException {
        this.indexBaseDir = baseDir.resolve(indexSetName);
        this.indexSetName = indexSetName;
        this.indexes = new HashMap<>();
        
        if (!Files.exists(this.indexBaseDir)) {
            throw new IndexAccessException(
                "Index set directory does not exist: " + this.indexBaseDir,
                "index_manager",
                IndexAccessException.ErrorType.INITIALIZATION_ERROR
            );
        }
        
        initializeIndexes();
    }

    /**
     * Initializes all required indexes.
     *
     * @throws IndexAccessException if initialization fails
     */
    private void initializeIndexes() throws IndexAccessException {
        Options options = new Options();
        options.createIfMissing(false);  // Don't create if missing
        options.cacheSize(64 * 1024 * 1024);  // 64MB cache

        // Initialize all required indexes
        String[] indexTypes = {
            "unigram", "bigram", "trigram", "pos", "ner", "ner_date", 
            "dependency", "nash"
        };

        for (String type : indexTypes) {
            try {
                Path indexPath = indexBaseDir.resolve(type);
                if (!Files.exists(indexPath) || !Files.exists(indexPath.resolve("CURRENT"))) {
                    if (!Files.exists(indexPath)) {
                        logger.warn("Index directory {} does not exist", indexPath);
                    } else {
                        logger.error("Index directory {} exists but does not contain valid LevelDB files", indexPath);
                    }
                    continue;
                }

                boolean hasManifest = false;
                try {
                    hasManifest = Files.list(indexPath)
                        .anyMatch(p -> p.getFileName().toString().startsWith("MANIFEST-"));
                } catch (IOException e) {
                    logger.error("Failed to check for manifest file in {}: {}", indexPath, e.getMessage());
                }

                if (!hasManifest) {
                    logger.error("Index directory {} exists but does not contain valid LevelDB files", indexPath);
                    continue;
                }

                indexes.put(type, new IndexAccess(indexPath, type, options));
                logger.info("Initialized {} index", type);
                
            } catch (Exception e) {
                logger.error("Failed to initialize {} index: {}", type, e.getMessage());
                // Continue with other indexes
            }
        }

        if (indexes.isEmpty()) {
            throw new IndexAccessException(
                "No indexes could be initialized in index set '" + indexSetName + 
                "'. Please ensure the index directory contains valid LevelDB databases.",
                "index_manager",
                IndexAccessException.ErrorType.INITIALIZATION_ERROR
            );
        }
    }

    /**
     * Gets an index by name
     *
     * @param name The index name
     * @return Optional containing the index (as interface) if found
     */
    public Optional<IndexAccessInterface> getIndex(String name) {
        checkClosed();
        return Optional.ofNullable(indexes.get(name));
    }

    /**
     * Gets the appropriate index for a condition type
     *
     * @param condition The condition to get an index for
     * @return Optional containing the index (as interface) if found
     */
    public Optional<IndexAccessInterface> getIndexForCondition(Condition condition) {
        checkClosed();
        
        // Map condition types to appropriate indexes
        if (condition instanceof Contains) {
            // For CONTAINS conditions, prefer the most specific n-gram index available
            Contains containsCondition = (Contains) condition;
            String[] terms = containsCondition.terms().toArray(new String[0]);
            
            if (terms.length >= 3 && indexes.containsKey("trigram")) {
                return Optional.of(indexes.get("trigram"));
            } else if (terms.length == 2 && indexes.containsKey("bigram")) {
                return Optional.of(indexes.get("bigram"));
            } else if (indexes.containsKey("unigram")) {
                return Optional.of(indexes.get("unigram"));
            }
        } else if (condition instanceof Ner) {
            Ner nerCondition = (Ner) condition;
            String entityType = nerCondition.entityType();
            
            // Use ner_date for DATE entities, ner for others
            if ("DATE".equals(entityType) && indexes.containsKey("ner_date")) {
                return Optional.of(indexes.get("ner_date"));
            } else if (indexes.containsKey("ner")) {
                return Optional.of(indexes.get("ner"));
            }
        } else if (condition instanceof Temporal && indexes.containsKey("ner_date")) {
            return Optional.of(indexes.get("ner_date"));
        } else if (condition instanceof Dependency && indexes.containsKey("dependency")) {
            return Optional.of(indexes.get("dependency"));
        }
        
        logger.warn("No appropriate index found for condition type: {}", condition.getClass().getSimpleName());
        return Optional.empty();
    }

    /**
     * Gets all available indexes
     *
     * @return Map of index name to IndexAccessInterface
     */
    public Map<String, IndexAccessInterface> getAllIndexes() {
        checkClosed();
        return new HashMap<>(indexes);
    }
    
    /**
     * Gets the base directory for the current index set
     * 
     * @return The base directory path as a string
     */
    public String getIndexBaseDir() {
        return indexBaseDir.toString();
    }

    /**
     * Checks if the manager is closed and throws an exception if it is.
     *
     * @throws IllegalStateException if the manager is closed
     */
    private void checkClosed() {
        if (isClosed) {
            throw new IllegalStateException("IndexManager is closed");
        }
    }

    @Override
    public void close() throws Exception {
        if (!isClosed) {
            for (IndexAccessInterface index : indexes.values()) {
                try {
                    index.close();
                } catch (Exception e) {
                    logger.error("Error closing index {}: {}", index.getIndexType(), e.getMessage());
                }
            }
            indexes.clear();
            isClosed = true;
            logger.info("Closed IndexManager for index set '{}'", indexSetName);
        }
    }
} 