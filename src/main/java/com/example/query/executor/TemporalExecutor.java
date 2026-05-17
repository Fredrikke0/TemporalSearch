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
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.TemporalBounds;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.condition.Temporal;

/**
 * Executor for temporal conditions using the CellResult-based interface.
 * Delegates execution to a selected strategy.
 */
public final class TemporalExecutor implements ConditionExecutor<Temporal> {
    private static final Logger logger = LoggerFactory.getLogger(TemporalExecutor.class);

    private static final String DATE_INDEX = "ner_date";

    // Formatter for parsing keys from the ner_date index (YYYYMMDD)
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Map<String, TemporalExecutionStrategy> strategies = new HashMap<>();
    private String activeStrategyName = "naive"; // Default to naive strategy

    /**
     * Strategy interface for temporal condition execution.
     */
    interface TemporalExecutionStrategy {
        String getName();

        CellResult execute(
                Temporal condition,
                Map<String, IndexAccessInterface> indexes,
                Query.Granularity granularity,
                int granularitySize,
                String corpusName,
                AttributeRequirements requirements,
                Optional<Roaring64NavigableMap> allowedCells) throws QueryExecutionException;
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
     */
    public String getActiveStrategyName() {
        return activeStrategyName;
    }

    /**
     * Gets the currently active strategy implementation.
     */
    private TemporalExecutionStrategy getActiveStrategy() {
        if (activeStrategyName == null) {
            throw new IllegalStateException("No active temporal strategy set.");
        }
        TemporalExecutionStrategy strategy = strategies.get(activeStrategyName);
        if (strategy == null) {
            throw new IllegalStateException(
                    "Active strategy '" + activeStrategyName + "' not found in registered strategies.");
        }
        return strategy;
    }

    @Override
    public CellResult execute(Temporal condition, Map<String, IndexAccessInterface> indexes,
            Query.Granularity granularity,
            int granularitySize,
            String corpusName,
            AttributeRequirements requirements,
            Optional<Roaring64NavigableMap> allowedCells)
            throws QueryExecutionException {

        logger.debug(
                ">>> Executing TemporalExecutor (delegating to strategy: {}, granularity={}, allowedCellsPresent={})",
                activeStrategyName, granularity, allowedCells.isPresent());

        TemporalExecutionStrategy strategy = getActiveStrategy();
        return strategy.execute(condition, indexes, granularity, granularitySize,
                corpusName, requirements, allowedCells);
    }

    // =========================================================================
    // Static Helper Methods
    // =========================================================================

    /**
     * Parses a date key using yyyyMMdd; returns null on failure.
     */
    public static LocalDate parseDateKey(String dateStr) {
        if (dateStr == null)
            return null;
        try {
            return LocalDate.parse(dateStr.trim(), INDEX_DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            logger.trace("Failed to parse date key '{}' using format yyyyMMdd: {}", dateStr, e.getMessage());
            return null;
        }
    }

    /**
     * Evaluates if a document date satisfies the temporal condition.
     */
    public static boolean evaluateTemporalCondition(TemporalPredicate type,
            LocalDateTime docDateTimeStart, LocalDateTime docDateTimeEnd,
            LocalDateTime queryStart, LocalDateTime queryEnd) {
        // Ensure query interval is valid (start <= end)
        if (queryStart != null && queryEnd != null && queryStart.isAfter(queryEnd)) {
            logger.warn("Invalid query interval: queryStart ({}) is after queryEnd ({}). Returning false.",
                    queryStart, queryEnd);
            return false;
        }
        // Ensure doc interval is valid
        if (docDateTimeStart.isAfter(docDateTimeEnd)) {
            logger.warn("Invalid document interval: docStart ({}) is after docEnd ({}). Returning false.",
                    docDateTimeStart, docDateTimeEnd);
            return false;
        }

        return switch (type) {
            case CONTAINS -> queryStart != null && queryEnd != null
                    && !queryStart.isAfter(docDateTimeStart) && !queryEnd.isBefore(docDateTimeEnd);
            case CONTAINED_BY -> queryStart != null && queryEnd != null
                    && !docDateTimeStart.isAfter(queryStart) && !docDateTimeEnd.isBefore(queryEnd);
            case INTERSECT -> queryStart != null && queryEnd != null
                    && !queryStart.isAfter(docDateTimeEnd) && !queryEnd.isBefore(docDateTimeStart);
            case BEFORE -> queryStart != null && docDateTimeEnd.isBefore(queryStart);
            case AFTER -> queryStart != null && docDateTimeStart.isAfter(queryStart);
            case BEFORE_EQUAL -> queryEnd != null && !docDateTimeStart.isAfter(queryEnd);
            case AFTER_EQUAL -> queryStart != null && !docDateTimeEnd.isBefore(queryStart);
            case EQUAL -> {
                if (queryStart == null) {
                    logger.trace("EQUAL predicate cannot be evaluated with null queryStart.");
                    yield false;
                }
                LocalDateTime effectiveQueryEnd = (queryEnd == null)
                        ? queryStart.toLocalDate().atTime(23, 59, 59)
                        : queryEnd;
                if (queryStart.toLocalDate().isEqual(effectiveQueryEnd.toLocalDate())) {
                    yield docDateTimeStart.toLocalDate().isEqual(queryStart.toLocalDate());
                } else {
                    yield !docDateTimeStart.toLocalDate().isBefore(queryStart.toLocalDate())
                            && !docDateTimeEnd.toLocalDate().isAfter(effectiveQueryEnd.toLocalDate());
                }
            }
            default -> {
                logger.warn("Unsupported TemporalPredicate type: {}. Returning false.", type);
                yield false;
            }
        };
    }

    /**
     * Strips a segment suffix (e.g., "#0", "#123") from a date key.
     * Returns the base key without the suffix.
     */
    static String stripSegmentSuffix(String key) {
        int hashPos = key.lastIndexOf('#');
        if (hashPos <= 0 || hashPos == key.length() - 1)
            return key;
        for (int i = hashPos + 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9')
                return key;
        }
        return key.substring(0, hashPos);
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
        public CellResult execute(
                Temporal condition,
                Map<String, IndexAccessInterface> indexes,
                Query.Granularity granularity,
                int granularitySize,
                String corpusName,
                AttributeRequirements requirements,
                Optional<Roaring64NavigableMap> allowedCells)
                throws QueryExecutionException {

            strategyLogger.debug(">>> Executing NaiveTemporalStrategy (granularity={}, allowedCellsPresent={})",
                    granularity, allowedCells.isPresent());

            IndexAccessInterface dateIndex = indexes.get(DATE_INDEX);
            if (dateIndex == null || !dateIndex.isOpen()) {
                strategyLogger.warn("Date index '{}' is not available or not open. Returning empty result.",
                        DATE_INDEX);
                return CellResult.empty(granularity);
            }

            PostingList.DeserializeMode mode = requirements.toDeserializeMode();
            CellResult result = CellResult.empty(granularity);
            String varName = condition.qualifiedVariableName().orElse(null);
            Map<Long, LocalDate> cellDates = varName != null ? new HashMap<>() : null;

            Optional<LocalDateTime> queryStartDateTime = condition.startDate();
            Optional<LocalDateTime> queryEndDateTime = condition.endDate();
            TemporalPredicate type = condition.temporalType();

            // Calculate iteration range based on the temporal condition
            IterationRange range = calculateIterationRange(type, queryStartDateTime, queryEndDateTime);

            try (RocksIterator iterator = (range.startKey() != null || range.endKey() != null)
                    ? dateIndex.seekWithBounds(
                            (range.startKey() != null ? range.startKey() : "").getBytes(StandardCharsets.UTF_8),
                            (range.endKey() != null ? range.endKey() : null) != null
                                    ? range.endKey().getBytes(StandardCharsets.UTF_8)
                                    : null,
                            256 * 1024)
                    : dateIndex.iterateFromFirst()) {

                if (range.startKey() != null) {
                    strategyLogger.debug("NaiveTemporalStrategy: Bounded seek from: {} to < {}",
                            range.startKey(), range.endKey());
                } else {
                    strategyLogger.debug("NaiveTemporalStrategy: Unbounded full scan (no start/end)");
                }

                int keysProcessed = 0;
                int keysSkipped = 0;
                int keysMatched = 0;

                while (iterator.isValid()) {
                    String currentKey = new String(iterator.key(), StandardCharsets.UTF_8);

                    // Check if we've exceeded our range (endKey is exclusive)
                    if (range.endKey() != null && currentKey.compareTo(range.endKey()) >= 0)
                        break;

                    keysProcessed++;
                    LocalDate entryDate;
                    try {
                        String baseDateKey = stripSegmentSuffix(currentKey);
                        entryDate = parseDateKey(baseDateKey);
                    } catch (DateTimeParseException e) {
                        strategyLogger.warn("Could not parse date from key '{}'. Skipping. Error: {}",
                                currentKey, e.getMessage());
                        iterator.next();
                        continue;
                    }

                    // Ensure date is within the globally supported temporal range
                    if (entryDate.isBefore(TemporalBounds.LOWER) || entryDate.isAfter(TemporalBounds.UPPER)) {
                        iterator.next();
                        continue;
                    }

                    boolean match = evaluateTemporalCondition(
                            type,
                            entryDate.atStartOfDay(),
                            entryDate.atTime(LocalTime.MAX),
                            queryStartDateTime.orElse(null),
                            queryEndDateTime.orElse(null));

                    if (match) {
                        byte[] keyBytes = currentKey.getBytes(StandardCharsets.UTF_8);
                        Optional<PostingList> plOpt = dateIndex.getPostingList(keyBytes, mode);

                        if (plOpt.isPresent() && !plOpt.get().isEmpty()) {
                            PostingList pl = plOpt.get();
                            CellResult keyResult = (mode == PostingList.DeserializeMode.FULL)
                                    ? CellResult.fromPostingListWithOccurrences(pl, granularity)
                                    : CellResult.fromPostingList(pl, granularity);
                            result = result.or(keyResult);
                            keysMatched++;
                            // Record cellKey -> date for variable binding
                            if (varName != null && cellDates != null) {
                                var cellIter = pl.cells().getLongIterator();
                                while (cellIter.hasNext()) {
                                    long ck = cellIter.next();
                                    cellDates.putIfAbsent(ck, entryDate);
                                }
                            }
                        }
                    } else {
                        keysSkipped++;
                    }

                    iterator.next();
                }

                strategyLogger.debug(
                        "NaiveTemporalStrategy: Processed {} keys, {} matched, {} skipped non-matching within range",
                        keysProcessed, keysMatched, keysSkipped);

            } catch (IndexAccessException e) {
                strategyLogger.error("Error during NaiveTemporalStrategy execution: {}", e.getMessage(), e);
                throw new QueryExecutionException(
                        "Error accessing date index or deserializing data.",
                        e,
                        condition.toString(),
                        QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
            }

            // Build bindings if variable binding is active
            Bindings bindings = null;
            if (varName != null && cellDates != null && !cellDates.isEmpty()) {
                Bindings.Builder builder = Bindings.builder();
                for (Map.Entry<Long, LocalDate> entry : cellDates.entrySet()) {
                    builder.withCellKey(entry.getKey())
                            .add(entry.getValue(), ValueType.DATE, varName);
                }
                bindings = builder.build();
                result = CellResult.of(result.cells(), bindings, granularity);
            }

            // Apply allowedCells filtering at the end
            if (allowedCells.isPresent() && !result.isEmpty()) {
                Roaring64NavigableMap filtered = result.cells().clone();
                filtered.and(allowedCells.get());
                result = CellResult.of(filtered, result.bindings(), granularity);
            }

            strategyLogger.debug("NaiveTemporalStrategy finished. Result has {} cells.", result.cellCount());
            return result;
        }

        /**
         * Calculates the optimal iteration range for the date index based on
         * the temporal condition.
         */
        private IterationRange calculateIterationRange(TemporalPredicate type,
                Optional<LocalDateTime> queryStart,
                Optional<LocalDateTime> queryEnd) {
            String startKey = null;
            String endKey = null;

            LocalDate queryStartDate = queryStart.map(LocalDateTime::toLocalDate).orElse(null);
            LocalDate queryEndDate = queryEnd.map(LocalDateTime::toLocalDate).orElse(null);

            switch (type) {
                case EQUAL:
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate);
                        endKey = formatDateKey(
                                queryEndDate != null ? queryEndDate.plusDays(1) : queryStartDate.plusDays(1));
                    }
                    break;

                case INTERSECT, CONTAINS, CONTAINED_BY:
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate);
                    }
                    if (queryEndDate != null) {
                        endKey = formatDateKey(queryEndDate.plusDays(1));
                    }
                    break;

                case AFTER:
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate.plusDays(1));
                        endKey = formatDateKey(TemporalBounds.UPPER);
                    }
                    break;

                case AFTER_EQUAL:
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate);
                        endKey = formatDateKey(TemporalBounds.UPPER);
                    }
                    break;

                case BEFORE:
                    if (queryStartDate != null) {
                        startKey = formatDateKey(TemporalBounds.LOWER);
                        endKey = formatDateKey(queryStartDate.minusDays(1));
                    }
                    break;

                case BEFORE_EQUAL:
                    if (queryStartDate != null) {
                        startKey = formatDateKey(TemporalBounds.LOWER);
                        endKey = formatDateKey(queryStartDate);
                    }
                    break;

                default:
                    strategyLogger.warn("Unknown temporal predicate type: {}. Using full index scan.", type);
                    break;
            }

            strategyLogger.debug("Calculated iteration range for predicate {}: startKey={}, endKey={}",
                    type, startKey, endKey);
            return new IterationRange(startKey, endKey);
        }

        /**
         * Formats a LocalDate as a YYYYMMDD key string for the date index.
         */
        private String formatDateKey(LocalDate date) {
            return date.format(INDEX_DATE_FORMATTER);
        }
    }

    private record IterationRange(String startKey, String endKey) {
    }
}
