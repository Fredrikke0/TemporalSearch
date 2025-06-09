package com.example.query.model.condition;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;
import com.example.query.model.TemporalPredicate;
import com.example.query.model.TemporalRange;

import no.ntnu.sandbox.Nash;

/**
 * Represents a temporal condition in the query language.
 * This condition matches documents based on temporal expressions.
 *
 * The temporal types are designed to align with Nash predicates for efficient querying.
 */
public record Temporal(
    Optional<LocalDateTime> startDate,
    Optional<LocalDateTime> endDate,
    Optional<String> qualifiedVariableName,
    Optional<TemporalRange> range,
    TemporalPredicate temporalType
) implements Condition {

    /**
     * Helper record to store parsed start and end dates from a Nash interval.
     */
    private record NashIntervalDatePair(LocalDate start, LocalDate end) {}

    /**
     * Maps comparison operators to TemporalPredicate
     */
    public enum ComparisonType {
        LT(TemporalPredicate.BEFORE),
        GT(TemporalPredicate.AFTER),
        LE(TemporalPredicate.BEFORE_EQUAL),
        GE(TemporalPredicate.AFTER_EQUAL),
        EQ(TemporalPredicate.EQUAL);

        private final TemporalPredicate temporalPredicate;

        ComparisonType(TemporalPredicate temporalPredicate) {
            this.temporalPredicate = temporalPredicate;
        }

        public TemporalPredicate getTemporalPredicate() {
            return temporalPredicate;
        }
    }

    // Date formatters for Nash interval conversion
    private static final DateTimeFormatter NASH_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    // Define MIN/MAX bounds based on Nash implementation
    private static final LocalDate MIN_NASH_DATE = Nash.GLOBAL_LOWER_BOUND;
    private static final LocalDate MAX_NASH_DATE = Nash.GLOBAL_UPPER_BOUND;

    /**
     * Parses a Nash interval string (e.g., "[YYYY-MM-DD , YYYY-MM-DD]") into a pair of LocalDate objects.
     *
     * @param nashInterval The interval string.
     * @return An Optional containing a NashIntervalDatePair if parsing is successful, otherwise empty.
     */
    private static Optional<NashIntervalDatePair> parseNashIntervalToDates(String nashInterval) {
        if (nashInterval == null || nashInterval.isBlank()) {
            return Optional.empty();
        }
        String interval = nashInterval.replaceAll("[\\[\\]]", "").trim();
        String[] parts = interval.split(" *, *");

        if (parts.length != 2) {
            // logger.warn("Invalid Nash interval format for parsing to dates: {}", nashInterval);
            return Optional.empty();
        }

        try {
            LocalDate startDateVal = LocalDate.parse(parts[0].trim(), NASH_DATE_FORMAT);
            LocalDate endDateVal = LocalDate.parse(parts[1].trim(), NASH_DATE_FORMAT);
            return Optional.of(new NashIntervalDatePair(startDateVal, endDateVal));
        } catch (Exception e) {
            // logger.warn("Failed to parse dates from Nash interval string '{}': {}", nashInterval, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Creates a temporal condition with validation.
     */
    public Temporal {
        Objects.requireNonNull(startDate, "Start date Optional cannot be null");
        Objects.requireNonNull(endDate, "End date Optional cannot be null");
        Objects.requireNonNull(qualifiedVariableName, "Qualified variable name Optional cannot be null");
        Objects.requireNonNull(range, "Range Optional cannot be null");
        Objects.requireNonNull(temporalType, "Temporal type cannot be null");

        if (qualifiedVariableName.isPresent() && qualifiedVariableName.get().isBlank()) {
            throw new IllegalArgumentException("Qualified variable name cannot be blank when present");
        }

        // Calculate effective end date for validation if type is EQUAL and original endDate is empty
        Optional<LocalDateTime> calculatedEndDateOpt = endDate;
        if (temporalType == TemporalPredicate.EQUAL && startDate.isPresent() && endDate.isEmpty()) {
            LocalDateTime start = startDate.get();
            boolean isStartOfDay = start.getHour() == 0 && start.getMinute() == 0 && start.getSecond() == 0 && start.getNano() == 0;
            if (isStartOfDay) {
                if (start.getDayOfMonth() == 1 && start.getMonthValue() == 1) { // YYYY
                    calculatedEndDateOpt = Optional.of(LocalDateTime.of(start.getYear(), 12, 31, 23, 59, 59));
                } else if (start.getDayOfMonth() == 1) { // YYYY-MM
                    java.time.YearMonth ym = java.time.YearMonth.of(start.getYear(), start.getMonth()); // Use YearMonth.of
                    calculatedEndDateOpt = Optional.of(LocalDateTime.of(start.getYear(), start.getMonth(), ym.lengthOfMonth(), 23, 59, 59));
                } else { // YYYY-MM-DD
                    calculatedEndDateOpt = Optional.of(start.toLocalDate().atTime(23, 59, 59));
                }
            } else { // Non-start-of-day date, equality still means the whole day for DATE()
                calculatedEndDateOpt = Optional.of(start.toLocalDate().atTime(23, 59, 59));
            }
        }
        // Assign the calculatedEndDateOpt to the record's endDate field if it was originally empty and has been calculated
        if (endDate.isEmpty() && calculatedEndDateOpt.isPresent()) {
            endDate = calculatedEndDateOpt;
        }

        // Use the potentially updated 'endDate' for validation
        if (startDate.isPresent() && endDate.isPresent() && startDate.get().isAfter(endDate.get())) {
            throw new IllegalArgumentException(String.format("Start date (%s) cannot be after effective end date (%s) for predicate %s",
                                                            startDate.get(), endDate.get(), temporalType));
        }

        if (isComparisonPredicate(temporalType)) {
            // For EQUAL, effectiveEndDate *can* be present if it defines the end of an equality range (e.g. DATE(=YYYY)).
            // For <, >, <=, >=, the original endDate parameter should be empty.
            if (temporalType != TemporalPredicate.EQUAL && endDate.isPresent()) {
                 throw new IllegalArgumentException(
                    String.format("Predicate %s should not have an endDate. StartDate: %s, EndDate: %s",
                                  temporalType, startDate, endDate)
                );
            }
             // For EQUAL, startDate must be present if it's a literal comparison.
            if (temporalType == TemporalPredicate.EQUAL && startDate.isEmpty()) {
                 throw new IllegalArgumentException(temporalType + " predicate requires a startDate when comparing to a literal.");
            }
            // For <, >, <=, >=, startDate must be present if it's a literal comparison.
            // Variable comparisons like ?var < date_literal or date_literal < ?var are parsed into Temporal such that startDate holds the literal.
             if ((temporalType == TemporalPredicate.BEFORE || temporalType == TemporalPredicate.AFTER ||
                  temporalType == TemporalPredicate.BEFORE_EQUAL || temporalType == TemporalPredicate.AFTER_EQUAL) &&
                 startDate.isEmpty()) {
                throw new IllegalArgumentException(temporalType + " predicate requires a startDate when comparing to a literal.");
            }

        } else if (temporalType == TemporalPredicate.CONTAINS || temporalType == TemporalPredicate.CONTAINED_BY || temporalType == TemporalPredicate.INTERSECT) {
            if (startDate.isEmpty() || endDate.isEmpty()) { // These predicates always require both from the query
                throw new IllegalArgumentException("Interval predicates (CONTAINS, CONTAINED_BY, INTERSECT) require both start and end dates from query.");
            }
        }
    }

    // Helper to check if a predicate is a simple comparison type
    private static boolean isComparisonPredicate(TemporalPredicate predicate) {
        return predicate == TemporalPredicate.BEFORE ||
               predicate == TemporalPredicate.AFTER ||
               predicate == TemporalPredicate.BEFORE_EQUAL ||
               predicate == TemporalPredicate.AFTER_EQUAL ||
               predicate == TemporalPredicate.EQUAL;
    }

    /**
     * Constructor for simple date comparison (now expects Optional startDate).
     */
    public Temporal(ComparisonType comparisonType, int year) {
        this(Optional.of(LocalDateTime.of(year, 1, 1, 0, 0)),
             Optional.empty(),
             Optional.empty(),
             Optional.empty(),
             comparisonType.getTemporalPredicate());
    }

    /**
     * Constructor for simple date comparison with a variable (now expects Optional startDate).
     */
    public Temporal(ComparisonType comparisonType, int year, String variableName) {
        this(Optional.of(LocalDateTime.of(year, 1, 1, 0, 0)),
             Optional.empty(),
             Optional.of(variableName),
             Optional.empty(),
             comparisonType.getTemporalPredicate());
    }

    /**
     * Constructor for simple temporal condition (now expects Optional startDate).
     */
    public Temporal(TemporalPredicate type, LocalDateTime startDate) {
        this(Optional.of(startDate), Optional.empty(), Optional.empty(), Optional.empty(), type);
    }

    /**
     * Constructor for date range condition (now expects Optional startDate).
     */
    public Temporal(LocalDateTime startDate, LocalDateTime endDate) {
        this(Optional.of(startDate), Optional.of(endDate), Optional.empty(), Optional.empty(), TemporalPredicate.CONTAINS);
    }

    /**
     * Constructor for date range condition with specific temporal type (now expects Optional startDate).
     */
    public Temporal(TemporalPredicate type, LocalDateTime startDate, LocalDateTime endDate) {
        this(Optional.of(startDate), Optional.of(endDate), Optional.empty(), Optional.empty(), type);
    }

    /**
     * Constructor for temporal condition with range (now expects Optional startDate).
     */
    public Temporal(TemporalPredicate type, LocalDateTime date, String range) {
        this(Optional.of(date), Optional.empty(), Optional.empty(), Optional.of(new TemporalRange(range)), type);
    }

    /**
     * Constructor for temporal condition with range and variable (now expects Optional startDate).
     */
    public Temporal(TemporalPredicate type, LocalDateTime date, Optional<TemporalRange> range, String variableName) {
        this(Optional.of(date), Optional.empty(), Optional.of(variableName), range, type);
    }

    /**
     * Constructor that creates a Temporal from a Nash-compatible interval string.
     * Format expected: [YYYY-MM-DD , YYYY-MM-DD]
     *
     * @param type The temporal type for this condition
     * @param nashIntervalString The interval string in Nash format
     */
    public static Temporal fromNashInterval(TemporalPredicate type, String nashIntervalString) {
        String interval = nashIntervalString.replaceAll("[\\[\\]]", "").trim();
        String[] parts = interval.split(" *, *");

        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid Nash interval format: " + nashIntervalString);
        }

        LocalDate startDateVal = LocalDate.parse(parts[0].trim(), NASH_DATE_FORMAT);
        LocalDate endDateVal = LocalDate.parse(parts[1].trim(), NASH_DATE_FORMAT);

        return new Temporal(
            Optional.of(startDateVal.atStartOfDay()),
            Optional.of(endDateVal.atStartOfDay()),
            Optional.empty(),
            Optional.empty(),
            type
        );
    }

    /**
     * Converts this Temporal to a Nash-compatible interval string.
     * Handles comparison operators by converting them into appropriate ranges
     * using MIN_NASH_DATE and MAX_NASH_DATE.
     *
     * @return A string in the format [YYYY-MM-DD , YYYY-MM-DD]
     */
    public Optional<String> toNashInterval() {
        if (isComparisonPredicate(temporalType)) {
            if (startDate.isEmpty()) {
                // Cannot form an interval for comparison if the date is not present
                return Optional.empty();
            }
            LocalDateTime comparisonDate = startDate.get();

            // Prioritize already set endDate (from constructor) for EQUAL predicate.
            // Only re-derive effectiveEndDateForNash if endDate is truly empty.
            LocalDateTime effectiveEndDateForNash = null;
            if (temporalType == TemporalPredicate.EQUAL) {
                if (endDate.isPresent()) {
                    effectiveEndDateForNash = endDate.get();
                } else {
                    // endDate is not present, so derive it based on startDate
                    LocalDateTime start = startDate.get();
                    boolean isStartOfDay = start.getHour() == 0 && start.getMinute() == 0 && start.getSecond() == 0 && start.getNano() == 0;
                    if (isStartOfDay) {
                        if (start.getDayOfMonth() == 1 && start.getMonthValue() == 1) { // YYYY
                            effectiveEndDateForNash = LocalDateTime.of(start.getYear(), 12, 31, 23, 59, 59);
                        } else if (start.getDayOfMonth() == 1) { // YYYY-MM
                            java.time.YearMonth ym = java.time.YearMonth.from(start);
                            effectiveEndDateForNash = LocalDateTime.of(start.getYear(), start.getMonth(), ym.lengthOfMonth(), 23, 59, 59);
                        } else { // YYYY-MM-DD
                            effectiveEndDateForNash = start.toLocalDate().atTime(23, 59, 59);
                        }
                    } else { // Non-start-of-day date, equality still means the whole day for DATE()
                        effectiveEndDateForNash = start.toLocalDate().atTime(23, 59, 59);
                    }
                }
            } else { // For other comparison predicates, endDate is not typically used directly for interval construction.
                effectiveEndDateForNash = endDate.orElse(null); // Though it might be null.
            }

            return switch (temporalType) {
                case BEFORE -> {
                    LocalDate dateOnly = comparisonDate.toLocalDate().minusDays(1);
                    if (dateOnly.isBefore(MIN_NASH_DATE)) dateOnly = MIN_NASH_DATE;
                    // Ensure end date is not before MIN_NASH_DATE, and also not before actual MIN_NASH_DATE
                    LocalDate effectiveMinNash = MIN_NASH_DATE;
                    if (dateOnly.isBefore(effectiveMinNash)) dateOnly = effectiveMinNash; // Should not happen if MIN_NASH_DATE is truly min

                    yield Optional.of(String.format("[%s , %s]",
                                             NASH_DATE_FORMAT.format(effectiveMinNash),
                                             NASH_DATE_FORMAT.format(dateOnly)));
                }
                case AFTER -> {
                    LocalDate dateOnly = comparisonDate.toLocalDate().plusDays(1);
                    if (dateOnly.isAfter(MAX_NASH_DATE)) dateOnly = MAX_NASH_DATE;
                    LocalDate effectiveMaxNash = MAX_NASH_DATE;
                    if (dateOnly.isAfter(effectiveMaxNash)) dateOnly = effectiveMaxNash; // Should not happen

                    yield Optional.of(String.format("[%s , %s]",
                                            NASH_DATE_FORMAT.format(dateOnly),
                                            NASH_DATE_FORMAT.format(effectiveMaxNash)));
                }
                case BEFORE_EQUAL -> {
                    LocalDate dateOnly = comparisonDate.toLocalDate();
                    if (dateOnly.isBefore(MIN_NASH_DATE)) dateOnly = MIN_NASH_DATE; // Should use MIN_NASH_DATE if target is before
                    yield Optional.of(String.format("[%s , %s]",
                                                  NASH_DATE_FORMAT.format(MIN_NASH_DATE),
                                                  NASH_DATE_FORMAT.format(dateOnly)));
                }
                case AFTER_EQUAL -> {
                    LocalDate dateOnly = comparisonDate.toLocalDate();
                    if (dateOnly.isAfter(MAX_NASH_DATE)) dateOnly = MAX_NASH_DATE; // Should use MAX_NASH_DATE if target is after
                    yield Optional.of(String.format("[%s , %s]",
                                                 NASH_DATE_FORMAT.format(dateOnly),
                                                 NASH_DATE_FORMAT.format(MAX_NASH_DATE)));
                }
                case EQUAL -> {
                    LocalDate dateOnlyStartOriginal = startDate.get().toLocalDate();
                    // Use effectiveEndDateForNash if available (derived for YYYY, YYYY-MM, YYYY-MM-DD cases)
                    // otherwise use the original endDate if present, or default to start date if both empty (single day)
                    LocalDate dateOnlyEnd = Optional.ofNullable(effectiveEndDateForNash)
                                                    .map(LocalDateTime::toLocalDate)
                                                    .orElse(dateOnlyStartOriginal); // Fallback to start if somehow effectiveEndDateForNash is still null

                    LocalDate finalDateOnlyStart = dateOnlyStartOriginal;
                    // Clamp to Nash bounds
                    if (finalDateOnlyStart.isBefore(MIN_NASH_DATE)) finalDateOnlyStart = MIN_NASH_DATE;
                    if (dateOnlyEnd.isAfter(MAX_NASH_DATE)) dateOnlyEnd = MAX_NASH_DATE;
                    // Ensure valid range (end not before start)
                    if (dateOnlyEnd.isBefore(finalDateOnlyStart)) dateOnlyEnd = finalDateOnlyStart;

                    yield Optional.of(String.format("[%s , %s]",
                                           NASH_DATE_FORMAT.format(finalDateOnlyStart),
                                           NASH_DATE_FORMAT.format(dateOnlyEnd)));
                }
                default -> Optional.empty(); // Should not happen for comparison predicates
            };
        }

        if (temporalType == TemporalPredicate.CONTAINS ||
            temporalType == TemporalPredicate.INTERSECT ||
            temporalType == TemporalPredicate.CONTAINED_BY) {

            if (startDate.isPresent() && endDate.isPresent()) {
                LocalDate start = startDate.get().toLocalDate();
                LocalDate end = endDate.get().toLocalDate();

                if (start.isBefore(MIN_NASH_DATE)) start = MIN_NASH_DATE;
                if (end.isAfter(MAX_NASH_DATE)) end = MAX_NASH_DATE;
                if (end.isBefore(start)) end = start;

                return Optional.of(String.format("[%s , %s]",
                                         NASH_DATE_FORMAT.format(start),
                                         NASH_DATE_FORMAT.format(end)));
            } else {
                return Optional.empty();
            }
        }

        if (temporalType == TemporalPredicate.PROXIMITY) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    /**
     * Expands year-only Nash intervals to full dates.
     * For example, [2023, 2024] becomes [2023-01-01, 2024-12-31].
     *
     * @param interval The interval string to expand
     * @return Expanded interval string
     */
    public static String expandYearOnlyInterval(String interval) {
        // Remove brackets
        String cleanInterval = interval.replaceAll("[\\[\\]]", "");

        // Split the interval
        String[] parts = cleanInterval.split(" *, *");
        if (parts.length != 2) {
            return "[" + interval + "]"; // Return original with brackets if invalid
        }

        // Check if parts are just years
        boolean areJustYears = true;
        for (String part : parts) {
            try {
                Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                areJustYears = false;
                break;
            }
        }

        // If parts are just years, expand to full ISO dates
        if (areJustYears) {
            String start = parts[0].trim() + "-01-01";
            String end = parts[1].trim() + "-12-31";
            return "[" + start + " , " + end + "]";
        }

        // Return the original format with brackets
        return "[" + parts[0] + " , " + parts[1] + "]";
    }

    @Override
    public String getType() {
        return "TEMPORAL";
    }

    @Override
    public Set<String> getProducedVariables() {
        return qualifiedVariableName.isPresent() ? Set.of(qualifiedVariableName.get()) : Collections.emptySet();
    }

    @Override
    public VariableType getProducedVariableType() {
        return VariableType.TEMPORAL;
    }

    @Override
    public void registerVariables(VariableRegistry registry) {
        if (qualifiedVariableName.isPresent()) {
            // Registration now happens in QueryModelBuilder with qualified name
            // registry.registerProducer(qualifiedVariableName.get(), getProducedVariableType(), getType());
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DATE(");

        // Append operator and date value representation
        if (temporalType == TemporalPredicate.INTERSECT && startDate.isPresent() && endDate.isPresent()) {
            // Handle date comparison operators by showing the original comparison
            // This requires mapping back from the internal representation, which is complex.
            // For simplicity, show the internal INTERSECT format.
            sb.append(temporalType.name()).append(" ");
            sb.append("[").append(NASH_DATE_FORMAT.format(startDate.get()));
            sb.append(", ").append(NASH_DATE_FORMAT.format(endDate.get())).append("]");
        } else {
            sb.append(temporalType.name());
            if (startDate.isPresent()) {
                 sb.append(" ").append(NASH_DATE_FORMAT.format(startDate.get()));
                 endDate.ifPresent(end -> sb.append(" .. ").append(NASH_DATE_FORMAT.format(end)));
            }
        }

        range.ifPresent(r -> sb.append(" RADIUS ").append(r.value()));
        sb.append(")");

        // Append qualified variable name if present
        qualifiedVariableName.ifPresent(var -> sb.append(" BIND ").append(var));

        return sb.toString();
    }

    /**
     * Returns the variable name if this is a variable binding condition.
     *
     * @return The qualified variable name, or null if not bound
     */
    public String variableName() {
        return qualifiedVariableName.orElse(null);
    }

    /**
     * Attempts to intersect this Temporal condition with another (typically non-binding) Temporal condition.
     * If successful, returns a new Temporal condition representing the intersection.
     * The new condition will have TemporalPredicate.INTERSECT and will retain the
     * qualifiedVariableName and range of this original condition.
     *
     * @param otherNonBindingFilter The other Temporal condition to intersect with.
     * @return An Optional containing the merged Temporal condition, or empty if merging is not possible
     *         or results in an invalid/empty interval.
     */
    public Optional<Temporal> intersectWith(Temporal otherNonBindingFilter) {
        Optional<String> thisIntervalOpt = this.toNashInterval();
        Optional<String> otherIntervalOpt = otherNonBindingFilter.toNashInterval();

        if (thisIntervalOpt.isEmpty() || otherIntervalOpt.isEmpty()) {
            return Optional.empty(); // Cannot merge if one doesn't produce a valid interval
        }

        Optional<NashIntervalDatePair> p1Opt = parseNashIntervalToDates(thisIntervalOpt.get());
        Optional<NashIntervalDatePair> p2Opt = parseNashIntervalToDates(otherIntervalOpt.get());

        if (p1Opt.isEmpty() || p2Opt.isEmpty()) {
            return Optional.empty(); // Parsing failed for one of the intervals
        }

        NashIntervalDatePair p1 = p1Opt.get();
        NashIntervalDatePair p2 = p2Opt.get();

        LocalDate s1 = p1.start(); LocalDate e1 = p1.end();
        LocalDate s2 = p2.start(); LocalDate e2 = p2.end();

        // Calculate intersection: newStart = max(s1, s2), newEnd = min(e1, e2)
        LocalDate newStart = s1.isAfter(s2) ? s1 : s2;
        LocalDate newEnd = e1.isBefore(e2) ? e1 : e2;

        // Check if intersection is valid (newStart must be before or equal to newEnd)
        if (newStart.isAfter(newEnd)) {
            // The intersection is empty/invalid.
            return Optional.empty();
        }

        // Create new Temporal with INTERSECT, new dates, and this condition's variable name and range.
        // Range is preserved from 'this' original condition.
        return Optional.of(new Temporal(
            Optional.of(newStart.atStartOfDay()),
            Optional.of(newEnd.atStartOfDay()),
            this.qualifiedVariableName(), // Preserve original variable binding
            this.range(),                 // Preserve original range
            TemporalPredicate.INTERSECT
        ));
    }

    /**
     * Creates a new Temporal condition with the variable name requalified.
     * This is used during subquery processing when variable names need to be updated
     * from one alias scope to another (e.g., from "$main.date" to "q2.date").
     *
     * @param oldPrefix The old prefix to replace (e.g., "$main.")
     * @param newPrefix The new prefix to use (e.g., "q2.")
     * @return A new Temporal condition with the requalified variable name, or this condition if no change needed
     */
    public Temporal requalifyVariable(String oldPrefix, String newPrefix) {
        if (qualifiedVariableName.isEmpty()) {
            return this; // No variable to requalify
        }

        String currentVarName = qualifiedVariableName.get();
        if (!currentVarName.startsWith(oldPrefix)) {
            return this; // Variable doesn't match the old prefix
        }

        String newVarName = newPrefix + currentVarName.substring(oldPrefix.length());
        return new Temporal(
            this.startDate,
            this.endDate,
            Optional.of(newVarName),
            this.range,
            this.temporalType
        );
    }

    /**
     * Checks if a given document date/time matches this temporal condition.
     * The documentDateTime is typically the start of the day for date-only comparisons from stitch index.
     *
     * @param documentDateTime The date/time from the document/index to check.
     * @return true if the condition is met, false otherwise.
     */
    public boolean matches(LocalDateTime documentDateTime) {
        // For stitch index, documentDateTime is often just a LocalDate.atStartOfDay().
        // We represent it as a one-day interval for comparison.
        LocalDateTime docStart = documentDateTime;
        LocalDateTime docEnd = documentDateTime.toLocalDate().atTime(LocalTime.MAX);

        // Use this condition's startDate, endDate, and temporalType (which are from the query)
        Optional<LocalDateTime> queryStartOpt = this.startDate();
        Optional<LocalDateTime> queryEndOpt = this.endDate(); // This endDate is already effectively calculated in constructor for EQUAL
        TemporalPredicate type = this.temporalType();

        // Ensure query interval is valid if both are present (should be guaranteed by constructor)
        if (queryStartOpt.isPresent() && queryEndOpt.isPresent() && queryStartOpt.get().isAfter(queryEndOpt.get())) {
            // This should not happen due to constructor validation
            return false;
        }

        return switch (type) {
            case CONTAINS -> queryStartOpt.isPresent() && queryEndOpt.isPresent() &&
                             !queryStartOpt.get().isAfter(docStart) && !queryEndOpt.get().isBefore(docEnd);
            case CONTAINED_BY -> queryStartOpt.isPresent() && queryEndOpt.isPresent() &&
                                 !docStart.isAfter(queryStartOpt.get()) && !docEnd.isBefore(queryEndOpt.get());
            case INTERSECT -> queryStartOpt.isPresent() && queryEndOpt.isPresent() &&
                              !queryStartOpt.get().isAfter(docEnd) && !queryEndOpt.get().isBefore(docStart);
            case BEFORE -> queryStartOpt.isPresent() && docEnd.isBefore(queryStartOpt.get());
            case AFTER -> queryStartOpt.isPresent() && docStart.isAfter(queryStartOpt.get());
            case BEFORE_EQUAL -> queryStartOpt.isPresent() && !docStart.isAfter(queryStartOpt.get()); // queryStartOpt is the reference point from query
            case AFTER_EQUAL -> queryStartOpt.isPresent() && !docEnd.isBefore(queryStartOpt.get());   // queryStartOpt is the reference point from query
            case EQUAL -> {
                if (queryStartOpt.isEmpty()) yield false; // EQUAL requires a query date
                // Query interval for EQUAL is [queryStartOpt.toLocalDate(), effectiveQueryEnd.toLocalDate()]
                // This was already handled by constructor setting this.endDate() for equality checks.
                LocalDate queryDate = queryStartOpt.get().toLocalDate();
                LocalDate effectiveQueryEndDate = this.endDate().map(LocalDateTime::toLocalDate).orElse(queryDate); // Use already processed endDate

                yield !docStart.toLocalDate().isBefore(queryDate) &&
                      !docEnd.toLocalDate().isAfter(effectiveQueryEndDate);
            }
            case PROXIMITY -> {
                // PROXIMITY is complex and usually involves range calculation based on radius.
                // For a simple true/false based on a single doc date, it's tricky without more context.
                // This might be better handled if PROXIMITY implies an INTERSECT test against a pre-calculated range.
                // For now, treating as INTERSECT if query has a range, otherwise false.
                if (queryStartOpt.isPresent() && queryEndOpt.isPresent()) {
                    yield !queryStartOpt.get().isAfter(docEnd) && !queryEndOpt.get().isBefore(docStart); // Intersect
                }
                yield false;
            }
            default -> false;
        };
    }
}