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

    public static void main(String[] args) throws IOException {
        logger.debug("Starting LevelDBBrowser...");
        ArgumentParser parser = ArgumentParsers.newFor("LevelDBBrowser").build()
                .defaultHelp(true)
                .description("Browse contents of LevelDB index databases");

        parser.addArgument("index_type")
                .choices("unigram", "bigram", "trigram", "dependency", "ner_date", "pos", "hypernym", "stitch", "nash")
                .help("Type of index to browse");

        parser.addArgument("db_path")
                .help("Base path to index directory");

        parser.addArgument("-k", "--key")
                .help("Look up a specific key");

        parser.addArgument("-p", "--prefix")
                .help("List entries with this key prefix");

        parser.addArgument("-l", "--limit")
                .type(Integer.class)
                .setDefault(100)
                .help("Maximum number of entries to display (default: 100, use 0 for no limit)");

        parser.addArgument("-s", "--stats")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Show basic statistics about the index");

        try {
            Namespace ns = parser.parseArgs(args);
            String indexType = ns.getString("index_type");
            String basePath = ns.getString("db_path");
            String key = ns.getString("key");
            String prefix = ns.getString("prefix");
            int limit = ns.getInt("limit");
            boolean showStats = ns.getBoolean("stats");

            String dbPath = basePath + "/" + indexType;

            // Load annotation synonyms for stitch index if needed
            Map<String, Map<Integer, String>> annotationSynonyms = new HashMap<>();
            if (indexType.equals("stitch") && (key != null || prefix != null)) {
                annotationSynonyms = loadAnnotationSynonyms(basePath);
            }

            // Open the database
            Options options = new Options();
            try (DB db = factory.open(new File(dbPath), options)) {
                if (showStats) {
                    displayStats(db);
                }
                if (key != null) {
                    displayEntry(db, key, indexType, annotationSynonyms);
                } else if (prefix != null) {
                    listEntriesByPrefix(db, prefix, limit, indexType, annotationSynonyms);
                } else {
                    listAllEntries(db, limit, indexType, annotationSynonyms);
                }
            }
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error browsing database: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void displayStats(DB db) throws IOException {
        long totalEntries = 0;
        long totalPositions = 0;

        try (DBIterator iterator = db.iterator()) {
            for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
                totalEntries++;
                PositionList positions = PositionList.deserialize(iterator.peekNext().getValue());
                totalPositions += positions.size();
            }
        }

        System.out.println("Index Statistics");
        System.out.println("================");
        System.out.printf("Total entries: %,d%n", totalEntries);
        System.out.printf("Total positions: %,d%n", totalPositions);
        System.out.printf("Average positions per entry: %.2f%n", totalEntries > 0 ? (double) totalPositions / totalEntries : 0);
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
        System.out.println("All Entries");
        System.out.println("===========");
        
        int count = 0;
        try (DBIterator iterator = db.iterator()) {
            iterator.seekToFirst();
            
            while (iterator.hasNext() && (limit == 0 || count < limit)) {
                Map.Entry<byte[], byte[]> entry = iterator.peekNext();

                if (isNash) {
                    displayNashEntry(entry.getKey(), entry.getValue());
                } else {
                    String key = asString(entry.getKey());
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
