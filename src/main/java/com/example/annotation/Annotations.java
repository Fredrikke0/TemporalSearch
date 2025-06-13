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
                return new AnnotationStatus(false, 1); // Fail gracefully
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
            // If annotations table doesn't exist, we will try to query it and catch SQLException below if needed.

            int startDocumentIdForProcessing = -1;
            String findMinUnannotatedSql = """
                SELECT MIN(d.document_id)
                FROM documents d
                LEFT JOIN (SELECT DISTINCT document_id FROM annotations) a
                ON d.document_id = a.document_id
                WHERE a.document_id IS NULL
                """;

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(findMinUnannotatedSql)) {
                if (rs.next()) {
                    int minUnannotated = rs.getInt(1);
                    if (!rs.wasNull()) {
                        startDocumentIdForProcessing = minUnannotated;
                    }
                }
            } catch (SQLException e) {
                // This can happen if 'annotations' table doesn't exist.
                logger.info("Could not query for unannotated documents (e.g., 'annotations' table might not exist). Needs processing from the start (document_id: {}).", minDocIdOverall, e);
                return new AnnotationStatus(true, minDocIdOverall); // Assume processing needed from start
            }

            if (startDocumentIdForProcessing != -1) {
                logger.info("Found unannotated documents. Annotation should start/resume from document_id: {}", startDocumentIdForProcessing);
                return new AnnotationStatus(true, startDocumentIdForProcessing);
            } else {
                logger.info("All documents in the 'documents' table appear to have corresponding entries in 'annotations'. No new documents to process.");
                return new AnnotationStatus(false, minDocIdOverall);
            }
        }
    }

    /**
     * Identifies unannotated documents and re-numbers their document_ids to be higher than
     * the current maximum document_id in the documents table.
     * Assumes 'documents' and 'annotations' tables exist.
     */
    public static void fixDocumentIds(Path projectDbPath) throws SQLException {
        String url = "jdbc:sqlite:" + projectDbPath;
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(url);
            conn.setAutoCommit(false);

            List<Integer> unannotatedDocIds = new ArrayList<>();
            // This query will fail if 'documents' or 'annotations' table does not exist.
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
     * Runs annotation from a specific document ID, deleting any existing annotation/dependency rows for each doc before inserting new ones.
     * Processes up to 'limit' documents in this run (limit is per run, not total).
     * Respects resumability: resumes from the last annotated document.
     * Also accepts a force flag to control cleanup behavior.
     * Includes an option to fix document IDs for unannotated documents before processing.
     */
    public static void runAnnotation(Path projectDbPath, int initialStartDocumentId, int threads, int batchSize, Integer limit, boolean force, boolean fixIds) throws Exception {
        if (fixIds) {
            logger.info("Fix IDs flag is true. Attempting to re-number unannotated document IDs.");
            try {
                fixDocumentIds(projectDbPath); // This modifies the DB
                logger.info("Document ID fixing process completed.");
            } catch (SQLException e) {
                logger.error("Error during document ID fixing. Annotation process cannot proceed reliably.", e);
                throw new Exception("Failed to fix document IDs, cannot annotate.", e);
            }
        }

        AnnotationStatus currentStatus = getAnnotationStatus(projectDbPath);
        int effectiveStartDocumentId;
        boolean effectivelyNeedsProcessing = currentStatus.needsProcessing;

        if (force) {
            effectiveStartDocumentId = initialStartDocumentId;
            effectivelyNeedsProcessing = true;
            logger.info("Force flag is true. Annotation will start/resume from document_id {} (user-provided or default if 1).", effectiveStartDocumentId);
        } else {
            if (!currentStatus.needsProcessing) {
                logger.info("No documents require annotation (according to getAnnotationStatus) and force flag is false. Nothing to do.");
                return;
            }
            effectiveStartDocumentId = currentStatus.startDocumentId;
            logger.info("Annotation will start/resume from document_id {} (determined by current status).", effectiveStartDocumentId);
        }

        String url = "jdbc:sqlite:" + projectDbPath;
        try (Connection conn = DriverManager.getConnection(url)) {
            conn.setAutoCommit(false);
            createTables(conn, false); // Don't drop tables

            // Cleanup logic based on force and effectiveStartDocumentId
            if (force) {
                logger.info("Force flag is true. Deleting existing annotations and dependencies for document_id >= {}", effectiveStartDocumentId);
                try (PreparedStatement delAnn = conn.prepareStatement("DELETE FROM annotations WHERE document_id >= ?");
                     PreparedStatement delDep = conn.prepareStatement("DELETE FROM dependencies WHERE document_id >= ?")) {

                    delAnn.setInt(1, effectiveStartDocumentId);
                    delAnn.executeUpdate();

                    delDep.setInt(1, effectiveStartDocumentId);
                    delDep.executeUpdate();

                    conn.commit();
                } catch (SQLException e) {
                    logger.error("Error during forced delete for document_id >= " + effectiveStartDocumentId, e);
                    throw e;
                }
            } else if (effectivelyNeedsProcessing && effectiveStartDocumentId > 1) {
                // This 'else if' condition ensures we only do this preemptive delete if we are *not* forcing,
                // *and* we actually determined there's processing to do, *and* we're not starting from the very beginning (doc_id 1).
                // This is to clean up a potentially partially processed document we are resuming.
                logger.info("Resuming or starting from specific ID (force=false, effectivelyNeedsProcessing=true). Performing cleanup for document_id: {}", effectiveStartDocumentId);
                try (PreparedStatement delAnn = conn.prepareStatement("DELETE FROM annotations WHERE document_id = ?");
                     PreparedStatement delDep = conn.prepareStatement("DELETE FROM dependencies WHERE document_id = ?")) {

                    delAnn.setInt(1, effectiveStartDocumentId);
                    delAnn.executeUpdate();

                    delDep.setInt(1, effectiveStartDocumentId);
                    delDep.executeUpdate();

                    conn.commit();
                } catch (SQLException e) {
                    logger.error("Error during pre-emptive delete for document_id=" + effectiveStartDocumentId, e);
                    throw e;
                }
            }

            // --- Calculate total documents to process (respecting limit and effectiveStartDocumentId) ---
            long estimatedDocsInScope = 0;
            // Query for estimation should use effectiveStartDocumentId
            String maxIdSql = "SELECT COALESCE(MAX(document_id), 0) FROM documents WHERE document_id >= ?";
            try (PreparedStatement maxIdStmt = conn.prepareStatement(maxIdSql)) {
                maxIdStmt.setInt(1, effectiveStartDocumentId);
                try (ResultSet maxIdRs = maxIdStmt.executeQuery()) {
                    if (maxIdRs.next()) {
                        long queriedMaxId = maxIdRs.getLong(1);
                        if (queriedMaxId > 0) {
                            estimatedDocsInScope = (queriedMaxId >= effectiveStartDocumentId) ? (queriedMaxId - effectiveStartDocumentId + 1) : 0;
                        }
                    }
                }
            }
            if (estimatedDocsInScope == 0 && effectiveStartDocumentId == 1) { // Check this logic carefully
                 // If no docs in scope and starting from 1, maybe there are no docs at all or only docs < 1 (impossible)
                try (PreparedStatement totalCheckStmt = conn.prepareStatement("SELECT COALESCE(MAX(document_id), 0) FROM documents");
                     ResultSet totalRs = totalCheckStmt.executeQuery()) {
                    if (totalRs.next() && totalRs.getLong(1) == 0) {
                        logger.info("No documents found in the database. Nothing to annotate.");
                        return;
                    }
                }
            }


            long totalDocumentsToProcessThisRun;
            // Query base should use effectiveStartDocumentId
            String queryBase = "SELECT document_id, text, timestamp FROM documents WHERE document_id >= ? ORDER BY document_id ASC";

            if (limit != null) {
                totalDocumentsToProcessThisRun = limit;
                logger.info("Attempting to process up to {} documents starting from document_id {} (limit specified). Estimated {} documents in available range starting from this ID.", limit, effectiveStartDocumentId, estimatedDocsInScope);
            } else {
                totalDocumentsToProcessThisRun = estimatedDocsInScope;
                if (estimatedDocsInScope > 0) {
                    logger.info("Attempting to process all {} estimated documents starting from document_id {} (no limit specified).", estimatedDocsInScope, effectiveStartDocumentId);
                } else {
                     // Check if any documents exist at all starting from effectiveStartDocumentId
                    boolean docsActuallyExist = false;
                    try (PreparedStatement checkExistStmt = conn.prepareStatement("SELECT 1 FROM documents WHERE document_id >= ? LIMIT 1")) {
                        checkExistStmt.setInt(1, effectiveStartDocumentId);
                        try(ResultSet rsCheck = checkExistStmt.executeQuery()) {
                            if (rsCheck.next()) {
                                docsActuallyExist = true;
                            }
                        }
                    }
                    if (docsActuallyExist) {
                         // This case is tricky: estimatedDocsInScope might be 0 if effectiveStartDocumentId is MAX(doc_id)
                         // but a document *does* exist at effectiveStartDocumentId. Progress bar will show 1.
                         if (estimatedDocsInScope == 0) totalDocumentsToProcessThisRun = 1; // Process at least the start ID if it exists
                         logger.info("Attempting to process documents starting from document_id {} (no limit specified, estimated 0 but start ID might exist).", effectiveStartDocumentId);
                    } else {
                        logger.info("No documents found to process at or after document_id {} (no limit specified, and start ID does not exist).", effectiveStartDocumentId);
                        if (totalDocumentsToProcessThisRun == 0) return;
                    }
                }
            }

            if (totalDocumentsToProcessThisRun == 0 && effectivelyNeedsProcessing) {
                 logger.info("No documents to process in this run, despite 'effectivelyNeedsProcessing' being true. This might be due to ID fixing or an empty range from {}.", effectiveStartDocumentId);
                 return;
            }
            if (totalDocumentsToProcessThisRun == 0 && !effectivelyNeedsProcessing){
                 logger.info("No documents to process in this run (effectivelyNeedsProcessing is false and count is 0).");
                 return;
            }


            final int FETCH_CHUNK_SIZE = batchSize * 10;
            int currentFetchStartId = effectiveStartDocumentId; // Use currentFetchStartId for query pagination
            int totalProcessedInThisRun = 0;

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
            List<Integer> docIdsInFlight = new ArrayList<>(); // Renamed from docIds to avoid confusion
            List<AnnotationResult> batchResults = new ArrayList<>();
            int committedAnnotationsInBatch = 0; // Renamed from 'batch' to be clearer

            ProgressBarBuilder pbb = new ProgressBarBuilder()
                .setTaskName("Annotating")
                .setInitialMax(totalDocumentsToProcessThisRun > 0 ? totalDocumentsToProcessThisRun : 1) // Ensure initialMax is at least 1 for PB
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setUpdateIntervalMillis(200)
                .showSpeed();

            try (ProgressBar pb = pbb.build()) {
                while (true) {
                    int documentsToFetchThisChunk = FETCH_CHUNK_SIZE;
                    if (limit != null) {
                        int remaining = limit - totalProcessedInThisRun;
                        if (remaining <= 0) break;
                        documentsToFetchThisChunk = Math.min(FETCH_CHUNK_SIZE, remaining);
                    }
                    if (documentsToFetchThisChunk <= 0 && limit !=null) break; // Optimization if limit dictates 0 to fetch

                    String chunkQuery = queryBase + " LIMIT ?"; // queryBase already uses effectiveStartDocumentId implicitly via currentFetchStartId's init
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
                                // currentFetchStartId = documentId + 1; // This was original, correct for ASC order
                            }
                        }
                    }

                    if (documentsChunk.isEmpty()) {
                        logger.debug("No more documents found starting from document_id {}. Processing complete for this run.", currentFetchStartId);
                        break;
                    }
                    // Update currentFetchStartId to the ID *after* the last one fetched in this chunk for the next iteration
                    if (!documentsChunk.isEmpty()) {
                        currentFetchStartId = documentsChunk.get(documentsChunk.size() -1).documentId + 1;
                    }


                    logger.debug("Fetched {} documents in chunk starting from document_id {}", documentsChunk.size(), documentsChunk.get(0).documentId);

                    for (DocumentData doc : documentsChunk) {
                        java.util.concurrent.Future<AnnotationResult> future = executor.submit(() -> {
                            AnnotationResult result = processTextWithCoreNLP(pipeline, doc.text, doc.documentId, doc.timestamp);
                            return result;
                        });
                        futures.add(future);
                        docIdsInFlight.add(doc.documentId);

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

                            for (AnnotationResult result : batchResults) {
                                insertData(conn, result.annotations, result.dependencies);
                                committedAnnotationsInBatch++;
                                totalProcessedInThisRun++;
                                pb.step();
                            }

                            if (committedAnnotationsInBatch > 0) {
                                conn.commit();
                                logger.trace("Committed batch of {} annotation results (for {} documents).", committedAnnotationsInBatch, batchResults.size()); // logging was batch (int)
                                committedAnnotationsInBatch = 0;
                            }

                            batchResults.clear();
                            futures.clear();
                            docIdsInFlight.clear();
                        }
                    }
                    documentsChunk.clear();
                    if (limit != null && totalProcessedInThisRun >= limit) {
                        logger.info("Reached processing limit ({}) for this run.", limit);
                        break;
                    }
                }

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

                for (AnnotationResult result : batchResults) {
                    insertData(conn, result.annotations, result.dependencies);
                    committedAnnotationsInBatch++;
                    totalProcessedInThisRun++; // Count successfully processed and inserted
                    pb.step();
                }

                if (committedAnnotationsInBatch > 0) {
                    conn.commit();
                    logger.trace("Committed final {} annotation results (for {} documents).", committedAnnotationsInBatch, batchResults.size());
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
            }
        }
    }

    private static class AnnotationResult {
        final List<Map<String, Object>> annotations;
        final List<Map<String, Object>> dependencies;

        AnnotationResult(List<Map<String, Object>> annotations, List<Map<String, Object>> dependencies) {
            this.annotations = annotations;
            this.dependencies = dependencies;
        }
    }

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
                            lemma TEXT,
                            pos TEXT,
                            ner TEXT,
                            normalized_ner TEXT,
                            FOREIGN KEY (document_id) REFERENCES documents(document_id)
                        )
                    """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_annotations_document_id ON annotations (document_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ann_did_sid_token_lemma ON annotations (document_id, sentence_id, token, lemma)");

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

        // Set the document date for SUTime to resolve relative dates like "yesterday"
        if (documentTimestamp != null && !documentTimestamp.isEmpty()) {
            // CoreNLP's DocDateAnnotation expects "YYYY-MM-DD"
            String dateOnly = documentTimestamp.length() > 10 ? documentTimestamp.substring(0, 10) : documentTimestamp;
            if (dateOnly.matches("\\d{4}-\\d{2}-\\d{2}")) { // Basic check for YYYY-MM-DD format
                 document.annotation().set(CoreAnnotations.DocDateAnnotation.class, dateOnly);
                 logger.trace("Set DocDateAnnotation to: {} for document_id: {}", dateOnly, documentId);
            } else {
                logger.warn("Timestamp format for document_id: {} ('{}') is not YYYY-MM-DD. SUTime might not use it correctly.", documentId, dateOnly);
            }
        }

        pipeline.annotate(document);

        int sentenceId = 0;
        for (CoreSentence sentence : document.sentences()) {
            List<CoreLabel> tokens = sentence.tokens();

            // Process tokens
            for (CoreLabel token : tokens) {
                Map<String, Object> annotation = new HashMap<>();
                annotation.put("document_id", documentId);
                annotation.put("sentence_id", sentenceId);
                annotation.put("begin_char", token.beginPosition());
                annotation.put("end_char", token.endPosition());
                annotation.put("token", token.word());
                annotation.put("lemma", token.lemma());
                annotation.put("pos", token.tag());
                annotation.put("ner", token.ner());
                annotation.put("normalized_ner",
                        token.get(CoreAnnotations.NormalizedNamedEntityTagAnnotation.class));

                annotations.add(annotation);
            }

            // Process dependencies
            SemanticGraph dependencies_graph = sentence.dependencyParse();
            if (dependencies_graph != null) {
                for (SemanticGraphEdge edge : dependencies_graph.edgeIterable()) {
                    IndexedWord source = edge.getSource();
                    IndexedWord target = edge.getTarget();

                    int beginChar = Math.min(source.beginPosition(), target.beginPosition());
                    int endChar = Math.max(source.endPosition(), target.endPosition());

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
            sentenceId++;
        }

        return new AnnotationResult(annotations, dependencies);
    }

    private static void insertData(Connection conn, List<Map<String, Object>> annotations,
            List<Map<String, Object>> dependencies) throws SQLException {
        String annotationSQL = """
                    INSERT INTO annotations (
                        document_id, sentence_id, begin_char, end_char, token,
                        lemma, pos, ner, normalized_ner
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                annotationStmt.setString(6, (String) annotation.get("lemma"));
                annotationStmt.setString(7, (String) annotation.get("pos"));
                annotationStmt.setString(8, (String) annotation.get("ner"));
                annotationStmt.setString(9, (String) annotation.get("normalized_ner"));

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

    // Helper class for document data
    private static class DocumentData {
        final int documentId;
        final String text;
        final String timestamp;

        DocumentData(int documentId, String text, String timestamp) {
            this.documentId = documentId;
            this.text = text;
            this.timestamp = timestamp;
        }
    }
}

