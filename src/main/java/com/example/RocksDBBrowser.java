package com.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.Position;
import com.example.core.PositionList;
import com.example.core.PositionListSoA;
import com.example.index.StitchPosition;
import com.example.index.util.NashSerializationUtils;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

/**
 * A simple RocksDB browser.
 * This class allows browsing contents of RocksDB index databases.
 */
public class RocksDBBrowser {
    private static final String DELIMITER = "\\0";
    private static final Logger logger = LoggerFactory.getLogger(RocksDBBrowser.class);
    private static final String ANNOTATION_SYNONYMS_PREFIX = "%s_synonyms.ser";
    private static final String[] ANNOTATION_TYPES = {"date", "ner", "pos", "dependency"};
    private static final List<String> ALL_INDEX_TYPES = Collections.unmodifiableList(Arrays.asList(
        "unigram", "bigram", "trigram", "dependency", "ner_date", "ner", "pos", "hypernym", "nash",
        // Stitch indexes
        "stitch_unigram_date", "stitch_unigram_ner", "stitch_unigram_pos", "stitch_unigram_dependency",
        "stitch_bigram_date", "stitch_bigram_ner", "stitch_bigram_pos", "stitch_bigram_dependency",
        "stitch_trigram_date", "stitch_trigram_ner", "stitch_trigram_pos", "stitch_trigram_dependency"
    ));

    public static void main(String[] args) throws IOException {
        logger.debug("Starting RocksDBBrowser...");
        ArgumentParser parser = ArgumentParsers.newFor("RocksDBBrowser").build()
                .defaultHelp(true)
                .description("Browse contents of RocksDB index databases. Supports listing entries, looking up specific keys/prefixes, and displaying statistics.");

        List<String> availableIndexChoices = new ArrayList<>(ALL_INDEX_TYPES);
        availableIndexChoices.add("all");

        parser.addArgument("-i", "--index-type")
                .choices(availableIndexChoices)
                .metavar("INDEX_TYPE")
                .required(true)
                .help("Type of index to browse (e.g., unigram, stitch). Use 'all' to perform the operation on all known index types sequentially.");

        parser.addArgument("-d", "--db-path")
                .metavar("DB_PATH")
                .required(true)
                .help("Base path to the directory containing the various index subdirectories (e.g., projects/nyt/indexes/)");

        parser.addArgument("-k", "--key")
                .help("Look up a specific key within the selected index. The exact format of the key depends on the index type.");

        parser.addArgument("-p", "--prefix")
                .help("List entries where the key starts with the given prefix. Useful for exploring keys in a hierarchical structure.");

        parser.addArgument("-l", "--limit")
                .type(Integer.class)
                .setDefault(100)
                .help("Maximum number of entries or positions to display (default: 100). Use 0 for no limit. Applies to listing operations.");

        parser.addArgument("-s", "--stats")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Show basic statistics about the selected index (or all indexes if 'all' is chosen for index_type). If no key or prefix is specified, only stats are shown.");

        try {
            Namespace ns = parser.parseArgs(args);
            String indexType = ns.getString("index_type");
            String basePath = ns.getString("db_path");
            String key = ns.getString("key");
            String prefix = ns.getString("prefix");
            int limit = ns.getInt("limit");
            boolean showStats = ns.getBoolean("stats");

            if ("all".equalsIgnoreCase(indexType)) {
                for (String singleIndexType : ALL_INDEX_TYPES) {
                    System.out.printf("\n--- Processing Index: %s ---\n", singleIndexType);
                    try {
                        processSingleIndex(singleIndexType, basePath, key, prefix, limit, showStats, parser);
                    } catch (Exception e) {
                        System.err.printf("Error processing index %s: %s%n", singleIndexType, e.getMessage());
                        // Optionally print stack trace for more detail: e.printStackTrace();
                    }
                }
            } else {
                processSingleIndex(indexType, basePath, key, prefix, limit, showStats, parser);
            }

        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        } catch (Exception e) { // Catching other potential exceptions from initial setup
            System.err.println("Error in RocksDBBrowser setup: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void processSingleIndex(String indexType, String basePath, String key, String prefix, int limit, boolean showStats, ArgumentParser parser) throws IOException {
        String dbPath = basePath + "/" + indexType;
        File dbFile = new File(dbPath);

        if (!dbFile.exists() || !dbFile.isDirectory()) {
            System.err.printf("Database path for index '%s' not found or not a directory: %s%n", indexType, dbPath);
            return;
        }
        System.out.printf("Accessing database at: %s%n", dbPath);

        Map<String, Map<Integer, String>> annotationSynonyms = new HashMap<>();
        if (indexType.startsWith("stitch_") && (key != null || prefix != null)) {
            // Pass the base path for indexes, not the specific stitch index path for synonyms
            annotationSynonyms = loadAnnotationSynonyms(basePath);
        }

        Options options = new Options();
        options.setCreateIfMissing(false);

        try (RocksDB db = RocksDB.openReadOnly(options, dbFile.getAbsolutePath())) {
            if (showStats) {
                displayStats(db, indexType);
                if (key == null && prefix == null) {
                    options.close();
                    return;
                }
            }
            if (key != null) {
                displayEntry(db, key, indexType, annotationSynonyms);
            } else if (prefix != null) {
                listEntriesByPrefix(db, prefix, limit, indexType, annotationSynonyms);
            } else {
                listAllEntries(db, limit, indexType, annotationSynonyms);
            }
        } catch (RocksDBException e) {
            System.err.printf("Error opening RocksDB database at %s: %s%n", dbPath, e.getMessage());
            // e.printStackTrace(); // Uncomment for more detailed error
        } finally {
            if (options != null) {
                options.close();
            }
        }
    }

    private static void displayStats(RocksDB db, String indexType) throws IOException {
        long totalEntries = 0;
        long totalPositions = 0;
        long nashDateLookupCount = 0;
        boolean isNashIndex = "nash".equals(indexType);

        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                totalEntries++;
                if (isNashIndex) {
                    if (Arrays.equals(iterator.key(), NashSerializationUtils.DATE_LOOKUP_KEY)) {
                        try {
                            List<LocalDate> dateLookup = NashSerializationUtils.deserializeDateLookup(iterator.value());
                            nashDateLookupCount = dateLookup.size();
                        } catch (IOException e) {
                            logger.warn("Could not deserialize Nash date lookup table during stats: {}", e.getMessage());
                        }
                    }
                } else {
                    try {
                        byte[] value = iterator.value();
                        totalPositions += PositionListSoA.getNumPositionsFromBlob(value);
                    } catch (Exception e) {
                        logger.warn("Could not deserialize entry value in index '{}' during stats calculation: {}. Skipping for position count.", indexType, e.getMessage());
                    }
                }
            }
        }

        System.out.println("Index Statistics");
        System.out.println("================");
        System.out.printf("Total entries: %,d%n", totalEntries);
        if (isNashIndex) {
            if (nashDateLookupCount > 0) {
                System.out.printf("Total dates in lookup table: %,d%n", nashDateLookupCount);
            }
            System.out.println("(Detailed position counts are not applicable for Nash index in this view)");
        } else {
            System.out.printf("Total positions: %,d%n", totalPositions);
            System.out.printf("Average positions per entry: %.2f%n", totalEntries > 0 ? (double) totalPositions / totalEntries : 0);
        }
        System.out.println();
    }

    private static void displayEntry(RocksDB db, String key, String indexType, Map<String, Map<Integer, String>> synonyms) throws IOException {
        byte[] data = null;
        try {
            data = db.get(bytes(key));
        } catch (RocksDBException e) {
            System.err.printf("Error getting key '%s' from RocksDB: %s%n", key, e.getMessage());
            return;
        }

        if (data == null) {
            System.out.printf("Key not found: %s%n", key);
            return;
        }

        if (indexType.equals("nash")) {
            displayNashEntry(bytes(key), data);
        } else {
            PositionListSoA positionsSoA = PositionListSoA.deserializeFromCompositeBlob(data);
            displayPositionsSoA(key, positionsSoA, indexType, synonyms);
        }
    }

    private static void listEntriesByPrefix(RocksDB db, String prefix, int limit, String indexType, Map<String, Map<Integer, String>> synonyms) throws IOException {
        boolean isNash = indexType.equals("nash");
        System.out.printf("Entries with prefix '%s':%n", prefix);
        System.out.println("=".repeat(20 + prefix.length()));

        int count = 0;
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seek(bytes(prefix));

            while (iterator.isValid() && (limit == 0 || count < limit)) {
                byte[] keyBytes = iterator.key();
                byte[] valueBytes = iterator.value();
                String currentKey = asString(keyBytes);

                if (!currentKey.startsWith(prefix)) break;

                if (isNash) {
                    displayNashEntry(keyBytes, valueBytes);
                } else {
                    PositionListSoA positionsSoA = PositionListSoA.deserializeFromCompositeBlob(valueBytes);
                    displayPositionsSoA(currentKey, positionsSoA, indexType, synonyms);
                }
                count++;
                iterator.next();
            }
        }

        if (limit > 0 && count == limit) {
            System.out.printf("%nShowing first %d entries. Use --limit to see more.%n", limit);
        }
    }

    private static void listAllEntries(RocksDB db, int limit, String indexType, Map<String, Map<Integer, String>> synonyms) throws IOException {
        boolean isNash = indexType.equals("nash");
        System.out.println("All Entries Summary (Key and Position Count)");
        System.out.println("============================================");

        List<Map.Entry<String, Integer>> keyAndCountsList = new ArrayList<>();
        List<Map.Entry<byte[], byte[]>> nashEntriesList = new ArrayList<>();

        if (isNash) {
            try (RocksIterator iterator = db.newIterator()) {
                iterator.seekToFirst();
                while (iterator.isValid()) {
                    nashEntriesList.add(new AbstractMap.SimpleEntry<>(iterator.key(), iterator.value()));
                    iterator.next();
                }
            }
        } else {
            try (RocksIterator iterator = db.newIterator()) {
                iterator.seekToFirst();
                while (iterator.isValid()) {
                    String key = asString(iterator.key());
                    int positionCount = PositionListSoA.getNumPositionsFromBlob(iterator.value());
                    keyAndCountsList.add(new AbstractMap.SimpleEntry<>(key, positionCount));
                    iterator.next();
                }
            }
            keyAndCountsList.sort((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()));
        }

        int count = 0;
        if (isNash) {
            for (Map.Entry<byte[], byte[]> entry : nashEntriesList) {
                if (limit > 0 && count >= limit) break;
                byte[] keyBytes = entry.getKey();
                byte[] valueBytes = entry.getValue();
                String keyStr = asString(keyBytes);

                if (Arrays.equals(keyBytes, NashSerializationUtils.DATE_LOOKUP_KEY)) {
                    List<LocalDate> dateLookup = NashSerializationUtils.deserializeDateLookup(valueBytes);
                    System.out.printf("Key: %s (Date Lookup Table), Dates: %d%n", keyStr, dateLookup.size());
                } else {
                    try {
                        PositionListSoA positionsSoA = PositionListSoA.deserializeFromCompositeBlob(valueBytes);
                        System.out.printf("Key: %s (Nash Prefix), Position Entries: %d%n", keyStr, positionsSoA.getNumPositions());
                    } catch (IOException e) {
                        System.out.printf("Key: %s (Nash Prefix), Error deserializing: %s%n", keyStr, e.getMessage());
                    }
                }
                count++;
            }
        } else {
            for (Map.Entry<String, Integer> entry : keyAndCountsList) {
                if (limit > 0 && count >= limit) break;
                String formattedKey = formatKey(entry.getKey(), indexType);
                System.out.printf("Key: %s, Position Count: %d%n", formattedKey, entry.getValue());
                count++;
            }
        }

        if (limit > 0 && count == limit) {
            long totalEntriesInDb = isNash ? nashEntriesList.size() : keyAndCountsList.size();
            System.out.printf("%nShowing first %d entries (of %,d total). Use --limit 0 to see all.%n", limit, totalEntriesInDb);
        }
    }

    private static void displayPositions(String key, PositionList positions, String indexType, Map<String, Map<Integer, String>> synonyms) {
        System.out.printf("%nKey: %s%n", formatKey(key, indexType));
        System.out.printf("Positions: %d%n", positions.size());
        System.out.println("----------");

        int count = 0;
        int maxPositions = 100;

        for (Position pos : positions.getPositions()) {
            if (count >= maxPositions) {
                System.out.printf("%nShowing first %d positions. Total positions: %d%n", maxPositions, positions.size());
                break;
            }

            if (pos instanceof StitchPosition stitchPos) {
                String annotationType = stitchPos.getType().toString().toLowerCase();
                int synonymId = stitchPos.getSynonymId();
                String value = synonyms
                    .getOrDefault(annotationType, Map.of())
                    .getOrDefault(synonymId, "unknown");

                System.out.printf("  [doc:%d][sent:%d][chars:%d-%d][%s:%s]%n",
                    pos.getDocumentId(),
                    pos.getSentenceId(),
                    pos.getBeginPosition(),
                    pos.getEndPosition(),
                    annotationType,
                    value);
                } else {
                System.out.printf("  [doc:%d][sent:%d][chars:%d-%d]%n",
                    pos.getDocumentId(),
                    pos.getSentenceId(),
                    pos.getBeginPosition(),
                    pos.getEndPosition());
            }
            count++;
        }
    }

    private static String formatKey(String key, String indexType) {
        if (indexType.equals("dependency")) {
            String[] parts = key.split(DELIMITER);
            return parts.length == 3 ? String.format("%s-%s->%s", parts[0], parts[1], parts[2]) : key;
        } else if (indexType.startsWith("stitch_") || indexType.equals("bigram") || indexType.equals("trigram")) {
            return key.replace(DELIMITER, " <STITCH> ");
        }
        return key;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<Integer, String>> loadAnnotationSynonyms(String basePath) {
        Map<String, Map<Integer, String>> allSynonyms = new HashMap<>();

        for (String annotationType : ANNOTATION_TYPES) {
            String synonymsFileName = String.format(ANNOTATION_SYNONYMS_PREFIX, annotationType);
            Path synonymsPath = Paths.get(basePath, "stitch-" + annotationType, synonymsFileName);
            File synonymsFile = synonymsPath.toFile();

            if (!synonymsFile.exists()) {
                logger.warn("Synonym file not found for type '{}' at path: {}", annotationType, synonymsPath);
                continue;
            }

            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(synonymsFile))) {
                Map<String, Integer> valueToId = (Map<String, Integer>) ois.readObject();
                Map<Integer, String> idToValue = new HashMap<>();
                valueToId.forEach((value, id) -> idToValue.put(id, value));
                allSynonyms.put(annotationType, idToValue);
                logger.info("Successfully loaded {} synonyms for type '{}' from {}", idToValue.size(), annotationType, synonymsPath);
            } catch (Exception e) {
                logger.error("Error loading {} synonyms from {}: {}", annotationType, synonymsPath, e.getMessage());
            }
        }

        return allSynonyms;
    }

    private static byte[] bytes(String str) {
        return str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String asString(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void displayNashEntry(byte[] keyBytes, byte[] valueBytes) throws IOException {
        if (Arrays.equals(keyBytes, NashSerializationUtils.DATE_LOOKUP_KEY)) {
            System.out.printf("%nKey: %s (Date Lookup Table)%n", asString(keyBytes));
            System.out.println("----------");
            try {
                List<LocalDate> dateLookup = NashSerializationUtils.deserializeDateLookup(valueBytes);
                System.out.printf("Dates: %d%n", dateLookup.size());
                int displayCount = 0;
                for (LocalDate date : dateLookup) {
                    System.out.printf("  [%d]: %s%n", displayCount, date);
                    displayCount++;
                    if (displayCount >= 100) {
                         System.out.println("  ... (showing first 100 dates)");
                         break;
                    }
                }
            } catch (IOException e) {
                System.out.println("  Error deserializing date lookup table: " + e.getMessage());
            }
        } else {
            String key = asString(keyBytes);
            System.out.printf("%nKey: %s (Nash Prefix)%n", key);
            System.out.println("----------");
            try {
                PositionListSoA positionsSoA = PositionListSoA.deserializeFromCompositeBlob(valueBytes);
                System.out.printf("Entries: %d%n", positionsSoA.getNumPositions());
                for (int i = 0; i < positionsSoA.getNumPositions(); i++) {
                    if (i >= 100) { // Limit display
                        System.out.println("  ... (showing first 100 entries)");
                        break;
                    }
                    Position pos = positionsSoA.getPositionAt(i);
                    int dateId = positionsSoA.getSynonymIdAt(i); // dateId is in synonymId
                    System.out.printf("  Entry %d: DocID=%d, SentID=%d, Begin=%d, End=%d, DateID=%d%n",
                                      i, pos.getDocumentId(), pos.getSentenceId(), pos.getBeginPosition(), pos.getEndPosition(), dateId);
                }
            } catch (IOException e) {
                System.out.println("  Error deserializing Nash prefix data (PositionListSoA): " + e.getMessage());
            }
        }
    }

    private static void displayPositionsSoA(String key, PositionListSoA positionsSoA, String indexType, Map<String, Map<Integer, String>> synonyms) {
        System.out.printf("%nKey: %s%n", formatKey(key, indexType));
        System.out.printf("Positions: %d%n", positionsSoA.getNumPositions());
        System.out.println("----------");

        int count = 0;
        int maxPositionsToDisplay = 100; // Renamed for clarity

        for (int i = 0; i < positionsSoA.getNumPositions(); i++) {
            if (count >= maxPositionsToDisplay && maxPositionsToDisplay > 0) {
                System.out.printf("%nShowing first %d positions. Total positions: %d%n", maxPositionsToDisplay, positionsSoA.getNumPositions());
                break;
            }

            Position pos = positionsSoA.getPositionAt(i);
            int currentSynonymId = positionsSoA.getSynonymIdAt(i);
            String synonymOutput = "";

            if (indexType.startsWith("stitch_")) {
                String[] keyParts = key.split(Pattern.quote(DELIMITER));
                if (keyParts.length > 1) {
                    String annotationTypeFromKey = keyParts[keyParts.length - 1]; // Last part is the type
                    String lookedUpValue = synonyms
                        .getOrDefault(annotationTypeFromKey.toLowerCase(), Collections.emptyMap())
                        .getOrDefault(currentSynonymId, "id:" + currentSynonymId);
                    synonymOutput = String.format("[%s:%s]", annotationTypeFromKey, lookedUpValue);
                } else {
                    synonymOutput = String.format("[syn_id:%d]", currentSynonymId); // Fallback if key parsing fails
                }
            } else if (indexType.equals("pos")) {
                // For POS index, the key is the POS tag. SynonymId is the token ID.
                String tokenValue = synonyms
                    .getOrDefault("pos", Collections.emptyMap())
                    .getOrDefault(currentSynonymId, "id:" + currentSynonymId);
                synonymOutput = String.format("[token:%s]", tokenValue);
            } else if (currentSynonymId != -1) {
                // For other generic indexes, if synonymId is present and not -1
                synonymOutput = String.format("[syn_id:%d]", currentSynonymId);
            }

            System.out.printf("  [doc:%d][sent:%d][chars:%d-%d]%s%n",
                pos.getDocumentId(),
                pos.getSentenceId(),
                pos.getBeginPosition(),
                pos.getEndPosition(),
                synonymOutput); // Append synonym output string

            count++;
        }
        if (maxPositionsToDisplay == 0 && positionsSoA.getNumPositions() > 0) { // if limit was 0, indicate all shown
             System.out.printf("%nShowing all %d positions.%n", positionsSoA.getNumPositions());
        }
    }
}
