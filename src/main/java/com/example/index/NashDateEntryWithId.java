package com.example.index;

import com.example.core.Position;

/**
 * Represents an entry for the Nash index, associating a Position with a date ID.
 * This date ID corresponds to an entry in the date lookup table (idToDate list in NashIndexGenerator).
 */
public class NashDateEntryWithId {
    final Position position;
    final int dateId;

    public NashDateEntryWithId(Position position, int dateId) {
        this.position = position;
        this.dateId = dateId;
    }

    public Position position() {
        return position;
    }

    public int dateId() {
        return dateId;
    }
}