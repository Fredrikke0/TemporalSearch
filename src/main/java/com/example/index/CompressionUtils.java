package com.example.index;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.logging.ProgressTracker;

/**
 * Utility class for compressing and decompressing temporary index files.
 * Uses GZIP compression to reduce disk space usage while maintaining reasonable
 * compression/decompression speed.
 */
public class CompressionUtils {
    private static final Logger logger = LoggerFactory.getLogger(CompressionUtils.class);
    private static final int BUFFER_SIZE = 8192; // 8KB buffer
    private static final ProgressTracker progress = new ProgressTracker();

    private CompressionUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Compresses a file using GZIP compression.
     *
     * @param sourcePath Path to the source file
     * @param targetPath Path where the compressed file should be written
     * @return The size reduction achieved by compression (original size / compressed size)
     * @throws IOException If an I/O error occurs
     */
    public static double compressFile(Path sourcePath, Path targetPath) throws IOException {
        long startTime = System.currentTimeMillis();
        long originalSize = Files.size(sourcePath);

        progress.startBatch(originalSize);
        try (InputStream in = Files.newInputStream(sourcePath);
             OutputStream out = Files.newOutputStream(targetPath);
             GZIPOutputStream gzipOut = new GZIPOutputStream(out, BUFFER_SIZE)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                gzipOut.write(buffer, 0, bytesRead);
                progress.updateBatch(bytesRead);
            }
        }
        progress.completeBatch();

        long compressedSize = Files.size(targetPath);
        long endTime = System.currentTimeMillis();

        double compressionRatio = (double) originalSize / compressedSize;
        logger.debug("Compressed {} ({} KB -> {} KB, {:.1f}x reduction) in {} ms",
            sourcePath.getFileName(),
            originalSize / 1024,
            compressedSize / 1024,
            compressionRatio,
            endTime - startTime);

        return compressionRatio;
    }

    /**
     * Decompresses a GZIP compressed file.
     *
     * @param sourcePath Path to the compressed file
     * @param targetPath Path where the decompressed file should be written
     * @throws IOException If an I/O error occurs
     */
    public static void decompressFile(Path sourcePath, Path targetPath) throws IOException {
        long startTime = System.currentTimeMillis();
        long compressedSize = Files.size(sourcePath);

        progress.startBatch(compressedSize);
        try (InputStream in = Files.newInputStream(sourcePath);
             GZIPInputStream gzipIn = new GZIPInputStream(in, BUFFER_SIZE);
             OutputStream out = Files.newOutputStream(targetPath)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = gzipIn.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                progress.updateBatch(bytesRead);
            }
        }
        progress.completeBatch();

        long decompressedSize = Files.size(targetPath);
        long endTime = System.currentTimeMillis();

        logger.debug("Decompressed {} ({} KB -> {} KB) in {} ms",
            sourcePath.getFileName(),
            compressedSize / 1024,
            decompressedSize / 1024,
            endTime - startTime);
    }

    /**
     * Creates a compressed output stream for writing data.
     *
     * @param outputPath Path where the compressed data should be written
     * @return A GZIP output stream for writing compressed data
     * @throws IOException If an I/O error occurs
     */
    public static OutputStream createCompressedOutputStream(Path outputPath) throws IOException {
        OutputStream fileOut = Files.newOutputStream(outputPath);
        return new GZIPOutputStream(fileOut, BUFFER_SIZE);
    }

    /**
     * Creates a decompression input stream for reading compressed data.
     *
     * @param inputPath Path to the compressed file
     * @return A GZIP input stream for reading compressed data
     * @throws IOException If an I/O error occurs
     */
    public static InputStream createDecompressionInputStream(Path inputPath) throws IOException {
        InputStream fileIn = Files.newInputStream(inputPath);
        return new GZIPInputStream(fileIn, BUFFER_SIZE);
    }

}