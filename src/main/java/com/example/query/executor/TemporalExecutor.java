package com.example.query.executor;

import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.query.binding.MatchDetail;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.condition.Temporal;
import com.example.query.index.IndexManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import no.ntnu.sandbox.Nash;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.nio.file.Path;
import java.util.stream.Collectors;

import com.example.index.NashDateEntryWithId;
import com.example.index.util.NashSerializationUtils;

/**
 * Executor for temporal conditions in queries. Delegates execution to a selected strategy.
 * Returns QueryResult containing MatchDetail objects.
 */
public final class TemporalExecutor implements ConditionExecutor<Temporal> {
    private static final Logger logger = LoggerFactory.getLogger(TemporalExecutor.class);
    
    private static final String DATE_INDEX = "ner_date";
    private static final String NASH_INDEX = "nash";
    
    // Formatter for parsing keys from the ner_date index (YYYY-MM-DD)
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd
    // Formatter for creating interval strings for Nash.invert ([YYYY-MM-DD , YYYY-MM-DD])
    private static final DateTimeFormatter NASH_INTERVAL_FORMATTER = DateTimeFormatter.ISO_DATE;
    
    // --- Strategy Management ---
    private final Map<String, TemporalExecutionStrategy> strategies = new HashMap<>();
    private String activeStrategyName = "naive"; // Default to naive strategy
    
    /**
     * Creates a new TemporalExecutor and registers default strategies.
     */
    public TemporalExecutor() {
        // Register default strategies
        registerStrategy(new NaiveTemporalStrategy());
        registerStrategy(new NashTemporalStrategy());
        // Set default active strategy (can be overridden)
        setActiveStrategy("naive");
    }
    
    /**
     * Registers a temporal execution strategy.
     * @param strategy The strategy implementation.
     */
    public void registerStrategy(TemporalExecutionStrategy strategy) {
        Objects.requireNonNull(strategy, "Strategy cannot be null");
        Objects.requireNonNull(strategy.getName(), "Strategy name cannot be null");
        if (strategies.containsKey(strategy.getName())) {
             logger.warn("Overwriting existing strategy: {}", strategy.getName());
        }
        strategies.put(strategy.getName(), strategy);
        logger.info("Registered temporal strategy: {}", strategy.getName());
    }
    
    /**
     * Sets the active strategy for executing temporal conditions.
     * @param name The name of the strategy to activate.
     * @throws IllegalArgumentException if the strategy name is not registered.
     */
    public void setActiveStrategy(String name) {
        if (!strategies.containsKey(name)) {
            throw new IllegalArgumentException("Strategy '" + name + "' is not registered.");
        }
        this.activeStrategyName = name;
        logger.info("Set active temporal strategy to: {}", name);
    }
    
     /**
      * Gets the name of the currently active strategy.
      * @return The active strategy name.
      */
     public String getActiveStrategyName() {
         return activeStrategyName;
     }

    /**
     * Gets the currently active strategy implementation.
     * @return The active TemporalExecutionStrategy.
     * @throws IllegalStateException if no active strategy is set or found.
     */
    private TemporalExecutionStrategy getActiveStrategy() {
        if (activeStrategyName == null) {
            throw new IllegalStateException("No active temporal strategy set.");
        }
        TemporalExecutionStrategy strategy = strategies.get(activeStrategyName);
        if (strategy == null) {
            // This shouldn't happen if setActiveStrategy is used correctly
            throw new IllegalStateException("Active strategy '" + activeStrategyName + "' not found in registered strategies.");
        }
        return strategy;
    }
    // --- End Strategy Management ---

    /**
     * Initializes necessary resources for a specific corpus. No Nash-specific logic needed.
     */
    public boolean initializeForCorpus(String corpusName, IndexManager indexManager) {
        logger.info("Initializing TemporalExecutor for corpus: {}", corpusName);
        // Only allow the active strategy to perform its own initialization.
        TemporalExecutionStrategy currentStrategy = getActiveStrategy();
        logger.debug("Performing strategy-specific initialization for '{}' on corpus '{}'", currentStrategy.getName(), corpusName);
        return currentStrategy.initializeForCorpus(corpusName, this);
    }

    @Override
    public QueryResult execute(
            Temporal condition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName)
            throws QueryExecutionException {
        
        // --- Determine the execution strategy (Simpler now) ---
        TemporalExecutionStrategy executionStrategy = getActiveStrategy(); // Always use the configured active strategy

        logger.debug("Executing temporal condition: {} for corpus: {} using selected strategy: {}",
                     condition, corpusName, executionStrategy.getName());
        // --- End Strategy Selection ---

        try {
            // Delegate execution to the resolved strategy
            List<MatchDetail> details = executionStrategy.execute(
                condition,
                indexes,
                granularity,
                granularitySize,
                corpusName,
                this // Pass 'this' for context (lookup tables, etc.)
            );

            logger.debug("Temporal strategy '{}' produced {} MatchDetail objects. Returning QueryResult.", executionStrategy.getName(), details.size());

            // Create QueryResult directly
            QueryResult finalResult = new QueryResult(granularity, granularitySize, details);

            logger.debug("Temporal execution complete with {} MatchDetail objects.", finalResult.getAllDetails().size());
            return finalResult;

        } catch (Exception e) {
            if (e instanceof QueryExecutionException qee) { throw qee; }
            throw new QueryExecutionException("Error executing temporal condition with strategy " + executionStrategy.getName() + ": " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
        }
    }

    // =========================================================================
    // Concrete Strategy Implementations (Inner Classes for now)
    // =========================================================================

    /**
     * Strategy using the Nash index for efficient temporal queries, supporting document and sentence granularity.
     * Reads from LevelDB using IndexAccessInterface and NashSerializationUtils.
     */
    private static class NashTemporalStrategy implements TemporalExecutionStrategy {
        private static final Logger strategyLogger = LoggerFactory.getLogger(NashTemporalStrategy.class);

        @Override
        public String getName() {
            return "nash";
        }

        @Override
        public boolean initializeForCorpus(String corpusName, TemporalExecutor temporalExecutor) {
            // No special initialization needed; index must exist on disk.
            return true;
        }

        @Override
        public List<MatchDetail> execute(
            Temporal condition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            TemporalExecutor temporalExecutor)
            throws QueryExecutionException {

            IndexAccessInterface nashIndex = indexes.get(NASH_INDEX);
            if (nashIndex == null) {
                throw new QueryExecutionException("Missing required Nash index for corpus: " + corpusName, condition.toString(), QueryExecutionException.ErrorType.MISSING_INDEX);
            }

            // Fetch and deserialize the date lookup table
            List<LocalDate> dateLookup;
            try {
                var rawLookup = nashIndex.getRaw(NashSerializationUtils.DATE_LOOKUP_KEY);
                if (rawLookup.isEmpty()) {
                    strategyLogger.warn("Nash date lookup table missing or empty for corpus '{}'.", corpusName);
                    return Collections.emptyList();
                }
                dateLookup = NashSerializationUtils.deserializeDateLookup(rawLookup.get());
                 strategyLogger.debug("Nash date lookup table loaded with {} entries.", dateLookup.size());
            } catch (Exception e) {
                throw new QueryExecutionException("Failed to read Nash date lookup table: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
            }

            List<MatchDetail> details = new ArrayList<>();
            Optional<String> variableName = condition.qualifiedVariableName();
            // Get the interval, handling the Optional return type
            Optional<String> intervalOpt = condition.toNashInterval();
            if (intervalOpt.isEmpty()) {
                strategyLogger.warn("Could not generate Nash interval for condition: {}. Skipping Nash query.", condition);
                return Collections.emptyList(); // Cannot query Nash without an interval
            }
            String interval = intervalOpt.get(); // Unwrap the Optional
            
            // TODO: Review expandYearOnlyInterval - Does it handle non-year-only intervals correctly?
            // For now, assume it returns the input if not year-only.
            String expandedInterval = Temporal.expandYearOnlyInterval(interval);
        

            // --- Revised Strategy: ALWAYS use INTERSECT ---
            // The specific temporal logic (BEFORE, AFTER, CONTAINS, etc.) is handled by 
            // the interval generated in Temporal.toNashInterval(). The Nash query 
            // should find any indexed items intersecting that generated interval.
            Nash.RangePredicate nashPredicate = Nash.RangePredicate.INTERSECT;
            strategyLogger.debug("Using Nash predicate INTERSECT for TemporalPredicate: {} (Interval: {})", 
                                 condition.temporalType(), expandedInterval);
            // --- End Revised Strategy ---

            strategyLogger.debug("Querying Nash index for corpus '{}' with expanded interval: {}, predicate: {}, variable: {}",
                                 corpusName, expandedInterval, nashPredicate, variableName.isPresent() ? variableName.get() : "none");
            try {
                String[] hashPrefixes = Nash.generateTimeHash(expandedInterval, nashPredicate);
                //strategyLogger.debug("Generated {} hash prefixes: {}", hashPrefixes.length, java.util.Arrays.toString(hashPrefixes));
                Set<NashDateEntryWithId> matchingEntries = new HashSet<>();
                int prefixesWithData = 0;
                for (String hashPrefix : hashPrefixes) {
                    var rawData = nashIndex.getRaw(hashPrefix.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if (rawData.isPresent()) {
                        prefixesWithData++;
                        List<NashDateEntryWithId> entries = NashSerializationUtils.deserializeNashEntries(rawData.get());
                        matchingEntries.addAll(entries);
                    } else {
                        // Optionally log prefixes that didn't find data
                         strategyLogger.trace("No data found for prefix: {}", hashPrefix);
                    }
                }
                strategyLogger.debug("Found data for {} out of {} prefixes. Total unique NashDateEntryWithId collected: {}", 
                                     prefixesWithData, hashPrefixes.length, matchingEntries.size());

                // Process the collected candidate entries
                for (NashDateEntryWithId entry : matchingEntries) {
                    if (entry.dateId() < 0 || entry.dateId() >= dateLookup.size()) {
                        strategyLogger.error("Invalid dateId {} found in Nash entry for position {}. Max valid ID is {}. Skipping entry.",
                                entry.dateId(), entry.position(), dateLookup.size() - 1);
                        continue;
                    }
                    LocalDate specificDate = dateLookup.get(entry.dateId());
                    
                    // TODO: Re-enable post-filtering once candidate retrieval is confirmed correct
                    // Post-filtering: Evaluate the actual date against the original condition
                    // if (evaluateTemporalCondition(condition.temporalType(), specificDate.atStartOfDay(), condition.startDate(), condition.endDate().orElse(condition.startDate()))) {
                        details.add(new MatchDetail(specificDate, ValueType.DATE, entry.position(), variableName));
                    // } else {
                        // Optional: Log skipped entries if needed for debugging
                    //     strategyLogger.trace("Skipping Nash entry (dateId={}) with date {} as it failed post-filtering for condition {}", entry.dateId(), specificDate, condition.temporalType());
                    // }
                }
                return details;
            } catch (Exception e) {
                throw new QueryExecutionException("Error querying Nash index: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
            }
        }
    }
    
    /**
     * Strategy that directly scans the date index. Handles all cases, including variables
     * and sentence granularity, but can be less efficient for simple document-level checks.
     */
    private static class NaiveTemporalStrategy implements TemporalExecutionStrategy {
        private static final Logger strategyLogger = LoggerFactory.getLogger(NaiveTemporalStrategy.class);

        @Override
        public String getName() {
            return "naive";
        }

        @Override
        public boolean initializeForCorpus(String corpusName, TemporalExecutor temporalExecutor) {
             // No specific initialization needed for this strategy.
             strategyLogger.debug("NaiveTemporalStrategy requires no specific initialization for corpus '{}'", corpusName);
             return true;
        }

        @Override
        public List<MatchDetail> execute(
            Temporal condition,
                Map<String, IndexAccessInterface> indexes,
                Query.Granularity granularity,
                int granularitySize,
                String corpusName,
                TemporalExecutor temporalExecutor) // temporalExecutor not needed here
            throws QueryExecutionException {
        
            // ADDED: Check if the required ner_date index is available
            IndexAccessInterface dateIndex = indexes.get(DATE_INDEX);
            if (dateIndex == null) {
                throw new QueryExecutionException(
                    String.format("Naive strategy requires index '%s' which is missing or failed to initialize for corpus '%s'.", DATE_INDEX, corpusName),
                    condition.toString(), 
                    QueryExecutionException.ErrorType.MISSING_INDEX
                );
            }
            
        List<MatchDetail> details = new ArrayList<>();
        String conditionId = String.valueOf(condition.hashCode());
            // IndexAccessInterface dateIndex = indexes.get(DATE_INDEX); // Assumes index exists (checked by main executor)
        TemporalPredicate type = condition.temporalType();
        Optional<LocalDateTime> queryStartOpt = condition.startDate(); // Get Optional start date
            // Use queryStart if endDate is not present, ensuring queryEnd is never null.
            // If queryStart itself is empty (e.g., variable comparison), evaluation might fail later.
            // Naive strategy needs a concrete start date to compare against index entries.
            if (queryStartOpt.isEmpty()) {
                // This shouldn't happen if the condition requires a literal date for Naive scan,
                // or if variable resolution happened before calling execute.
                // Handle gracefully for now.
                 strategyLogger.error("NaiveTemporalStrategy requires a concrete start date, but it was empty for condition: {}. Returning empty result.", condition);
                 return Collections.emptyList();
            }
            LocalDateTime queryStart = queryStartOpt.get(); // Unwrap start date
        LocalDateTime queryEnd = condition.endDate().orElse(queryStart); // End date defaults to start date if absent
            Optional<String> variableName = condition.qualifiedVariableName(); // Use qualifiedVariableName()

            strategyLogger.debug("Scanning DATE index directly for condition: {} ({}), interval [{} to {}], variable: {}",
                                 type, conditionId, queryStart, queryEnd, variableName.isPresent() ? "?"+variableName.get() : "none");

        try (var iterator = dateIndex.iterator()) {
            iterator.seekToFirst(); // Start scan from the beginning
                int entriesScanned = 0;
                int matchesFound = 0;
            while (iterator.hasNext()) {
                    // Use peekNext/next pattern to handle potential errors during parsing/deserialization gracefully per entry
                    Entry<byte[], byte[]> currentEntry = null;
                    try {
                         currentEntry = iterator.peekNext(); // Peek first
                         entriesScanned++;
                 String dateStr = new String(currentEntry.getKey(), StandardCharsets.UTF_8);
                         LocalDate docDate = parseDateKey(dateStr); // Use shared parsing logic
                
                 if (docDate != null) {
                     // Evaluate the condition using helper method
                     if (evaluateTemporalCondition(type, docDate.atStartOfDay(), queryStart, queryEnd)) {
                         PositionList positions = PositionList.deserialize(currentEntry.getValue());
                                 // Ensure we convert the List to a Set safely
                                 Set<Position> positionSet = null;
                                 if (positions != null && positions.getPositions() != null) {
                                     positionSet = new HashSet<>(positions.getPositions());
                                 }
                                 if (positionSet != null) {
                                     for (Position position : positionSet) {
                             // Create MatchDetail for each position matching the date criteria
                                         // Use docDate object (actual matched date) as the value when binding variables
                                         Object matchValue = (variableName.isPresent()) ? docDate : intervalStringFromDate(docDate); // Or use interval string?
                                         // Pass variable name Optional directly to canonical constructor
                                         details.add(new MatchDetail(matchValue, ValueType.DATE, position, variableName));
                                         matchesFound++;
                                     }
                                 }
                             }
                         }
                         iterator.next(); // Consume the entry only if processing was successful up to this point
                    } catch (Exception entryEx) {
                         strategyLogger.error("Error processing DATE index entry key '{}': {}. Skipping entry.",
                                 currentEntry != null ? new String(currentEntry.getKey(), StandardCharsets.UTF_8) : "unknown",
                                 entryEx.getMessage(), entryEx);
                         if(iterator.hasNext()) {
                            try {
                                iterator.next(); // Attempt to advance past the problematic entry
                            } catch (Exception nextEx) {
                                strategyLogger.error("Failed to advance iterator after error: {}", nextEx.getMessage(), nextEx);
                                throw new QueryExecutionException("Failed to advance iterator after error", nextEx, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
                 }
                         } else {
                            break; // Cannot advance, exit loop
                         }
                    }
                }
                strategyLogger.debug("DATE index scan completed. Scanned {} entries, found {} matching positions.", entriesScanned, matchesFound);
        } catch (Exception e) {
                // Catch errors during initial seek or general iterator issues
             throw new QueryExecutionException("Error during naive DATE index scan: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
        }
             return details;
        }

        // Helper to create interval string, potentially needed if variable binding uses date but non-binding uses interval
        private String intervalStringFromDate(LocalDate date) {
             if (date == null) return "[]";
             String formattedDate = NASH_INTERVAL_FORMATTER.format(date);
             return String.format("[%s , %s]", formattedDate, formattedDate);
                         }
                     }

    // =========================================================================
    // Static Helper Methods (used by strategies and potentially externally)
    // =========================================================================
    
    /**
     * Parses a date string potentially from the index key.
     * Expects format like 'YYYY-MM-DD'. Returns null if parsing fails.
     * Made public static for potential reuse, kept INDEX_DATE_FORMATTER private.
     */
    public static LocalDate parseDateKey(String dateStr) {
        if (dateStr == null) return null;
        try {
            // Trim potential whitespace before parsing
            return LocalDate.parse(dateStr.trim(), INDEX_DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            // Keep this at trace level as keys might not always be dates
            logger.trace("Failed to parse date key '{}' using format {}: {}", dateStr, INDEX_DATE_FORMATTER, e.getMessage());
            return null;
        }
    }
    
    /**
     * Evaluates if a document date satisfies the temporal condition.
     * Assumes the document date represents a single point in time (start == end).
     * Made public static for potential reuse.
     *
     * @param type The TemporalPredicate (CONTAINS, INTERSECT, etc.)
     * @param docDateTime The date/time associated with the document/entry (at start of day).
     * @param queryStart The start of the query interval.
     * @param queryEnd The end of the query interval.
     * @return true if the condition is met, false otherwise.
     */
     public static boolean evaluateTemporalCondition(TemporalPredicate type, LocalDateTime docDateTime, LocalDateTime queryStart, LocalDateTime queryEnd) {
         // Document interval is a single point: [docDateTime, docDateTime]
         LocalDateTime docStart = docDateTime;
         LocalDateTime docEnd = docDateTime;

         // Ensure query interval is valid (start <= end) - should be handled by Temporal constructor ideally
         if (queryStart.isAfter(queryEnd)) {
             logger.warn("Invalid query interval detected in evaluation: queryStart ({}) is after queryEnd ({}). Returning false.", queryStart, queryEnd);
             return false;
         }

         return switch (type) {
             // Query interval [queryStart, queryEnd] must contain doc interval [docStart, docEnd]
             // This means queryStart <= docStart AND queryEnd >= docEnd
             case CONTAINS -> !queryStart.isAfter(docStart) && !queryEnd.isBefore(docEnd);

             // Doc interval [docStart, docEnd] must be contained within query interval [queryStart, queryEnd]
             // This is the same logic as CONTAINS for point-based doc intervals.
             case CONTAINED_BY -> !docStart.isAfter(queryStart) && !docEnd.isBefore(queryEnd);

             // Intervals overlap: Not (query ends before doc starts OR query starts after doc ends)
             // ! (queryEnd < docStart OR queryStart > docEnd)
             // Simplified: queryStart <= docEnd AND queryEnd >= docStart
             case INTERSECT -> !queryStart.isAfter(docEnd) && !queryEnd.isBefore(docStart);

             // Comparisons (assuming comparison is against queryStart, ignoring queryEnd unless needed)
             case BEFORE -> docEnd.isBefore(queryStart); // Doc interval ends before query interval starts
             case AFTER -> docStart.isAfter(queryStart); // Doc interval starts after query interval starts
             case BEFORE_EQUAL -> !docStart.isAfter(queryStart); // Doc interval starts on or before query interval starts
             case AFTER_EQUAL -> !docEnd.isBefore(queryStart); // Doc interval ends on or after query interval starts
             case EQUAL -> docStart.isEqual(queryStart) && docEnd.isEqual(queryStart); // Doc interval matches the query start exactly

             // PROXIMITY might need range logic - not implemented here yet. Requires Temporal.range()
             case PROXIMITY -> {
                  logger.warn("TemporalPredicate.PROXIMITY evaluation not fully implemented in evaluateTemporalCondition.");
                  // Basic intersect check for now? Or depends on Temporal.range
                  yield !queryStart.isAfter(docEnd) && !queryEnd.isBefore(docStart); // Placeholder: same as intersect
             }

             // Default for any unexpected/unhandled types
             default -> {
                 logger.warn("Unsupported TemporalPredicate type encountered in evaluation: {}. Returning false.", type);
                 yield false;
             }
         };
     }
} 