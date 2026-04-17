package com.example.query.executor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.TemporalBounds;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.condition.Temporal;

/**
 * Executor for temporal conditions in queries. Delegates execution to a selected strategy.
 * Returns QueryResult containing MatchDetail objects.
 */
public final class TemporalExecutor implements ConditionExecutor<Temporal> {
    private static final Logger logger = LoggerFactory.getLogger(TemporalExecutor.class);

    private static final String DATE_INDEX = "ner_date";

    // Formatter for parsing keys from the ner_date index (YYYYMMDD)
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

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
        registerStrategy(new NaiveTemporalStrategy());
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

            try (RocksIterator iterator = (range.startKey() != null || range.endKey() != null)
                    ? dateIndex.seekWithBounds(
                        (range.startKey() != null ? range.startKey() : "").getBytes(StandardCharsets.UTF_8),
                        (range.endKey() != null ? range.endKey() : null) != null ? range.endKey().getBytes(StandardCharsets.UTF_8) : null,
                        256 * 1024)
                    : dateIndex.iterateFromFirst()) {
                if (range.startKey() != null) {
                    strategyLogger.debug("NaiveTemporalStrategy: Bounded seek from: {} to < {}", range.startKey(), range.endKey());
                } else {
                    strategyLogger.debug("NaiveTemporalStrategy: Unbounded full scan (no start/end)");
                }

                int keysProcessed = 0;
                int keysSkipped = 0;

                while (iterator.isValid()) {
                    String currentKey = new String(iterator.key(), StandardCharsets.UTF_8);

                    // Check if we've exceeded our range (endKey is exclusive)
                    // iterateUpperBound will bound the iterator; keep check as safety when using unbounded iterator
                    if (range.endKey() != null && currentKey.compareTo(range.endKey()) >= 0) break;

                    keysProcessed++;
                    LocalDate entryDate;
                    try {
                        String baseDateKey = stripSegmentSuffix(currentKey);
                        entryDate = TemporalExecutor.parseDateKey(baseDateKey);
                    } catch (DateTimeParseException e) {
                        strategyLogger.warn("Could not parse date from key '{}' in {} index. Skipping. Error: {}", currentKey, DATE_INDEX, e.getMessage());
                        iterator.next();
                        continue;
                    }

                    // Ensure date is within the globally supported temporal range (1925-2025)
                    if (entryDate.isBefore(TemporalBounds.LOWER) || entryDate.isAfter(TemporalBounds.UPPER)) {
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
                        java.util.Optional<com.example.core.PositionListSoA> mergedOpt;
                        try {
                            mergedOpt = dateIndex.getMergedPositions(stripSegmentSuffix(currentKey), context, requirements);
                        } catch (IndexAccessException iae) {
                            throw iae;
                        } catch (Exception e) {
                            throw new QueryExecutionException("Error accessing date index during merged read: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
                        }
                        if (mergedOpt.isEmpty() || mergedOpt.get().isEmpty()) {
                            iterator.next();
                            continue;
                        }
                        PositionListSoA positionList = mergedOpt.get();
                        strategyLogger.trace("NaiveTemporalStrategy: Original blob for key '{}' indicated {} positions. Filtered size: {}.",
                                             currentKey, positionList.getNumPositions(), positionList.getNumPositions());
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

            } catch (IndexAccessException e) {
                strategyLogger.error("Error during NaiveTemporalStrategy execution: {}", e.getMessage(), e);
                throw new QueryExecutionException("Error accessing date index or deserializing data.", e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
            }

            // Centralized sorting handled by QueryExecutor
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
                        endKey = formatDateKey(TemporalBounds.UPPER);
                    }
                    // No end key - iterate to the end of the index
                    break;

                case AFTER_EQUAL:
                    // For AFTER_EQUAL, start from the reference date
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate);
                        endKey = formatDateKey(TemporalBounds.UPPER);
                    }
                    // No end key - iterate to the end of the index
                    break;

                case BEFORE:
                    // For BEFORE queries, iterate from beginning up to (but not including) the reference date
                    if (queryStartDate != null) {
                        startKey = formatDateKey(TemporalBounds.LOWER);
                        endKey = formatDateKey(queryStartDate.minusDays(1));
                    }
                    // No start key - iterate from the beginning of the index
                    break;

                case BEFORE_EQUAL:
                    // For BEFORE_EQUAL, iterate from beginning up to and including the reference date
                    if (queryStartDate != null) {
                        startKey = formatDateKey(TemporalBounds.LOWER);
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

        private String stripSegmentSuffix(String key) {
            int hashPos = key.lastIndexOf('#');
            if (hashPos <= 0 || hashPos == key.length() - 1) return key;
            for (int i = hashPos + 1; i < key.length(); i++) {
                char c = key.charAt(i);
                if (c < '0' || c > '9') return key;
            }
            return key.substring(0, hashPos);
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
             case PROXIMITY ->
                 // PROXIMITY currently collapses to INTERSECT semantics.
                 queryStart != null && queryEnd != null && !queryStart.isAfter(docDateTimeEnd) && !queryEnd.isBefore(docDateTimeStart);
             default -> {
                 logger.warn("Unsupported TemporalPredicate type encountered in evaluation: {}. Returning false.", type);
                 yield false;
             }
         };
     }

    private record IterationRange(String startKey, String endKey) {}
}