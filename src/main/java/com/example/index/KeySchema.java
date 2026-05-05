package com.example.index;

import java.nio.charset.StandardCharsets;

import com.example.core.IndexAccessInterface;

/**
 * Key schema utilities for the new posting list format.
 *
 * <p>
 * Indexes that previously carried {@code synonymId} in the value blob now
 * encode it as a 4-byte big-endian suffix on the key. This affects NER, POS,
 * and all stitch generators.
 * </p>
 *
 * <h3>NER/POS key format</h3>
 * 
 * <pre>{@code
 *   <TYPE> \0 <4-byte BE synId>
 * }</pre>
 *
 * <h3>Stitch key format</h3>
 * 
 * <pre>{@code
 *   <NGRAM_KEY> \0 <TYPE> \0 <4-byte BE synId>
 * }</pre>
 */
public final class KeySchema {

    private static final char DELIM = IndexAccessInterface.DELIMITER; // '\0'

    private KeySchema() {
        /* utility class */ }

    // --- NER/POS keys ---

    /**
     * Encodes a NER or POS key: {@code type\0synId}.
     *
     * @param type  the entity type (e.g. "PERSON") or POS tag (e.g. "NN")
     * @param synId the synonym ID (non-negative)
     * @return the key bytes
     */
    public static byte[] encodeKey(String type, int synId) {
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[typeBytes.length + 1 + 4];
        System.arraycopy(typeBytes, 0, key, 0, typeBytes.length);
        key[typeBytes.length] = (byte) DELIM;
        writeIntBE(synId, key, typeBytes.length + 1);
        return key;
    }

    /**
     * Encodes a NER or POS prefix (for prefix scans): {@code type\0}.
     * The caller can scan from this prefix to enumerate all synIds for a type.
     */
    public static byte[] encodeTypePrefix(String type) {
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[typeBytes.length + 1];
        System.arraycopy(typeBytes, 0, key, 0, typeBytes.length);
        key[typeBytes.length] = (byte) DELIM;
        return key;
    }

    /**
     * Decodes a NER/POS key into its components.
     *
     * @param key the raw key bytes
     * @return a {@link DecodedKey} record
     * @throws IllegalArgumentException if the key format is invalid
     */
    public static DecodedKey decodeKey(byte[] key) {
        // Find the delimiter
        int delimPos = -1;
        for (int i = 0; i < key.length - 4; i++) {
            if (key[i] == (byte) DELIM) {
                delimPos = i;
                break;
            }
        }
        if (delimPos < 0 || delimPos + 5 > key.length) {
            throw new IllegalArgumentException(
                    "Invalid NER/POS key: expected <type>\\0<4-byte synId>, got " + key.length + " bytes");
        }
        String type = new String(key, 0, delimPos, StandardCharsets.UTF_8);
        int synId = readIntBE(key, delimPos + 1);
        return new DecodedKey(type, synId);
    }

    /** A decoded NER/POS key. */
    public record DecodedKey(String type, int synId) {
    }

    // --- Stitch keys ---

    /**
     * Encodes a stitch key: {@code ngramKey\0type\0synId}.
     *
     * @param ngramKey the n-gram key (space-separated tokens)
     * @param type     the annotation type (e.g. "NER" or "DATE")
     * @param synId    the synonym ID of the annotation value
     */
    public static byte[] encodeStitchKey(String ngramKey, String type, int synId) {
        byte[] ngramBytes = ngramKey.getBytes(StandardCharsets.UTF_8);
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[ngramBytes.length + 1 + typeBytes.length + 1 + 4];
        int pos = 0;
        System.arraycopy(ngramBytes, 0, key, pos, ngramBytes.length);
        pos += ngramBytes.length;
        key[pos++] = (byte) DELIM;
        System.arraycopy(typeBytes, 0, key, pos, typeBytes.length);
        pos += typeBytes.length;
        key[pos++] = (byte) DELIM;
        writeIntBE(synId, key, pos);
        return key;
    }

    /**
     * Encodes a stitch prefix for prefix scans: {@code ngramKey\0type\0}.
     */
    public static byte[] encodeStitchPrefix(String ngramKey, String type) {
        byte[] ngramBytes = ngramKey.getBytes(StandardCharsets.UTF_8);
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[ngramBytes.length + 1 + typeBytes.length + 1];
        int pos = 0;
        System.arraycopy(ngramBytes, 0, key, pos, ngramBytes.length);
        pos += ngramBytes.length;
        key[pos++] = (byte) DELIM;
        System.arraycopy(typeBytes, 0, key, pos, typeBytes.length);
        pos += typeBytes.length;
        key[pos] = (byte) DELIM;
        return key;
    }

    /**
     * Decodes a stitch key.
     */
    public static DecodedStitchKey decodeStitchKey(byte[] key) {
        // Find first delimiter (after ngramKey)
        int delim1 = -1;
        for (int i = 0; i < key.length - 5; i++) {
            if (key[i] == (byte) DELIM) {
                delim1 = i;
                break;
            }
        }
        if (delim1 < 0) {
            throw new IllegalArgumentException("Invalid stitch key: missing first delimiter");
        }
        // Find second delimiter (after type)
        int delim2 = -1;
        for (int i = delim1 + 1; i < key.length - 4; i++) {
            if (key[i] == (byte) DELIM) {
                delim2 = i;
                break;
            }
        }
        if (delim2 < 0 || delim2 + 5 > key.length) {
            throw new IllegalArgumentException(
                    "Invalid stitch key: expected <ngram>\\0<type>\\0<4-byte synId>");
        }
        String ngramKey = new String(key, 0, delim1, StandardCharsets.UTF_8);
        String type = new String(key, delim1 + 1, delim2 - delim1 - 1, StandardCharsets.UTF_8);
        int synId = readIntBE(key, delim2 + 1);
        return new DecodedStitchKey(ngramKey, type, synId);
    }

    /** A decoded stitch key. */
    public record DecodedStitchKey(String ngramKey, String type, int synId) {
    }

    // --- Private helpers ---

    private static void writeIntBE(int value, byte[] dest, int offset) {
        dest[offset] = (byte) (value >>> 24);
        dest[offset + 1] = (byte) (value >>> 16);
        dest[offset + 2] = (byte) (value >>> 8);
        dest[offset + 3] = (byte) value;
    }

    private static int readIntBE(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 24)
                | ((src[offset + 1] & 0xFF) << 16)
                | ((src[offset + 2] & 0xFF) << 8)
                | (src[offset + 3] & 0xFF);
    }
}
