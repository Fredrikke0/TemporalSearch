package com.example.annotation;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.CoreSentence;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;

public class Annotations {
    private static final Logger logger = LoggerFactory.getLogger(Annotations.class);
    private static final int MAX_DOCUMENT_LENGTH = 20000; // Max length to process; longer documents will be truncated.
    private static final int DOCUMENT_PROCESSING_TIMEOUT_MINUTES = 5; // Timeout for a single CoreNLP task

    private static final Set<String> POS_SKIP = Set.of(
        ",", ".", ":", "``", "''", "$", "SYM", "HYPH", "NFP",
        "AFX", "LS", "X", "-LRB-", "-RRB-", "FW", "ADD"
    );

    /** Returns true if this token should be persisted. */
    private static boolean keepToken(CoreLabel tok) {
        String text = tok.word();
        String pos  = tok.tag();

        if (text == null || text.isBlank()) return false;
        if (pos  != null && POS_SKIP.contains(pos)) return false;
        // Also remove tokens that are ONLY punctuation and got past the POS check
        if (!text.chars().anyMatch(Character::isLetterOrDigit)) {
            return false;
        }
        // Remove tokens starting with apostrophe
        if (text.startsWith("'")) return false;
        return true;
    }

    private static StanfordCoreNLP createCoreNLPPipeline(int threads) {
        CoreNLPConfig config = new CoreNLPConfig(threads);
        return config.createPipeline();
    }

    /**
     * Returns annotation status for the given project DB path.
     * Checks for annotation columns/tables, finds max_annotated_id and max_doc_id.
     * Returns a status object: { needsProcessing: boolean, startDocumentId: int }
     */
    public static class AnnotationStatus {
        public final boolean needsProcessing;
        public final int startDocumentId;
        public AnnotationStatus(boolean needsProcessing, int startDocumentId) {
            this.needsProcessing = needsProcessing;
            this.startDocumentId = startDocumentId;
        }
    }

    public static AnnotationStatus getAnnotationStatus(Path projectDbPath) throws SQLException {
        String url = "jdbc:sqlite:" + projectDbPath;
        try (Connection conn = DriverManager.getConnection(url)) {
            int minDocIdOverall = 1;
            long totalDocsInDb = 0;

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*), MIN(document_id) FROM documents")) {
                if (rs.next()) {
                    totalDocsInDb = rs.getLong(1);
                    if (totalDocsInDb > 0) {
                        minDocIdOverall = rs.getInt(2);
                        if (rs.wasNull() || minDocIdOverall == 0) minDocIdOverall = 1;
                    }
                }
            } catch (SQLException e) {
                logger.warn("Could not query 'documents' table (it might not exist). Assuming no documents to process.", e);
                return new AnnotationStatus(false, 1);
            }

            if (totalDocsInDb == 0) {
                logger.info("No documents found in the 'documents' table. Nothing to annotate.");
                return new AnnotationStatus(false, 1);
            }

            boolean annotationsTableExists = false;
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='annotations'")) {
                if (rs.next()) annotationsTableExists = true;
            }

            long maxDocIdOverall = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT MAX(document_id) FROM documents")) {
                if (rs.next()) {
                    maxDocIdOverall = rs.getLong(1);
                }
            }

            // If there is no annotations table or it has no rows, start from the minimum doc id
            long maxAnnotatedDocId = 0;
            if (annotationsTableExists) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(document_id),0) FROM annotations")) {
                    if (rs.next()) {
                        maxAnnotatedDocId = rs.getLong(1);
                    }
                }
            }

            int nextDocIdToProcess = (int) (maxAnnotatedDocId + 1);
            if (nextDocIdToProcess <= 0) {
                nextDocIdToProcess = minDocIdOverall; // Fallback safety
            }

            if (nextDocIdToProcess > maxDocIdOverall) {
                logger.info("All documents up to {} appear to be annotated. No new documents to process.", maxDocIdOverall);
                return new AnnotationStatus(false, nextDocIdToProcess);
            } else {
                logger.info("Next document to annotate: {} (max annotated so far: {}, max in documents table: {}).", nextDocIdToProcess, maxAnnotatedDocId, maxDocIdOverall);
                return new AnnotationStatus(true, nextDocIdToProcess);
            }
        }
    }

    /**
     * Identifies unannotated documents and re-numbers their document_ids to be higher than
     * the current maximum document_id in the documents table.
     */
    public static void fixDocumentIds(Path projectDbPath) throws SQLException {
        String url = "jdbc:sqlite:" + projectDbPath;
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(url);
            conn.setAutoCommit(false);

            List<Integer> unannotatedDocIds = new ArrayList<>();
            String findUnannotatedSql = """
                SELECT d.document_id
                FROM documents d
                LEFT JOIN (SELECT DISTINCT document_id FROM annotations) a
                ON d.document_id = a.document_id
                WHERE a.document_id IS NULL
                ORDER BY d.document_id ASC
                """;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(findUnannotatedSql)) {
                while (rs.next()) {
                    unannotatedDocIds.add(rs.getInt(1));
                }
            }

            if (unannotatedDocIds.isEmpty()) {
                logger.info("fixDocumentIds: No unannotated documents found. No IDs to fix.");
                conn.commit();
                return;
            }
            logger.info("fixDocumentIds: Found {} unannotated documents to re-number.", unannotatedDocIds.size());

            long currentMaxOverallDocId = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT MAX(document_id) FROM documents")) {
                if (rs.next()) {
                    currentMaxOverallDocId = rs.getLong(1);
                }
            }
            logger.debug("fixDocumentIds: Current MAX(document_id) in documents table is {}.", currentMaxOverallDocId);

            long nextNewDocId = currentMaxOverallDocId + 1;
            String updateSql = "UPDATE documents SET document_id = ? WHERE document_id = ?";
            int renumberedCount = 0;
            try (PreparedStatement pstmtUpdate = conn.prepareStatement(updateSql)) {
                for (int oldDocId : unannotatedDocIds) {
                    pstmtUpdate.setLong(1, nextNewDocId);
                    pstmtUpdate.setInt(2, oldDocId);
                    int affectedRows = pstmtUpdate.executeUpdate();
                    if (affectedRows > 0) {
                        logger.debug("fixDocumentIds: Re-numbered document_id {} to {}.", oldDocId, nextNewDocId);
                        renumberedCount++;
                        nextNewDocId++;
                    } else {
                        logger.warn("fixDocumentIds: Failed to update document_id {} (expected to be renumbered to {}).", oldDocId, nextNewDocId -1 );
                    }
                }
            }

            conn.commit();
            logger.info("fixDocumentIds: Successfully re-numbered {} documents. Next available document_id for new documents would be {}.", renumberedCount, nextNewDocId);

        } catch (SQLException e) {
            logger.error("fixDocumentIds: SQLException during ID fixing. This might be due to missing 'documents' or 'annotations' tables, or other DB issues. Attempting to rollback.", e);
            if (conn != null) {
                try {
                    if (!conn.getAutoCommit()) {
                        conn.rollback();
                        logger.info("fixDocumentIds: Rollback successful.");
                    }
                } catch (SQLException e2) {
                    logger.error("fixDocumentIds: Error during explicit rollback.", e2);
                }
            }
            throw e;
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e3) {
                    logger.error("fixDocumentIds: Error closing connection.", e3);
                }
            }
        }
    }

    /**
     * Manages the entire annotation stage. It checks status, handles ID fixing,
     * determines the start document, and executes the annotation process.
     * This is the primary entry point for the annotation pipeline.
     */
    public static void runAnnotationStage(Path projectDbPath, int threads, int batchSize, Integer limit, boolean force, boolean fixIds, Integer cliStartDocId) throws Exception {
        if (fixIds) {
            logger.info("Fix IDs flag is true. Attempting to re-number unannotated document IDs.");
            try {
                fixDocumentIds(projectDbPath);
                logger.info("Document ID fixing process completed.");
            } catch (SQLException e) {
                logger.error("Error during document ID fixing. Annotation process cannot proceed reliably.", e);
                throw new Exception("Failed to fix document IDs, cannot annotate.", e);
            }
        }

        AnnotationStatus currentStatus = getAnnotationStatus(projectDbPath);
        int startId;
        boolean needsProcessing = currentStatus.needsProcessing;

        if (cliStartDocId != null) {
            startId = cliStartDocId;
            logger.trace("Using command-line specified --start-doc-id: {}", startId);
            if (force) {
                logger.trace("--force is also active. Annotation will start from {} and overwrite existing annotations from this ID onwards.", startId);
            }
        } else if (force) {
            startId = 1;
            logger.trace("--force active, starting annotation from document_id 1.");
        } else {
            startId = currentStatus.startDocumentId;
            logger.trace("Resuming annotation based on status, starting from document_id: {}", startId);
        }

        if (force || needsProcessing) {
             logger.info("Starting annotation process (startId={}, force={}, limit={}, threads={}, batchSize={})",
                            startId, force, limit == null ? "none" : limit, threads, batchSize);
             performAnnotation(projectDbPath, startId, threads, batchSize, limit, force);
        } else {
             logger.info("Annotation already complete according to status check. Skipping. Use --force to re-annotate.");
        }
    }

    /**
     * Runs the core annotation loop from a specific document ID.
     * This method assumes that the decision to run has already been made.
     */
    private static void performAnnotation(Path projectDbPath, int startDocumentId, int threads, int batchSize, Integer limit, boolean force) throws Exception {
        String url = "jdbc:sqlite:" + projectDbPath;
        try (Connection conn = DriverManager.getConnection(url)) {
            try (Statement pragma = conn.createStatement()) {
                pragma.execute("PRAGMA journal_mode=WAL");
                pragma.execute("PRAGMA synchronous=NORMAL");
                pragma.execute("PRAGMA busy_timeout=60000");
                pragma.execute("PRAGMA temp_store=MEMORY");
                pragma.execute("PRAGMA cache_size=-200000"); // ~200MB
            }
            conn.setAutoCommit(false);
            createTables(conn, false);

            if (force) {
                logger.info("Force flag is true. Deleting existing annotations and dependencies for document_id >= {}", startDocumentId);
                try (PreparedStatement delAnn = conn.prepareStatement("DELETE FROM annotations WHERE document_id >= ?");
                     PreparedStatement delDep = conn.prepareStatement("DELETE FROM dependencies WHERE document_id >= ?")) {

                    delAnn.setInt(1, startDocumentId);
                    delAnn.executeUpdate();

                    delDep.setInt(1, startDocumentId);
                    delDep.executeUpdate();

                    conn.commit();
                } catch (SQLException e) {
                    logger.error("Error during forced delete for document_id >= " + startDocumentId, e);
                    throw e;
                }
            } else {
                logger.info("Resuming or starting from specific ID (force=false). Performing cleanup for document_id: {}", startDocumentId);
                try (PreparedStatement delAnn = conn.prepareStatement("DELETE FROM annotations WHERE document_id = ?");
                     PreparedStatement delDep = conn.prepareStatement("DELETE FROM dependencies WHERE document_id = ?")) {

                    delAnn.setInt(1, startDocumentId);
                    delAnn.executeUpdate();

                    delDep.setInt(1, startDocumentId);
                    delDep.executeUpdate();

                    conn.commit();
                } catch (SQLException e) {
                    logger.error("Error during pre-emptive delete for document_id=" + startDocumentId, e);
                    throw e;
                }
            }

            // Keep transaction control for explicit batching and commits
            conn.setAutoCommit(false);

            long estimatedDocsInScope = 0;
            String maxIdSql = "SELECT COALESCE(MAX(document_id), 0) FROM documents WHERE document_id >= ?";
            try (PreparedStatement maxIdStmt = conn.prepareStatement(maxIdSql)) {
                maxIdStmt.setInt(1, startDocumentId);
                try (ResultSet maxIdRs = maxIdStmt.executeQuery()) {
                    if (maxIdRs.next()) {
                        long queriedMaxId = maxIdRs.getLong(1);
                        if (queriedMaxId > 0) {
                            estimatedDocsInScope = (queriedMaxId >= startDocumentId) ? (queriedMaxId - startDocumentId + 1) : 0;
                        }
                    }
                }
            }
            if (estimatedDocsInScope == 0 && startDocumentId == 1) {
                try (PreparedStatement totalCheckStmt = conn.prepareStatement("SELECT COALESCE(MAX(document_id), 0) FROM documents");
                     ResultSet totalRs = totalCheckStmt.executeQuery()) {
                    if (totalRs.next() && totalRs.getLong(1) == 0) {
                        logger.info("No documents found in the database. Nothing to annotate.");
                        return;
                    }
                }
            }


            long totalDocumentsToProcessThisRun;
            String queryBase = "SELECT document_id, text, timestamp FROM documents WHERE document_id >= ? ORDER BY document_id ASC";

            if (limit != null) {
                totalDocumentsToProcessThisRun = limit;
                logger.info("Attempting to process up to {} documents starting from document_id {} (limit specified). Estimated {} documents in available range starting from this ID.", limit, startDocumentId, estimatedDocsInScope);
            } else {
                totalDocumentsToProcessThisRun = estimatedDocsInScope;
                if (estimatedDocsInScope > 0) {
                    logger.info("Attempting to process all {} estimated documents starting from document_id {} (no limit specified).", estimatedDocsInScope, startDocumentId);
                } else {
                    boolean docsActuallyExist = false;
                    try (PreparedStatement checkExistStmt = conn.prepareStatement("SELECT 1 FROM documents WHERE document_id >= ? LIMIT 1")) {
                        checkExistStmt.setInt(1, startDocumentId);
                        try(ResultSet rsCheck = checkExistStmt.executeQuery()) {
                            if (rsCheck.next()) {
                                docsActuallyExist = true;
                            }
                        }
                    }
                    if (docsActuallyExist) {
                         if (estimatedDocsInScope == 0) totalDocumentsToProcessThisRun = 1;
                         logger.info("Attempting to process documents starting from document_id {} (no limit specified, estimated 0 but start ID might exist).", startDocumentId);
                    } else {
                        logger.info("No documents found to process at or after document_id {} (no limit specified, and start ID does not exist).", startDocumentId);
                        if (totalDocumentsToProcessThisRun == 0) return;
                    }
                }
            }

            if (totalDocumentsToProcessThisRun == 0) {
                 logger.info("No documents to process in this run. This might be due to an empty range from {}.", startDocumentId);
                 return;
            }

            final int FETCH_CHUNK_SIZE = batchSize * 10;
            int currentFetchStartId = startDocumentId;
            int totalProcessedInThisRun = 0;
            int totalScheduledInThisRun = 0;

            int totalUserThreads = threads;
            int numCoreNLPInternalThreads;
            int numExecutorThreads;

            if (totalUserThreads <= 1) {
                numCoreNLPInternalThreads = 1;
                numExecutorThreads = 1;
            } else {
                numExecutorThreads = Math.max(1, (int) Math.ceil(totalUserThreads * 0.8));
                numCoreNLPInternalThreads = Math.max(1, totalUserThreads - numExecutorThreads);
                if (numCoreNLPInternalThreads == 0) {
                    numCoreNLPInternalThreads = 1;
                    numExecutorThreads = Math.max(1, totalUserThreads - 1);
                }
            }

            StanfordCoreNLP pipeline = createCoreNLPPipeline(numCoreNLPInternalThreads);
            logger.debug("CoreNLP pipeline initialized for processing with {} internal threads.", numCoreNLPInternalThreads);
            logger.debug("ExecutorService configured for {} parallel document tasks.", numExecutorThreads);

            ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(numExecutorThreads);
            List<java.util.concurrent.Future<AnnotationResult>> futures = new ArrayList<>();
            List<Integer> docIdsInFlight = new ArrayList<>();
            List<AnnotationResult> batchResults = new ArrayList<>();

            ProgressBarBuilder pbb = new ProgressBarBuilder()
                .setTaskName("Annotating")
                .setInitialMax(totalDocumentsToProcessThisRun > 0 ? totalDocumentsToProcessThisRun : 1)
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setUpdateIntervalMillis(200)
                .showSpeed();

            ProgressBar pb = pbb.build();

            try {
                int committedAnnotationsInBatch = 0;
                while (true) {
                    int documentsToFetchThisChunk = FETCH_CHUNK_SIZE;
                    if (limit != null) {
                        int remaining = limit - totalScheduledInThisRun;
                        if (remaining <= 0) break;
                        documentsToFetchThisChunk = Math.min(FETCH_CHUNK_SIZE, remaining);
                    }
                    if (documentsToFetchThisChunk <= 0 && limit !=null) break; // Optimization if limit dictates 0 to fetch

                    String chunkQuery = queryBase + " LIMIT ?";
                    List<DocumentData> documentsChunk = new ArrayList<>();

                    try (PreparedStatement chunkStmt = conn.prepareStatement(chunkQuery)) {
                        chunkStmt.setInt(1, currentFetchStartId);
                        chunkStmt.setInt(2, documentsToFetchThisChunk);

                        try (ResultSet rs = chunkStmt.executeQuery()) {
                            while (rs.next()) {
                                int documentId = rs.getInt("document_id");
                                String text = rs.getString("text");
                                String timestamp = rs.getString("timestamp");
                                documentsChunk.add(new DocumentData(documentId, text, timestamp));
                            }
                        }
                    }

                    if (documentsChunk.isEmpty()) {
                        logger.debug("No more documents found starting from document_id {}. Processing complete for this run.", currentFetchStartId);
                        break;
                    }
                    if (!documentsChunk.isEmpty()) {
                        currentFetchStartId = documentsChunk.get(documentsChunk.size() - 1).documentId() + 1;
                    }


                    logger.debug("Fetched {} documents in chunk starting from document_id {}", documentsChunk.size(), documentsChunk.get(0).documentId());

                    for (DocumentData doc : documentsChunk) {
                        java.util.concurrent.Future<AnnotationResult> future = executor.submit(() -> {
                            AnnotationResult result = processTextWithCoreNLP(pipeline, doc.text(), doc.documentId(), doc.timestamp());
                            return result;
                        });
                        futures.add(future);
                        docIdsInFlight.add(doc.documentId());

                        // Track how many documents we've scheduled to avoid over-scheduling beyond the limit
                        if (limit != null) {
                            totalScheduledInThisRun++;
                            if (totalScheduledInThisRun >= limit) {
                                // We have scheduled up to the limit; no need to add more documents in this chunk
                                // Break out of the inner loop so we don't schedule extra tasks
                                break;
                            }
                        }

                        if (futures.size() >= batchSize) {
                            for (int i = 0; i < futures.size(); i++) {
                                java.util.concurrent.Future<AnnotationResult> f = futures.get(i);
                                int docId = docIdsInFlight.get(i);
                                try {
                                    AnnotationResult result = f.get(DOCUMENT_PROCESSING_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
                                    if (result != null) {
                                        batchResults.add(result);
                                    }
                                } catch (java.util.concurrent.TimeoutException e) {
                                    logger.warn("Timeout ({} min) processing document_id={}. Skipping this document.", DOCUMENT_PROCESSING_TIMEOUT_MINUTES, docId);
                                } catch (Exception e) {
                                    logger.error("Error during annotation task execution for documentId=" + docId, e);
                                }
                            }

                            for (var result : batchResults) {
                                insertData(conn, result.annotations(), result.dependencies());
                                committedAnnotationsInBatch++;
                                totalProcessedInThisRun++;
                                pb.step();
                            }
                            if (committedAnnotationsInBatch > 0) {
                                conn.commit();
                                committedAnnotationsInBatch = 0;
                            }
                            batchResults.clear();
                            futures.clear();
                            docIdsInFlight.clear();
                        }
                    }

                    // If we broke early out of the documentsChunk loop due to reaching the scheduling limit,
                    // ensure that we discard any documents in the chunk that we did not schedule.
                    if (limit != null && totalScheduledInThisRun >= limit) {
                        // Remove any remaining DocumentData objects in documentsChunk that were not scheduled
                        // (They are after the current index in the original loop). Since we broke the loop,
                        // nothing further is needed here, but this clarifies intention.
                    }

                    // Process any remaining futures from the chunk that didn't form a full batch
                    if (!futures.isEmpty()) {
                        for (int i = 0; i < futures.size(); i++) {
                            java.util.concurrent.Future<AnnotationResult> f = futures.get(i);
                            int docId = docIdsInFlight.get(i);
                            try {
                                AnnotationResult result = f.get(DOCUMENT_PROCESSING_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
                                if (result != null) {
                                    batchResults.add(result);
                                }
                            } catch (java.util.concurrent.TimeoutException e) {
                                logger.warn("Timeout ({} min) processing document_id={} (in remaining batch). Skipping this document.", DOCUMENT_PROCESSING_TIMEOUT_MINUTES, docId);
                            } catch (Exception e) {
                                logger.error("Error during annotation task execution for documentId=" + docId, e);
                            }
                        }
                        for (var result : batchResults) {
                            insertData(conn, result.annotations(), result.dependencies());
                            committedAnnotationsInBatch++;
                            totalProcessedInThisRun++;
                            pb.step();
                        }
                        if (committedAnnotationsInBatch > 0) {
                            conn.commit();
                            committedAnnotationsInBatch = 0;
                        }
                        batchResults.clear();
                        futures.clear();
                        docIdsInFlight.clear();
                    }

                    documentsChunk.clear();
                    if (limit != null && totalProcessedInThisRun >= limit) {
                        logger.info("Reached processing limit ({}) for this run.", limit);
                        break;
                    }
                }
                 pb.setExtraMessage(String.format("Completed %d / %d", totalProcessedInThisRun, totalDocumentsToProcessThisRun));

            } finally {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.MINUTES)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                pb.close();
            }
        }
    }

    private static record AnnotationResult(List<Map<String, Object>> annotations,
                                          List<Map<String, Object>> dependencies) {}

    private static void createTables(Connection conn, boolean overwrite) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (overwrite) {
                stmt.execute("DROP TABLE IF EXISTS annotations");
                stmt.execute("DROP TABLE IF EXISTS dependencies");
            }
            stmt.execute("PRAGMA foreign_keys = ON");

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS annotations (
                            annotation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                            document_id INTEGER NOT NULL,
                            sentence_id INTEGER,
                            begin_char INTEGER,
                            end_char INTEGER,
                            token TEXT,
                            pos TEXT,
                            ner TEXT,
                            normalized_ner TEXT,
                            FOREIGN KEY (document_id) REFERENCES documents(document_id)
                        )
                    """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_annotations_document_id ON annotations (document_id)");

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS dependencies (
                            dependency_id INTEGER PRIMARY KEY AUTOINCREMENT,
                            document_id INTEGER NOT NULL,
                            sentence_id INTEGER,
                            begin_char INTEGER,
                            end_char INTEGER,
                            head_token TEXT,
                            dependent_token TEXT,
                            relation TEXT,
                            FOREIGN KEY (document_id) REFERENCES documents(document_id)
                        )
                    """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dependencies_document_id ON dependencies (document_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dep_id ON dependencies (dependency_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dep_relation_did_sid_tokens ON dependencies (relation, document_id, sentence_id, head_token, dependent_token)");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_doc_id_timestamp ON documents (document_id, timestamp)");
        }
    }

    /**
     * Processes text with CoreNLP pipeline, and returns annotations.
     *
     * @param pipeline The CoreNLP pipeline to use for processing
     * @param text The text to process
     * @param documentId The ID of the document being processed
     * @return The AnnotationResult containing annotations and dependencies
     */
    private static AnnotationResult processTextWithCoreNLP(StanfordCoreNLP pipeline, String text, int documentId, String documentTimestamp) {
        List<Map<String, Object>> annotations = new ArrayList<>();
        List<Map<String, Object>> dependencies = new ArrayList<>();

        String textToProcess = text;
        if (text.length() > MAX_DOCUMENT_LENGTH) {
            logger.debug("Document_id {} exceeds MAX_DOCUMENT_LENGTH ({} > {}). Truncating to {} characters.",
                        documentId, text.length(), MAX_DOCUMENT_LENGTH, MAX_DOCUMENT_LENGTH);
            textToProcess = text.substring(0, MAX_DOCUMENT_LENGTH);
        }

        CoreDocument document = new CoreDocument(textToProcess);

        if (documentTimestamp != null && !documentTimestamp.isEmpty()) {
            String dateOnly = documentTimestamp.length() > 10 ? documentTimestamp.substring(0, 10) : documentTimestamp;
            if (dateOnly.matches("\\d{4}-\\d{2}-\\d{2}")) {
                 document.annotation().set(CoreAnnotations.DocDateAnnotation.class, dateOnly);
                 logger.trace("Set DocDateAnnotation to: {} for document_id: {}", dateOnly, documentId);
            } else {
                logger.warn("Timestamp format for document_id: {} ('{}') is not YYYY-MM-DD. SUTime might not use it correctly.", documentId, dateOnly);
            }
        }

        pipeline.annotate(document);

        int sentenceId = 0;
        for (CoreSentence sentence : document.sentences()) {
            List<CoreLabel> coreLabels = sentence.tokens();
            if (coreLabels.isEmpty()) {
                sentenceId++;
                continue;
            }

            int firstTokenActualBeginChar = coreLabels.get(0).beginPosition();
            boolean truncationOccurredInSentence = false;
            List<CoreLabel> keptTokensForThisSentence = new ArrayList<>();

            // Process tokens
            for (CoreLabel token : coreLabels) {
                if (!keepToken(token)) {
                    continue;
                }
                if (token.endPosition() <= firstTokenActualBeginChar + CoreNLPConfig.MAX_SENTENCE_LENGTH) {
                Map<String, Object> annotation = new HashMap<>();
                annotation.put("document_id", documentId);
                annotation.put("sentence_id", sentenceId);
                annotation.put("begin_char", token.beginPosition());
                annotation.put("end_char", token.endPosition());
                annotation.put("token", token.word());
                annotation.put("pos", token.tag());
                annotation.put("ner", token.ner());
                annotation.put("normalized_ner",
                        token.get(CoreAnnotations.NormalizedNamedEntityTagAnnotation.class));

                annotations.add(annotation);
                    keptTokensForThisSentence.add(token);
                } else {
                    // Token extends beyond the allowed span
                    if (!truncationOccurredInSentence) {
                        String lastTokenDetails = keptTokensForThisSentence.isEmpty() ?
                            "N/A (no tokens included)" :
                            String.format("text: '%s', end_char: %d",
                                keptTokensForThisSentence.get(keptTokensForThisSentence.size()-1).word(),
                                keptTokensForThisSentence.get(keptTokensForThisSentence.size()-1).endPosition());

                        logger.trace("Sentence (doc_id: {}, sentence_id: {}) annotation processing truncated by length. Token with text: '{}' (begin_char: {}, end_char: {}) exceeded span limit (first_token_begin_char: {} + max_span: {} = {}). Last token included: {}.",
                                documentId,
                                sentenceId,
                                token.word(),
                                token.beginPosition(),
                                token.endPosition(),
                                firstTokenActualBeginChar,
                                CoreNLPConfig.MAX_SENTENCE_LENGTH,
                                firstTokenActualBeginChar + CoreNLPConfig.MAX_SENTENCE_LENGTH,
                                lastTokenDetails);
                        truncationOccurredInSentence = true;
                    }
                    break;
                }
            }

            // Process dependencies
            SemanticGraph dependencies_graph = sentence.dependencyParse();
            if (dependencies_graph != null) {
                for (SemanticGraphEdge edge : dependencies_graph.edgeIterable()) {
                    IndexedWord source = edge.getSource();
                    IndexedWord target = edge.getTarget();

                    // Filter out dependencies if either token was filtered out
                    if (!keepToken(source.backingLabel()) || !keepToken(target.backingLabel())) {
                        continue;
                    }

                    if (source.endPosition() <= firstTokenActualBeginChar + CoreNLPConfig.MAX_SENTENCE_LENGTH &&
                        target.endPosition() <= firstTokenActualBeginChar + CoreNLPConfig.MAX_SENTENCE_LENGTH) {

                    int beginChar = Math.min(source.beginPosition(), target.beginPosition());
                    int endChar = Math.max(source.endPosition(), target.endPosition());

                        if (endChar <= firstTokenActualBeginChar + CoreNLPConfig.MAX_SENTENCE_LENGTH) {
                    Map<String, Object> dependency = new HashMap<>();
                    dependency.put("document_id", documentId);
                    dependency.put("sentence_id", sentenceId);
                    dependency.put("begin_char", beginChar);
                    dependency.put("end_char", endChar);
                    dependency.put("head_token", source.word());
                    dependency.put("dependent_token", target.word());
                    dependency.put("relation", edge.getRelation().toString());

                    dependencies.add(dependency);
                        }
                    }
                }
            }
            sentenceId++;
        }

        return new AnnotationResult(annotations, dependencies);
    }

    private static void insertData(Connection conn, List<Map<String, Object>> annotations,
            List<Map<String, Object>> dependencies) throws SQLException {
        String annotationSQL = """
                    INSERT INTO annotations (
                        document_id, sentence_id, begin_char, end_char, token,
                        pos, ner, normalized_ner
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String dependencySQL = """
                    INSERT INTO dependencies (
                        document_id, sentence_id, begin_char, end_char,
                        head_token, dependent_token, relation
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement annotationStmt = conn.prepareStatement(annotationSQL);
                PreparedStatement dependencyStmt = conn.prepareStatement(dependencySQL)) {

            for (Map<String, Object> annotation : annotations) {
                annotationStmt.setInt(1, (Integer) annotation.get("document_id"));
                annotationStmt.setInt(2, (Integer) annotation.get("sentence_id"));
                annotationStmt.setInt(3, (Integer) annotation.get("begin_char"));
                annotationStmt.setInt(4, (Integer) annotation.get("end_char"));
                annotationStmt.setString(5, (String) annotation.get("token"));
                annotationStmt.setString(6, (String) annotation.get("pos"));
                annotationStmt.setString(7, (String) annotation.get("ner"));
                annotationStmt.setString(8, (String) annotation.get("normalized_ner"));

                annotationStmt.addBatch();
            }
            annotationStmt.executeBatch();

            for (Map<String, Object> dependency : dependencies) {
                dependencyStmt.setInt(1, (Integer) dependency.get("document_id"));
                dependencyStmt.setInt(2, (Integer) dependency.get("sentence_id"));
                dependencyStmt.setInt(3, (Integer) dependency.get("begin_char"));
                dependencyStmt.setInt(4, (Integer) dependency.get("end_char"));
                dependencyStmt.setString(5, (String) dependency.get("head_token"));
                dependencyStmt.setString(6, (String) dependency.get("dependent_token"));
                dependencyStmt.setString(7, (String) dependency.get("relation"));

                dependencyStmt.addBatch();
            }
            dependencyStmt.executeBatch();
        }
    }

    private static record DocumentData(int documentId, String text, String timestamp) {}
}

