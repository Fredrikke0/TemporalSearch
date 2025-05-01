package com.example;

import com.example.index.*;
import com.example.logging.IndexingMetrics;
import com.example.logging.ProgressTracker;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.io.FileNotFoundException;


public class IndexRunner {
    private static final Logger logger = LoggerFactory.getLogger(IndexRunner.class);

    public static void main(String[] args) {
        // Create focused argument parser for indexing
        ArgumentParser parser = ArgumentParsers.newFor("IndexRunner").build()
                .defaultHelp(true)
                .description("Create indexes from annotated database");

        parser.addArgument("-d", "--db")
                .required(true)
                .help("SQLite database file path");

        parser.addArgument("--index-dir")
                .setDefault("indexes")
                .help("Directory for storing indexes (default: 'indexes')");

        parser.addArgument("--stopwords")
                .setDefault("stopwords.txt")
                .help("Path to stopwords file (default: stopwords.txt)");

        parser.addArgument("--batch-size")
                .setDefault(1000)
                .type(Integer.class)
                .help("Batch size for processing (default: 1000)");

        parser.addArgument("-t", "--type")
                .choices("all", "unigram", "bigram", "trigram", "dependency", "ner_date", "ner", "pos", "hypernym", "stitch", "nash")
                .setDefault("all")
                .help("Type of index to generate");

        parser.addArgument("--debug")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Enable debug logging to console");

        parser.addArgument("-k", "--preserve-index")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Keep existing index data if present");

        try {
            // Parse arguments
            Namespace ns = parser.parseArgs(args);
            
            // Set debug mode
            if (ns.getBoolean("debug")) {
                System.setProperty("DEBUG_MODE", "true");
            }

            runIndexing(
                ns.getString("db"),
                ns.getString("index_dir"),
                ns.getString("stopwords"),
                ns.getInt("batch_size"),
                ns.getString("type"),
                ns.getBoolean("preserve_index")
            );
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        } catch (Exception e) {
            logger.error("Error generating index: {}", e.getMessage(), e);
            System.exit(1);
        }
    }


    public static void runIndexing(String dbPath, String indexDir, String stopwordsPath,
            int batchSize, String indexType, boolean preserveIndex) throws Exception {
        logger.info("Starting indexing process (No Limit Applied)");
        logger.debug("Database: {}", dbPath);
        logger.debug("Index directory: {}", indexDir);
        
        // Verify the database exists and is not empty
        Path dbFilePath = Path.of(dbPath);
        if (!Files.exists(dbFilePath)) {
            throw new FileNotFoundException("Database file not found: " + dbPath);
        }
        
        // Check if the database file has content
        if (Files.size(dbFilePath) == 0) {
            throw new IOException("Database file is empty. Please run the conversion and annotation stages first.");
        }
        
        // Create all index subdirectories
        setupIndexDirectories(indexDir, indexType);
        
        IndexingMetrics metrics = new IndexingMetrics();
        ProgressTracker progress = new ProgressTracker();
        
        // Create index configuration without limit
        IndexConfig indexConfig = new IndexConfig.Builder()
            .withPreserveExistingIndex(preserveIndex)
            .build();

        // Ensure index directory exists
        Path indexPath = Paths.get(indexDir);
        if (!preserveIndex) {
            // Only clean up the index directories, not the database file
            Path[] indexDirectories = {
                indexPath.resolve("unigram"),
                indexPath.resolve("bigram"),
                indexPath.resolve("entity"),
                indexPath.resolve("dependency"),
                indexPath.resolve("nerdate"),
                indexPath.resolve("ner"),
                indexPath.resolve("pos"),
                indexPath.resolve("hypernym"),
                indexPath.resolve("stitch")
            };
            
            for (Path dir : indexDirectories) {
                if (Files.exists(dir)) {
                    logger.debug("Cleaning existing index directory: {}", dir);
                    Files.walk(dir)
                         .sorted((a, b) -> b.compareTo(a))
                         .forEach(path -> {
                             try {
                                 Files.deleteIfExists(path);
                             } catch (IOException e) {
                                 logger.warn("Could not delete: {} ({})", path, e.getMessage());
                             }
                         });
                }
            }
        }
        
        // Make sure only the index directories are created, not overwriting the database
        Files.createDirectories(indexPath);
        
        // Connect to database and process indexes
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            // Determine total number of indexes to generate
            int totalIndexes = indexType.equals("all") ? 10 : 1;
            int currentIndex = 0;

            // Create and run generators based on type
            try {
                if (indexType.equals("all") || indexType.equals("unigram")) {
                    currentIndex++;
                    metrics.startBatch(batchSize, "unigram");
                    long count = getAnnotationCount(conn);
                    progress.startIndex("Unigram Index", count);
                    
                    // Get path with proper directory structure
                    Path unigramPath = Path.of(indexDir).resolve("unigram");
                    try (UnigramIndexGenerator gen = new UnigramIndexGenerator(
                            unigramPath.toString(), stopwordsPath, conn, progress, indexConfig)) {
                        gen.generateIndex();
                        metrics.recordBatchSuccess((int)count);
                    } catch (Exception e) {
                        metrics.recordBatchFailure();
                        logger.error("Error generating unigram index: {}", e.getMessage(), e);
                    }
                    progress.completeIndex();
                }

                if (indexType.equals("all") || indexType.equals("bigram")) {
                    currentIndex++;
                    metrics.startBatch(batchSize, "bigram");
                    long count = getAnnotationCount(conn);
                    progress.startIndex("Bigram Index", count);
                    
                    // Get path with proper directory structure
                    Path bigramPath = Path.of(indexDir).resolve("bigram");
                    try (BigramIndexGenerator gen = new BigramIndexGenerator(
                            bigramPath.toString(), stopwordsPath, conn, progress, indexConfig)) {
                        gen.generateIndex();
                        metrics.recordBatchSuccess((int)count);
                    } catch (Exception e) {
                        metrics.recordBatchFailure();
                        logger.error("Error generating bigram index: {}", e.getMessage(), e);
                    }
                    progress.completeIndex();
                }

                if (indexType.equals("all") || indexType.equals("trigram")) {
                    currentIndex++;
                    metrics.startBatch(batchSize, "trigram");
                    long count = getAnnotationCount(conn);
                    progress.startIndex("Trigram Index", count);
                    
                    // Get path with proper directory structure
                    Path trigramPath = Path.of(indexDir).resolve("trigram");
                    try (TrigramIndexGenerator gen = new TrigramIndexGenerator(
                            trigramPath.toString(), stopwordsPath, conn, progress, indexConfig)) {
                        gen.generateIndex();
                        metrics.recordBatchSuccess((int)count);
                    } catch (Exception e) {
                        metrics.recordBatchFailure();
                        logger.error("Error generating trigram index: {}", e.getMessage(), e);
                    }
                    progress.completeIndex();
                }

                if (indexType.equals("all") || indexType.equals("dependency")) {
                    currentIndex++;
                    metrics.startBatch(batchSize, "dependency");
                    long count = getDependencyCount(conn);
                    progress.startIndex("Dependency Index", count);
                    
                    // Get path with proper directory structure
                    Path dependencyPath = Path.of(indexDir).resolve("dependency");
                    try (DependencyIndexGenerator gen = new DependencyIndexGenerator(
                            dependencyPath.toString(), stopwordsPath, conn, progress, indexConfig)) {
                        gen.generateIndex();
                        metrics.recordBatchSuccess((int)count);
                    } catch (Exception e) {
                        metrics.recordBatchFailure();
                        logger.error("Error generating dependency index: {}", e.getMessage(), e);
                    }
                    progress.completeIndex();
                }

                if (indexType.equals("all") || indexType.equals("ner_date")) {
                    currentIndex++;
                    metrics.startBatch(batchSize, "ner_date");
                    long count = getNerDateCount(conn);
                    progress.startIndex("NER Date Index", count);
                    
                    // Get path with proper directory structure
                    Path nerDatePath = Path.of(indexDir).resolve("ner_date");
                    try (NerDateIndexGenerator gen = new NerDateIndexGenerator(
                            nerDatePath.toString(), stopwordsPath, conn, progress, indexConfig)) {
                        gen.generateIndex();
                        metrics.recordBatchSuccess((int)count);
                    } catch (Exception e) {
                        metrics.recordBatchFailure();
                        logger.error("Error generating NER date index: {}", e.getMessage(), e);
                    }
                    progress.completeIndex();
                }

                if (indexType.equals("all") || indexType.equals("ner")) {
                    currentIndex++;
                    metrics.startBatch(batchSize, "ner");
                    long count = getNerCount(conn);
                    progress.startIndex("NER Index", count);
                    
                    // Get path with proper directory structure
                    Path nerPath = Path.of(indexDir).resolve("ner");
                    try (NerIndexGenerator gen = new NerIndexGenerator(
                            nerPath.toString(), stopwordsPath, conn, progress, indexConfig)) {
                        gen.generateIndex();
                        metrics.recordBatchSuccess((int)count);
                    } catch (Exception e) {
                        metrics.recordBatchFailure();
                        logger.error("Error generating NER index: {}", e.getMessage(), e);
                    }
                    progress.completeIndex();
                }

                if (indexType.equals("all") || indexType.equals("pos")) {
                    currentIndex++;
                    metrics.startBatch(batchSize, "pos");
                    long count = getAnnotationCount(conn);
                    progress.startIndex("POS Tag Index", count);
                    
                    // Get path with proper directory structure
                    Path posPath = Path.of(indexDir).resolve("pos");
                    try (POSIndexGenerator gen = new POSIndexGenerator(
                            posPath.toString(), stopwordsPath, conn, progress, indexConfig)) {
                        gen.generateIndex();
                        metrics.recordBatchSuccess((int)count);
                    } catch (Exception e) {
                        metrics.recordBatchFailure();
                        logger.error("Error generating POS index: {}", e.getMessage(), e);
                    }
                    progress.completeIndex();
                }

                if (indexType.equals("all") || indexType.equals("hypernym")) {
                    currentIndex++;
                    metrics.startBatch(batchSize, "hypernym");
                    long count = getAnnotationCount(conn);
                    progress.startIndex("Hypernym Index", count);
                    
                    // Get path with proper directory structure
                    Path hypernymPath = Path.of(indexDir).resolve("hypernym");
                    try (HypernymIndexGenerator gen = new HypernymIndexGenerator(
                            hypernymPath.toString(), stopwordsPath, conn, progress, indexConfig)) {
                        gen.generateIndex();
                        metrics.recordBatchSuccess((int)count);
                    } catch (Exception e) {
                        metrics.recordBatchFailure();
                        logger.error("Error generating hypernym index: {}", e.getMessage(), e);
                    }
                    progress.completeIndex();
                }

                if (indexType.equals("all") || indexType.equals("stitch")) {
                    currentIndex++;
                    metrics.startBatch(batchSize, "stitch");
                    long count = getNerDateCount(conn);
                    progress.startIndex("Stitch Index", count);
                    
                    // Get path with proper directory structure
                    Path stitchPath = Path.of(indexDir).resolve("stitch");
                    try (StitchIndexGenerator gen = new StitchIndexGenerator(
                            stitchPath.toString(), stopwordsPath, conn, progress, indexConfig)) {
                        gen.generateIndex();
                        metrics.recordBatchSuccess((int)count);
                    } catch (Exception e) {
                        metrics.recordBatchFailure();
                        logger.error("Error generating stitch index: {}", e.getMessage(), e);
                    }
                    progress.completeIndex();
                }

                if (indexType.equals("all") || indexType.equals("nash")) {
                    currentIndex++;
                    metrics.startBatch(batchSize, "nash");
                    long count = getNerDateCount(conn);
                    progress.startIndex("Nash Index", count);
                    
                    // Get path with proper directory structure
                    Path nashPath = Path.of(indexDir).resolve("nash");
                    try (NashIndexGenerator gen = new NashIndexGenerator(
                            nashPath.toString(), stopwordsPath, conn, progress, indexConfig)) {
                        gen.generateIndex();
                        metrics.recordBatchSuccess((int)count);
                    } catch (Exception e) {
                        metrics.recordBatchFailure();
                        logger.error("Error generating nash index: {}", e.getMessage(), e);
                    }
                    progress.completeIndex();
                }

            } finally {
                metrics.logIndexingMetrics();
                progress.close();
            }
        }

        logger.info("Index generation completed successfully");
    }

    private static long getAnnotationCount(Connection conn) throws SQLException {
        // First check if the annotations table exists
        try (Statement checkStmt = conn.createStatement();
             ResultSet checkRs = checkStmt.executeQuery(
                 "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='annotations'")) {
            if (checkRs.next() && checkRs.getInt(1) == 0) {
                // Table doesn't exist
                logger.error("Annotations table does not exist. Please run annotation stage first.");
                throw new SQLException("Annotations table does not exist. Please run annotation stage first.");
            }
        }
        
        String sql = "SELECT COUNT(*) FROM annotations";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        } catch (SQLException e) {
            logger.error("Error counting annotations: {}", e.getMessage());
            return 0;
        }
    }

    private static long getDependencyCount(Connection conn) throws SQLException {
        // First check if the dependencies table exists
        try (Statement checkStmt = conn.createStatement();
             ResultSet checkRs = checkStmt.executeQuery(
                 "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='dependencies'")) {
            if (checkRs.next() && checkRs.getInt(1) == 0) {
                // Table doesn't exist
                logger.error("Dependencies table does not exist. Please run annotation stage first.");
                throw new SQLException("Dependencies table does not exist. Please run annotation stage first.");
            }
        }
        
        String sql = "SELECT COUNT(*) FROM dependencies";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        } catch (SQLException e) {
            logger.error("Error counting dependencies: {}", e.getMessage());
            return 0;
        }
    }

    private static long getNerDateCount(Connection conn) throws SQLException {
        // First check if the annotations table exists
        try (Statement checkStmt = conn.createStatement();
             ResultSet checkRs = checkStmt.executeQuery(
                 "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='annotations'")) {
            if (checkRs.next() && checkRs.getInt(1) == 0) {
                // Table doesn't exist
                logger.error("Annotations table does not exist. Please run annotation stage first.");
                throw new SQLException("Annotations table does not exist. Please run annotation stage first.");
            }
        }
        
        String sql = "SELECT COUNT(*) FROM annotations WHERE ner = 'DATE'";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        } catch (SQLException e) {
            logger.error("Error counting NER dates: {}", e.getMessage());
            return 0;
        }
    }

    private static long getNerCount(Connection conn) throws SQLException {
        // First check if the annotations table exists
        try (Statement checkStmt = conn.createStatement();
             ResultSet checkRs = checkStmt.executeQuery(
                 "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='annotations'")) {
            if (checkRs.next() && checkRs.getInt(1) == 0) {
                // Table doesn't exist
                logger.error("Annotations table does not exist. Please run annotation stage first.");
                throw new SQLException("Annotations table does not exist. Please run annotation stage first.");
            }
        }
        
        String sql = "SELECT COUNT(*) FROM annotations WHERE ner IS NOT NULL AND ner != '' AND ner != 'DATE' AND ner != 'O'";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        } catch (SQLException e) {
            logger.error("Error counting NER entities: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Setup index directories for the given project
     * @param indexDir Base directory for indexes
     * @param indexType Type of index to create (or "all")
     * @throws IOException If directory creation fails
     */
    private static void setupIndexDirectories(String indexDir, String indexType) throws IOException {
        // Ensure the base directory exists
        Files.createDirectories(Path.of(indexDir));
        
        // Determine which subdirectories to create
        if (indexType.equals("all") || indexType.equals("unigram")) {
            Files.createDirectories(Path.of(indexDir, "unigram"));
        }
        if (indexType.equals("all") || indexType.equals("bigram")) {
            Files.createDirectories(Path.of(indexDir, "bigram"));
        }
        if (indexType.equals("all") || indexType.equals("trigram")) {
            Files.createDirectories(Path.of(indexDir, "trigram"));
        }
        if (indexType.equals("all") || indexType.equals("dependency")) {
            Files.createDirectories(Path.of(indexDir, "dependency"));
        }
        if (indexType.equals("all") || indexType.equals("ner_date")) {
            Files.createDirectories(Path.of(indexDir, "ner_date"));
        }
        if (indexType.equals("all") || indexType.equals("ner")) {
            Files.createDirectories(Path.of(indexDir, "ner"));
        }
        if (indexType.equals("all") || indexType.equals("pos")) {
            Files.createDirectories(Path.of(indexDir, "pos"));
        }
        if (indexType.equals("all") || indexType.equals("hypernym")) {
            Files.createDirectories(Path.of(indexDir, "hypernym"));
        }
        if (indexType.equals("all") || indexType.equals("stitch")) {
            Files.createDirectories(Path.of(indexDir, "stitch"));
        }
        if (indexType.equals("all") || indexType.equals("nash")) {
            Files.createDirectories(Path.of(indexDir, "nash"));
        }
    }
}
