package com.example.query.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
import com.example.index.util.NashSerializationUtils;
import com.example.query.binding.ValueType;
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

    private final Map<String, TemporalExecutionStrategy> strategies = new HashMap<>();
    private String activeStrategyName = "naive"; // Default to naive strategy

    // Define the strategy interface explicitly
    interface TemporalExecutionStrategy {
        String getName();
        QueryResultSoA execute(
            Temporal condition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            TemporalExecutor temporalExecutor, // Executor instance for accessing shared helpers
            AttributeRequirements requirements,
            Optional<FilteringContext> context
        ) throws QueryExecutionException;
    }

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

    @Override
    public QueryResultSoA execute(Temporal condition, Map<String, IndexAccessInterface> indexes,
                               Query.Granularity granularity,
                               int granularitySize,
                               String corpusName,
                               AttributeRequirements requirements,
                               Optional<FilteringContext> context)
        throws QueryExecutionException {

        logger.debug(">>> Executing TemporalExecutor (delegating to strategy: {})", activeStrategyName);
        logger.debug("Executing TEMPORAL condition with active strategy: '{}', AttributeRequirements: {}, ContextIsPresent: {}",
            activeStrategyName, requirements.getRequiredSoAAttributes(), context.isPresent());

        TemporalExecutionStrategy strategy = getActiveStrategy();
        return strategy.execute(condition, indexes, granularity, granularitySize, corpusName, this, requirements, context);
    }

    // =========================================================================
    // Strategy Implementations
    // =========================================================================

    /**
     * Strategy using the Nash index for efficient temporal queries, supporting document and sentence granularity.
     * Reads from RocksDB using IndexAccessInterface and NashSerializationUtils.
     */
    private static class NashTemporalStrategy implements TemporalExecutionStrategy {
        private static final Logger strategyLogger = LoggerFactory.getLogger(NashTemporalStrategy.class);

        @Override
        public String getName() {
            return "nash";
        }

        @Override
        public QueryResultSoA execute(
            Temporal condition,
            Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            TemporalExecutor temporalExecutor,
            AttributeRequirements requirements,
            Optional<FilteringContext> context)
            throws QueryExecutionException {

            strategyLogger.debug(">>> Executing NashTemporalStrategy");
            strategyLogger.debug("Executing NashTemporalStrategy for condition: {}, ContextIsPresent: {}", condition, context.isPresent());
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

            Set<UniqueTemporalMatch> uniqueMatches = new HashSet<>();

            try {
                for (String prefix : searchPrefixes) {
                    if (prefix == null || prefix.isEmpty()) continue;
                    strategyLogger.trace("NashTemporalStrategy: Searching for Nash prefix: '{}'", prefix);
                    Optional<byte[]> serializedEntriesBytes = nashDB.getRaw(prefix.getBytes(StandardCharsets.UTF_8));

                    if (serializedEntriesBytes.isPresent()) {
                        strategyLogger.trace("NashTemporalStrategy: Found data for prefix '{}'. Deserializing with PositionListSoA.", prefix);
                        PositionListSoA positionsSoA = PositionListSoA.deserializeWithFilters(serializedEntriesBytes.get(), context, requirements);
                        strategyLogger.trace("NashTemporalStrategy: Deserialized {} entries using PositionListSoA for prefix '{}'. Filtered size: {}.",
                                             PositionListSoA.getNumPositionsFromBlob(serializedEntriesBytes.get()), prefix, positionsSoA.getNumPositions());

                        if (positionsSoA.isEmpty()) {
                            strategyLogger.trace("NashTemporalStrategy: PositionsSoA for prefix '{}' is empty after context filtering.", prefix);
                            continue;
                        }

                        for (int i = 0; i < positionsSoA.getNumPositions(); i++) {
                            int dateId = positionsSoA.getSynonymIdAt(i); // dateId is stored in synonymId field

                            if (dateId >= 0 && dateId < idToDateLookup.size()) {
                                LocalDate entryDate = idToDateLookup.get(dateId);
                                boolean match = evaluateTemporalCondition(
                                    condition.temporalType(),
                                    entryDate.atStartOfDay(),
                                    entryDate.atTime(LocalTime.MAX),
                                    queryDateTimeStart.orElse(null),
                                    queryDateTimeEnd.orElse(null)
                                );

                                if (match) {
                                    strategyLogger.trace("NashTemporalStrategy: Match found for prefix '{}'. Date: {}, Position: Doc={}, Sent={}, Begin={}, End={}",
                                                         prefix, entryDate, positionsSoA.getDocIdAt(i),
                                                         (requirements.needsSentenceId ? positionsSoA.getSentenceIdAt(i) : -1),
                                                         (requirements.needsPositions ? positionsSoA.getBeginCharAt(i) : -1),
                                                         (requirements.needsPositions ? positionsSoA.getEndCharAt(i) : -1));

                                    uniqueMatches.add(new UniqueTemporalMatch(
                                        positionsSoA.getPositionAt(i),
                                        entryDate));
                                }
                            } else {
                                strategyLogger.warn("NashTemporalStrategy: Invalid dateId {} found for prefix '{}' at index {}. Max valid dateId is {}. Skipping entry.",
                                                    dateId, prefix, i, idToDateLookup.size() -1);
                            }
                        }
                    } else {
                         strategyLogger.trace("NashTemporalStrategy: No data found for Nash prefix: '{}'", prefix);
                    }
                }

                strategyLogger.debug("NashTemporalStrategy: Found {} unique temporal matches after processing all prefixes.", uniqueMatches.size());
                String effectiveVarName = (variableToBind != null && !variableToBind.isEmpty()) ? variableToBind : null;

                for (UniqueTemporalMatch match : uniqueMatches) {
                    resultSoA.add(
                        match.date(),
                        ValueType.DATE,
                        effectiveVarName,
                        match.position().getDocumentId(),
                        requirements.needsSentenceId ? match.position().getSentenceId() : -1,
                        requirements.needsPositions ? match.position().getBeginPosition() : -1,
                        requirements.needsPositions ? match.position().getEndPosition() : -1,
                        -1,
                        resultSoA.getNextConceptualRowId()
                    );
                }

            } catch (IOException | IndexAccessException e) {
                strategyLogger.error("NashTemporalStrategy: Error accessing Nash index or deserializing entries: {}", e.getMessage(), e);
                throw new QueryExecutionException("Failed to execute temporal condition with Nash strategy due to index access error: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
            } catch (Exception e) { // Catch broader exceptions from Nash or other logic
                strategyLogger.error("NashTemporalStrategy: Unexpected error during execution: {}", e.getMessage(), e);
                throw new QueryExecutionException("Unexpected error in Nash temporal strategy: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
            }

            // Sort results by document ID to ensure proper ordering for merge joins
            resultSoA.sort();
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

            LocalDate effectiveStart = null;
            LocalDate effectiveEnd = null;

            if (start != null && end != null) { // Explicit range or single day if start.isEqual(end)
                effectiveStart = start;
                effectiveEnd = end;
            } else if (start != null) { // Only start is provided
                 // For predicates like AFTER, AFTER_EQUAL, EQUAL when only start is given.
                 // For EQUAL, treat as single day. For AFTER, range is (start, future]. For AFTER_EQUAL, [start, future]
                 // For BEFORE, start is the reference date, so range is [GLOBAL_LOWER_BOUND, start-1]
                 switch (predicateType) {
                    case EQUAL:
                        effectiveStart = start;
                        effectiveEnd = start;
                        break;
                    case AFTER: // (start, GLOBAL_UPPER_BOUND]
                        effectiveStart = start.plusDays(1); // Exclusive start
                        effectiveEnd = Nash.GLOBAL_UPPER_BOUND; // Use Nash's global upper bound
                        break;
                    case AFTER_EQUAL: // [start, GLOBAL_UPPER_BOUND]
                        effectiveStart = start;
                        effectiveEnd = Nash.GLOBAL_UPPER_BOUND;
                        break;
                     case BEFORE: // [GLOBAL_LOWER_BOUND, start-1] - start is the reference date
                         effectiveStart = Nash.GLOBAL_LOWER_BOUND;
                         effectiveEnd = start.minusDays(1); // Exclusive of reference date
                         break;
                     case BEFORE_EQUAL: // [GLOBAL_LOWER_BOUND, start] - start is the reference date
                         effectiveStart = Nash.GLOBAL_LOWER_BOUND;
                         effectiveEnd = start; // Inclusive of reference date
                         break;
                    default: // INTERSECT, CONTAINS, etc. with only a start point is often a single day query.
                        effectiveStart = start;
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

            // Ensure start is not after end. If it is, the interval is empty.
            if (effectiveStart != null && effectiveEnd != null && effectiveStart.isAfter(effectiveEnd)) {
                strategyLogger.warn("convertToNashIntervalString: Effective start date {} is after effective end date {}. This results in an empty interval.", effectiveStart, effectiveEnd);
                return null; // An invalid/empty interval means no results.
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

    }

    /**
     * Strategy that directly scans the date index with range optimization.
     * Takes advantage of the ordered YYYYMMDD key format to efficiently iterate
     * only within the relevant date range instead of scanning the entire index.
     */
    private static class NaiveTemporalStrategy implements TemporalExecutionStrategy {
        private static final Logger strategyLogger = LoggerFactory.getLogger(NaiveTemporalStrategy.class);

        @Override
        public String getName() {
            return "naive";
        }

        @Override
        public QueryResultSoA execute(
            Temporal condition,
                Map<String, IndexAccessInterface> indexes,
                Query.Granularity granularity,
                int granularitySize,
                String corpusName,
                TemporalExecutor temporalExecutor,
                AttributeRequirements requirements,
                Optional<FilteringContext> context)
            throws QueryExecutionException {

            strategyLogger.debug(">>> Executing NaiveTemporalStrategy");
            strategyLogger.debug("Executing NaiveTemporalStrategy for condition: {}, ContextIsPresent: {}", condition, context.isPresent());
            QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
            int conceptualRowIdCounter = 0;

            IndexAccessInterface dateIndex = indexes.get(DATE_INDEX);
            if (dateIndex == null || !dateIndex.isOpen()) {
                strategyLogger.warn("Date index '{}' is not available or not open. Cannot execute temporal condition with naive strategy.", DATE_INDEX);
                return resultSoA;
            }

            String variableNameToBind = condition.variableName();
            Optional<LocalDateTime> queryStartDateTime = condition.startDate();
            Optional<LocalDateTime> queryEndDateTime = condition.endDate();
            TemporalPredicate type = condition.temporalType();

            // Calculate iteration range based on the temporal condition
            IterationRange range = calculateIterationRange(condition, type, queryStartDateTime, queryEndDateTime);

            try (RocksIterator iterator = dateIndex.iterateFromFirst()) {
                // Position iterator at the start of our range
                if (range.startKey() != null) {
                    iterator.seek(range.startKey().getBytes(StandardCharsets.UTF_8));
                    strategyLogger.debug("NaiveTemporalStrategy: Seeking to start key: {}", range.startKey());
                } else {
                    iterator.seekToFirst();
                    strategyLogger.debug("NaiveTemporalStrategy: No start key, seeking to first entry");
                }

                int keysProcessed = 0;
                int keysSkipped = 0;

                while (iterator.isValid()) {
                    String currentKey = new String(iterator.key(), StandardCharsets.UTF_8);

                    // Check if we've exceeded our range (endKey is exclusive)
                    if (range.endKey() != null && currentKey.compareTo(range.endKey()) >= 0) {
                        strategyLogger.debug("NaiveTemporalStrategy: Stopping scan. Key '{}' is at or beyond the exclusive end of range '{}'", currentKey, range.endKey());
                        break;
                    }

                    keysProcessed++;
                    byte[] valueBytes = iterator.value();
                    if (valueBytes == null || valueBytes.length == 0) {
                        iterator.next();
                        continue;
                    }

                    LocalDate entryDate;
                    try {
                        entryDate = TemporalExecutor.parseDateKey(currentKey);
                    } catch (DateTimeParseException e) {
                        strategyLogger.warn("Could not parse date from key '{}' in {} index. Skipping. Error: {}", currentKey, DATE_INDEX, e.getMessage());
                        iterator.next();
                        continue;
                    }

                    // Ensure date is within the supported range of the Nash index (1925-2025) for consistency
                    if (entryDate.isBefore(Nash.GLOBAL_LOWER_BOUND) || entryDate.isAfter(Nash.GLOBAL_UPPER_BOUND)) {
                        iterator.next();
                        continue;
                    }

                    boolean match = TemporalExecutor.evaluateTemporalCondition(
                        type,
                        entryDate.atStartOfDay(),
                        entryDate.atTime(LocalTime.MAX),
                        queryStartDateTime.orElse(null),
                        queryEndDateTime.orElse(null)
                    );

                    if (match) {
                        PositionListSoA positionList = PositionListSoA.deserializeWithFilters(valueBytes, context, requirements);
                        strategyLogger.trace("NaiveTemporalStrategy: Original blob for key '{}' indicated {} positions. Filtered size: {}.",
                                             currentKey, PositionListSoA.getNumPositionsFromBlob(valueBytes), positionList.getNumPositions());
                        if (positionList.isEmpty()){
                            iterator.next();
                            continue;
                        }

                        // Process matching entries
                        if (variableNameToBind != null) {
                            for (int i = 0; i < positionList.getNumPositions(); i++) {
                                Position pos = positionList.getPositionAt(i);
                                conceptualRowIdCounter = processEntryForVariableBinding(condition, resultSoA, requirements, granularity, granularitySize, conceptualRowIdCounter, entryDate, variableNameToBind, pos);
                            }
                        } else {
                            conceptualRowIdCounter = processEntryForDateLiteral(condition, resultSoA, requirements, granularity, granularitySize, conceptualRowIdCounter, entryDate, positionList);
                        }
                    } else {
                        keysSkipped++;
                    }

                    iterator.next();
                }

                strategyLogger.debug("NaiveTemporalStrategy: Processed {} keys, skipped {} non-matching keys within range", keysProcessed, keysSkipped);

            } catch (IOException | IndexAccessException e) {
                strategyLogger.error("Error during NaiveTemporalStrategy execution: {}", e.getMessage(), e);
                throw new QueryExecutionException("Error accessing date index or deserializing data.", e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
            }

            // Sort results by document ID to ensure proper ordering for merge joins
            resultSoA.sort();
            strategyLogger.debug("NaiveTemporalStrategy finished. QueryResultSoA size: {}.", resultSoA.size());
            return resultSoA;
        }

        /**
         * Calculates the optimal iteration range for the date index based on the temporal condition.
         * Converts dates to YYYYMMDD format keys and determines start/end bounds for efficient iteration.
         */
        private IterationRange calculateIterationRange(Temporal condition, TemporalPredicate type,
                                                     Optional<LocalDateTime> queryStart, Optional<LocalDateTime> queryEnd) {
            String startKey = null;
            String endKey = null;

            // Convert query dates to LocalDate for key generation
            LocalDate queryStartDate = queryStart.map(LocalDateTime::toLocalDate).orElse(null);
            LocalDate queryEndDate = queryEnd.map(LocalDateTime::toLocalDate).orElse(null);

            // For most predicates, we need to consider the query range
            switch (type) {
                case EQUAL:
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate);
                        endKey = formatDateKey(queryEndDate != null ? queryEndDate.plusDays(1) : queryStartDate.plusDays(1));
                    }
                    break;

                case INTERSECT, CONTAINS, CONTAINED_BY:
                    // For range predicates, iterate from start to end of query range
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate);
                    }
                    if (queryEndDate != null) {
                        endKey = formatDateKey(queryEndDate.plusDays(1));
                    }
                    break;

                case AFTER:
                    // For AFTER queries, start from the day after the reference date
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate.plusDays(1));
                        endKey = formatDateKey(Nash.GLOBAL_UPPER_BOUND);
                    }
                    // No end key - iterate to the end of the index
                    break;

                case AFTER_EQUAL:
                    // For AFTER_EQUAL, start from the reference date
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate);
                        endKey = formatDateKey(Nash.GLOBAL_UPPER_BOUND);
                    }
                    // No end key - iterate to the end of the index
                    break;

                case BEFORE:
                    // For BEFORE queries, iterate from beginning up to (but not including) the reference date
                    if (queryStartDate != null) {
                        startKey = formatDateKey(Nash.GLOBAL_LOWER_BOUND);
                        endKey = formatDateKey(queryStartDate.minusDays(1));
                    }
                    // No start key - iterate from the beginning of the index
                    break;

                case BEFORE_EQUAL:
                    // For BEFORE_EQUAL, iterate from beginning up to and including the reference date
                    if (queryStartDate != null) {
                        startKey = formatDateKey(Nash.GLOBAL_LOWER_BOUND);
                        endKey = formatDateKey(queryStartDate);
                    }
                    // No start key - iterate from the beginning of the index
                    break;

                case PROXIMITY:
                    // For proximity, use the same range as INTERSECT
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate);
                    }
                    if (queryEndDate != null) {
                        endKey = formatDateKey(queryEndDate.plusDays(1));
                    }
                    break;

                default:
                    strategyLogger.warn("Unknown temporal predicate type: {}. Using full index scan.", type);
                    // No start/end keys - full scan
                    break;
            }

            strategyLogger.debug("Calculated iteration range for predicate {}: startKey={}, endKey={}", type, startKey, endKey);
            return new IterationRange(startKey, endKey);
        }

        /**
         * Formats a LocalDate as a YYYYMMDD key string for the date index.
         */
        private String formatDateKey(LocalDate date) {
            return date.format(INDEX_DATE_FORMATTER);
        }


        private int processEntryForDateLiteral(Temporal condition, QueryResultSoA resultSoA, AttributeRequirements requirements,
                                  Query.Granularity granularity, int granularitySize, int currentConceptualRowId,
                                               LocalDate boundDate, PositionListSoA filteredPositions) {
            if (filteredPositions.isEmpty()) return currentConceptualRowId;
            int conceptualRowsAdded = 0;
            for (int i = 0; i < filteredPositions.getNumPositions(); i++) {
                resultSoA.add(
                    boundDate, // Value for the literal match
                    ValueType.DATE, // Type of the value
                    null, // No variable name for literal matches
                    filteredPositions.getDocIdAt(i),
                    requirements.needsSentenceId ? filteredPositions.getSentenceIdAt(i) : -1,
                    requirements.needsPositions ? filteredPositions.getBeginCharAt(i) : -1,
                    requirements.needsPositions ? filteredPositions.getEndCharAt(i) : -1,
                    -1, // Synonym ID not applicable here
                    resultSoA.getNextConceptualRowId()
                );
                conceptualRowsAdded++;
            }
            return currentConceptualRowId + conceptualRowsAdded;
        }

        private int processEntryForVariableBinding(Temporal condition, QueryResultSoA resultSoA, AttributeRequirements requirements,
                                                 Query.Granularity granularity, int granularitySize, int currentConceptualRowId,
                                                 LocalDate boundDate, String variableNameToBind, Position filteredPosition) {
            resultSoA.add(
                boundDate, ValueType.DATE, variableNameToBind,
                filteredPosition.getDocumentId(),
                requirements.needsSentenceId ? filteredPosition.getSentenceId() : -1,
                requirements.needsPositions ? filteredPosition.getBeginPosition() : -1,
                requirements.needsPositions ? filteredPosition.getEndPosition() : -1,
                -1, // Ensuring this is -1 as Position object has no getSynonymId and it's not relevant for ner_date entries here.
                resultSoA.getNextConceptualRowId()
            );
             // If a conceptual row was added, increment the strategy's counter.
             return currentConceptualRowId + 1;
        }
    }

    // =========================================================================
    // Static Helper Methods (used by strategies and potentially externally)
    // =========================================================================

    /**
     * Parses a date key using yyyyMMdd; returns null on failure.
     */
    public static LocalDate parseDateKey(String dateStr) {
        if (dateStr == null) return null;
        try {
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

    // Helper record for defining iteration range in NaiveTemporalStrategy
    private record IterationRange(String startKey, String endKey) {}

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