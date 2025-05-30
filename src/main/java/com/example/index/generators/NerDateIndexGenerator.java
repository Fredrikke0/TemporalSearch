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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final DateTimeFormatter INPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private Set<String> uniqueDatesProcessed = new HashSet<>();

    public NerDateIndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        this(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, null);
    }

    public NerDateIndexGenerator(String indexBaseDir, String stopwordsPath, Connection sqliteConn, ProgressTracker progress, int batchSize, Path customTempPath) throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize, customTempPath);
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
                    // Further validation for YYYY-MM-DD format
                    if (normalizedNer != null && DATE_PATTERN.matcher(normalizedNer).matches()) {
                        try {
                            LocalDate.parse(normalizedNer); // Strict parse check
                            batch.add(new AnnotationEntry(
                                rs.getLong("annotation_id"),
                                rs.getInt("document_id"),
                                rs.getInt("sentence_id"),
                                rs.getInt("begin_char"),
                                rs.getInt("end_char"),
                                rs.getString("token"), // Original token might be useful for context
                                rs.getString("pos"),
                                rs.getString("ner"),
                                normalizedNer, // Key for this index is the normalized date string
                                rs.getString("lemma")
                            ));
                        } catch (DateTimeParseException e) {
                            logger.debug("Skipping entry with unparseable normalized_ner date: {}", normalizedNer);
                        }
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

        // Sort by document, sentence, and then start position to correctly group consecutive entities.
        Collections.sort(batch, Comparator
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

            String normalizedDateKey = normalizeDate(rawNormalizedDate); // Convert to YYYYMMDD
            if (normalizedDateKey == null) {
                //logger.debug("Could not normalize date for key: {}", rawNormalizedDate);
                processAndClearCurrentDateEntity(tempAggregator, currentMergedEntityTokens);
                continue;
            }

            if (currentMergedEntityTokens.isEmpty()) {
                currentMergedEntityTokens.add(currentEntry);
            } else {
                AnnotationEntry prevEntry = currentMergedEntityTokens.get(currentMergedEntityTokens.size() - 1);
                String prevNormalizedDateKey = normalizeDate(prevEntry.getNormalizedNer());

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
        String normalizedDateKey = normalizeDate(rawNormalizedDate);

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
    private String normalizeDate(String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            LocalDate parsed = LocalDate.parse(date, INPUT_FORMAT);
            return parsed.format(KEY_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    protected String getIndexName() {
        return "ner_date";
    }

    @Override
    public long getDocumentCountForIndex() throws SQLException {
        // Count documents that have at least one 'DATE' entity with a valid normalized_ner.
        // This might be slow if many dates don't match the pattern.
        // A simpler count from annotations might be acceptable if performance is an issue.
        // String countSql = "SELECT MAX(annotation_id) FROM annotations WHERE ner = 'DATE' AND normalized_ner IS NOT NULL AND normalized_ner LIKE '____-__-__'";
        // long count = 0;
        // try (PreparedStatement stmt = sqliteConn.prepareStatement(countSql)) {
        //     try (ResultSet rs = stmt.executeQuery()) {
        //         if (rs.next()) {
        //             count = rs.getLong(1);
        //         }
        //     }
        // }
        // return count;
        // Return 0 to indicate an indeterminate progress bar, as MAX(annotation_id) is not representative.
        return 0;
    }
}