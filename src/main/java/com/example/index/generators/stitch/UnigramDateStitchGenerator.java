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

public final class UnigramDateStitchGenerator extends AbstractNgramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(UnigramDateStitchGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_unigram_date";

    public UnigramDateStitchGenerator(
            IndexAccessInterface indexAccess,
            String stopwordsPath,
            Connection sqliteConn,
            ProgressTracker progress,
            int batchSize,
            Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(1,
              indexAccess,
              stopwordsPath,
              sqliteConn,
              progress,
              batchSize,
              customTempPath,
              sharedSynonymManager,
              AnnotationType.DATE);
        logger.info("UnigramDateStitchIndexGenerator initialized for index type: {}. Stopwords path: '{}'", MY_INDEX_NAME, stopwordsPath);
    }

    @Override
    protected boolean requiresSynonymIdForAnnotationValue() {
        return false; // Date strings (YYYYMMDD) are directly part of the key, not via synonym ID
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        // No-op for DATE stitch index when date string is directly in the key.
        // SynonymManager is not used for the date part.
        if (type != AnnotationType.DATE) {
            throw new IllegalArgumentException("DateStitchIndexGenerator's populateSpecificAnnotationSynonyms called with incorrect type: " + type);
        }
        logger.debug("populateSpecificAnnotationSynonyms is a no-op for DateStitchIndexGenerator as date strings are directly in the key.");
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> rawAnnotationsListFromDb = new ArrayList<>();
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
                    String nerTag = rs.getString("ner"); // Should always be "DATE"
                    String originalNormalizedDate = rs.getString("normalized_ner"); // Expected YYYY-MM-DD

                    if (!"DATE".equals(nerTag)) {
                         logger.warn("Fetched non-DATE NER tag '{}' for docId {} when expecting DATE. Normalized: '{}'. Skipping.", nerTag, documentId, originalNormalizedDate);
                         continue;
                    }

                    String dateKeyYYYYMMDD = NerDateIndexGenerator.normalizeDateToKeyFormat(originalNormalizedDate);
                    if (dateKeyYYYYMMDD == null) {
                        logger.trace("Skipping DATE annotation due to failed normalization of '{}' for doc ID {}.", originalNormalizedDate, documentId);
                        continue;
                    }

                    rawAnnotationsListFromDb.add(new AnnotationData(
                        rs.getInt("sentence_id"),
                        rs.getInt("begin_char"),
                        rs.getInt("end_char"),
                        dateKeyYYYYMMDD,         // annotationKeyComponent (e.g., "20231026")
                        dateKeyYYYYMMDD          // specificValueForSynonym (e.g., "20231026")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("SQLException in fetchAnnotationsForDocument for DATE stitch, doc ID {}: {}", documentId, e.getMessage(), e);
            throw e;
        }

        // Filter the raw list before any merging specific to Date annotations
        List<AnnotationData> filteredAnnotations = filterAnnotationsBySentenceCharacterSpan(
            rawAnnotationsListFromDb, documentId, "Unigram Date");

        if (filteredAnnotations.isEmpty()) {
            // Log based on whether original fetch was empty or filtering emptied it
            if (!rawAnnotationsListFromDb.isEmpty()) {
                logger.trace("No valid DATE annotations remaining after span filtering for document ID {} for {} index.", documentId, MY_INDEX_NAME);
            } else {
                logger.trace("No valid raw DATE annotations found for document ID {} for {} index.", documentId, MY_INDEX_NAME);
            }
            return new ArrayList<>(); // Return empty list
        }

        // The rest of the method uses 'filteredAnnotations' for merging
        List<AnnotationData> mergedAnnotations = new ArrayList<>();
        List<AnnotationData> currentProcessingGroup = new ArrayList<>();
        currentProcessingGroup.add(filteredAnnotations.get(0)); // Start with the first from the filtered list

        for (int i = 1; i < filteredAnnotations.size(); i++) {
            AnnotationData currentAnnotation = filteredAnnotations.get(i);
            AnnotationData lastAnnotationInGroup = currentProcessingGroup.get(currentProcessingGroup.size() - 1);

            // Check if currentAnnotation can extend the currentProcessingGroup
            if (currentAnnotation.annotationKeyComponent().equals(lastAnnotationInGroup.annotationKeyComponent()) &&
                currentAnnotation.sentenceId() == lastAnnotationInGroup.sentenceId() &&
                currentAnnotation.beginChar() <= lastAnnotationInGroup.endChar() + 2) { // Allow gap of 0 or 1 char
                currentProcessingGroup.add(currentAnnotation); // Extend the group
            } else {
                // Finalize the previous group
                AnnotationData firstTokenOfGroup = currentProcessingGroup.get(0);
                mergedAnnotations.add(new AnnotationData(
                    firstTokenOfGroup.sentenceId(),
                    firstTokenOfGroup.beginChar(),
                    lastAnnotationInGroup.endChar(), // endChar from the actual last token in the completed group
                    firstTokenOfGroup.annotationKeyComponent(),
                    firstTokenOfGroup.specificValueForSynonym()
                ));
                // Start a new group
                currentProcessingGroup.clear();
                currentProcessingGroup.add(currentAnnotation);
            }
        }

        // Finalize the last remaining group after the loop
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

        if (mergedAnnotations.isEmpty() && !filteredAnnotations.isEmpty()) { // Check against filteredAnnotations
             logger.trace("All filtered DATE annotations for document ID {} resulted in an empty merged list for {} index. Filtered count: {}", documentId, MY_INDEX_NAME, filteredAnnotations.size());
        } else {
            logger.trace("Fetched {} raw DATE annotations, filtered to {}, merged into {} annotations for document ID {} for {} index.",
                         rawAnnotationsListFromDb.size(), filteredAnnotations.size(), mergedAnnotations.size(), documentId, MY_INDEX_NAME);
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