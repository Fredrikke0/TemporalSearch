package com.example.index.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for serializing and deserializing data structures used by the Nash index.
 */
public final class NashSerializationUtils {

    // Special key for storing the date lookup table in RocksDB
    public static final byte[] DATE_LOOKUP_KEY = "__DATE_LOOKUP__".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private NashSerializationUtils() {
        // Prevent instantiation
    }

    /**
     * Serializes the date lookup table (List<LocalDate>).
     */
    public static byte[] serializeDateLookup(List<LocalDate> lookup) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeInt(lookup.size()); // Write number of dates
            for (LocalDate date : lookup) {
                dos.writeLong(date.toEpochDay()); // Write date as epoch day
            }
            return baos.toByteArray();
        }
    }

    /**
     * Deserializes the date lookup table (List<LocalDate>).
     */
    public static List<LocalDate> deserializeDateLookup(byte[] data) throws IOException {
        List<LocalDate> lookup = new ArrayList<>();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream dis = new DataInputStream(bais)) {

            int count = dis.readInt(); // Read number of dates
            for (int i = 0; i < count; i++) {
                long epochDay = dis.readLong(); // Read epoch day
                lookup.add(LocalDate.ofEpochDay(epochDay));
            }
        }
        return lookup;
    }
}