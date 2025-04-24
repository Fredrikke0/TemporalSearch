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
    LocalDateTime startDate,
    Optional<LocalDateTime> endDate,
    Optional<String> qualifiedVariableName,
    Optional<TemporalRange> range,
    TemporalPredicate temporalType
) implements Condition {
    
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
    
    /**
     * Creates a temporal condition with validation.
     */
    public Temporal {
        Objects.requireNonNull(startDate, "Start date cannot be null");
        endDate = endDate != null ? endDate : Optional.empty();
        qualifiedVariableName = qualifiedVariableName != null ? qualifiedVariableName : Optional.empty();
        range = range != null ? range : Optional.empty();
        Objects.requireNonNull(temporalType, "Temporal type cannot be null");
    }
    
    /**
     * Constructor for simple date comparison.
     */
    public Temporal(ComparisonType comparisonType, int year) {
        this(LocalDateTime.of(year, 1, 1, 0, 0),
             Optional.empty(),
             Optional.empty(),
             Optional.empty(),
             comparisonType.getTemporalPredicate());
    }
    
    /**
     * Constructor for simple date comparison with a variable.
     */
    public Temporal(ComparisonType comparisonType, int year, String variableName) {
        this(LocalDateTime.of(year, 1, 1, 0, 0),
             Optional.empty(),
             Optional.of(variableName),
             Optional.empty(),
             comparisonType.getTemporalPredicate());
    }
    
    /**
     * Constructor for simple temporal condition.
     */
    public Temporal(TemporalPredicate type, LocalDateTime startDate) {
        this(startDate, Optional.empty(), Optional.empty(), Optional.empty(), type);
    }
    
    /**
     * Constructor for date range condition.
     */
    public Temporal(LocalDateTime startDate, LocalDateTime endDate) {
        this(startDate, Optional.of(endDate), Optional.empty(), Optional.empty(), TemporalPredicate.CONTAINS);
    }
    
    /**
     * Constructor for date range condition with specific temporal type.
     */
    public Temporal(TemporalPredicate type, LocalDateTime startDate, LocalDateTime endDate) {
        this(startDate, Optional.of(endDate), Optional.empty(), Optional.empty(), type);
    }
    
    /**
     * Constructor for temporal condition with range.
     */
    public Temporal(TemporalPredicate type, LocalDateTime date, String range) {
        this(date, Optional.empty(), Optional.empty(), Optional.of(new TemporalRange(range)), type);
    }
    
    /**
     * Constructor for temporal condition with range and variable.
     */
    public Temporal(TemporalPredicate type, LocalDateTime date, Optional<TemporalRange> range, String variableName) {
        this(date, Optional.empty(), Optional.of(variableName), range, type);
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
        
        LocalDate startDate = LocalDate.parse(parts[0].trim(), NASH_DATE_FORMAT);
        LocalDate endDate = LocalDate.parse(parts[1].trim(), NASH_DATE_FORMAT);
        
        return new Temporal(
            startDate.atStartOfDay(),
            Optional.of(endDate.atStartOfDay()),
            Optional.empty(),
            Optional.empty(),
            type
        );
    }
    
    /**
     * Converts this Temporal to a Nash-compatible interval string.
     * 
     * @return A string in the format [YYYY-MM-DD , YYYY-MM-DD]
     */
    public String toNashInterval() {
        LocalDateTime start = this.startDate();
        LocalDateTime end = this.endDate().orElse(start);
        
        return String.format("[%s , %s]", 
                start.format(NASH_DATE_FORMAT),
                end.format(NASH_DATE_FORMAT));
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
        if (temporalType == TemporalPredicate.INTERSECT && startDate != null && endDate.isPresent()) {
            // Handle date comparison operators by showing the original comparison
            // This requires mapping back from the internal representation, which is complex.
            // For simplicity, show the internal INTERSECT format.
            sb.append(temporalType.name()).append(" ");
            sb.append("[").append(NASH_DATE_FORMAT.format(startDate));
            sb.append(", ").append(NASH_DATE_FORMAT.format(endDate.get())).append("]");
        } else {
            sb.append(temporalType.name());
            if (startDate != null) {
                 sb.append(" ").append(NASH_DATE_FORMAT.format(startDate));
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
} 