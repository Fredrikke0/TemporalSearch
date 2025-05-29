package com.example.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

import com.example.index.StitchPosition;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntComparator;
import me.lemire.integercompression.FastPFOR128;
import me.lemire.integercompression.IntWrapper;
import me.lemire.integercompression.IntegerCODEC;
import me.lemire.integercompression.differential.Delta;

/**
 * Manages collections of position data using a Structure of Arrays (SoA) approach.
 * This class is designed for memory efficiency and selective attribute access,
 * particularly for large datasets.
 *
 * See design/root_problems/positionlist-blobs.md for detailed design.
 */
public class PositionListSoA {

    private IntArrayList documentIds;
    private IntArrayList sentenceIds;
    private IntArrayList beginChars;
    private IntArrayList endChars;
    private IntArrayList synonymIds; // Always present. Value is -1 for non-stitch positions.

    private int numPositions;        // Number of logical positions stored, also the size of each active list

    private static final IntegerCODEC CODEC = new FastPFOR128();
    private static final int UNCOMPRESSED_THRESHOLD = 128; // Small arrays might not benefit from compression.

    /**
     * Convenience constructor, defaults to a non-stitch list (isStitchList = false).
     * Now the primary constructor.
     */
    public PositionListSoA() {
        // this(false, null); // Old call
        this.numPositions = 0;
        this.documentIds = new IntArrayList();
        this.sentenceIds = new IntArrayList();
        this.beginChars = new IntArrayList();
        this.endChars = new IntArrayList();
        this.synonymIds = new IntArrayList(); // Always initialized
    }

    // --- Methods for adding data (to be added next) ---
    /**
     * Adds a position to this list. For non-stitch lists.
     * Appends attributes to the end of respective internal lists. Increments numPositions.
     *
     * @param docId Document ID.
     * @param sentId Sentence ID.
     * @param beginChar Begin character offset.
     * @param endChar End character offset.
     */
    public void add(int docId, int sentId, int beginChar, int endChar) {
        this.documentIds.add(docId);
        this.sentenceIds.add(sentId);
        this.beginChars.add(beginChar);
        this.endChars.add(endChar);
        this.synonymIds.add(-1); // Always add synonymId, -1 for non-stitch
        this.numPositions++;
    }

    /**
     * Adds a stitch position to this list. For stitch lists.
     * Appends attributes including synonymId. Increments numPositions.
     *
     * @param docId Document ID.
     * @param sentId Sentence ID.
     * @param beginChar Begin character offset of the unigram.
     * @param endChar End character offset of the unigram.
     * @param synonymId Synonym ID linking to the annotation.
     */
    public void add(int docId, int sentId, int beginChar, int endChar, int synonymId) {
        this.documentIds.add(docId);
        this.sentenceIds.add(sentId);
        this.beginChars.add(beginChar);
        this.endChars.add(endChar);
        this.synonymIds.add(synonymId);
        this.numPositions++;
    }

    /**
     * Adds a position from a {@link Position} object.
     * Deconstructs the Position object and calls the appropriate primitive add method.
     * If the provided position is a {@link StitchPosition} and this list is not a stitch list
     * (this.isStitchList is false), an {@link IllegalArgumentException} is thrown.
     * If the provided position is not a StitchPosition but this list *is* a stitch list,
     * an {@link IllegalArgumentException} is thrown.
     *
     * @param position The Position object to add.
     * @throws IllegalArgumentException if the type of Position object is incompatible with the list's stitch type.
     */
    public void add(Position position) {
        if (position instanceof StitchPosition stitchPos) {
            add(stitchPos.getDocumentId(), stitchPos.getSentenceId(), stitchPos.getBeginPosition(), stitchPos.getEndPosition(), stitchPos.getSynonymId());
        } else {
            add(position.getDocumentId(), position.getSentenceId(), position.getBeginPosition(), position.getEndPosition(), -1);
        }
    }

    // --- Methods for SoA native access (to be added next) ---
    /**
     * Returns the number of logical positions stored in this list.
     * @return The number of positions.
     */
    public int getNumPositions() {
        return numPositions;
    }

    /**
     * Gets the document ID at the specified index.
     * @param index The index of the position.
     * @return The document ID.
     * @throws IndexOutOfBoundsException if the index is out of range.
     */
    public int getDocIdAt(int index) {
        return documentIds.getInt(index);
    }

    /**
     * Gets the sentence ID at the specified index.
     * @param index The index of the position.
     * @return The sentence ID.
     * @throws IndexOutOfBoundsException if the index is out of range.
     */
    public int getSentenceIdAt(int index) {
        return sentenceIds.getInt(index);
    }

    /**
     * Gets the beginning character offset at the specified index.
     * @param index The index of the position.
     * @return The beginning character offset.
     * @throws IndexOutOfBoundsException if the index is out of range.
     */
    public int getBeginCharAt(int index) {
        return beginChars.getInt(index);
    }

    /**
     * Gets the ending character offset at the specified index.
     * @param index The index of the position.
     * @return The ending character offset.
     * @throws IndexOutOfBoundsException if the index is out of range.
     */
    public int getEndCharAt(int index) {
        return endChars.getInt(index);
    }

    /**
     * Gets the synonym ID at the specified index.
     * @param index The index of the position.
     * @return The synonym ID.
     */
    public int getSynonymIdAt(int index) {
        return synonymIds.getInt(index); // Will return -1 for non-stitch positions
    }

    /**
     * Returns a view or copy of the document IDs list.
     * For now, returning a direct reference. Consider read-only views or copies if mutations are a concern.
     * @return The list of document IDs.
     */
    public IntArrayList getDocumentIds() {
        return documentIds;
    }

    /**
     * Returns a view or copy of the sentence IDs list.
     * @return The list of sentence IDs.
     */
    public IntArrayList getSentenceIds() {
        return sentenceIds;
    }

    /**
     * Returns a view or copy of the begin character offsets list.
     * @return The list of begin character offsets.
     */
    public IntArrayList getBeginChars() {
        return beginChars;
    }

    /**
     * Returns a view or copy of the end character offsets list.
     * @return The list of end character offsets.
     */
    public IntArrayList getEndChars() {
        return endChars;
    }

    /**
     * Returns a view or copy of the synonym IDs list.
     * @return The list of synonym IDs.
     */
    public IntArrayList getSynonymIds() {
        return synonymIds;
    }

    // --- Methods for AoS compatibility access (to be added next) ---
    /**
     * Reconstructs and returns a {@link Position} object on-the-fly from the SoA data at the given index.
     * This method can be less performant for iterating over many positions compared to direct SoA access.
     * The synonym ID for this position can be obtained separately via {@link #getSynonymIdAt(int)}.
     *
     * @param index The index of the position to reconstruct.
     * @return The reconstructed Position object.
     * @throws IndexOutOfBoundsException if the index is out of range.
     */
    public Position getPositionAt(int index) {
        if (index < 0 || index >= numPositions) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + numPositions);
        }
        return new Position(
            documentIds.getInt(index),
            sentenceIds.getInt(index),
            beginChars.getInt(index),
            endChars.getInt(index)
        );
    }

    /**
     * Returns an iterator that reconstructs {@link Position} objects lazily.
     * This iterator is fail-fast if the list structure is modified during iteration.
     *
     * @return An iterator over Position objects.
     */
    public Iterator<Position> positionIterator() {
        return new Iterator<Position>() {
            private int currentIndex = 0;
            private final int expectedNumPositions = numPositions; // For fail-fast behavior (optional)

            @Override
            public boolean hasNext() {
                // Optional: Check for concurrent modification
                // if (expectedNumPositions != numPositions) {
                //    throw new ConcurrentModificationException();
                // }
                return currentIndex < numPositions;
            }

            @Override
            public Position next() {
                // Optional: Check for concurrent modification
                // if (expectedNumPositions != numPositions) {
                //    throw new ConcurrentModificationException();
                // }
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return getPositionAt(currentIndex++);
            }
        };
    }

    // --- Methods for Serialization / Deserialization ---
    /**
     * Serializes the {@code PositionListSoA} into a single composite binary blob.
     * The blob contains a metadata header followed by individually compressed attribute arrays.
     *
     * Structure:
     * - num_positions (int)
     * - For each attribute array (docIds, sentenceIds, beginChars, endChars, and synonymIds):
     *   - Compressed data (prefixed by its own length metadata, see writeCompressedIntArray)
     *
     * @return A byte array representing the serialized {@code PositionListSoA}.
     * @throws IOException If an I/O error occurs during serialization.
     */
    public byte[] serializeToCompositeBlob() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(this.numPositions * 10); // Adjusted estimate for 5 arrays
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            // 1. Write Metadata Header
            dos.writeInt(this.numPositions);

            // 2. Write Attribute Blobs
            writeCompressedIntArrayList(dos, this.documentIds, true);     // applyDelta = true
            writeCompressedIntArrayList(dos, this.sentenceIds, true);     // applyDelta = true
            writeCompressedIntArrayList(dos, this.beginChars, true);      // applyDelta = true
            writeCompressedIntArrayList(dos, this.endChars, true);        // applyDelta = true
            writeCompressedIntArrayList(dos, this.synonymIds, false);     // applyDelta = false
            dos.flush();
        }
        return baos.toByteArray();
    }

    /**
     * Writes an integer array to the output stream, compressing it if it's large enough.
     * The format is:
     * 1. originalLength (int): Number of integers in the original array.
     * 2. compressedLengthOrMarker (int):
     *    - If negative: -originalLength, indicating data is uncompressed.
     *    - If positive: Number of integers in the compressed data.
     * 3. data (int[]): Actual integer data (either uncompressed or compressed).
     *
     * @param out The DataOutputStream to write to.
     * @param data The integer array to write.
     * @param numElementsInArray The number of elements from the beginning of the array to write.
     * @param applyDelta Whether to apply delta coding before compression.
     * @throws IOException If an I/O error occurs.
     */
    public static void writeCompressedIntArray(DataOutputStream out, int[] data, int numElementsInArray, boolean applyDelta) throws IOException {
        out.writeInt(numElementsInArray); // Store the original number of elements

        if (numElementsInArray <= UNCOMPRESSED_THRESHOLD || numElementsInArray == 0) {
            out.writeInt(-numElementsInArray); // Negative marker for uncompressed (or zero for empty)
            // For uncompressed, write original data regardless of applyDelta
            for (int i = 0; i < numElementsInArray; i++) {
                out.writeInt(data[i]);
            }
        } else {
            // Ensure input array for compression is correctly sized
            int[] dataToCompress = (data.length == numElementsInArray) ? data : Arrays.copyOf(data, numElementsInArray);

            if (applyDelta && numElementsInArray > 0) {
                // Apply delta coding in-place on a copy if it's the original 'data' array,
                // or on dataToCompress if it's already a copy.
                if (dataToCompress == data) { // If dataToCompress points to the original 'data'
                    dataToCompress = Arrays.copyOf(dataToCompress, numElementsInArray); // Make a true copy
                }
                Delta.delta(dataToCompress);
            }

            // FastPFOR requires input buffer to be padded if its length is not a multiple of its block size (128 for FastPFOR128)
            int blockSize = FastPFOR128.BLOCK_SIZE;
            int remainder = dataToCompress.length % blockSize; // numElementsInArray is the effective length
            int[] inputForCompression = dataToCompress;
            if (remainder != 0) {
                int paddedLength = dataToCompress.length + (blockSize - remainder);
                inputForCompression = Arrays.copyOf(dataToCompress, paddedLength);
            }

            // Sufficiently large buffer: original size + 50% + fixed overhead
            int[] compressedData = new int[inputForCompression.length + (inputForCompression.length / 2) + 1024];
            IntWrapper inPos = new IntWrapper(0);
            IntWrapper outPos = new IntWrapper(0);
            CODEC.compress(inputForCompression, inPos, inputForCompression.length - inPos.get(), compressedData, outPos);

            out.writeInt(outPos.get()); // Number of integers in the compressed data
            for (int i = 0; i < outPos.get(); i++) {
                out.writeInt(compressedData[i]);
            }
        }
    }

    /**
     * Deserializes a composite binary blob (created by {@link #serializeToCompositeBlob()})
     * back into a {@code PositionListSoA} object.
     * This performs full deserialization of all attributes.
     *
     * @param compositeBlob The byte array containing the serialized data.
     * @return A new {@code PositionListSoA} instance populated with the deserialized data.
     * @throws IOException If an I/O error occurs or the data format is invalid.
     */
    public static PositionListSoA deserializeFromCompositeBlob(byte[] compositeBlob) throws IOException {
        if (compositeBlob == null || compositeBlob.length == 0) {
            throw new IOException("Cannot deserialize from null or zero-length compositeBlob.");
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(compositeBlob);
             DataInputStream dis = new DataInputStream(bais)) {

            int numPositions = dis.readInt();
            if (numPositions < 0) throw new IOException("Invalid numPositions: " + numPositions);

            PositionListSoA soaList = new PositionListSoA(); // Simplified constructor call
            if (numPositions == 0) {
                return soaList;
            }

            // Read attribute arrays (always all 5)
            soaList.documentIds = readCompressedIntArray(dis, numPositions, true);
            soaList.sentenceIds = readCompressedIntArray(dis, numPositions, true);
            soaList.beginChars = readCompressedIntArray(dis, numPositions, true);
            soaList.endChars = readCompressedIntArray(dis, numPositions, true);
            soaList.synonymIds = readCompressedIntArray(dis, numPositions, false);

            soaList.numPositions = numPositions;

            // Check if all data was consumed (optional, for strictness)
            if (dis.available() > 0) {
                // This could indicate extra data or a bug in serialization/deserialization logic for lengths.
                // For now, we'll be lenient, but this could be a warning or error.
                // System.err.println("Warning: Extra data remaining after deserialization: " + dis.available() + " bytes");
            }

            return soaList;
        } catch (java.io.EOFException e) {
            throw new IOException("Unexpected end of file during deserialization. Blob may be truncated or malformed.", e);
        }
    }

    /**
     * Reads a compressed (or uncompressed) integer array from the input stream.
     *
     * @param in The DataInputStream to read from.
     * @param numExpectedPositions The number of positions expected in the list (used for initial sizing and FastPFOR decompression).
     * @param applyInverseDelta Whether to apply inverse delta transformation after decompression.
     * @return An IntArrayList containing the deserialized integers.
     * @throws IOException If an I/O error occurs.
     */
    public static IntArrayList readCompressedIntArray(DataInputStream in, int numExpectedPositions, boolean applyInverseDelta) throws IOException {
        int originalLength = in.readInt(); // Original number of elements
        if (originalLength == 0) {
            return new IntArrayList(0); // Handle empty array case
        }

        int compressedLengthOrMarker = in.readInt(); // Length of compressed data or negative marker
        IntArrayList list = new IntArrayList(originalLength); // Pre-size with actual original length

        if (compressedLengthOrMarker < 0) { // Uncompressed data
            if (-compressedLengthOrMarker != originalLength && originalLength !=0) { // Check marker consistency for non-empty
                 throw new IOException("Uncompressed length marker mismatch. Expected: " + originalLength + ", Got from marker: " + (-compressedLengthOrMarker));
            }
            // For uncompressed, read original data regardless of applyInverseDelta
            for (int i = 0; i < originalLength; i++) {
                list.add(in.readInt());
            }
        } else { // Compressed data
            int[] compressedData = new int[compressedLengthOrMarker];
            for (int i = 0; i < compressedLengthOrMarker; i++) {
                compressedData[i] = in.readInt();
            }

            int blockSize = FastPFOR128.BLOCK_SIZE;
            int remainder = originalLength % blockSize;
            int paddedOutputLength = originalLength;
            if (remainder != 0) {
                paddedOutputLength = originalLength + (blockSize - remainder);
            }

            int[] decompressed = new int[paddedOutputLength];
            IntWrapper inPos = new IntWrapper(0);
            IntWrapper outPos = new IntWrapper(0);

            CODEC.uncompress(compressedData, inPos, compressedLengthOrMarker - inPos.get(), decompressed, outPos);

            if (outPos.get() < originalLength) {
                 throw new IOException("Decompression output size mismatch. Expected to decompress " + originalLength + " ints, but got " + outPos.get() + " ints.");
            }

            if (applyInverseDelta && originalLength > 0) {
                // Apply inverse delta only to the 'originalLength' part of the decompressed array
                // Delta.inverseDelta operates in-place. If 'decompressed' is larger due to padding,
                // we need to be careful. However, inverseDelta should correctly handle operating
                // on the prefix if its length is passed or implied by the array structure.
                // For simplicity, let's assume Delta.inverseDelta works on the whole array
                // and the extra padded values (if any) won't affect the first 'originalLength' values.
                // Or, more robustly, copy to an exact-size array first if Delta.inverseDelta
                // might misbehave with padding.
                // Given Delta.inverseDelta(int[] arr) modifies arr in place, we should be fine as long
                // as we only use the first originalLength elements later.

                // Create an exact-sized array for inverse delta if needed, or ensure inverseDelta handles it.
                // Let's assume inverseDelta works correctly on the 'decompressed' array up to 'originalLength'.
                // We'll copy the relevant part to 'finalData' before inverseDelta if 'decompressed' is padded.
                int[] dataForInverseDelta;
                if (paddedOutputLength > originalLength) {
                    dataForInverseDelta = Arrays.copyOf(decompressed, originalLength);
                    Delta.inverseDelta(dataForInverseDelta);
                     list.addElements(0, dataForInverseDelta, 0, originalLength);
                } else { // originalLength == paddedOutputLength, no padding was applied
                    Delta.inverseDelta(decompressed); // Apply in-place
                    list.addElements(0, decompressed, 0, originalLength);
                }
            } else {
                list.addElements(0, decompressed, 0, originalLength);
            }
        }
        return list;
    }

    // ---- Selective Deserialization Utilities ----

    /**
     * Reads only the metadata header from the composite blob to get the number of positions.
     *
     * @param compositeBlob The byte array of the serialized PositionListSoA.
     * @return The number of positions.
     * @throws IOException If an I/O error occurs or the blob is too short.
     */
    public static int getNumPositionsFromBlob(byte[] compositeBlob) throws IOException {
        if (compositeBlob == null || compositeBlob.length < 4) { // Minimum for numPositions (int)
            throw new IOException("Composite blob is too short to read numPositions.");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compositeBlob);
             DataInputStream dis = new DataInputStream(bais)) {
            return dis.readInt();
        }
    }

    /**
     * Selectively deserializes only the document IDs from the composite blob.
     *
     * @param compositeBlob The byte array of the serialized PositionListSoA.
     * @return An IntArrayList containing the document IDs.
     * @throws IOException If an I/O error occurs or the blob is malformed.
     */
    public static IntArrayList decompressDocIds(byte[] compositeBlob) throws IOException {
        if (compositeBlob == null || compositeBlob.length < 4) { // Min for header (numPositions only now)
            throw new IOException("Composite blob is too short to decompress document IDs.");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compositeBlob);
             DataInputStream dis = new DataInputStream(bais)) {

            int numPositions = dis.readInt();
            if (numPositions == 0) return new IntArrayList(0);
            return readCompressedIntArray(dis, numPositions, true); // applyInverseDelta = true
        }
    }

    /**
     * Selectively deserializes only the sentence IDs from the composite blob.
     *
     * @param compositeBlob The byte array of the serialized PositionListSoA.
     * @return An IntArrayList containing the sentence IDs.
     * @throws IOException If an I/O error occurs or the blob is malformed.
     */
    public static IntArrayList decompressSentenceIds(byte[] compositeBlob) throws IOException {
        if (compositeBlob == null || compositeBlob.length < 4) { // Min for header
            throw new IOException("Composite blob is too short to decompress sentence IDs.");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compositeBlob);
             DataInputStream dis = new DataInputStream(bais)) {

            int numPositions = dis.readInt();

            // Skip DocIDs array
            skipCompressedIntArray(dis, numPositions);

            if (numPositions == 0) return new IntArrayList(0); // Should have been caught by skip or read logic if numPos=0 led to lengthMarker=0
            return readCompressedIntArray(dis, numPositions, true); // applyInverseDelta = true
        }
    }

    /**
     * Helper method to skip over a single compressed/uncompressed attribute array in the stream.
     * Reads the length marker and skips the appropriate number of bytes for the data.
     * @param dis The DataInputStream to read from and skip.
     * @param numExpectedPositions Hint for expected positions (currently not strictly used by skip logic itself but good for consistency).
     * @throws IOException If an I/O error occurs.
     */
    private static void skipCompressedIntArray(DataInputStream dis, int numExpectedPositions) throws IOException {
        int originalLength = dis.readInt(); // Read original length
        if (originalLength == 0) return;    // Nothing to skip for an empty array

        int compressedLengthOrMarker = dis.readInt(); // Read compressed length or uncompressed marker
        int bytesToSkip = 0;

        if (compressedLengthOrMarker < 0) { // Uncompressed
             if (-compressedLengthOrMarker != originalLength && originalLength !=0) {
                 throw new IOException("Uncompressed length marker mismatch during skip. Expected: " + originalLength + ", Got from marker: " + (-compressedLengthOrMarker));
            }
            bytesToSkip = originalLength * 4; // 4 bytes per int
        } else { // Compressed
            bytesToSkip = compressedLengthOrMarker * 4; // 4 bytes per int
        }

        long skipped = dis.skipBytes(bytesToSkip);
        if (skipped != bytesToSkip) {
            throw new IOException("Failed to skip the full " + bytesToSkip + " bytes for an attribute array. Skipped only " + skipped);
        }
    }

    /**
     * Selectively deserializes only the begin character offsets from the composite blob.
     *
     * @param compositeBlob The byte array of the serialized PositionListSoA.
     * @return An IntArrayList containing the begin character offsets.
     * @throws IOException If an I/O error occurs or the blob is malformed.
     */
    public static IntArrayList decompressBeginChars(byte[] compositeBlob) throws IOException {
        if (compositeBlob == null || compositeBlob.length < 4) {
            throw new IOException("Composite blob is too short to decompress begin characters.");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compositeBlob);
             DataInputStream dis = new DataInputStream(bais)) {

            int numPositions = dis.readInt();

            skipCompressedIntArray(dis, numPositions); // Skip DocIDs
            skipCompressedIntArray(dis, numPositions); // Skip SentenceIDs

            if (numPositions == 0) return new IntArrayList(0);
            return readCompressedIntArray(dis, numPositions, true); // applyInverseDelta = true
        }
    }

    /**
     * Selectively deserializes only the end character offsets from the composite blob.
     *
     * @param compositeBlob The byte array of the serialized PositionListSoA.
     * @return An IntArrayList containing the end character offsets.
     * @throws IOException If an I/O error occurs or the blob is malformed.
     */
    public static IntArrayList decompressEndChars(byte[] compositeBlob) throws IOException {
        if (compositeBlob == null || compositeBlob.length < 4) {
            throw new IOException("Composite blob is too short to decompress end characters.");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compositeBlob);
             DataInputStream dis = new DataInputStream(bais)) {

            int numPositions = dis.readInt();

            skipCompressedIntArray(dis, numPositions); // Skip DocIDs
            skipCompressedIntArray(dis, numPositions); // Skip SentenceIDs
            skipCompressedIntArray(dis, numPositions); // Skip BeginChars

            if (numPositions == 0) return new IntArrayList(0);
            return readCompressedIntArray(dis, numPositions, true); // applyInverseDelta = true
        }
    }

    /**
     * Selectively deserializes only the synonym IDs from the composite blob.
     * If the blob does not represent a stitch list, this method will throw an IOException
     * as it attempts to read data that isn't there (or misinterprets other data).
     * Callers should check {@link #getIsStitchListFromBlob(byte[])} first if unsure.
     *
     * @param compositeBlob The byte array of the serialized PositionListSoA.
     * @return An IntArrayList containing the synonym IDs.
     * @throws IOException If an I/O error occurs, the blob is malformed, or it's not a stitch list blob.
     */
    public static IntArrayList decompressSynonymIds(byte[] compositeBlob) throws IOException {
        if (compositeBlob == null || compositeBlob.length < 4) { // Min for header, check for actual content later
            throw new IOException("Composite blob is too short to decompress synonym IDs.");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compositeBlob);
             DataInputStream dis = new DataInputStream(bais)) {

            int numPositions = dis.readInt();

            skipCompressedIntArray(dis, numPositions); // Skip DocIDs
            skipCompressedIntArray(dis, numPositions); // Skip SentenceIDs
            skipCompressedIntArray(dis, numPositions); // Skip BeginChars
            skipCompressedIntArray(dis, numPositions); // Skip EndChars

            if (numPositions == 0) return new IntArrayList(0);
            return readCompressedIntArray(dis, numPositions, false); // applyInverseDelta = false for synonymIds
        }
    }

    // --- Methods for Manipulation & Other (to be added next) ---

    /**
     * Appends all positions from another {@code PositionListSoA} to this one.
     * Both lists must have compatible {@code isStitchList} flags and, if stitch lists,
     * compatible {@code stitchAnnotationType}.
     *
     * @param other The {@code PositionListSoA} to add all positions from.
     * @throws IllegalArgumentException if the lists are not compatible (mismatched stitch status or types).
     */
    public void addAll(PositionListSoA other) {


        this.documentIds.addAll(other.documentIds);
        this.sentenceIds.addAll(other.sentenceIds);
        this.beginChars.addAll(other.beginChars);
        this.endChars.addAll(other.endChars);
        this.synonymIds.addAll(other.synonymIds); // Always add
        this.numPositions += other.numPositions;
    }

    /**
     * Removes all of the elements from this list. The list will be empty after this call returns.
     * Resets numPositions to 0 and clears all internal attribute lists.
     */
    public void clear() {
        this.documentIds.clear();
        this.sentenceIds.clear();
        this.beginChars.clear();
        this.endChars.clear();
        if (this.synonymIds != null) { // Should always be non-null now
             this.synonymIds.clear();
        }
        this.numPositions = 0;
    }

    /**
     * Returns true if this list contains no positions.
     *
     * @return true if this list contains no positions.
     */
    public boolean isEmpty() {
        return this.numPositions == 0;
    }

    /**
     * Trims the capacity of the internal attribute lists to be the list's current size.
     * An application can use this operation to minimize the storage of a PositionListSoA instance.
     */
    public void trimToSize() {
        this.documentIds.trim();
        this.sentenceIds.trim();
        this.beginChars.trim();
        this.endChars.trim();
        this.synonymIds.trim();
    }

    /**
     * Sorts all parallel attribute arrays according to the standard position comparison:
     * documentId, then sentenceId, then beginChar, then endChar.
     * If it is a stitch list, it further sorts by synonymId after the primary keys.
     * The sort is stable with respect to elements not distinguished by the comparator.
     */
    public void sort() {
        if (numPositions <= 1) {
            return;
        }

        int[] p = new int[numPositions];
        for (int i = 0; i < numPositions; i++) {
            p[i] = i;
        }

        IntComparator comparator = (i1, i2) -> {
            int docIdCompare = Integer.compare(documentIds.getInt(i1), documentIds.getInt(i2));
            if (docIdCompare != 0) return docIdCompare;

            int sentIdCompare = Integer.compare(sentenceIds.getInt(i1), sentenceIds.getInt(i2));
            if (sentIdCompare != 0) return sentIdCompare;

            int beginCompare = Integer.compare(beginChars.getInt(i1), beginChars.getInt(i2));
            if (beginCompare != 0) return beginCompare;

            int endCompare = Integer.compare(endChars.getInt(i1), endChars.getInt(i2));
            if (endCompare != 0) return endCompare;

            int synonymIdCompare = Integer.compare(synonymIds.getInt(i1), synonymIds.getInt(i2));
            if (synonymIdCompare != 0) return synonymIdCompare;
            return 0;
        };

        IntArrays.quickSort(p, comparator);

        IntArrayList sortedDocIds = new IntArrayList(numPositions);
        IntArrayList sortedSentenceIds = new IntArrayList(numPositions);
        IntArrayList sortedBeginChars = new IntArrayList(numPositions);
        IntArrayList sortedEndChars = new IntArrayList(numPositions);
        IntArrayList sortedSynonymIds = new IntArrayList(numPositions); // Always create

        for (int i = 0; i < numPositions; i++) {
            int originalIndex = p[i];
            sortedDocIds.add(documentIds.getInt(originalIndex));
            sortedSentenceIds.add(sentenceIds.getInt(originalIndex));
            sortedBeginChars.add(beginChars.getInt(originalIndex));
            sortedEndChars.add(endChars.getInt(originalIndex));
            sortedSynonymIds.add(synonymIds.getInt(originalIndex)); // Always add
        }

        this.documentIds = sortedDocIds;
        this.sentenceIds = sortedSentenceIds;
        this.beginChars = sortedBeginChars;
        this.endChars = sortedEndChars;
        this.synonymIds = sortedSynonymIds; // Always assign
    }

    // Private helper record for merge operation
    private record PositionTuple(int docId, int sentId, int begin, int end, int synId) {}

    /**
     * Merges another {@code PositionListSoA} into this one, ensuring uniqueness of positions.
     * Uniqueness is based on documentId, sentenceId, beginChar, endChar, and synonymId.
     * The merged list will be sorted according to the standard sort order.
     *
     * @param other The {@code PositionListSoA} to merge with this one.
     */
    public void merge(PositionListSoA other) {
        if (other == null || other.isEmpty()) {
            return;
        }

        Set<PositionTuple> uniquePositionTuples = new TreeSet<>(Comparator
            .comparingInt(PositionTuple::docId)
            .thenComparingInt(PositionTuple::sentId)
            .thenComparingInt(PositionTuple::begin)
            .thenComparingInt(PositionTuple::end)
            .thenComparingInt(PositionTuple::synId));

        for (int i = 0; i < this.numPositions; i++) {
            uniquePositionTuples.add(new PositionTuple(
                this.documentIds.getInt(i),
                this.sentenceIds.getInt(i),
                this.beginChars.getInt(i),
                this.endChars.getInt(i),
                this.synonymIds.getInt(i)
            ));
        }
        for (int i = 0; i < other.numPositions; i++) {
            uniquePositionTuples.add(new PositionTuple(
                other.documentIds.getInt(i),
                other.sentenceIds.getInt(i),
                other.beginChars.getInt(i),
                other.endChars.getInt(i),
                other.synonymIds.getInt(i)
            ));
        }

        this.clear();

        // The TreeSet is already sorted by the comparator. Add them back in order.
        for (PositionTuple pt : uniquePositionTuples) {
            this.add(pt.docId(), pt.sentId(), pt.begin(), pt.end(), pt.synId());
        }
        // numPositions is updated by the add calls.
        // The list is already sorted due to TreeSet iteration order and how items were added.
        // If an explicit re-sort to exactly match the sort() method behavior is needed (e.g. for stability guarantees not provided by this TreeSet approach)
        // then uncomment: this.sort();
        // However, for just unique sorted positions, this is sufficient.
    }

    /**
     * Helper to write an IntArrayList's elements to a DataOutputStream using the compression logic.
     *
     * @param dos The DataOutputStream to write to.
     * @param list The IntArrayList whose elements are to be written.
     * @param applyDelta Whether to apply delta coding before compression.
     * @throws IOException If an I/O error occurs.
     */
    private void writeCompressedIntArrayList(DataOutputStream dos, IntArrayList list, boolean applyDelta) throws IOException {
        writeCompressedIntArray(dos, list.elements(), list.size(), applyDelta);
    }

}