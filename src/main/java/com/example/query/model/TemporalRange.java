package com.example.query.model;

import java.util.Objects;

/**
 * Represents a temporal range specification in a temporal condition.
 */
public record TemporalRange(String value) {
    public TemporalRange {
        Objects.requireNonNull(value, "Range value cannot be null");
    }

    @Override
    public String toString() {
        return value;
    }
}