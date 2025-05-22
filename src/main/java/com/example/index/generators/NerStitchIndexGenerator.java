package com.example.index.generators;

import com.example.index.AnnotationType;
import com.example.logging.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class NerStitchIndexGenerator extends AbstractUnigramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(NerStitchIndexGenerator.class);
    private static final String MY_INDEX_NAME = "stitch-ner";
    private static final String NER_TAGS_TO_EXCLUDE_SQL = NerIndexGenerator.NER_TAGS_TO_EXCLUDE_SQL;

    public NerStitchIndexGenerator(String indexBaseDir, String stopwordsPath, Connection sqliteConn,
                                 ProgressTracker progressTracker, int batchSize, Path customSortTempPath) throws IOException {
        this(indexBaseDir, stopwordsPath, sqliteConn, progressTracker, batchSize, customSortTempPath, true);
    }

    public NerStitchIndexGenerator(String indexBaseDir, String stopwordsPath, Connection sqliteConn,
                                 ProgressTracker progressTracker, int batchSize, Path customSortTempPath, boolean initializeDB) throws IOException {
        super(indexBaseDir, MY_INDEX_NAME,
              stopwordsPath, sqliteConn, progressTracker, batchSize, customSortTempPath,
              AnnotationType.NER, 
              initializeDB
        );
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        if (type != AnnotationType.NER) {
            throw new IllegalArgumentException("NerStitchIndexGenerator can only populate NER synonyms.");
        }
        String query = String.format("""
            SELECT annotation_id, document_id, sentence_id, begin_char, end_char, token, ner
            FROM annotations
            WHERE ner IS NOT NULL
                AND ner NOT IN %s
            ORDER BY document_id, sentence_id, annotation_id
        """, NER_TAGS_TO_EXCLUDE_SQL);

        int count = 0;
        int skipped = 0;
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            int prevDocId = -1, prevSentId = -1, prevEndChar = -1;
            String currentEntityType = null;
            List<String> currentEntityRawTokens = new ArrayList<>();
            int currentEntityDocId = -1, currentEntitySentId = -1, currentEntityBeginChar = -1;

            while (rs.next()) {
                int docId = rs.getInt("document_id");
                int sentId = rs.getInt("sentence_id");
                int beginChar = rs.getInt("begin_char");
                int endChar = rs.getInt("end_char");
                String token = rs.getString("token");
                String nerTag = rs.getString("ner");

                boolean entityBreak = false;
                if (currentEntityType != null) {
                    if (nerTag == null || "O".equals(nerTag) || "DATE".equals(nerTag) ||
                        !nerTag.equals(currentEntityType) ||
                        docId != currentEntityDocId ||
                        sentId != currentEntitySentId ||
                        (prevEndChar != -1 && beginChar > prevEndChar + 1)) {
                        entityBreak = true;
                    }
                }

                if (entityBreak) {
                    if (!currentEntityRawTokens.isEmpty()) {
                        String entityText = String.join(" ", currentEntityRawTokens).toLowerCase();
                        String composite = currentEntityType.toUpperCase() + com.example.core.IndexAccessInterface.DELIMITER + entityText;
                        try {
                            annotationSynonyms.getOrCreateId(composite);
                            count++;
                        } catch (IllegalArgumentException e) {
                            logger.debug("Filtered out invalid NER entity value during synonym population: {} ({})", composite, e.getMessage());
                            skipped++;
                        }
                    }
                    currentEntityRawTokens.clear();
                    currentEntityType = null;
                }

                if (nerTag != null && !nerTag.isEmpty() && !"O".equals(nerTag) && !"DATE".equals(nerTag)) {
                    if (currentEntityType == null) {
                        currentEntityType = nerTag;
                        currentEntityDocId = docId;
                        currentEntitySentId = sentId;
                        currentEntityBeginChar = beginChar;
                    }
                    currentEntityRawTokens.add(token);
                }
                prevDocId = docId;
                prevSentId = sentId;
                prevEndChar = endChar;
            }
            // Handle last entity
            if (currentEntityType != null && !currentEntityRawTokens.isEmpty()) {
                String entityText = String.join(" ", currentEntityRawTokens).toLowerCase();
                String composite = currentEntityType.toUpperCase() + com.example.core.IndexAccessInterface.DELIMITER + entityText;
                try {
                    annotationSynonyms.getOrCreateId(composite);
                    count++;
                } catch (IllegalArgumentException e) {
                    logger.debug("Filtered out invalid NER entity value during synonym population: {} ({})", composite, e.getMessage());
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            logger.info("Populated {} NER synonyms from composite TYPE/value, filtered out {} invalid values", count, skipped);
        } else {
            logger.info("Populated {} NER synonyms from composite TYPE/value", count);
        }
        annotationSynonyms.validateSynonyms();
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> annotations = new ArrayList<>();
        String sql = String.format("""
            SELECT sentence_id, begin_char, end_char, token, ner
            FROM annotations
            WHERE document_id = ?
                AND ner IS NOT NULL
                AND ner NOT IN %s
            ORDER BY sentence_id, begin_char
        """, NER_TAGS_TO_EXCLUDE_SQL);

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                int prevSentId = -1, prevEndChar = -1;
                String currentEntityType = null;
                List<String> currentEntityRawTokens = new ArrayList<>();
                int currentEntitySentId = -1, currentEntityBeginChar = -1;
                int currentEntityEndChar = -1;

                while (rs.next()) {
                    int sentId = rs.getInt("sentence_id");
                    int beginChar = rs.getInt("begin_char");
                    int endChar = rs.getInt("end_char");
                    String token = rs.getString("token");
                    String nerTag = rs.getString("ner");

                    boolean entityBreak = false;
                    if (currentEntityType != null) {
                        if (nerTag == null || "O".equals(nerTag) || "DATE".equals(nerTag) ||
                            !nerTag.equals(currentEntityType) ||
                            sentId != currentEntitySentId ||
                            (prevEndChar != -1 && beginChar > prevEndChar + 1)) {
                            entityBreak = true;
                        }
                    }

                    if (entityBreak) {
                        if (!currentEntityRawTokens.isEmpty()) {
                            String entityValue = String.join(" ", currentEntityRawTokens).toLowerCase();
                            annotations.add(new AnnotationData(
                                    currentEntitySentId,
                                    currentEntityBeginChar,
                                    currentEntityEndChar,
                                    entityValue
                            ));
                        }
                        currentEntityRawTokens.clear();
                        currentEntityType = null;
                    }

                    if (nerTag != null && !nerTag.isEmpty() && !"O".equals(nerTag) && !"DATE".equals(nerTag)) {
                        if (currentEntityType == null) {
                            currentEntityType = nerTag;
                            currentEntitySentId = sentId;
                            currentEntityBeginChar = beginChar;
                        }
                        currentEntityRawTokens.add(token);
                        currentEntityEndChar = endChar;
                    }
                    prevSentId = sentId;
                    prevEndChar = endChar;
                }
                // Handle last entity
                if (currentEntityType != null && !currentEntityRawTokens.isEmpty()) {
                    String entityValue = String.join(" ", currentEntityRawTokens).toLowerCase();
                    annotations.add(new AnnotationData(
                            currentEntitySentId,
                            currentEntityBeginChar,
                            currentEntityEndChar,
                            entityValue
                    ));
                }
            }
        }
        return annotations;
    }

    @Override
    protected AnnotationType getManagedAnnotationType() {
        return AnnotationType.NER;
    }

    @Override
    public String getIndexName() {
        return MY_INDEX_NAME;
    }

    @Override
    protected String getSpecificAnnotationTypeDBCondition() {
        return "ner IS NOT NULL AND ner NOT IN " + NER_TAGS_TO_EXCLUDE_SQL;
    }
} 