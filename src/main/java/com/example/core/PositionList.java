package com.example.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.index.AnnotationType;
import com.example.index.StitchPosition;
import com.example.logging.LogSampler;

import me.lemire.integercompression.FastPFOR128;
import me.lemire.integercompression.IntWrapper;
import me.lemire.integercompression.IntegerCODEC;

/**
 * Manages collections of Position objects with efficient compression and serialization capabilities.
 * Uses FastPFOR128 compression to minimize storage requirements while maintaining quick access times.
 * Supports operations like merging, sorting, and deduplication of positions. The class handles
 * serialization by separating position data into parallel arrays for optimal compression ratios,
 * making it suitable for storage in key-value databases. Thread-safe for read operations through
 * unmodifiable list views.
 */
public class PositionList {
    private static final Logger logger = LoggerFactory.getLogger(PositionList.class);
    private static final LogSampler logSampler = new LogSampler(0.001);
    private final List<Position> positions;
    private static final IntegerCODEC codec = new FastPFOR128();

    public PositionList() {
        this.positions = new ArrayList<>();
    }

    public synchronized void add(Position position) {
        if (position == null) {
            logger.warn("Attempted to add null position");
            return;
        }
        positions.add(position);

        // if (logSampler.shouldLog()) {
        //     logger.debug("Added position - docId: {}, sentenceId: {}, begin: {}, end: {}",
        //         position.getDocumentId(), position.getSentenceId(),
        //         position.getBeginPosition(), position.getEndPosition());
        // }
    }

    public List<Position> getPositions() {
        return Collections.unmodifiableList(positions);
    }

    public byte[] serialize() throws IOException {
        if (positions.isEmpty()) {
            logger.debug("Serializing empty position list");
            return new byte[0];
        }

        // Trim excess capacity if positions is an ArrayList
        if (positions instanceof ArrayList) {
            ((ArrayList<?>) positions).trimToSize();
        }

        try {
            // Sort positions for efficient compression
            sort();

            int numPositions = positions.size();

            // Prepare arrays for compression
            int[] docIds = new int[numPositions];
            int[] sentenceIds = new int[numPositions];
            int[] beginPositions = new int[numPositions];
            int[] endPositions = new int[numPositions];

            // Prepare type information and synonym IDs (for StitchPosition)
            byte[] positionTypes = new byte[numPositions];
            byte[] annotationTypeOrdinals = new byte[numPositions];
            int[] synonymIds = new int[numPositions];
            int[] annotationBeginChars = new int[numPositions];
            int[] annotationEndChars = new int[numPositions];
            boolean hasSpecialPositions = false;

            // Store original values
            for (int i = 0; i < numPositions; i++) {
                Position pos = positions.get(i);
                docIds[i] = pos.getDocumentId();
                sentenceIds[i] = pos.getSentenceId();
                beginPositions[i] = pos.getBeginPosition();
                endPositions[i] = pos.getEndPosition();

                if (pos instanceof StitchPosition stitchPos) {
                    positionTypes[i] = StitchPosition.POSITION_TYPE;
                    annotationTypeOrdinals[i] = (byte) stitchPos.getType().ordinal();
                    synonymIds[i] = stitchPos.getSynonymId();
                    annotationBeginChars[i] = stitchPos.getAnnotationBeginChar();
                    annotationEndChars[i] = stitchPos.getAnnotationEndChar();
                    hasSpecialPositions = true;
                } else {
                    positionTypes[i] = 0;
                    annotationTypeOrdinals[i] = -1;
                    synonymIds[i] = -1;
                    annotationBeginChars[i] = -1;
                    annotationEndChars[i] = -1;
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream(numPositions * 16); // Initial rough estimate
            DataOutputStream dos = new DataOutputStream(baos);

            // Write metadata
            dos.writeInt(numPositions);
            dos.writeByte(hasSpecialPositions ? 1 : 0); // Flag if we have special positions

            // Compress each array individually
            for (int[] array : new int[][]{docIds, sentenceIds, beginPositions, endPositions}) {
                writeCompressedIntArray(dos, array);
            }

            if (hasSpecialPositions) {
                dos.write(positionTypes);
                dos.write(annotationTypeOrdinals);

                writeCompressedIntArray(dos, synonymIds);
                writeCompressedIntArray(dos, annotationBeginChars);
                writeCompressedIntArray(dos, annotationEndChars);
            }

            dos.flush();
            return baos.toByteArray();

        } catch (Exception e) {
            logger.error("Failed to serialize position list: {}", e.getMessage(), e);
            if (e instanceof IOException) {
                 throw (IOException) e;
            } else if (e instanceof RuntimeException) {
                 throw (RuntimeException) e;
            } else {
                 throw new IOException("Serialization failed due to an unexpected error: " + e.getMessage(), e);
            }
        }
    }

    private void writeCompressedIntArray(DataOutputStream dos, int[] array) throws IOException {
        IntWrapper inOffset = new IntWrapper(0);
        IntWrapper outOffset = new IntWrapper(0);

        if (array.length <= 128) {  // Don't compress small arrays
            dos.writeInt(-array.length); // Negative length indicates uncompressed
            for (int i = 0; i < array.length; i++) {
                dos.writeInt(array[i]);
            }
        } else {
            int blockSize = 128;
            int numBlocks = (array.length + blockSize - 1) / blockSize;
            int paddedSize = numBlocks * blockSize;

            int[] paddedArray = Arrays.copyOf(array, paddedSize);
            if ((long)paddedSize * 2L * 4L > Integer.MAX_VALUE) { // Check for int[] allocation, not long for byte[]
                throw new IOException("Temporary compression buffer for FastPFOR would exceed int array limits for an intermediate int[] buffer.");
            }
            int[] compressed = new int[paddedSize * 2];

            codec.compress(paddedArray, inOffset, paddedSize, compressed, outOffset);
            int compressedSizeInInts = outOffset.get();

            dos.writeInt(array.length);      // Original length
            dos.writeInt(compressedSizeInInts); // Compressed size in ints
            for (int i = 0; i < compressedSizeInInts; i++) {
                dos.writeInt(compressed[i]);
            }
        }
    }

    public static PositionList deserialize(byte[] data) throws IOException {
        PositionList list = new PositionList();
        if (data == null || data.length == 0) {
            logger.debug("Deserializing empty or null data to empty PositionList");
            return list;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        int count = buffer.getInt();
        boolean hasSpecialPositions = buffer.get() == 1;

        if (count == 0) {
            logger.debug("Deserialized PositionList with zero positions");
            return list;
        }

        int[] docIds = new int[count];
        int[] sentenceIds = new int[count];
        int[] beginPositions = new int[count];
        int[] endPositions = new int[count];

        byte[] positionTypes = null;
        byte[] annotationTypeOrdinals = null; // For storing AnnotationType.ordinal()
        int[] synonymIds = null;
        int[] annotationBeginChars = null;
        int[] annotationEndChars = null;

        if (hasSpecialPositions) {
            positionTypes = new byte[count];
            annotationTypeOrdinals = new byte[count];
            synonymIds = new int[count];
            annotationBeginChars = new int[count];
            annotationEndChars = new int[count];
        }

        // Decompress each array
        for (int[] array : new int[][]{docIds, sentenceIds, beginPositions, endPositions}) {
            IntWrapper inOffset = new IntWrapper(0);
            IntWrapper outOffset = new IntWrapper(0);

            int storedLengthMarker = buffer.getInt(); // If negative, it's uncompressed data length & data follows directly.
                                                  // If positive, it's original_length, followed by compressed_size & compressed data.

            if (storedLengthMarker < 0) { // Uncompressed
                int length = -storedLengthMarker;
                // Ensure 'length' matches 'count' for these arrays, or handle error
                if (length != count && array != synonymIds) { // synonymIds might have a different check if it can be shorter than count
                    throw new IOException(String.format("Uncompressed data length mismatch. Expected %d, got %d", count, length));
                }
                for (int i = 0; i < length; i++) {
                     array[i] = buffer.getInt();
                }
            } else { // Compressed
                // Ensure 'storedLengthMarker' matches 'count' for these arrays
                 if (storedLengthMarker != count && array != synonymIds) {
                    throw new IOException(String.format("Compressed data original length mismatch. Expected %d, got %d", count, storedLengthMarker));
                }
                int compressedSize = buffer.getInt();

                int blockSize = 128;
                int numBlocks = (storedLengthMarker + blockSize - 1) / blockSize;
                int paddedSize = numBlocks * blockSize;

                if ((long)paddedSize * 2L > Integer.MAX_VALUE / 4) {
                    throw new IOException("Temporary decompression buffer for FastPFOR would exceed int array limits.");
                }
                int[] compressedData = new int[compressedSize];
                for (int i = 0; i < compressedSize; i++) {
                    compressedData[i] = buffer.getInt();
                }

                int[] decompressedPadded = new int[paddedSize];
                codec.uncompress(compressedData, inOffset, compressedSize, decompressedPadded, outOffset);

                System.arraycopy(decompressedPadded, 0, array, 0, storedLengthMarker);
            }
        }

        if (hasSpecialPositions) {
            buffer.get(positionTypes);
            buffer.get(annotationTypeOrdinals);

            IntWrapper inOffset = new IntWrapper(0); // Declare once for this block
            IntWrapper outOffset = new IntWrapper(0); // Declare once for this block

            // Deserialize synonymIds
            int synonymIdsStoredLengthMarker = buffer.getInt();

            if (synonymIdsStoredLengthMarker < 0) { // Uncompressed
                int length = -synonymIdsStoredLengthMarker;
                 if (length != count ) {
                    throw new IOException(String.format("Uncompressed synonymId data length mismatch. Expected %d, got %d", count, length));
                }
                for (int i = 0; i < length; i++) {
                     synonymIds[i] = buffer.getInt();
                }
            } else { // Compressed
                 if (synonymIdsStoredLengthMarker != count ) {
                    throw new IOException(String.format("Compressed synonymId data original length mismatch. Expected %d, got %d", count, synonymIdsStoredLengthMarker));
                }
                int compressedSize = buffer.getInt();

                int blockSize = 128;
                int numBlocks = (synonymIdsStoredLengthMarker + blockSize - 1) / blockSize;
                int paddedSize = numBlocks * blockSize;

                if ((long)paddedSize * 2L > Integer.MAX_VALUE / 4) {
                    throw new IOException("Temporary decompression buffer for FastPFOR (synonym IDs) would exceed int array limits.");
                }
                int[] compressedData = new int[compressedSize];
                for (int i = 0; i < compressedSize; i++) {
                    compressedData[i] = buffer.getInt();
                }

                int[] decompressedPadded = new int[paddedSize];
                inOffset.set(0); // Reset for each decompression op
                outOffset.set(0); // Reset for each decompression op
                codec.uncompress(compressedData, inOffset, compressedSize, decompressedPadded, outOffset);

                System.arraycopy(decompressedPadded, 0, synonymIds, 0, synonymIdsStoredLengthMarker);
            }

            // Deserialize annotationBeginChars and annotationEndChars
            for (int[] array : new int[][]{annotationBeginChars, annotationEndChars}) {
                int storedLengthMarker = buffer.getInt(); // This was the one flagged as duplicate - should be fine if synonymIds uses a different name

                if (storedLengthMarker < 0) { // Uncompressed
                    int length = -storedLengthMarker;
                    if (length != count) {
                        throw new IOException(String.format("Uncompressed annotation char data length mismatch. Expected %d, got %d", count, length));
                    }
                    for (int i = 0; i < length; i++) {
                        array[i] = buffer.getInt();
                    }
                } else { // Compressed
                    if (storedLengthMarker != count) {
                        throw new IOException(String.format("Compressed annotation char data original length mismatch. Expected %d, got %d", count, storedLengthMarker));
                    }
                    int compressedSize = buffer.getInt();
                    int blockSize = 128;
                    int numBlocks = (storedLengthMarker + blockSize - 1) / blockSize;
                    int paddedSize = numBlocks * blockSize;
                    if ((long)paddedSize * 2L > Integer.MAX_VALUE / 4) {
                        throw new IOException("Temporary decompression buffer for FastPFOR (annotation chars) would exceed int array limits.");
                    }
                    int[] compressedData = new int[compressedSize];
                    for (int i = 0; i < compressedSize; i++) {
                        compressedData[i] = buffer.getInt();
                    }
                    int[] decompressedPadded = new int[paddedSize];
                    inOffset.set(0); // Reset for each decompression op
                    outOffset.set(0); // Reset for each decompression op
                    codec.uncompress(compressedData, inOffset, compressedSize, decompressedPadded, outOffset);
                    System.arraycopy(decompressedPadded, 0, array, 0, storedLengthMarker);
                }
            }
        }

        // Reconstruct positions
        for (int i = 0; i < count; i++) {
            if (hasSpecialPositions && positionTypes[i] == StitchPosition.POSITION_TYPE) {
                // Ensure the ordinal is valid before converting to AnnotationType
                AnnotationType type = AnnotationType.values()[annotationTypeOrdinals[i]];
                 list.add(new StitchPosition(docIds[i], sentenceIds[i], beginPositions[i], endPositions[i],
                                            type, // Use deserialized type
                                            synonymIds[i],
                                            annotationBeginChars[i],
                                            annotationEndChars[i]));
            } else {
                list.add(new Position(docIds[i], sentenceIds[i], beginPositions[i], endPositions[i]));
            }
        }
        // if (logSampler.shouldLog()) {
        //     logger.debug("Deserialized {} positions successfully.", list.size());
        // }
        return list;
    }

    public synchronized void merge(PositionList other) {
        if (other == null) {
            logger.warn("Attempted to merge with null PositionList");
            return;
        }

        int initialSize = positions.size();
        int otherSize = other.size();

        try {
            // Use TreeSet with custom comparator for ordered deduplication
            TreeSet<Position> positionSet = new TreeSet<>((a, b) -> {
                // First compare document IDs
                int docCompare = Integer.compare(a.getDocumentId(), b.getDocumentId());
                if (docCompare != 0) return docCompare;

                // Then compare sentence IDs
                int sentCompare = Integer.compare(a.getSentenceId(), b.getSentenceId());
                if (sentCompare != 0) return sentCompare;

                // For positions within the same sentence, we need to be careful:
                // - For overlapping positions (within 2 chars), treat them as the same
                // - For non-overlapping positions, keep them separate
                int beginDiff = Math.abs(a.getBeginPosition() - b.getBeginPosition());
                int endDiff = Math.abs(a.getEndPosition() - b.getEndPosition());

                // If positions overlap significantly, treat them as the same
                if (beginDiff <= 2 && endDiff <= 2) {
                    logger.debug("Overlapping positions: {} and {}", a, b);
                    return 0; // Treat as equal/same position
                }

                // Otherwise, order by begin position then end position
                int beginCompare = Integer.compare(a.getBeginPosition(), b.getBeginPosition());
                if (beginCompare != 0) return beginCompare;

                return Integer.compare(a.getEndPosition(), b.getEndPosition());
            });

            // Add all positions to the set for deduplication
            positionSet.addAll(positions);
            positionSet.addAll(other.getPositions());

            // Clear and re-add all positions in sorted order
            positions.clear();
            positions.addAll(positionSet);

            if (logSampler.shouldLog()) {
                logger.debug("Merged {} + {} positions into {} unique positions",
                    initialSize, otherSize, positions.size());
            }
        } catch (Exception e) {
            logger.error("Failed to merge position lists: {}", e.getMessage(), e);
            throw e;
        }
    }

    public int size() {
        return positions.size();
    }

    public void sort() {
        Collections.sort(positions, (a, b) -> {
            int docCompare = Integer.compare(a.getDocumentId(), b.getDocumentId());
            if (docCompare != 0) return docCompare;

            int sentCompare = Integer.compare(a.getSentenceId(), b.getSentenceId());
            if (sentCompare != 0) return sentCompare;

            int beginCompare = Integer.compare(a.getBeginPosition(), b.getBeginPosition());
            if (beginCompare != 0) return beginCompare;

            return Integer.compare(a.getEndPosition(), b.getEndPosition());
        });
    }

    /**
     * Efficiently retrieves the number of positions from a serialized PositionList byte array
     * without fully deserializing the entire list.
     *
     * @param data The serialized byte array of a PositionList.
     * @return The count of positions, or 0 if the data is invalid or empty.
     */
    public static int getPositionCountFromSerialized(byte[] data) {
        if (data == null || data.length < 4) { // Minimum 4 bytes for an int (the count)
            // logger.debug("Cannot get position count from null, empty, or too short data array (length: {}).", data == null ? "null" : data.length);
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        try {
            return buffer.getInt(); // The first int in the serialized data is the count of positions.
        } catch (java.nio.BufferUnderflowException e) {
            logger.warn("Buffer underflow when trying to read position count from serialized data. Data length: {}. Expected at least 4 bytes for count.", data.length, e);
            return 0; // Or throw a custom exception if this case should be handled more strictly.
        }
    }
}
