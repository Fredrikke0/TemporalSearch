package com.example.query.model;

import java.time.LocalDate;

/**
 * Global lower and upper bounds for temporal queries.
 *
 * <p>These bounds define the supported date range across the entire
 * temporal query subsystem (date-index range scans, semantic validation,
 * stitch filtering).
 */
public final class TemporalBounds {
    public static final LocalDate LOWER = LocalDate.of(1925, 1, 1);
    public static final LocalDate UPPER = LocalDate.of(2025, 12, 31);

    private TemporalBounds() {}
}
