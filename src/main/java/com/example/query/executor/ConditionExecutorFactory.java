package com.example.query.executor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.index.util.SynonymManager;
import com.example.query.model.Query;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.condition.Condition;
import com.example.query.model.condition.Contains;
import com.example.query.model.condition.Dependency;
import com.example.query.model.condition.Logical;
import com.example.query.model.condition.Ner;
import com.example.query.model.condition.Not;
import com.example.query.model.condition.Pos;
import com.example.query.model.condition.StitchedCondition;
import com.example.query.model.condition.Temporal;

/**
 * Factory for creating or retrieving ConditionExecutor instances based on Condition type.
 * Ensures that singleton executors (like TemporalExecutor) are reused.
 */
public class ConditionExecutorFactory {
    private static final Logger logger = LoggerFactory.getLogger(ConditionExecutorFactory.class);

    private final Map<Class<? extends Condition>, ConditionExecutor<?>> executorCache = new ConcurrentHashMap<>();
    private final SynonymManager synonymManager;
    private final String stitchStrategy;
    private final Query.Granularity queryGranularity;

    public ConditionExecutorFactory(SynonymManager synonymManager, String stitchStrategy, Query.Granularity queryGranularity) {
        this.synonymManager = synonymManager;
        this.stitchStrategy = stitchStrategy;
        this.queryGranularity = queryGranularity;
        logger.debug("Initialized condition executor factory with SynonymManager, stitchStrategy: {}, queryGranularity: {}.", stitchStrategy, queryGranularity);
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

        if (conditionClass == Temporal.class) {
            return (ConditionExecutor<T>) executorCache.computeIfAbsent(Temporal.class, k -> {
                logger.debug("Creating and caching TemporalExecutor instance.");
                return new TemporalExecutor();
            });
        }

        if (condition instanceof Logical) {
            return (ConditionExecutor<T>) new LogicalExecutor(this, this.stitchStrategy, this.queryGranularity);
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
              Function<Class<? extends Condition>, NotExecutor> factoryFunction = k -> {
                   logger.debug("Creating and caching NotExecutor instance.");
                   return new NotExecutor(this);
              };
              return (ConditionExecutor<T>) executorCache.computeIfAbsent(Not.class, factoryFunction);
          }
        if (condition instanceof StitchedCondition) {
            logger.debug("Creating new StitchedExecutor instance.");
            return (ConditionExecutor<T>) new StitchedExecutor(this.synonymManager);
        }

        throw new IllegalArgumentException("No executor found for condition type: " + conditionClass.getSimpleName() + " (Full type: " + condition.getClass().getName() + ")");
    }

     /**
      * Utility method to specifically get the configured TemporalExecutor instance.
      * Useful for operations like initialization.
      * @return The singleton TemporalExecutor instance.
      */
     public TemporalExecutor getTemporalExecutorInstance() {
         Temporal dummyTemporal = new Temporal(TemporalPredicate.EQUAL, java.time.LocalDateTime.now());
         return (TemporalExecutor) getExecutor(dummyTemporal);
     }
}