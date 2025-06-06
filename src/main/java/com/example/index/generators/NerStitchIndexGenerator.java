package com.example.index.generators;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.AnnotationType;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;

public class NerStitchIndexGenerator extends AbstractUnigramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(NerStitchIndexGenerator.class);
    // NER types to exclude, as they are handled by dedicated generators or are too broad/noisy
    private static final List<String> EXCLUDED_NER_TYPES = List.of("DATE", "NUMBER", "ORDINAL", "DURATION", "SET", "MONEY", "PERCENT");
    public static final String MY_INDEX_NAME = "stitch_unigram_ner";

    public NerStitchIndexGenerator(
            IndexAccessInterface indexAccess,
            String stopwordsPath,
            Connection sqliteConn,
            ProgressTracker progress,
            int batchSize,
            Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath,
              AnnotationType.NER, sharedSynonymManager
        );
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        if (type != AnnotationType.NER) {
            throw new IllegalArgumentException("NerStitchIndexGenerator can only populate NER synonyms.");
        }

        StringBuilder queryBuilder = new StringBuilder("""
            SELECT DISTINCT normalized_ner
            FROM annotations
            WHERE ner IS NOT NULL AND normalized_ner IS NOT NULL
        """);
        if (!EXCLUDED_NER_TYPES.isEmpty()) {
            queryBuilder.append(" AND ner NOT IN (");
            for (int i = 0; i < EXCLUDED_NER_TYPES.size(); i++) {
                queryBuilder.append("?");
                if (i < EXCLUDED_NER_TYPES.size() - 1) {
                    queryBuilder.append(", ");
                }
            }
            queryBuilder.append(")");
        }
        queryBuilder.append(" ORDER BY normalized_ner");

        String query = queryBuilder.toString();
        int count = 0;
        int skipped = 0;

        try (PreparedStatement stmt = sqliteConn.prepareStatement(query)) {
            if (!EXCLUDED_NER_TYPES.isEmpty()) {
                for (int i = 0; i < EXCLUDED_NER_TYPES.size(); i++) {
                    stmt.setString(i + 1, EXCLUDED_NER_TYPES.get(i));
                }
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String nerValue = rs.getString(1);
                    if (nerValue != null && !nerValue.trim().isEmpty()) {
                        try {
                            synonymManager.getId(nerValue);
                            count++;
                        } catch (IllegalArgumentException e) {
                            logger.warn("Skipping invalid NER annotation during synonym population: {}", e.getMessage());
                            skipped++;
                        } catch (RocksDBException e) {
                            logger.error("RocksDB error getting ID for NER value '{}' during synonym population: {}", nerValue, e.getMessage(), e);
                            skipped++;
                        }
                    } else {
                        skipped++;
                    }
                }
            }
        }
        if (skipped > 0) {
            logger.info("Populated {} NER synonyms (excluding {} types), filtered out {} invalid/empty values", count, EXCLUDED_NER_TYPES.size(), skipped);
        } else {
            logger.info("Populated {} NER synonyms (excluding {} types)", count, EXCLUDED_NER_TYPES.size());
        }
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> mergedAnnotations = new ArrayList<>();
        List<AnnotationData> rawAnnotations = new ArrayList<>();

        StringBuilder sqlBuilder = new StringBuilder("""
            SELECT sentence_id, begin_char, end_char, normalized_ner, ner
            FROM annotations
            WHERE document_id = ?
                AND ner IS NOT NULL
                AND normalized_ner IS NOT NULL
        """);

        if (!EXCLUDED_NER_TYPES.isEmpty()) {
            sqlBuilder.append(" AND ner NOT IN (");
            for (int i = 0; i < EXCLUDED_NER_TYPES.size(); i++) {
                sqlBuilder.append("?"); // Use placeholders for PreparedStatement
                if (i < EXCLUDED_NER_TYPES.size() - 1) {
                    sqlBuilder.append(", ");
                }
            }
            sqlBuilder.append(")");
        }
        sqlBuilder.append(" ORDER BY sentence_id, begin_char");

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sqlBuilder.toString())) {
            int paramIndex = 1;
            stmt.setInt(paramIndex++, documentId);
            if (!EXCLUDED_NER_TYPES.isEmpty()) {
                for (String excludedType : EXCLUDED_NER_TYPES) {
                    stmt.setString(paramIndex++, excludedType);
                }
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // We are fetching normalized_ner directly as the value for AnnotationData
                    String nerValue = rs.getString("normalized_ner");
                    // String nerTag = rs.getString("ner"); // nerTag is used for filtering via SQL

                    if (nerValue != null && !nerValue.trim().isEmpty()) {
                         rawAnnotations.add(new AnnotationData(
                                rs.getInt("sentence_id"),
                                rs.getInt("begin_char"),
                                rs.getInt("end_char"),
                                nerValue // This is already the normalized entity value
                        ));
                    }
                }
            }
        }

        if (rawAnnotations.isEmpty()) {
            return Collections.emptyList();
        }

        // Merging logic for consecutive annotations of the SAME normalized_ner value
        List<AnnotationData> currentMergeCandidates = new ArrayList<>();
        for (AnnotationData currentAnnotation : rawAnnotations) {
            if (currentMergeCandidates.isEmpty()) {
                currentMergeCandidates.add(currentAnnotation);
            } else {
                AnnotationData prevAnnotation = currentMergeCandidates.get(currentMergeCandidates.size() - 1);

                if (!currentAnnotation.normalizedValue().equals(prevAnnotation.normalizedValue()) ||
                    currentAnnotation.sentenceId() != prevAnnotation.sentenceId() ||
                    currentAnnotation.beginChar() > prevAnnotation.endChar() + 2) { // Allow small gap for spaces etc.

                    if (!currentMergeCandidates.isEmpty()) {
                        AnnotationData firstToken = currentMergeCandidates.get(0);
                        AnnotationData lastToken = currentMergeCandidates.get(currentMergeCandidates.size() - 1);
                        mergedAnnotations.add(new AnnotationData(
                                firstToken.sentenceId(),
                                firstToken.beginChar(),
                                lastToken.endChar(),
                                firstToken.normalizedValue()));
                    }
                    currentMergeCandidates.clear();
                    currentMergeCandidates.add(currentAnnotation);
                } else {
                    currentMergeCandidates.add(currentAnnotation);
                }
            }
        }

        if (!currentMergeCandidates.isEmpty()) {
            AnnotationData firstToken = currentMergeCandidates.get(0);
            AnnotationData lastToken = currentMergeCandidates.get(currentMergeCandidates.size() - 1);
            mergedAnnotations.add(new AnnotationData(
                    firstToken.sentenceId(),
                    firstToken.beginChar(),
                    lastToken.endChar(),
                    firstToken.normalizedValue()));
        }

        return mergedAnnotations;
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
        StringBuilder conditionBuilder = new StringBuilder("ner IS NOT NULL AND normalized_ner IS NOT NULL");
        if (!EXCLUDED_NER_TYPES.isEmpty()) {
            conditionBuilder.append(" AND ner NOT IN (");
            for (int i = 0; i < EXCLUDED_NER_TYPES.size(); i++) {
                conditionBuilder.append("'").append(EXCLUDED_NER_TYPES.get(i).replace("'", "''")).append("'");
                if (i < EXCLUDED_NER_TYPES.size() - 1) {
                    conditionBuilder.append(", ");
                }
            }
            conditionBuilder.append(")");
        }
        return conditionBuilder.toString();
    }
}