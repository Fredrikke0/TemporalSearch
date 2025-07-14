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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.pig.impl.util.MultiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.core.Position;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationEntry;
import com.example.index.NashDateEntryWithId;
import com.example.index.util.DateEntityMerger;
import com.example.index.util.DateEntityMerger.MergedDateEntity;
import com.example.index.util.NashSerializationUtils;
import com.example.logging.ProgressTracker;

import no.ntnu.sandbox.Nash;

/**
 * Generates a persistent RocksDB index for Nash time hashes.
 * Reads all DATE annotations, builds the Nash structure in memory,
 * and writes the inverted index and date lookup table to RocksDB.
 * Note: This generator loads all relevant data into memory, which might be an issue for very large datasets.
 */
public final class NashIndexGenerator extends IndexGenerator<AnnotationEntry> {
    private static final Logger logger = LoggerFactory.getLogger(NashIndexGenerator.class);
    private static final DateTimeFormatter NASH_INTERVAL_FORMATTER = DateTimeFormatter.ISO_DATE; // YYYY-MM-DD
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private long nashTermsWritten = 0; // Field to store terms written by this generator

    public NashIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public NashIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
    }

    @Override
    protected String getTableName() {
        return "annotations";
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
     * performs inversion, and writes results directly to RocksDB.
     * Bypasses the standard batching/external sort mechanism of IndexGenerator.
     */
    @Override
    public void generateIndex() throws SQLException, IOException {
        logger.info("Starting Nash index generation (in-memory build)... Index Name: {}", getIndexName());
        long startTime = System.currentTimeMillis();
        this.nashTermsWritten = 0; // Initialize for this run

        Map<LocalDate, Integer> dateToId = new HashMap<>();
        List<LocalDate> idToDate = new ArrayList<>();
        List<String> intervalStrings = new ArrayList<>();
        Map<Integer, List<NashDateEntryWithId>> listIndexToEntries = new HashMap<>();
        long rawAnnotationsFetched = 0;

        logger.info("Fetching all DATE annotations from database for Nash index...");
        String query = "SELECT a.document_id, a.sentence_id, a.begin_char, a.end_char, " +
                       "a.normalized_ner, a.token, a.pos, a.ner, a.annotation_id " +
                       "FROM annotations a " +
                       "WHERE a.ner = 'DATE' AND a.normalized_ner IS NOT NULL " +
                       "ORDER BY a.document_id, a.sentence_id, a.begin_char";

        List<AnnotationEntry> allDateAnnotations = new ArrayList<>();
        try (PreparedStatement stmt = sqliteConn.prepareStatement(query); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                rawAnnotationsFetched++;
                allDateAnnotations.add(new AnnotationEntry(
                    rs.getLong("annotation_id"),
                    rs.getInt("document_id"),
                    rs.getInt("sentence_id"),
                    rs.getInt("begin_char"),
                    rs.getInt("end_char"),
                    rs.getString("token"),
                    rs.getString("pos"),
                    rs.getString("ner"),
                    rs.getString("normalized_ner")
                ));
            }
        }
        logger.info("Finished fetching {} raw DATE annotations from DB.", rawAnnotationsFetched);

        if (allDateAnnotations.isEmpty()) {
            logger.warn("No DATE annotations found. Nash index will be empty.");
            try {
                writeEmptyNashIndex(indexAccess);
            } catch (IndexAccessException e) {
                logger.error("Failed to write empty Nash index due to IndexAccessException", e);
                throw new IOException("Failed to write empty Nash index", e);
            }
            return;
        }

        // The SQL query already sorts the annotations.
        List<MergedDateEntity> mergedEntities = DateEntityMerger.merge(allDateAnnotations);

        for (MergedDateEntity entity : mergedEntities) {
            LocalDate parsedDate = parseNormalizedDate(entity.normalizedDate());
            if (parsedDate == null) {
                logger.trace("Skipping invalid/unparseable normalized_ner date: {} for entity at doc/sent: {}/{}",
                                entity.normalizedDate(), entity.documentId(), entity.sentenceId());
                continue;
            }

            final LocalDate finalDocDate = parsedDate;
            int dateId = dateToId.computeIfAbsent(parsedDate, date -> {
                idToDate.add(date);
                int newId = idToDate.size() - 1;
                String interval = String.format("[%s , %s]",
                        NASH_INTERVAL_FORMATTER.format(finalDocDate),
                        NASH_INTERVAL_FORMATTER.format(finalDocDate));
                intervalStrings.add(interval);
                return newId;
            });

            Position finalizedPosition = new Position(entity.documentId(), entity.sentenceId(), entity.beginChar(), entity.endChar());
            listIndexToEntries.computeIfAbsent(dateId, k -> new ArrayList<>())
                            .add(new NashDateEntryWithId(finalizedPosition, dateId));
        }

        logger.info("Finished processing fetched annotations. Found {} unique dates for Nash intervals.", idToDate.size());

        if (intervalStrings.isEmpty()) {
            logger.warn("No valid date intervals found after processing. Nash index will be empty.");
            try {
                writeEmptyNashIndex(indexAccess);
            } catch (IndexAccessException e) {
                logger.error("Failed to write empty Nash index due to IndexAccessException", e);
                throw new IOException("Failed to write empty Nash index", e);
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

        logger.info("Writing Nash index data to RocksDB at {} ...", getIndexName());
        long termsWritten = 0;
        try {
            for (String nashPrefix : invertedIndex.keySet()) {
                // Calculate the potential size of aggregatedEntries for logging
                long potentialAggregatedEntryCount = 0;
                java.util.Collection<Integer> dateIdsForThisPrefix = invertedIndex.get(nashPrefix);
                if (dateIdsForThisPrefix == null) {
                    dateIdsForThisPrefix = Collections.emptyList(); // Should not happen with MultiMap but defensive
                }

                for (Integer dateIdFromNash : dateIdsForThisPrefix) {
                    List<NashDateEntryWithId> entriesForDate = listIndexToEntries.get(dateIdFromNash);
                    if (entriesForDate != null) {
                        potentialAggregatedEntryCount += entriesForDate.size();
                    }
                }

                logger.trace("Processing Nash prefix: '{}'. Maps to {} unique dates. Aggregating a total of ~{} NashDateEntryWithId objects.",
                            nashPrefix, dateIdsForThisPrefix.size(), potentialAggregatedEntryCount);

                if (potentialAggregatedEntryCount > 50_000_000) { // Log a strong warning if it's very large
                    // Estimate based on PositionListSoA might be different, but this warning is about raw entry count
                    long estimatedRawBytes = (potentialAggregatedEntryCount * 20L) + 4L;
                    logger.warn("HIGH POTENTIAL FOR LARGE SERIALIZED BLOB (using PositionListSoA now): Nash prefix '{}' will attempt to aggregate {} entries. Old estimated raw serialized byte array size: ~{} bytes.",
                                nashPrefix, potentialAggregatedEntryCount, estimatedRawBytes);
                }

                PositionListSoA aggregatedEntriesSoA = new PositionListSoA(); // Changed from List<NashDateEntryWithId>
                for (Integer dateIdFromNash : dateIdsForThisPrefix) {
                    List<NashDateEntryWithId> entriesForDate = listIndexToEntries.get(dateIdFromNash);
                    if (entriesForDate != null) {
                        for (NashDateEntryWithId entry : entriesForDate) {
                            Position pos = entry.position();
                            // Add to PositionListSoA, using dateId as synonymId
                            aggregatedEntriesSoA.add(pos.getDocumentId(), pos.getSentenceId(), pos.getBeginPosition(), pos.getEndPosition(), entry.dateId());
                        }
                    } else {
                        logger.warn("Inconsistency: Nash prefix '{}' mapped to date ID {} which has no entries.", nashPrefix, dateIdFromNash);
                    }
                }

                if (!aggregatedEntriesSoA.isEmpty()) {
                    // PositionListSoA's serializeToCompositeBlob handles compression.
                    // Sorting aggregatedEntriesSoA.sort() could be done here if needed to optimize delta coding further,
                    // but PositionListSoA's delta coding works on individual arrays which are built in added order.
                    // The main benefit of sorting would be for docId, sentId arrays if they are not already largely sorted by the aggregation logic.
                    // Given the structure (iteration by nashPrefix, then by dateIdFromNash, then by entriesForDate),
                    // the order might not be optimal for docId/sentId compression across all entries for a single Nash prefix.
                    // However, let's try without explicit sort first.

                    byte[] serializedEntries = aggregatedEntriesSoA.serializeToCompositeBlob(); // Changed serialization
                    indexAccess.put(bytes(nashPrefix), serializedEntries);
                    termsWritten++;
                    if (termsWritten % 1000 == 0) {
                        logger.info("Written {} Nash prefixes to RocksDB...", termsWritten);
                    }
                }
            }
            byte[] serializedLookup = NashSerializationUtils.serializeDateLookup(idToDate);
            indexAccess.put(NashSerializationUtils.DATE_LOOKUP_KEY, serializedLookup);
            logger.info("Written date lookup table ({} entries) to RocksDB.", idToDate.size());
            long endTime = System.currentTimeMillis();
            this.nashTermsWritten = termsWritten; // Update the field
            logger.info("Successfully generated Nash index. Total unique prefixes written: {}. Time taken: {} ms",
                    termsWritten, (endTime - startTime));
        } catch (IndexAccessException e) {
            logger.error("RocksDB error writing Nash index data", e);
            throw new IOException("Failed to write Nash index to RocksDB", e);
        } catch (IOException e) {
            logger.error("Serialization error writing Nash index data", e);
            throw new IOException("Failed to serialize Nash index data for RocksDB", e);
        }
    }

    private void writeEmptyNashIndex(IndexAccessInterface idxAccess) throws IOException, IndexAccessException {
        idxAccess.put(NashSerializationUtils.DATE_LOOKUP_KEY, NashSerializationUtils.serializeDateLookup(Collections.emptyList()));
        logger.info("Wrote empty date lookup table for Nash index.");
        this.nashTermsWritten = 0; // Ensure count is 0 if index is empty
    }

    private LocalDate parseNormalizedDate(String dateStr) {
        String key = NerDateIndexGenerator.normalizeDateToKeyFormat(dateStr);
        if (key == null) {
            return null;
        }
        try {
            // NerDateIndexGenerator.normalizeDateToKeyFormat returns a string in 'yyyyMMdd' format.
            return LocalDate.parse(key, DateTimeFormatter.ofPattern("yyyyMMdd"));
        } catch (DateTimeParseException e) {
            // This path should ideally not be taken if normalizeDateToKeyFormat is correct.
            logger.warn("Failed to parse date key '{}' generated from input '{}'", key, dateStr, e);
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

    @Override
    public long getTotalTermsWrittenToIndex() {
        return this.nashTermsWritten;
    }
}