package com.example;

import org.iq80.leveldb.*;
import static org.iq80.leveldb.impl.Iq80DBFactory.*;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.index.StitchPosition;
import com.example.index.NashDateEntryWithId;
import com.example.index.util.NashSerializationUtils;

public class LevelDBBrowser {
    private static final String DELIMITER = "\u0000";
    private static final Logger logger = LoggerFactory.getLogger(LevelDBBrowser.class);
    private static final String ANNOTATION_SYNONYMS_PREFIX = "%s_synonyms.ser";
    private static final String[] ANNOTATION_TYPES = {"date", "ner", "pos", "dependency"};
    private static final List<String> ALL_INDEX_TYPES = Collections.unmodifiableList(Arrays.asList(
        "unigram", "bigram", "trigram", "dependency", "ner_date", "pos", "hypernym", "stitch", "nash"
    ));

    public static void main(String[] args) throws IOException {
        logger.debug("Starting LevelDBBrowser...");
        ArgumentParser parser = ArgumentParsers.newFor("LevelDBBrowser").build()
                .defaultHelp(true)
                .description("Browse contents of LevelDB index databases. Supports listing entries, looking up specific keys/prefixes, and displaying statistics.");

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
            System.err.println("Error in LevelDBBrowser setup: " + e.getMessage());
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
        if (indexType.equals("stitch") && (key != null || prefix != null)) {
            // Pass the base path for indexes, not the specific stitch index path for synonyms
            annotationSynonyms = loadAnnotationSynonyms(basePath);
        }

        Options options = new Options();
        options.createIfMissing(false); // Do not create if missing, we are browsing

        try (DB db = factory.open(dbFile, options)) {
            if (showStats) {
                displayStats(db, indexType);
                // If only stats are shown for a single index, and "all" is not selected, main method's return handles exit.
                // If "all" is selected, we want to continue to the next index if only stats are shown.
                // The previous change (return in main if showStats) should be revisited if --stats for 'all' should only show stats and not list entries.
                // For now, if --stats is true, it will show stats and then, if key/prefix not null, also show entries.
                // The return in main: "if (showStats) { displayStats(db); return; }" is now inside processSingleIndex implicitly for the single index case.
                // Let's refine this: if showStats is true, we only do stats for this index.
                if (key == null && prefix == null) { // Only return if no specific key/prefix is given alongside --stats
                    return;
                }
            }
            if (key != null) {
                displayEntry(db, key, indexType, annotationSynonyms);
            } else if (prefix != null) {
                listEntriesByPrefix(db, prefix, limit, indexType, annotationSynonyms);
            } else { // If not showing only stats, and no key/prefix, list all
                listAllEntries(db, limit, indexType, annotationSynonyms);
            }
        }
        // Catch DB spezifc errors here to allow processing of other dbs in "all" mode.
        // General IOExceptions will be caught by the caller (main) if not in "all" mode, or handled per-index in "all" mode.
    }

    private static void displayStats(DB db, String indexType) throws IOException {
        long totalEntries = 0;
        long totalPositions = 0;
        long nashDateLookupCount = 0;
        boolean isNashIndex = "nash".equals(indexType);

        try (DBIterator iterator = db.iterator()) {
            for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
                totalEntries++;
                if (isNashIndex) {
                    // For Nash, we might want to count specific things if possible,
                    // e.g. count of dates in the lookup table if we find that key.
                    // For now, we just count entries.
                    // If we find the date lookup key, we can count dates.
                    if (Arrays.equals(iterator.peekNext().getKey(), NashSerializationUtils.DATE_LOOKUP_KEY)) {
                        try {
                            List<LocalDate> dateLookup = NashSerializationUtils.deserializeDateLookup(iterator.peekNext().getValue());
                            nashDateLookupCount = dateLookup.size();
                        } catch (IOException e) {
                            logger.warn("Could not deserialize Nash date lookup table during stats: {}", e.getMessage());
                        }
                    }
                } else {
                    try {
                        PositionList positions = PositionList.deserialize(iterator.peekNext().getValue());
                        totalPositions += positions.size();
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

    private static void displayEntry(DB db, String key, String indexType, Map<String, Map<Integer, String>> synonyms) throws IOException {
        byte[] data = db.get(bytes(key));
        if (data == null) {
            System.out.printf("Key not found: %s%n", key);
            return;
        }

        if (indexType.equals("nash")) {
            displayNashEntry(bytes(key), data);
        } else {
            PositionList positions = PositionList.deserialize(data);
            displayPositions(key, positions, indexType, synonyms);
        }
    }

    private static void listEntriesByPrefix(DB db, String prefix, int limit, String indexType, Map<String, Map<Integer, String>> synonyms) throws IOException {
        boolean isNash = indexType.equals("nash");
        System.out.printf("Entries with prefix '%s':%n", prefix);
        System.out.println("=".repeat(20 + prefix.length()));
        
        int count = 0;
        try (DBIterator iterator = db.iterator()) {
            iterator.seek(bytes(prefix));
            
            while (iterator.hasNext() && (limit == 0 || count < limit)) {
                Map.Entry<byte[], byte[]> entry = iterator.peekNext();
                String key = asString(entry.getKey());
                if (!key.startsWith(prefix)) break;
                
                if (isNash) {
                    displayNashEntry(entry.getKey(), entry.getValue());
                } else {
                    PositionList positions = PositionList.deserialize(entry.getValue());
                    displayPositions(key, positions, indexType, synonyms);
                }
                count++;
                iterator.next();
            }
        }
        
        if (limit > 0 && count == limit) {
            System.out.printf("%nShowing first %d entries. Use --limit to see more.%n", limit);
        }
    }

    private static void listAllEntries(DB db, int limit, String indexType, Map<String, Map<Integer, String>> synonyms) throws IOException {
        boolean isNash = indexType.equals("nash");
        System.out.println("All Entries (sorted by position count)");
        System.out.println("=====================================");

        List<Map.Entry<String, PositionList>> allEntriesList = new ArrayList<>();
        List<Map.Entry<byte[], byte[]>> nashEntriesList = new ArrayList<>();

        try (DBIterator iterator = db.iterator()) {
            iterator.seekToFirst();
            while (iterator.hasNext()) {
                Map.Entry<byte[], byte[]> entry = iterator.next(); // Use next() as we process it now
                if (isNash) {
                    nashEntriesList.add(new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
                } else {
                    String key = asString(entry.getKey());
                    PositionList positions = PositionList.deserialize(entry.getValue());
                    allEntriesList.add(new AbstractMap.SimpleEntry<>(key, positions));
                }
            }
        }

        if (!isNash) {
            allEntriesList.sort((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()));
        } // Nash entries are not sorted by position list size in this manner

        int count = 0;
        if (isNash) {
            for (Map.Entry<byte[], byte[]> entry : nashEntriesList) {
                if (limit > 0 && count >= limit) break;
                displayNashEntry(entry.getKey(), entry.getValue());
                count++;
            }
        } else {
            for (Map.Entry<String, PositionList> entry : allEntriesList) {
                if (limit > 0 && count >= limit) break;
                displayPositions(entry.getKey(), entry.getValue(), indexType, synonyms);
                count++;
            }
        }

        if (limit > 0 && count == limit) {
            long totalEntries = isNash ? nashEntriesList.size() : allEntriesList.size();
            System.out.printf("%nShowing first %d entries (of %d total). Use --limit 0 to see all.%n", limit, totalEntries);
        }
    }

    private static void displayPositions(String key, PositionList positions, String indexType, Map<String, Map<Integer, String>> synonyms) {
        System.out.printf("%nKey: %s%n", formatKey(key, indexType));
        System.out.printf("Positions: %d%n", positions.size());
        System.out.println("----------");

        int count = 0;
        int maxPositions = 100;  // Limit to 100 positions by default
        
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
                
                System.out.printf("  [doc:%d][sent:%d][chars:%d-%d][time:%s][%s:%s]%n",
                    pos.getDocumentId(),
                    pos.getSentenceId(),
                    pos.getBeginPosition(),
                    pos.getEndPosition(),
                    pos.getTimestamp(),
                    annotationType,
                    value);
                } else {
                System.out.printf("  [doc:%d][sent:%d][chars:%d-%d][time:%s]%n",
                    pos.getDocumentId(),
                    pos.getSentenceId(),
                    pos.getBeginPosition(),
                    pos.getEndPosition(),
                    pos.getTimestamp());
            }
            count++;
        }
    }

    private static String formatKey(String key, String indexType) {
        if (indexType.equals("dependency")) {
            String[] parts = key.split(DELIMITER);
            return parts.length == 3 ? String.format("%s-%s->%s", parts[0], parts[1], parts[2]) : key;
        }
        return key;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<Integer, String>> loadAnnotationSynonyms(String basePath) {
        Map<String, Map<Integer, String>> allSynonyms = new HashMap<>();
        
        for (String annotationType : ANNOTATION_TYPES) {
            String synonymsFileName = String.format(ANNOTATION_SYNONYMS_PREFIX, annotationType);
            Path synonymsPath = Paths.get(basePath, "stitch", synonymsFileName);
            File synonymsFile = synonymsPath.toFile();
            
            if (!synonymsFile.exists()) continue;
            
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(synonymsFile))) {
                Map<String, Integer> valueToId = (Map<String, Integer>) ois.readObject();
                Map<Integer, String> idToValue = new HashMap<>();
                valueToId.forEach((value, id) -> idToValue.put(id, value));
                allSynonyms.put(annotationType, idToValue);
            } catch (Exception e) {
                logger.error("Error loading {} synonyms: {}", annotationType, e.getMessage());
            }
        }
        
        return allSynonyms;
    }

    private static byte[] bytes(String str) {
        return str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void displayNashEntry(byte[] keyBytes, byte[] valueBytes) throws IOException {
        if (Arrays.equals(keyBytes, NashSerializationUtils.DATE_LOOKUP_KEY)) {
            System.out.printf("%nKey: %s (Date Lookup Table)%n", new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("----------");
            try {
                List<LocalDate> dateLookup = NashSerializationUtils.deserializeDateLookup(valueBytes);
                System.out.printf("Dates: %d%n", dateLookup.size());
                int displayCount = 0;
                for (LocalDate date : dateLookup) {
                    System.out.printf("  [%d]: %s%n", displayCount, date);
                    displayCount++;
                    if (displayCount >= 100) { // Limit displayed dates
                         System.out.println("  ... (showing first 100 dates)");
                         break;
                    }
                }
            } catch (IOException e) {
                System.out.println("  Error deserializing date lookup table: " + e.getMessage());
            }
        } else {
            String key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
            System.out.printf("%nKey: %s (Nash Prefix)%n", key);
             System.out.println("----------");
            List<NashDateEntryWithId> entries = NashSerializationUtils.deserializeNashEntries(valueBytes);
            System.out.printf("Entries: %d%n", entries.size());
            displayNashPositions(entries); // Use a helper for detailed display
        }
    }

    private static void displayNashPositions(List<NashDateEntryWithId> entries) {
         int count = 0;
         int maxPositions = 100; // Limit display
         for (NashDateEntryWithId entry : entries) {
             if (count >= maxPositions) {
                 System.out.printf("%nShowing first %d entries. Total entries: %d%n", maxPositions, entries.size());
                 break;
             }
             Position pos = entry.position();
             System.out.printf("  [doc:%d][sent:%d][chars:%d-%d][time:%s][dateId:%d]%n",
                     pos.getDocumentId(),
                     pos.getSentenceId(),
                     pos.getBeginPosition(),
                     pos.getEndPosition(),
                     pos.getTimestamp(),
                     entry.dateId());
             count++;
         }
    }
}
