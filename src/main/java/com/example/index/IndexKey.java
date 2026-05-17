package com.example.index;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Immutable, value-typed key for RocksDB indexes. Wraps a {@code byte[]}
 * with structural equality ({@link #equals} / {@link #hashCode} on array
 * contents) and {@link Comparable} ordering (unsigned bytewise, matching
 * RocksDB's default comparator).
 *
 * <p>
 * The canonical string form is Base64, used for debugging and logging.
 * The temp-file format uses length-prefixed raw bytes via
 * {@link com.example.index.BinarySortedFile}. No charset conversion is
 * ever performed on the key bytes — Base64 is pure ASCII.
 *
 * <h3>Factory methods</h3>
 * <ul>
 * <li>{@link #fromBytes(byte[])} — wraps raw bytes (e.g. from
 * {@link KeySchema#encodeKey} or {@link KeySchema#encodeStitchKey})</li>
 * <li>{@link #fromUtf8(String)} — for plain-text keys (n-grams, dates,
 * dependency relations) where the natural UTF-8 encoding is correct</li>
 * <li>{@link #fromBase64(String)} — deserialises from the temp-file
 * format</li>
 * </ul>
 */
public final class IndexKey implements Comparable<IndexKey> {

    private final byte[] bytes;
    private final int hash;

    private IndexKey(byte[] bytes) {
        this.bytes = bytes.clone();
        this.hash = Arrays.hashCode(bytes);
    }

    // ---------------------------------------------------------------- factories

    /** Wraps an already-encoded binary key (e.g. from {@link KeySchema}). */
    public static IndexKey fromBytes(byte[] keyBytes) {
        return new IndexKey(keyBytes);
    }

    /** Creates a key from a plain UTF-8 string (n-grams, dates, etc.). */
    public static IndexKey fromUtf8(String key) {
        return new IndexKey(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Deserialises a key from its Base64 representation (the temp-file
     * format).
     */
    public static IndexKey fromBase64(String base64) {
        return new IndexKey(Base64.getDecoder().decode(base64));
    }

    // ------------------------------------------------------- accessors

    /** The raw key bytes (defensive copy). */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** Number of bytes in the key. */
    public int length() {
        return bytes.length;
    }

    /** Base64-encoded form (used in temp files). */
    public String toBase64() {
        return Base64.getEncoder().encodeToString(bytes);
    }

    // ----------------------------------------------- Object / Comparable

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof IndexKey other))
            return false;
        return Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * Unsigned bytewise comparison, matching the default RocksDB comparator
     * and the existing {@code compareByteArrays} helper.
     */
    @Override
    public int compareTo(IndexKey other) {
        return compareBytes(this.bytes, other.bytes);
    }

    /**
     * Unsigned bytewise comparison of two raw key byte arrays.
     * Used by the binary merge to compare keys without wrapping them in IndexKey
     * objects.
     */
    public static int compareBytes(byte[] a, byte[] b) {
        int min = Math.min(a.length, b.length);
        for (int i = 0; i < min; i++) {
            int va = a[i] & 0xFF;
            int vb = b[i] & 0xFF;
            if (va != vb)
                return va - vb;
        }
        return a.length - b.length;
    }

    /** Debug-friendly: shows Base64 and byte count. */
    @Override
    public String toString() {
        return "IndexKey[" + bytes.length + "b]:" + toBase64();
    }
}
