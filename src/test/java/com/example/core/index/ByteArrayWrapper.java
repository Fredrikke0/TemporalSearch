package com.example.core.index;

import java.util.Arrays;

/**
 * A wrapper class for byte arrays to allow them to be used as keys in Maps.
 * Implements equals() and hashCode() based on the array content.
 * Implements Comparable for use in sorted maps like TreeMap.
 */
public final class ByteArrayWrapper implements Comparable<ByteArrayWrapper> {
    private final byte[] data;

    public ByteArrayWrapper(byte[] data) {
        this.data = Arrays.copyOf(data, data.length); // Defensive copy
    }

    public byte[] getData() {
        return Arrays.copyOf(data, data.length); // Return defensive copy
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        ByteArrayWrapper that = (ByteArrayWrapper) other;
        return Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public int compareTo(ByteArrayWrapper other) {
        return Arrays.compare(this.data, other.data);
    }

    @Override
    public String toString() {
        // Provide a readable representation, e.g., hex or UTF-8 if appropriate
        // For simplicity, just show length or use Arrays.toString
        return "ByteArrayWrapper[" + Arrays.toString(data) + "]";
    }
}