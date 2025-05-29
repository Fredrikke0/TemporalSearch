package com.example.index.generators;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.pig.impl.util.MultiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.Position;
import com.example.index.AnnotationEntry;
import com.example.index.NashDateEntryWithId;
import com.example.index.util.NashSerializationUtils;
import com.example.logging.ProgressTracker;

import no.ntnu.sandbox.Nash;

/**
 * Generates a persistent LevelDB index for Nash time hashes.
 * Reads all DATE annotations, builds the Nash structure in memory,
 * and writes the inverted index and date lookup table to LevelDB.
 * Note: This generator loads all relevant data into memory, which might be an issue for very large datasets.
 */
public final class NashIndexGenerator extends IndexGenerator<AnnotationEntry> {
    private static final Logger logger = LoggerFactory.getLogger(NashIndexGenerator.class);
    private static final DateTimeFormatter NASH_INTERVAL_FORMATTER = DateTimeFormatter.ISO_DATE; // YYYY-MM-DD

    public NashIndexGenerator(String indexBaseDir, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize)
            throws IOException {
        this(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public NashIndexGenerator(String indexBaseDir, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath)
            throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    protected String getIndexName() {
        return "nash";
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        logger.warn("fetchBatch called unexpectedly in NashIndexGenerator. This should not happen with the current implementation.");
        return Collections.emptyList();
    }

    @Override
    protected com.google.common.collect.ListMultimap<String, com.example.core.PositionListSoA> processBatch(List<AnnotationEntry> batch) {
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

        Map<LocalDate, Integer> dateToId = new HashMap<>();
        List<LocalDate> idToDate = new ArrayList<>();
        List<String> intervalStrings = new ArrayList<>();
        Map<Integer, List<NashDateEntryWithId>> listIndexToEntries = new HashMap<>();
        long rawAnnotationsProcessed = 0;

        int currentDocId = -1;
        int currentSentId = -1;
        String currentNormalizedNer = null;
        int currentStartChar = -1;
        int currentEndChar = -1;
        int currentDateId = -1;

        logger.info("Fetching all DATE annotations from database...");
        String query = "SELECT a.document_id, a.sentence_id, a.begin_char, a.end_char, " +
                       "a.normalized_ner " +
                       "FROM annotations a " +
                       "WHERE a.ner = 'DATE' AND a.normalized_ner IS NOT NULL " +
                       "ORDER BY a.document_id, a.sentence_id, a.begin_char";

        try (PreparedStatement stmt = sqliteConn.prepareStatement(query); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                rawAnnotationsProcessed++;
                int docId = rs.getInt("document_id");
                int sentId = rs.getInt("sentence_id");
                int beginChar = rs.getInt("begin_char");
                int endChar = rs.getInt("end_char");
                String normalizedNer = rs.getString("normalized_ner");

                if (docId == currentDocId && sentId == currentSentId && Objects.equals(normalizedNer, currentNormalizedNer)) {
                    currentEndChar = endChar;
                } else {
                    if (currentDocId != -1 && currentDateId != -1) {
                        Position finalizedPosition = new Position(
                                currentDocId,
                                currentSentId,
                                currentStartChar,
                                currentEndChar
                        );
                        listIndexToEntries.get(currentDateId).add(new NashDateEntryWithId(finalizedPosition, currentDateId));
                    }
                    currentDocId = docId;
                    currentSentId = sentId;
                    currentNormalizedNer = normalizedNer;
                    currentStartChar = beginChar;
                    currentEndChar = endChar;
                    currentDateId = -1;

                    LocalDate docDate = parseNormalizedDate(normalizedNer);
                    if (docDate != null) {
                        final LocalDate finalDocDate = docDate;
                        currentDateId = dateToId.computeIfAbsent(docDate, date -> {
                            idToDate.add(date);
                            int newId = idToDate.size() - 1;
                            String interval = String.format("[%s , %s]",
                                    NASH_INTERVAL_FORMATTER.format(finalDocDate),
                                    NASH_INTERVAL_FORMATTER.format(finalDocDate));
                            intervalStrings.add(interval);
                            listIndexToEntries.put(newId, new ArrayList<>());
                            return newId;
                        });
                    } else {
                        logger.trace("Skipping invalid/unparseable normalized_ner date: {}", normalizedNer);
                    }
                }
            }
            if (currentDocId != -1 && currentDateId != -1) {
                Position finalizedPosition = new Position(
                        currentDocId,
                        currentSentId,
                        currentStartChar,
                        currentEndChar
                );
                 listIndexToEntries.get(currentDateId).add(new NashDateEntryWithId(finalizedPosition, currentDateId));
            }
        } catch (SQLException e) {
            logger.error("Database error fetching annotations for Nash index", e);
            throw e;
        }
        logger.info("Finished fetching {} raw DATE annotations. Found {} unique dates.", rawAnnotationsProcessed, idToDate.size());

        if (intervalStrings.isEmpty()) {
            logger.warn("No valid date intervals found. Nash index will be empty.");
             try {
                 indexAccess.put(NashSerializationUtils.DATE_LOOKUP_KEY, NashSerializationUtils.serializeDateLookup(Collections.emptyList()));
             } catch (IndexAccessException | IOException e) {
                 logger.error("Failed to write empty date lookup table", e);
             }
            return;
        }

        logger.info("Performing Nash inversion for {} unique date intervals...", intervalStrings.size());
        MultiMap<String, Integer> invertedIndex;
        try {
            invertedIndex = Nash.invert(intervalStrings);
            logger.info("Nash inversion complete. Found {} unique Nash prefixes.", invertedIndex.size());
        } catch (Exception e) {
            logger.error("Failed during Nash.invert call", e);
            throw new IOException("Failed to generate Nash inverted index", e);
        }

        logger.info("Writing Nash index data to LevelDB at {} ...", getIndexName());
        long termsWritten = 0;
        try {
            for (String nashPrefix : invertedIndex.keySet()) {
                List<NashDateEntryWithId> aggregatedEntries = new ArrayList<>();
                for (Integer dateIdFromNash : invertedIndex.get(nashPrefix)) {
                    List<NashDateEntryWithId> entriesForDate = listIndexToEntries.get(dateIdFromNash);
                    if (entriesForDate != null) {
                        aggregatedEntries.addAll(entriesForDate);
                    } else {
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
            throw new IOException("Failed to serialize Nash index data for LevelDB", e);
        }
    }

    private LocalDate parseNormalizedDate(String dateStr) {
        if (dateStr == null) return null;
        try {
            return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e1) {
            try {
                if (dateStr.matches("^\\d{4}$")) {
                    return LocalDate.parse(dateStr + "-01-01");
                } else if (dateStr.matches("^\\d{4}-\\d{2}$")) {
                    return LocalDate.parse(dateStr + "-01");
                }
            } catch (DateTimeParseException e2) {
                // Fall through
            }
            logger.trace("Could not parse date string '{}' with available formats.", dateStr);
            return null;
        }
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        // For Nash index, we are interested in documents that have DATE entities
        // which also have a corresponding entry in `political_actors` or `event_summaries`.
        String countSql = "SELECT COUNT(DISTINCT document_id) FROM annotations WHERE ner = 'DATE' AND normalized_ner IS NOT NULL";
        try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }
}