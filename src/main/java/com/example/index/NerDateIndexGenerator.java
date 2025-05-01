package com.example.index;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.logging.ProgressTracker;
import com.example.core.Position;
import com.example.core.PositionList;

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

    private Set<String> uniqueDatesProcessed = new HashSet<>();

    public NerDateIndexGenerator(String levelDbPath, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress) throws IOException {
        super(levelDbPath, stopwordsPath, sqliteConn, progress);
    }

    public NerDateIndexGenerator(String levelDbPath, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, IndexConfig config) throws IOException {
        super(levelDbPath, stopwordsPath, sqliteConn, progress, config);
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(int offset) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        String query = "SELECT a.annotation_id, a.document_id, a.sentence_id, a.begin_char, a.end_char, " +
                      "a.token, a.normalized_ner, a.ner, d.timestamp " +
                      "FROM annotations a " +
                      "JOIN documents d ON a.document_id = d.document_id " +
                      "WHERE a.ner = 'DATE' " +
                      "ORDER BY a.document_id, a.sentence_id, a.begin_char LIMIT ? OFFSET ?";
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(query)) {
            stmt.setInt(1, config.getBatchSize());
            stmt.setInt(2, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String normalizedDate = rs.getString("normalized_ner");
                    if (normalizedDate == null || !normalizedDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        logger.debug("Skipping invalid date format: {}", normalizedDate);
                        continue;
                    }

                    batch.add(new AnnotationEntry(
                        rs.getInt("annotation_id"),
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        normalizedDate, // Store normalized date in lemma field
                        "DATE", // Store NER type in pos field
                        LocalDate.parse(rs.getString("timestamp").substring(0, 10))
                    ));
                }
            }
        }
        return batch;
    }

    @Override
    protected ListMultimap<String, PositionList> processBatch(List<AnnotationEntry> batch) throws IOException {
        ListMultimap<String, PositionList> index = ArrayListMultimap.create();
        Map<String, PositionList> positionLists = new HashMap<>();
        
        for (AnnotationEntry entry : batch) {
            String normalizedDate = normalizeDate(entry.getLemma());
            if (normalizedDate == null) {
                logger.debug("Skipping invalid date format: {}", entry.getLemma());
                continue;
            }

            Position position = new Position(
                entry.getDocumentId(), 
                entry.getSentenceId(), 
                entry.getBeginChar(), 
                entry.getEndChar(), 
                entry.getTimestamp()
            );

            // Add to the set for tracking unique dates across the whole run
            this.uniqueDatesProcessed.add(normalizedDate);

            // Get or create position list for this date
            PositionList posList = positionLists.computeIfAbsent(normalizedDate, k -> new PositionList());
            posList.add(position);
        }
        
        // Add all position lists to result
        for (Map.Entry<String, PositionList> entry : positionLists.entrySet()) {
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
} 