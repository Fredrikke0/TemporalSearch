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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.core.PositionListSoA;
import com.example.index.AnnotationEntry;
import com.example.index.util.DateEntityMerger;
import com.example.index.util.DateEntityMerger.MergedDateEntity;
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

/**
 * Generates a streaming index for date entities from annotated text.
 * Extracts dates from the normalized_ner column where ner type is "DATE",
 * normalizes them to YYYYMMDD format, and stores their positions.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class NerDateIndexGenerator extends IndexGenerator<AnnotationEntry> {
    private static final Logger logger = LoggerFactory.getLogger(NerDateIndexGenerator.class);
    private static final DateTimeFormatter INPUT_FORMAT_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter INPUT_FORMAT_YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter INPUT_FORMAT_YEAR = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private Set<String> uniqueDatesProcessed = new HashSet<>();

    public NerDateIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public NerDateIndexGenerator(IndexAccessInterface indexAccess, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        String sql;
        // Keyset pagination on annotation_id for NER='DATE' entries
        if (lastProcessedEntry == null) {
            sql = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner " +
                  "FROM annotations WHERE ner = 'DATE' AND normalized_ner IS NOT NULL " +
                  "ORDER BY annotation_id LIMIT ?";
        } else {
            sql = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner " +
                  "FROM annotations WHERE ner = 'DATE' AND normalized_ner IS NOT NULL AND annotation_id > ? " +
                  "ORDER BY annotation_id LIMIT ?";
        }

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            if (lastProcessedEntry == null) {
                stmt.setInt(1, batchSize);
            } else {
                stmt.setLong(1, lastProcessedEntry.getAnnotationId());
                stmt.setInt(2, batchSize);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String normalizedNer = rs.getString("normalized_ner");
                    if (normalizedNer != null && !normalizedNer.trim().isEmpty()) {
                        batch.add(new AnnotationEntry(
                            rs.getLong("annotation_id"),
                            rs.getInt("document_id"),
                            rs.getInt("sentence_id"),
                            rs.getInt("begin_char"),
                            rs.getInt("end_char"),
                            rs.getString("token"),
                            rs.getString("pos"),
                            rs.getString("ner"),
                            normalizedNer
                        ));
                    }
                }
            }
        }
        return batch;
    }

    @Override
    protected ListMultimap<String, PositionListSoA> processBatch(List<AnnotationEntry> batch) {
        ListMultimap<String, PositionListSoA> index = ArrayListMultimap.create();
        if (batch.isEmpty()) {
            return index;
        }

        // We just need to sort the batch by position for the merging logic to work correctly.
        batch.sort(Comparator
            .comparingInt(AnnotationEntry::getDocumentId)
            .thenComparingInt(AnnotationEntry::getSentenceId)
            .thenComparingInt(AnnotationEntry::getBeginChar));

        List<MergedDateEntity> mergedEntities = DateEntityMerger.merge(batch);
        Map<String, PositionListSoA> tempAggregator = new HashMap<>();

        for (MergedDateEntity entity : mergedEntities) {
            String normalizedDateKey = normalizeDateToKeyFormat(entity.normalizedDate());
            if (normalizedDateKey != null) {
                this.uniqueDatesProcessed.add(normalizedDateKey);
                PositionListSoA pl = tempAggregator.computeIfAbsent(normalizedDateKey, k -> new PositionListSoA());
                pl.add(entity.documentId(), entity.sentenceId(), entity.beginChar(), entity.endChar());
            }
        }

        for (Map.Entry<String, PositionListSoA> entry : tempAggregator.entrySet()) {
            index.put(entry.getKey(), entry.getValue());
        }

        return index;
    }

    @Override
    public void generateIndex() throws SQLException, IOException {
        this.uniqueDatesProcessed.clear(); // Clear before starting
        logger.info("Starting NER Date index generation for index: {}", getIndexName());
        super.generateIndex(); // Execute standard batch processing and writing
        // Log the final count after super.generateIndex() finishes
        logger.info("Finished NER Date index generation. Found {} unique dates (YYYYMMDD format).",
                    this.uniqueDatesProcessed.size());
    }

    /**
     * Normalizes a date string from YYYY-MM-DD format to YYYYMMDD format.
     * Returns null if the input is not a valid date or not in the expected format.
     */
    public static String normalizeDateToKeyFormat(String date) {
        if (date == null || date.trim().isEmpty()) {
            return null;
        }
        String trimmedDate = date.trim();

        // Check for "0000" year early, as it's invalid for LocalDate
        if (trimmedDate.startsWith("0000")) {
            logger.trace("Invalid year '0000' in date string '{}'. Skipping.", trimmedDate);
            return null;
        }

        LocalDate parsedDate;
        try {
            // Attempt to parse as YYYY-MM-DD
            parsedDate = LocalDate.parse(trimmedDate, INPUT_FORMAT_FULL);
        } catch (DateTimeParseException e1) {
            try {
                // Attempt to parse as YYYY-MM
                // LocalDate.parse with "yyyy-MM" pattern will parse "YYYY-MM" to the first day of that month.
                java.time.YearMonth ym = java.time.YearMonth.parse(trimmedDate, INPUT_FORMAT_YEAR_MONTH);
                parsedDate = ym.atDay(1);
            } catch (DateTimeParseException e2) {
                try {
                    // Attempt to parse as YYYY
                    // LocalDate.parse with "yyyy" pattern will parse "YYYY" to YYYY-01-01.
                    // However, to be more explicit or handle cases where direct parsing "yyyy" isn't standard for LocalDate:
                    java.time.Year year = java.time.Year.parse(trimmedDate, INPUT_FORMAT_YEAR);
                    parsedDate = year.atDay(1); // First day of the year
                } catch (DateTimeParseException e3) {
                    logger.trace("Could not parse date string '{}' with available formats (YYYY-MM-DD, YYYY-MM, YYYY).", trimmedDate);
                    return null;
                }
            }
        }

        if (parsedDate.getYear() == 0) {
             logger.debug("Parsed date resulted in year 0000 for input '{}', which is invalid.", trimmedDate);
             return null;
        }

        return parsedDate.format(KEY_FORMAT);
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        return 0;
    }
}