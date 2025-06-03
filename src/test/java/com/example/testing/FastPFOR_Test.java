package com.example.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import me.lemire.integercompression.FastPFOR128;
import me.lemire.integercompression.IntWrapper;
import me.lemire.integercompression.IntegerCODEC;

public class FastPFOR_Test {

    @Test
    public void testFastPFOR_withSpecificSize() {
        System.out.println("Testing FastPFOR128 with specific input size...");

        int numElements = 405888; // Matches 'COUNTRY' synonymIds
        int[] originalData = new int[numElements];
        for (int i = 0; i < numElements; i++) {
            originalData[i] = i + 1; // Sequential integers, similar to synonym IDs
        }

        IntegerCODEC codec = new FastPFOR128();

        // Compression
        System.out.println("Compressing data...");
        IntWrapper inPosCompress = new IntWrapper(0);
        IntWrapper outPosCompress = new IntWrapper(0);
        int[] compressedData = new int[(numElements * 2) + 1024];

        try {
            codec.compress(originalData, inPosCompress, originalData.length, compressedData, outPosCompress);
        } catch (Exception e) {
            System.err.println("Exception during compression:");
            e.printStackTrace();
            fail("Exception during compression: " + e.getMessage());
        }

        int compressedSizeInInts = outPosCompress.get();
        System.out.println("Compression successful.");
        System.out.println("Original data length (ints): " + originalData.length);
        System.out.println("Compressed data length (ints): " + compressedSizeInInts);

        int[] actualCompressedData = Arrays.copyOf(compressedData, compressedSizeInInts);

        // Decompression
        System.out.println("\nDecompressing data...");
        IntWrapper inPosDecompress = new IntWrapper(0);
        IntWrapper outPosDecompress = new IntWrapper(0);
        int[] decompressedData = new int[numElements + 128 + 1024];
        System.out.println("Decompression buffer size (ints): " + decompressedData.length);

        try {
            System.out.println("Calling codec.uncompress with: compressedData.length=" + actualCompressedData.length + ", decompressedBuffer.length=" + decompressedData.length);
            codec.uncompress(actualCompressedData, inPosDecompress, actualCompressedData.length, decompressedData, outPosDecompress);
        } catch (ArrayIndexOutOfBoundsException aioobe) {
            System.err.println("ArrayIndexOutOfBoundsException during decompression!");
            System.err.println("Exception message: " + aioobe.getMessage());
            aioobe.printStackTrace();
            fail("ArrayIndexOutOfBoundsException during decompression: " + aioobe.getMessage());
        } catch (Exception e) {
            System.err.println("Exception during decompression:");
            e.printStackTrace();
            fail("Exception during decompression: " + e.getMessage());
        }

        int decompressedSizeInInts = outPosDecompress.get();
        System.out.println("Decompression successful.");
        System.out.println("Decompressed data length (ints): " + decompressedSizeInInts);

        // Verification
        System.out.println("\nVerifying data...");
        assertEquals(numElements, decompressedSizeInInts, "Mismatch in decompressed size!");
        assertArrayEquals(originalData, Arrays.copyOf(decompressedData, numElements), "Original and decompressed data do not match.");

        System.out.println("Verification successful: Original and decompressed data match.");
    }
}