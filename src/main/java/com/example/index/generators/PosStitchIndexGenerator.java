package com.example.index.generators;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.index.AnnotationType;
import com.example.logging.ProgressTracker;

public class PosStitchIndexGenerator extends AbstractUnigramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(PosStitchIndexGenerator.class);
    public static final String MY_INDEX_NAME = "stitch-pos";
    private static final String POS_TAGS_TO_EXCLUDE_SQL = POSIndexGenerator.POS_TAGS_TO_EXCLUDE_SQL;

    public PosStitchIndexGenerator(String indexBaseDir, String stopwordsPath, Connection sqliteConn,
                                 ProgressTracker progressTracker, int batchSize, Path customSortTempPath) throws IOException {
        super(indexBaseDir, MY_INDEX_NAME,
              stopwordsPath, sqliteConn, progressTracker, batchSize, customSortTempPath,
              AnnotationType.POS
        );
    }

    @Override
    protected void populateSpecificAnnotationSynonyms(AnnotationType type) throws SQLException, IOException {
        if (type != AnnotationType.POS) {
            throw new IllegalArgumentException("PosStitchIndexGenerator can only populate POS synonyms.");
        }
        String query = String.format("""
            SELECT DISTINCT pos, token
            FROM annotations
            WHERE pos IS NOT NULL AND pos != ''
                AND token IS NOT NULL AND token != ''
                AND pos NOT IN %s
            ORDER BY pos, token
        """, POS_TAGS_TO_EXCLUDE_SQL);

        int count = 0;
        int skipped = 0;
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String posTag = rs.getString("pos");
                String token = rs.getString("token");
                if (posTag != null && !posTag.isEmpty() && token != null && !token.isEmpty()) {
                    String compositeSynonym = posTag.toUpperCase() + com.example.core.IndexAccessInterface.DELIMITER + token.toLowerCase();
                    try {
                        annotationSynonyms.getOrCreateId(compositeSynonym);
                        count++;
                    } catch (IllegalArgumentException e) {
                        logger.debug("Filtered out invalid POS composite synonym during population: {} ({})", compositeSynonym, e.getMessage());
                        skipped++;
                    }
                }
            }
        }
        if (skipped > 0) {
            logger.info("Populated {} POS composite synonyms, filtered out {} invalid values", count, skipped);
        } else {
            logger.info("Populated {} POS composite synonyms", count);
        }
        annotationSynonyms.validateSynonyms();
    }

    @Override
    protected List<AnnotationData> fetchAnnotationsForDocument(int documentId) throws SQLException {
        List<AnnotationData> annotations = new ArrayList<>();
        String sql = String.format("""
            SELECT sentence_id, begin_char, end_char, pos, token
            FROM annotations
            WHERE document_id = ?
                AND pos IS NOT NULL AND pos != ''
                AND token IS NOT NULL AND token != ''
                AND pos NOT IN %s
            ORDER BY sentence_id, begin_char
        """, POS_TAGS_TO_EXCLUDE_SQL);

        try (PreparedStatement stmt = sqliteConn.prepareStatement(sql)) {
            stmt.setInt(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String posTag = rs.getString("pos");
                    String token = rs.getString("token");

                    if (posTag != null && !posTag.isEmpty() && token != null && !token.isEmpty()
                            && !isPosTagExcluded(posTag)) {
                        String compositeValue = posTag.toUpperCase() + com.example.core.IndexAccessInterface.DELIMITER + token.toLowerCase();
                        annotations.add(new AnnotationData(
                                rs.getInt("sentence_id"),
                                rs.getInt("begin_char"),
                                rs.getInt("end_char"),
                                compositeValue
                        ));
                    }
                }
            }
        }
        return annotations;
    }

    private boolean isPosTagExcluded(String posTag) {
        return posTag.equals(",") || posTag.equals(".") || posTag.equals(":") || posTag.equals("``") ||
               posTag.equals("''") || posTag.equals("$") || posTag.equals("SYM") || posTag.equals("HYPH") ||
               posTag.equals("NFP") || posTag.equals("AFX") || posTag.equals("LS") || posTag.equals("X") ||
               posTag.equals("-LRB-") || posTag.equals("-RRB-") || posTag.equals("PUNCT");
    }

    @Override
    protected String getSpecificAnnotationTypeDBCondition() {
        return "pos IS NOT NULL AND pos != '' AND pos NOT IN " + POS_TAGS_TO_EXCLUDE_SQL;
    }

    @Override
    public String getIndexName() {
        return MY_INDEX_NAME;
    }

    @Override
    protected AnnotationType getManagedAnnotationType() {
        return AnnotationType.POS;
    }
}
