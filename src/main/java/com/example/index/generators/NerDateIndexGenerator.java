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
import com.example.logging.ProgressTracker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

/**
 * Generates a streaming index for date entities from annotated text.
 * Extracts dates from the normalized_ner column where ner type is "DATE",
 * normalizes them to YYYYMMDD format, and stores their positions.
 * Uses streaming processing and external sorting for efficient memory usage.
 *
 * This implementation is now RocksDB-based (see IndexGenerator).
 *
 * <h2>Date Extraction and Indexing Process</h2>
 * <ol>
 *   <li>The NLP pipeline identifies date mentions in the text using Named Entity Recognition (NER)</li>
 *   <li>Date entities are normalized to YYYY-MM-DD format during NLP processing</li>
 *   <li>This index generator extracts those normalized dates and converts them to YYYYMMDD format for storage</li>
 *   <li>For each date mention, the document ID, sentence ID, and character position are recorded</li>
 * </ol>
 *
 * <h2>Relationship with DATE Operator in Queries</h2>
 * <p>When querying with the DATE operator (e.g., DATE(CONTAINS [2023, 2024])), the system will:
 * <ol>
 *   <li>Use the Nash index to quickly filter documents containing date mentions in the specified range</li>
 *   <li>When used with GRANULARITY SENTENCE, resolve the specific sentences that contain these date mentions</li>
 * </ol>
 * <p>Available predicates for DATE queries:
 * <ul>
 *   <li>CONTAINS: Returns documents where mentioned dates fall entirely within the query range</li>
 *   <li>INTERSECT: Returns documents with any date mention that overlaps the query range (more lenient)</li>
 *   <li>CONTAINED_BY: Returns documents where mentioned dates contain the entire query range</li>
 *   <li>BEFORE, AFTER, EQUAL: Compare with specific date values</li>
 * </ul>
 *
 * <p>For optimal results with date range searches spanning multiple years, use the INTERSECT predicate
 * rather than CONTAINS, as CONTAINS requires dates to be fully contained within the range.
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
            sql = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner, lemma " +
                  "FROM annotations WHERE ner = 'DATE' AND normalized_ner IS NOT NULL " +
                  "ORDER BY annotation_id LIMIT ?";
        } else {
            sql = "SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, pos, ner, normalized_ner, lemma " +
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
                    // Lenient parsing will be handled by normalizeDate and processBatch
                    // No strict check here anymore, just ensure it's not null/empty before adding
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
                            normalizedNer, // Pass the raw normalized_ner
                            rs.getString("lemma")
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

        // Span filtering is now handled upstream by Annotations.java.
        // We just need to sort the batch by position for the merging logic to work correctly.
        batch.sort(Comparator
            .comparingInt(AnnotationEntry::getDocumentId)
            .thenComparingInt(AnnotationEntry::getSentenceId)
            .thenComparingInt(AnnotationEntry::getBeginChar));

        Map<String, PositionListSoA> tempAggregator = new HashMap<>();
        List<AnnotationEntry> currentMergedEntityTokens = new ArrayList<>();

        for (int i = 0; i < batch.size(); i++) {
            AnnotationEntry currentEntry = batch.get(i);
            String rawNormalizedDate = currentEntry.getNormalizedNer(); // This is YYYY-MM-DD

            if (rawNormalizedDate == null || rawNormalizedDate.isEmpty()) {
                processAndClearCurrentDateEntity(tempAggregator, currentMergedEntityTokens);
                continue;
            }

            String normalizedDateKey = normalizeDateToKeyFormat(rawNormalizedDate); // Use public static method
            if (normalizedDateKey == null) {
                //logger.debug("Could not normalize date for key: {}", rawNormalizedDate);
                processAndClearCurrentDateEntity(tempAggregator, currentMergedEntityTokens);
                continue;
            }

            if (currentMergedEntityTokens.isEmpty()) {
                currentMergedEntityTokens.add(currentEntry);
            } else {
                AnnotationEntry prevEntry = currentMergedEntityTokens.get(currentMergedEntityTokens.size() - 1);
                String prevNormalizedDateKey = normalizeDateToKeyFormat(prevEntry.getNormalizedNer());

                // Check for entity break
                if (!normalizedDateKey.equals(prevNormalizedDateKey) ||
                    currentEntry.getDocumentId() != prevEntry.getDocumentId() ||
                    currentEntry.getSentenceId() != prevEntry.getSentenceId() ||
                    currentEntry.getBeginChar() > prevEntry.getEndChar() + 2) { // Allow a small gap (e.g., space, hyphen)
                    processAndClearCurrentDateEntity(tempAggregator, currentMergedEntityTokens);
                    currentMergedEntityTokens.add(currentEntry);
                } else {
                    // Continue current entity
                    currentMergedEntityTokens.add(currentEntry);
                }
            }
        }

        // Process any remaining entity
        processAndClearCurrentDateEntity(tempAggregator, currentMergedEntityTokens);

        for (Map.Entry<String, PositionListSoA> mapEntry : tempAggregator.entrySet()) {
            index.put(mapEntry.getKey(), mapEntry.getValue());
        }
        return index;
    }

    private void processAndClearCurrentDateEntity(Map<String, PositionListSoA> tempAggregator, List<AnnotationEntry> currentEntityTokens) {
        if (currentEntityTokens.isEmpty()) {
            return;
        }

        AnnotationEntry firstToken = currentEntityTokens.get(0);
        AnnotationEntry lastToken = currentEntityTokens.get(currentEntityTokens.size() - 1);

        String rawNormalizedDate = firstToken.getNormalizedNer(); // All tokens in the list should have the same normalized date
        String normalizedDateKey = normalizeDateToKeyFormat(rawNormalizedDate);

        if (normalizedDateKey == null) {
            logger.warn("Skipping entity due to normalization failure for date '{}' at doc/sent/char: {}/{}/{}",
                rawNormalizedDate, firstToken.getDocumentId(), firstToken.getSentenceId(), firstToken.getBeginChar());
            currentEntityTokens.clear();
            return;
        }

        this.uniqueDatesProcessed.add(normalizedDateKey); // Populate for logging

        PositionListSoA pl = tempAggregator.computeIfAbsent(normalizedDateKey, k -> new PositionListSoA());
        // Use beginChar of the first token and endChar of the last token
        pl.add(firstToken.getDocumentId(), firstToken.getSentenceId(), firstToken.getBeginChar(), lastToken.getEndChar());

        currentEntityTokens.clear();
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

        // Check again for year 0000 after parsing attempts (e.g. if a lenient parser somehow allowed it)
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
        // Return 0 to indicate an indeterminate progress bar, as MAX(annotation_id) is not representative.
        return 0;
    }
}