package com.example.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

class CompressionUtilsTest {
    @TempDir
    Path tempDir;

    private Path sourcePath;
    private Path compressedPath;
    private Path decompressedPath;

    @BeforeEach
    void setUp() throws IOException {
        sourcePath = tempDir.resolve("source.txt");
        compressedPath = tempDir.resolve("compressed.gz");
        decompressedPath = tempDir.resolve("decompressed.txt");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(sourcePath);
        Files.deleteIfExists(compressedPath);
        Files.deleteIfExists(decompressedPath);
    }

    @Test
    void testCompressAndDecompress() throws IOException {
        // Create a test file with repeating content for good compression
        String content = "This is a test string that will be repeated many times. ";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            builder.append(content);
        }
        Files.write(sourcePath, builder.toString().getBytes(StandardCharsets.UTF_8));

        // Compress the file
        double compressionRatio = CompressionUtils.compressFile(sourcePath, compressedPath);
        assertTrue(compressionRatio > 1.0, "Compression should achieve some size reduction");
        assertTrue(Files.size(compressedPath) < Files.size(sourcePath),
            "Compressed file should be smaller than source");

        // Decompress the file
        CompressionUtils.decompressFile(compressedPath, decompressedPath);

        // Verify the content matches
        byte[] originalBytes = Files.readAllBytes(sourcePath);
        byte[] decompressedBytes = Files.readAllBytes(decompressedPath);
        assertArrayEquals(originalBytes, decompressedBytes,
            "Decompressed content should match original");
    }

    @Test
    void testStreamCompression() throws IOException {
        String testData = "Test data for stream compression";

        // Write compressed data
        try (OutputStream out = CompressionUtils.createCompressedOutputStream(compressedPath)) {
            out.write(testData.getBytes(StandardCharsets.UTF_8));
        }

        // Read compressed data
        StringBuilder result = new StringBuilder();
        try (InputStream in = CompressionUtils.createDecompressionInputStream(compressedPath);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int charsRead;
            while ((charsRead = reader.read(buffer)) != -1) {
                result.append(buffer, 0, charsRead);
            }
        }

        assertEquals(testData, result.toString(),
            "Data read from compressed stream should match original");
    }

    @Test
    void testLargeFileCompression() throws IOException {
        // Create a large file with random data
        try (OutputStream out = Files.newOutputStream(sourcePath)) {
            byte[] buffer = new byte[1024];
            Random random = new Random(42); // Use fixed seed for reproducibility
            for (int i = 0; i < 1024; i++) { // 1MB total
                random.nextBytes(buffer);
                out.write(buffer);
            }
        }

        // Compress and decompress
        CompressionUtils.compressFile(sourcePath, compressedPath);
        CompressionUtils.decompressFile(compressedPath, decompressedPath);

        // Verify
        assertTrue(Files.mismatch(sourcePath, decompressedPath) == -1,
            "Large file content should match after compression/decompression");
    }

    @Test
    void testCompressEmptyFile() throws IOException {
        // Create empty file
        Files.createFile(sourcePath);

        // Compress empty file
        CompressionUtils.compressFile(sourcePath, compressedPath);

        // Decompress empty file
        CompressionUtils.decompressFile(compressedPath, decompressedPath);

        assertEquals(0, Files.size(sourcePath),
            "Source file should be empty");
        assertEquals(0, Files.size(decompressedPath),
            "Decompressed file should be empty");
        assertTrue(Files.size(compressedPath) > 0,
            "Compressed file should contain at least GZIP header");
    }
}