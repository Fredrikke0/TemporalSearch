package com.example.index;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.logging.ProgressTracker;
import com.example.core.Position;
import com.example.core.PositionList;
import com.example.core.IndexAccess;
import com.example.core.IndexAccessInterface;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a streaming index for named entities from annotated text.
 * Processes all NER types except DATE (which has its own dedicated index).
 * Creates composite keys of the form "entityType\0entityValue" for efficient retrieval.
 * Uses streaming processing and external sorting for efficient memory usage.
 */
public final class NerIndexGenerator extends IndexGenerator<AnnotationEntry> {
    private static final Logger logger = LoggerFactory.getLogger(NerIndexGenerator.class);
    
    public NerIndexGenerator(String indexBaseDir, String stopwordsPath,
            Connection sqliteConn, ProgressTracker progress, int batchSize) throws IOException {
        super(indexBaseDir, stopwordsPath, sqliteConn, progress, batchSize);
    }

    @Override
    protected List<AnnotationEntry> fetchBatch(AnnotationEntry lastProcessedEntry) throws SQLException {
        List<AnnotationEntry> batch = new ArrayList<>();
        String query;
        boolean isFirstBatch = (lastProcessedEntry == null);
        String existingWhereClause = "a.ner IS NOT NULL AND a.ner != '' AND a.ner != 'DATE' AND a.ner != 'O'";

        if (isFirstBatch) {
            query = "SELECT a.annotation_id, a.document_id, a.sentence_id, a.begin_char, a.end_char, " +
                    "a.token, a.ner, d.timestamp " +
                    "FROM annotations a " +
                    "JOIN documents d ON a.document_id = d.document_id " +
                    "WHERE " + existingWhereClause + " " +
                    "ORDER BY a.annotation_id LIMIT ?";
        } else {
            query = "SELECT a.annotation_id, a.document_id, a.sentence_id, a.begin_char, a.end_char, " +
                    "a.token, a.ner, d.timestamp " +
                    "FROM annotations a " +
                    "JOIN documents d ON a.document_id = d.document_id " +
                    "WHERE " + existingWhereClause + " AND a.annotation_id > ? " +
                    "ORDER BY a.annotation_id LIMIT ?";
        }
        
        try (PreparedStatement stmt = sqliteConn.prepareStatement(query)) {
            if (isFirstBatch) {
                stmt.setInt(1, this.batchSize);
            } else {
                stmt.setInt(1, lastProcessedEntry.getAnnotationId());
                stmt.setInt(2, this.batchSize);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String nerType = rs.getString("ner");
                    String entityText = rs.getString("token");
                    
                    if (nerType == null || entityText == null || nerType.isEmpty() || entityText.isEmpty()) {
                        continue;
                    }

                    batch.add(new AnnotationEntry(
                        rs.getInt("annotation_id"),
                        rs.getInt("document_id"),
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        entityText.toLowerCase(), // Entity text (lowercased token)
                        nerType                 // NER type
                    ));
                }
            }
        }
        
        logger.debug("Fetched batch of {} NER annotations", batch.size());
        return batch;
    }

    @Override
    protected ListMultimap<String, PositionList> processBatch(List<AnnotationEntry> batch) throws IOException {
        ListMultimap<String, PositionList> index = ArrayListMultimap.create();
        Map<String, PositionList> positionLists = new HashMap<>();
        
        // Variables to track continuous entity spans
        int currentDocId = -1;
        int currentSentenceId = -1;
        String currentEntityType = null;
        StringBuilder currentEntityText = new StringBuilder();
        int entityBeginChar = -1;
        int entityEndChar = -1;
        int lastAnnotationId = -1; // Track the last processed annotation ID
        
        // Process annotations in sequence (they are already ordered by document, sentence, and position)
        for (int i = 0; i < batch.size(); i++) {
            AnnotationEntry entry = batch.get(i);
            int docId = entry.getDocumentId();
            int sentenceId = entry.getSentenceId();
            String entityType = entry.getPos(); // NER type stored in pos field
            String token = entry.getToken(); // Entity text (token) stored in token field
            int beginChar = entry.getBeginChar();
            int endChar = entry.getEndChar();
            int annotationId = entry.getAnnotationId(); // Get the annotation ID
            
            // Check if this is a continuation of the current entity
            // Now also check if the annotation ID is consecutive (exactly one more than the last one)
            boolean isContinuation = docId == currentDocId && 
                                     sentenceId == currentSentenceId && 
                                     entityType.equals(currentEntityType) &&
                                     annotationId == lastAnnotationId + 1;
            
            if (isContinuation) {
                // Continue the current entity
                currentEntityText.append(" ").append(token); 
                entityEndChar = endChar;
            } else {
                // Finalize the previous entity if it exists
                if (currentEntityType != null) {
                    addEntityToIndex(positionLists, currentEntityType, currentEntityText.toString(),
                        currentDocId, currentSentenceId, entityBeginChar, entityEndChar);
                }
                
                // Start a new entity
                currentDocId = docId;
                currentSentenceId = sentenceId;
                currentEntityType = entityType;
                currentEntityText = new StringBuilder(token);
                entityBeginChar = beginChar;
                entityEndChar = endChar;
            }
            
            // Update the last annotation ID
            lastAnnotationId = annotationId;
            
            // If this is the last entry, finalize the current entity
            if (i == batch.size() - 1 && currentEntityType != null) {
                addEntityToIndex(positionLists, currentEntityType, currentEntityText.toString(),
                    currentDocId, currentSentenceId, entityBeginChar, entityEndChar);
            }
        }
        
        // Add all position lists to result
        for (Map.Entry<String, PositionList> entry : positionLists.entrySet()) {
            index.put(entry.getKey(), entry.getValue());
        }
        
        logger.debug("Processed batch with {} unique entity entries", positionLists.size());
        return index;
    }
    
    /**
     * Helper method to add an entity to the index
     */
    private void addEntityToIndex(Map<String, PositionList> positionLists, String entityType, 
                                 String entityText, int docId, int sentenceId, 
                                 int beginChar, int endChar) {
        // Create composite key with format "ENTITY_TYPE\0entityText"
        // Ensure entityType is uppercase to match NerExecutor expectations
        String compositeKey = entityType.toUpperCase() + IndexAccessInterface.DELIMITER + entityText;
        
        Position position = new Position(
            docId, 
            sentenceId, 
            beginChar, 
            endChar 
        );

        // Get or create position list for this entity
        PositionList posList = positionLists.computeIfAbsent(compositeKey, k -> new PositionList());
        posList.add(position);
    }

    @Override
    protected String getTableName() {
        return "annotations";
    }

    @Override
    protected String getIndexName() {
        return "ner";
    }
} 