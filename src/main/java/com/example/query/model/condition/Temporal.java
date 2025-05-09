package com.example.query.model.condition;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

        if (startDate.isPresent() && endDate.isPresent() && startDate.get().isAfter(endDate.get())) {
            throw new IllegalArgumentException(String.format("Start date (%s) cannot be after end date (%s)", startDate.get(), endDate.get()));
        }
        
        if (isComparisonPredicate(temporalType)) {
            if (endDate.isPresent()) {
                throw new IllegalArgumentException("Comparison predicates (BEFORE, AFTER, etc.) should not have an end date.");
            }
            if (startDate.isEmpty()) {
                // Allow empty startDate if it's a variable comparison (e.g., date < ?var)
                // But require it if it's a literal comparison (e.g., date < 2023-01-01)
                // This validation might need refinement based on how variable resolution works.
                // For now, we allow empty startDate, assuming it will be resolved later or is a variable comparison.
                // logger.debug("Comparison predicate with empty startDate - assuming variable comparison or later resolution.");
            }
        } else if (temporalType == TemporalPredicate.CONTAINS || temporalType == TemporalPredicate.CONTAINED_BY || temporalType == TemporalPredicate.INTERSECT) {
            if (startDate.isEmpty() || endDate.isEmpty()) {
                throw new IllegalArgumentException("Interval predicates (CONTAINS, CONTAINED_BY, INTERSECT) require both start and end dates.");
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
                // (e.g. if it's purely a variable comparison not yet resolved)
                return Optional.empty();
            }
            LocalDateTime comparisonDate = startDate.get();
            LocalDate dateOnly = comparisonDate.toLocalDate();

            return switch (temporalType) {
                case BEFORE -> Optional.of(String.format("[%s , %s]", 
                                             NASH_DATE_FORMAT.format(MIN_NASH_DATE), 
                                             NASH_DATE_FORMAT.format(dateOnly.minusDays(1))));
                case AFTER -> Optional.of(String.format("[%s , %s]", 
                                            NASH_DATE_FORMAT.format(dateOnly.plusDays(1)), 
                                            NASH_DATE_FORMAT.format(MAX_NASH_DATE)));
                case BEFORE_EQUAL -> Optional.of(String.format("[%s , %s]", 
                                                  NASH_DATE_FORMAT.format(MIN_NASH_DATE), 
                                                  NASH_DATE_FORMAT.format(dateOnly)));
                case AFTER_EQUAL -> Optional.of(String.format("[%s , %s]", 
                                                 NASH_DATE_FORMAT.format(dateOnly), 
                                                 NASH_DATE_FORMAT.format(MAX_NASH_DATE)));
                case EQUAL -> Optional.of(String.format("[%s , %s]", 
                                           NASH_DATE_FORMAT.format(dateOnly), 
                                           NASH_DATE_FORMAT.format(dateOnly)));
                default -> Optional.empty();
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
} 