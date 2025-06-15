package com.example.index.generators.stitch;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.AnnotationType;
import com.example.index.generators.NerDateIndexGenerator;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;

public final class TrigramDateStitchGenerator extends AbstractNgramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(TrigramDateStitchGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_trigram_date";

    public TrigramDateStitchGenerator(
            IndexAccessInterface indexAccess,
            String stopwordsPath,
            Connection sqliteConn,
            ProgressTracker progress,
            int batchSize,
            Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(3, // N-gram size (trigram)
              indexAccess,
              stopwordsPath,
              sqliteConn,
              progress,
              batchSize,
              customTempPath,
              sharedSynonymManager,
              AnnotationType.DATE);
        logger.info("TrigramDateStitchGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
    }

    @Override
    protected boolean requiresSynonymIdForAnnotationValue() {
        return false; // Date strings (YYYYMMDD) are directly part of the key
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        if (type != AnnotationType.DATE) {
            throw new IllegalArgumentException("TrigramDateStitchGenerator's populateSpecificAnnotationSynonyms called with incorrect type: " + type);
        }
        logger.debug("populateSpecificAnnotationSynonyms is a no-op for TrigramDateStitchGenerator.");
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> rawAnnotationsList = new ArrayList<>();
        String sql = """
            SELECT sentence_id, begin_char, end_char, ner, normalized_ner
            FROM annotations
            WHERE document_id = ?
                AND ner = 'DATE'
                AND normalized_ner IS NOT NULL AND normalized_ner != ''
            ORDER BY sentence_id, begin_char
        """;

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String originalNormalizedDate = rs.getString("normalized_ner");
                    String dateKeyYYYYMMDD = NerDateIndexGenerator.normalizeDateToKeyFormat(originalNormalizedDate);
                    if (dateKeyYYYYMMDD == null) {
                        logger.trace("Skipping DATE annotation due to failed normalization of '{}' for doc ID {}.", originalNormalizedDate, documentId);
                        continue;
                    }
                    rawAnnotationsList.add(new AnnotationData(
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        dateKeyYYYYMMDD, // annotationKeyComponent
                        dateKeyYYYYMMDD  // specificValueForSynonym (consistent, though not used for synonym lookup)
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("SQLException in fetchAnnotationsForDocument for Trigram DATE stitch, doc ID {}: {}", documentId, e.getMessage(), e);
            throw e;
        }

        if (rawAnnotationsList.isEmpty()) {
            return rawAnnotationsList;
        }

        List<AnnotationData> mergedAnnotations = new ArrayList<>();
        List<AnnotationData> currentProcessingGroup = new ArrayList<>();
        currentProcessingGroup.add(rawAnnotationsList.get(0));

        for (int i = 1; i < rawAnnotationsList.size(); i++) {
            AnnotationData currentAnnotation = rawAnnotationsList.get(i);
            AnnotationData lastAnnotationInGroup = currentProcessingGroup.get(currentProcessingGroup.size() - 1);

            if (currentAnnotation.annotationKeyComponent().equals(lastAnnotationInGroup.annotationKeyComponent()) &&
                currentAnnotation.sentenceId() == lastAnnotationInGroup.sentenceId() &&
                currentAnnotation.beginChar() <= lastAnnotationInGroup.endChar() + 2) { // Allow gap of 0 or 1 char
                currentProcessingGroup.add(currentAnnotation);
            } else {
                AnnotationData firstTokenOfGroup = currentProcessingGroup.get(0);
                mergedAnnotations.add(new AnnotationData(
                    firstTokenOfGroup.sentenceId(),
                    firstTokenOfGroup.beginChar(),
                    lastAnnotationInGroup.endChar(),
                    firstTokenOfGroup.annotationKeyComponent(),
                    firstTokenOfGroup.specificValueForSynonym()
                ));
                currentProcessingGroup.clear();
                currentProcessingGroup.add(currentAnnotation);
            }
        }

        if (!currentProcessingGroup.isEmpty()) {
            AnnotationData firstTokenOfGroup = currentProcessingGroup.get(0);
            AnnotationData lastAnnotationInGroup = currentProcessingGroup.get(currentProcessingGroup.size() - 1);
            mergedAnnotations.add(new AnnotationData(
                firstTokenOfGroup.sentenceId(),
                firstTokenOfGroup.beginChar(),
                lastAnnotationInGroup.endChar(),
                firstTokenOfGroup.annotationKeyComponent(),
                firstTokenOfGroup.specificValueForSynonym()
            ));
        }
        return mergedAnnotations;
    }

    @Override
    protected AnnotationType getManagedAnnotationType() {
        return AnnotationType.DATE;
    }

    @Override
    public String getIndexName() {
        return MY_INDEX_NAME;
    }
}