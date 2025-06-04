package com.example.query.executor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.index.util.SynonymManager;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Dependency;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Not;
import com.example.query.model.condition.Pos;
import com.example.query.model.condition.Temporal;

/**
 * Factory for creating or retrieving ConditionExecutor instances based on Condition type.
 * Ensures that singleton executors (like TemporalExecutor) are reused.
 */
public class ConditionExecutorFactory {
    private static final Logger logger = LoggerFactory.getLogger(ConditionExecutorFactory.class);

    // Cache for singleton executors like TemporalExecutor
    private final Map<Class<? extends Condition>, ConditionExecutor<?>> executorCache = new ConcurrentHashMap<>();
    private final SynonymManager synonymManager;

    // Configuration for TemporalExecutor strategy
    private String desiredTemporalStrategy = "naive"; // Default strategy

    public ConditionExecutorFactory(SynonymManager synonymManager) {
        this.synonymManager = synonymManager;
        logger.debug("Initialized condition executor factory with SynonymManager.");
    }

    /**
     * Sets the desired strategy name for the TemporalExecutor.
     * Must be called before the TemporalExecutor is first requested.
     *
     * @param strategyName The name of the strategy ("nash" or "naive").
     */
    public void setTemporalStrategy(String strategyName) {
        if ("nash".equalsIgnoreCase(strategyName) || "naive".equalsIgnoreCase(strategyName)) {
            this.desiredTemporalStrategy = strategyName.toLowerCase();
            logger.info("Temporal execution strategy set to: {}", this.desiredTemporalStrategy);
            // If TemporalExecutor already exists in cache, update its strategy
            TemporalExecutor existingExecutor = (TemporalExecutor) executorCache.get(Temporal.class);
            if (existingExecutor != null) {
                 try {
                     existingExecutor.setActiveStrategy(this.desiredTemporalStrategy);
                 } catch (IllegalArgumentException e) {
                      logger.error("Failed to set active strategy '{}' on existing TemporalExecutor: {}", this.desiredTemporalStrategy, e.getMessage());
                 }
            }
        } else {
            logger.warn("Invalid temporal strategy name provided: '{}'. Using default '{}'.", strategyName, this.desiredTemporalStrategy);
        }
    }

    /**
     * Gets the currently configured temporal strategy name.
     *
     * @return The name of the temporal strategy ("nash" or "naive").
     */
    public String getTemporalStrategy() {
        return this.desiredTemporalStrategy;
    }

    /**
     * Gets the appropriate executor for the given condition.
     * Creates new instances for non-singleton executors, reuses cached singletons.
     *
     * @param condition The condition requiring an executor
     * @return The ConditionExecutor instance
     * @throws IllegalArgumentException if no executor is found for the condition type
     */
    @SuppressWarnings("unchecked")
    public <T extends Condition> ConditionExecutor<T> getExecutor(T condition) {
        Class<? extends Condition> conditionClass = condition.getClass();

        // Handle specific types that should be singletons or require special setup
        if (conditionClass == Temporal.class) {
            // Use computeIfAbsent for thread-safe singleton creation and configuration
            return (ConditionExecutor<T>) executorCache.computeIfAbsent(Temporal.class, k -> {
                TemporalExecutor temporalExecutor = new TemporalExecutor();
                 try {
                     temporalExecutor.setActiveStrategy(desiredTemporalStrategy);
                 } catch (IllegalArgumentException e) {
                     logger.error("Failed to set initial strategy '{}' on new TemporalExecutor: {}. Defaulting might occur.", desiredTemporalStrategy, e.getMessage());
                     // Let TemporalExecutor's constructor default handle it
                 }
                 logger.debug("Created and cached TemporalExecutor instance with initial strategy preference: {}", desiredTemporalStrategy);
                return temporalExecutor;
            });
        }

        // Handle other condition types (assuming non-singleton for now)
        if (condition instanceof Logical) {
            return (ConditionExecutor<T>) new LogicalExecutor(this); // Pass factory for recursion
        }
        if (condition instanceof Contains) {
            return (ConditionExecutor<T>) executorCache.computeIfAbsent(Contains.class, k -> {
                 logger.debug("Creating and caching ContainsExecutor instance.");
                 return new ContainsExecutor();
            });
        }
        if (condition instanceof Ner) {
             return (ConditionExecutor<T>) executorCache.computeIfAbsent(Ner.class, k -> {
                 logger.debug("Creating and caching NerExecutor instance.");
                 return new NerExecutor(this.synonymManager);
             });
        }
         if (condition instanceof Pos) {
              return (ConditionExecutor<T>) executorCache.computeIfAbsent(Pos.class, k -> {
                  logger.debug("Creating and caching PosExecutor instance.");
                  return new PosExecutor(this.synonymManager);
              });
         }
          if (condition instanceof Dependency) {
               return (ConditionExecutor<T>) executorCache.computeIfAbsent(Dependency.class, k -> {
                   logger.debug("Creating and caching DependencyExecutor instance.");
                   return new DependencyExecutor();
               });
          }
          if (condition instanceof Not) {
              // Use computeIfAbsent to cache NotExecutor as well, passing the factory
              // Explicitly cast the lambda parameter type
              Function<Class<? extends Condition>, NotExecutor> factoryFunction = k -> {
                   logger.debug("Creating and caching NotExecutor instance.");
                   return new NotExecutor(this);
              };
              return (ConditionExecutor<T>) executorCache.computeIfAbsent(Not.class, factoryFunction);
          }
        // Add cases for other condition types (Pos, Dependency, etc.)

        throw new IllegalArgumentException("No executor found for condition type: " + conditionClass.getSimpleName());
    }

     /**
      * Utility method to specifically get the configured TemporalExecutor instance.
      * Useful for operations like initialization.
      * @return The singleton TemporalExecutor instance.
      */
     public TemporalExecutor getTemporalExecutorInstance() {
         // Ensure the instance is created and configured if it wasn't already
         // Create a dummy Temporal condition just to trigger the getExecutor logic for Temporal.class
         Temporal dummyTemporal = new Temporal(TemporalPredicate.EQUAL, java.time.LocalDateTime.now());
         return (TemporalExecutor) getExecutor(dummyTemporal);
     }
}