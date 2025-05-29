package com.example.index.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.example.core.Position;
import com.example.index.NashDateEntryWithId;

/**
 * Utility class for serializing and deserializing data structures used by the Nash index.
 */
public final class NashSerializationUtils {

    // Special key for storing the date lookup table in LevelDB
    public static final byte[] DATE_LOOKUP_KEY = "__DATE_LOOKUP__".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private NashSerializationUtils() {
        // Prevent instantiation
    }

    /**
     * Serializes a list of NashDateEntryWithId objects using DataOutputStream.
     */
    public static byte[] serializeNashEntries(List<NashDateEntryWithId> entries) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeInt(entries.size()); // Write number of entries
            for (NashDateEntryWithId entry : entries) {
                Position pos = entry.position();
                // Write Position fields directly
                dos.writeInt(pos.getDocumentId());
                dos.writeInt(pos.getSentenceId());
                dos.writeInt(pos.getBeginPosition());
                dos.writeInt(pos.getEndPosition());

                dos.writeInt(entry.dateId());     // Write date ID
            }
            return baos.toByteArray();
        }
    }

    /**
     * Deserializes a list of NashDateEntryWithId objects using DataInputStream.
     */
    public static List<NashDateEntryWithId> deserializeNashEntries(byte[] data) throws IOException {
        List<NashDateEntryWithId> entries = new ArrayList<>();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream dis = new DataInputStream(bais)) {

            int count = dis.readInt(); // Read number of entries
            for (int i = 0; i < count; i++) {
                // Read Position fields directly
                int docId = dis.readInt();
                int sentId = dis.readInt();
                int beginPos = dis.readInt();
                int endPos = dis.readInt();

                Position position = new Position(docId, sentId, beginPos, endPos);

                int dateId = dis.readInt();     // Read date ID
                entries.add(new NashDateEntryWithId(position, dateId));
            }
        }
        return entries;
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