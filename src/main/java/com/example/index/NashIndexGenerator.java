package com.example.index;

import com.example.core.IndexAccessException;
import com.example.core.Position;
import com.example.index.util.NashSerializationUtils;
import com.example.logging.ProgressTracker;
import no.ntnu.sandbox.Nash;
import org.apache.pig.impl.util.MultiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generates a persistent LevelDB index for Nash time hashes.
 * Reads all DATE annotations, builds the Nash structure in memory,
 * and writes the inverted index and date lookup table to LevelDB.
 * Note: This generator loads all relevant data into memory, which might be an issue for very large datasets.
 */
public final class NashIndexGenerator extends IndexGenerator<AnnotationEntry> {
    private static final Logger logger = LoggerFactory.getLogger(NashIndexGenerator.class);
    private static final DateTimeFormatter NASH_INTERVAL_FORMATTER = DateTimeFormatter.ISO_DATE; // YYYY-MM-DD

    public NashIndexGenerator(String levelDbPath, String stopwordsPath, Connection sqliteConn, ProgressTracker progress)
            throws IOException {
        super(levelDbPath, stopwordsPath, sqliteConn, progress);
    }

    public NashIndexGenerator(String levelDbPath, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, IndexConfig config)
            throws IOException {
        super(levelDbPath, stopwordsPath, sqliteConn, progress, config);
    }

    @Override
    protected String getTableName() {
        return "annotations"; // Source table
    }

    @Override
    protected String getIndexName() {
        return "nash"; // Name of this specific index
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(int offset) throws SQLException {
        // This method is technically not used because generateIndex fetches all data at once.
        // Provide a basic implementation to satisfy the abstract class, but it won't be called by the overridden generateIndex.
        logger.warn("fetchBatch called unexpectedly in NashIndexGenerator. This should not happen.");
        return Collections.emptyList();
    }

    @Override
    protected com.google.common.collect.ListMultimap<String, com.example.core.PositionList> processBatch(List<AnnotationEntry> batch) throws IOException {
        // This method is also not used due to the overridden generateIndex.
        logger.warn("processBatch called unexpectedly in NashIndexGenerator. This should not happen.");
        return com.google.common.collect.ArrayListMultimap.create();
    }

    /**
     * Overrides the base generateIndex to implement Nash-specific logic.
     * Fetches all DATE annotations, builds the Nash structure in memory,
     * performs inversion, and writes results directly to LevelDB.
     * Bypasses the standard batching/external sort mechanism of IndexGenerator.
     */
    @Override
    public void generateIndex() throws SQLException, IOException {
        logger.info("Starting Nash index generation (in-memory build)... Index Name: {}", getIndexName());
        long startTime = System.currentTimeMillis();

        // Data structures built in memory
        Map<LocalDate, Integer> dateToId = new HashMap<>();
        List<LocalDate> idToDate = new ArrayList<>();
        List<String> intervalStrings = new ArrayList<>();
        // Map from the original interval list index to the list of entries for that interval
        Map<Integer, List<NashDateEntryWithId>> listIndexToEntries = new HashMap<>();
        int intervalIndexCounter = 0;
        long rawAnnotationsProcessed = 0;

        // 1. Fetch ALL relevant annotations (potentially memory-intensive)
        logger.info("Fetching all DATE annotations from database...");
        String query = "SELECT a.document_id, a.sentence_id, a.begin_char, a.end_char, " +
                       "a.normalized_ner, d.timestamp " +
                       "FROM annotations a JOIN documents d ON a.document_id = d.document_id " +
                       "WHERE a.ner = 'DATE' " +
                       "ORDER BY a.document_id, a.sentence_id, a.begin_char";

        try (PreparedStatement stmt = sqliteConn.prepareStatement(query); ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                rawAnnotationsProcessed++;
                String normalizedDateStr = rs.getString("normalized_ner");
                LocalDate docDate = parseNormalizedDate(normalizedDateStr);
                if (docDate == null) {
                    logger.trace("Skipping invalid/unparseable normalized_ner date: {}", normalizedDateStr);
                    continue;
                }
                LocalDate timestamp = LocalDate.parse(rs.getString("timestamp").substring(0, 10)); // Assuming timestamp is valid

                // Get or create Date ID
                int dateId = dateToId.computeIfAbsent(docDate, date -> {
                    idToDate.add(date);
                    return idToDate.size() - 1; // 0-based ID
                });

                // Create Position
                Position position = new Position(
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        timestamp // Use document timestamp for Position
                );

                // Create interval string *only if* this is the first time we see this dateId
                // Store mapping from interval index -> entries for that date
                if (!listIndexToEntries.containsKey(dateId)) {
                    String interval = String.format("[%s , %s]",
                            NASH_INTERVAL_FORMATTER.format(docDate),
                            NASH_INTERVAL_FORMATTER.format(docDate));
                    intervalStrings.add(interval); // Add interval string
                    listIndexToEntries.put(dateId, new ArrayList<>()); // Initialize list for this date ID
                    // Important: The index in intervalStrings corresponds to dateId here because we add
                    // intervalString only when dateId is first seen.
                }

                // Add the entry (position + dateId) to the list associated with this dateId
                listIndexToEntries.get(dateId).add(new NashDateEntryWithId(position, dateId));

                if (rawAnnotationsProcessed % 50000 == 0) {
                    logger.info("Fetched {} raw DATE annotations...", rawAnnotationsProcessed);
                }
            }
        } catch (SQLException e) {
            logger.error("Database error fetching annotations for Nash index", e);
            throw e;
        }
        logger.info("Finished fetching {} raw DATE annotations. Found {} unique dates.", rawAnnotationsProcessed, idToDate.size());

        if (intervalStrings.isEmpty()) {
            logger.warn("No valid date intervals found. Nash index will be empty.");
            // Ensure index is created but potentially empty
             try {
                 indexAccess.put(NashSerializationUtils.DATE_LOOKUP_KEY, NashSerializationUtils.serializeDateLookup(Collections.emptyList()));
             } catch (IndexAccessException | IOException e) {
                 logger.error("Failed to write empty date lookup table", e);
             }
            return; // Nothing more to do
        }

        // 2. Perform Nash Inversion
        logger.info("Performing Nash inversion for {} unique date intervals...", intervalStrings.size());
        // Revert: Go back to using Nash.invert as shown in the Nash.java example
        MultiMap<String, Integer> invertedIndex; // Maps Nash Prefix -> List of original interval string indices
        // Map<String, List<NashDateEntryWithId>> prefixToEntriesMap = new HashMap<>();
        // int dateIdCounter = 0;

        try {
            // Revert: Call Nash.invert on the list of point interval strings
            invertedIndex = Nash.invert(intervalStrings);
            logger.info("Nash inversion complete. Found {} unique Nash prefixes.", invertedIndex.size());
            
            // // Iterate through each unique date and its corresponding entries
            // for (Map.Entry<Integer, List<NashDateEntryWithId>> dateEntry : listIndexToEntries.entrySet()) {
            //     int dateId = dateEntry.getKey();
            //     List<NashDateEntryWithId> entriesForThisDate = dateEntry.getValue();
            //     LocalDate currentDate = idToDate.get(dateId);
            //     String pointInterval = String.format("[%s , %s]",
            //                                     NASH_INTERVAL_FORMATTER.format(currentDate),
            //                                     NASH_INTERVAL_FORMATTER.format(currentDate));

            //     // Generate prefixes using CONTAINED_BY for this point interval
            //     String[] indexingPrefixes = Nash.generateTimeHash(pointInterval, Nash.RangePredicate.CONTAINED_BY);

            //     // Map these prefixes to the list of entries for this date
            //     for (String prefix : indexingPrefixes) {
            //         prefixToEntriesMap.computeIfAbsent(prefix, k -> new ArrayList<>()).addAll(entriesForThisDate);
            //     }

            //     dateIdCounter++;
            //     if (dateIdCounter % 1000 == 0) {
            //         logger.info("Generated indexing prefixes for {} unique dates...", dateIdCounter);
            //     }
            // }
            //  logger.info("Prefix generation complete. Found {} unique Nash prefixes to store.", prefixToEntriesMap.size());
        } catch (Exception e) {
            logger.error("Failed during Nash.invert call", e);
            throw new IOException("Failed to generate Nash inverted index", e);
        }

        // 3. Write to LevelDB
        logger.info("Writing Nash index data to LevelDB at {} ...", indexAccess.getIndexType());
        long termsWritten = 0;
        try {
            // Revert: Write main Nash prefix entries using the invertedIndex from Nash.invert
            for (String nashPrefix : invertedIndex.keySet()) {
                List<NashDateEntryWithId> aggregatedEntries = new ArrayList<>();
                // The indices in invertedIndex.get(nashPrefix) correspond to the original intervalStrings list,
                // which in our setup correspond directly to the dateId.
                for (Integer dateIdFromNash : invertedIndex.get(nashPrefix)) {
                    List<NashDateEntryWithId> entriesForDate = listIndexToEntries.get(dateIdFromNash);
                    if (entriesForDate != null) {
                        aggregatedEntries.addAll(entriesForDate);
                    } else {
                        // This case might indicate an issue if Nash.invert returns indices outside the range 0 to idToDate.size()-1
                        logger.warn("Inconsistency: Nash prefix '{}' mapped to date ID {} which has no entries.", nashPrefix, dateIdFromNash);
                    }
                }

                if (!aggregatedEntries.isEmpty()) {
                    byte[] serializedEntries = NashSerializationUtils.serializeNashEntries(aggregatedEntries);
                    indexAccess.put(bytes(nashPrefix), serializedEntries);
                    termsWritten++;
                    if (termsWritten % 1000 == 0) {
                        logger.info("Written {} Nash prefixes to LevelDB...", termsWritten);
                    }
                }
            }

            // Write the date lookup table
            byte[] serializedLookup = NashSerializationUtils.serializeDateLookup(idToDate);
            indexAccess.put(NashSerializationUtils.DATE_LOOKUP_KEY, serializedLookup);
            logger.info("Written date lookup table ({} entries) to LevelDB.", idToDate.size());

            long endTime = System.currentTimeMillis();
            logger.info("Successfully generated Nash index. Total unique prefixes written: {}. Time taken: {} ms",
                    termsWritten, (endTime - startTime));

        } catch (IndexAccessException e) {
            logger.error("LevelDB error writing Nash index data", e);
            throw new IOException("Failed to write Nash index to LevelDB", e);
        } catch (IOException e) {
            logger.error("Serialization error writing Nash index data", e);
            throw e;
        }
        // Note: IndexAccess is closed by the superclass or caller managing the generator lifecycle.
    }

    /**
     * Helper to parse normalized date string (YYYY-MM-DD).
     */
    private LocalDate parseNormalizedDate(String dateStr) {
        if (dateStr == null || dateStr.length() != 10) { // Basic format check
            return null;
        }
        try {
            // Use the same formatter expected in the database
            return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            logger.trace("Could not parse date string '{}': {}", dateStr, e.getMessage());
            return null;
        }
    }

    // Helper method inherited from IndexGenerator
    // protected static byte[] bytes(String str)
} 