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
import com.example.query.executor.QueryResult;

import org.apache.pig.impl.util.MultiMap;
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

/**
 * Executor for temporal conditions in queries. Delegates execution to a selected strategy.
 * Returns QueryResult containing MatchDetail objects.
 */
public final class TemporalExecutor implements ConditionExecutor<Temporal> {
    private static final Logger logger = LoggerFactory.getLogger(TemporalExecutor.class);
    
    private static final String DATE_INDEX = "ner_date";
    
    // Formatter for parsing keys from the ner_date index (YYYY-MM-DD)
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd
    // Formatter for creating interval strings for Nash.invert ([YYYY-MM-DD , YYYY-MM-DD])
    private static final DateTimeFormatter NASH_INTERVAL_FORMATTER = DateTimeFormatter.ISO_DATE;
    
    // --- New structure for Nash Index ---
    /** Record to hold position and the ID of the specific date associated with it. */
    private record NashDateEntryWithId(Position position, int dateId) {}

    /** Stores the actual Nash index structure: Corpus -> Nash Prefix -> Set of entries with date IDs */
    final Map<String, Map<String, Set<NashDateEntryWithId>>> nashIndicesWithIds = new HashMap<>();

    /** Stores the lookup from date ID back to LocalDate for each corpus */
    final Map<String, List<LocalDate>> corpusDateLookups = new HashMap<>();
    // --- End New structure ---
    
    // Store Nash indices per corpus: Map<CorpusName, Map<NashHashPrefix, Set<Position>>>
    // This is kept here as it's a shared resource potentially used by Nash strategy
    // Changed value type to Set<Position> to support sentence granularity with offsets
    final Map<String, Map<String, Set<Position>>> nashIndices = new HashMap<>();
    private final Map<String, Boolean> nashInitializationStatus = new HashMap<>(); // Track init status per corpus
    
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
     * Initializes necessary resources for a specific corpus, like the Nash index.
     * Delegates initialization to the active strategy if needed.
     * Ensures Nash index is initialized only once per corpus if Nash strategy is active or potentially active.
     */
    public boolean initializeForCorpus(String corpusName, IndexManager indexManager) {
        logger.info("Initializing TemporalExecutor for corpus: {}", corpusName);

        // Ensure Nash index is initialized if the Nash strategy *might* be used.
        // We do this eagerly regardless of the *currently* active strategy to avoid
        // re-initialization if the strategy is switched later.
        // Use nashInitializationStatus for the check, as nashIndices might exist but be empty from a failed attempt.
        if (!nashInitializationStatus.containsKey(corpusName)) {
            logger.debug("Attempting Nash index initialization for corpus: {}", corpusName);
            boolean nashInitSuccess = initializeNashIndexInternal(corpusName, indexManager);
            nashInitializationStatus.put(corpusName, nashInitSuccess);
            if (!nashInitSuccess) {
                logger.error("Nash index initialization failed for corpus: {}. Nash strategy will be unavailable.", corpusName);
                // Decide if this is a fatal error or if we can proceed with fallback strategies.
                // For now, log error and continue.
            }
        } else {
             logger.debug("Nash index already initialized or initialization previously attempted for corpus: {}", corpusName);
        }

        // Allow the *currently active* strategy to perform its own initialization.
        TemporalExecutionStrategy currentStrategy = getActiveStrategy();
        logger.debug("Performing strategy-specific initialization for '{}' on corpus '{}'", currentStrategy.getName(), corpusName);
        boolean strategyInitSuccess = currentStrategy.initializeForCorpus(corpusName, this);

        // Overall success depends on both Nash (if relevant) and the active strategy's init.
        // If Nash failed but the active strategy doesn't need it (e.g., index_scan), we might still consider it successful overall.
        // Let's return the active strategy's success status for now.
        return strategyInitSuccess;
    }

    @Override
    public QueryResult execute(
            Temporal condition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName)
            throws QueryExecutionException {
        
        if (!indexes.containsKey(DATE_INDEX)) {
            throw new QueryExecutionException(String.format("Missing required index: %s", DATE_INDEX), condition.toString(), QueryExecutionException.ErrorType.MISSING_INDEX);
        }
        
        if (!nashInitializationStatus.containsKey(corpusName)) {
             logger.warn("TemporalExecutor not explicitly initialized for corpus '{}'. Nash index might be unavailable.", corpusName);
        }

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
    // Internal Nash Index Initialization (used by initializeForCorpus)
    // =========================================================================

    /**
     * Internal method to initialize the Nash index structure for a specific corpus.
     * Stores Set<NashDateEntryWithId> now, using a date lookup table.
     */
    private boolean initializeNashIndexInternal(String corpusName, IndexManager indexManager) {
        // Check initialization status first
        if (nashInitializationStatus.containsKey(corpusName)) {
             logger.debug("Nash index already present or previously initialized for corpus: {}", corpusName);
             return nashInitializationStatus.getOrDefault(corpusName, true); // Return stored status
        }

        logger.info("Initializing Nash index structure with Date ID lookup for corpus: {}", corpusName);

        Optional<IndexAccessInterface> indexOpt = indexManager.getIndex(DATE_INDEX);
        if (indexOpt.isEmpty()) {
            logger.error("Cannot initialize Nash index: '{}' index not found via IndexManager for corpus '{}'.", DATE_INDEX, corpusName);
            return false; // Mark initialization as failed
        }
        IndexAccessInterface dateIndex = indexOpt.get();

        // --- New: Date ID Lookup structures for this corpus ---
        Map<LocalDate, Integer> dateToId = new HashMap<>();
        List<LocalDate> idToDate = new ArrayList<>();
        // --- End New ---

        List<String> intervalStrings = new ArrayList<>();
        // Store List<NashDateEntryWithId> temporarily, mapped by the original interval list index
        Map<Integer, List<NashDateEntryWithId>> listIndexToEntries = new HashMap<>();
        int intervalIndex = 0;

        try (var iterator = dateIndex.iterator()) {
            iterator.seekToFirst();
            while (iterator.hasNext()) {
                Entry<byte[], byte[]> entry = iterator.next();
                String dateStr = new String(entry.getKey(), StandardCharsets.UTF_8);
                LocalDate docDate = parseDateKey(dateStr); // Use shared parsing logic

                if (docDate != null) {
                    String interval = String.format("[%s , %s]",
                            NASH_INTERVAL_FORMATTER.format(docDate),
                            NASH_INTERVAL_FORMATTER.format(docDate));

                    // --- New: Get or create Date ID ---
                    int dateId = dateToId.computeIfAbsent(docDate, date -> {
                        idToDate.add(date);
                        return idToDate.size() - 1; // ID is the index
                    });
                    // --- End New ---

                    PositionList positions = PositionList.deserialize(entry.getValue());
                    Set<Position> positionSet = (positions != null && positions.getPositions() != null)
                                               ? new HashSet<>(positions.getPositions())
                                               : null;

                    if (positionSet != null && !positionSet.isEmpty()) {
                         intervalStrings.add(interval); // Only add interval if positions exist
                         List<NashDateEntryWithId> entriesForInterval = new ArrayList<>();
                         for (Position pos : positionSet) {
                             entriesForInterval.add(new NashDateEntryWithId(pos, dateId));
                         }
                         listIndexToEntries.put(intervalIndex, entriesForInterval);
                         intervalIndex++;
                    } else {
                         logger.trace("Empty or null PositionList for key '{}', interval '{}'. Skipping interval.", dateStr, interval);
                    }
                } else {
                    logger.trace("Skipping non-date key during Nash initialization: {}", dateStr);
                }
            }
        } catch (Exception e) {
            logger.error("Error reading from '{}' index during Nash initialization for corpus '{}': {}", DATE_INDEX, corpusName, e.getMessage(), e);
            return false; // Mark initialization as failed
        }

        logger.debug("Prepared {} interval strings from '{}' index for Nash inversion.", intervalStrings.size(), DATE_INDEX);

        if (intervalStrings.isEmpty()) {
            logger.warn("No valid date intervals found in '{}' index for corpus '{}'. Nash index will be empty.", DATE_INDEX, corpusName);
            nashIndicesWithIds.put(corpusName, Collections.emptyMap()); // Use new map name
            corpusDateLookups.put(corpusName, Collections.emptyList()); // Store empty lookup
            return true; // Initialization technically complete (empty index)
        }

        try {
            MultiMap<String, Integer> invertedIndex = Nash.invert(intervalStrings);
            // Store Set<NashDateEntryWithId>
            Map<String, Set<NashDateEntryWithId>> corpusNashIndex = new HashMap<>();
            for (String nashPrefix : invertedIndex.keySet()) {
                Set<NashDateEntryWithId> entrySet = new HashSet<>(); // Store entries now
                for (Integer listIdx : invertedIndex.get(nashPrefix)) {
                    List<NashDateEntryWithId> entriesForIndex = listIndexToEntries.get(listIdx);
                    if (entriesForIndex != null) {
                        entrySet.addAll(entriesForIndex);
                    } else {
                         logger.warn("Inconsistency during Nash build: Prefix '{}' mapped to list index {} which has no associated entries.", nashPrefix, listIdx);
                    }
                }
                 if (!entrySet.isEmpty()) {
                    corpusNashIndex.put(nashPrefix, entrySet);
                 }
            }

            nashIndicesWithIds.put(corpusName, corpusNashIndex); // Use new map name
            corpusDateLookups.put(corpusName, idToDate); // Store the lookup table
            logger.info("Nash index initialized with {} unique hash prefixes for corpus: {} (using Date ID lookup, {} unique dates)",
                corpusNashIndex.size(), corpusName, idToDate.size());
            return true; // Mark initialization as successful

        } catch (Exception e) {
            logger.error("Failed to generate Nash index structure for corpus '{}': {}", corpusName, e.getMessage(), e);
            nashIndicesWithIds.put(corpusName, Collections.emptyMap()); // Store empty map on failure
            corpusDateLookups.put(corpusName, Collections.emptyList()); // Store empty lookup
            return false; // Mark initialization as failed
        }
    }

    // =========================================================================
    // Concrete Strategy Implementations (Inner Classes for now)
    // =========================================================================

    /**
     * Strategy using the Nash index for efficient temporal queries, supporting document and sentence granularity.
     * Uses Date ID lookup to handle variable binding and joins.
     */
    private static class NashTemporalStrategy implements TemporalExecutionStrategy {
        private static final Logger strategyLogger = LoggerFactory.getLogger(NashTemporalStrategy.class);

        @Override
        public String getName() {
            return "nash";
        }

        @Override
        public boolean initializeForCorpus(String corpusName, TemporalExecutor temporalExecutor) {
             boolean nashReady = temporalExecutor.nashInitializationStatus.getOrDefault(corpusName, false);
             if (!nashReady) {
                 strategyLogger.warn("Nash index not successfully initialized for corpus '{}'. This strategy may fail.", corpusName);
             } else if (!temporalExecutor.corpusDateLookups.containsKey(corpusName)) {
                 // Should not happen if init was successful, but good sanity check
                 strategyLogger.error("Nash index inconsistency: Initialization reported success for corpus '{}' but date lookup table is missing.", corpusName);
                 return false; // Strategy cannot function without lookup
             }
             return true;
        }

        @Override
        public List<MatchDetail> execute(
            Temporal condition,
                Map<String, IndexAccessInterface> indexes, // Not directly used
                Query.Granularity granularity,
                int granularitySize,
                String corpusName,
                TemporalExecutor temporalExecutor)
            throws QueryExecutionException {

             // --- Get required data structures ---
             Map<String, Set<NashDateEntryWithId>> nashIndex = temporalExecutor.nashIndicesWithIds.get(corpusName);
             List<LocalDate> dateLookup = temporalExecutor.corpusDateLookups.get(corpusName);

             // --- Validation ---
             if (nashIndex == null || dateLookup == null) {
                 boolean initAttempted = temporalExecutor.nashInitializationStatus.containsKey(corpusName);
                 boolean initSucceeded = temporalExecutor.nashInitializationStatus.getOrDefault(corpusName, false);
                 String errorMsg;
                 if (!initAttempted) {
                      errorMsg = "Nash index not initialized for corpus: " + corpusName + ". Initialization was never attempted.";
                 } else if (!initSucceeded) {
                      errorMsg = "Nash index initialization failed previously for corpus: " + corpusName + ". Cannot use Nash strategy.";
                 } else {
                     errorMsg = "Nash index inconsistency for corpus " + corpusName + ": Index or lookup table is null despite successful initialization status.";
                 }
                 strategyLogger.error(errorMsg);
                 throw new QueryExecutionException(errorMsg, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
             }
             if (nashIndex.isEmpty()){
                 strategyLogger.debug("Nash index for corpus '{}' is empty. Returning empty result.", corpusName);
                 return Collections.emptyList();
             }
             // --- End Validation ---

             List<MatchDetail> details = new ArrayList<>();
             String conditionId = String.valueOf(condition.hashCode());
             String variableName = condition.variable().orElse(null); // Get variable name if present

             String interval = condition.toNashInterval();
             String expandedInterval = Temporal.expandYearOnlyInterval(interval);
             Nash.RangePredicate nashPredicate = condition.temporalType().toNashPredicate();
             strategyLogger.debug("Querying Nash index for corpus '{}' with expanded interval: {}, predicate: {}, variable: {}",
                                  corpusName, expandedInterval, nashPredicate, variableName);

             try {
                 String[] hashPrefixes = Nash.generateTimeHash(expandedInterval, nashPredicate);
                 Set<NashDateEntryWithId> matchingEntries = new HashSet<>(); // Collect entries
                 int prefixesChecked = 0;
                 for (String hashPrefix : hashPrefixes) {
                     prefixesChecked++;
                     Set<NashDateEntryWithId> entriesFromHash = nashIndex.get(hashPrefix);
                     if (entriesFromHash != null) {
                         matchingEntries.addAll(entriesFromHash);
                     }
                 }
                 strategyLogger.debug("Checked {} Nash prefixes, found {} unique matching NashDateEntryWithId objects", prefixesChecked, matchingEntries.size());

                 // --- Create MatchDetail using Date ID lookup ---
                 for (NashDateEntryWithId entry : matchingEntries) {
                     if (entry.dateId() < 0 || entry.dateId() >= dateLookup.size()) {
                          strategyLogger.error("Invalid dateId {} found in Nash entry for position {}. Max valid ID is {}. Skipping entry.",
                                                entry.dateId(), entry.position(), dateLookup.size() - 1);
                          continue;
                     }
                     LocalDate specificDate = dateLookup.get(entry.dateId());
                     // Use the specificDate as the value, use the entry's position
                     details.add(new MatchDetail(specificDate, ValueType.DATE, entry.position(), variableName));
                 }
                 // --- End MatchDetail Creation ---

                 return details;
             } catch (Exception e) { // Catch specific Nash exceptions if possible
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
        
        List<MatchDetail> details = new ArrayList<>();
        String conditionId = String.valueOf(condition.hashCode());
            IndexAccessInterface dateIndex = indexes.get(DATE_INDEX); // Assumes index exists (checked by main executor)
        TemporalPredicate type = condition.temporalType();
        LocalDateTime queryStart = condition.startDate();
            // Use queryStart if endDate is not present, ensuring queryEnd is never null.
        LocalDateTime queryEnd = condition.endDate().orElse(queryStart);
            String variableName = condition.variable().orElse(null); // Get variable name if present

            strategyLogger.debug("Scanning DATE index directly for condition: {} ({}), interval [{} to {}], variable: {}",
                                 type, conditionId, queryStart, queryEnd, variableName != null ? "?"+variableName : "none");

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
                                         Object matchValue = (variableName != null) ? docDate : intervalStringFromDate(docDate); // Or use interval string?
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