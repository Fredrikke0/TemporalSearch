package com.example.index;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Binary on-disk format for sorted index entries during external merge.
 *
 * <h3>File format</h3>
 * Each entry: {@code [keyLen:int][key:byte[]][valLen:int][val:byte[]]}
 * All integers are big-endian (Java {@code DataOutput} convention).
 * Entries are sorted by unsigned bytewise key comparison
 * ({@link IndexKey#compareBytes}).
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * // Writing batch files
 * try (BinarySortedFile.Writer w = BinarySortedFile.writer(file)) {
 *     w.writeEntry(keyBytes, valueBytes);
 * }
 *
 * // Merging multiple sorted files
 * BinarySortedFile.merge(inputFiles, outputFile);
 *
 * // Reading for ingestion
 * try (BinarySortedFile.Reader r = BinarySortedFile.reader(file)) {
 *     while (r.advance()) {
 *         byte[] key = r.currentKey();
 *         byte[] value = r.currentValue();
 *     }
 * }
 * }</pre>
 */
public final class BinarySortedFile {

    /** File extension for binary sorted files. */
    public static final String EXTENSION = ".bsf";

    /**
     * Default IO buffer size (64 KB).
     * Kept moderate because the OS page cache already provides read-ahead;
     * a large per-file buffer multiplies by the number of files being merged
     * and can cause OOM with many input files.
     */
    public static final int DEFAULT_BUFFER_BYTES = 64 << 10;

    /**
     * Buffer size for single-file sequential I/O (1 MB).
     * Used when only one file is being read sequentially (e.g. the final
     * sorted file in writeToLevelDB), where a large buffer reduces syscall
     * overhead without multiplying across many file handles.
     */
    public static final int SEQUENTIAL_BUFFER_BYTES = 1 << 20;

    /**
     * Maximum number of files to open simultaneously during a merge pass.
     * Must stay well below the OS file-descriptor limit (default 1024 on
     * Linux). When the input list exceeds this, merging is done in multiple
     * passes &mdash; grouping files into batches, merging each batch, then
     * merging the results. This keeps fd usage bounded while allowing the
     * caller to accumulate many temp files between merges for I/O efficiency.
     */
    public static final int MAX_MERGE_FAN_IN = 500;

    /** Default filename for the final merged output file. */
    public static final String DEFAULT_OUTPUT_FILENAME = "sorted" + EXTENSION;

    private BinarySortedFile() {
        // static utility class
    }

    // ----------------------------------------------------------------- factories

    /** Creates a writer for a new binary sorted file with default buffer size. */
    public static Writer writer(File file) throws IOException {
        return new Writer(file, DEFAULT_BUFFER_BYTES);
    }

    /** Creates a writer with a custom IO buffer size in bytes. */
    public static Writer writer(File file, int bufferBytes) throws IOException {
        return new Writer(file, bufferBytes);
    }

    /**
     * Creates a reader for an existing binary sorted file with default buffer size.
     */
    public static Reader reader(File file) throws IOException {
        return new Reader(file, DEFAULT_BUFFER_BYTES);
    }

    /** Creates a reader with a custom IO buffer size in bytes. */
    public static Reader reader(File file, int bufferBytes) throws IOException {
        return new Reader(file, bufferBytes);
    }

    // ----------------------------------------------------------------- merge

    /**
     * Maximum allowed key or value length to guard against corrupted files
     * (100 MB).
     */
    public static final int MAX_ENTRY_LENGTH = 100 << 20;

    /**
     * K-way merge of sorted input files into a single sorted output file.
     * When the number of inputs exceeds {@link #MAX_MERGE_FAN_IN}, merging
     * is done in multiple passes to stay within file-descriptor limits.
     * All input files must be in the binary sorted format and individually
     * sorted. Entries with equal keys are preserved in arbitrary order
     * (deduplication happens downstream).
     *
     * @param inputs sorted input files (non-empty files only; empty files are
     *               skipped)
     * @param output the merged output file (created or overwritten)
     * @throws IOException if an I/O error occurs
     */
    public static void merge(List<File> inputs, File output) throws IOException {
        // Filter out empty files
        List<File> nonEmpty = new ArrayList<>();
        for (File f : inputs) {
            if (f.length() > 0) {
                nonEmpty.add(f);
            }
        }

        if (nonEmpty.isEmpty()) {
            new FileOutputStream(output).close();
            return;
        }
        if (nonEmpty.size() == 1) {
            copyFile(nonEmpty.get(0), output);
            return;
        }

        // Multi-pass merge when inputs exceed the safe fan-in limit.
        if (nonEmpty.size() > MAX_MERGE_FAN_IN) {
            mergeMultiPass(nonEmpty, output);
            return;
        }

        mergeSinglePass(nonEmpty, output);
    }

    /**
     * Single-pass k-way merge. Caller guarantees {@code inputs.size() >= 2}
     * and within {@link #MAX_MERGE_FAN_IN}.
     */
    private static void mergeSinglePass(List<File> inputs, File output) throws IOException {
        List<Reader> readers = new ArrayList<>(inputs.size());
        try {
            for (File f : inputs) {
                readers.add(new Reader(f, DEFAULT_BUFFER_BYTES));
            }

            PriorityQueue<Reader> heap = new PriorityQueue<>(
                    (a, b) -> IndexKey.compareBytes(a.currentKey, b.currentKey));

            for (Reader r : readers) {
                if (r.advance()) {
                    heap.add(r);
                } else {
                    r.close();
                }
            }

            try (Writer out = new Writer(output, DEFAULT_BUFFER_BYTES)) {
                while (!heap.isEmpty()) {
                    Reader r = heap.poll();
                    out.writeEntry(r.currentKey, r.currentValue);
                    if (r.advance()) {
                        heap.add(r);
                    } else {
                        r.close();
                    }
                }
            }
        } finally {
            for (Reader r : readers) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Multi-pass merge: splits inputs into batches of at most
     * {@link #MAX_MERGE_FAN_IN}, merges each batch, then recursively merges
     * the results until a single output file remains.
     */
    private static void mergeMultiPass(List<File> inputs, File output) throws IOException {
        Path tempDir = output.getAbsoluteFile().toPath().getParent();
        List<File> current = new ArrayList<>(inputs);

        while (current.size() > MAX_MERGE_FAN_IN) {
            List<File> next = new ArrayList<>();
            for (int i = 0; i < current.size(); i += MAX_MERGE_FAN_IN) {
                int end = Math.min(i + MAX_MERGE_FAN_IN, current.size());
                List<File> batch = current.subList(i, end);
                File merged = File.createTempFile("mergepass-", EXTENSION,
                        tempDir.toFile());
                mergeSinglePass(batch, merged);
                next.add(merged);
                // Delete batch inputs now that they have been merged
                for (File f : batch) {
                    try {
                        Files.deleteIfExists(f.toPath());
                    } catch (IOException ignored) {
                    }
                }
            }
            current = next;
        }

        // Final pass: copy (or move) the last file to the requested output
        if (current.size() == 1) {
            copyFile(current.get(0), output);
        } else {
            mergeSinglePass(current, output);
            for (File f : current) {
                try {
                    Files.deleteIfExists(f.toPath());
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Copies a single source file to the destination, falling back to
     * copy+delete when an atomic move is not possible (e.g. across filesystems).
     */
    private static void copyFile(File source, File output) throws IOException {
        try {
            Files.move(source.toPath(), output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.copy(source.toPath(), output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.delete(source.toPath());
        }
    }

    // ==================================================================== Writer

    /**
     * Writes entries to a binary sorted file. Caller is responsible for
     * writing entries in sorted order.
     */
    public static final class Writer implements AutoCloseable {
        private final DataOutputStream out;

        private Writer(File file, int bufferBytes) throws IOException {
            this.out = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(file), bufferBytes));
        }

        /**
         * Writes a single key-value entry. The caller must ensure entries are
         * written in sorted order (unsigned bytewise by key).
         */
        public void writeEntry(byte[] key, byte[] value) throws IOException {
            out.writeInt(key.length);
            out.write(key);
            out.writeInt(value.length);
            out.write(value);
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    // ==================================================================== Reader

    /**
     * Reads entries sequentially from a binary sorted file. Call
     * {@link #advance()} to load the next entry, then access its key and value
     * via {@link #currentKey()} and {@link #currentValue()}.
     */
    public static final class Reader implements AutoCloseable {
        private final DataInputStream in;
        private final File file;
        byte[] currentKey;
        byte[] currentValue;

        private Reader(File file, int bufferBytes) throws IOException {
            this.file = file;
            this.in = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(file), bufferBytes));
        }

        /**
         * Reads the next entry. Returns {@code false} at end-of-file.
         * On success, the entry is available via {@link #currentKey()} and
         * {@link #currentValue()}.
         *
         * @throws IOException if the file appears corrupted (e.g. negative or
         *                     unreasonably large length fields)
         */
        public boolean advance() throws IOException {
            try {
                int keyLen = in.readInt();
                validateLength(keyLen, "key");
                currentKey = new byte[keyLen];
                in.readFully(currentKey);
                int valLen = in.readInt();
                validateLength(valLen, "value");
                currentValue = new byte[valLen];
                in.readFully(currentValue);
                return true;
            } catch (EOFException e) {
                return false;
            }
        }

        private static void validateLength(int length, String kind) throws IOException {
            if (length < 0 || length > MAX_ENTRY_LENGTH) {
                throw new IOException(
                        "Corrupted binary sorted file: " + kind + " length " + length
                                + " is outside valid range [0, " + MAX_ENTRY_LENGTH + "]");
            }
        }

        /**
         * The key of the current entry. Valid after a successful {@link #advance()}.
         */
        public byte[] currentKey() {
            return currentKey;
        }

        /**
         * The value of the current entry. Valid after a successful {@link #advance()}.
         */
        public byte[] currentValue() {
            return currentValue;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
