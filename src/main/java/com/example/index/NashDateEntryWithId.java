package com.example.index;

import com.example.core.Position;

/**
 * Represents an entry for the Nash index, associating a Position with a date ID.
 * This date ID corresponds to an entry in the date lookup table (idToDate list in NashIndexGenerator).
 */
public class NashDateEntryWithId {
    // Fields are deliberately package-private or have public getters for access by NashSerializationUtils
    // and NashIndexGenerator if they were in the same package. Making them public for simplicity if used across packages.
    final Position position;
    final int dateId;

    public NashDateEntryWithId(Position position, int dateId) {
        this.position = position;
        this.dateId = dateId;
    }

    public Position position() { // Public getter
        return position;
    }

    public int dateId() { // Public getter
        return dateId;
    }

    // Consider adding equals, hashCode, and toString if these objects are stored in collections
    // or used in ways that require these methods.
}