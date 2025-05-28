package com.example;

import com.example.index.*;
import com.example.index.generators.BigramIndexGenerator;
import com.example.index.generators.DateStitchIndexGenerator;
import com.example.index.generators.DependencyIndexGenerator;
import com.example.index.generators.HypernymIndexGenerator;
import com.example.index.generators.NashIndexGenerator;
import com.example.index.generators.NerDateIndexGenerator;
import com.example.index.generators.NerIndexGenerator;
import com.example.index.generators.NerStitchIndexGenerator;
import com.example.index.generators.POSIndexGenerator;
import com.example.index.generators.PosStitchIndexGenerator;
import com.example.index.generators.TrigramIndexGenerator;
import com.example.index.generators.UnigramIndexGenerator;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.google.common.base.Stopwatch;
import java.util.stream.Stream;


public class IndexRunner {
    private static final Logger logger = LoggerFactory.getLogger(IndexRunner.class);

    public static void main(String[] args) {
        ArgumentParser parser = ArgumentParsers.newFor("IndexRunner").build()
                .defaultHelp(true)
                .description("Create indexes from annotated database");
        parser.addArgument("-d", "--db").required(true).help("SQLite database file path");
        parser.addArgument("--index-dir").setDefault("indexes").help("Directory for storing indexes (default: 'indexes')");
        parser.addArgument("--stopwords").setDefault("stopwords.txt").help("Path to stopwords file (default: stopwords.txt)");
        parser.addArgument("--batch-size").setDefault(1000).type(Integer.class).help("Batch size for processing (default: 1000)");
        parser.addArgument("-t", "--type")
                .choices("all", "unigram", "bigram", "trigram", "dependency", "ner_date", "ner", "pos", "hypernym", "stitch", "nash")
                .setDefault("all")
                .help("Type of index to generate");
        parser.addArgument("--custom-temp-dir").dest("custom_temp_dir").type(String.class).required(false)
                .help("Path to a custom directory for temporary files during index generation.");
        parser.addArgument("--debug").action(net.sourceforge.argparse4j.impl.Arguments.storeTrue()).help("Enable debug logging to console");
        parser.addArgument("--force").action(net.sourceforge.argparse4j.impl.Arguments.storeTrue()).help("Force re-generation of indexes, overwriting existing ones.");
        try {
            Namespace ns = parser.parseArgs(args);
            if (ns.getBoolean("debug")) {
                System.setProperty("DEBUG_MODE", "true");
            }
            runIndexing(
                ns.getString("db"),
                ns.getString("index_dir"),
                ns.getString("stopwords"),
                ns.getInt("batch_size"),
                ns.getString("type"),
                ns.getString("custom_temp_dir"),
                ns.getBoolean("force")
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
            int batchSize, String indexType, String customTempDirStr, boolean force) throws Exception {
        logger.info("Starting indexing process (force={})", force);
        logger.debug("Database: {}", dbPath);
        logger.debug("Index directory: {}", indexDir);
        if (customTempDirStr != null) {
            logger.debug("Custom temporary directory: {}", customTempDirStr);
        }
        
        Path customTempPath = (customTempDirStr != null && !customTempDirStr.isBlank()) ? Path.of(customTempDirStr) : null;
        if (customTempPath != null) {
            Files.createDirectories(customTempPath);
            logger.info("Using custom temporary directory for ExternalSort: {}", customTempPath.toAbsolutePath());
        }

        // Verify the database exists and is not empty
        Path dbFilePath = Path.of(dbPath);
        if (!Files.exists(dbFilePath)) {
            throw new FileNotFoundException("Database file not found: " + dbPath);
        }
        if (Files.size(dbFilePath) == 0) {
            throw new IOException("Database file is empty. Please run the annotation stage first.");
        }
        // Create index directories based on requested type
        List<String> indexTypesToProcess = new ArrayList<>();
        if ("all".equalsIgnoreCase(indexType)) {
            indexTypesToProcess.addAll(List.of("unigram", "bigram", "trigram", "dependency", "hypernym", "ner_date", "pos", "ner", "stitch"));
        } else {
            indexTypesToProcess.add(indexType.toLowerCase());
        }

        setupIndexDirectories(indexDir, indexTypesToProcess);

        Stopwatch totalTime = Stopwatch.createStarted();
        IndexingMetrics metrics = new IndexingMetrics();
        ProgressTracker progress = new ProgressTracker();
        Path indexPath = Paths.get(indexDir);
        Files.createDirectories(indexPath);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try {
                for (String type : indexTypesToProcess) {
                    Path specificIndexDir = indexPath.resolve(type);
                    boolean generateThisIndex = true;

                    if (!force && Files.exists(specificIndexDir) && Files.isDirectory(specificIndexDir)) {
                        // Check if directory is not empty. An empty dir might mean it was created by setupIndexDirectories but generation failed/was skipped.
                        try (Stream<Path> stream = Files.list(specificIndexDir)) {
                            if (stream.findAny().isPresent()) {
                                logger.info("Index for type '{}' already exists at '{}' and --force is false. Skipping generation.", type, specificIndexDir.toAbsolutePath());
                                generateThisIndex = false;
                            } else {
                                logger.info("Index directory for type '{}' exists but is empty: '{}'. Proceeding with generation.", type, specificIndexDir.toAbsolutePath());
                            }
                        }
                    } else if (force && Files.exists(specificIndexDir)) {
                        // Pipeline.java should have handled deletion based on its own --force and index_type logic.
                        // IndexRunner will proceed assuming the path is clear or will be created by the generator.
                        logger.info("--force is true. Proceeding with generation for type '{}', assuming directory '{}' is clear or will be handled by generator.", type, specificIndexDir.toAbsolutePath());
                    }

                    if (!generateThisIndex) {
                        continue; // Skip to the next index type
                    }

                    if (type.equals("unigram")) {
                        metrics.startBatch(batchSize, "unigram");
                        try (UnigramIndexGenerator gen = new UnigramIndexGenerator(
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
                        } catch (Exception e) {
                            metrics.recordBatchFailure();
                            logger.error("Error generating unigram index: {}", e.getMessage(), e);
                        } finally {
                            progress.completeIndex();
                        }
                    }

                    if (type.equals("bigram")) {
                        metrics.startBatch(batchSize, "bigram");
                        try (BigramIndexGenerator gen = new BigramIndexGenerator(
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
                        } catch (Exception e) {
                            metrics.recordBatchFailure();
                            logger.error("Error generating bigram index: {}", e.getMessage(), e);
                        } finally {
                            progress.completeIndex();
                        }
                    }

                    if (type.equals("trigram")) {
                        metrics.startBatch(batchSize, "trigram");
                        try (TrigramIndexGenerator gen = new TrigramIndexGenerator(
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
                        } catch (Exception e) {
                            metrics.recordBatchFailure();
                            logger.error("Error generating trigram index: {}", e.getMessage(), e);
                        } finally {
                            progress.completeIndex();
                        }
                    }

                    if (type.equals("dependency")) {
                        metrics.startBatch(batchSize, "dependency");
                        try (DependencyIndexGenerator gen = new DependencyIndexGenerator(
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
                        } catch (Exception e) {
                            metrics.recordBatchFailure();
                            logger.error("Error generating dependency index: {}", e.getMessage(), e);
                        } finally {
                            progress.completeIndex();
                        }
                    }

                    if (type.equals("ner_date")) {
                        metrics.startBatch(batchSize, "ner_date");
                        try (NerDateIndexGenerator gen = new NerDateIndexGenerator(
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
                        } catch (Exception e) {
                            metrics.recordBatchFailure();
                            logger.error("Error generating NER date index: {}", e.getMessage(), e);
                        } finally {
                            progress.completeIndex();
                        }
                    }

                    if (type.equals("ner")) {
                        metrics.startBatch(batchSize, "ner");
                        try (NerIndexGenerator gen = new NerIndexGenerator(
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
                        } catch (Exception e) {
                            metrics.recordBatchFailure();
                            logger.error("Error generating NER index: {}", e.getMessage(), e);
                        } finally {
                            progress.completeIndex();
                        }
                    }

                    if (type.equals("pos")) {
                        metrics.startBatch(batchSize, "pos");
                        try (POSIndexGenerator gen = new POSIndexGenerator(
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
                        } catch (Exception e) {
                            metrics.recordBatchFailure();
                            logger.error("Error generating POS index: {}", e.getMessage(), e);
                        } finally {
                            progress.completeIndex();
                        }
                    }

                    if (type.equals("hypernym")) {
                        metrics.startBatch(batchSize, "hypernym");
                        try (HypernymIndexGenerator gen = new HypernymIndexGenerator(
                                specificIndexDir.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
                        } catch (Exception e) {
                            metrics.recordBatchFailure();
                            logger.error("Error generating hypernym index: {}", e.getMessage(), e);
                        } finally {
                            progress.completeIndex();
                        }
                    }
                }

                if (indexTypesToProcess.contains("stitch")) {
                    boolean generateStitch = true;
                    // Check a representative stitch index dir. If one exists, assume others might too.
                    // Pipeline.java handles deletion for 'all' or specific 'stitch' types.
                    Path representativeStitchDir = indexPath.resolve(DateStitchIndexGenerator.MY_INDEX_NAME); // e.g. stitch-date
                    if (!force && Files.exists(representativeStitchDir) && Files.isDirectory(representativeStitchDir)) {
                        try (Stream<Path> stream = Files.list(representativeStitchDir)) {
                            if (stream.findAny().isPresent()) {
                                logger.info("Stitch indexes (e.g., '{}') seem to exist and --force is false. Skipping stitch generation.", representativeStitchDir.toAbsolutePath());
                                generateStitch = false;
                            }
                        }
                    }

                    if (generateStitch) {
                        logger.info("Starting generation for all stitch indexes...");
                        Stopwatch stitchSw = Stopwatch.createStarted();
                        
                        // Date Stitch Index
                        DateStitchIndexGenerator dateStitchGenerator = new DateStitchIndexGenerator(indexDir, stopwordsPath, conn, progress, batchSize, customTempPath);
                        logger.info("Generating DATE stitch index at {}/{}...", indexDir, dateStitchGenerator.getIndexName());
                        dateStitchGenerator.generateIndex();
                        dateStitchGenerator.close(); // Ensure resources are released
                        logger.info("Finished DATE stitch index in {}.", stitchSw.elapsed(TimeUnit.SECONDS));
                        
                        // NER Stitch Index (excluding DATE)
                        stitchSw.reset().start();
                        NerStitchIndexGenerator nerStitchGenerator = new NerStitchIndexGenerator(indexDir, stopwordsPath, conn, progress, batchSize, customTempPath);
                        logger.info("Generating NER stitch index at {}/{}...", indexDir, nerStitchGenerator.getIndexName());
                        nerStitchGenerator.generateIndex();
                        nerStitchGenerator.close();
                        logger.info("Finished NER stitch index in {}.", stitchSw.elapsed(TimeUnit.SECONDS));

                        // POS Stitch Index
                        stitchSw.reset().start();
                        PosStitchIndexGenerator posStitchGenerator = new PosStitchIndexGenerator(indexDir, stopwordsPath, conn, progress, batchSize, customTempPath);
                        logger.info("Generating POS stitch index at {}/{}...", indexDir, posStitchGenerator.getIndexName());
                        posStitchGenerator.generateIndex();
                        posStitchGenerator.close();
                        logger.info("Finished POS stitch index in {}.", stitchSw.elapsed(TimeUnit.SECONDS));

                        metrics.addTiming("stitch_indexes_total", stitchSw.elapsed(TimeUnit.MILLISECONDS)); // This will be the time for the last one. Consider summing them or logging individually.
                        logger.info("All stitch indexes completed.");
                    }
                }

                if (indexTypesToProcess.contains("nash")) {
                    Path nashSpecificPath = indexPath.resolve("nash");
                    boolean generateNash = true;
                    if (!force && Files.exists(nashSpecificPath) && Files.isDirectory(nashSpecificPath)) {
                         try (Stream<Path> stream = Files.list(nashSpecificPath)) {
                            if (stream.findAny().isPresent()) {
                                logger.info("Index for type 'nash' already exists at '{}' and --force is false. Skipping generation.", nashSpecificPath.toAbsolutePath());
                                generateNash = false;
                            }
                        }
                    }
                    if (generateNash) {
                        metrics.startBatch(batchSize, "nash");
                        try (NashIndexGenerator gen = new NashIndexGenerator(
                                nashSpecificPath.toString(), stopwordsPath, conn, progress, batchSize, customTempPath)) {
                            gen.generateIndex();
                            metrics.recordBatchSuccess((int)gen.getDocumentCountForIndex());
                        } catch (Exception e) {
                            metrics.recordBatchFailure();
                            logger.error("Error generating nash index: {}", e.getMessage(), e);
                        } finally {
                            progress.completeIndex();
                        }
                    }
                }

            } catch (Exception e) {
                logger.error("An error occurred during index generation: {}", e.getMessage(), e);
                throw e;
            } finally {
                metrics.logIndexingMetrics();
            }
        } catch (SQLException e) {
            logger.error("SQL error during indexing: {}", e.getMessage(), e);
            throw e;
        }
        logger.info("Indexing process completed. Total time: {}.", totalTime.elapsed(TimeUnit.MILLISECONDS));
    }

    /**
     * Sets up the necessary directory structure for the specified index type.
     * If 'all' is specified, it ensures the base index directory exists.
     * If a specific type is given, it ensures that specific subdirectory exists.
     *
     * @param indexBaseDir The base directory where all indexes are stored.
     * @param indexTypes   The types of indexes being generated ("all" or specific types).
     * @throws IOException If an I/O error occurs creating the directories.
     */
    private static void setupIndexDirectories(String indexBaseDirStr, List<String> indexTypes) throws IOException {
        Path baseDir = Path.of(indexBaseDirStr);
        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir);
            logger.info("Created base index directory: {}", baseDir.toAbsolutePath());
        }

        for (String type : indexTypes) {
            if ("stitch".equalsIgnoreCase(type)) {
                // These names correspond to getIndexName() in the specific generators
                ensureDirectoryExists(baseDir.resolve("stitch-date"));
                ensureDirectoryExists(baseDir.resolve("stitch-ner"));
                ensureDirectoryExists(baseDir.resolve("stitch-pos"));
            } else if (!"all".equalsIgnoreCase(type)) { // "all" is just a meta-type, not a directory
                ensureDirectoryExists(baseDir.resolve(type));
            }
        }
    }

    private static void ensureDirectoryExists(Path dirPath) throws IOException {
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
            logger.debug("Created/ensured index directory: {}", dirPath.toAbsolutePath());
        } else {
            logger.trace("Directory already exists: {}", dirPath.toAbsolutePath());
        }
    }
}
