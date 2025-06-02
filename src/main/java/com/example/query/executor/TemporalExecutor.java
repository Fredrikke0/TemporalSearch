package com.example.query.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.index.NashDateEntryWithId;
import com.example.index.util.NashSerializationUtils;
import com.example.query.binding.ValueType;
import com.example.query.index.IndexManager;
import com.example.query.model.Query;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.condition.Temporal;

import no.ntnu.sandbox.Nash;

/**
 * Executor for temporal conditions in queries. Delegates execution to a selected strategy.
 * Returns QueryResult containing MatchDetail objects.
 */
public final class TemporalExecutor implements ConditionExecutor<Temporal> {
    private static final Logger logger = LoggerFactory.getLogger(TemporalExecutor.class);

    private static final String DATE_INDEX = "ner_date";
    private static final String NASH_INDEX = "nash";

    // Formatter for parsing keys from the ner_date index (YYYYMMDD)
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
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
        TemporalExecutionStrategy currentStrategy = getActiveStrategy();
        logger.debug("Performing strategy-specific initialization for '{}' on corpus '{}'", currentStrategy.getName(), corpusName);
        return currentStrategy.initializeForCorpus(corpusName, this);
    }

    @Override
    public QueryResultSoA execute(Temporal condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements)
        throws QueryExecutionException {

        logger.debug("Executing TEMPORAL condition with active strategy: '{}', AttributeRequirements: {}",
            activeStrategyName, requirements.getRequiredSoAAttributes());

        TemporalExecutionStrategy strategy = getActiveStrategy();
        return strategy.execute(condition, indexes, granularity, granularitySize, corpusName, this, requirements);
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
            // Potentially pre-load idToDateLookup if it's static per corpus and index opening
            return true;
        }

        @Override
        public QueryResultSoA execute(
            Temporal condition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            TemporalExecutor temporalExecutor,
            AttributeRequirements requirements)
            throws QueryExecutionException {

            strategyLogger.debug("Executing NashTemporalStrategy for condition: {}", condition);
            QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);

            IndexAccessInterface nashDB = indexes.get(NASH_INDEX);
            if (nashDB == null || !nashDB.isOpen()) {
                strategyLogger.warn("NashDB index '{}' is not available or not open. Cannot execute temporal condition with Nash strategy.", NASH_INDEX);
                return resultSoA;
            }

            List<LocalDate> idToDateLookup;
            try {
                Optional<byte[]> dateLookupBytes = nashDB.getRaw(NashSerializationUtils.DATE_LOOKUP_KEY);
                if (dateLookupBytes.isEmpty()) {
                    strategyLogger.error("NashTemporalStrategy: Date lookup table (idToDate) not found in Nash index under key '{}'. Cannot proceed.", new String(NashSerializationUtils.DATE_LOOKUP_KEY, StandardCharsets.UTF_8));
                    return resultSoA;
                }
                idToDateLookup = NashSerializationUtils.deserializeDateLookup(dateLookupBytes.get());
                if (idToDateLookup.isEmpty() && wouldExpectDates(condition)) { // Add a helper 'wouldExpectDates' if needed
                     strategyLogger.warn("NashTemporalStrategy: Date lookup table is empty, but condition implies dates are expected. Condition: {}", condition);
                     // Depending on strictness, could return empty resultSoA here.
                }
                strategyLogger.debug("NashTemporalStrategy: Successfully loaded idToDate lookup table with {} entries.", idToDateLookup.size());
            } catch (IOException | IndexAccessException e) {
                strategyLogger.error("NashTemporalStrategy: Failed to load or deserialize idToDate lookup table: {}", e.getMessage(), e);
                throw new QueryExecutionException("Failed to load essential date lookup data from Nash index.", e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
            }

            String variableToBind = condition.variableName();
            if (variableToBind == null) {
                strategyLogger.warn("No variable specified in Temporal condition for Nash strategy. Using default or skipping. Condition: {}", condition);
                // Decide handling: use a default like "@temporal", or return if variable is essential.
                // For now, let's assume it might be okay if the query structure allows it (e.g. EXISTS { ?s <temp_prop> ?date. ?date DATE(...) })
                // If variableToBind is essential for result population, this needs stricter handling.
            }

            Optional<LocalDateTime> queryDateTimeStart = condition.startDate();
            Optional<LocalDateTime> queryDateTimeEnd = condition.endDate();

            String nashQueryIntervalString = convertToNashIntervalString(queryDateTimeStart, queryDateTimeEnd, condition.temporalType());
            if (nashQueryIntervalString == null) {
                strategyLogger.warn("NashTemporalStrategy: Could not form a valid Nash query interval for condition: {}. No results can be found.", condition);
                return resultSoA;
            }
            strategyLogger.debug("NashTemporalStrategy: Constructed Nash query interval: {}", nashQueryIntervalString);

            Nash.RangePredicate nashPredicate = mapToNashRangePredicate(condition.temporalType());
            String[] searchPrefixes;
            try {
                searchPrefixes = Nash.generateTimeHash(nashQueryIntervalString, nashPredicate);
            } catch (Exception e) {
                strategyLogger.error("NashTemporalStrategy: Error calling Nash.generateTimeHash for interval '{}', predicate '{}': {}", nashQueryIntervalString, nashPredicate, e.getMessage(), e);
                throw new QueryExecutionException("Failed to generate Nash search prefixes.", e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
            }

            if (searchPrefixes == null || searchPrefixes.length == 0) {
                strategyLogger.debug("NashTemporalStrategy: Nash.generateTimeHash returned no search prefixes for interval '{}', predicate '{}'.", nashQueryIntervalString, nashPredicate);
                return resultSoA;
            }
            strategyLogger.debug("NashTemporalStrategy: Nash prefixes to search: {}", Arrays.toString(searchPrefixes));

            // Use a Set to store unique matches before adding to resultSoA to handle cases where
            // multiple Nash prefixes might resolve to the same underlying (Position, LocalDate) pair.
            Set<UniqueTemporalMatch> uniqueMatches = new HashSet<>();
            int conceptualRowIdCounter = 0; // This will be managed by QueryResultSoA now

            try {
                for (String prefix : searchPrefixes) {
                    if (prefix == null || prefix.isEmpty()) continue;
                    strategyLogger.trace("NashTemporalStrategy: Searching for Nash prefix: '{}'", prefix);
                    Optional<byte[]> serializedEntriesBytes = nashDB.getRaw(prefix.getBytes(StandardCharsets.UTF_8));

                    if (serializedEntriesBytes.isPresent()) {
                        strategyLogger.trace("NashTemporalStrategy: Found data for prefix '{}'. Deserializing NashDateEntryWithId list.", prefix);
                        List<NashDateEntryWithId> nashEntries = NashSerializationUtils.deserializeNashEntries(serializedEntriesBytes.get());
                        strategyLogger.trace("NashTemporalStrategy: Deserialized {} NashDateEntryWithId entries for prefix '{}'.", nashEntries.size(), prefix);

                        for (NashDateEntryWithId nashEntry : nashEntries) {
                            int dateId = nashEntry.dateId();
                            Position position = nashEntry.position();

                            if (dateId >= 0 && dateId < idToDateLookup.size()) {
                                LocalDate actualDate = idToDateLookup.get(dateId);
                                strategyLogger.trace("NashTemporalStrategy: Resolved dateId {} to date {} for position {}.", dateId, actualDate, position);

                                // Final precise check: Nash prefixes are a first-pass filter.
                                // The actualDate must satisfy the original query's temporal constraints.
                                boolean finalMatch = evaluateTemporalCondition(
                                    condition.temporalType(),
                                    actualDate.atStartOfDay(),
                                    actualDate.atTime(LocalTime.MAX), // Consider date as full day
                                    queryDateTimeStart.orElse(null),
                                    queryDateTimeEnd.orElse(null)
                                );

                                if (finalMatch) {
                                    strategyLogger.trace("NashTemporalStrategy: Final temporal condition met for date {}, position {}. Adding to unique matches.", actualDate, position);
                                    uniqueMatches.add(new UniqueTemporalMatch(position, actualDate));
                                } else {
                                    strategyLogger.trace("NashTemporalStrategy: Date {} (from prefix {}) did NOT meet final condition. Query start: {}, Query end: {}, Type: {}", actualDate, prefix, queryDateTimeStart, queryDateTimeEnd, condition.temporalType());
                                }
                            } else {
                                strategyLogger.warn("NashTemporalStrategy: Invalid dateId {} found for position {} with prefix '{}'. Max valid id: {}. Skipping entry.", dateId, position, prefix, idToDateLookup.size() - 1);
                            }
                        }
                    } else {
                         strategyLogger.trace("NashTemporalStrategy: No data found for Nash prefix: '{}'", prefix);
                    }
                }

                strategyLogger.debug("NashTemporalStrategy: Found {} unique temporal matches after processing all prefixes.", uniqueMatches.size());

                // Populate QueryResultSoA from uniqueMatches
                // Group by conceptual row ID implicitly by how resultSoA.add handles it
                // The `variableToBind` might need to be more robustly determined (e.g., if always @temporal)
                String effectiveVarName = (variableToBind == null || variableToBind.isEmpty()) ? "@temporal" : variableToBind;

                for (UniqueTemporalMatch match : uniqueMatches) {
                    resultSoA.add(
                        match.date(), // Value to bind (LocalDate)
                        ValueType.DATE,
                        effectiveVarName,
                        match.position().getDocumentId(),
                        requirements.needsSentenceId ? match.position().getSentenceId() : -1,
                        requirements.needsPositions ? match.position().getBeginPosition() : -1,
                        requirements.needsPositions ? match.position().getEndPosition() : -1,
                        requirements.needsSynonymIds ? -1 : -1, // NashDateEntry does not store synonymId directly applicable here; this is for NER synonyms, not date part synonyms.
                                                                 // If date parts (year, month) were bound, this would change.
                        resultSoA.getNextConceptualRowId() // Manage conceptual rows correctly
                    );
                }

            } catch (IOException | IndexAccessException e) {
                strategyLogger.error("NashTemporalStrategy: Error accessing Nash index or deserializing entries: {}", e.getMessage(), e);
                throw new QueryExecutionException("Failed to execute temporal condition with Nash strategy due to index access error: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
            } catch (Exception e) { // Catch broader exceptions from Nash or other logic
                strategyLogger.error("NashTemporalStrategy: Unexpected error during execution: {}", e.getMessage(), e);
                throw new QueryExecutionException("Unexpected error in Nash temporal strategy: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
            }

            strategyLogger.debug("NashTemporalStrategy execution finished, {} results in SoA ({} conceptual rows).", resultSoA.size(), resultSoA.getConceptualRowCount());
            return resultSoA;
        }

        // Helper to determine if a condition is expected to yield dates, for warnings.
        private boolean wouldExpectDates(Temporal condition) {
            // Simple check: if any date component is specified in the condition.
            return condition.startDate().isPresent() || condition.endDate().isPresent();
        }

        /**
         * Converts query start/end dates into a Nash interval string "[YYYY-MM-DD , YYYY-MM-DD]".
         * Handles cases like specific dates, year, year-month, or ranges.
         */
        private String convertToNashIntervalString(Optional<LocalDateTime> queryStart, Optional<LocalDateTime> queryEnd, TemporalPredicate predicateType) {
            LocalDate start = queryStart.map(LocalDateTime::toLocalDate).orElse(null);
            LocalDate end = queryEnd.map(LocalDateTime::toLocalDate).orElse(null);

            // Handle single-point predicates like EQUAL, AFTER, BEFORE etc. by defining a minimal interval
            // or ensuring they are correctly interpreted by Nash.generateTimeHash.
            // For Nash, most queries are range-based. A single date X becomes "[X , X]".
            // AFTER X becomes "[X+1, GLOBAL_UPPER_BOUND]"
            // BEFORE X becomes "[GLOBAL_LOWER_BOUND, X-1]"
            // This logic might need refinement based on how Nash.generateTimeHash interprets single points for such predicates.

            LocalDate effectiveStart = null;
            LocalDate effectiveEnd = null;

            if (start != null && end != null) { // Explicit range or single day if start.isEqual(end)
                effectiveStart = start;
                effectiveEnd = end;
            } else if (start != null) { // Only start is provided
                 effectiveStart = start;
                 // For predicates like AFTER, AFTER_EQUAL, EQUAL when only start is given.
                 // For EQUAL, treat as single day. For AFTER, range is (start, future]. For AFTER_EQUAL, [start, future]
                 switch (predicateType) {
                    case EQUAL:
                        effectiveEnd = start;
                        break;
                    case AFTER: // (start, GLOBAL_UPPER_BOUND]
                        effectiveStart = start.plusDays(1); // Exclusive start
                        effectiveEnd = Nash.GLOBAL_UPPER_BOUND; // Use Nash's global upper bound
                        break;
                    case AFTER_EQUAL: // [start, GLOBAL_UPPER_BOUND]
                        effectiveEnd = Nash.GLOBAL_UPPER_BOUND;
                        break;
                     case BEFORE: // [GLOBAL_LOWER_BOUND, start-1] - this case is unusual if queryStart is 'start'
                     case BEFORE_EQUAL: // [GLOBAL_LOWER_BOUND, start] - unusual
                         // If query logic means 'date is before queryStart', then queryStart is the 'end' of the range.
                         strategyLogger.warn("convertToNashIntervalString: Predicate {} with only queryStart is ambiguous. Treating queryStart as the reference point.", predicateType);
                         // Defaulting to a single day for now if predicate is not clearly directional like AFTER/*
                         effectiveEnd = start; // Fallback to single day. This path needs careful thought for all predicates.
                         break;
                    default: // INTERSECT, CONTAINS, etc. with only a start point is often a single day query.
                        effectiveEnd = start;
                        break;
                }
            } else if (end != null) { // Only end is provided
                effectiveEnd = end;
                // For predicates like BEFORE, BEFORE_EQUAL when only end is given.
                switch (predicateType) {
                    case EQUAL: // Should ideally have start, but if only end, treat as single day
                        effectiveStart = end;
                        break;
                    case BEFORE: // [GLOBAL_LOWER_BOUND, end-1]
                        effectiveStart = Nash.GLOBAL_LOWER_BOUND; // Use Nash's global lower bound
                        effectiveEnd = end.minusDays(1); // Exclusive end
                        break;
                    case BEFORE_EQUAL: // [GLOBAL_LOWER_BOUND, end]
                        effectiveStart = Nash.GLOBAL_LOWER_BOUND;
                        break;
                    default:
                        effectiveStart = end; // Fallback to single day.
                        break;
                }
            } else {
                // No start or end date specified in the query - this might mean "any date that satisfies a structural property"
                // or it's an unbounded query. For Nash, an interval is typically needed.
                // This could map to the entire global Nash range if the query intends "any date".
                strategyLogger.warn("convertToNashIntervalString: No queryStart or queryEnd provided for predicate {}. This might lead to querying the entire Nash range.", predicateType);
                effectiveStart = Nash.GLOBAL_LOWER_BOUND;
                effectiveEnd = Nash.GLOBAL_UPPER_BOUND;
            }

            // Ensure start is not after end
            if (effectiveStart != null && effectiveEnd != null && effectiveStart.isAfter(effectiveEnd)) {
                strategyLogger.warn("convertToNashIntervalString: Effective start date {} is after effective end date {}. Swapping them for Nash interval.", effectiveStart, effectiveEnd);
                LocalDate temp = effectiveStart;
                effectiveStart = effectiveEnd;
                effectiveEnd = temp;
            }

            // Ensure dates are within Nash global bounds, clamp if necessary
            if (effectiveStart != null && effectiveStart.isBefore(Nash.GLOBAL_LOWER_BOUND)) {
                effectiveStart = Nash.GLOBAL_LOWER_BOUND;
            }
            if (effectiveEnd != null && effectiveEnd.isAfter(Nash.GLOBAL_UPPER_BOUND)) {
                effectiveEnd = Nash.GLOBAL_UPPER_BOUND;
            }

            if (effectiveStart == null || effectiveEnd == null) {
                 strategyLogger.error("convertToNashIntervalString: Could not determine effective start/end for Nash interval. QueryStart: {}, QueryEnd: {}, Predicate: {}", queryStart, queryEnd, predicateType);
                 return null; // Cannot form a valid interval
            }


            return String.format("[%s , %s]",
                NASH_INTERVAL_FORMATTER.format(effectiveStart),
                NASH_INTERVAL_FORMATTER.format(effectiveEnd));
        }

        // Removed getDocIdsForMatchingDates as its logic is now integrated into execute
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
            strategyLogger.debug("NaiveTemporalStrategy requires no specific initialization for corpus: {}", corpusName);
            return true; // Naive strategy requires no special setup
        }

        @Override
        public QueryResultSoA execute(
            Temporal condition,
                Map<String, IndexAccessInterface> indexes,
                Query.Granularity granularity,
                int granularitySize,
                String corpusName,
                TemporalExecutor temporalExecutor,
                AttributeRequirements requirements)
            throws QueryExecutionException {
            strategyLogger.debug("Executing NaiveTemporalStrategy for condition: {}, AttrReqs: {}", condition, requirements.getRequiredSoAAttributes());
            QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
            int conceptualRowIdCounter = 0;

            IndexAccessInterface dateIndex = indexes.get(DATE_INDEX);
            if (dateIndex == null || !dateIndex.isOpen()) {
                strategyLogger.warn("Date index '{}' is not available or not open. Cannot execute temporal condition.", DATE_INDEX);
                return resultSoA;
            }

            Optional<LocalDateTime> queryDateTimeStart = condition.startDate();
            Optional<LocalDateTime> queryDateTimeEnd = condition.endDate();
            String variableNameToBind = condition.variableName();

            strategyLogger.debug("Naive strategy: queryStart={}, queryEnd={}, variable={}, DateIndex='{}'",
                queryDateTimeStart, queryDateTimeEnd, variableNameToBind, dateIndex.getIndexType());

            try (RocksIterator iterator = dateIndex.iterateFromFirst()) {
                strategyLogger.debug("NaiveTemporalStrategy: Iterator obtained. isValid: {}", iterator.isValid());
                while (iterator.isValid()) {
                    byte[] keyBytes = iterator.key();
                    byte[] valueBytes = iterator.value();
                    String dateStr = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
                    strategyLogger.trace("NaiveTemporalStrategy: Iterator valid. Key='{}', Value size={}", dateStr, valueBytes.length);

                    LocalDate entryDate = parseDateKey(dateStr);

                    if (entryDate != null) {
                        boolean temporalMatch = evaluateTemporalCondition(
                            condition.temporalType(),
                            entryDate.atStartOfDay(), entryDate.atTime(LocalTime.MAX),
                            queryDateTimeStart.orElse(null), queryDateTimeEnd.orElse(null)
                        );

                        if (temporalMatch) {
                            Object valueToBindInSoA = entryDate;
                            strategyLogger.trace("NaiveTemporalStrategy: Key='{}', Date='{}', TemporalMatch=true. Deserializing PositionListSoA.", dateStr, entryDate);

                            conceptualRowIdCounter = processEntry(
                                new SimpleEntry<>(keyBytes, valueBytes),
                                condition, resultSoA, requirements,
                                granularity, granularitySize, conceptualRowIdCounter, entryDate, variableNameToBind, valueToBindInSoA
                            );
                        }
                    }
                    iterator.next();
                }
            }
             catch (IOException e) {
                strategyLogger.error("IOException during NaiveTemporalStrategy execution: {}", e.getMessage(), e);
                throw new QueryExecutionException("Failed to read from date index: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
            }
            catch (Exception e) {
                strategyLogger.error("Error executing NaiveTemporalStrategy: {}", e.getMessage(), e);
                throw new QueryExecutionException("Failed to execute temporal condition: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
            }
            strategyLogger.debug("NaiveTemporalStrategy execution finished, {} conceptual rows. Final SoA size: {}", resultSoA.getConceptualRowCount(), resultSoA.size());
            return resultSoA;
        }

        private int processEntry(Map.Entry<byte[], byte[]> entry, Temporal condition,
                                  QueryResultSoA resultSoA, AttributeRequirements requirements,
                                  Query.Granularity granularity, int granularitySize, int currentConceptualRowId,
                                  LocalDate boundDate, String variableNameToBind, Object valueToBind)
                                  throws IOException {
            byte[] valueBytes = entry.getValue(); // Get value from entry
            String keyString = new String(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8); // Get key string for logging
            strategyLogger.trace("NaiveTemporalStrategy.processEntry: Key='{}', boundDate='{}', variable='{}'", keyString, boundDate, variableNameToBind);

            PositionListSoA positions = PositionListSoA.deserializeFromCompositeBlob(valueBytes);
            strategyLogger.trace("NaiveTemporalStrategy.processEntry: Key='{}', deserialized PositionListSoA, numPositions={}", keyString, positions.getNumPositions());

            int conceptualRowIdForTheseBindings = currentConceptualRowId;
            boolean firstBindingForThisEntry = true;

            for (int i = 0; i < positions.getNumPositions(); i++) {
                 if (firstBindingForThisEntry) {
                    conceptualRowIdForTheseBindings = resultSoA.getNextConceptualRowId();
                    firstBindingForThisEntry = false;
                }
                resultSoA.add(
                    valueToBind,
                    ValueType.DATE,
                    variableNameToBind,
                    positions.getDocIdAt(i),
                    requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                    requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                    requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                    requirements.needsSynonymIds ? positions.getSynonymIdAt(i) : -1,
                    conceptualRowIdForTheseBindings
                );
            }
            return positions.getNumPositions() > 0 ? currentConceptualRowId +1 : currentConceptualRowId;
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
            // Assume yyyyMMdd format directly
            return LocalDate.parse(dateStr.trim(), INDEX_DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            logger.trace("Failed to parse date key '{}' using format yyyyMMdd: {}", dateStr, e.getMessage());
            return null;
        }
    }

    /**
     * Evaluates if a document date satisfies the temporal condition.
     * Assumes the document date represents a single point in time (start == end).
     * Made public static for potential reuse.
     *
     * @param type The TemporalPredicate (CONTAINS, INTERSECT, etc.)
     * @param docDateTimeStart The start date/time associated with the document/entry.
     * @param docDateTimeEnd The end date/time associated with the document/entry.
     * @param queryStart The start of the query interval.
     * @param queryEnd The end of the query interval.
     * @return true if the condition is met, false otherwise.
     */
     public static boolean evaluateTemporalCondition(TemporalPredicate type, LocalDateTime docDateTimeStart, LocalDateTime docDateTimeEnd, LocalDateTime queryStart, LocalDateTime queryEnd) {
         // Ensure query interval is valid (start <= end)
         if (queryStart != null && queryEnd != null && queryStart.isAfter(queryEnd)) {
             logger.warn("Invalid query interval detected in evaluation: queryStart ({}) is after queryEnd ({}). Returning false.", queryStart, queryEnd);
             return false;
         }
         // Ensure doc interval is valid
        if (docDateTimeStart.isAfter(docDateTimeEnd)) {
            logger.warn("Invalid document interval detected in evaluation: docStart ({}) is after docEnd ({}). Returning false.", docDateTimeStart, docDateTimeEnd);
             return false;
         }

         return switch (type) {
             case CONTAINS -> queryStart != null && queryEnd != null && !queryStart.isAfter(docDateTimeStart) && !queryEnd.isBefore(docDateTimeEnd);
             case CONTAINED_BY -> queryStart != null && queryEnd != null && !docDateTimeStart.isAfter(queryStart) && !docDateTimeEnd.isBefore(queryEnd);
             case INTERSECT -> queryStart != null && queryEnd != null && !queryStart.isAfter(docDateTimeEnd) && !queryEnd.isBefore(docDateTimeStart);
             case BEFORE -> queryStart != null && docDateTimeEnd.isBefore(queryStart);
             // For DATE(> X) or similar, queryStart is X. docDateTimeStart must be after X.
             case AFTER -> queryStart != null && docDateTimeStart.isAfter(queryStart);
             // For DATE(<= X), queryEnd is X. docDateTimeStart must be before or on X.
             case BEFORE_EQUAL -> queryEnd != null && !docDateTimeStart.isAfter(queryEnd);
             // For DATE(>= X), queryStart is X. docDateTimeEnd must be on or after X.
             case AFTER_EQUAL -> queryStart != null && !docDateTimeEnd.isBefore(queryStart);
             case EQUAL -> {
                if (queryStart == null) {
                    logger.trace("EQUAL predicate cannot be evaluated with null queryStart. Doc: [{}, {}]", docDateTimeStart, docDateTimeEnd);
                    yield false;
                }
                // If queryEnd is null, it's a single date query based on queryStart
                LocalDateTime effectiveQueryEnd = (queryEnd == null) ? queryStart.toLocalDate().atTime(23,59,59) : queryEnd;

                if (queryStart.toLocalDate().isEqual(effectiveQueryEnd.toLocalDate())) {
                    // Query is a single day, doc's day must be exactly that day.
                    yield docDateTimeStart.toLocalDate().isEqual(queryStart.toLocalDate());
                } else {
                    // Query is a range, doc's day must be contained by or equal to query range.
                    yield !docDateTimeStart.toLocalDate().isBefore(queryStart.toLocalDate()) &&
                          !docDateTimeEnd.toLocalDate().isAfter(effectiveQueryEnd.toLocalDate());
                }
            }
             case PROXIMITY -> {
                 logger.warn("TemporalPredicate.PROXIMITY is generally handled by Nash strategy's prefix expansion. Direct evaluation in NaiveStrategy may not be meaningful or is treated as INTERSECT.");
                 // Treat as INTERSECT for evaluation purposes if it reaches here.
                 yield queryStart != null && queryEnd != null && !queryStart.isAfter(docDateTimeEnd) && !queryEnd.isBefore(docDateTimeStart);
             }
             default -> {
                 logger.warn("Unsupported TemporalPredicate type encountered in evaluation: {}. Returning false.", type);
                 yield false;
             }
         };
     }

    // Helper record for tracking unique matches in NashTemporalStrategy
    private record UniqueTemporalMatch(Position position, LocalDate date) {}

    /**
     * Maps a TemporalPredicate to a Nash.RangePredicate.
     * For predicates like EQUAL, BEFORE, AFTER that are converted to intervals by
     * Temporal.toNashInterval(), they are treated as INTERSECT queries against the Nash index.
     */
    private static Nash.RangePredicate mapToNashRangePredicate(TemporalPredicate temporalPredicate) {
        return switch (temporalPredicate) {
            case CONTAINS -> Nash.RangePredicate.CONTAINS;       // Query interval contains document interval
            case CONTAINED_BY -> Nash.RangePredicate.CONTAINED_BY; // Query interval is contained by document interval
            case INTERSECT, EQUAL, BEFORE, AFTER, BEFORE_EQUAL, AFTER_EQUAL -> Nash.RangePredicate.INTERSECT;
            // PROXIMITY might also map to INTERSECT or its own Nash predicate if available and handled by toNashInterval.
            // For now, assuming PROXIMITY is handled by Temporal.toNashInterval() to produce a range for INTERSECT.
            case PROXIMITY -> Nash.RangePredicate.PROXIMITY;
            default -> {
                logger.warn("Unhandled TemporalPredicate {} in mapToNashRangePredicate, defaulting to INTERSECT.", temporalPredicate);
                yield Nash.RangePredicate.INTERSECT;
            }
        };
    }
}