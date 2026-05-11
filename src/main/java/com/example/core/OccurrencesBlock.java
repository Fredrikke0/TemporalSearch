package com.example.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.roaringbitmap.longlong.Roaring64NavigableMap;

/**
 * A CSR (Compressed Sparse Row) structure storing per-cell in-sentence
 * character offsets for a single term.
 *
 * <p>
 * The structure maps from a cell key — a packed {@code long} combining
 * {@code docId} (upper 32 bits) and {@code sentId} (lower 32 bits) — to the
 * list of character begin offsets within that sentence. The term length is
 * stored separately as a constant, since every occurrence of the same term has
 * the same character length.
 * </p>
 *
 * <h3>Invariants</h3>
 * <ul>
 * <li>{@code cellKeys} sorted strictly ascending; length = numCells</li>
 * <li>{@code cellOffsets} length = numCells + 1; {@code cellOffsets[0] == 0};
 * monotonically non-decreasing</li>
 * <li>{@code begins} has unsigned byte values (0..255); within each cell
 * segment
 * {@code begins[cellOffsets[i] .. cellOffsets[i+1]-1]}, values are strictly
 * increasing</li>
 * </ul>
 */
public class OccurrencesBlock {

    /** Packed cell keys: {@code ((long) docId << 32) | (sentId & 0xFFFF_FFFFL)}. */
    private final long[] cellKeys;

    /**
     * Offsets into {@code begins}. Length is {@code cellKeys.length + 1}.
     * {@code cellOffsets[i+1] - cellOffsets[i]} gives the occurrence count for
     * cell {@code i}.
     */
    private final int[] cellOffsets;

    /** Unsigned byte begin offsets, ascending per cell. */
    private final byte[] begins;

    /** The fixed character length of every occurrence for this term. */
    private final byte constantLength;

    /**
     * Constructs an OccurrencesBlock from already-validated CSR arrays.
     *
     * @param cellKeys       sorted ascending, length = numCells
     * @param cellOffsets    length = numCells + 1, cellOffsets[0] == 0,
     *                       monotonically non-decreasing
     * @param begins         unsigned byte array of begin offsets, ascending per
     *                       cell
     * @param constantLength fixed character length of the term
     * @throws IllegalArgumentException if any invariant is violated
     */
    public OccurrencesBlock(long[] cellKeys, int[] cellOffsets, byte[] begins, byte constantLength) {
        if (cellKeys == null) {
            throw new IllegalArgumentException("cellKeys must not be null");
        }
        if (cellOffsets == null) {
            throw new IllegalArgumentException("cellOffsets must not be null");
        }
        if (begins == null) {
            throw new IllegalArgumentException("begins must not be null");
        }
        if (cellOffsets.length != cellKeys.length + 1) {
            throw new IllegalArgumentException(
                    "cellOffsets length (" + cellOffsets.length + ") must be cellKeys length + 1 ("
                            + (cellKeys.length + 1) + ")");
        }
        if (cellOffsets.length > 0 && cellOffsets[0] != 0) {
            throw new IllegalArgumentException("cellOffsets[0] must be 0, was " + cellOffsets[0]);
        }
        if (cellOffsets.length > 0 && cellOffsets[cellOffsets.length - 1] != begins.length) {
            throw new IllegalArgumentException(
                    "Last cellOffsets entry (" + cellOffsets[cellOffsets.length - 1]
                            + ") must equal begins length (" + begins.length + ")");
        }

        // Validate cellKeys sorted ascending
        for (int i = 1; i < cellKeys.length; i++) {
            if (cellKeys[i] <= cellKeys[i - 1]) {
                throw new IllegalArgumentException(
                        "cellKeys must be strictly ascending; cellKeys[" + i + "]="
                                + cellKeys[i] + " <= cellKeys[" + (i - 1) + "]=" + cellKeys[i - 1]);
            }
        }

        // Validate cellOffsets monotonically non-decreasing
        for (int i = 1; i < cellOffsets.length; i++) {
            if (cellOffsets[i] < cellOffsets[i - 1]) {
                throw new IllegalArgumentException(
                        "cellOffsets must be monotonically non-decreasing; cellOffsets[" + i + "]="
                                + cellOffsets[i] + " < cellOffsets[" + (i - 1) + "]=" + cellOffsets[i - 1]);
            }
        }

        // Validate begins ascending within each cell
        for (int i = 0; i < cellKeys.length; i++) {
            int start = cellOffsets[i];
            int end = cellOffsets[i + 1];
            for (int j = start + 1; j < end; j++) {
                int prev = Byte.toUnsignedInt(begins[j - 1]);
                int curr = Byte.toUnsignedInt(begins[j]);
                if (curr <= prev) {
                    throw new IllegalArgumentException(
                            "begins must be strictly ascending within cell " + i
                                    + "; begins[" + j + "]=" + curr + " <= begins[" + (j - 1) + "]=" + prev);
                }
            }
        }

        this.cellKeys = cellKeys;
        this.cellOffsets = cellOffsets;
        this.begins = begins;
        this.constantLength = constantLength;
    }

    // ---------- internal trusted constructor ----------

    /**
     * Package-private constructor that bypasses validation. Intended for internal
     * use when the data is already known to be valid (e.g. during deserialization
     * or intersection).
     */
    OccurrencesBlock(long[] cellKeys, int[] cellOffsets, byte[] begins, byte constantLength, boolean trusted) {
        this.cellKeys = cellKeys;
        this.cellOffsets = cellOffsets;
        this.begins = begins;
        this.constantLength = constantLength;
    }

    // ---------- accessors ----------

    /** Returns the number of cells. */
    public int numCells() {
        return cellKeys.length;
    }

    /**
     * Returns the packed cell key at index {@code i}.
     *
     * @param i cell index
     * @return {@code ((long) docId << 32) | sentId}
     */
    public long cellKey(int i) {
        return cellKeys[i];
    }

    /**
     * Returns a non-allocating slice view over the i-th cell's begin offsets.
     *
     * @param i cell index
     * @return an {@link OccurrencesView} for the cell
     */
    public OccurrencesView occurrences(int i) {
        int offset = cellOffsets[i];
        int length = cellOffsets[i + 1] - offset;
        return new OccurrencesView(begins, offset, length, constantLength);
    }

    /**
     * Returns the fixed character length for this term.
     */
    public byte constantLength() {
        return constantLength;
    }

    // ---------- serialization ----------

    /**
     * Serializes this block to a byte array.
     *
     * <p>
     * Format (big-endian, compatible with DataOutputStream):
     * </p>
     *
     * <pre>
     *   byte constantLength      (1 byte)
     *   int  numCells            (4 bytes)
     *   for each cell:
     *     long cellKey           (8 bytes)
     *   for each cell:
     *     int  occurrenceCount   (4 bytes)   — so cellOffsets can be reconstructed
     *   int  totalBegins         (4 bytes)
     *   byte[] begins            (totalBegins bytes)
     * </pre>
     *
     * @return the serialized form as a byte array
     */
    public byte[] serialize() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeByte(constantLength);
            dos.writeInt(cellKeys.length);
            for (long key : cellKeys) {
                dos.writeLong(key);
            }
            // Write per-cell occurrence counts so cellOffsets can be reconstructed
            for (int i = 0; i < cellKeys.length; i++) {
                dos.writeInt(cellOffsets[i + 1] - cellOffsets[i]);
            }
            dos.writeInt(begins.length);
            dos.write(begins);
            dos.flush();
        } catch (IOException e) {
            // ByteArrayOutputStream does not throw IOException
            throw new RuntimeException("Unexpected IOException during serialization", e);
        }
        return baos.toByteArray();
    }

    /**
     * Deserializes an OccurrencesBlock from a byte array in the format produced
     * by {@link #serialize()}.
     *
     * @param data the serialized bytes
     * @return a new OccurrencesBlock
     * @throws IllegalArgumentException if the data is malformed
     */
    public static OccurrencesBlock deserialize(byte[] data) {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);
        try {
            byte constantLength = dis.readByte();
            int numCells = dis.readInt();
            if (numCells < 0) {
                throw new IllegalArgumentException("Invalid numCells: " + numCells);
            }

            long[] cellKeys = new long[numCells];
            for (int i = 0; i < numCells; i++) {
                cellKeys[i] = dis.readLong();
            }

            // Read per-cell occurrence counts and reconstruct cellOffsets
            int[] cellOffsets = new int[numCells + 1];
            cellOffsets[0] = 0;
            int totalBegins = 0;
            for (int i = 0; i < numCells; i++) {
                int count = dis.readInt();
                if (count < 0) {
                    throw new IllegalArgumentException(
                            "Invalid occurrence count for cell " + i + ": " + count);
                }
                totalBegins += count;
                cellOffsets[i + 1] = totalBegins;
            }

            int storedTotalBegins = dis.readInt();
            if (storedTotalBegins != totalBegins) {
                throw new IllegalArgumentException(
                        "totalBegins mismatch: stored=" + storedTotalBegins
                                + ", computed=" + totalBegins);
            }

            byte[] begins = new byte[totalBegins];
            dis.readFully(begins);

            return new OccurrencesBlock(cellKeys, cellOffsets, begins, constantLength, true);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to deserialize OccurrencesBlock", e);
        }
    }

    // ---------- intersect ----------

    /**
     * Returns a new OccurrencesBlock containing only the cells whose keys are
     * present in {@code matchedCells}, preserving relative ordering.
     *
     * @param matchedCells the set of cell keys to retain
     * @return a narrowed OccurrencesBlock, or {@code null} if the result would
     *         be empty
     */
    public OccurrencesBlock intersect(Roaring64NavigableMap matchedCells) {
        if (matchedCells == null || matchedCells.isEmpty()) {
            return null;
        }

        // First pass: count surviving cells
        int survivingCount = 0;
        for (long key : cellKeys) {
            if (matchedCells.contains(key)) {
                survivingCount++;
            }
        }

        if (survivingCount == 0) {
            return null;
        }

        long[] newKeys = new long[survivingCount];
        int[] newOffsets = new int[survivingCount + 1];

        // We'll collect the surviving begins segments and later copy into a
        // single array. Using int[][] as intermediate to avoid boxing.
        int[][] survivingSegments = new int[survivingCount][];
        int totalBegins = 0;

        int outIdx = 0;
        for (int i = 0; i < cellKeys.length; i++) {
            if (matchedCells.contains(cellKeys[i])) {
                newKeys[outIdx] = cellKeys[i];
                int start = cellOffsets[i];
                int end = cellOffsets[i + 1];
                int count = end - start;
                newOffsets[outIdx] = totalBegins;
                // Collect the segment as ints (convert to byte later)
                int[] segment = new int[count];
                for (int j = 0; j < count; j++) {
                    segment[j] = Byte.toUnsignedInt(begins[start + j]);
                }
                survivingSegments[outIdx] = segment;
                totalBegins += count;
                outIdx++;
            }
        }
        newOffsets[survivingCount] = totalBegins;

        // Flatten begins
        byte[] newBegins = new byte[totalBegins];
        int dest = 0;
        for (int[] seg : survivingSegments) {
            for (int b : seg) {
                newBegins[dest++] = (byte) b;
            }
        }

        return new OccurrencesBlock(newKeys, newOffsets, newBegins, constantLength, true);
    }

    // ---------- merge ----------

    /**
     * Merges two OccurrencesBlocks by performing a sorted merge of their cell
     * keys and concatenating the begins arrays. Cells that appear in both are
     * merged by concatenating their begins (with dedup). Both must have the
     * same constantLength.
     *
     * @param a first block
     * @param b second block
     * @return a merged OccurrencesBlock
     */
    public static OccurrencesBlock merge(OccurrencesBlock a, OccurrencesBlock b) {
        if (a == null || a.numCells() == 0)
            return b;
        if (b == null || b.numCells() == 0)
            return a;

        // Two-pointer merge of sorted cell key arrays
        int totalCells = a.numCells() + b.numCells();
        long[] mergedKeys = new long[totalCells];
        int[] mergedOffsets = new int[totalCells + 1];

        // For the begins, we need to track per-cell data
        java.util.List<byte[]> mergedSegments = new java.util.ArrayList<>(totalCells);

        int i = 0, j = 0;
        int totalBegins = 0;
        int outIdx = 0;

        while (i < a.numCells() || j < b.numCells()) {
            long cellKey;
            byte[] segment;

            if (i < a.numCells() && j < b.numCells()) {
                long ka = a.cellKey(i);
                long kb = b.cellKey(j);
                if (ka < kb) {
                    cellKey = ka;
                    segment = extractSegment(a, i);
                    i++;
                } else if (kb < ka) {
                    cellKey = kb;
                    segment = extractSegment(b, j);
                    j++;
                } else {
                    // Same cell: merge segments (dedup by begin offset)
                    cellKey = ka;
                    segment = mergeCellSegments(
                            extractSegment(a, i),
                            extractSegment(b, j));
                    i++;
                    j++;
                }
            } else if (i < a.numCells()) {
                cellKey = a.cellKey(i);
                segment = extractSegment(a, i);
                i++;
            } else {
                cellKey = b.cellKey(j);
                segment = extractSegment(b, j);
                j++;
            }

            mergedKeys[outIdx] = cellKey;
            mergedOffsets[outIdx] = totalBegins;
            mergedSegments.add(segment);
            totalBegins += segment.length;
            outIdx++;
        }
        mergedOffsets[outIdx] = totalBegins;

        // Trim arrays if there were merged cells
        if (outIdx < totalCells) {
            long[] trimmedKeys = new long[outIdx];
            System.arraycopy(mergedKeys, 0, trimmedKeys, 0, outIdx);
            mergedKeys = trimmedKeys;
            int[] trimmedOffsets = new int[outIdx + 1];
            System.arraycopy(mergedOffsets, 0, trimmedOffsets, 0, outIdx + 1);
            mergedOffsets = trimmedOffsets;
        }

        // Flatten begins
        byte[] flatBegins = new byte[totalBegins];
        int dest = 0;
        for (byte[] seg : mergedSegments) {
            System.arraycopy(seg, 0, flatBegins, dest, seg.length);
            dest += seg.length;
        }

        byte cl = a.constantLength != 0 ? a.constantLength : b.constantLength;
        return new OccurrencesBlock(mergedKeys, mergedOffsets, flatBegins, cl, true);
    }

    private static byte[] extractSegment(OccurrencesBlock block, int cellIdx) {
        int start = block.cellOffsets[cellIdx];
        int end = block.cellOffsets[cellIdx + 1];
        int len = end - start;
        byte[] seg = new byte[len];
        System.arraycopy(block.begins, start, seg, 0, len);
        return seg;
    }

    private static byte[] mergeCellSegments(byte[] s1, byte[] s2) {
        // Both are already sorted; merge and dedup
        int total = s1.length + s2.length;
        byte[] merged = new byte[total];
        int i = 0, j = 0, k = 0;
        while (i < s1.length && j < s2.length) {
            int v1 = Byte.toUnsignedInt(s1[i]);
            int v2 = Byte.toUnsignedInt(s2[j]);
            if (v1 < v2) {
                merged[k++] = s1[i++];
            } else if (v2 < v1) {
                merged[k++] = s2[j++];
            } else {
                merged[k++] = s1[i++]; // dedup
                j++;
            }
        }
        while (i < s1.length)
            merged[k++] = s1[i++];
        while (j < s2.length)
            merged[k++] = s2[j++];
        if (k < total) {
            byte[] trimmed = new byte[k];
            System.arraycopy(merged, 0, trimmed, 0, k);
            return trimmed;
        }
        return merged;
    }

    // ---------- static factory: fromUnsorted ----------

    /**
     * Creates an OccurrencesBlock from unsorted cell keys and their associated
     * begin offset lists.
     *
     * @param unsortedKeys   cell keys, may be in any order
     * @param beginsPerCell  parallel list; each element is a sorted byte array of
     *                       begin offsets for the corresponding cell
     * @param constantLength fixed character length of the term
     * @return a validated OccurrencesBlock with sorted keys
     */
    public static OccurrencesBlock fromUnsorted(long[] unsortedKeys, byte[][] beginsPerCell, byte constantLength) {
        if (unsortedKeys == null) {
            throw new IllegalArgumentException("unsortedKeys must not be null");
        }
        if (beginsPerCell == null) {
            throw new IllegalArgumentException("beginsPerCell must not be null");
        }
        if (unsortedKeys.length != beginsPerCell.length) {
            throw new IllegalArgumentException(
                    "unsortedKeys length (" + unsortedKeys.length
                            + ") must equal beginsPerCell length (" + beginsPerCell.length + ")");
        }

        int n = unsortedKeys.length;
        if (n == 0) {
            return new OccurrencesBlock(new long[0], new int[] { 0 }, new byte[0], constantLength, true);
        }

        // Create index array and sort by key
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) {
            indices[i] = i;
        }
        Arrays.sort(indices, (a, b) -> Long.compare(unsortedKeys[a], unsortedKeys[b]));

        long[] sortedKeys = new long[n];
        int[] cellOffsets = new int[n + 1];
        int totalBegins = 0;

        // First pass: compute total begins
        for (int i = 0; i < n; i++) {
            int srcIdx = indices[i];
            sortedKeys[i] = unsortedKeys[srcIdx];
            cellOffsets[i] = totalBegins;
            totalBegins += beginsPerCell[srcIdx].length;
        }
        cellOffsets[n] = totalBegins;

        // Second pass: flatten begins
        byte[] begins = new byte[totalBegins];
        int dest = 0;
        for (int i = 0; i < n; i++) {
            int srcIdx = indices[i];
            byte[] src = beginsPerCell[srcIdx];
            System.arraycopy(src, 0, begins, dest, src.length);
            dest += src.length;
        }

        return new OccurrencesBlock(sortedKeys, cellOffsets, begins, constantLength);
    }

    // ---------- object methods ----------

    @Override
    public String toString() {
        return "OccurrencesBlock{cells=" + cellKeys.length
                + ", begins=" + begins.length
                + ", constantLength=" + constantLength + "}";
    }
}
