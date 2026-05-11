package com.example.core;

import org.roaringbitmap.longlong.Roaring64NavigableMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Two-layer posting list backed by Roaring64 bitmap and CSR occurrence blocks.
 *
 * Layer 1: {@link Roaring64NavigableMap} of (docId, sentId) cells where the
 * term occurs.
 * key = ((long) docId &lt;&lt; 32) | (sentId &amp; 0xFFFF_FFFFL)
 * Layer 2: {@link OccurrencesBlock} with per-cell in-sentence char offsets
 * (null in CELLS_ONLY mode).
 */
public final class PostingList {

    public enum DeserializeMode {
        /** Decode only Layer 1 (cells bitmap). Used by filter propagation and joins. */
        CELLS_ONLY,
        /**
         * Decode Layer 1 + Layer 2 (occurrences). Used by snippet extraction and
         * char-level predicates.
         */
        FULL
    }

    private final Roaring64NavigableMap cells;
    private final byte constantLength;
    private final OccurrencesBlock occurrences; // nullable

    private PostingList(Roaring64NavigableMap cells, byte constantLength, OccurrencesBlock occurrences) {
        this.cells = cells;
        this.constantLength = constantLength;
        this.occurrences = occurrences;
    }

    // --- Factory methods ---

    /** Create a PostingList with only cells (no occurrence data). */
    public static PostingList fromCells(Roaring64NavigableMap cells, byte constantLength) {
        if (cells == null)
            throw new IllegalArgumentException("cells must not be null");
        return new PostingList(cells, constantLength, null);
    }

    /** Create a PostingList with both cells and occurrences. */
    public static PostingList fromCellsAndOccurrences(Roaring64NavigableMap cells, byte constantLength,
            OccurrencesBlock occurrences) {
        if (cells == null)
            throw new IllegalArgumentException("cells must not be null");
        if (occurrences == null)
            throw new IllegalArgumentException("occurrences must not be null for FULL mode");
        return new PostingList(cells, constantLength, occurrences);
    }

    /** Returns an empty PostingList. */
    public static PostingList empty(byte constantLength) {
        return new PostingList(new Roaring64NavigableMap(), constantLength, null);
    }

    /**
     * Merges another PostingList into this one (logical OR of cells, merge
     * occurrences). Both must have the same constantLength.
     *
     * @param other the other PostingList to merge in
     * @return a new merged PostingList
     */
    public PostingList merge(PostingList other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (this.isEmpty()) {
            return other;
        }
        Roaring64NavigableMap mergedCells = this.cells.clone();
        mergedCells.or(other.cells);

        OccurrencesBlock mergedOcc = null;
        // If both have occurrences, merge by concatenating CSR data
        if (this.occurrences != null && other.occurrences != null) {
            mergedOcc = OccurrencesBlock.merge(this.occurrences, other.occurrences);
        } else if (this.occurrences != null) {
            mergedOcc = this.occurrences;
        } else if (other.occurrences != null) {
            mergedOcc = other.occurrences;
        }

        byte cl = this.constantLength != 0 ? this.constantLength : other.constantLength;
        return new PostingList(mergedCells, cl, mergedOcc);
    }

    /**
     * Creates a union of multiple PostingLists. All must have the same
     * constantLength (or 0 which is ignored).
     *
     * @param lists the lists to union
     * @return a merged PostingList
     */
    public static PostingList union(java.util.List<PostingList> lists) {
        if (lists == null || lists.isEmpty()) {
            return empty((byte) 0);
        }
        PostingList result = lists.get(0);
        for (int i = 1; i < lists.size(); i++) {
            result = result.merge(lists.get(i));
        }
        return result;
    }

    // --- Getters ---

    public Roaring64NavigableMap cells() {
        return cells;
    }

    public byte constantLength() {
        return constantLength;
    }

    public OccurrencesBlock occurrences() {
        return occurrences;
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    // --- Serialization ---

    /**
     * Serializes to a byte array.
     * Format:
     * 1 byte constantLength
     * 1 byte flags: bit 0 = hasOccurrences
     * 4 bytes serializedSizeInBytes of cells (int, but we use long and cast)
     * N bytes cells serialized (via Roaring64NavigableMap.serialize)
     * [if hasOccurrences:] N bytes OccurrencesBlock serialized
     */
    public byte[] serialize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeByte(constantLength);
            boolean hasOcc = occurrences != null;
            dos.writeByte(hasOcc ? 1 : 0);
            // Roaring64NavigableMap serializes via serialize(DataOutput)
            cells.serialize(dos);
            if (hasOcc) {
                byte[] occBytes = occurrences.serialize();
                dos.writeInt(occBytes.length);
                dos.write(occBytes);
            }
        }
        return baos.toByteArray();
    }

    /**
     * Deserializes from a byte array in CELLS_ONLY mode (backward compat).
     */
    public static PostingList deserialize(byte[] data) throws IOException {
        return deserialize(data, DeserializeMode.CELLS_ONLY);
    }

    /**
     * Deserializes a PostingList from a byte array using the specified mode.
     * In CELLS_ONLY mode, occurrences data is skipped.
     */
    public static PostingList deserialize(byte[] data, DeserializeMode mode) throws IOException {
        if (data == null || data.length == 0) {
            return empty((byte) 0);
        }
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            byte constLen = dis.readByte();
            byte flags = dis.readByte();
            boolean hasOcc = (flags & 1) != 0;

            Roaring64NavigableMap cells = new Roaring64NavigableMap();
            cells.deserialize(dis);

            OccurrencesBlock occurrences = null;
            if (hasOcc && mode == DeserializeMode.FULL) {
                int occLen = dis.readInt();
                byte[] occBytes = new byte[occLen];
                dis.readFully(occBytes);
                occurrences = OccurrencesBlock.deserialize(occBytes);
            }

            return new PostingList(cells, constLen, occurrences);
        }
    }

    // --- Cell packing helpers ---

    /** Pack (docId, sentId) into a single long cell key. */
    public static long packCellKey(int docId, int sentId) {
        return ((long) docId << 32) | (sentId & 0xFFFF_FFFFL);
    }

    /** Extract docId from a cell key. */
    public static int docIdFromCellKey(long cellKey) {
        return (int) (cellKey >>> 32);
    }

    /** Extract sentId from a cell key. */
    public static int sentIdFromCellKey(long cellKey) {
        return (int) cellKey;
    }

    @Override
    public String toString() {
        return "PostingList{cells=" + cells.getLongCardinality()
                + ", constantLength=" + constantLength
                + ", occurrences=" + (occurrences != null ? occurrences.numCells() + " cells" : "none") + "}";
    }
}
