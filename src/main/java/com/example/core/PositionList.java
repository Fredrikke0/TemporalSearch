package com.example.core;

import me.lemire.integercompression.*;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.logging.LogSampler;
import com.example.index.StitchPosition;
import com.example.index.AnnotationType;
import java.io.IOException;

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

        try {
            // Sort positions for efficient compression
            sort();

            // Prepare arrays for compression
            int[] docIds = new int[positions.size()];
            int[] sentenceIds = new int[positions.size()];
            int[] beginPositions = new int[positions.size()];
            int[] endPositions = new int[positions.size()];
            
            // Prepare type information and synonym IDs (for StitchPosition)
            byte[] positionTypes = new byte[positions.size()]; // Allocated whether or not hasSpecialPositions is true, to simplify logic. Optimized out if not used.
            int[] synonymIds = new int[positions.size()];    // Allocated whether or not hasSpecialPositions is true.
            boolean hasSpecialPositions = false;
            
            // Store original values
            for (int i = 0; i < positions.size(); i++) {
                Position pos = positions.get(i);
                docIds[i] = pos.getDocumentId();
                sentenceIds[i] = pos.getSentenceId();
                beginPositions[i] = pos.getBeginPosition();
                endPositions[i] = pos.getEndPosition();
                
                // Store position type and synonym ID if applicable
                if (pos instanceof StitchPosition) {
                    positionTypes[i] = StitchPosition.POSITION_TYPE;
                    synonymIds[i] = ((StitchPosition) pos).getSynonymId();
                    hasSpecialPositions = true;
                } else {
                    positionTypes[i] = 0; // Regular position
                    synonymIds[i] = -1;   // Invalid synonym ID
                }
            }

            if (logSampler.shouldLog()) {
                logger.debug("Serializing {} positions, first docId: {}, last docId: {}",
                    positions.size(), docIds[0], docIds[positions.size() - 1]);
            }

            // Calculate required capacity robustly
            long calculatedCapacity = 4L; // count (int)
            calculatedCapacity += 1L; // hasSpecialPositions (byte)
            long numPositions = positions.size();
            long paddedNumPositions = ((numPositions + 128L - 1L) / 128L) * 128L; // Padded size for compression blocks

            // For the 4 main int arrays (docIds, sentenceIds, beginPositions, endPositions)
            for (int i = 0; i < 4; i++) {
                calculatedCapacity += 4L; // original length field (int)
                if (numPositions <= 128) { // Uncompressed
                    calculatedCapacity += numPositions * 4L; // data
                } else { // Compressed
                    calculatedCapacity += 4L; // compressed size field (int)
                    calculatedCapacity += paddedNumPositions * 2L * 4L; // Max compressed data in bytes
                }
            }

            if (hasSpecialPositions) {
                calculatedCapacity += numPositions; // positionTypes (byte array)
                
                // synonymIds array (treated like other int arrays)
                calculatedCapacity += 4L; // original length field (int)
                if (numPositions <= 128) { // Uncompressed
                    calculatedCapacity += numPositions * 4L;
                } else { // Compressed
                    calculatedCapacity += 4L; // compressed size field (int)
                    calculatedCapacity += paddedNumPositions * 2L * 4L; // Max compressed data in bytes
                }
            }
            
            calculatedCapacity += 256L; // General buffer / contingency

            if (calculatedCapacity > Integer.MAX_VALUE) {
                String errorMessage = String.format(
                    "Calculated serialized size (%d bytes) for %d positions exceeds maximum ByteBuffer capacity (%d bytes). " +
                    "This PositionList is too large to be stored as a single entry.",
                    calculatedCapacity, numPositions, Integer.MAX_VALUE);
                logger.error(errorMessage);
                throw new IOException(errorMessage);
            }
            
            ByteBuffer buffer = ByteBuffer.allocate((int) calculatedCapacity);

            // Write metadata
            buffer.putInt(positions.size());
            buffer.put((byte)(hasSpecialPositions ? 1 : 0)); // Flag if we have special positions

            // Compress each array individually with proper size checks
            for (int[] array : new int[][]{docIds, sentenceIds, beginPositions, endPositions}) {
                IntWrapper inOffset = new IntWrapper(0);
                IntWrapper outOffset = new IntWrapper(0);
                
                if (array.length <= 128) {  // Don't compress small arrays
                    buffer.putInt(-array.length);
                    for (int i = 0; i < array.length; i++) {
                        buffer.putInt(array[i]);
                    }
                } else {
                    // Calculate number of complete blocks
                    int blockSize = 128;
                    int numBlocks = (array.length + blockSize - 1) / blockSize;
                    int paddedSize = numBlocks * blockSize;
                    
                    // Create padded array
                    int[] paddedArray = Arrays.copyOf(array, paddedSize);
                    // Ensure the temporary compression buffer itself doesn't exceed memory limits for int arrays
                    if ((long)paddedSize * 2L > Integer.MAX_VALUE / 4) { // Check if paddedSize*2*sizeof(int) is too large
                        throw new IOException("Temporary compression buffer for FastPFOR would exceed int array limits.");
                    }
                    int[] compressed = new int[paddedSize * 2]; // Double size for safety
                    
                    // Try compression
                    codec.compress(paddedArray, inOffset, paddedSize, compressed, outOffset);
                    int compressedSize = outOffset.get();
                    
                    // Store the actual length and compressed size
                    buffer.putInt(array.length);  // Original length
                    buffer.putInt(compressedSize); // Compressed size
                    for (int i = 0; i < compressedSize; i++) {
                        buffer.putInt(compressed[i]);
                    }
                }
            }

            // Write position type information if needed
            if (hasSpecialPositions) {
                // Write position types (1 byte per position)
                buffer.put(positionTypes);
                
                // Write synonym IDs if we have StitchPositions
                IntWrapper inOffset = new IntWrapper(0);
                IntWrapper outOffset = new IntWrapper(0);
                
                if (positions.size() <= 128) {  // Don't compress small arrays
                    buffer.putInt(-positions.size());
                    for (int i = 0; i < positions.size(); i++) {
                        buffer.putInt(synonymIds[i]);
                    }
                } else {
                    // Calculate number of complete blocks
                    int blockSize = 128;
                    int numBlocks = (positions.size() + blockSize - 1) / blockSize;
                    int paddedSize = numBlocks * blockSize;
                    
                    // Ensure the temporary compression buffer itself doesn't exceed memory limits for int arrays
                     if ((long)paddedSize * 2L > Integer.MAX_VALUE / 4) { // Check if paddedSize*2*sizeof(int) is too large
                        throw new IOException("Temporary compression buffer for FastPFOR (synonym IDs) would exceed int array limits.");
                    }
                    int[] paddedArray = Arrays.copyOf(synonymIds, paddedSize);
                    int[] compressed = new int[paddedSize * 2]; // Double size for safety
                    
                    // Try compression
                    codec.compress(paddedArray, inOffset, paddedSize, compressed, outOffset);
                    int compressedSize = outOffset.get();
                    
                    // Store the actual length and compressed size
                    buffer.putInt(positions.size());  // Original length
                    buffer.putInt(compressedSize);    // Compressed size
                    for (int i = 0; i < compressedSize; i++) {
                        buffer.putInt(compressed[i]);
                    }
                }
            }

            // Create exact-sized result
            byte[] result = new byte[buffer.position()];
            System.arraycopy(buffer.array(), 0, result, 0, buffer.position());
            return result;
        } catch (Exception e) {
            logger.error("Failed to serialize position list: {}", e.getMessage(), e);
            // If it's not already an IOException, wrap it to satisfy the method signature if necessary,
            // or ensure all paths that can throw checked exceptions are handled.
            // The primary concern was the calculatedCapacity leading to an IOException.
            // Other exceptions like BufferOverflowException (if estimation is off) are RuntimeExceptions.
            if (e instanceof IOException) {
                 throw (IOException) e;
            } else if (e instanceof RuntimeException) {
                 throw (RuntimeException) e; // Re-throw runtime exceptions
            } else {
                 // Wrap other checked exceptions if any were introduced (though none are apparent here from the changes)
                 throw new IOException("Serialization failed due to an unexpected error: " + e.getMessage(), e);
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
        int[] synonymIds = null;
        
        if (hasSpecialPositions) {
            positionTypes = new byte[count];
            synonymIds = new int[count];
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
            
            IntWrapper inOffset = new IntWrapper(0);
            IntWrapper outOffset = new IntWrapper(0);

            int storedLengthMarker = buffer.getInt(); 

            if (storedLengthMarker < 0) { // Uncompressed
                int length = -storedLengthMarker;
                 if (length != count ) {
                    throw new IOException(String.format("Uncompressed synonymId data length mismatch. Expected %d, got %d", count, length));
                }
                for (int i = 0; i < length; i++) {
                     synonymIds[i] = buffer.getInt();
                }
            } else { // Compressed
                 if (storedLengthMarker != count ) {
                    throw new IOException(String.format("Compressed synonymId data original length mismatch. Expected %d, got %d", count, storedLengthMarker));
                }
                int compressedSize = buffer.getInt();

                int blockSize = 128;
                int numBlocks = (storedLengthMarker + blockSize - 1) / blockSize;
                int paddedSize = numBlocks * blockSize;

                if ((long)paddedSize * 2L > Integer.MAX_VALUE / 4) { 
                    throw new IOException("Temporary decompression buffer for FastPFOR (synonym IDs) would exceed int array limits.");
                }
                int[] compressedData = new int[compressedSize]; 
                for (int i = 0; i < compressedSize; i++) {
                    compressedData[i] = buffer.getInt();
                }
                
                int[] decompressedPadded = new int[paddedSize];
                codec.uncompress(compressedData, inOffset, compressedSize, decompressedPadded, outOffset);
                
                System.arraycopy(decompressedPadded, 0, synonymIds, 0, storedLengthMarker); 
            }
        }

        // Reconstruct positions
        for (int i = 0; i < count; i++) {
            if (hasSpecialPositions && positionTypes[i] == StitchPosition.POSITION_TYPE) {
                 list.add(new StitchPosition(docIds[i], sentenceIds[i], beginPositions[i], endPositions[i], 
                                            AnnotationType.DATE,
                                            synonymIds[i]));
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
