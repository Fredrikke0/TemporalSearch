package com.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.index.util.NashSerializationUtils;
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
    private static final String DELIMITER_REGEX = "\\0";
    private static final Logger logger = LoggerFactory.getLogger(RocksDBBrowser.class);
    private static SynonymManager globalSynonymManager;
    private static final List<String> ALL_INDEX_TYPES = Collections.unmodifiableList(Arrays.asList(
        "unigram", "bigram", "trigram", "dependency", "ner_date", "ner", "pos", "hypernym", "nash",
        "synonym_manager_db",
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
                .help("Type of index to browse (e.g., unigram, stitch, synonym_manager_db). Use 'all' to perform the operation on all known index types sequentially.");

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

            Path synonymManagerDbPath = Paths.get(basePath, "global_values_lookup.db");
            try {
                if (Files.exists(synonymManagerDbPath)) {
                    logger.info("Attempting to initialize SynonymManager from: {}", synonymManagerDbPath);
                    globalSynonymManager = new SynonymManager(synonymManagerDbPath);
                    logger.info("SynonymManager initialized successfully.");
                } else {
                    logger.warn("SynonymManager database not found at: {}. Synonym lookups will not be available.", synonymManagerDbPath);
                }
            } catch (RocksDBException e) {
                logger.error("Failed to initialize SynonymManager from {}: {}. Synonym lookups will not be available.", synonymManagerDbPath, e.getMessage());
                // globalSynonymManager will remain null
            }

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
        } finally {
            if (globalSynonymManager != null) {
                logger.info("Closing global SynonymManager.");
                globalSynonymManager.close();
            }
        }
    }

    private static void processSingleIndex(String indexType, String basePath, String key, String prefix, int limit, boolean showStats, ArgumentParser parser) throws IOException {
        Path dbPathActual;
        if ("synonym_manager_db".equalsIgnoreCase(indexType)) {
            dbPathActual = Paths.get(basePath, "global_values_lookup.db");
        } else {
            dbPathActual = Paths.get(basePath, indexType);
        }

        File dbFile = dbPathActual.toFile();

        if (!dbFile.exists() || !dbFile.isDirectory()) {
            System.err.printf("Database path for index '%s' not found or not a directory: %s%n", indexType, dbPathActual.toString());
            return;
        }
        System.out.printf("Accessing database at: %s%n", dbPathActual.toString());

        Options options = new Options();
        options.setCreateIfMissing(false);

        try (RocksDB db = RocksDB.openReadOnly(options, dbFile.getAbsolutePath())) {
            if (showStats) {
                displayStats(db, indexType);
                if (key == null && prefix == null && !"synonym_manager_db".equalsIgnoreCase(indexType)) {
                    options.close();
                    return;
                } else if (key == null && prefix == null && "synonym_manager_db".equalsIgnoreCase(indexType)) {
                    options.close();
                    return;
                }
            }
            if (key != null) {
                displayEntry(db, key, indexType);
            } else if (prefix != null) {
                listEntriesByPrefix(db, prefix, limit, indexType);
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

    private static void displayStats(RocksDB db, String indexType) throws IOException {
        long totalEntries = 0;
        long totalPositions = 0;
        long nashDateLookupCount = 0;
        boolean isNashIndex = "nash".equals(indexType);
        boolean isSynonymDb = "synonym_manager_db".equalsIgnoreCase(indexType);

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
                } else if (!isSynonymDb) {
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
        } else if (isNashIndex) {
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

    private static void displayEntry(RocksDB db, String key, String indexType) throws IOException {
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
        } else if ("synonym_manager_db".equalsIgnoreCase(indexType)) {
            displaySynonymDbEntry(bytes(key), data);
        } else {
            PositionListSoA positionsSoA = PositionListSoA.deserializeFromCompositeBlob(data);
            displayPositionsSoA(key, positionsSoA, indexType);
        }
    }

    private static void listEntriesByPrefix(RocksDB db, String prefix, int limit, String indexType) throws IOException {
        boolean isNash = indexType.equals("nash");
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

                if (!currentKey.startsWith(prefix)) break;

                if (isNash) {
                    displayNashEntry(keyBytes, valueBytes);
                } else if (isSynonymDb) {
                    displaySynonymDbEntry(keyBytes, valueBytes);
                } else {
                    PositionListSoA positionsSoA = PositionListSoA.deserializeFromCompositeBlob(valueBytes);
                    displayPositionsSoA(currentKey, positionsSoA, indexType);
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
        boolean isNash = indexType.equals("nash");
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
                System.out.printf("%nShowing first %d entries. Total entries might be higher. Use --limit 0 to see all (and get accurate total).%n", limit);
            } else if (totalSynonymDbEntries > 0) {
                System.out.printf("%nShowing %s%d entries.%n", (limit == 0 ? "all " : ""), totalSynonymDbEntries);
            } else {
                System.out.println("No entries found in Synonym Manager DB.");
            }
            return;
        }

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

    private static void displayPositionsSoA(String key, PositionListSoA positionsSoA, String indexType) {
        System.out.printf("%nKey: %s%n", formatKey(key, indexType));
        System.out.printf("Positions: %d%n", positionsSoA.getNumPositions());
        System.out.println("----------");

        int count = 0;
        int maxPositionsToDisplay = 100;

        for (int i = 0; i < positionsSoA.getNumPositions(); i++) {
            if (count >= maxPositionsToDisplay && maxPositionsToDisplay > 0) {
                System.out.printf("%nShowing first %d positions. Total positions: %d%n", maxPositionsToDisplay, positionsSoA.getNumPositions());
                break;
            }

            Position pos = positionsSoA.getPositionAt(i);
            int currentSynonymId = positionsSoA.getSynonymIdAt(i);
            String synonymOutput = "";

            if (currentSynonymId != -1) { // Only attempt lookup if synonymId is valid (not -1)
                if (globalSynonymManager != null) {
                    String lookedUpValue; // Declared here to be used in all branches
                    try {
                        // Attempt to get the term from SynonymManager first
                        termFromManagerLoop: do { // Label for breaking out of the loop after first match
                            lookedUpValue = globalSynonymManager.getTerm(currentSynonymId)
                                                                .orElse("id:" + currentSynonymId + "(not_found_in_SM)");

                            if (indexType.startsWith("stitch_")) {
                                // Use Pattern.quote for literal splitting on the delimiter character
                                String[] keyParts = key.split(Pattern.quote(String.valueOf(ACTUAL_DELIMITER_CHAR)));
                                if (keyParts.length > 1) {
                                    synonymOutput = String.format("[%s]", lookedUpValue);
                                } else {
                                    // Even if key parsing fails, show the looked up value if ID was valid
                                    synonymOutput = String.format("[syn_id:%d(%s)(key_parse_err)]", currentSynonymId, lookedUpValue);
                                }
                                break termFromManagerLoop;
                            } else if (indexType.equals("pos")) {
                                synonymOutput = String.format("[token_value:%s]", lookedUpValue);
                                break termFromManagerLoop;
                            } else if (indexType.equals("ner")) {
                                synonymOutput = String.format("[entity_value:%s]", lookedUpValue);
                                break termFromManagerLoop;
                            } else if (indexType.equals("ner_date")) {
                                // For ner_date, key is the date. If synonymId is present and not -1, it's unexpected.
                                synonymOutput = String.format("[unexpected_syn_id_for_ner_date:%d]", currentSynonymId);
                                break termFromManagerLoop;
                            } else { // Generic case for other index types
                                synonymOutput = String.format("[value_id:%d (%s)]", currentSynonymId, lookedUpValue);
                                break termFromManagerLoop;
                            }
                        } while(false); // Ensures the block runs once

                    } catch (RocksDBException e) {
                        synonymOutput = String.format("[id:%d(db_error_SM)]", currentSynonymId);
                        logger.warn("RocksDBException looking up synonym ID {} for key '{}' in index type '{}': {}",
                                    currentSynonymId, key, indexType, e.getMessage());
                    }
                } else { // globalSynonymManager is null (e.g., DB not found)
                    synonymOutput = String.format("[id:%d(SM_unavailable)]", currentSynonymId);
                }
            } // If currentSynonymId is -1, synonymOutput remains empty, which is correct.

            System.out.printf("  [doc:%d][sent:%d][chars:%d-%d]%s%n",
                pos.getDocumentId(),
                pos.getSentenceId(),
                pos.getBeginPosition(),
                pos.getEndPosition(),
                synonymOutput);
            count++;
        }
        if (maxPositionsToDisplay == 0 && positionsSoA.getNumPositions() > 0) {
             System.out.printf("%nShowing all %d positions.%n", positionsSoA.getNumPositions());
        }
    }

    private static String formatKey(String key, String indexType) {
        if (indexType.equals("dependency")) {
            String[] parts = key.split(DELIMITER_REGEX);
            return parts.length == 3 ? String.format("%s-%s->%s", parts[0], parts[1], parts[2]) : key;
        } else if (indexType.startsWith("stitch_") || indexType.equals("bigram") || indexType.equals("trigram")) {
            return key.replace(String.valueOf(ACTUAL_DELIMITER_CHAR), " <DELIM> ");
        }
        return key;
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
}
