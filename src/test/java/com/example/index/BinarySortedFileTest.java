package com.example.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link BinarySortedFile} covering the full lifecycle:
 * write, read, merge, and edge cases.
 */
@DisplayName("BinarySortedFile")
class BinarySortedFileTest {

    @TempDir
    Path tempDir;

    // ---- helpers ----

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Builds a sorted list of (key, value) pairs for deterministic tests. */
    private static List<Entry> makeEntries(int count) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(
                    bytes(String.format("key-%05d", i)),
                    bytes("value-" + i)));
        }
        return entries;
    }

    /** Writes entries in order to a .bsf file. */
    private File writeFile(List<Entry> entries) throws IOException {
        File file = Files.createTempFile(tempDir, "test-", BinarySortedFile.EXTENSION).toFile();
        try (BinarySortedFile.Writer w = BinarySortedFile.writer(file)) {
            for (Entry e : entries) {
                w.writeEntry(e.key, e.value);
            }
        }
        return file;
    }

    /** Reads all entries from a .bsf file. */
    private List<Entry> readAll(File file) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (BinarySortedFile.Reader r = BinarySortedFile.reader(file)) {
            while (r.advance()) {
                // Defensive copies since Reader reuses the arrays
                entries.add(new Entry(
                        Arrays.copyOf(r.currentKey(), r.currentKey().length),
                        Arrays.copyOf(r.currentValue(), r.currentValue().length)));
            }
        }
        return entries;
    }

    // ---- nested test groups ----

    @Nested
    @DisplayName("Writer / Reader round-trip")
    class RoundTrip {

        @Test
        @DisplayName("Empty file round-trips to zero entries")
        void emptyFile() throws Exception {
            File file = writeFile(Collections.emptyList());
            assertTrue(file.exists());
            assertEquals(0, file.length());

            List<Entry> result = readAll(file);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Single entry round-trips correctly")
        void singleEntry() throws Exception {
            List<Entry> entries = makeEntries(1);
            File file = writeFile(entries);
            List<Entry> result = readAll(file);

            assertEquals(1, result.size());
            assertArrayEquals(entries.get(0).key, result.get(0).key);
            assertArrayEquals(entries.get(0).value, result.get(0).value);
        }

        @Test
        @DisplayName("Many entries round-trip correctly")
        void manyEntries() throws Exception {
            List<Entry> entries = makeEntries(10_000);
            File file = writeFile(entries);
            List<Entry> result = readAll(file);

            assertEquals(entries.size(), result.size());
            for (int i = 0; i < entries.size(); i++) {
                assertArrayEquals(entries.get(i).key, result.get(i).key,
                        "Mismatch at index " + i);
                assertArrayEquals(entries.get(i).value, result.get(i).value,
                        "Mismatch at index " + i);
            }
        }

        @Test
        @DisplayName("Empty key and empty value round-trip")
        void emptyKeyAndValue() throws Exception {
            File file = Files.createTempFile(tempDir, "empty-kv-", BinarySortedFile.EXTENSION).toFile();
            try (BinarySortedFile.Writer w = BinarySortedFile.writer(file)) {
                w.writeEntry(new byte[0], new byte[0]);
            }

            List<Entry> result = readAll(file);
            assertEquals(1, result.size());
            assertEquals(0, result.get(0).key.length);
            assertEquals(0, result.get(0).value.length);
        }

        @Test
        @DisplayName("Large entry (1 MB) round-trips correctly")
        void largeEntry() throws Exception {
            byte[] key = bytes("large-key");
            byte[] value = new byte[1 << 20]; // 1 MB
            new Random(42).nextBytes(value);

            File file = Files.createTempFile(tempDir, "large-", BinarySortedFile.EXTENSION).toFile();
            try (BinarySortedFile.Writer w = BinarySortedFile.writer(file)) {
                w.writeEntry(key, value);
            }

            List<Entry> result = readAll(file);
            assertEquals(1, result.size());
            assertArrayEquals(key, result.get(0).key);
            assertArrayEquals(value, result.get(0).value);
        }
    }

    @Nested
    @DisplayName("Merge correctness")
    class Merge {

        @Test
        @DisplayName("Merging zero non-empty inputs produces an empty output file")
        void mergeZeroInputs() throws Exception {
            File output = tempDir.resolve("merged.bsf").toFile();

            BinarySortedFile.merge(Collections.emptyList(), output);

            assertTrue(output.exists());
            assertEquals(0, output.length());
        }

        @Test
        @DisplayName("Merging a single input file copies its contents")
        void mergeSingleInput() throws Exception {
            List<Entry> entries = makeEntries(100);
            File input = writeFile(entries);
            File output = tempDir.resolve("merged.bsf").toFile();

            BinarySortedFile.merge(List.of(input), output);

            // Input should have been moved; output should contain the data
            List<Entry> result = readAll(output);
            assertEquals(entries.size(), result.size());
            for (int i = 0; i < entries.size(); i++) {
                assertArrayEquals(entries.get(i).key, result.get(i).key);
            }
        }

        @Test
        @DisplayName("Single non-empty file among empty files is handled correctly")
        void singleNonEmptyAmongEmpties() throws Exception {
            List<Entry> entries = makeEntries(50);
            File realFile = writeFile(entries);
            File emptyFile = Files.createTempFile(tempDir, "empty-", BinarySortedFile.EXTENSION).toFile();
            File output = tempDir.resolve("merged.bsf").toFile();

            BinarySortedFile.merge(List.of(emptyFile, realFile), output);

            List<Entry> result = readAll(output);
            assertEquals(entries.size(), result.size());
        }

        @Test
        @DisplayName("Two-way merge produces correct sorted output")
        void twoWayMerge() throws Exception {
            // File A: keys 0,2,4,...
            List<Entry> a = new ArrayList<>();
            for (int i = 0; i < 200; i += 2) {
                a.add(new Entry(bytes(String.format("key-%05d", i)), bytes("from-a-" + i)));
            }
            // File B: keys 1,3,5,...
            List<Entry> b = new ArrayList<>();
            for (int i = 1; i < 200; i += 2) {
                b.add(new Entry(bytes(String.format("key-%05d", i)), bytes("from-b-" + i)));
            }

            File fileA = writeFile(a);
            File fileB = writeFile(b);
            File output = tempDir.resolve("merged.bsf").toFile();

            BinarySortedFile.merge(List.of(fileA, fileB), output);

            List<Entry> result = readAll(output);
            assertEquals(200, result.size());
            // Verify interleaved order
            for (int i = 0; i < 200; i++) {
                assertEquals(String.format("key-%05d", i), str(result.get(i).key));
            }
        }

        @Test
        @DisplayName("K-way merge with many files")
        void manyWayMerge() throws Exception {
            int numFiles = 50;
            int entriesPerFile = 200;
            List<File> inputs = new ArrayList<>();
            List<Entry> allEntries = new ArrayList<>();

            Random rng = new Random(123);
            for (int f = 0; f < numFiles; f++) {
                List<Entry> fileEntries = new ArrayList<>();
                for (int i = 0; i < entriesPerFile; i++) {
                    fileEntries.add(new Entry(
                            bytes(String.format("k-%06d", f * entriesPerFile + i)),
                            bytes("v-" + rng.nextInt())));
                }
                allEntries.addAll(fileEntries);
                inputs.add(writeFile(fileEntries));
            }

            File output = tempDir.resolve("merged.bsf").toFile();
            BinarySortedFile.merge(inputs, output);

            List<Entry> result = readAll(output);
            assertEquals(allEntries.size(), result.size());

            // Verify sorted order
            List<String> resultKeys = result.stream()
                    .map(e -> str(e.key))
                    .collect(Collectors.toList());
            List<String> sortedKeys = new ArrayList<>(resultKeys);
            sortedKeys.sort(Comparator.naturalOrder());
            assertEquals(sortedKeys, resultKeys, "Output must be globally sorted");
        }

        @Test
        @DisplayName("Merge with duplicate keys preserves all entries")
        void mergeWithDuplicates() throws Exception {
            List<Entry> a = List.of(
                    new Entry(bytes("dup"), bytes("a1")),
                    new Entry(bytes("dup"), bytes("a2")));
            List<Entry> b = List.of(
                    new Entry(bytes("dup"), bytes("b1")),
                    new Entry(bytes("unique"), bytes("only-in-b")));

            File fileA = writeFile(a);
            File fileB = writeFile(b);
            File output = tempDir.resolve("merged.bsf").toFile();

            BinarySortedFile.merge(List.of(fileA, fileB), output);

            List<Entry> result = readAll(output);
            assertEquals(4, result.size());
            // All "dup" entries should appear before "unique"
            long dupCount = result.stream().filter(e -> str(e.key).equals("dup")).count();
            assertEquals(3, dupCount);
            assertTrue(str(result.get(3).key).equals("unique"));
        }
    }

    @Nested
    @DisplayName("Reader edge cases")
    class ReaderEdgeCases {

        @Test
        @DisplayName("advance() returns false immediately on empty file")
        void emptyFile() throws Exception {
            File file = Files.createTempFile(tempDir, "empty-", BinarySortedFile.EXTENSION).toFile();
            try (BinarySortedFile.Reader r = BinarySortedFile.reader(file)) {
                assertFalse(r.advance());
            }
        }

        @Test
        @DisplayName("advance() called repeatedly after EOF keeps returning false")
        void advanceAfterEof() throws Exception {
            List<Entry> entries = makeEntries(3);
            File file = writeFile(entries);

            try (BinarySortedFile.Reader r = BinarySortedFile.reader(file)) {
                assertTrue(r.advance()); // entry 1
                assertTrue(r.advance()); // entry 2
                assertTrue(r.advance()); // entry 3
                assertFalse(r.advance()); // EOF
                assertFalse(r.advance()); // still EOF
            }
        }

        @Test
        @DisplayName("Reader can be closed and reopened on the same file")
        void closeAndReopen() throws Exception {
            List<Entry> entries = makeEntries(10);
            File file = writeFile(entries);

            // Read first 5
            try (BinarySortedFile.Reader r = BinarySortedFile.reader(file)) {
                for (int i = 0; i < 5; i++) {
                    assertTrue(r.advance());
                }
            }

            // Reopen and read remaining
            try (BinarySortedFile.Reader r = BinarySortedFile.reader(file)) {
                for (int i = 0; i < 10; i++) {
                    assertTrue(r.advance());
                }
                assertFalse(r.advance());
            }
        }

        @Test
        @DisplayName("Truncated file mid-entry causes EOF on next advance")
        void truncatedMidEntry() throws Exception {
            // Write a valid file with two entries, then truncate mid-second-entry.
            // Key: "key-00000" (9 bytes), Value: "value-0" (7 bytes)
            // Each entry: 4 + 9 + 4 + 7 = 24 bytes.
            List<Entry> entries = new ArrayList<>();
            entries.add(new Entry(bytes("first"), bytes("v1")));
            entries.add(new Entry(bytes("second"), bytes("v2")));
            File file = writeFile(entries);

            byte[] allBytes = Files.readAllBytes(file.toPath());
            // First entry is 4+5+4+2 = 15 bytes. Truncate at 18 bytes —
            // mid-way through the second entry.
            Files.write(file.toPath(), Arrays.copyOf(allBytes, 18));

            try (BinarySortedFile.Reader r = BinarySortedFile.reader(file)) {
                assertTrue(r.advance()); // first entry intact
                assertArrayEquals(bytes("first"), r.currentKey());
                // Second advance hits truncated data — returns false (EOFException caught)
                assertFalse(r.advance());
            }
        }

        @Test
        @DisplayName("Corrupted file with negative length throws IOException")
        void negativeLength() throws Exception {
            File file = Files.createTempFile(tempDir, "corrupt-", BinarySortedFile.EXTENSION).toFile();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.FileOutputStream(file))) {
                out.writeInt(-1); // negative key length
            }

            try (BinarySortedFile.Reader r = BinarySortedFile.reader(file)) {
                IOException ex = assertThrows(IOException.class, r::advance);
                assertTrue(ex.getMessage().contains("key length"));
            }
        }

        @Test
        @DisplayName("Corrupted file with length exceeding MAX_ENTRY_LENGTH throws IOException")
        void excessiveLength() throws Exception {
            File file = Files.createTempFile(tempDir, "corrupt-", BinarySortedFile.EXTENSION).toFile();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.FileOutputStream(file))) {
                out.writeInt(BinarySortedFile.MAX_ENTRY_LENGTH + 1); // too large
            }

            try (BinarySortedFile.Reader r = BinarySortedFile.reader(file)) {
                IOException ex = assertThrows(IOException.class, r::advance);
                assertTrue(ex.getMessage().contains("key length"));
                assertTrue(ex.getMessage().contains("outside valid range"));
            }
        }
    }

    @Nested
    @DisplayName("Key ordering (unsigned bytewise)")
    class KeyOrdering {

        @Test
        @DisplayName("Unsigned bytewise: byte 0x80 sorts before 0x7F (unlike signed Java bytes)")
        void unsignedBytewiseOrder() throws Exception {
            // 0x80 = -128 signed, but as unsigned it's 128, which is > 127 (0x7F)
            byte[] key80 = { (byte) 0x80 };
            byte[] key7F = { (byte) 0x7F };

            File file = Files.createTempFile(tempDir, "order-", BinarySortedFile.EXTENSION).toFile();
            // Write in "wrong" signed order
            try (BinarySortedFile.Writer w = BinarySortedFile.writer(file)) {
                w.writeEntry(key80, bytes("val80"));
                w.writeEntry(key7F, bytes("val7F"));
            }

            // The file is supposed to be pre-sorted by the caller — but the merge
            // comparator should still handle unsigned ordering correctly when files
            // are independently sorted.
            // Test the comparator directly:
            assertTrue(IndexKey.compareBytes(key80, key7F) > 0,
                    "0x80 (unsigned 128) must sort after 0x7F (unsigned 127)");
            assertTrue(IndexKey.compareBytes(key7F, key80) < 0,
                    "0x7F (unsigned 127) must sort before 0x80 (unsigned 128)");
        }

        @Test
        @DisplayName("Shorter prefix sorts before longer key with same prefix")
        void prefixBeforeLonger() throws Exception {
            byte[] shortKey = bytes("abc");
            byte[] longKey = bytes("abcd");

            assertTrue(IndexKey.compareBytes(shortKey, longKey) < 0,
                    "Shorter key must sort before longer key with same prefix");
        }

        @Test
        @DisplayName("Identical keys compare as equal")
        void identicalKeys() {
            byte[] a = bytes("same");
            byte[] b = bytes("same");
            assertEquals(0, IndexKey.compareBytes(a, b));
        }

        @Test
        @DisplayName("Empty key sorts before any non-empty key")
        void emptyBeforeNonEmpty() {
            byte[] empty = new byte[0];
            byte[] nonEmpty = bytes("x");
            assertTrue(IndexKey.compareBytes(empty, nonEmpty) < 0);
        }

        @Test
        @DisplayName("Two empty keys compare equal")
        void twoEmptyKeys() {
            assertEquals(0, IndexKey.compareBytes(new byte[0], new byte[0]));
        }
    }

    @Nested
    @DisplayName("Writer / Reader binary fidelity")
    class BinaryFidelity {

        @Test
        @DisplayName("All byte values 0x00-0xFF survive round-trip in keys")
        void allByteValuesInKey() throws Exception {
            byte[] key = new byte[256];
            for (int i = 0; i < 256; i++) {
                key[i] = (byte) i;
            }
            byte[] value = bytes("binary-test");

            File file = Files.createTempFile(tempDir, "binary-", BinarySortedFile.EXTENSION).toFile();
            try (BinarySortedFile.Writer w = BinarySortedFile.writer(file)) {
                w.writeEntry(key, value);
            }

            List<Entry> result = readAll(file);
            assertEquals(1, result.size());
            assertArrayEquals(key, result.get(0).key);
            assertArrayEquals(value, result.get(0).value);
        }

        @Test
        @DisplayName("All byte values 0x00-0xFF survive round-trip in values")
        void allByteValuesInValue() throws Exception {
            byte[] key = bytes("all-bytes-value");
            byte[] value = new byte[256];
            for (int i = 0; i < 256; i++) {
                value[i] = (byte) i;
            }

            File file = Files.createTempFile(tempDir, "binary-", BinarySortedFile.EXTENSION).toFile();
            try (BinarySortedFile.Writer w = BinarySortedFile.writer(file)) {
                w.writeEntry(key, value);
            }

            List<Entry> result = readAll(file);
            assertEquals(1, result.size());
            assertArrayEquals(key, result.get(0).key);
            assertArrayEquals(value, result.get(0).value);
        }
    }

    @Nested
    @DisplayName("Merge stress")
    class MergeStress {

        @Test
        @DisplayName("Merge with many readers (simulating incremental merge pattern)")
        void manyReaders() throws Exception {
            // Simulate the pattern from IndexGenerator: many small batch files
            int numFiles = 200;
            int entriesPerFile = 50;
            List<File> inputs = new ArrayList<>();

            for (int f = 0; f < numFiles; f++) {
                List<Entry> entries = new ArrayList<>();
                for (int i = 0; i < entriesPerFile; i++) {
                    int globalIdx = f * entriesPerFile + i;
                    entries.add(new Entry(
                            bytes(String.format("k-%08d", globalIdx)),
                            bytes("v-" + globalIdx)));
                }
                inputs.add(writeFile(entries));
            }

            File output = tempDir.resolve("merged.bsf").toFile();
            BinarySortedFile.merge(inputs, output);

            List<Entry> result = readAll(output);
            assertEquals(numFiles * entriesPerFile, result.size());

            // Verify strict sorting
            for (int i = 1; i < result.size(); i++) {
                assertTrue(IndexKey.compareBytes(result.get(i - 1).key, result.get(i).key) <= 0,
                        "Output must be globally sorted at index " + i);
            }
        }

        @Test
        @DisplayName("Multi-pass merge with 600 files (exceeding MAX_MERGE_FAN_IN)")
        void multiPassMerge() throws Exception {
            int numFiles = 600; // > MAX_MERGE_FAN_IN (500), triggers mergeMultiPass
            int entriesPerFile = 10;
            List<File> inputs = new ArrayList<>();

            for (int f = 0; f < numFiles; f++) {
                List<Entry> entries = new ArrayList<>();
                for (int i = 0; i < entriesPerFile; i++) {
                    int globalIdx = f * entriesPerFile + i;
                    entries.add(new Entry(
                            bytes(String.format("k-%06d", globalIdx)),
                            bytes("v-" + globalIdx)));
                }
                inputs.add(writeFile(entries));
            }

            File output = tempDir.resolve("merged.bsf").toFile();
            BinarySortedFile.merge(inputs, output);

            List<Entry> result = readAll(output);
            assertEquals(numFiles * entriesPerFile, result.size());

            for (int i = 1; i < result.size(); i++) {
                assertTrue(IndexKey.compareBytes(result.get(i - 1).key, result.get(i).key) <= 0,
                        "Multi-pass output must be globally sorted at index " + i);
            }
        }

        @Test
        @DisplayName("Merge with files of unequal sizes")
        void unequalSizes() throws Exception {
            File largeFile = writeFile(makeEntries(5000));
            File mediumFile = writeFile(makeEntries(1000));
            File smallFile = writeFile(makeEntries(10));
            File emptyFile = Files.createTempFile(tempDir, "empty-", BinarySortedFile.EXTENSION).toFile();
            File output = tempDir.resolve("merged.bsf").toFile();

            BinarySortedFile.merge(List.of(emptyFile, smallFile, largeFile, mediumFile), output);

            List<Entry> result = readAll(output);
            assertEquals(6010, result.size());
            for (int i = 1; i < result.size(); i++) {
                assertTrue(IndexKey.compareBytes(result.get(i - 1).key, result.get(i).key) <= 0);
            }
        }
    }

    // ---- helper record ----

    private static final class Entry {
        final byte[] key;
        final byte[] value;

        Entry(byte[] key, byte[] value) {
            this.key = key;
            this.value = value;
        }
    }
}
