package com.example.index;

import com.example.logging.ProgressTracker;
import com.example.core.PositionList;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * Generates a stitch index that connects unigrams with various types of annotations.
 * The index enables efficient querying of relationships between words and 
 * different annotation types (DATE, NER, POS, DEPENDENCY) in the document collection.
 */
public class StitchIndexGenerator extends IndexGenerator<StitchEntry> {
    private static final Logger logger = LoggerFactory.getLogger(StitchIndexGenerator.class);
    private final MultiAnnotationSynonyms annotationSynonyms;
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static class DocumentInfo {
        final int documentId;
        final LocalDate timestamp;

        DocumentInfo(int documentId, LocalDate timestamp) {
            this.documentId = documentId;
            this.timestamp = timestamp;
        }
    }

    public StitchIndexGenerator(String indexBaseDir, String stopwordsPath,
            java.sql.Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize);
        Path baseDir = Path.of(indexBaseDir);
        this.annotationSynonyms = new MultiAnnotationSynonyms(baseDir);
        try {
            logger.info("Initializing annotation synonyms for stitch index");
            populateAnnotationSynonyms();
            logger.info("Successfully initialized annotation synonyms with {} entries", annotationSynonyms.size());
        } catch (SQLException e) {
            // Close resources to prevent memory leaks
            try {
                annotationSynonyms.close();
            } catch (Exception ex) {
                logger.warn("Failed to close annotation synonyms after initialization error", ex);
            }
            throw new IOException("Failed to populate annotation synonyms", e);
        }
    }

    /**
     * Populates all annotation synonyms from the database.
     */
    private void populateAnnotationSynonyms() throws SQLException, IOException {
        populateDateSynonyms();
        populateNerSynonyms();
        populatePosSynonyms();
        populateDependencySynonyms();
        
        // Validate synonyms to ensure consistency
        annotationSynonyms.validateSynonyms();
        logger.info("Populated all annotation synonyms");
    }

    /**
     * Populates date synonyms from the database.
     * Fetches all unique normalized date entities and creates mappings for them.
     */
    private void populateDateSynonyms() throws SQLException, IOException {
        String query = """
            SELECT DISTINCT normalized_ner
            FROM annotations
            WHERE ner = 'DATE'
                AND normalized_ner IS NOT NULL
                AND normalized_ner LIKE '____-__-__'
            ORDER BY normalized_ner
        """;

        int count = 0;
        int skipped = 0;
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String dateValue = rs.getString(1);
                // Add extra validation to ensure proper date format (YYYY-MM-DD)
                if (dateValue != null && DATE_PATTERN.matcher(dateValue).matches()) {
                    try {
                        // Additional validation by trying to parse as LocalDate
                        LocalDate.parse(dateValue);
                        annotationSynonyms.getOrCreateId(dateValue, AnnotationType.DATE);
                        count++;
                    } catch (DateTimeParseException e) {
                        logger.debug("Filtered out invalid date: {} (not a valid date)", dateValue);
                        skipped++;
                    } catch (IllegalArgumentException e) {
                        logger.warn("Skipping invalid annotation: {}", e.getMessage());
                        skipped++;
                    }
                } else if (dateValue != null) {
                    logger.debug("Filtered out invalid date: {} (does not match YYYY-MM-DD pattern)", dateValue);
                    skipped++;
                }
            }
        }
        
        if (skipped > 0) {
            logger.info("Populated {} date synonyms, filtered out {} invalid dates", count, skipped);
        } else {
            logger.info("Populated {} date synonyms", count);
        }
    }
    
    /**
     * Populates NER synonyms from the database.
     * Fetches all unique NER types (excluding DATE which is handled separately).
     */
    private void populateNerSynonyms() throws SQLException, IOException {
        String query = """
            SELECT DISTINCT ner
            FROM annotations
            WHERE ner IS NOT NULL
                AND ner != 'DATE'
                AND ner != 'O'
                AND LENGTH(ner) > 0
            ORDER BY ner
        """;

        int count = 0;
        int skipped = 0;
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String nerValue = rs.getString(1);
                if (nerValue != null && !nerValue.isEmpty()) {
                    try {
                        annotationSynonyms.getOrCreateId(nerValue, AnnotationType.NER);
                        count++;
                    } catch (IllegalArgumentException e) {
                        logger.debug("Filtered out invalid NER: {} ({})", nerValue, e.getMessage());
                        skipped++;
                    }
                }
            }
        }
        
        if (skipped > 0) {
            logger.info("Populated {} NER synonyms, filtered out {} invalid values", count, skipped);
        } else {
            logger.info("Populated {} NER synonyms", count);
        }
    }
    
    /**
     * Populates POS synonyms from the database.
     * Fetches all unique POS tags.
     */
    private void populatePosSynonyms() throws SQLException, IOException {
        String query = """
            SELECT DISTINCT pos
            FROM annotations
            WHERE pos IS NOT NULL
                AND pos NOT IN ('PUNCT', 'SYM')
                AND LENGTH(pos) > 0
            ORDER BY pos
        """;

        int count = 0;
        int skipped = 0;
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String posValue = rs.getString(1);
                if (posValue != null && !posValue.isEmpty()) {
                    try {
                        annotationSynonyms.getOrCreateId(posValue, AnnotationType.POS);
                        count++;
                    } catch (IllegalArgumentException e) {
                        logger.debug("Filtered out invalid POS: {} ({})", posValue, e.getMessage());
                        skipped++;
                    }
                }
            }
        }
        
        if (skipped > 0) {
            logger.info("Populated {} POS synonyms, filtered out {} invalid values", count, skipped);
        } else {
            logger.info("Populated {} POS synonyms", count);
        }
    }
    
    /**
     * Populates dependency relation synonyms from the database.
     * Fetches all unique dependency relation types.
     */
    private void populateDependencySynonyms() throws SQLException, IOException {
        String query = """
            SELECT DISTINCT relation
            FROM dependencies
            WHERE relation IS NOT NULL
                AND LENGTH(relation) > 0
            ORDER BY relation
        """;

        int count = 0;
        int skipped = 0;
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String relationValue = rs.getString(1);
                if (relationValue != null && !relationValue.isEmpty()) {
                    try {
                        annotationSynonyms.getOrCreateId(relationValue, AnnotationType.DEPENDENCY);
                        count++;
                    } catch (IllegalArgumentException e) {
                        logger.debug("Filtered out invalid dependency relation: {} ({})", relationValue, e.getMessage());
                        skipped++;
                    }
                }
            }
        }
        
        if (skipped > 0) {
            logger.info("Populated {} dependency relation synonyms, filtered out {} invalid values", count, skipped);
        } else {
            logger.info("Populated {} dependency relation synonyms", count);
        }
    }

    @Override
    protected List<StitchEntry> fetchBatch(int offset) throws SQLException {
        // Start by fetching a batch of documents to process
        List<DocumentInfo> documentInfos = fetchDocumentBatch(offset, this.batchSize);
        if (documentInfos.isEmpty()) {
            return List.of(); // No more documents to process
        }

        List<StitchEntry> entries = new ArrayList<>();
        
        // Process one document at a time to avoid memory issues
        for (DocumentInfo documentInfo : documentInfos) {
            processDocumentForStitchIndex(documentInfo.documentId, documentInfo.timestamp, entries);
        }
        
        return entries;
    }

    /**
     * Fetches a batch of document IDs to process
     */
    private List<DocumentInfo> fetchDocumentBatch(int offset, int batchSize) throws SQLException {
        List<DocumentInfo> documentInfos = new ArrayList<>();
        String sql = "SELECT document_id, timestamp FROM documents ORDER BY document_id LIMIT ? OFFSET ?";
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, batchSize);
            stmt.setInt(2, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int documentId = rs.getInt("document_id");
                    String timestampStr = rs.getString("timestamp");
                    if (timestampStr != null && timestampStr.length() >= 10) {
                        try {
                            LocalDate timestamp = LocalDate.parse(timestampStr.substring(0, 10));
                            documentInfos.add(new DocumentInfo(documentId, timestamp));
                        } catch (DateTimeParseException e) {
                            logger.warn("Skipping document_id {} due to invalid timestamp format: {}", documentId, timestampStr, e);
                        }
                    } else {
                        logger.warn("Skipping document_id {} due to missing or short timestamp: {}", documentId, timestampStr);
                    }
                }
            }
        }
        
        return documentInfos;
    }

    /**
     * Processes a single document, fetching its unigrams and annotations separately
     * and then joining them in memory to create StitchEntries
     */
    private void processDocumentForStitchIndex(int documentId, LocalDate documentTimestamp, List<StitchEntry> entries) throws SQLException {
        // Step 1: Get document timestamp
        if (documentTimestamp == null) {
            logger.warn("Skipping document_id {} due to null timestamp provided to processDocumentForStitchIndex", documentId);
            return; // Skip if we can't get timestamp
        }
        
        // Step 2: Fetch unigrams for this document
        Map<Integer, List<UnigramData>> unigramsBySentence = fetchUnigrams(documentId);
        if (unigramsBySentence.isEmpty()) {
            return; // No unigrams to process
        }
        
        // Step 3: Fetch and process each annotation type separately
        processDateAnnotations(documentId, documentTimestamp, unigramsBySentence, entries);
        processNerAnnotations(documentId, documentTimestamp, unigramsBySentence, entries);
        processPosAnnotations(documentId, documentTimestamp, unigramsBySentence, entries);
        processDependencyAnnotations(documentId, documentTimestamp, unigramsBySentence, entries);
    }

    /**
     * Fetches unigrams for a document and organizes them by sentence
     */
    private Map<Integer, List<UnigramData>> fetchUnigrams(int documentId) throws SQLException {
        Map<Integer, List<UnigramData>> unigramsBySentence = new HashMap<>();
        
        // Get all tokens from annotations table only, we don't want to get tokens from dependencies
        String sql = """
            SELECT sentence_id, begin_char, end_char, token, lemma
            FROM annotations
            WHERE 
                document_id = ?
                AND token IS NOT NULL
                AND LENGTH(token) > 0
        """;
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int sentenceId = rs.getInt("sentence_id");
                    String token = rs.getString("token");
                    if (token == null || token.isEmpty()) {
                        continue;
                    }
                    
                    // Use lemma if available, fall back to token
                    String lemma = rs.getString("lemma");
                    String normalizedToken = (lemma != null && !lemma.isEmpty()) ? lemma.toLowerCase() : token.toLowerCase();
                    
                    // Skip stopwords
                    if (isStopword(normalizedToken)) {
                            continue;
                        }
                        
                    UnigramData unigramData = new UnigramData(
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        normalizedToken
                    );
                    
                    unigramsBySentence.computeIfAbsent(sentenceId, k -> new ArrayList<>())
                                   .add(unigramData);
                }
            }
        }
        
        return unigramsBySentence;
    }

    /**
     * Process DATE annotations for a document
     */
    private void processDateAnnotations(
            int documentId, 
            LocalDate documentTimestamp,
            Map<Integer, List<UnigramData>> unigramsBySentence,
            List<StitchEntry> entries) throws SQLException {
        
        String sql = """
            SELECT sentence_id, begin_char, end_char, normalized_ner
            FROM annotations
            WHERE 
                document_id = ?
                AND ner = 'DATE' 
                AND normalized_ner IS NOT NULL
                AND normalized_ner LIKE '____-__-__'
        """;
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                // Track which unigram-date combinations we've already processed
                Map<Integer, Set<Integer>> processedCombinations = new HashMap<>();
                
                while (rs.next()) {
                    int sentenceId = rs.getInt("sentence_id");
                    String dateValue = rs.getString("normalized_ner");
                    
                    // Skip invalid dates (should be caught by SQL WHERE clause, but just in case)
                    if (dateValue == null || !dateValue.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        continue;
                    }
                        
                    // Try to parse the date to validate it
                    try {
                        LocalDate.parse(dateValue);
                    } catch (Exception e) {
                        // Skip invalid dates
                        logger.debug("Skipping invalid date: {}", dateValue);
                        continue;
                    }
                        
                    // Get the synonym ID for this date
                    int synonymId = annotationSynonyms.getOrCreateId(dateValue, AnnotationType.DATE);
                    
                    // Find unigrams in the same sentence and create stitch entries
                    List<UnigramData> unigrams = unigramsBySentence.getOrDefault(sentenceId, List.of());
                    for (UnigramData unigram : unigrams) {
                        // Skip if we've already processed this unigram-date combination
                        Set<Integer> processedDates = processedCombinations
                            .computeIfAbsent(sentenceId, k -> new HashSet<>());
                        
                        // Create a unique identifier for this unigram-date combination
                        int combinationId = Objects.hash(unigram.beginChar, unigram.endChar, synonymId);
                        if (!processedDates.add(combinationId)) {
                            continue;
                        }
                        
                        entries.add(new StitchEntry(
                            documentId,
                            sentenceId,
                            unigram.beginChar,
                            unigram.endChar,
                            documentTimestamp,
                            unigram.token,
                            AnnotationType.DATE,
                            synonymId
                        ));
                    }
                }
            }
        }
    }

    /**
     * Process NER annotations for a document
     */
    private void processNerAnnotations(
            int documentId, 
            LocalDate documentTimestamp,
            Map<Integer, List<UnigramData>> unigramsBySentence,
            List<StitchEntry> entries) throws SQLException {
        
        String sql = """
            SELECT sentence_id, begin_char, end_char, ner
            FROM annotations
            WHERE 
                document_id = ?
                AND ner IS NOT NULL
                AND ner != 'DATE'
                AND ner != 'O'
                AND LENGTH(ner) > 0
        """;
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int sentenceId = rs.getInt("sentence_id");
                    String nerValue = rs.getString("ner");
                    
                    // Skip if the NER value is invalid
                    if (nerValue == null || nerValue.isEmpty()) {
                        continue;
                    }
                    
                    // Get the synonym ID for this NER tag
                    int synonymId = annotationSynonyms.getOrCreateId(nerValue, AnnotationType.NER);
                    
                    // Find unigrams in the same sentence and create stitch entries
                    List<UnigramData> unigrams = unigramsBySentence.getOrDefault(sentenceId, List.of());
                    for (UnigramData unigram : unigrams) {
                        entries.add(new StitchEntry(
                            documentId,
                            sentenceId,
                            unigram.beginChar,
                            unigram.endChar,
                            documentTimestamp,
                            unigram.token,
                            AnnotationType.NER,
                            synonymId
                        ));
                    }
                }
            }
        }
    }

    /**
     * Process POS annotations for a document
     */
    private void processPosAnnotations(
            int documentId, 
            LocalDate documentTimestamp,
            Map<Integer, List<UnigramData>> unigramsBySentence,
            List<StitchEntry> entries) throws SQLException {
        
        String sql = """
            SELECT sentence_id, begin_char, end_char, pos
            FROM annotations
            WHERE 
                document_id = ?
                AND pos IS NOT NULL
                AND pos NOT IN ('PUNCT', 'SYM')
                AND LENGTH(pos) > 0
        """;
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int sentenceId = rs.getInt("sentence_id");
                    String posValue = rs.getString("pos");
                    
                    // Skip if the POS value is invalid
                    if (posValue == null || posValue.isEmpty()) {
                        continue;
                    }
                    
                    // Get the synonym ID for this POS tag
                    int synonymId = annotationSynonyms.getOrCreateId(posValue, AnnotationType.POS);
                    
                    // Find unigrams in the same sentence and create stitch entries
                    List<UnigramData> unigrams = unigramsBySentence.getOrDefault(sentenceId, List.of());
                    for (UnigramData unigram : unigrams) {
                        entries.add(new StitchEntry(
                            documentId,
                            sentenceId,
                            unigram.beginChar,
                            unigram.endChar,
                            documentTimestamp,
                            unigram.token,
                            AnnotationType.POS,
                            synonymId
                        ));
                    }
                }
            }
        }
    }

    /**
     * Process dependency annotations for a document
     */
    private void processDependencyAnnotations(
            int documentId, 
            LocalDate documentTimestamp,
            Map<Integer, List<UnigramData>> unigramsBySentence,
            List<StitchEntry> entries) throws SQLException {
        
        String sql = """
            SELECT sentence_id, begin_char, end_char, relation
            FROM dependencies
            WHERE 
                document_id = ?
                AND relation IS NOT NULL
                AND LENGTH(relation) > 0
        """;
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int sentenceId = rs.getInt("sentence_id");
                    String relationValue = rs.getString("relation");
                    
                    // Skip if the relation value is invalid
                    if (relationValue == null || relationValue.isEmpty()) {
                        continue;
                    }
                    
                    // Get the synonym ID for this dependency relation
                    int synonymId = annotationSynonyms.getOrCreateId(relationValue, AnnotationType.DEPENDENCY);
                    
                    // Find unigrams in the same sentence and create stitch entries
                    List<UnigramData> unigrams = unigramsBySentence.getOrDefault(sentenceId, List.of());
                    for (UnigramData unigram : unigrams) {
                        entries.add(new StitchEntry(
                            documentId,
                            sentenceId,
                            unigram.beginChar,
                            unigram.endChar,
                            documentTimestamp,
                            unigram.token,
                            AnnotationType.DEPENDENCY,
                            synonymId
                        ));
                    }
                }
            }
        }
    }

    @Override
    protected ListMultimap<String, PositionList> processBatch(List<StitchEntry> batch) {
        ListMultimap<String, PositionList> index = ArrayListMultimap.create();
        
        // Group entries by unigram value
        Map<String, List<StitchEntry>> groupedEntries = batch.stream()
            .collect(Collectors.groupingBy(StitchEntry::value));
        
        // Process each unigram group
        for (Map.Entry<String, List<StitchEntry>> entry : groupedEntries.entrySet()) {
            String unigram = entry.getKey();
            List<StitchEntry> entries = entry.getValue();
            
            // Create maps to group positions by annotation type
            Map<AnnotationType, PositionList> positionsByType = new HashMap<>();
            
            // Process entries
            for (StitchEntry e : entries) {
                // Get or create position list for this unigram and annotation type
                PositionList positions = positionsByType.computeIfAbsent(
                    e.type(), 
                    type -> new PositionList()
                );
                
                // Create position with annotation details
                StitchPosition position = new StitchPosition(
                    e.documentId(), 
                    e.sentenceId(),
                    e.beginChar(), 
                    e.endChar(),
                    e.timestamp(), 
                    e.type(),
                    e.synonymId()
                );
                
                positions.add(position);
            }
            
            // Add position lists to index with composite keys
            for (Map.Entry<AnnotationType, PositionList> typeEntry : positionsByType.entrySet()) {
                AnnotationType type = typeEntry.getKey();
                PositionList positions = typeEntry.getValue();
                
                // Create composite key with format "unigram\0type"
                String compositeKey = unigram + "\0" + type.name();
                index.put(compositeKey, positions);
            }
        }
        
        logger.debug("Processed batch with {} unique unigram-annotation combinations", index.keySet().size());
        return index;
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    protected String getIndexName() {
        return "stitch";
    }

    @Override
    public void close() throws IOException {
        logger.info("Closing StitchIndexGenerator and associated resources");
        Exception firstException = null;
        
        try {
            annotationSynonyms.close();
        } catch (Exception e) {
            firstException = e;
            logger.error("Error closing annotation synonyms", e);
        }
        
        try {
            super.close();
        } catch (Exception e) {
            if (firstException == null) {
                throw e;
            } else {
                logger.error("Error closing index generator after previous error", e);
                // Add the second exception as a suppressed exception to the first
                firstException.addSuppressed(e);
                throw new IOException("Multiple errors occurred during close", firstException);
            }
        }
        
        if (firstException != null) {
            throw new IOException("Error closing annotation synonyms", firstException);
        }
        
        logger.info("Successfully closed StitchIndexGenerator");
    }

    /**
     * Simple data class to hold unigram information
     */
    private static class UnigramData {
        final int beginChar;
        final int endChar;
        final String token;
        
        UnigramData(int beginChar, int endChar, String token) {
            this.beginChar = beginChar;
            this.endChar = endChar;
            this.token = token;
        }
    }
} 
