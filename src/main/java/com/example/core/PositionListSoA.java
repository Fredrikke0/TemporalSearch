package com.example.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.index.StitchPosition;
import com.example.query.executor.FilteringContext;
import com.example.query.model.Query;

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
    public static final int RLE_ENCODED_MARKER = Integer.MIN_VALUE + 2024; // Marker for Run-Length Encoded constant arrays

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
        // logger.debug("Serializing PositionListSoA: numPositions = {}", this.numPositions);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(this.numPositions * 10); // Adjusted estimate for 5 arrays
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            // 1. Write Metadata Header
            dos.writeInt(this.numPositions);

            // 2. Write Attribute Blobs
            // logger.debug("Serializing docIds: size = {}", this.documentIds.size());
            writeCompressedIntArrayList(dos, this.documentIds, true, compressionOverride);     // applyDelta = true
            // logger.debug("Serializing sentenceIds: size = {}", this.sentenceIds.size());
            writeCompressedIntArrayList(dos, this.sentenceIds, true, compressionOverride);     // applyDelta = true
            // logger.debug("Serializing beginChars: size = {}", this.beginChars.size());
            writeCompressedIntArrayList(dos, this.beginChars, true, compressionOverride);      // applyDelta = true
            // logger.debug("Serializing endChars: size = {}", this.endChars.size());
            writeCompressedIntArrayList(dos, this.endChars, true, compressionOverride);        // applyDelta = true
            // logger.debug("Serializing synonymIds: size = {}", this.synonymIds.size());
            writeCompressedIntArrayList(dos, this.synonymIds, false, compressionOverride);     // applyDelta = false
            dos.flush();
        }
        // logger.debug("Serialization complete. Total blob size: {} bytes.", baos.size());
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
        if (numElementsInArray == 0) {
            out.writeInt(0); // Marker for empty array
            return;
        }

        // 1. Attempt RLE first if data is constant.
        // This applies regardless of applyDelta, unless caller specifically forces FORCE_UNCOMPRESSED.
        // RLE is generally superior for constant arrays.
        if (override != CompressionOverride.FORCE_UNCOMPRESSED) {
            boolean allSame = true;
            int firstValue = data[0];
            for (int i = 1; i < numElementsInArray; i++) {
                if (data[i] != firstValue) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) {
                // logger.debug("writeCompressedIntArray: Writing RLE data. Value={}, NumElements={}", firstValue, numElementsInArray);
                out.writeInt(RLE_ENCODED_MARKER);
                out.writeInt(firstValue);
                return;
            }
        }
        // logger.debug("writeCompressedIntArray: Data not RLE (or RLE skipped by override). NumElements={}", numElementsInArray);

        // If we are here, data was not RLE encoded.
        int[] dataToWrite = data; // Use original data by default
        if (applyDelta && numElementsInArray > 0) {
            // logger.debug("writeCompressedIntArray: Applying delta to data. NumElements={}", numElementsInArray);
            dataToWrite = Arrays.copyOf(data, numElementsInArray); // Create a copy for delta transformation
            Delta.delta(dataToWrite); // Apply delta in-place on the copy
        }

        // Determine if we should write uncompressed based on various factors.
        boolean writeUncompressed;
        if (!applyDelta) {
            // For non-delta arrays (that were not RLE), always write them uncompressed.
            // The `override` parameter does not affect this decision for non-delta arrays,
            // as FastPFOR is not suitable for them anyway.
            writeUncompressed = true;
        } else {
            // For delta-coded arrays (that were not RLE):
            // Use override or threshold to decide.
            CompressionOverride effectiveOverride = (override == null) ? CompressionOverride.DEFAULT : override;
            writeUncompressed = (effectiveOverride == CompressionOverride.FORCE_UNCOMPRESSED) ||
                              (effectiveOverride == CompressionOverride.DEFAULT && numElementsInArray < UNCOMPRESSED_THRESHOLD);
        }

        if (writeUncompressed) {
            out.writeInt(numElementsInArray * 4); // Size of payload in BYTES (marker for uncompressed)
            for (int i = 0; i < numElementsInArray; i++) {
                out.writeInt(dataToWrite[i]); // dataToWrite is potentially delta-coded if applyDelta was true
            }
        } else {
            // COMPRESSED PATH (only for applyDelta = true, not RLE, not forced uncompressed, and exceeded threshold)
            // This implies applyDelta must have been true to reach here.
            IntegerCODEC chosenCodec = PositionListSoA.CODEC; // Default FastPFOR128 for delta-coded arrays

            IntWrapper inpos = new IntWrapper(0);
            IntWrapper outpos = new IntWrapper(0);
            // For FastPFOR, data needs to be padded to block size if it's the chosen codec.
            int[] dataToCompress = dataToWrite;
            if (chosenCodec instanceof FastPFOR128) {
                int originalLength = numElementsInArray;
                int blockSize = 128; // FastPFOR128 block size
                int remainder = originalLength % blockSize;
                if (remainder != 0) {
                    int paddedLength = originalLength + (blockSize - remainder);
                    dataToCompress = Arrays.copyOf(dataToWrite, paddedLength);
                }
            }

            // Use dataToCompress.length which will be the padded length for FastPFOR
            int[] compressedInts = new int[dataToCompress.length * 2 + 1024];
            chosenCodec.compress(dataToCompress, inpos, dataToCompress.length, compressedInts, outpos);

            int compressedSizeInInts = outpos.get();
            // No longer manually convert to byte[], write ints directly using DataOutputStream
            // The stored size marker is in bytes, so it's compressedSizeInInts * 4
            out.writeInt(compressedSizeInInts * 4);
            for (int i = 0; i < compressedSizeInInts; i++) {
                out.writeInt(compressedInts[i]);
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
            return new PositionListSoA(); // Return empty if blob is null or empty
        }

        PositionListSoA instance = new PositionListSoA();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compositeBlob);
             DataInputStream dis = new DataInputStream(bais)) {

            int numPositionsRead = dis.readInt();

            if (numPositionsRead < 0) {
                 throw new IOException("Invalid numPositions: " + numPositionsRead);
            }
            instance.numPositions = numPositionsRead;

            if (instance.numPositions == 0) {
                return instance;
            }

            // logger.debug("Deserializing documentIds for {} positions...", instance.numPositions);
            instance.documentIds = readCompressedIntArray(dis, instance.numPositions, true);
            // logger.debug("Deserialized documentIds size: {}", instance.documentIds.size());

            // logger.debug("Deserializing sentenceIds for {} positions...", instance.numPositions);
            instance.sentenceIds = readCompressedIntArray(dis, instance.numPositions, true);
            // logger.debug("Deserialized sentenceIds size: {}", instance.sentenceIds.size());

            // logger.debug("Deserializing beginChars for {} positions...", instance.numPositions);
            instance.beginChars = readCompressedIntArray(dis, instance.numPositions, true);
            // logger.debug("Deserialized beginChars size: {}", instance.beginChars.size());

            // logger.debug("Deserializing endChars for {} positions...", instance.numPositions);
            instance.endChars = readCompressedIntArray(dis, instance.numPositions, true);
            // logger.debug("Deserialized endChars size: {}", instance.endChars.size());

            // logger.debug("Deserializing synonymIds for {} positions...", instance.numPositions);
            instance.synonymIds = readCompressedIntArray(dis, instance.numPositions, false); // No delta on synonym IDs typically
            // logger.debug("Deserialized synonymIds size: {}", instance.synonymIds.size());


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
        // Delegate to the version-aware method, assuming latest version if not specified by caller context
        return readCompressedIntArray(in, numExpectedPositions, applyInverseDelta, 3); // Default to version 3 logic
    }

    /**
     * Reads a compressed integer array from the stream, handling different versions.
     *
     * @param in The DataInputStream to read from.
     * @param numExpectedPositions The number of integers expected in the array.
     * @param applyInverseDelta Whether to apply inverse delta decoding.
     * @param blobVersion The version of the blob format being read.
     * @return An IntArrayList containing the decompressed and (if applicable) delta-decoded integers.
     * @throws IOException If an I/O error occurs.
     */
    public static IntArrayList readCompressedIntArray(DataInputStream in, int numExpectedPositions, boolean applyInverseDelta, int blobVersion) throws IOException {
        if (numExpectedPositions == 0) {
            int sizeMarkerForEmpty = in.readInt();
            if (sizeMarkerForEmpty != 0) {
                logger.warn("readCompressedIntArray: numExpectedPositions is 0, but size marker read was {}. Expected 0.", sizeMarkerForEmpty);
                // Potentially throw new IOException here if strictness is required for empty array marker.
            }
            return new IntArrayList(0);
        }

        int arraySizeOrMarker = in.readInt();

        if (arraySizeOrMarker == RLE_ENCODED_MARKER) {
            int value = in.readInt();
            IntArrayList list = new IntArrayList(numExpectedPositions);
            for (int i = 0; i < numExpectedPositions; i++) {
                list.add(value);
            }
            // logger.debug("readCompressedIntArray: Read RLE data, value = {}, numExpectedPositions = {}.", value, numExpectedPositions);
            // RLE data does not undergo delta transformation, so return directly.
            // This is correct for both applyInverseDelta true and false cases if the original was RLE.
            return list;
        }

        int[] dataPayload;

        if (!applyInverseDelta) {
            // Data for arrays where inverse delta is not applied (e.g., synonymIds).
            // These arrays were written raw/uncompressed (if not RLE, which is handled above).
            // So, arraySizeOrMarker here should be the raw byte count (numExpectedPositions * 4).
            // logger.debug("readCompressedIntArray: Reading non-delta array (e.g. synonymIds). applyInverseDelta=false. numExpectedPositions={}, arraySizeOrMarker={}", numExpectedPositions, arraySizeOrMarker);
            if (arraySizeOrMarker != numExpectedPositions * 4) {
                throw new IOException(String.format(
                    "Data format error for non-delta array (expected raw uncompressed): numExpectedPositions=%d, expected raw byte size %d, but stream marker is %d. Not RLE.",
                    numExpectedPositions, numExpectedPositions * 4, arraySizeOrMarker
                ));
            }
            dataPayload = new int[numExpectedPositions];
            for (int i = 0; i < numExpectedPositions; i++) {
                dataPayload[i] = in.readInt();
            }
            // logger.debug("readCompressedIntArray: Read as UNCOMPRESSED (because !applyInverseDelta). numExpectedPositions={}, read {} ints.", numExpectedPositions, dataPayload.length);
            // No inverse delta needs to be applied, as it wasn't delta-coded.
        } else {
            // applyInverseDelta is TRUE. Data was potentially delta-coded.
            // It could have been written uncompressed (due to threshold for delta-arrays) or compressed.

            // Determine if data was originally written uncompressed (due to threshold for delta-arrays)
            boolean wasWrittenUncompressedDueToThreshold = (numExpectedPositions < UNCOMPRESSED_THRESHOLD);

            if (wasWrittenUncompressedDueToThreshold) {
                if (arraySizeOrMarker != numExpectedPositions * 4) {
                    throw new IOException(String.format(
                        "Data format error (expected uncompressed based on threshold for delta array): numExpectedPositions=%d, expected byte size %d, but stream marker is %d.",
                        numExpectedPositions, numExpectedPositions * 4, arraySizeOrMarker
                    ));
                }
                dataPayload = new int[numExpectedPositions];
                for (int i = 0; i < numExpectedPositions; i++) {
                    dataPayload[i] = in.readInt();
                }
            } else {
                // COMPRESSED PATH (for delta arrays that exceeded threshold, were not RLE, and applyInverseDelta is true)
                if (arraySizeOrMarker <= 0 || arraySizeOrMarker % 4 != 0) {
                    throw new IOException("Invalid compressed data size marker: " + arraySizeOrMarker + " (must be >0 and multiple of 4).");
                }
                int numIntsInCompressedPayload = arraySizeOrMarker / 4;
                // No longer read into byte[] and manually convert. Read ints directly.
                int[] compressedInts = new int[numIntsInCompressedPayload];
                for (int i = 0; i < numIntsInCompressedPayload; i++) {
                    compressedInts[i] = in.readInt();
                }

                // For delta-coded arrays that are compressed, we use the default CODEC (FastPFOR128).
                IntegerCODEC chosenCodec = PositionListSoA.CODEC;

                // Sufficient buffer for FastPFOR padding too
                int[] tempDecompressed = new int[numExpectedPositions + 128 + 1024];
                IntWrapper inPos = new IntWrapper(0);
                IntWrapper outPos = new IntWrapper(0);

                if ((numExpectedPositions == 405888 && !applyInverseDelta) || logger.isDebugEnabled() && !applyInverseDelta) {
                     logger.debug("Before chosenCodec.uncompress ({} {}): compressedInts.length={}, numIntsInPayload={}, numExpectedPositions={}, tempDecompressed.length={}",
                                  (numExpectedPositions == 405888 ? "TARGETED" : "DIAG"), chosenCodec.toString(),
                                  compressedInts.length, numIntsInCompressedPayload, numExpectedPositions, tempDecompressed.length);
                } else if (logger.isTraceEnabled()){
                     logger.trace("Before chosenCodec.uncompress ({}): compressedInts.length={}, numIntsInPayload={}, numExpectedPositions={}, tempDecompressed.length={}",
                                  chosenCodec.toString(), compressedInts.length, numIntsInCompressedPayload, numExpectedPositions, tempDecompressed.length);
                }

                // For FastPFOR, the 'len' parameter to uncompress is the number of compressed ints in the input stream.
                // The output buffer (tempDecompressed) must be large enough for the *padded* original count.
                // The actual number of elements produced will be in outPos.
                chosenCodec.uncompress(compressedInts, inPos, numIntsInCompressedPayload, tempDecompressed, outPos);

                int actualDecompressedPaddedCount = outPos.get();

                if (actualDecompressedPaddedCount < numExpectedPositions) {
                    throw new IOException(String.format(
                        "Decompression error with %s: produced fewer elements (%d) than expected before truncation (%d). Compressed size was %d ints.",
                        chosenCodec.toString(), actualDecompressedPaddedCount, numExpectedPositions, numIntsInCompressedPayload
                    ));
                }
                // Truncate to the exact numExpectedPositions if FastPFOR produced more due to padding.
                // VariableByte should produce exactly numExpectedPositions if compression was done on numExpectedPositions.
                dataPayload = Arrays.copyOf(tempDecompressed, numExpectedPositions);
                // logger.debug("readCompressedIntArray: Decompressed with {}, padded count {}, truncated to {}.", codecName, actualDecompressedPaddedCount, dataPayload.length);
            }
        }

        // Apply inverse delta if needed.
        // This is correctly skipped if !applyInverseDelta due to the separate path taken above.
        // For the applyInverseDelta=true path, dataPayload is now populated (either from uncompressed-due-to-threshold or from decompression).
        if (applyInverseDelta && numExpectedPositions > 0) {
            if (dataPayload == null || dataPayload.length != numExpectedPositions) {
                 // This state should ideally not be reached if logic above is correct.
                 throw new IOException("Internal error: dataPayload inconsistent before inverse delta. Expected length " + numExpectedPositions + ", got " + (dataPayload != null ? dataPayload.length : "null"));
            }
            Delta.inverseDelta(dataPayload); // Apply inverse delta in-place
        }
        return IntArrayList.wrap(dataPayload, numExpectedPositions);
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

    // Method from design document: PositionListSoA.deserializeWithFilters
    public static PositionListSoA deserializeWithFilters(byte[] blob, Optional<FilteringContext> context)
            throws IOException {
        if (blob == null || blob.length == 0) {
            return new PositionListSoA(); // Return empty if blob is null or empty
        }

        // If context is not present or is unrestricted, use the standard deserialization
        if (context.isEmpty() || context.get().isUnrestricted()) {
            logger.trace("deserializeWithFilters: Context is empty or unrestricted, calling deserializeFromCompositeBlob.");
            return deserializeFromCompositeBlob(blob);
        }

        FilteringContext activeContext = context.get();
        //logger.debug("deserializeWithFilters: Active context present. Granularity: {}, AllowedDocsPresent: {}, AllowedDocSentsPresent: {}",
        //        activeContext.granularity(), activeContext.allowedDocumentIds().isPresent(), activeContext.allowedDocumentSentenceIds().isPresent());

        PositionListSoA result = new PositionListSoA();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(blob));

        int numPositionsInBlob = dis.readInt();
        if (numPositionsInBlob == 0) {
            dis.close();
            return result; // Empty result if blob represents no positions
        }

        // Step 1: Deserialize all potential data arrays from the blob
        // Match the structure of deserializeFromCompositeBlob and serializeToCompositeBlob
        IntArrayList allDocIds = readCompressedIntArray(dis, numPositionsInBlob, true);     // applyDelta = true
        IntArrayList allSentIds = readCompressedIntArray(dis, numPositionsInBlob, true);    // applyDelta = true
        IntArrayList allBeginChars = readCompressedIntArray(dis, numPositionsInBlob, true); // applyDelta = true
        IntArrayList allEndChars = readCompressedIntArray(dis, numPositionsInBlob, true);   // applyDelta = true
        IntArrayList allSynonymIds = readCompressedIntArray(dis, numPositionsInBlob, false); // applyDelta = false

        dis.close();

        // Step 2: Create an inclusion mask
        boolean[] inclusionMask = new boolean[numPositionsInBlob];
        int selectedCount = 0;

        // Step 3: Document ID Filtering
        Optional<Set<Integer>> allowedDocIdsOpt = activeContext.allowedDocumentIds();
        if (allowedDocIdsOpt.isPresent()) {
            Set<Integer> allowedDocs = allowedDocIdsOpt.get();
            if (allowedDocs.isEmpty()) {
                logger.debug("deserializeWithFilters: Context has empty allowedDocumentIds set. Returning empty result.");
                return result; // No documents allowed, so result is empty
            }

            // Filter using the set for direct lookup (hash join style).
            for (int i = 0; i < numPositionsInBlob; i++) {
                if (allowedDocs.contains(allDocIds.getInt(i))) {
                    inclusionMask[i] = true;
                }
            }
        } else {
            // No document ID filter from context, so all documents initially pass this stage
            Arrays.fill(inclusionMask, true);
        }

        // Step 4: Sentence ID Filtering (if applicable)
        if (activeContext.granularity() == Query.Granularity.SENTENCE && allSentIds != null) {
            Optional<Map<Integer, Set<Integer>>> allowedDocSentIdsOpt = activeContext.allowedDocumentSentenceIds();
            if (allowedDocSentIdsOpt.isPresent()) {
                Map<Integer, Set<Integer>> allowedDocSents = allowedDocSentIdsOpt.get();
                if (allowedDocSents.isEmpty() && allowedDocIdsOpt.isPresent()) {
                    // If allowedDocSents is empty AND there was some doc restriction, it means no sentences are allowed for those docs.
                    // If allowedDocIdsOpt was NOT present, an empty allowedDocSents might mean "no sentence restriction for any doc".
                    // This case is a bit ambiguous; however, FilteringContext.intersect handles this by making allowedDocSentIds an empty map
                    // if the doc intersection is empty. If doc intersection is not empty but sentence intersection IS, then this applies.
                    logger.debug("deserializeWithFilters: Context has empty allowedDocumentSentenceIds map for sentence granularity. Filtering all.");
                    return result; // Effectively, no sentences allowed.
                }

                for (int i = 0; i < numPositionsInBlob; i++) {
                    if (inclusionMask[i]) { // Only check if it passed document filtering
                        int docId = allDocIds.getInt(i);
                        int sentId = (allSentIds != null) ? allSentIds.getInt(i) : -1;
                        Set<Integer> allowedSentsForDoc = allowedDocSents.get(docId);
                        if (allowedSentsForDoc == null || !allowedSentsForDoc.contains(sentId)) {
                            inclusionMask[i] = false; // Filter out this position
                        }
                    }
                }
            }
            // If allowedDocSentIdsOpt is not present, no sentence-specific filtering is done, rely on doc filter.
        }

        // Step 5: Populate the result PositionListSoA with filtered data
        for (int i = 0; i < numPositionsInBlob; i++) {
            if (inclusionMask[i]) {
                int docId = allDocIds.getInt(i);
                int sentId = (allSentIds != null) ? allSentIds.getInt(i) : -1;
                int beginChar = (allBeginChars != null) ? allBeginChars.getInt(i) : -1;
                int endChar = (allEndChars != null) ? allEndChars.getInt(i) : -1;
                int synonymId = (allSynonymIds != null) ? allSynonymIds.getInt(i) : -1;
                result.add(docId, sentId, beginChar, endChar, synonymId);
                selectedCount++;
            }
        }
        //logger.debug("deserializeWithFilters: Selected {} positions out of {} from blob after filtering.", selectedCount, numPositionsInBlob);
        return result;
    }

    /**
     * Efficiently merges two compressed PositionListSoA blobs by selectively decompressing,
     * merging, and recompressing each attribute array one at a time. This minimizes peak memory
     * usage compared to fully decompressing both blobs.
     *
     * @param blob1 The first compressed blob (accumulated state)
     * @param blob2 The second compressed blob (new chunk to merge)
     * @return A new compressed blob containing the merged data
     * @throws IOException If an I/O error occurs during decompression/compression
     */
    public static byte[] mergeCompressedBlobs(byte[] blob1, byte[] blob2) throws IOException {
        if (blob1 == null || blob1.length == 0) {
            if (blob2 == null || blob2.length == 0) {
                return new PositionListSoA().serializeToCompositeBlob(CompressionOverride.DEFAULT);
            }
            return blob2;
        }
        if (blob2 == null || blob2.length == 0) {
            return blob1;
        }

        int numPositions1 = getNumPositionsFromBlob(blob1);
        int numPositions2 = getNumPositionsFromBlob(blob2);
        int totalPositions = numPositions1 + numPositions2;

        if (totalPositions == 0) {
            // Both blobs might represent empty lists, or one/both are malformed to appear empty.
            // Return a canonical empty blob.
            return new PositionListSoA().serializeToCompositeBlob(CompressionOverride.DEFAULT);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(totalPositions * 8);
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(totalPositions);

            IntArrayList docIds1 = decompressDocIds(blob1);
            IntArrayList docIds2 = decompressDocIds(blob2);
            docIds1.addAll(docIds2);
            writeCompressedIntArray(dos, docIds1.elements(), docIds1.size(), true, CompressionOverride.DEFAULT);
            docIds1 = null; // Help GC
            docIds2 = null; // Help GC

            IntArrayList sentIds1 = decompressSentenceIds(blob1);
            IntArrayList sentIds2 = decompressSentenceIds(blob2);
            sentIds1.addAll(sentIds2);
            writeCompressedIntArray(dos, sentIds1.elements(), sentIds1.size(), true, CompressionOverride.DEFAULT);
            sentIds1 = null; // Help GC
            sentIds2 = null; // Help GC

            IntArrayList beginChars1 = decompressBeginChars(blob1);
            IntArrayList beginChars2 = decompressBeginChars(blob2);
            beginChars1.addAll(beginChars2);
            writeCompressedIntArray(dos, beginChars1.elements(), beginChars1.size(), true, CompressionOverride.DEFAULT);
            beginChars1 = null; // Help GC
            beginChars2 = null; // Help GC

            IntArrayList endChars1 = decompressEndChars(blob1);
            IntArrayList endChars2 = decompressEndChars(blob2);
            endChars1.addAll(endChars2);
            writeCompressedIntArray(dos, endChars1.elements(), endChars1.size(), true, CompressionOverride.DEFAULT);
            endChars1 = null; // Help GC
            endChars2 = null; // Help GC

            IntArrayList synonymIds1 = decompressSynonymIds(blob1);
            IntArrayList synonymIds2 = decompressSynonymIds(blob2);
            synonymIds1.addAll(synonymIds2);
            writeCompressedIntArray(dos, synonymIds1.elements(), synonymIds1.size(), false, CompressionOverride.DEFAULT); // applyDelta = false
            // synonymIds1 = null; // Help GC - these are the last ones, less critical but good practice
            // synonymIds2 = null; // Help GC

            dos.flush();
        }
        return baos.toByteArray();
    }

    /**
     * Merges two compressed PositionListSoA blobs by fully deserializing them,
     * adding all positions from the second to the first, and then re-serializing the result.
     * This method might be faster for smaller lists or when memory is less constrained,
     * as it avoids selective decompression/recompression but uses more peak memory.
     *
     * @param blob1 The first compressed blob.
     * @param blob2 The second compressed blob to merge into the first.
     * @return A new compressed blob containing the merged data.
     * @throws IOException If an I/O error occurs during deserialization or serialization.
     */
    public static byte[] mergeBlobsByFullDeserialization(byte[] blob1, byte[] blob2) throws IOException {
        if (blob1 == null || blob1.length == 0) {
            if (blob2 == null || blob2.length == 0) {
                // Both are empty, return a canonical empty blob
                return new PositionListSoA().serializeToCompositeBlob(CompressionOverride.DEFAULT);
            }
            // Blob1 is empty, blob2 is not, return blob2
            return blob2;
        }
        if (blob2 == null || blob2.length == 0) {
            // Blob2 is empty, blob1 is not, return blob1
            return blob1;
        }

        PositionListSoA list1 = deserializeFromCompositeBlob(blob1);
        PositionListSoA list2 = deserializeFromCompositeBlob(blob2);

        list1.addAll(list2); // Add all elements from list2 to list1

        // Serialize the merged list1 back to a blob
        return list1.serializeToCompositeBlob(CompressionOverride.DEFAULT);
    }

    /**
     * Merges a list of compressed PositionListSoA blobs efficiently by processing
     * attributes one at a time. This minimizes peak memory by not decompressing
     * all attributes of all blobs simultaneously.
     *
     * @param blobsToMerge A list of byte arrays, each a serialized PositionListSoA.
     * @param compressionOverride The compression strategy for the output blob.
     * @return A new compressed blob containing the merged data.
     * @throws IOException If an I/O error occurs.
     */
    public static byte[] mergeNCompressedBlobs(List<byte[]> blobsToMerge, CompressionOverride compressionOverride) throws IOException {
        if (blobsToMerge == null || blobsToMerge.isEmpty()) {
            return new PositionListSoA().serializeToCompositeBlob(compressionOverride);
        }

        // Filter out null or empty blobs and get total positions
        List<byte[]> validBlobs = new java.util.ArrayList<>(); // Explicitly use java.util.ArrayList
        int totalFinalPositions = 0;
        for (byte[] blob : blobsToMerge) {
            if (blob != null && blob.length > 0) {
                validBlobs.add(blob);
                // getNumPositionsFromBlob handles empty/short blobs returning 0 or throwing, which is fine.
                totalFinalPositions += getNumPositionsFromBlob(blob);
            }
        }

        if (totalFinalPositions == 0 && validBlobs.isEmpty()) { // Ensure if totalFinalPositions is 0 due to all blobs being empty/invalid, we return empty.
             return new PositionListSoA().serializeToCompositeBlob(compressionOverride);
        }
         // If totalFinalPositions is 0 but validBlobs is not empty (e.g. contains blobs representing 0 positions),
         // we should still proceed to write an empty PositionListSoA structure (num_positions = 0 followed by empty arrays).

        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(16, totalFinalPositions * 8)); // Min 16 bytes, estimate for others
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(totalFinalPositions);

            if (totalFinalPositions > 0) {
                // Attribute-wise merge only if there are positions to merge
                IntArrayList mergedDocIds = new IntArrayList(totalFinalPositions);
                for (byte[] blob : validBlobs) {
                    mergedDocIds.addAll(decompressDocIds(blob));
                }
                writeCompressedIntArray(dos, mergedDocIds.elements(), mergedDocIds.size(), true, compressionOverride);
                mergedDocIds = null; // Help GC

                IntArrayList mergedSentIds = new IntArrayList(totalFinalPositions);
                for (byte[] blob : validBlobs) {
                    mergedSentIds.addAll(decompressSentenceIds(blob));
                }
                writeCompressedIntArray(dos, mergedSentIds.elements(), mergedSentIds.size(), true, compressionOverride);
                mergedSentIds = null;

                IntArrayList mergedBeginChars = new IntArrayList(totalFinalPositions);
                for (byte[] blob : validBlobs) {
                    mergedBeginChars.addAll(decompressBeginChars(blob));
                }
                writeCompressedIntArray(dos, mergedBeginChars.elements(), mergedBeginChars.size(), true, compressionOverride);
                mergedBeginChars = null;

                IntArrayList mergedEndChars = new IntArrayList(totalFinalPositions);
                for (byte[] blob : validBlobs) {
                    mergedEndChars.addAll(decompressEndChars(blob));
                }
                writeCompressedIntArray(dos, mergedEndChars.elements(), mergedEndChars.size(), true, compressionOverride);
                mergedEndChars = null;

                IntArrayList mergedSynonymIds = new IntArrayList(totalFinalPositions);
                for (byte[] blob : validBlobs) {
                    mergedSynonymIds.addAll(decompressSynonymIds(blob));
                }
                writeCompressedIntArray(dos, mergedSynonymIds.elements(), mergedSynonymIds.size(), false, compressionOverride);
                // mergedSynonymIds = null; // Last one
            } else {
                // If totalFinalPositions is 0, we still need to write out the structure for empty arrays.
                // writeCompressedIntArray handles numElementsInArray == 0 correctly by writing a 0 marker.
                writeCompressedIntArray(dos, new int[0], 0, true, compressionOverride); // docIds
                writeCompressedIntArray(dos, new int[0], 0, true, compressionOverride); // sentIds
                writeCompressedIntArray(dos, new int[0], 0, true, compressionOverride); // beginChars
                writeCompressedIntArray(dos, new int[0], 0, true, compressionOverride); // endChars
                writeCompressedIntArray(dos, new int[0], 0, false, compressionOverride); // synonymIds
            }
            dos.flush();
        }
        return baos.toByteArray();
    }
}