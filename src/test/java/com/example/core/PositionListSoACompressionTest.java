package com.example.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

public class PositionListSoACompressionTest {

    private static final int POSITIONS_PER_DOC_FOR_TEST = 10; // Arbitrary for testing

    private PositionListSoA generatePositionList(int numPositions) {
        return generatePositionList(numPositions, 0, 1); // Defaults to old behavior
    }

    private PositionListSoA generatePositionList(int numPositions, int startDocId, int maxDocIdIncrement) {
        PositionListSoA list = new PositionListSoA();
        Random random = new Random(42); // Seed for reproducibility

        int currentDocId = startDocId;
        int sentenceIdCounter = 0;
        int charCounter = 0;

        for (int i = 0; i < numPositions; i++) {
            if (i > 0 && i % POSITIONS_PER_DOC_FOR_TEST == 0) {
                currentDocId += (1 + random.nextInt(maxDocIdIncrement)); // Increment with a gap
                sentenceIdCounter = 0; // Reset for new document
                charCounter = 0;       // Reset for new document
            }

            int docId = currentDocId;
            // Make sentenceId, beginChar, endChar somewhat realistic but simple for testing
            int sentId = sentenceIdCounter++;
            int beginChar = charCounter + random.nextInt(5); // Small random increment
            int endChar = beginChar + random.nextInt(10) + 5; // Ensure endChar > beginChar
            charCounter = endChar + random.nextInt(5) + 1; // Next position starts after this one

            list.add(docId, sentId, beginChar, endChar);
        }
        return list;
    }

    @Test
    public void testCompressionThresholds() throws IOException {
        List<Integer> testSizes = Arrays.asList(8, 16, 20, 24, 28, 31, 32, 33, 64, 127, 128);

        System.out.println("PositionListSoA Compression Analysis (Dense DocIDs):");
        System.out.println("--------------------------------------------------------------------------------------------------");
        System.out.printf("%-10s | %-15s | %-25s |  %-25s | %-12s | %-12s%n",
                "List Size", "Default (bytes)", "Force Compress (bytes)", "Force Uncompress (bytes)", "FC % vs Def", "FU % vs Def");
        System.out.println("--------------------------------------------------------------------------------------------------");

        for (int size : testSizes) {
            PositionListSoA pl = generatePositionList(size);
            assertNotNull(pl);

            byte[] defaultBlob = pl.serializeToCompositeBlob(PositionListSoA.CompressionOverride.DEFAULT);
            byte[] forceCompressBlob = pl.serializeToCompositeBlob(PositionListSoA.CompressionOverride.FORCE_COMPRESSION);
            byte[] forceUncompressedBlob = pl.serializeToCompositeBlob(PositionListSoA.CompressionOverride.FORCE_UNCOMPRESSED);

            double fcPercentage = defaultBlob.length == 0 ? 0 : ((double)(forceCompressBlob.length - defaultBlob.length) / defaultBlob.length) * 100;
            double fuPercentage = defaultBlob.length == 0 ? 0 : ((double)(forceUncompressedBlob.length - defaultBlob.length) / defaultBlob.length) * 100;

            System.out.printf("%-10d | %-15d | %-25d | %-25d | %-11.2f%% | %-11.2f%% %n",
                    size, defaultBlob.length, forceCompressBlob.length, forceUncompressedBlob.length, fcPercentage, fuPercentage);
        }
        System.out.println("--------------------------------------------------------------------------------------------------");
        System.out.println("Note: 'Force Compress' may still use RLE for constant arrays if not delta coded.");
        System.out.println("      'Force Uncompress' bypasses RLE and FastPFOR.");
        System.out.println("      'Default' uses UNCOMPRESSED_THRESHOLD (" + PositionListSoA.UNCOMPRESSED_THRESHOLD + ") and RLE.");
    }

    @Test
    public void testCompressionWithLargeAndSparseDocIds() throws IOException {
        List<Integer> testSizes = Arrays.asList(8, 16, 20, 24, 28, 31, 32, 33, 64, 127, 128);
        int startDocId = 1_000_000; // Starting with a large doc ID
        int maxDocIdIncrement = 5000;  // Doc IDs can be far apart

        System.out.println("\nPositionListSoA Compression Analysis (Large & Sparse DocIDs):");
        System.out.println("Start DocID: " + startDocId + ", Max Increment: " + maxDocIdIncrement + ", Positions/Doc: " + POSITIONS_PER_DOC_FOR_TEST);
        System.out.println("--------------------------------------------------------------------------------------------------");
        System.out.printf("%-10s | %-15s | %-20s | %-20s | %-12s | %-12s%n",
                "List Size", "Default (bytes)", "Force Compress (bytes)", "Force Uncompress (bytes)", "FC % vs Def", "FU % vs Def");
        System.out.println("--------------------------------------------------------------------------------------------------");

        for (int size : testSizes) {
            PositionListSoA pl = generatePositionList(size, startDocId, maxDocIdIncrement);
            assertNotNull(pl);

            byte[] defaultBlob = pl.serializeToCompositeBlob(PositionListSoA.CompressionOverride.DEFAULT);
            byte[] forceCompressBlob = pl.serializeToCompositeBlob(PositionListSoA.CompressionOverride.FORCE_COMPRESSION);
            byte[] forceUncompressedBlob = pl.serializeToCompositeBlob(PositionListSoA.CompressionOverride.FORCE_UNCOMPRESSED);

            double fcPercentage = defaultBlob.length == 0 ? 0 : ((double)(forceCompressBlob.length - defaultBlob.length) / defaultBlob.length) * 100;
            double fuPercentage = defaultBlob.length == 0 ? 0 : ((double)(forceUncompressedBlob.length - defaultBlob.length) / defaultBlob.length) * 100;

            System.out.printf("%-10d | %-15d | %-20d | %-20d | %-11.2f%% | %-11.2f%% %n",
                    size, defaultBlob.length, forceCompressBlob.length, forceUncompressedBlob.length, fcPercentage, fuPercentage);
        }
        System.out.println("--------------------------------------------------------------------------------------------------");
        System.out.println("Note: 'Force Compress' may still use RLE for constant arrays if not delta coded.");
        System.out.println("      'Force Uncompress' bypasses RLE and FastPFOR.");
        System.out.println("      'Default' uses UNCOMPRESSED_THRESHOLD (" + PositionListSoA.UNCOMPRESSED_THRESHOLD + ") and RLE.");
    }
}