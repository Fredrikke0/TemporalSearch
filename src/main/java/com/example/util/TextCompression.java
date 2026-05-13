package com.example.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Utility for compressing and decompressing document text using JDK
 * {@link Deflater}
 * (raw deflate, no GZIP wrapper). This avoids the ~20-byte GZIP header/footer
 * per
 * document which adds up for large collections.
 *
 * <p>
 * Typical compression ratio for English prose text is 2.5&ndash;4&times;.
 * Decompression cost is in the microsecond to low-millisecond range for typical
 * documents, negligible compared to NLP pipeline processing.
 * </p>
 *
 * <p>
 * Usage:
 * 
 * <pre>{@code
 * // Compress for storage
 * byte[] compressed = TextCompression.compress("some text");
 * preparedStatement.setBytes(1, compressed);
 *
 * // Decompress on read
 * byte[] compressed = resultSet.getBytes("text");
 * String text = TextCompression.decompress(compressed);
 * }</pre>
 */
public final class TextCompression {

    private TextCompression() {
        /* utility class */ }

    /**
     * Compresses a string using deflate (best compression level).
     *
     * @param text the text to compress; must not be null
     * @return the compressed bytes
     */
    public static byte[] compress(String text) {
        if (text.isEmpty()) {
            return new byte[0];
        }
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length);
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            baos.write(buf, 0, n);
        }
        deflater.end();
        return baos.toByteArray();
    }

    /**
     * Decompresses bytes produced by {@link #compress(String)} back to a string.
     *
     * @param data the compressed bytes; must not be null
     * @return the original decompressed text
     * @throws RuntimeException wrapping {@link DataFormatException} if data is
     *                          corrupt
     */
    public static String decompress(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        Inflater inflater = new Inflater();
        inflater.setInput(data);

        // Start with an estimate: typical compression ratio ~3x, but cap at 100KB
        int estimatedSize = Math.min(data.length * 4, 100_000);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(estimatedSize);
        byte[] buf = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                baos.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            throw new RuntimeException("Failed to decompress text data", e);
        } finally {
            inflater.end();
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
