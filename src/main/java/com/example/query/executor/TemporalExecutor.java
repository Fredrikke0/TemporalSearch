package com.example.query.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.iq80.leveldb.DBIterator;
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

import it.unimi.dsi.fastutil.ints.IntArrayList;
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

            IndexAccessInterface nashIndex = indexes.get(NASH_INDEX);
            if (nashIndex == null) {
                throw new QueryExecutionException("Missing required Nash index for corpus: " + corpusName, condition.toString(), QueryExecutionException.ErrorType.MISSING_INDEX);
            }

            List<LocalDate> dateLookup;
            try {
                Optional<byte[]> lookupBytes = nashIndex.getRaw(NashSerializationUtils.DATE_LOOKUP_KEY);
                if (lookupBytes.isEmpty()) {
                    throw new QueryExecutionException("Nash date lookup table not found in index.", condition.toString(), QueryExecutionException.ErrorType.MISSING_INDEX_DATA);
                }
                dateLookup = NashSerializationUtils.deserializeDateLookup(lookupBytes.get());
            } catch (IOException | IndexAccessException e) {
                throw new QueryExecutionException("Failed to load Nash date lookup table.", e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
            }

            QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
            int conceptualRowIdCounter = 0;
            // Use a Set to track unique (Position, LocalDate) combinations already added to avoid duplicates from multiple prefixes
            Set<UniqueTemporalMatch> addedMatches = new HashSet<>();

            Optional<String> nashIntervalOpt = condition.toNashInterval();
            if (nashIntervalOpt.isEmpty()) {
                strategyLogger.warn("Could not convert temporal condition to a Nash interval: {}. Returning empty result.", condition);
                return resultSoA; // Return empty SoA
            }
            String nashInterval = nashIntervalOpt.get();
            strategyLogger.debug("NashTemporalStrategy: Executing for Nash interval: {} with original condition type: {}", nashInterval, condition.temporalType());

            // Determine the correct Nash.RangePredicate based on the original TemporalPredicate
            Nash.RangePredicate nashQueryPredicate = mapToNashRangePredicate(condition.temporalType());
            strategyLogger.debug("Mapped TemporalPredicate {} to Nash.RangePredicate {}", condition.temporalType(), nashQueryPredicate);

            // Use Nash.generateTimeHash to get the Z-order prefixes for the query interval
            // Log the exact inputs to Nash.generateTimeHash
            strategyLogger.info("DEBUG NashTemporalStrategy: Calling Nash.generateTimeHash with interval: '{}', predicate: {}", nashInterval, nashQueryPredicate);
            String[] queryHashPrefixes = Nash.generateTimeHash(nashInterval, nashQueryPredicate);
            strategyLogger.debug("Generated {} query hash prefixes for interval '{}' with predicate {}", queryHashPrefixes.length, nashInterval, nashQueryPredicate);

            // ---- DEBUG LOGGING: Query NASH Prefixes ----
            if (queryHashPrefixes.length > 0) {
                strategyLogger.info("DEBUG NashTemporalStrategy: Query Prefixes for interval '{}' (Predicate: {}):", nashInterval, nashQueryPredicate);
                // for (String queryPrefix : queryHashPrefixes) {
                //     strategyLogger.info("  Query Prefix: " + queryPrefix);
                // }
            } else {
                strategyLogger.info("DEBUG NashTemporalStrategy: No query prefixes generated for interval '{}' (Predicate: {}).", nashInterval, nashQueryPredicate);
            }
            // ---- END DEBUG LOGGING ----

            for (String prefix : queryHashPrefixes) {
                try {
                    Optional<byte[]> data = nashIndex.getRaw(prefix.getBytes(StandardCharsets.UTF_8));
                    if (data.isPresent()) {
                        List<NashDateEntryWithId> entries = NashSerializationUtils.deserializeNashEntries(data.get());
                        // Log retrieved entries for a specific query prefix if needed (can be verbose)
                        // strategyLogger.info("DEBUG NashTemporalStrategy: For query prefix '{}', retrieved {} entries.", prefix, entries.size());
                        for (NashDateEntryWithId entry : entries) {
                            Position pos = entry.position();
                            LocalDate entryDate = dateLookup.get(entry.dateId());
                            Object valueToBind = entryDate;

                            // Perform precise check against original condition
                            boolean preciseMatch = false;
                            if (condition.startDate().isPresent() && condition.endDate().isPresent()) {
                                // Case 1: Condition has a start and end date (e.g. CONTAINS, INTERSECT range)
                                preciseMatch = TemporalExecutor.evaluateTemporalCondition(
                                    condition.temporalType(),
                                    entryDate.atStartOfDay(), entryDate.atTime(23,59,59), // Document is a single day interval
                                    condition.startDate().get(),
                                    condition.endDate().get()
                                );
                            } else if (condition.startDate().isPresent()) {
                                // Case 2: Condition has only a start date (e.g. EQUAL, AFTER, BEFORE single date)
                                // For EQUAL, evaluateTemporalCondition expects both doc start/end and query start/end.
                                // We treat the query as a single day interval here for comparison predicates.
                                LocalDateTime queryDateTimeStart = condition.startDate().get();
                                LocalDateTime queryDateTimeEnd = condition.temporalType() == TemporalPredicate.EQUAL
                                                              ? condition.startDate().get().toLocalDate().atTime(23,59,59)
                                                              : queryDateTimeStart; // For AFTER/BEFORE, end time is not used by evaluate for point checks.

                                preciseMatch = TemporalExecutor.evaluateTemporalCondition(
                                    condition.temporalType(),
                                    entryDate.atStartOfDay(), entryDate.atTime(23,59,59),
                                    queryDateTimeStart,
                                    queryDateTimeEnd
                                );
                            } else {
                                // Should not happen if toNashInterval() succeeded, implies a condition structure not handled here
                                strategyLogger.warn("Cannot perform precise check for NASH entry due to unexpected Temporal condition structure: {}", condition);
                                // As a fallback, consider it a match if NASH brought it up, though this might be too lenient.
                                // preciseMatch = true; // Or log an error and skip.
                            }

                            if (preciseMatch) {
                                UniqueTemporalMatch currentMatch = new UniqueTemporalMatch(pos, entryDate);
                                if (addedMatches.add(currentMatch)) {
                                    resultSoA.add(
                                        valueToBind,
                                        ValueType.DATE,
                                        condition.qualifiedVariableName().orElse(null),
                                        pos.getDocumentId(),
                                        requirements.needsSentenceId ? pos.getSentenceId() : -1,
                                        requirements.needsPositions ? pos.getBeginPosition() : -1,
                                        requirements.needsPositions ? pos.getEndPosition() : -1,
                                        -1,
                                        conceptualRowIdCounter++
                                    );
                                    // Log when a precise match is added
                                    strategyLogger.info("DEBUG NashTemporalStrategy: Added precise match: DocID={}, Date={}, Pos={}, ConditionType={}, QueryInterval='{}'",
                                                        pos.getDocumentId(), entryDate, pos, condition.temporalType(), nashInterval);
                                } else {
                                    strategyLogger.trace("Skipping duplicate (already precise-checked) temporal match via different prefix: {}", currentMatch);
                                }
                            } else {
                                // Log when a NASH candidate fails the precise check
                                strategyLogger.info("DEBUG NashTemporalStrategy: NASH candidate failed precise check: DocID={}, Date={}, Pos={}, ConditionType={}, QueryInterval='{}'",
                                                    pos.getDocumentId(), entryDate, pos, condition.temporalType(), nashInterval);
                                strategyLogger.trace("NASH candidate date {} (DocID {}) did not pass precise check for condition: {}. Skipping.",
                                                    entryDate, pos.getDocumentId(), condition);
                            }
                        }
                    } else {
                        // Log if a query prefix finds no data in the index
                        // strategyLogger.info("DEBUG NashTemporalStrategy: Query prefix '{}' found no data in NASH index.", prefix);
                    }
                } catch (IOException e) {
                    strategyLogger.error("IOException while processing Nash prefix '{}': {}", prefix, e.getMessage(), e);
                } catch (IndexAccessException e) {
                     strategyLogger.error("IndexAccessException while processing Nash prefix '{}': {}", prefix, e.getMessage(), e);
                     throw new QueryExecutionException("Failed to access Nash index for prefix: " + prefix, e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
                }
            }
            strategyLogger.debug("NashTemporalStrategy: Found {} entries. Returning QueryResultSoA.", resultSoA.size());
            return resultSoA;
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

            IndexAccessInterface dateIndex = indexes.get(DATE_INDEX);
            if (dateIndex == null) {
                throw new QueryExecutionException("Missing required ner_date index for corpus: " + corpusName, condition.toString(), QueryExecutionException.ErrorType.MISSING_INDEX);
            }

            QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);
            int conceptualRowIdCounter = 0;

            LocalDate queryStart = condition.startDate().map(LocalDateTime::toLocalDate).orElse(null);
            LocalDate queryEnd = condition.endDate().map(LocalDateTime::toLocalDate).orElse(null); // Used by evaluateTemporalCondition for ranges
            TemporalPredicate type = condition.temporalType();

            strategyLogger.debug("NaiveTemporalStrategy: Executing for condition: type={}, start={}, end={}", type, queryStart, queryEnd);

            DBIterator iterator = null;
            boolean iterateBackwards = false;

            try {
                if (queryStart != null) {
                    String seekKeyStr;
                    byte[] seekKeyBytes;

                    if (type == TemporalPredicate.BEFORE) {
                        seekKeyStr = queryStart.format(INDEX_DATE_FORMATTER);
                        seekKeyBytes = seekKeyStr.getBytes(StandardCharsets.UTF_8);
                        iterator = dateIndex.seek(seekKeyBytes);
                        iterateBackwards = true;
                        strategyLogger.debug("NaiveTemporalStrategy: Configured for BACKWARD iteration for BEFORE {}", queryStart);
                    } else if (type == TemporalPredicate.BEFORE_EQUAL) {
                        // Seek to day AFTER queryStart. The loop's iter.prev() will then start from queryStart.
                        LocalDate seekTargetDate = queryStart.plusDays(1);
                        seekKeyStr = seekTargetDate.format(INDEX_DATE_FORMATTER);
                        seekKeyBytes = seekKeyStr.getBytes(StandardCharsets.UTF_8);
                        iterator = dateIndex.seek(seekKeyBytes);
                        iterateBackwards = true;
                        strategyLogger.debug("NaiveTemporalStrategy: Configured for BACKWARD iteration for BEFORE_EQUAL {}", queryStart);
                    } else if (type == TemporalPredicate.AFTER || type == TemporalPredicate.AFTER_EQUAL || type == TemporalPredicate.EQUAL ||
                               type == TemporalPredicate.CONTAINS || type == TemporalPredicate.INTERSECT || type == TemporalPredicate.CONTAINED_BY) {
                        seekKeyStr = queryStart.format(INDEX_DATE_FORMATTER);
                        seekKeyBytes = seekKeyStr.getBytes(StandardCharsets.UTF_8);
                        iterator = dateIndex.seek(seekKeyBytes);
                        iterateBackwards = false;
                        strategyLogger.debug("NaiveTemporalStrategy: Configured for FORWARD iteration from seek key {} for type {}", seekKeyStr, type);
                    } else { // Other types with queryStart not suited for specific seek strategy
                        iterator = dateIndex.iterateFromFirst();
                        iterateBackwards = false;
                        strategyLogger.debug("NaiveTemporalStrategy: Configured for FORWARD iteration from start (unhandled specific seek for type {} with queryStart)", type);
                    }
                } else { // queryStart is null (e.g., unbounded BEFORE/AFTER or error)
                    iterator = dateIndex.iterateFromFirst();
                    iterateBackwards = false;
                    strategyLogger.debug("NaiveTemporalStrategy: Configured for FORWARD iteration from start (queryStart is null)");
                }

                if (iterator == null) { // Should not happen if logic above is complete
                    throw new QueryExecutionException("Iterator could not be initialized", condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
                }

                try (DBIterator iter = iterator) { // Manage the lifecycle of the obtained iterator
                    if (iterateBackwards) {
                        strategyLogger.debug("NaiveTemporalStrategy: Starting BACKWARD scan.");
                        while (iter.hasPrev()) {
                            Entry<byte[], byte[]> entry = iter.prev();
                            processEntry(entry, condition, resultSoA, requirements, granularity, granularitySize, conceptualRowIdCounter++);
                        }
                    } else { // Iterate forwards
                        strategyLogger.debug("NaiveTemporalStrategy: Starting FORWARD scan.");
                        while (iter.hasNext()) {
                            Entry<byte[], byte[]> entry = iter.next();
                            String key = new String(entry.getKey(), StandardCharsets.UTF_8);
                            LocalDate docDate = TemporalExecutor.parseDateKey(key);
                            if (docDate == null) continue;

                            // Early exit conditions for FORWARD scan
                            if (queryStart != null) { // queryStart is from condition.startDate()
                                if (type == TemporalPredicate.BEFORE && (docDate.isEqual(queryStart) || docDate.isAfter(queryStart))) {
                                    strategyLogger.trace("NaiveTemporalStrategy (forward): Early exit for BEFORE: docDate {} >= queryStart {}", docDate, queryStart);
                                    break;
                                }
                                // For BEFORE_EQUAL X, X is queryStart. If docDate > X, exit.
                                if (type == TemporalPredicate.BEFORE_EQUAL && docDate.isAfter(queryStart)) {
                                     strategyLogger.trace("NaiveTemporalStrategy (forward): Early exit for BEFORE_EQUAL: docDate {} > queryStart {}", docDate, queryStart);
                                     break;
                                }
                                // For EQUAL X (single date), if docDate > X, can stop.
                                // queryEnd here is the original condition.endDate().
                                if (type == TemporalPredicate.EQUAL && queryEnd == null && docDate.isAfter(queryStart)) {
                                    strategyLogger.trace("NaiveTemporalStrategy (forward): Early exit for EQUAL (single date): docDate {} > queryDate {}", docDate, queryStart);
                                    break;
                                }
                            }

                            // Early exit for range queries if docDate exceeds queryEnd
                            if (queryEnd != null) { // queryEnd is from condition.endDate()
                                boolean canEarlyExit = type == TemporalPredicate.CONTAINS ||
                                                       type == TemporalPredicate.INTERSECT ||
                                                       type == TemporalPredicate.CONTAINED_BY ||
                                                       (type == TemporalPredicate.EQUAL && queryStart != null); // EQUAL can define a range

                                if (canEarlyExit && docDate.isAfter(queryEnd)) {
                                    strategyLogger.trace("NaiveTemporalStrategy (forward): Early exit for range query type {}: docDate {} > queryEnd {}", type, docDate, queryEnd);
                                    break;
                                }
                            }

                            processEntry(entry, condition, resultSoA, requirements, granularity, granularitySize, conceptualRowIdCounter++);
                        }
                    }
                } // end try-with-resources for iterator
            } catch (IOException e) {
                throw new QueryExecutionException("Failed to read from date index", e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
            } catch (IndexAccessException e) {
                throw new QueryExecutionException("Failed to access date index", e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
            }

            strategyLogger.debug("NaiveTemporalStrategy: Found {} entries. Returning QueryResultSoA.", resultSoA.size());
            return resultSoA;
        }

        // Helper method to process a single entry, used by both forward and backward iteration.
        private void processEntry(Entry<byte[], byte[]> entry, Temporal condition,
                                  QueryResultSoA resultSoA, AttributeRequirements requirements,
                                  Query.Granularity granularity, int granularitySize, int conceptualRowId)
                                  throws IOException {

            String key = new String(entry.getKey(), StandardCharsets.UTF_8);
            LocalDate docDate = TemporalExecutor.parseDateKey(key);
            if (docDate == null) {
                return;
            }

            LocalDate queryStartLocalDate = condition.startDate().map(LocalDateTime::toLocalDate).orElse(null);
            LocalDate queryEndLocalDate = condition.endDate().map(LocalDateTime::toLocalDate).orElse(null); // This is the original queryEnd

            LocalDateTime evalQueryStartDateTime = condition.startDate().orElse(null);
            LocalDateTime evalQueryEndDateTime = condition.endDate().orElse(null);


            // Adjustments for single-point date predicates for evaluateTemporalCondition
            if (condition.temporalType() == TemporalPredicate.EQUAL && evalQueryStartDateTime != null && evalQueryEndDateTime == null) {
                evalQueryEndDateTime = evalQueryStartDateTime.toLocalDate().atTime(23, 59, 59);
            } else if (condition.temporalType() == TemporalPredicate.BEFORE_EQUAL && evalQueryStartDateTime != null && evalQueryEndDateTime == null) {
                // For DATE(<= X), X is from condition.startDate(). evaluateTemporalCondition expects X as its queryEnd parameter.
                evalQueryEndDateTime = evalQueryStartDateTime.toLocalDate().atTime(23,59,59);
            } else if (condition.temporalType() == TemporalPredicate.AFTER_EQUAL && evalQueryStartDateTime != null && evalQueryEndDateTime == null) {
                 // For DATE(>= X), X is from condition.startDate(). evaluateTemporalCondition expects X as its queryStart.
                 // No change needed to evalQueryEndDateTime, it's fine if null.
            }


            // Validation based on the type, ensuring necessary dates are present for evaluation.
            // This logic was previously inside the loop, now centralized in processEntry.
            boolean skipEvaluation = false;
            String skipReason = "";
            switch (condition.temporalType()) {
                case CONTAINS:
                case CONTAINED_BY:
                case INTERSECT:
                case PROXIMITY: // Proximity implies a range
                    if (evalQueryStartDateTime == null || evalQueryEndDateTime == null) {
                        skipEvaluation = true;
                        skipReason = "requires both query start and end dates.";
                    }
                    break;
                case BEFORE: // e.g., DATE(< X), X is evalQueryStartDateTime
                case AFTER:  // e.g., DATE(> X), X is evalQueryStartDateTime
                case AFTER_EQUAL: // e.g., DATE(>= X), X is evalQueryStartDateTime
                case EQUAL: // e.g., DATE(= X), X is evalQueryStartDateTime
                    if (evalQueryStartDateTime == null) {
                        skipEvaluation = true;
                        skipReason = "requires a query start date.";
                    }
                    break;
                case BEFORE_EQUAL: // e.g., DATE(<= X), X is evalQueryStartDateTime, but evaluation uses it as evalQueryEndDateTime
                    if (evalQueryStartDateTime == null) { // The original date boundary is from queryStart
                        skipEvaluation = true;
                        skipReason = "requires a query start date (for the boundary X).";
                    }
                    break;
                default:
                    strategyLogger.warn("Unhandled TemporalPredicate type {} in date validation logic.", condition.temporalType());
                    skipEvaluation = true;
                    skipReason = "is an unhandled predicate type in validation.";
                    break;
            }

            if (skipEvaluation) {
                strategyLogger.trace("NaiveTemporalStrategy: Skipping evaluation for docDate {} (key {}): type {} {}.", docDate, key, condition.temporalType(), skipReason);
                return;
            }


            boolean conditionMet = TemporalExecutor.evaluateTemporalCondition(
                condition.temporalType(),
                docDate.atStartOfDay(), docDate.atTime(23, 59, 59),
                evalQueryStartDateTime,
                evalQueryEndDateTime
            );

            if (conditionMet) {
                byte[] rawBlob = entry.getValue();
                if (rawBlob == null) {
                    strategyLogger.warn("NaiveTemporalStrategy: Null data blob for key '{}', skipping.", key);
                    return;
                }
                int numPositions = PositionListSoA.getNumPositionsFromBlob(rawBlob);
                if (numPositions == 0) {
                    return;
                }

                IntArrayList docIds = PositionListSoA.decompressDocIds(rawBlob);
                IntArrayList sentIds = requirements.needsSentenceId ? PositionListSoA.decompressSentenceIds(rawBlob) : null;
                IntArrayList beginChars = requirements.needsPositions ? PositionListSoA.decompressBeginChars(rawBlob) : null;
                IntArrayList endChars = requirements.needsPositions ? PositionListSoA.decompressEndChars(rawBlob) : null;

                Object valueToBind = docDate;
                for (int i = 0; i < numPositions; i++) {
                    resultSoA.add(
                        valueToBind,
                        ValueType.DATE,
                        condition.qualifiedVariableName().orElse(null),
                        docIds.getInt(i),
                        sentIds != null ? sentIds.getInt(i) : -1,
                        beginChars != null ? beginChars.getInt(i) : -1,
                        endChars != null ? endChars.getInt(i) : -1,
                        -1, // score, not applicable here
                        conceptualRowId // Use the passed-in conceptualRowId
                    );
                }
                 strategyLogger.trace("NaiveTemporalStrategy: Added {} positions for docDate {} (key {}) meeting condition {}", numPositions, docDate, key, condition.temporalType());
            } else {
                strategyLogger.trace("NaiveTemporalStrategy: docDate {} (key {}) did NOT meet condition {} [qStart: {}, qEnd: {}]", docDate, key, condition.temporalType(), evalQueryStartDateTime, evalQueryEndDateTime);
            }
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