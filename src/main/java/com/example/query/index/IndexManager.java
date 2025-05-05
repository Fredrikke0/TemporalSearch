package com.example.query.index;

import com.example.core.IndexAccessInterface;
import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.query.model.*;
import com.example.query.model.condition.*;
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
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages access to LevelDB indexes for a specific index set.
 * Responsible for lazily initializing only required indexes based on the query.
 */
public class IndexManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(IndexManager.class);
    
    private final Map<String, IndexAccessInterface> indexes;
    private final Path indexBaseDir;
    private final String indexSetName;
    private final Options levelDbOptions; // Store options for reuse
    private boolean isClosed = false;

    /**
     * Creates a new IndexManager for a specific index set and query.
     * Initializes only the indexes required by the query and temporal strategy.
     *
     * @param baseDir The base directory for all index sets
     * @param indexSetName The name of the index set to use (from FROM clause)
     * @param query The parsed query object
     * @param temporalStrategy The name of the temporal strategy ("nash" or "naive")
     * @throws IndexAccessException if the base index directory doesn't exist
     */
    public IndexManager(Path projectBaseDir, String indexSetName, Query query, String temporalStrategy) throws IndexAccessException {
        // Resolve the specific index directory *within* the project directory
        this.indexBaseDir = projectBaseDir.resolve("indexes"); 
        this.indexSetName = indexSetName; // Still needed for context/potential future use?
        this.indexes = new HashMap<>();
        this.levelDbOptions = new Options();
        this.levelDbOptions.createIfMissing(false); // Don't create if missing
        this.levelDbOptions.cacheSize(64 * 1024 * 1024); // 64MB cache
        
        // Check existence of the resolved index base directory (e.g., project/indexes)
        if (!Files.exists(this.indexBaseDir)) {
            throw new IndexAccessException(
                "Base index directory does not exist within the project: " + this.indexBaseDir,
                "index_manager",
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

        for (String type : requiredIndexNames) {
            try {
                Path indexPath = indexBaseDir.resolve(type);
                if (!Files.exists(indexPath) || !Files.exists(indexPath.resolve("CURRENT"))) {
                    logger.warn("Required index directory {} or its CURRENT file does not exist or is invalid. Skipping.", indexPath);
                    continue; // Skip this index if not properly formed
                }

                // Basic validation: check for MANIFEST file
                boolean hasManifest = false;
                try {
                    hasManifest = Files.list(indexPath)
                        .anyMatch(p -> p.getFileName().toString().startsWith("MANIFEST-"));
                } catch (IOException e) {
                    logger.error("Failed to check for manifest file in {}: {}", indexPath, e.getMessage());
                } // Continue even if check fails, IndexAccess constructor might handle it

                if (!hasManifest) {
                     logger.warn("Required index directory {} exists but seems incomplete (missing MANIFEST). Attempting to open anyway.", indexPath);
                     // Maybe allow opening, LevelDB might recover or throw a clearer error
                }

                indexes.put(type, new IndexAccess(indexPath, type, levelDbOptions));
                logger.info("Successfully initialized required {} index", type);
                
            } catch (IndexAccessException e) {
                // Log specific IndexAccess errors but continue trying others
                logger.error("Failed to initialize required {} index at {}: {} [Type: {}]", 
                    type, indexBaseDir.resolve(type), e.getMessage(), e.getErrorType());
                 // Potentially re-throw if a critical index fails, or collect errors
            } catch (Exception e) {
                logger.error("Unexpected error initializing required {} index at {}: {}", 
                    type, indexBaseDir.resolve(type), e.getMessage(), e);
            }
        }

        // Check if *any* required index was successfully initialized
        if (indexes.isEmpty() && !requiredIndexNames.isEmpty()) {
             String missing = String.join(", ", requiredIndexNames);
            throw new IndexAccessException(
                "None of the required indexes could be initialized in index set '" + indexSetName + 
                "'. Required: [" + missing + "]. Please ensure the index directories exist and contain valid LevelDB databases.",
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
    public void close() throws Exception {
        if (!isClosed) {
            isClosed = true; // Mark as closed early to prevent races
            logger.info("Closing IndexManager for index set '{}'. Shutting down {} indexes...", indexSetName, indexes.size());
            List<Exception> closeErrors = new ArrayList<>();
            for (Map.Entry<String, IndexAccessInterface> entry : indexes.entrySet()) {
                try {
                    entry.getValue().close();
                    logger.debug("Closed index {}", entry.getKey());
                } catch (Exception e) {
                    logger.error("Error closing index {}: {}", entry.getKey(), e.getMessage());
                    closeErrors.add(e);
                }
            }
            indexes.clear();
            if (!closeErrors.isEmpty()) {
                 // Combine exceptions or throw the first one
                 throw new IOException("Errors occurred while closing indexes: " + 
                     closeErrors.stream().map(Throwable::getMessage).collect(Collectors.joining("; ")));
            }
            logger.info("IndexManager closed successfully for index set '{}'", indexSetName);
        }
    }
} 