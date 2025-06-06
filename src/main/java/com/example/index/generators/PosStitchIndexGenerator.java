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

import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessInterface;
import com.example.index.AnnotationType;
import com.example.index.util.SynonymManager;
import com.example.logging.ProgressTracker;

public class PosStitchIndexGenerator extends AbstractUnigramStitchGenerator {
    private static final Logger logger = LoggerFactory.getLogger(PosStitchIndexGenerator.class);
    public static final String MY_INDEX_NAME = "stitch_unigram_pos";
    private static final String POS_TAGS_TO_EXCLUDE_SQL = POSIndexGenerator.POS_TAGS_TO_EXCLUDE_SQL;

    public PosStitchIndexGenerator(
            IndexAccessInterface indexAccess,
            String stopwordsPath,
            Connection sqliteConn,
            ProgressTracker progress,
            int batchSize,
            Path customTempPath,
            SynonymManager sharedSynonymManager) throws IOException {
        super(indexAccess, stopwordsPath, sqliteConn, progress, batchSize, customTempPath,
              AnnotationType.POS, sharedSynonymManager
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
                        synonymManager.getId(compositeSynonym);
                        count++;
                    } catch (IllegalArgumentException e) {
                        logger.debug("Filtered out invalid POS composite synonym during population: {} ({})", compositeSynonym, e.getMessage());
                        skipped++;
                    } catch (RocksDBException e) {
                        logger.error("RocksDB error getting ID for POS composite synonym '{}' during population: {}", compositeSynonym, e.getMessage(), e);
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

                    if (posTag != null && !posTag.isEmpty() && token != null && !token.isEmpty()) {
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
