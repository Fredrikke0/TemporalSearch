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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(PositionListSoA.class);

    private IntArrayList documentIds;
    private IntArrayList sentenceIds;
    private IntArrayList beginChars;
    private IntArrayList endChars;
    private IntArrayList synonymIds; // Always present. Value is -1 for non-stitch positions.

    private int numPositions;        // Number of logical positions stored, also the size of each active list

    private static final IntegerCODEC CODEC = new FastPFOR128();
    public static final int UNCOMPRESSED_THRESHOLD = 20; // Small arrays might not benefit from compression.
    private static final int RLE_ENCODED_MARKER = Integer.MIN_VALUE + 2024; // Marker for Run-Length Encoded constant arrays

    /**
     * Defines compression override behavior for serialization.
     */
    public enum CompressionOverride {
        /**
         * Uses the default compression logic (e.g., UNCOMPRESSED_THRESHOLD, RLE).
         */
        DEFAULT,
        /**
         * Forces compression if applicable (e.g., FastPFOR, RLE), ignoring thresholds like UNCOMPRESSED_THRESHOLD.
         */
        FORCE_COMPRESSION,
        /**
         * Forces data to be written uncompressed. RLE will not be applied.
         */
        FORCE_UNCOMPRESSED
    }

    /**
     * Convenience constructor, defaults to a non-stitch list (isStitchList = false).
     * Now the primary constructor.
     */
    public PositionListSoA() {
        this.numPositions = 0;
        this.documentIds = new IntArrayList();
        this.sentenceIds = new IntArrayList();
        this.beginChars = new IntArrayList();
        this.endChars = new IntArrayList();
        this.synonymIds = new IntArrayList(); // Always initialized
    }

    // --- Methods for adding data ---
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
     * Serializes the {@code PositionListSoA} into a single composite binary blob using default compression.
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
        return serializeToCompositeBlob(CompressionOverride.DEFAULT);
    }

    /**
     * Serializes the {@code PositionListSoA} into a single composite binary blob
     * with a specific compression override.
     * The blob contains a metadata header followed by individually compressed attribute arrays.
     *
     * Structure:
     * - num_positions (int)
     * - For each attribute array (docIds, sentenceIds, beginChars, endChars, and synonymIds):
     *   - Compressed data (prefixed by its own length metadata, see writeCompressedIntArray)
     *
     * @param compressionOverride The compression strategy to use.
     * @return A byte array representing the serialized {@code PositionListSoA}.
     * @throws IOException If an I/O error occurs during serialization.
     */
    public byte[] serializeToCompositeBlob(CompressionOverride compressionOverride) throws IOException {
        // logger.debug("serializeToCompositeBlob: START. numPositions = {}, override = {}", this.numPositions, compressionOverride);
        // logger.debug("serializeToCompositeBlob: Array sizes BEFORE compression: docIds={}, sentIds={}, beginChars={}, endChars={}, synonymIds={}",
        //         this.documentIds.size(), this.sentenceIds.size(), this.beginChars.size(), this.endChars.size(), this.synonymIds.size());

        ByteArrayOutputStream baos = new ByteArrayOutputStream(this.numPositions * 10); // Adjusted estimate for 5 arrays
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            // 1. Write Metadata Header
            dos.writeInt(this.numPositions);
            // logger.debug("serializeToCompositeBlob: Wrote numPositions={}", this.numPositions);

            // 2. Write Attribute Blobs
            writeCompressedIntArrayList(dos, this.documentIds, true, compressionOverride);     // applyDelta = true
            writeCompressedIntArrayList(dos, this.sentenceIds, true, compressionOverride);     // applyDelta = true
            writeCompressedIntArrayList(dos, this.beginChars, true, compressionOverride);      // applyDelta = true
            writeCompressedIntArrayList(dos, this.endChars, true, compressionOverride);        // applyDelta = true
            writeCompressedIntArrayList(dos, this.synonymIds, false, compressionOverride);     // applyDelta = false
            dos.flush();
        }
        // logger.debug("serializeToCompositeBlob: END. Serialized blob length: {}", baos.size());
        return baos.toByteArray();
    }

    /**
     * Writes an integer array to the output stream, compressing it if it's large enough.
     * This version calls the overridden method with default compression.
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
        writeCompressedIntArray(out, data, numElementsInArray, applyDelta, CompressionOverride.DEFAULT);
    }

    /**
     * Writes an integer array to the output stream, compressing it based on the override.
     * The format is:
     * 1. originalLength (int): Number of integers in the original array.
     * 2. compressedLengthOrMarker (int):
     *    - If negative: -originalLength, indicating data is uncompressed.
     *    - If positive: Number of integers in the compressed data.
     *    - If RLE_ENCODED_MARKER: data is RLE encoded.
     * 3. data (int[] or int): Actual integer data (either uncompressed, compressed, or the RLE value).
     *
     * @param out The DataOutputStream to write to.
     * @param data The integer array to write.
     * @param numElementsInArray The number of elements from the beginning of the array to write.
     * @param applyDelta Whether to apply delta coding before compression.
     * @param override The compression override strategy.
     * @throws IOException If an I/O error occurs.
     */
    public static void writeCompressedIntArray(DataOutputStream out, int[] data, int numElementsInArray, boolean applyDelta, CompressionOverride override) throws IOException {
        // Determine effective override, defaulting to DEFAULT if null
        CompressionOverride effectiveOverride = (override == null) ? CompressionOverride.DEFAULT : override;
        // logger.debug("writeCompressedIntArray: START. numElements={}, applyDelta={}, effectiveOverride={}", numElementsInArray, applyDelta, effectiveOverride);

        if (numElementsInArray == 0) {
            out.writeInt(0); // Zero elements, write size 0 (no RLE marker needed for empty)
            // logger.debug("writeCompressedIntArray: Wrote 0 bytes for empty array.");
            return;
        }

        // Attempt RLE only if not forcing uncompressed
        if (effectiveOverride != CompressionOverride.FORCE_UNCOMPRESSED) {
            boolean allSame = true;
            int firstValue = data[0];
            for (int i = 1; i < numElementsInArray; i++) {
                if (data[i] != firstValue) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) {
                out.writeInt(RLE_ENCODED_MARKER);
                out.writeInt(firstValue);
                // logger.debug("writeCompressedIntArray: Wrote RLE data, value = {}, marker = {}", firstValue, RLE_ENCODED_MARKER);
                return;
            }
        }

        int[] dataToWrite = data;
        if (applyDelta && numElementsInArray > 0) {
            dataToWrite = Arrays.copyOf(data, numElementsInArray);
            Delta.delta(dataToWrite); // Apply delta in-place
        }

        // Compression logic based on effectiveOverride
        if (effectiveOverride == CompressionOverride.FORCE_UNCOMPRESSED || (effectiveOverride == CompressionOverride.DEFAULT && numElementsInArray < UNCOMPRESSED_THRESHOLD)) {
            // Write raw integers (uncompressed)
            out.writeInt(numElementsInArray * 4); // Size of uncompressed data in bytes
            for (int i = 0; i < numElementsInArray; ++i) {
                out.writeInt(dataToWrite[i]);
            }
            // logger.debug("writeCompressedIntArray: Writing UNCOMPRESSED data, {} elements, {} bytes", numElementsInArray, numElementsInArray * 4);
        } else {
            // Compress (either by DEFAULT for larger arrays or FORCE_COMPRESSION)
            int[] dataToCompress = dataToWrite;
            int originalLength = numElementsInArray;

            if (CODEC instanceof FastPFOR128) {
                int blockSize = 128; // FastPFOR128 block size
                int remainder = originalLength % blockSize;
                if (remainder != 0) {
                    int paddedLength = originalLength + (blockSize - remainder);
                    dataToCompress = Arrays.copyOf(dataToWrite, paddedLength);
                    // Fill padding with 0s; if delta was applied, these 0s become part of the delta chain
                    // Or, more simply, since FastPFOR compresses blocks, the values of padding don't critically affect correctness
                    // as long as we only use 'originalLength' on decompress. Let's stick with Arrays.copyOf which 0-pads.
                    // logger.debug("writeCompressedIntArray: Padded data from {} to {} for FastPFOR.", originalLength, paddedLength);
                }
            }

            IntWrapper inPos = new IntWrapper(0);
            IntWrapper outPos = new IntWrapper(0);
            // Use dataToCompress.length for the compression input length if padded
            int[] compressed = new int[(dataToCompress.length * 2) + 1024]; // Sufficiently large buffer
            CODEC.compress(dataToCompress, inPos, dataToCompress.length, compressed, outPos);
            int compressedSize = outPos.get();
            out.writeInt(compressedSize * 4); // Size of compressed data in bytes
            for (int i = 0; i < compressedSize; ++i) {
                out.writeInt(compressed[i]);
            }
            // logger.debug("writeCompressedIntArray: Writing COMPRESSED data, original {} elements, compressed to {} ints ({} bytes)", originalLength, compressedSize, compressedSize * 4);
        }
        // logger.debug("writeCompressedIntArray: END.");
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
        // logger.debug("deserializeFromCompositeBlob: START. Blob size = {} bytes", compositeBlob != null ? compositeBlob.length : "null");
        if (compositeBlob == null || compositeBlob.length == 0) {
            logger.warn("deserializeFromCompositeBlob: input blob is null or empty.");
            return new PositionListSoA(); // Return empty if blob is null or empty
        }

        PositionListSoA instance = new PositionListSoA();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compositeBlob);
             DataInputStream dis = new DataInputStream(bais)) {

            int numPositionsRead = dis.readInt();
            // logger.debug("deserializeFromCompositeBlob: Read numPositions={}", numPositionsRead);

            if (numPositionsRead < 0) {
                 logger.error("deserializeFromCompositeBlob: Invalid numPositions ({}) read from blob.", numPositionsRead);
                 throw new IOException("Invalid numPositions: " + numPositionsRead);
            }
            instance.numPositions = numPositionsRead;

            // If numPositions is 0, no further reading is needed, arrays are already empty.
            if (instance.numPositions == 0) {
                // logger.debug("deserializeFromCompositeBlob: numPositions is 0, returning empty instance.");
                return instance; // Should already be an empty, valid PositionListSoA
            }

            // logger.debug("deserializeFromCompositeBlob: Deserializing documentIds...");
            instance.documentIds = readCompressedIntArray(dis, instance.numPositions, true);
            // logger.debug("deserializeFromCompositeBlob: Deserialized documentIds size: {}", instance.documentIds.size());

            // logger.debug("deserializeFromCompositeBlob: Deserializing sentenceIds...");
            instance.sentenceIds = readCompressedIntArray(dis, instance.numPositions, true);
            // logger.debug("deserializeFromCompositeBlob: Deserialized sentenceIds size: {}", instance.sentenceIds.size());

            // logger.debug("deserializeFromCompositeBlob: Deserializing beginChars...");
            instance.beginChars = readCompressedIntArray(dis, instance.numPositions, true);
            // logger.debug("deserializeFromCompositeBlob: Deserialized beginChars size: {}", instance.beginChars.size());

            // logger.debug("deserializeFromCompositeBlob: Deserializing endChars...");
            instance.endChars = readCompressedIntArray(dis, instance.numPositions, true);
            // logger.debug("deserializeFromCompositeBlob: Deserialized endChars size: {}", instance.endChars.size());

            // logger.debug("deserializeFromCompositeBlob: Deserializing synonymIds...");
            instance.synonymIds = readCompressedIntArray(dis, instance.numPositions, false); // No delta on synonym IDs typically
            // logger.debug("deserializeFromCompositeBlob: Deserialized synonymIds size: {}", instance.synonymIds.size());

            // Post-deserialization validation
            if (instance.documentIds.size() != instance.numPositions ||
                instance.sentenceIds.size() != instance.numPositions ||
                instance.beginChars.size() != instance.numPositions ||
                instance.endChars.size() != instance.numPositions ||
                instance.synonymIds.size() != instance.numPositions) {
                String errorMessage = String.format(
                    "deserializeFromCompositeBlob: Mismatch between numPositions (%d) and actual array sizes. doc=%d, sent=%d, begin=%d, end=%d, syn=%d",
                    instance.numPositions, instance.documentIds.size(), instance.sentenceIds.size(),
                    instance.beginChars.size(), instance.endChars.size(), instance.synonymIds.size());
                logger.error(errorMessage);
                throw new IOException(errorMessage);
            }
            // logger.debug("deserializeFromCompositeBlob: END. Successfully deserialized.");

            return instance;
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
        // logger.debug("readCompressedIntArray: START. numExpectedPositions={}, applyInverseDelta={}", numExpectedPositions, applyInverseDelta);
        if (numExpectedPositions == 0) {
            // logger.debug("readCompressedIntArray: numExpectedPositions is 0, returning empty IntArrayList.");
            return new IntArrayList(0);
        }

        int arraySizeOrMarker = in.readInt(); // This is compressed size in bytes, or RLE_MARKER, or uncompressed size in bytes
        // logger.debug("readCompressedIntArray: Read arraySize/marker = {}", arraySizeOrMarker);

        if (arraySizeOrMarker == RLE_ENCODED_MARKER) {
            int value = in.readInt();
            IntArrayList list = new IntArrayList(numExpectedPositions);
            for (int i = 0; i < numExpectedPositions; i++) {
                list.add(value);
            }
            // logger.debug("readCompressedIntArray: Decoded RLE data, value = {}, count = {}. Returning list size: {}", value, numExpectedPositions, list.size());
            return list; // RLE data does not undergo delta coding typically
        }

        if (arraySizeOrMarker == 0 && numExpectedPositions > 0) {
             logger.warn("readCompressedIntArray: arraySizeOrMarker is 0, but numExpectedPositions={}. This implies an empty array was written for a non-empty expectation. Returning empty list.", numExpectedPositions);
             return new IntArrayList(0);
        }
         if (arraySizeOrMarker == 0 && numExpectedPositions == 0) { // Corrected condition from previous thought
             // logger.debug("readCompressedIntArray: arraySizeOrMarker is 0 and numExpectedPositions is 0. Returning empty list.");
            return new IntArrayList(0);
        }

        int numIntsToRead = arraySizeOrMarker / 4; // Convert byte size to int count
        int[] readData = new int[numIntsToRead];
        for (int i = 0; i < numIntsToRead; ++i) {
            readData[i] = in.readInt();
        }

        int[] decompressedElements;

        if (arraySizeOrMarker == numExpectedPositions * 4) { // Uncompressed
            decompressedElements = Arrays.copyOf(readData, numExpectedPositions); // Make a copy
            // logger.debug("readCompressedIntArray: Read UNCOMPRESSED data, {} elements.", numExpectedPositions);
        } else { // Compressed
            // Decompress into a temporary buffer that could be larger
            int[] tempDecompressed = new int[numExpectedPositions + 1024]; // Buffer for safety
            IntWrapper inPos = new IntWrapper(0);
            IntWrapper outPos = new IntWrapper(0);
            CODEC.uncompress(readData, inPos, numIntsToRead, tempDecompressed, outPos);

            int actualDecompressedCount = outPos.get();
            decompressedElements = new int[actualDecompressedCount];
            System.arraycopy(tempDecompressed, 0, decompressedElements, 0, actualDecompressedCount);

            // logger.debug("readCompressedIntArray: Read COMPRESSED data, decompressed {} elements (expected {}).", actualDecompressedCount, numExpectedPositions);
        }

        if (applyInverseDelta && decompressedElements.length > 0) {
            Delta.inverseDelta(decompressedElements); // Apply inverse delta in-place on the correctly sized decompressedElements array
            // logger.debug("readCompressedIntArray: Applied inverse delta to {} elements.", decompressedElements.length);
        }

        // After potential inverse delta, if the decompressed data was padded (for FastPFOR),
        // truncate it back to the numExpectedPositions.
        int[] finalElementsToWrap;
        if (decompressedElements.length > numExpectedPositions) {
            // logger.debug("readCompressedIntArray: Truncating padded decompressed data from {} to {}.", decompressedElements.length, numExpectedPositions);
            finalElementsToWrap = Arrays.copyOf(decompressedElements, numExpectedPositions);
        } else if (decompressedElements.length < numExpectedPositions) {
            // This case could indicate an issue if the decompressed data (before potential truncation for padding)
            // is already smaller than expected.
            logger.warn("readCompressedIntArray: Decompressed elements length ({}) is less than numExpectedPositions ({}). This might indicate data loss or corruption.", decompressedElements.length, numExpectedPositions);
            finalElementsToWrap = decompressedElements; // Use what we have
        } else {
            finalElementsToWrap = decompressedElements;
        }

        // logger.debug("readCompressedIntArray: END. Returning IntArrayList of size: {}", finalElementsToWrap.length);
        return IntArrayList.wrap(finalElementsToWrap); // Wrap the final, possibly delta-decoded and truncated, array
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
        // logger.debug("getNumPositionsFromBlob: START. Blob size = {} bytes", compositeBlob != null ? compositeBlob.length : "null");
        if (compositeBlob == null || compositeBlob.length < 4) { // Need at least 4 bytes for an int
            logger.warn("getNumPositionsFromBlob: blob is null or too short (size={}) to contain numPositions.", compositeBlob != null ? compositeBlob.length : "null");
            return 0;
        }
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(compositeBlob))) {
            int numPositionsRead = dis.readInt();
            // logger.debug("getNumPositionsFromBlob: END. Read numPositions = {}", numPositionsRead);
            return numPositionsRead;
        } catch (IOException e) {
            logger.error("getNumPositionsFromBlob: IOException while reading numPositions. Blob size: {}. Error: {}", compositeBlob.length, e.getMessage());
            throw e; // Re-throw as it indicates a more serious issue with the blob or stream.
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
        // logger.debug("decompressDocIds: Attempting to decompress doc IDs from blob (size={})", compositeBlob != null ? compositeBlob.length : "null");
        if (compositeBlob == null || compositeBlob.length < 4) { // Min: 4 for numPos, then array data
            logger.warn("decompressDocIds: blob is null or too short.");
            return new IntArrayList(0);
        }
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(compositeBlob))) {
            int numPositions = dis.readInt();
            // logger.debug("decompressDocIds: Read numPositions = {}", numPositions);
            if (numPositions == 0) return new IntArrayList(0);
            IntArrayList result = readCompressedIntArray(dis, numPositions, true); // applyInverseDelta = true
            // logger.debug("decompressDocIds: Successfully decompressed {} doc IDs.", result.size());
            return result;
        } catch (IOException e) {
            logger.error("decompressDocIds: IOException. Blob size: {}. Error: {}", compositeBlob.length, e.getMessage());
            throw e;
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
        // logger.debug("decompressSentenceIds: Attempting to decompress sentence IDs from blob (size={})", compositeBlob != null ? compositeBlob.length : "null");
        if (compositeBlob == null || compositeBlob.length < 4) {
            logger.warn("decompressSentenceIds: blob is null or too short.");
            return new IntArrayList(0);
        }
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(compositeBlob))) {
            int numPositions = dis.readInt();
            // logger.debug("decompressSentenceIds: Read numPositions = {}", numPositions);
            if (numPositions == 0) return new IntArrayList(0);
            skipCompressedIntArray(dis, numPositions); // Skip DocIDs
            // logger.debug("decompressSentenceIds: Skipped doc IDs.");
            IntArrayList result = readCompressedIntArray(dis, numPositions, true); // applyInverseDelta = true
            // logger.debug("decompressSentenceIds: Successfully decompressed {} sentence IDs.", result.size());
            return result;
        } catch (IOException e) {
            logger.error("decompressSentenceIds: IOException. Blob size: {}. Error: {}", compositeBlob.length, e.getMessage());
            throw e;
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
        // logger.debug("skipCompressedIntArray: START. numExpectedPositions={}", numExpectedPositions);
        if (numExpectedPositions == 0) {
            // logger.debug("skipCompressedIntArray: numExpectedPositions is 0, nothing to skip.");
            return;
        }
        int arraySizeOrMarker = dis.readInt(); // This is compressed size in bytes, or RLE_MARKER, or uncompressed size in bytes
        // logger.debug("skipCompressedIntArray: Read arraySize/marker = {}", arraySizeOrMarker);

        if (arraySizeOrMarker == RLE_ENCODED_MARKER) {
            dis.readInt(); // Skip the RLE value
            // logger.debug("skipCompressedIntArray: Skipped RLE value.");
        } else if (arraySizeOrMarker > 0) {
            // arraySizeOrMarker is byte count for compressed or uncompressed data
            long skipped = dis.skipBytes(arraySizeOrMarker);
            // logger.debug("skipCompressedIntArray: Skipped {} bytes for array data (expected to skip {}).", skipped, arraySizeOrMarker);
            if (skipped != arraySizeOrMarker) {
                logger.warn("skipCompressedIntArray: Failed to skip the expected number of bytes. Expected: {}, Actual: {}. Stream might be corrupted or at EOF.", arraySizeOrMarker, skipped);
                throw new IOException("Failed to skip expected bytes for compressed array data. Expected: " + arraySizeOrMarker + ", Actual: " + skipped);
            }
        } else if (arraySizeOrMarker == 0) {
             // logger.debug("skipCompressedIntArray: arraySizeOrMarker is 0 (empty array), nothing to skip beyond the size int itself.");
        } else {
            // This case should ideally not be reached if arraySizeOrMarker is negative but not RLE_MARKER
            logger.warn("skipCompressedIntArray: Encountered unexpected arraySize/marker value: {}. Cannot reliably skip.", arraySizeOrMarker);
            throw new IOException("Unexpected arraySize/marker in skipCompressedIntArray: " + arraySizeOrMarker);
        }
        // logger.debug("skipCompressedIntArray: END. Skipped based on marker/size: {}", arraySizeOrMarker);
    }

    /**
     * Selectively deserializes only the begin character offsets from the composite blob.
     *
     * @param compositeBlob The byte array of the serialized PositionListSoA.
     * @return An IntArrayList containing the begin character offsets.
     * @throws IOException If an I/O error occurs or the blob is malformed.
     */
    public static IntArrayList decompressBeginChars(byte[] compositeBlob) throws IOException {
        // logger.debug("decompressBeginChars: Attempting from blob (size={})", compositeBlob != null ? compositeBlob.length : "null");
        if (compositeBlob == null || compositeBlob.length < 4) return new IntArrayList(0);
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(compositeBlob))) {
            int numPositions = dis.readInt();
            if (numPositions == 0) return new IntArrayList(0);
            skipCompressedIntArray(dis, numPositions); // DocIDs
            skipCompressedIntArray(dis, numPositions); // SentenceIDs
            // logger.debug("decompressBeginChars: Skipped doc and sentence IDs for {} positions.", numPositions);
            return readCompressedIntArray(dis, numPositions, true);
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
        // logger.debug("decompressEndChars: Attempting from blob (size={})", compositeBlob != null ? compositeBlob.length : "null");
        if (compositeBlob == null || compositeBlob.length < 4) return new IntArrayList(0);
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(compositeBlob))) {
            int numPositions = dis.readInt();
            if (numPositions == 0) return new IntArrayList(0);
            skipCompressedIntArray(dis, numPositions); // DocIDs
            skipCompressedIntArray(dis, numPositions); // SentenceIDs
            skipCompressedIntArray(dis, numPositions); // BeginChars
            // logger.debug("decompressEndChars: Skipped doc, sentence, and begin_char IDs for {} positions.", numPositions);
            return readCompressedIntArray(dis, numPositions, true);
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
        // logger.debug("decompressSynonymIds: Attempting from blob (size={})", compositeBlob != null ? compositeBlob.length : "null");
        if (compositeBlob == null || compositeBlob.length < 4) return new IntArrayList(0);
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(compositeBlob))) {
            int numPositions = dis.readInt();
            if (numPositions == 0) return new IntArrayList(0);
            skipCompressedIntArray(dis, numPositions); // DocIDs
            skipCompressedIntArray(dis, numPositions); // SentenceIDs
            skipCompressedIntArray(dis, numPositions); // BeginChars
            skipCompressedIntArray(dis, numPositions); // EndChars
            // logger.debug("decompressSynonymIds: Skipped doc, sentence, begin_char, and end_char IDs for {} positions.", numPositions);
            return readCompressedIntArray(dis, numPositions, false); // No delta
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
     * Helper to write an IntArrayList's elements to a DataOutputStream using the compression logic
     * and a specific compression override.
     *
     * @param dos The DataOutputStream to write to.
     * @param list The IntArrayList whose elements are to be written.
     * @param applyDelta Whether to apply delta coding before compression.
     * @param override The compression override strategy.
     * @throws IOException If an I/O error occurs.
     */
    private void writeCompressedIntArrayList(DataOutputStream dos, IntArrayList list, boolean applyDelta, CompressionOverride override) throws IOException {
        writeCompressedIntArray(dos, list.elements(), list.size(), applyDelta, override);
    }
}