package com.example.query.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.rocksdb.Options;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.index.RocksDBConfig;
import com.example.index.util.SynonymManager;
import com.example.query.model.Query;
import com.example.query.model.SubquerySpec;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Dependency;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Not;
import com.example.query.model.condition.Pos;
import com.example.query.model.condition.Temporal;

/**
 * Manages access to RocksDB indexes for a specific index set.
 * Responsible for lazily initializing only required indexes based on the query.
 */
public class IndexManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(IndexManager.class);

    private final Map<String, IndexAccessInterface> indexes;
    private final Path indexBaseDir;
    private final String indexSetName;
    private SynonymManager synonymManager;
    private boolean isClosed = false;

    /**
     * Creates a new IndexManager for a specific index set and query.
     * Initializes only the indexes required by the query and temporal strategy.
     *
     * @param actualIndexDir The direct path to the directory containing the various index type subdirectories (e.g., unigram, ner_date).
     * @param indexSetName The name of the index set to use (from FROM clause), used for logging and identification.
     * @param query The parsed query object
     * @param temporalStrategy The name of the temporal strategy ("nash" or "naive")
     * @throws IndexAccessException if the base index directory doesn't exist or synonym manager fails to initialize
     */
    public IndexManager(Path actualIndexDir, String indexSetName, Query query, String temporalStrategy) throws IndexAccessException {
        this.indexBaseDir = actualIndexDir;
        this.indexSetName = indexSetName;
        this.indexes = new HashMap<>();

        if (!Files.exists(this.indexBaseDir)) {
            throw new IndexAccessException(
                "Provided index directory does not exist: " + this.indexBaseDir,
                "index_manager",
                IndexAccessException.ErrorType.INITIALIZATION_ERROR
            );
        }

        // Initialize SynonymManager
        try {
            Path synonymManagerDbPath = this.indexBaseDir.resolve("global_values_lookup.db");
            // Ensure parent directory for synonym manager DB exists
            // For a file-like DB name, the parent is just indexBaseDir, which is already checked.
            // If global_values_lookup.db is treated as a directory by RocksDB, then this check might be different
            // or handled by RocksDB itself if setCreateIfMissing is true on write.
            // For read-only, the path must exist.
            // Let's assume global_values_lookup.db is the directory RocksDB uses.
            if (!Files.exists(synonymManagerDbPath)) {
                 // This case might mean the DB was never created by IndexRunner or path is wrong.
                 // Throwing an error here might be too strict if SynonymManager itself handles it.
                 // However, for QueryCLI, we expect it to exist.
                 logger.warn("SynonymManager DB path does not exist: {}. Operations requiring synonyms may fail or SynonymManager will create it if in write mode (not typical for QueryCLI).", synonymManagerDbPath);
                 // Forcing creation of parent is not needed if synonymManagerDbPath is directly under indexBaseDir
            }
            this.synonymManager = new SynonymManager(synonymManagerDbPath);
            logger.info("SynonymManager initialized at: {}", synonymManagerDbPath);
        } catch (RocksDBException e) {
            logger.error("Failed to initialize SynonymManager for index set '{}' at {}: {}", indexSetName, this.indexBaseDir.resolve("global_values_lookup.db"), e.getMessage(), e);
            throw new IndexAccessException(
                "Failed to initialize SynonymManager for index set '" + indexSetName + "' at " + this.indexBaseDir.resolve("global_values_lookup.db") + ". Cause: " + e.getMessage(),
                "synonym_manager",
                IndexAccessException.ErrorType.INITIALIZATION_ERROR
            );
        }

        initializeRequiredIndexes(query, temporalStrategy);
    }

    /**
     * Determines and initializes only the indexes required by the query and temporal strategy.
     *
     * @param query The parsed query
     * @param temporalStrategy The temporal strategy ("nash" or "naive")
     * @throws IndexAccessException if no required indexes could be initialized
     */
    private void initializeRequiredIndexes(Query query, String temporalStrategy) throws IndexAccessException {
        Set<String> requiredIndexNames = determineRequiredIndexes(query, temporalStrategy);
        logger.info("Query requires the following indexes: {}", requiredIndexNames);

        List<Options> createdOptionsList = new ArrayList<>();

        for (String type : requiredIndexNames) {
            Options specificOptions = null;
            try {
                Path indexPath = indexBaseDir.resolve(type);
                if (!Files.exists(indexPath) || !Files.exists(indexPath.resolve("CURRENT"))) {
                    logger.warn("Required index directory {} or its CURRENT file does not exist or is invalid. Skipping.", indexPath);
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
                     logger.warn("Required index directory {} exists but seems incomplete (missing MANIFEST). Attempting to open anyway.", indexPath);
                }

                specificOptions = RocksDBConfig.createOptimizedOptions();
                createdOptionsList.add(specificOptions);
                specificOptions.setCreateIfMissing(false);

                IndexAccessInterface indexAccess = new IndexAccess(indexPath, type, specificOptions);
                indexes.put(type, indexAccess);
                createdOptionsList.remove(specificOptions);
                logger.info("Successfully initialized required {} index", type);

            } catch (IndexAccessException e) {
                logger.error("Failed to initialize required {} index at {}: {} [Type: {}]",
                    type, indexBaseDir.resolve(type), e.getMessage(), e.getErrorType());
                if (specificOptions != null && !indexes.containsValue(specificOptions)) {
                    specificOptions.close();
                }
            } catch (Exception e) {
                logger.error("Unexpected error initializing required {} index at {}: {}",
                    type, indexBaseDir.resolve(type), e.getMessage(), e);
                if (specificOptions != null && !indexes.containsValue(specificOptions)) {
                    specificOptions.close();
                }
            }
        }

        for(Options opts : createdOptionsList) {
            opts.close();
        }

        if (indexes.isEmpty() && !requiredIndexNames.isEmpty()) {
             String missing = String.join(", ", requiredIndexNames);
            throw new IndexAccessException(
                "None of the required indexes could be initialized in index set '" + indexSetName +
                "'. Required: [" + missing + "]. Please ensure the index directories exist and contain valid RocksDB databases.",
                "index_manager",
                IndexAccessException.ErrorType.INITIALIZATION_ERROR
            );
        }
         logger.debug("Finished initializing required indexes. Active indexes: {}", indexes.keySet());
    }

    /**
     * Determines the set of index names required by a query and temporal strategy.
     *
     * @param query The query object
     * @param temporalStrategy The temporal strategy name
     * @return A set of required index names (e.g., "unigram", "ner_date", "nash")
     */
    private Set<String> determineRequiredIndexes(Query query, String temporalStrategy) {
        Set<String> required = new HashSet<>();

        // 1. Analyze main query conditions
        if (query.conditions() != null) {
            query.conditions().forEach(condition -> collectIndexesForCondition(condition, required));
        }

        // 2. Analyze subquery conditions (if JOIN exists)
        if (!query.subqueries().isEmpty()) {
            for (SubquerySpec subSpec : query.subqueries()) {
                Query subquery = subSpec.subquery();
                if (subquery.conditions() != null) {
                     subquery.conditions().forEach(condition -> collectIndexesForCondition(condition, required));
                }
            }
        }

        // 3. Determine index based on temporal strategy and presence of DATE conditions
        boolean needsTemporal = queryHasTemporalCondition(query);
        if (needsTemporal) {
             if ("nash".equalsIgnoreCase(temporalStrategy)) {
                required.add("nash");
                 logger.debug("Nash strategy selected, requiring 'nash' index.");
            } else { // Default to "naive" strategy, which usually relies on ner_date
                required.add("ner_date");
                 logger.debug("Naive strategy selected (or DATE condition present), requiring 'ner_date' index.");
            }
        }

        // Ensure ner_date is included if any specific NER(DATE) condition exists, regardless of strategy
        if(queryHasNerDateCondition(query)) {
            required.add("ner_date");
            logger.debug("Explicit NER(DATE) condition found, ensuring 'ner_date' index is required.");
        }

        return required;
    }

    /**
     * Recursively collects index names required by a single condition and its children.
     *
     * @param condition The condition to analyze
     * @param required The set to add required index names to
     */
    private void collectIndexesForCondition(Condition condition, Set<String> required) {
        if (condition instanceof Logical logical) {
            logical.conditions().forEach(operand -> collectIndexesForCondition(operand, required));
        } else if (condition instanceof Not notCondition) {
            collectIndexesForCondition(notCondition.condition(), required);
        } else if (condition instanceof Contains contains) {
            int numTerms = contains.terms().size();
            if (numTerms >= 3) required.add("trigram");
            if (numTerms >= 2) required.add("bigram"); // Needs bigram if 2 or more
            if (numTerms >= 1) required.add("unigram"); // Always need unigram if CONTAINS is used
        } else if (condition instanceof Ner ner) {
            if ("DATE".equalsIgnoreCase(ner.entityType())) {
                required.add("ner_date");
            } else {
                required.add("ner");
            }
        } else if (condition instanceof Pos) {
            required.add("pos");
        } else if (condition instanceof Dependency) {
            required.add("dependency");
        } else if (condition instanceof Temporal) {
            // The main logic in determineRequiredIndexes handles strategy-based index selection (nash/ner_date)
            // No specific index needed *just* for Temporal condition itself here, strategy dictates it.
        }
    }

    /**
     * Checks if the query (including subquery) contains any Temporal or NER(DATE) conditions.
     *
     * @param query The query to check
     * @return true if a temporal-related condition exists, false otherwise
     */
     private boolean queryHasTemporalCondition(Query query) {
         if (query.conditions() != null && containsTemporalCondition(query.conditions())) {
             return true;
         }
         // Check subqueries
         if (!query.subqueries().isEmpty()) {
             for (SubquerySpec subSpec : query.subqueries()) {
                 Query subquery = subSpec.subquery();
                 if (subquery.conditions() != null && containsTemporalCondition(subquery.conditions())) {
                     return true;
                 }
             }
         }
         return false;
     }

     /**
      * Checks if the query (including subquery) contains any NER(DATE) conditions.
      *
      * @param query The query to check
      * @return true if a NER(DATE) condition exists, false otherwise
      */
      private boolean queryHasNerDateCondition(Query query) {
          if (query.conditions() != null && containsNerDateCondition(query.conditions())) {
              return true;
          }
          // Check subqueries
          if (!query.subqueries().isEmpty()) {
             for (SubquerySpec subSpec : query.subqueries()) {
                 Query subquery = subSpec.subquery();
                 if (subquery.conditions() != null && containsNerDateCondition(subquery.conditions())) {
                     return true;
                 }
             }
         }
          return false;
      }

     /**
      * Helper to recursively check a list of conditions for Temporal or NER(DATE).
      */
     private boolean containsTemporalCondition(List<Condition> conditions) {
         for (Condition condition : conditions) {
             if (condition instanceof Temporal) return true;
             if (condition instanceof Ner ner && "DATE".equalsIgnoreCase(ner.entityType())) return true;
             if (condition instanceof Logical logical) {
                 if (containsTemporalCondition(logical.conditions())) return true;
             }
             if (condition instanceof Not notCond) {
                  if (containsTemporalCondition(Collections.singletonList(notCond.condition()))) return true;
             }
         }
         return false;
     }

     /**
      * Helper to recursively check a list of conditions specifically for NER(DATE).
      */
      private boolean containsNerDateCondition(List<Condition> conditions) {
          for (Condition condition : conditions) {
              if (condition instanceof Ner ner && "DATE".equalsIgnoreCase(ner.entityType())) return true;
              if (condition instanceof Logical logical) {
                  if (containsNerDateCondition(logical.conditions())) return true;
              }
              if (condition instanceof Not notCond) {
                   if (containsNerDateCondition(Collections.singletonList(notCond.condition()))) return true;
              }
          }
          return false;
      }


    /**
     * Gets an index by name. Throws exception if requested index wasn't required/initialized.
     *
     * @param name The index name
     * @return The index access interface
     * @throws NoSuchElementException if the requested index was not initialized
     */
    public IndexAccessInterface getIndex(String name) {
        checkClosed();
        IndexAccessInterface index = indexes.get(name);
        if (index == null) {
            throw new NoSuchElementException(
                "Index '" + name + "' was not required by the query or failed to initialize. Available indexes: " + indexes.keySet()
            );
        }
        return index;
    }

     /**
      * Gets an optional index by name. Returns empty optional if not available.
      *
      * @param name The index name
      * @return Optional containing the index (as interface) if found and initialized
      */
     public Optional<IndexAccessInterface> getOptionalIndex(String name) {
         checkClosed();
         return Optional.ofNullable(indexes.get(name));
     }

    /**
     * Gets the SynonymManager associated with this IndexManager.
     * @return The SynonymManager instance.
     * @throws IllegalStateException if the IndexManager is closed.
     */
    public SynonymManager getSynonymManager() {
        checkClosed();
        if (this.synonymManager == null) {
            // This should not happen if constructor succeeded and not closed yet
            throw new IllegalStateException("SynonymManager is not initialized or has been closed.");
        }
        return this.synonymManager;
    }

    /**
     * Gets the appropriate index for a condition type.
     * Assumes the required indexes were already initialized.
     *
     * @param condition The condition to get an index for
     * @return Optional containing the index (as interface) if found among initialized indexes
     */
    public Optional<IndexAccessInterface> getIndexForCondition(Condition condition) {
        checkClosed();

        // Map condition types to appropriate REQUIRED indexes
        if (condition instanceof Contains containsCondition) {
            String[] terms = containsCondition.terms().toArray(new String[0]);
            // Prefer most specific N-gram index *that was initialized*
            if (terms.length >= 3 && indexes.containsKey("trigram")) {
                return Optional.of(indexes.get("trigram"));
            } else if (terms.length >= 2 && indexes.containsKey("bigram")) {
                return Optional.of(indexes.get("bigram"));
            } else if (indexes.containsKey("unigram")) { // Must have unigram if CONTAINS was used
                return Optional.of(indexes.get("unigram"));
            }
        } else if (condition instanceof Ner nerCondition) {
            String entityType = nerCondition.entityType();
            if ("DATE".equals(entityType) && indexes.containsKey("ner_date")) {
                return Optional.of(indexes.get("ner_date"));
            } else if (indexes.containsKey("ner")) {
                return Optional.of(indexes.get("ner"));
            }
        } else if (condition instanceof Temporal) {
             // Temporal conditions rely on either 'nash' or 'ner_date' based on strategy
             // Check which one was initialized
             if (indexes.containsKey("nash")) {
                 return Optional.of(indexes.get("nash"));
             } else if (indexes.containsKey("ner_date")) {
                 return Optional.of(indexes.get("ner_date"));
             }
        } else if (condition instanceof Dependency && indexes.containsKey("dependency")) {
            return Optional.of(indexes.get("dependency"));
        } else if (condition instanceof Pos && indexes.containsKey("pos")) {
             return Optional.of(indexes.get("pos"));
        }

        // If no specific index type matches or the required one wasn't initialized
        logger.warn("No appropriate *initialized* index found for condition type: {}. Available: {}",
                    condition.getClass().getSimpleName(), indexes.keySet());
        return Optional.empty();
    }

    /**
     * Gets all *initialized* indexes
     *
     * @return Map of index name to IndexAccessInterface for successfully initialized indexes
     */
    public Map<String, IndexAccessInterface> getAllIndexes() {
        checkClosed();
        return new HashMap<>(indexes); // Return copy
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
    public void close() throws IndexAccessException {
        if (isClosed) {
            return;
        }
        isClosed = true;
        logger.info("Closing IndexManager for index set: {}", indexSetName);
        List<String> failedToClose = new ArrayList<>();

        // Close SynonymManager first
        if (this.synonymManager != null) {
            try {
                this.synonymManager.close();
                logger.debug("Closed SynonymManager for index set: {}", indexSetName);
                this.synonymManager = null;
            } catch (Exception e) { // SynonymManager.close() might throw generic Exception or specific ones
                logger.error("Failed to close SynonymManager for set {}: {}", indexSetName, e.getMessage(), e);
                failedToClose.add("SynonymManager (" + e.getClass().getSimpleName() + ")");
            }
        }

        for (Map.Entry<String, IndexAccessInterface> entry : indexes.entrySet()) {
            try {
                entry.getValue().close();
                logger.debug("Closed index: {}", entry.getKey());
            } catch (IndexAccessException e) {
                logger.error("Failed to close index {} for set {}: {} [Type: {}]",
                             entry.getKey(), indexSetName, e.getMessage(), e.getErrorType(), e);
                failedToClose.add(entry.getKey() + " (Type: " + e.getErrorType() + ")");
            } catch (Exception e) {
                logger.error("Unexpected error closing index {} for set {}: {}",
                             entry.getKey(), indexSetName, e.getMessage(), e);
                failedToClose.add(entry.getKey() + " (Unexpected)");
            }
        }
        indexes.clear();

        if (!failedToClose.isEmpty()) {
            throw new IndexAccessException(
                "Failed to close one or more indexes: " + String.join(", ", failedToClose),
                indexSetName,
                IndexAccessException.ErrorType.RESOURCE_ERROR
            );
        }
        logger.info("IndexManager closed for index set: {}", indexSetName);
    }
}