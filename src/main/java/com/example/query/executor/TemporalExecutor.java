package com.example.query.executor;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.AbstractMap.SimpleEntry;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
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
                return resultSoA; // Return empty if NashDB is not usable
            }

            String variableToMatch = condition.variableName();
            if (variableToMatch == null) {
                strategyLogger.warn("No variable specified in Temporal condition for Nash strategy. Cannot determine target value. Condition: {}", condition);
                return resultSoA; // Cannot proceed without a variable to match date ranges against.
            }

            // If query specifies specific start/end dates, use them to filter Nash results further.
            Optional<LocalDateTime> queryDateTimeStart = condition.startDate();
            Optional<LocalDateTime> queryDateTimeEnd = condition.endDate();

            try {
                // Simplified: Iterate all Nash entries and filter by predicate and then by variable value.
                // This is NOT how Nash should be used optimally but serves as a temporary bridge.
                int conceptualRowIdCounter = 0;

                try (RocksIterator nashIterator = nashDB.iterateFromFirst()) { // Changed DBIterator to RocksIterator
                    while (nashIterator.isValid()) { // Changed from hasNext() to isValid()
                        byte[] nashKeyBytes = nashIterator.key(); // Get key
                        byte[] nashValueBytes = nashIterator.value(); // Get value
                        // String nashKey = new String(nashKeyBytes, java.nio.charset.StandardCharsets.UTF_8); // if needed for parsing

                        // Placeholder for Nash specific logic.
                        // For this migration, the internal loop for dateIndex demonstrates RocksIterator usage.

                        IndexAccessInterface dateIndex = indexes.get(DATE_INDEX);
                        if (dateIndex != null && dateIndex.isOpen()) {
                            try (RocksIterator dateIterator = dateIndex.iterateFromFirst()) { // Changed DBIterator to RocksIterator
                                while (dateIterator.isValid()) { // Changed from hasNext() to isValid()
                                    byte[] keyBytes = dateIterator.key();
                                    byte[] valueBytes = dateIterator.value();
                                    String key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
                                    String[] keyParts = key.split(String.valueOf(IndexAccessInterface.DELIMITER));

                                    if (keyParts.length == 2) {
                                        String entityType = keyParts[0];
                                        String dateStr = keyParts[1];

                                        boolean variableValueMatch = false;
                                        if (("DATE".equals(entityType) || variableToMatch.equalsIgnoreCase(dateStr) || variableToMatch.contains(dateStr))) {
                                            variableValueMatch = true;
                                        }

                                        if (variableValueMatch) {
                                            LocalDate entryDate = parseDateKey(dateStr);
                                            if (entryDate != null) {
                                                boolean temporalMatch = evaluateTemporalCondition(
                                                    condition.temporalType(),
                                                    entryDate.atStartOfDay(), entryDate.atTime(LocalTime.MAX),
                                                    queryDateTimeStart.orElse(null), queryDateTimeEnd.orElse(null)
                                                );

                                                if (temporalMatch) {
                                                    PositionListSoA positions = PositionListSoA.deserializeFromCompositeBlob(valueBytes);
                                                    for (int i = 0; i < positions.getNumPositions(); i++) {
                                                        resultSoA.add(
                                                            entryDate,
                                                            ValueType.DATE,
                                                            condition.variableName(),
                                                            positions.getDocIdAt(i),
                                                            requirements.needsSentenceId ? positions.getSentenceIdAt(i) : -1,
                                                            requirements.needsPositions ? positions.getBeginCharAt(i) : -1,
                                                            requirements.needsPositions ? positions.getEndCharAt(i) : -1,
                                                            requirements.needsSynonymIds ? positions.getSynonymIdAt(i) : -1,
                                                            conceptualRowIdCounter++
                                                        );
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    dateIterator.next(); // Advance dateIterator
                                }
                            }
                        }
                        nashIterator.next(); // Advance nashIterator
                    }
                }
            } catch (Exception e) {
                strategyLogger.error("Error executing NashTemporalStrategy: {}", e.getMessage(), e);
                throw new QueryExecutionException("Failed to execute temporal condition with Nash strategy: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INTERNAL_ERROR);
            }
            strategyLogger.debug("NashTemporalStrategy execution finished, {} results.", resultSoA.size());
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
            strategyLogger.debug("Executing NaiveTemporalStrategy for condition: {}", condition);
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

            strategyLogger.debug("Naive strategy: queryStart={}, queryEnd={}, variable={}", queryDateTimeStart, queryDateTimeEnd, variableNameToBind);

            try (RocksIterator iterator = dateIndex.iterateFromFirst()) { // Changed DBIterator to RocksIterator
                while (iterator.isValid()) { // Changed from hasNext() to isValid()
                    byte[] keyBytes = iterator.key(); // Get key
                    byte[] valueBytes = iterator.value(); // Get value
                    String key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
                    String[] keyParts = key.split(String.valueOf(IndexAccessInterface.DELIMITER));

                    if (keyParts.length == 2 && "DATE".equals(keyParts[0])) {
                        String dateStr = keyParts[1];
                        LocalDate entryDate = parseDateKey(dateStr);

                        if (entryDate != null) {
                            boolean temporalMatch = evaluateTemporalCondition(
                                condition.temporalType(),
                                entryDate.atStartOfDay(), entryDate.atTime(LocalTime.MAX),
                                queryDateTimeStart.orElse(null), queryDateTimeEnd.orElse(null)
                            );

                            if (temporalMatch) {
                                Object valueToBindInSoA = entryDate;

                                conceptualRowIdCounter = processEntry(
                                    new SimpleEntry<>(keyBytes, valueBytes),
                                    condition, resultSoA, requirements,
                                    granularity, granularitySize, conceptualRowIdCounter, entryDate, variableNameToBind, valueToBindInSoA
                                );
                            }
                        }
                    }
                    iterator.next(); // Advance iterator
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
            strategyLogger.debug("NaiveTemporalStrategy execution finished, {} conceptual rows.", resultSoA.getConceptualRowCount());
            return resultSoA;
        }

        private int processEntry(Map.Entry<byte[], byte[]> entry, Temporal condition,
                                  QueryResultSoA resultSoA, AttributeRequirements requirements,
                                  Query.Granularity granularity, int granularitySize, int currentConceptualRowId,
                                  LocalDate boundDate, String variableNameToBind, Object valueToBind)
                                  throws IOException {
            PositionListSoA positions = PositionListSoA.deserializeFromCompositeBlob(entry.getValue());
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