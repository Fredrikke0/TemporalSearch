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
import com.example.index.presence.RBPresenceIndex;
import com.example.query.binding.ValueType;
import com.example.query.model.Query;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.condition.Temporal;

import no.ntnu.sandbox.Nash;

/**
 * Executor for temporal conditions in queries. Delegates execution to a selected strategy.
 */
public final class TemporalExecutor implements ConditionExecutor<Temporal> {
    private static final Logger logger = LoggerFactory.getLogger(TemporalExecutor.class);

    private static final String DATE_INDEX = "rb_ner_date";

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

    public TemporalExecutor() {
        registerStrategy(new NaiveTemporalStrategy());
        setActiveStrategy("naive");
    }

    public void registerStrategy(TemporalExecutionStrategy strategy) {
        Objects.requireNonNull(strategy, "Strategy cannot be null");
        Objects.requireNonNull(strategy.getName(), "Strategy name cannot be null");
        if (strategies.containsKey(strategy.getName())) {
             logger.warn("Overwriting existing strategy: {}", strategy.getName());
        }
        strategies.put(strategy.getName(), strategy);
        logger.info("Registered temporal strategy: {}", strategy.getName());
    }

    public void setActiveStrategy(String name) {
        if (!strategies.containsKey(name)) {
            throw new IllegalArgumentException("Strategy '" + name + "' is not registered.");
        }
        this.activeStrategyName = name;
        logger.info("Set active temporal strategy to: {}", name);
    }

    public String getActiveStrategyName() {
        return activeStrategyName;
    }

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
    // Strategy Implementations (RB-only)
    // =========================================================================

    /**
     * Strategy that directly scans the date index with range optimization.
     */
    private static class NaiveTemporalStrategy implements TemporalExecutionStrategy {
        private static final Logger strategyLogger = LoggerFactory.getLogger(NaiveTemporalStrategy.class);

        @Override
        public String getName() { return "naive"; }

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

            strategyLogger.debug(">>> Executing NaiveTemporalStrategy (RB-only)");
            QueryResultSoA resultSoA = new QueryResultSoA(granularity, granularitySize, requirements);

            IndexAccessInterface dateIndex = indexes.get(DATE_INDEX);
            if (dateIndex == null || !dateIndex.isOpen()) {
                strategyLogger.warn("Date index '{}' is not available or not open.", DATE_INDEX);
                return resultSoA;
            }

            String variableNameToBind = condition.variableName();
            Optional<LocalDateTime> queryStartDateTime = condition.startDate();
            Optional<LocalDateTime> queryEndDateTime = condition.endDate();
            TemporalPredicate type = condition.temporalType();

            IterationRange range = calculateIterationRange(condition, type, queryStartDateTime, queryEndDateTime);

            try (RocksIterator iterator = (range.startKey() != null || range.endKey() != null)
                    ? dateIndex.seekWithBounds(
                        (range.startKey() != null ? range.startKey() : "").getBytes(StandardCharsets.UTF_8),
                        (range.endKey() != null ? range.endKey() : null) != null ? range.endKey().getBytes(StandardCharsets.UTF_8) : null,
                        256 * 1024)
                    : dateIndex.iterateFromFirst()) {

                int keysProcessed = 0;

                while (iterator.isValid()) {
                    String currentKey = new String(iterator.key(), StandardCharsets.UTF_8);
                    if (range.endKey() != null && currentKey.compareTo(range.endKey()) >= 0) break;

                    keysProcessed++;
                    LocalDate entryDate;
                    try {
                        String baseDateKey = stripSegmentSuffix(currentKey);
                        entryDate = TemporalExecutor.parseDateKey(baseDateKey);
                    } catch (DateTimeParseException e) {
                        iterator.next();
                        continue;
                    }

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
                        try {
                            Optional<byte[]> raw = dateIndex.getRaw(stripSegmentSuffix(currentKey).getBytes(StandardCharsets.UTF_8));
                            if (raw.isEmpty()) { iterator.next(); continue; }
                            RBPresenceIndex presence = RBPresenceIndex.fromBytes(raw.get());
                            org.roaringbitmap.longlong.LongIterator it = presence.getBitmap().getLongIterator();
                            while (it.hasNext()) {
                                long pair = it.next();
                                int docId = (int)(pair >>> 16);
                                int sentId = (int)(pair & 0xFFFFL);
                                resultSoA.add(
                                    entryDate,
                                    ValueType.DATE,
                                    variableNameToBind,
                                    docId,
                                    requirements.needsSentenceId ? sentId : -1,
                                    -1,
                                    -1,
                                    -1,
                                resultSoA.getNextConceptualRowId()
                                );
                            // conceptual row id already advanced in add()
                            }
                        } catch (Exception e) {
                            throw new QueryExecutionException("Error accessing rb_ner_date presence: " + e.getMessage(), e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
                        }
                    }

                    iterator.next();
                }

                strategyLogger.debug("NaiveTemporalStrategy: Processed {} keys", keysProcessed);

            } catch (IndexAccessException e) {
                strategyLogger.error("Error during NaiveTemporalStrategy execution: {}", e.getMessage(), e);
                throw new QueryExecutionException("Error accessing date index or deserializing data.", e, condition.toString(), QueryExecutionException.ErrorType.INDEX_ACCESS_ERROR);
            }

            resultSoA.sort();
            return resultSoA;
        }

        private IterationRange calculateIterationRange(Temporal condition, TemporalPredicate type,
                                                     Optional<LocalDateTime> queryStart, Optional<LocalDateTime> queryEnd) {
            String startKey = null;
            String endKey = null;

            LocalDate queryStartDate = queryStart.map(LocalDateTime::toLocalDate).orElse(null);
            LocalDate queryEndDate = queryEnd.map(LocalDateTime::toLocalDate).orElse(null);

            switch (type) {
                case EQUAL -> {
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate);
                        endKey = formatDateKey(queryEndDate != null ? queryEndDate.plusDays(1) : queryStartDate.plusDays(1));
                    }
                }
                case INTERSECT, CONTAINS, CONTAINED_BY -> {
                    if (queryStartDate != null) startKey = formatDateKey(queryStartDate);
                    if (queryEndDate != null) endKey = formatDateKey(queryEndDate.plusDays(1));
                }
                case AFTER -> {
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate.plusDays(1));
                        endKey = formatDateKey(Nash.GLOBAL_UPPER_BOUND);
                    }
                }
                case AFTER_EQUAL -> {
                    if (queryStartDate != null) {
                        startKey = formatDateKey(queryStartDate);
                        endKey = formatDateKey(Nash.GLOBAL_UPPER_BOUND);
                    }
                }
                case BEFORE -> {
                    if (queryStartDate != null) {
                        startKey = formatDateKey(Nash.GLOBAL_LOWER_BOUND);
                        endKey = formatDateKey(queryStartDate.minusDays(1));
                    }
                }
                case BEFORE_EQUAL -> {
                    if (queryStartDate != null) {
                        startKey = formatDateKey(Nash.GLOBAL_LOWER_BOUND);
                        endKey = formatDateKey(queryStartDate);
                    }
                }
                case PROXIMITY -> {
                    if (queryStartDate != null) startKey = formatDateKey(queryStartDate);
                    if (queryEndDate != null) endKey = formatDateKey(queryEndDate.plusDays(1));
                }
                default -> {}
            }

            return new IterationRange(startKey, endKey);
        }

        private String formatDateKey(LocalDate date) { return date.format(INDEX_DATE_FORMATTER); }

        private String stripSegmentSuffix(String key) {
            int hashPos = key.lastIndexOf('#');
            if (hashPos <= 0 || hashPos == key.length() - 1) return key;
            for (int i = hashPos + 1; i < key.length(); i++) {
                char c = key.charAt(i);
                if (c < '0' || c > '9') return key;
            }
            return key.substring(0, hashPos);
        }

        private record IterationRange(String startKey, String endKey) {}
    }

    public static LocalDate parseDateKey(String dateStr) {
        if (dateStr == null) return null;
        try {
            return LocalDate.parse(dateStr.trim(), INDEX_DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            logger.trace("Failed to parse date key '{}' using format yyyyMMdd: {}", dateStr, e.getMessage());
            return null;
        }
    }

    public static boolean evaluateTemporalCondition(TemporalPredicate type, LocalDateTime docDateTimeStart, LocalDateTime docDateTimeEnd, LocalDateTime queryStart, LocalDateTime queryEnd) {
        if (queryStart != null && queryEnd != null && queryStart.isAfter(queryEnd)) {
            logger.warn("Invalid query interval detected in evaluation: queryStart ({}) is after queryEnd ({}). Returning false.", queryStart, queryEnd);
            return false;
        }
        if (docDateTimeStart.isAfter(docDateTimeEnd)) {
            logger.warn("Invalid document interval detected in evaluation: docStart ({}) is after docEnd ({}). Returning false.", docDateTimeStart, docDateTimeEnd);
            return false;
        }

        return switch (type) {
            case CONTAINS -> queryStart != null && queryEnd != null && !queryStart.isAfter(docDateTimeStart) && !queryEnd.isBefore(docDateTimeEnd);
            case CONTAINED_BY -> queryStart != null && queryEnd != null && !docDateTimeStart.isAfter(queryStart) && !docDateTimeEnd.isBefore(queryEnd);
            case INTERSECT -> queryStart != null && queryEnd != null && !queryStart.isAfter(docDateTimeEnd) && !queryEnd.isBefore(docDateTimeStart);
            case BEFORE -> queryStart != null && docDateTimeEnd.isBefore(queryStart);
            case AFTER -> queryStart != null && docDateTimeStart.isAfter(queryStart);
            case BEFORE_EQUAL -> queryEnd != null && !docDateTimeStart.isAfter(queryEnd);
            case AFTER_EQUAL -> queryStart != null && !docDateTimeEnd.isBefore(queryStart);
            case EQUAL -> {
               if (queryStart == null) { yield false; }
               LocalDateTime effectiveQueryEnd = (queryEnd == null) ? queryStart.toLocalDate().atTime(23,59,59) : queryEnd;
               if (queryStart.toLocalDate().isEqual(effectiveQueryEnd.toLocalDate())) {
                   yield docDateTimeStart.toLocalDate().isEqual(queryStart.toLocalDate());
               } else {
                   yield !docDateTimeStart.toLocalDate().isBefore(queryStart.toLocalDate()) &&
                         !docDateTimeEnd.toLocalDate().isAfter(effectiveQueryEnd.toLocalDate());
               }
           }
            case PROXIMITY -> {
                yield queryStart != null && queryEnd != null && !queryStart.isAfter(docDateTimeEnd) && !queryEnd.isBefore(docDateTimeStart);
            }
            default -> { yield false; }
        };
    }
}