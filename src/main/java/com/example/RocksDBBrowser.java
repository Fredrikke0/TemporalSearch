package com.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.regex.Pattern;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.PostingList;
import com.example.index.util.SynonymManager;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

/**
 * A simple RocksDB browser.
 * This class allows browsing contents of RocksDB index databases.
 */
public class RocksDBBrowser {
    private static final char ACTUAL_DELIMITER_CHAR = IndexAccessInterface.DELIMITER;
    private static final String DELIMITER_REGEX = Pattern.quote(String.valueOf(ACTUAL_DELIMITER_CHAR));
    private static final Logger logger = LoggerFactory.getLogger(RocksDBBrowser.class);
    private static SynonymManager globalSynonymManager;
    private static final List<String> ALL_INDEX_TYPES = Collections.unmodifiableList(Arrays.asList(
            "unigram", "bigram", "trigram", "dependency", "ner_date", "ner", "pos", "hypernym",
            "synonym_manager_db",
            // Stitch indexes
            "stitch_unigram_date", "stitch_unigram_ner",
            "stitch_bigram_date", "stitch_bigram_ner",
            "stitch_trigram_date", "stitch_trigram_ner"));

    private static final List<String> SUMMARY_INDEX_TYPES = Collections.unmodifiableList(Arrays.asList(
            "unigram", "bigram", "trigram", "dependency", "ner_date", "ner", "pos"));

    public static void main(String[] args) throws IOException {
        logger.debug("Starting RocksDBBrowser...");
        ArgumentParser parser = ArgumentParsers.newFor("RocksDBBrowser").build()
                .defaultHelp(true)
                .description(
                        "Browse RocksDB index databases. Supports: listing entries (optionally filtered by --match prefix), showing top-N terms by position count (--top, optionally filtered by --match), and displaying stats (--stats).");

        List<String> availableIndexChoices = new ArrayList<>(ALL_INDEX_TYPES);
        availableIndexChoices.add("all");
        availableIndexChoices.add("summary");

        parser.addArgument("-i", "--index-type")
                .choices(availableIndexChoices)
                .metavar("INDEX_TYPE")
                .required(true)
                .help("Type of index to browse (e.g., unigram, stitch, synonym_manager_db). Use 'all' to perform the operation on all known index types sequentially. Use 'summary' for a position count summary.");

        parser.addArgument("-d", "--db-path")
                .metavar("DB_PATH")
                .required(true)
                .help("Base path to the directory containing the various index subdirectories (e.g., projects/nyt/indexes/)");

        parser.addArgument("-m", "--match")
                .help("List entries where the key starts with the given string (prefix match). Also used to filter keys when computing --top.");

        parser.addArgument("-l", "--limit")
                .type(Integer.class)
                .setDefault(100)
                .help("Maximum number of entries or positions to display (default: 100). Use 0 for no limit. Applies to listing operations.");

        parser.addArgument("--top")
                .type(Integer.class)
                .help("Show top N terms by position count for the selected index. Use --match to restrict aggregation to keys starting with the given prefix. Not supported for 'synonym_manager_db'. Overrides regular listing when no --match is provided.");

        parser.addArgument("-s", "--stats")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Show basic statistics about the selected index (or all indexes if 'all' is chosen for index_type). If no key or prefix is specified, only stats are shown.");

        parser.addArgument("--analyze-syn")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Analyze synonymIds compression (RLE-runs vs VarInt). Uses top-N entries by positions (N from --limit, default 100). Honors --match prefix. Skips index types without synonymIds.");

        try {
            Namespace ns = parser.parseArgs(args);
            String indexType = ns.getString("index_type");
            String basePath = ns.getString("db_path");
            String match = ns.getString("match");
            int limit = ns.getInt("limit");
            Integer topN = (Integer) ns.get("top");
            boolean showStats = ns.getBoolean("stats");
            boolean analyzeSyn = ns.getBoolean("analyze_syn");

            Path synonymManagerDbPath = Paths.get(basePath, "global_values_lookup.db");
            try {
                if (Files.exists(synonymManagerDbPath)) {
                    globalSynonymManager = new SynonymManager(synonymManagerDbPath);
                    logger.debug("SynonymManager initialized successfully.");
                } else {
                    logger.warn("SynonymManager database not found at: {}. Synonym lookups will not be available.",
                            synonymManagerDbPath);
                }
            } catch (RocksDBException e) {
                logger.error("Failed to initialize SynonymManager from {}: {}. Synonym lookups will not be available.",
                        synonymManagerDbPath, e.getMessage());
                // globalSynonymManager will remain null
            }

            if ("summary".equalsIgnoreCase(indexType)) {
                displaySummaryStats(basePath);
            } else if ("all".equalsIgnoreCase(indexType)) {
                for (String singleIndexType : ALL_INDEX_TYPES) {
                    System.out.printf("\n--- Processing Index: %s ---\n", singleIndexType);
                    try {
                        processSingleIndex(singleIndexType, basePath, match, limit, topN, showStats, analyzeSyn,
                                parser);
                    } catch (Exception e) {
                        System.err.printf("Error processing index %s: %s%n", singleIndexType, e.getMessage());
                        // Optionally print stack trace for more detail: e.printStackTrace();
                    }
                }
            } else {
                processSingleIndex(indexType, basePath, match, limit, topN, showStats, analyzeSyn, parser);
            }

        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        } catch (Exception e) { // Catching other potential exceptions from initial setup
            System.err.println("Error in RocksDBBrowser setup: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (globalSynonymManager != null) {
                logger.info("Closing global SynonymManager.");
                globalSynonymManager.close();
            }
        }
    }

    private static void processSingleIndex(String indexType, String basePath, String match, int limit, Integer topN,
            boolean showStats, boolean analyzeSyn, ArgumentParser parser) throws IOException {
        Path dbPathActual;
        if ("synonym_manager_db".equalsIgnoreCase(indexType)) {
            dbPathActual = Paths.get(basePath, "global_values_lookup.db");
        } else {
            dbPathActual = Paths.get(basePath, indexType);
        }

        File dbFile = dbPathActual.toFile();

        if (!dbFile.exists() || !dbFile.isDirectory()) {
            System.err.printf("Database path for index '%s' not found or not a directory: %s%n", indexType,
                    dbPathActual.toString());
            return;
        }

        Options options = new Options();
        options.setCreateIfMissing(false);

        try (RocksDB db = RocksDB.openReadOnly(options, dbFile.getAbsolutePath())) {
            if (showStats) {
                displayStats(db, indexType);
                // If no further action requested (no match, no topN), return after stats
                if (match == null && topN == null && !analyzeSyn) {
                    options.close();
                    return;
                }
            }
            if (analyzeSyn) {
                System.out.println(
                        "--analyze-syn is no longer supported: synId is now encoded in the key (KeySchema format), not in the blob.");
                options.close();
                return;
            }
            if (match != null && topN == null) {
                listEntriesByPrefix(db, match, limit, indexType);
            } else if (topN != null) {
                listTopTermsByPositions(db, topN, indexType, match);
            } else {
                listAllEntries(db, limit, indexType);
            }
        } catch (RocksDBException e) {
            System.err.printf("Error opening RocksDB database at %s: %s%n", dbPathActual.toString(), e.getMessage());
            // e.printStackTrace(); // Uncomment for more detailed error
        } finally {
            if (options != null) {
                options.close();
            }
        }
    }

    private static void listTopTermsByPositions(RocksDB db, int topN, String indexType, String prefixFilter)
            throws IOException {
        if (topN <= 0) {
            System.out.println("--top requires a positive integer.");
            return;
        }

        boolean isSynonymDb = "synonym_manager_db".equalsIgnoreCase(indexType);
        if (isSynonymDb) {
            return; // Silently skip for synonym manager DB
        }

        // Optional key prefix filter for aggregation (e.g., NER type prefix)
        String effectivePrefix = (prefixFilter != null && !prefixFilter.isBlank()) ? prefixFilter : null;

        PriorityQueue<TopEntry> minHeap = new PriorityQueue<>(Comparator.comparingLong(TopEntry::sum));

        String currentBaseKey = null;
        long currentBaseSum = 0L;
        int currentBaseSegments = 0;

        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                byte[] keyBytes = iterator.key();
                String keyStr = asString(keyBytes);
                String baseKey = baseKeyWithoutSegmentSuffix(keyStr);

                // If a prefix filter is provided, ensure key starts with it
                if (effectivePrefix != null && !keyStr.startsWith(effectivePrefix)) {
                    continue;
                }

                long positionsCountForThisEntry;
                try {
                    PostingList pl = PostingList.deserialize(iterator.value(), PostingList.DeserializeMode.CELLS_ONLY);
                    positionsCountForThisEntry = pl.cells().getLongCardinality();
                } catch (Exception e) {
                    logger.warn(
                            "Could not deserialize entry value in index '{}' for key '{}' while computing top terms: {}. Skipping.",
                            indexType, keyStr, e.getMessage());
                    continue;
                }

                if (currentBaseKey == null) {
                    currentBaseKey = baseKey;
                    currentBaseSum = positionsCountForThisEntry;
                    currentBaseSegments = 1;
                } else if (baseKey.equals(currentBaseKey)) {
                    currentBaseSum += positionsCountForThisEntry;
                    currentBaseSegments++;
                } else {
                    // Finalize previous base key
                    offerIntoTopHeap(minHeap, currentBaseKey, currentBaseSum, currentBaseSegments, topN);
                    // Start new aggregation
                    currentBaseKey = baseKey;
                    currentBaseSum = positionsCountForThisEntry;
                    currentBaseSegments = 1;
                }
            }
        }

        // Finalize the last base key aggregation
        if (currentBaseKey != null) {
            offerIntoTopHeap(minHeap, currentBaseKey, currentBaseSum, currentBaseSegments, topN);
        }

        List<TopEntry> topList = new ArrayList<>(minHeap);
        topList.sort((a, b) -> Long.compare(b.sum(), a.sum()));

        int rank = 1;
        for (TopEntry entry : topList) {
            String displayKey = formatKey(entry.key(), indexType);
            String segmentsInfo = entry.segments() > 1 ? String.format(" (segments: %d)", entry.segments()) : "";
            System.out.printf("%2d. %s -> %,d positions%s%n", rank, displayKey, entry.sum(), segmentsInfo);
            rank++;
        }
        if (topList.isEmpty()) {
            System.out.println("No entries found.");
        }
    }

    private static void offerIntoTopHeap(PriorityQueue<TopEntry> minHeap, String key, long sum, int segments,
            int topN) {
        TopEntry newEntry = new TopEntry(key, sum, segments);
        if (minHeap.size() < topN) {
            minHeap.offer(newEntry);
        } else if (minHeap.peek() != null && minHeap.peek().sum() < sum) {
            minHeap.poll();
            minHeap.offer(newEntry);
        }
    }

    private static final class TopEntry {
        private final String key;
        private final long sum;
        private final int segments;

        TopEntry(String key, long sum, int segments) {
            this.key = key;
            this.sum = sum;
            this.segments = segments;
        }

        String key() {
            return key;
        }

        long sum() {
            return sum;
        }

        int segments() {
            return segments;
        }
    }

    private static String baseKeyWithoutSegmentSuffix(String key) {
        int hashPos = key.lastIndexOf('#');
        if (hashPos == -1)
            return key;
        if (hashPos == key.length() - 1)
            return key; // Trailing '#', unlikely, keep as-is
        // Check if the suffix after '#' is all digits
        for (int i = hashPos + 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                return key; // Not a numeric suffix, treat whole as key
            }
        }
        return key.substring(0, hashPos);
    }

    private static void displayStats(RocksDB db, String indexType) throws IOException {
        long totalEntries = 0;
        long totalPositions = 0;
        long totalKeyBytes = 0;
        boolean isSynonymDb = "synonym_manager_db".equalsIgnoreCase(indexType);

        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                totalEntries++;
                totalKeyBytes += iterator.key() != null ? iterator.key().length : 0;
                if (!isSynonymDb) {
                    try {
                        byte[] value = iterator.value();
                        PostingList pl = PostingList.deserialize(value, PostingList.DeserializeMode.CELLS_ONLY);
                        totalPositions += pl.cells().getLongCardinality();
                    } catch (Exception e) {
                        logger.warn(
                                "Could not deserialize entry value in index '{}' during stats calculation: {}. Skipping for position count.",
                                indexType, e.getMessage());
                    }
                }
            }
        }

        System.out.println("Index Statistics");
        System.out.println("================");
        System.out.printf("Total entries: %,d%n", totalEntries);
        System.out.printf("Total key bytes: %,d%n", totalKeyBytes);
        System.out.printf("Average key size (bytes): %.2f%n",
                totalEntries > 0 ? (double) totalKeyBytes / totalEntries : 0.0);

        if (isSynonymDb) {
            long termToIdCount = 0;
            long idToTermCount = 0;
            String nextIdVal = "N/A";
            try (RocksIterator statsIterator = db.newIterator()) {
                for (statsIterator.seekToFirst(); statsIterator.isValid(); statsIterator.next()) {
                    String currentKey = asString(statsIterator.key());
                    if (currentKey.startsWith("term:")) {
                        termToIdCount++;
                    } else if (currentKey.startsWith("id:")) {
                        idToTermCount++;
                    } else if (currentKey.equals("__NEXT_ID__")) {
                        nextIdVal = asString(statsIterator.value());
                    }
                }
            }
            System.out.printf("  Term-to-ID mappings ('term:'): %,d%n", termToIdCount);
            System.out.printf("  ID-to-Term mappings ('id:'): %,d%n", idToTermCount);
            System.out.printf("  Next ID value ('__NEXT_ID__'): %s%n", nextIdVal);
        } else {
            System.out.printf("Total positions: %,d%n", totalPositions);
            System.out.printf("Average positions per entry: %.2f%n",
                    totalEntries > 0 ? (double) totalPositions / totalEntries : 0);
        }
        System.out.println();
    }

    private static void listEntriesByPrefix(RocksDB db, String prefix, int limit, String indexType) throws IOException {
        boolean isSynonymDb = "synonym_manager_db".equalsIgnoreCase(indexType);

        System.out.printf("Entries with prefix '%s':%n", prefix);
        System.out.println("=".repeat(20 + prefix.length()));

        int count = 0;
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seek(bytes(prefix));

            while (iterator.isValid() && (limit == 0 || count < limit)) {
                byte[] keyBytes = iterator.key();
                byte[] valueBytes = iterator.value();
                String currentKey = asString(keyBytes);

                if (!currentKey.startsWith(prefix))
                    break;

                if (isSynonymDb) {
                    displaySynonymDbEntry(keyBytes, valueBytes);
                } else {
                    PostingList pl = PostingList.deserialize(valueBytes, PostingList.DeserializeMode.FULL);
                    displayPostingList(currentKey, pl, indexType);
                }
                count++;
                iterator.next();
            }
        }

        if (limit > 0 && count == limit) {
            System.out.printf("%nShowing first %d entries. Use --limit to see more.%n", limit);
        }
    }

    private static void listAllEntries(RocksDB db, int limit, String indexType) throws IOException {
        boolean isSynonymDb = "synonym_manager_db".equalsIgnoreCase(indexType);

        System.out.println("All Entries Summary");
        System.out.println("============================================");

        if (isSynonymDb) {
            System.out.println("(Synonym Manager DB Entries - Key: Value)");
            int displayCount = 0;
            long totalSynonymDbEntries = 0;
            try (RocksIterator iterator = db.newIterator()) {
                for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                    totalSynonymDbEntries++;
                    if (limit > 0 && displayCount >= limit) {
                        continue;
                    }
                    displaySynonymDbEntry(iterator.key(), iterator.value());
                    displayCount++;
                }
            }
            if (limit > 0 && displayCount == limit && totalSynonymDbEntries > limit) {
                System.out.printf(
                        "%nShowing first %d entries. Total entries might be higher. Use --limit 0 to see all (and get accurate total).%n",
                        limit);
            } else if (totalSynonymDbEntries > 0) {
                System.out.printf("%nShowing %s%d entries.%n", (limit == 0 ? "all " : ""), totalSynonymDbEntries);
            } else {
                System.out.println("No entries found in Synonym Manager DB.");
            }
            return;
        }

        List<Map.Entry<String, Integer>> keyAndCountsList = new ArrayList<>();

        try (RocksIterator iterator = db.newIterator()) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                String key = asString(iterator.key());
                PostingList pl = PostingList.deserialize(iterator.value(), PostingList.DeserializeMode.CELLS_ONLY);
                int cellCount = (int) pl.cells().getLongCardinality();
                keyAndCountsList.add(new AbstractMap.SimpleEntry<>(key, cellCount));
                iterator.next();
            }
        }
        keyAndCountsList.sort((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()));

        int count = 0;
        for (Map.Entry<String, Integer> entry : keyAndCountsList) {
            if (limit > 0 && count >= limit)
                break;
            String formattedKey = formatKey(entry.getKey(), indexType);
            System.out.printf("Key: %s, Position Count: %d%n", formattedKey, entry.getValue());
            count++;
        }

        if (limit > 0 && count == limit) {
            long totalEntriesInDb = keyAndCountsList.size();
            System.out.printf("%nShowing first %d entries (of %,d total). Use --limit 0 to see all.%n", limit,
                    totalEntriesInDb);
        }
    }

    private static void displayPostingList(String key, PostingList pl, String indexType) {
        // Debug: print raw hex of key
        byte[] rawKey = key.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        StringBuilder hex = new StringBuilder();
        for (byte b : rawKey) hex.append(String.format("%02x ", b & 0xFF));
        System.out.printf("[hex: %s]%n", hex.toString().trim());
        System.out.printf("%nKey: %s%n", formatKey(key, indexType));
        System.out.printf("Cells: %d%n", pl.cells().getLongCardinality());
        if (pl.occurrences() != null) {
            System.out.printf("Occurrences: %d cells with data%n", pl.occurrences().numCells());
        }
        // Print first few cell keys
        int count = 0;
        var iter = pl.cells().getLongIterator();
        while (iter.hasNext() && count < 5) {
            long cellKey = iter.next();
            System.out.printf("  Cell %d: docId=%d, sentId=%d%n",
                    count, PostingList.docIdFromCellKey(cellKey), PostingList.sentIdFromCellKey(cellKey));
            count++;
        }
        if (pl.cells().getLongCardinality() > 5) {
            System.out.printf("  ... and %d more cells%n", pl.cells().getLongCardinality() - 5);
        }
    }


    private static String formatKey(String key, String indexType) {
        return formatKey(key.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), indexType);
    }

    private static String formatKey(byte[] keyBytes, String indexType) {
        String key = asString(keyBytes);
        if (indexType.equals("dependency")) {
            String[] parts = key.split(DELIMITER_REGEX);
            return parts.length == 3 ? String.format("%s-%s->%s", parts[0], parts[1], parts[2]) : key;
        } else if (indexType.equals("ner") || indexType.equals("pos") || indexType.startsWith("stitch_")) {
            // Read synId directly from raw bytes: last 4 bytes before any segment suffix
            try {
                byte[] base = keyBytes;
                // Strip trailing #segment bytes
                int segmentIdx = -1;
                for (int i = base.length - 1; i >= 0; i--) {
                    if (base[i] == '#') {
                        boolean allDigits = true;
                        for (int j = i + 1; j < base.length; j++) {
                            if (base[j] < '0' || base[j] > '9') { allDigits = false; break; }
                        }
                        if (allDigits) { segmentIdx = i; break; }
                    }
                }
                int synIdEnd = segmentIdx >= 0 ? segmentIdx : base.length;
                if (synIdEnd < 4) { return key; }
                int synId = readIntBE(base, synIdEnd - 4);
                String prefix = new String(base, 0, synIdEnd - 4, java.nio.charset.StandardCharsets.UTF_8)
                        .replace(String.valueOf(ACTUAL_DELIMITER_CHAR), " <DELIM> ");
                String term = null;
                if (globalSynonymManager != null) {
                    try {
                        term = globalSynonymManager.getTerm(synId).orElse(null);
                    } catch (org.rocksdb.RocksDBException e) { }
                }
                String value = term != null ? term : ("synId=" + synId);
                return prefix.trim() + " <DELIM> " + value;
            } catch (Exception ignore) { }
            return key.replace(String.valueOf(ACTUAL_DELIMITER_CHAR), " <DELIM> ");
        } else if (indexType.equals("bigram") || indexType.equals("trigram")) {
            return key.replace(String.valueOf(ACTUAL_DELIMITER_CHAR), " <DELIM> ");
        }
        return key;
    }

    private static int readIntBE(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24)
                | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8)
                | (buf[offset + 3] & 0xFF);
    }

    private static byte[] bytes(String str) {
        return str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static String asString(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static void displaySynonymDbEntry(byte[] keyBytes, byte[] valueBytes) {
        String keyStr = asString(keyBytes);
        String valueStr = asString(valueBytes);
        String typeHint = "";

        if (keyStr.startsWith("term:")) {
            typeHint = " (Term-to-ID)";
        } else if (keyStr.startsWith("id:")) {
            typeHint = " (ID-to-Term)";
        } else if (keyStr.equals("__NEXT_ID__")) {
            typeHint = " (Next ID Counter)";
        }
        System.out.printf("Key: %s%s, Value: %s%n", keyStr, typeHint, valueStr);
    }

    private static void displaySummaryStats(String basePath) {
        System.out.println("Index Position Summary");
        System.out.println("=====================================");
        System.out.printf("%-20s | %s%n", "Index Name", "Total Positions");
        System.out.println("----------------------|-----------------");

        for (String indexType : SUMMARY_INDEX_TYPES) {
            Path dbPathActual = Paths.get(basePath, indexType);
            File dbFile = dbPathActual.toFile();

            if (!dbFile.exists() || !dbFile.isDirectory()) {
                System.out.printf("%-20s | %s%n", indexType, "Not found");
                continue;
            }

            Options options = new Options();
            options.setCreateIfMissing(false);
            long totalPositions = 0;
            try (RocksDB db = RocksDB.openReadOnly(options, dbFile.getAbsolutePath())) {
                try (RocksIterator iterator = db.newIterator()) {
                    for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                        try {
                            byte[] value = iterator.value();
                            PostingList pl = PostingList.deserialize(value, PostingList.DeserializeMode.CELLS_ONLY);
                            totalPositions += pl.cells().getLongCardinality();
                        } catch (Exception e) {
                            logger.warn(
                                    "Could not deserialize entry value in index '{}' during summary calculation: {}. Skipping position count.",
                                    indexType, e.getMessage());
                        }
                    }
                }
                System.out.printf("%-20s | %,d%n", indexType, totalPositions);
            } catch (RocksDBException e) {
                System.out.printf("%-20s | %s%n", indexType, "Error opening DB");
                logger.error("Error opening RocksDB database at {}: {}", dbPathActual.toString(), e.getMessage());
            } finally {
                if (options != null) {
                    options.close();
                }
            }
        }
    }

    // analyzeSynonymCompressionTop removed: synId is now encoded in the key
    // (KeySchema format), not in the blob

    // skipArray removed: only used by analyzeSynonymCompressionTop which has been
    // removed
}
