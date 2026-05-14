package com.example.annotation;

import com.example.util.TextCompression;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.CoreSentence;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;

/**
 * One-shot utility that converts document-level {@code begin_char} /
 * {@code end_char} into sentence-relative offsets in an existing database.
 *
 * <p>
 * Uses a minimal CoreNLP pipeline (tokenize + ssplit only — no POS, NER, or
 * parsing) to find the first token position of each sentence, then subtracts
 * that offset from every annotation and dependency row belonging to that
 * sentence. The result is identical to what a fresh run of
 * {@link Annotations} would produce after the sentence-relative fix.
 * </p>
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 *   java com.example.annotation.OffsetFixer /path/to/project.db [threads]
 * }</pre>
 */
public final class OffsetFixer {
    private static final Logger logger = LoggerFactory.getLogger(OffsetFixer.class);

    private OffsetFixer() {
        /* utility */ }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: OffsetFixer <project.db> [threads]");
            System.exit(1);
        }
        Path dbPath = Path.of(args[0]);
        int threads = args.length >= 2 ? Integer.parseInt(args[1])
                : Runtime.getRuntime().availableProcessors();

        fix(dbPath, threads);
    }

    /**
     * Rewrites every {@code begin_char} / {@code end_char} in the annotations
     * and dependencies tables to be sentence-relative.
     */
    public static void fix(Path projectDbPath, int threads) throws SQLException {
        String url = "jdbc:sqlite:" + projectDbPath;
        try (Connection conn = DriverManager.getConnection(url)) {
            try (Statement pragma = conn.createStatement()) {
                pragma.execute("PRAGMA journal_mode=WAL");
                pragma.execute("PRAGMA synchronous=NORMAL");
                pragma.execute("PRAGMA busy_timeout=60000");
            }

            // Build minimal pipeline: tokenize + ssplit only
            Properties props = new Properties();
            props.setProperty("annotators", "tokenize,ssplit");
            props.setProperty("threads", String.valueOf(threads));
            props.setProperty("tokenize.options",
                    "ptb3Escaping=false,untokenizable=noneKeep,tokenizeNLs=false");
            props.setProperty("ssplit.newlineIsSentenceBreak", "two");
            StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

            // Count total documents for progress
            long totalDocs;
            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM documents")) {
                totalDocs = rs.next() ? rs.getLong(1) : 0;
            }
            // Check if dependencies table exists (may be absent when
            // DEPENDENCY_ENABLED=false)
            boolean hasDeps;
            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name='dependencies'")) {
                hasDeps = rs.next();
            }
            if (!hasDeps) {
                logger.info("Dependencies table not found — skipping dependency offset updates");
            }

            logger.info("Fixing offsets for {} documents using {} threads...",
                    totalDocs, threads);

            // Prepared statements for updates
            String updateAnnSql = "UPDATE annotations SET begin_char = begin_char - ?, end_char = end_char - ? "
                    + "WHERE document_id = ? AND sentence_id = ?";
            String updateDepSql = hasDeps
                    ? "UPDATE dependencies SET begin_char = begin_char - ?, end_char = end_char - ? "
                            + "WHERE document_id = ? AND sentence_id = ?"
                    : null;

            // Fetch documents ordered by id
            String docSql = "SELECT document_id, text FROM documents ORDER BY document_id ASC";
            conn.setAutoCommit(false);

            long processed = 0;
            try (Statement docStmt = conn.createStatement();
                    ResultSet docRs = docStmt.executeQuery(docSql);
                    PreparedStatement annUpd = conn.prepareStatement(updateAnnSql);
                    PreparedStatement depUpd = hasDeps ? conn.prepareStatement(updateDepSql) : null) {

                while (docRs.next()) {
                    int docId = docRs.getInt("document_id");
                    byte[] compressed = docRs.getBytes("text");
                    if (compressed == null || compressed.length == 0) {
                        continue;
                    }
                    String text = TextCompression.decompress(compressed);

                    // Truncate to match Annotations.MAX_DOCUMENT_LENGTH
                    if (text.length() > 20000) {
                        text = text.substring(0, 20000);
                    }

                    CoreDocument document = new CoreDocument(text);
                    pipeline.annotate(document);

                    int sentenceId = 0;
                    for (CoreSentence sentence : document.sentences()) {
                        var tokens = sentence.tokens();
                        if (tokens.isEmpty()) {
                            sentenceId++;
                            continue;
                        }

                        int offset = tokens.get(0).beginPosition();

                        // Update annotations for this sentence
                        annUpd.setInt(1, offset);
                        annUpd.setInt(2, offset);
                        annUpd.setInt(3, docId);
                        annUpd.setInt(4, sentenceId);
                        annUpd.executeUpdate();

                        // Update dependencies (skip if table missing)
                        if (depUpd != null) {
                            depUpd.setInt(1, offset);
                            depUpd.setInt(2, offset);
                            depUpd.setInt(3, docId);
                            depUpd.setInt(4, sentenceId);
                            depUpd.executeUpdate();
                        }

                        sentenceId++;
                    }

                    processed++;
                    if (processed % 1000 == 0) {
                        conn.commit();
                        logger.info("Progress: {}/{} documents fixed", processed, totalDocs);
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }

            logger.info("Done. Fixed {} documents.", processed);
        }
    }
}
