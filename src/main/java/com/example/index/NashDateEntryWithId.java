package com.example.index;

import com.example.core.Position;
import java.time.LocalDate;

/**
 * Record holding a position and the ID of the specific date associated with it,
 * used within the Nash index structure.
 */
public record NashDateEntryWithId(Position position, int dateId) {} 