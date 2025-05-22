package com.example.core;

import com.example.index.AnnotationType;
import com.example.index.StitchPosition;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

import me.lemire.integercompression.FastPFOR128;
import me.lemire.integercompression.IntWrapper;
import me.lemire.integercompression.IntegerCODEC;
import java.util.Arrays;
import java.util.Comparator;
import java.util.TreeSet;
import java.util.Set;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntComparator;

/**
 * Manages collections of position data using a Structure of Arrays (SoA) approach.
 * This class is designed for memory efficiency and selective attribute access,
 * particularly for large datasets.
 *
 * See design/root_problems/positionlist-blobs.md for detailed design.
 */
public class PositionListSoA {

    // Internal SoA representation
    private IntArrayList documentIds;
    private IntArrayList sentenceIds;
    private IntArrayList beginChars;
    private IntArrayList endChars;
    private IntArrayList synonymIds; // Always present. Value is -1 for non-stitch positions.

    private int numPositions;        // Number of logical positions stored, also the size of each active list

    private static final IntegerCODEC CODEC = new FastPFOR128();
    private static final int UNCOMPRESSED_THRESHOLD = 128; // Small arrays won't be compressed
    private static final int CODEC_BLOCK_SIZE = 128; // Block size for FastPFOR128

    /**
     * Primary constructor. Initializes empty primitive lists.
     * The flag determines if the synonymIds list is created and active.
     *
     * @param isStitchList true if this list will store stitch-related data (including synonymIds), false otherwise.
     * @param stitchAnnotationTypeIfStitch The {@link AnnotationType} for this list if it's a stitch list; can be null if not a stitch list.
     * @throws IllegalArgumentException if isStitchList is true but stitchAnnotationTypeIfStitch is null.
     */

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

    // --- Methods for Serialization / Deserialization (to be added next) ---
    /**
     * Serializes the {@code PositionListSoA} into a single composite binary blob.
     * The blob contains a metadata header followed by individually compressed attribute arrays.
     *
     * Structure:
     * - num_positions (int)
     * - isStitchList (byte: 1 for true, 0 for false)
     * - IF isStitchList is true: stitchAnnotationTypeOrdinal (byte)
     * - For each attribute array (docIds, sentenceIds, beginChars, endChars, and synonymIds if stitch):
     *   - Compressed data (prefixed by its own length metadata, see writeCompressedIntArraySoA)
     *
     * @return A byte array representing the serialized {@code PositionListSoA}.
     * @throws IOException If an I/O error occurs during serialization.
     */
    public byte[] serializeToCompositeBlob() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(this.numPositions * 10); // Adjusted estimate for 5 arrays
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            // 1. Write Metadata Header
            dos.writeInt(this.numPositions);

            // 2. Write Attribute Blobs (always all 5)
            writeCompressedIntArraySoA(dos, this.documentIds.elements(), this.numPositions);
            writeCompressedIntArraySoA(dos, this.sentenceIds.elements(), this.numPositions);
            writeCompressedIntArraySoA(dos, this.beginChars.elements(), this.numPositions);
            writeCompressedIntArraySoA(dos, this.endChars.elements(), this.numPositions);
            writeCompressedIntArraySoA(dos, this.synonymIds.elements(), this.numPositions); // Always write synonymIds
            dos.flush();
        }
        return baos.toByteArray();
    }

    /**
     * Helper method to write an integer array (potentially compressed) to the DataOutputStream.
     * Stores the actual number of elements from the source array up to {@code effectiveSize}.
     *
     * Format for each array written:
     * - If uncompressed (array length <= UNCOMPRESSED_THRESHOLD):
     *   - -(effectiveSize) (int): Negative effectiveSize indicates uncompressed.
     *   - int data[effectiveSize]
     * - If compressed:
     *   - effectiveSize (int): Original number of elements.
     *   - compressed_data_length_in_ints (int): Number of ints in the compressed data.
     *   - int compressed_data[compressed_data_length_in_ints]
     *
     * @param dos The DataOutputStream to write to.
     * @param array The source integer array. This array might be larger than effectiveSize.
     * @param effectiveSize The number of elements from the start of the array to serialize.
     * @throws IOException If an I/O error occurs.
     */
    private static void writeCompressedIntArraySoA(DataOutputStream dos, int[] array, int effectiveSize) throws IOException {
        if (effectiveSize == 0) {
            dos.writeInt(0); // Indicate zero original length, implies no data following for this array.
            return;
        }

        if (effectiveSize <= UNCOMPRESSED_THRESHOLD) {
            dos.writeInt(-effectiveSize); // Negative length indicates uncompressed
            for (int i = 0; i < effectiveSize; i++) {
                dos.writeInt(array[i]);
            }
        } else {
            int[] dataToCompress = array;
            if (array.length != effectiveSize) { 
                 dataToCompress = Arrays.copyOf(array, effectiveSize);
            }

            // Pad dataToCompress to be a multiple of CODEC_BLOCK_SIZE for FastPFOR
            int paddedSize = ((effectiveSize + CODEC_BLOCK_SIZE - 1) / CODEC_BLOCK_SIZE) * CODEC_BLOCK_SIZE;
            int[] paddedData = dataToCompress;
            if (effectiveSize != paddedSize) {
                 paddedData = Arrays.copyOf(dataToCompress, paddedSize);
                 // The padded elements don't matter for compression outcome if compressor handles original length
            }

            IntWrapper inOffset = new IntWrapper(0);
            IntWrapper outOffset = new IntWrapper(0);
            // Output buffer for compressed data; FastPFOR can sometimes expand, * 2 is a safe bet.
            int[] compressed = new int[paddedSize * 2]; 
            
            CODEC.compress(paddedData, inOffset, paddedSize, compressed, outOffset);
            int compressedSizeInInts = outOffset.get();

            dos.writeInt(effectiveSize);        // Original number of elements (non-padded)
            dos.writeInt(compressedSizeInInts); 
            for (int i = 0; i < compressedSizeInInts; i++) {
                dos.writeInt(compressed[i]);
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
            soaList.documentIds = new IntArrayList(readCompressedIntArraySoA(dis, numPositions));
            soaList.sentenceIds = new IntArrayList(readCompressedIntArraySoA(dis, numPositions));
            soaList.beginChars = new IntArrayList(readCompressedIntArraySoA(dis, numPositions));
            soaList.endChars = new IntArrayList(readCompressedIntArraySoA(dis, numPositions));
            soaList.synonymIds = new IntArrayList(readCompressedIntArraySoA(dis, numPositions)); // Always read synonymIds

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
     * Helper method to read an integer array (potentially compressed) from the DataInputStream.
     * Expects the format written by {@link #writeCompressedIntArraySoA(DataOutputStream, int[], int)}.
     *
     * @param dis The DataInputStream to read from.
     * @param expectedOriginalSize The expected number of elements in the deserialized array, used for validation for compressed arrays.
     *                           For uncompressed arrays, this isn't strictly needed for allocation if we read first then allocate.
     * @return A new int[] containing the deserialized integers.
     * @throws IOException If an I/O error occurs or data is malformed.
     */
    private static int[] readCompressedIntArraySoA(DataInputStream dis, int expectedOriginalSize) throws IOException {
        int lengthMarker = dis.readInt();

        if (lengthMarker == 0) { // Array was stored with effectiveSize 0
            if (expectedOriginalSize != 0) {
                // This might be an issue if expectedOriginalSize (numPositions from header) is > 0
                // but an array was written as empty. For now, assume this means an empty array is correct.
            }
            return new int[0];
        }

        if (lengthMarker < 0) { // Uncompressed
            int actualLength = -lengthMarker;
            int[] data = new int[actualLength];
            for (int i = 0; i < actualLength; i++) {
                data[i] = dis.readInt();
            }
            return data;
        } else { // Compressed
            int originalSize = lengthMarker; // This is the actual number of elements we care about
            int compressedSizeInInts = dis.readInt();
            if (compressedSizeInInts < 0) throw new IOException("Invalid compressedSizeInInts: " + compressedSizeInInts);

            int[] compressedData = new int[compressedSizeInInts];
            for (int i = 0; i < compressedSizeInInts; i++) {
                compressedData[i] = dis.readInt();
            }

            // Determine padded size for decompression based on originalSize
            int paddedSize = ((originalSize + CODEC_BLOCK_SIZE - 1) / CODEC_BLOCK_SIZE) * CODEC_BLOCK_SIZE;
            int[] decompressedPadded = new int[paddedSize]; 
            
            IntWrapper inOffset = new IntWrapper(0);
            IntWrapper outOffset = new IntWrapper(0);

            CODEC.uncompress(compressedData, inOffset, compressedSizeInInts, decompressedPadded, outOffset);
            
            // The outOffset.get() from uncompress for FastPFOR should be the paddedSize if it processed full blocks.
            // We only need the originalSize elements from the decompressedPadded array.
            if (outOffset.get() != paddedSize) {
                // This might indicate an issue if the codec didn't fill the expected padded buffer.
                // However, some codecs might return the actual number of items uncompressed if less than paddedSize.
                // For FastPFOR, it usually processes in blocks, so outOffset should reflect that.
                // The crucial part is that `decompressedPadded` contains at least `originalSize` valid elements.
            }

            // Copy only the original number of elements
            if (originalSize == paddedSize) {
                return decompressedPadded;
            } else {
                return Arrays.copyOf(decompressedPadded, originalSize);
            }
        }
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
     * Reads only the metadata header from the composite blob to get the {@link AnnotationType}.
     * Returns null if it's not a stitch list.
     *
     * @param compositeBlob The byte array of the serialized PositionListSoA.
     * @return The {@link AnnotationType} if it's a stitch list and the type is present; null otherwise.
     * @throws IOException If an I/O error occurs, the blob is too short, or the ordinal is invalid.
     */

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
            return new IntArrayList(readCompressedIntArraySoA(dis, numPositions));
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
            skipAttributeArraySoA(dis); 
            
            if (numPositions == 0) return new IntArrayList(0); // Should have been caught by skip or read logic if numPos=0 led to lengthMarker=0
            return new IntArrayList(readCompressedIntArraySoA(dis, numPositions));
        }
    }
    
    /**
     * Helper method to skip over a single compressed/uncompressed attribute array in the stream.
     * Reads the length marker and skips the appropriate number of bytes for the data.
     * @param dis The DataInputStream to read from and skip.
     * @throws IOException If an I/O error occurs.
     */
    private static void skipAttributeArraySoA(DataInputStream dis) throws IOException {
        int lengthMarker = dis.readInt(); // This is either -originalSize (uncompressed) or originalSize (compressed), or 0 (empty)
        if (lengthMarker == 0) {
            return; // No data to skip
        }
        if (lengthMarker < 0) { // Uncompressed
            int numInts = -lengthMarker;
            long bytesToSkip = (long)numInts * 4;
            if (dis.skipBytes((int)bytesToSkip) != bytesToSkip) {
                 throw new IOException("Failed to skip all bytes for uncompressed attribute array.");
            }
        } else { // Compressed
            int compressedSizeInInts = dis.readInt();
            long bytesToSkip = (long)compressedSizeInInts * 4;
             if (dis.skipBytes((int)bytesToSkip) != bytesToSkip) {
                 throw new IOException("Failed to skip all bytes for compressed attribute array.");
            }
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

            skipAttributeArraySoA(dis); // Skip DocIDs
            skipAttributeArraySoA(dis); // Skip SentenceIDs
            
            if (numPositions == 0) return new IntArrayList(0);
            return new IntArrayList(readCompressedIntArraySoA(dis, numPositions));
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

            skipAttributeArraySoA(dis); // Skip DocIDs
            skipAttributeArraySoA(dis); // Skip SentenceIDs
            skipAttributeArraySoA(dis); // Skip BeginChars
            
            if (numPositions == 0) return new IntArrayList(0);
            return new IntArrayList(readCompressedIntArraySoA(dis, numPositions));
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

            skipAttributeArraySoA(dis); // Skip DocIDs
            skipAttributeArraySoA(dis); // Skip SentenceIDs
            skipAttributeArraySoA(dis); // Skip BeginChars
            skipAttributeArraySoA(dis); // Skip EndChars
            
            if (numPositions == 0) return new IntArrayList(0);
            return new IntArrayList(readCompressedIntArraySoA(dis, numPositions));
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

} 