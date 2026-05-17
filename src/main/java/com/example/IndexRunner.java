package com.example;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.rocksdb.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccess;
import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.index.RocksDBConfig;
import com.example.index.generators.BigramIndexGenerator;
import com.example.index.generators.DependencyIndexGenerator;

import com.example.index.generators.NerDateIndexGenerator;
import com.example.index.generators.NerIndexGenerator;
import com.example.index.generators.POSIndexGenerator;
import com.example.index.generators.TrigramIndexGenerator;
import com.example.index.generators.UnigramIndexGenerator;
import com.example.index.generators.stitch.BigramDateStitchGenerator;
import com.example.index.generators.stitch.BigramNerStitchGenerator;
import com.example.index.generators.stitch.BigramPosStitchGenerator;
import com.example.index.generators.stitch.TrigramDateStitchGenerator;
import com.example.index.generators.stitch.TrigramNerStitchGenerator;
import com.example.index.generators.stitch.TrigramPosStitchGenerator;
import com.example.index.generators.stitch.UnigramDateStitchGenerator;
import com.example.index.generators.stitch.UnigramNerStitchGenerator;
import com.example.index.generators.stitch.UnigramPosStitchGenerator;
import com.example.index.util.SynonymManager;
import com.example.logging.IndexingMetrics;
import com.example.logging.ProgressTracker;
import com.google.common.base.Stopwatch;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

public class IndexRunner {
    private static final Logger logger = LoggerFactory.getLogger(IndexRunner.class);
    private static final String GLOBAL_VALUE_LOOKUP_DB_NAME = "global_values_lookup.db";

    private static final List<String> ALL_STITCH_INDEX_TYPES = List.of(
            UnigramDateStitchGenerator.MY_INDEX_NAME,
            UnigramNerStitchGenerator.MY_INDEX_NAME,
            // UnigramPosStitchIndexGenerator.MY_INDEX_NAME,
            BigramDateStitchGenerator.MY_INDEX_NAME,
            BigramNerStitchGenerator.MY_INDEX_NAME,
            // BigramPosStitchGenerator.MY_INDEX_NAME,
            TrigramDateStitchGenerator.MY_INDEX_NAME,
            TrigramNerStitchGenerator.MY_INDEX_NAME
    // TrigramPosStitchGenerator.MY_INDEX_NAME
    );

    private static final List<String> ALL_NON_STITCH_INDEX_TYPES = List.of(
            "unigram", "bigram", "trigram", "dependency",
            "ner_date", "pos", "ner");

    public static void main(String[] args) {
        ArgumentParser parser = ArgumentParsers.newFor("IndexRunner").build()
                .defaultHelp(true)
                .description("Create indexes from annotated database");
        parser.addArgument("-d", "--db").required(true).help("SQLite database file path");
        parser.addArgument("--index-dir").setDefault("indexes")
                .help("Directory for storing indexes (default: 'indexes')");
        parser.addArgument("--stopwords").setDefault("stopwords.txt")
                .help("Path to stopwords file (default: stopwords.txt)");
        parser.addArgument("--batch-size").setDefault(1000).type(Integer.class)
                .help("Batch size for processing (default: 1000)");

        List<String> allPossibleTypes = new ArrayList<>();
        allPossibleTypes.add("all");
        allPossibleTypes.add("stitches");
        allPossibleTypes.addAll(ALL_NON_STITCH_INDEX_TYPES);
        allPossibleTypes.addAll(ALL_STITCH_INDEX_TYPES);

        parser.addArgument("-t", "--type")
                .choices(allPossibleTypes.toArray(new String[0]))
                .setDefault(List.of("all"))
                .nargs("+")
                .help("Type of index to generate (can specify multiple, space-separated). " +
                        "'all' processes all known types. " +
                        "'stitches' processes all stitch-combination types.");
        parser.addArgument("--custom-temp-dir").dest("custom_temp_dir").type(String.class).required(false)
                .help("Path to a custom directory for temporary files during index generation.");
        parser.addArgument("--force").action(Arguments.storeTrue())
                .help("Force re-generation of indexes, overwriting existing ones.");
        try {
            Namespace ns = parser.parseArgs(args);
            runIndexing(
                    ns.getString("db"),
                    ns.getString("index_dir"),
                    ns.getString("stopwords"),
                    ns.getInt("batch_size"),
                    ns.getList("type"),
                    ns.getString("custom_temp_dir"),
                    ns.getBoolean("force"));
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        } catch (Exception e) {
            logger.error("Error generating index: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    public static void runIndexing(String dbPath, String indexDir, String stopwordsPath,
            int batchSize, List<String> cliRequestedIndexTypes, String customTempDirStr, boolean force)
            throws Exception {

        Path customTempPath = (customTempDirStr != null && !customTempDirStr.isBlank()) ? Path.of(customTempDirStr)
                : null;
        if (customTempPath != null) {
            if (!Files.exists(customTempPath))
                Files.createDirectories(customTempPath);
            logger.debug("Using custom temporary directory for index generation: {}", customTempPath.toAbsolutePath());
        }

        Path dbFilePath = Path.of(dbPath);
        if (!Files.exists(dbFilePath)) {
            throw new FileNotFoundException("Database file not found: " + dbPath);
        }
        if (Files.size(dbFilePath) == 0) {
            throw new IOException("Database file is empty. Please run the annotation stage first.");
        }

        Set<String> typesBeingBuilt = new LinkedHashSet<>();
        boolean expandAll = cliRequestedIndexTypes.contains("all");
        boolean expandStitches = cliRequestedIndexTypes.contains("stitches");

        if (expandAll) {
            typesBeingBuilt.addAll(ALL_NON_STITCH_INDEX_TYPES);
            typesBeingBuilt.addAll(ALL_STITCH_INDEX_TYPES);
        } else {
            for (String requestedType : cliRequestedIndexTypes) {
                if (requestedType.equals("stitches")) {
                    typesBeingBuilt.addAll(ALL_STITCH_INDEX_TYPES);
                } else {
                    typesBeingBuilt.add(requestedType);
                }
            }
        }

        Set<String> indexTypesToProcess = typesBeingBuilt.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        logger.info("Effective index types to process by IndexRunner: {}", indexTypesToProcess);
        setupIndexDirectories(indexDir, new ArrayList<>(indexTypesToProcess), force);

        Stopwatch totalTime = Stopwatch.createStarted();
        IndexingMetrics metrics = new IndexingMetrics();
        ProgressTracker progress = new ProgressTracker();
        Path indexPath = Paths.get(indexDir);
        Files.createDirectories(indexPath);

        Path globalLookupDbPath = indexPath.resolve(GLOBAL_VALUE_LOOKUP_DB_NAME);
        SynonymManager sharedSynonymManager = null;

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try {
                sharedSynonymManager = new SynonymManager(globalLookupDbPath);
                logger.info("Shared SynonymManager initialized at: {}", globalLookupDbPath);

                for (String type : indexTypesToProcess) {
                    Path specificIndexDir = indexPath.resolve(type);
                    boolean generateThisIndex = true;

                    if (!force && Files.exists(specificIndexDir) && Files.isDirectory(specificIndexDir)) {
                        try (Stream<Path> stream = Files.list(specificIndexDir)) {
                            if (stream.findAny().isPresent()) {
                                logger.info(
                                        "Index for type '{}' already exists at '{}' and --force is false. Skipping generation.",
                                        type, specificIndexDir.toAbsolutePath());
                                generateThisIndex = false;
                            } else {
                                logger.debug(
                                        "Index directory for type '{}' exists but is empty: '{}'. Proceeding with generation.",
                                        type, specificIndexDir.toAbsolutePath());
                            }
                        }
                    } else if (force && Files.exists(specificIndexDir)) {
                        ensureDirectoryExists(specificIndexDir, true);
                        if (!Files.exists(specificIndexDir))
                            Files.createDirectories(specificIndexDir);
                    }

                    if (!generateThisIndex) {
                        continue;
                    }

                    try (Options options = RocksDBConfig.createOptimizedOptions();
                            IndexAccessInterface indexAccess = new IndexAccess(specificIndexDir, type, options,
                                    false)) {

                        if (type.equals("unigram")) {
                            metrics.startIndexProcessing(type);
                            UnigramIndexGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new UnigramIndexGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating unigram index: {}", e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }
                        if (type.equals("bigram")) {
                            metrics.startIndexProcessing(type);
                            BigramIndexGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new BigramIndexGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating bigram index: {}", e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }

                        if (type.equals("trigram")) {
                            metrics.startIndexProcessing(type);
                            TrigramIndexGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new TrigramIndexGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating trigram index: {}", e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }

                        if (type.equals("dependency")) {
                            if (!tableExistsAndHasRows(conn, "dependencies")) {
                                logger.warn(
                                        "Dependencies table not found or empty in the database. Skipping dependency index generation. (Dependency parsing may be disabled in CoreNLPConfig)");
                                continue;
                            }
                            metrics.startIndexProcessing(type);
                            DependencyIndexGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new DependencyIndexGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating dependency index: {}", e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }

                        if (type.equals("ner_date")) {
                            metrics.startIndexProcessing(type);
                            NerDateIndexGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new NerDateIndexGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating NER date index: {}", e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }

                        if (type.equals("ner")) {
                            metrics.startIndexProcessing(type);
                            NerIndexGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new NerIndexGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                        sharedSynonymManager);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating NER index: {}", e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }

                        if (type.equals("pos")) {
                            metrics.startIndexProcessing(type);
                            POSIndexGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new POSIndexGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                        sharedSynonymManager);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating POS index: {}", e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }

                        if (type.equals(UnigramDateStitchGenerator.MY_INDEX_NAME)) {
                            metrics.startIndexProcessing(type);
                            UnigramDateStitchGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new UnigramDateStitchGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                        sharedSynonymManager);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating {} index: {}", type, e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }

                        if (type.equals(UnigramNerStitchGenerator.MY_INDEX_NAME)) {
                            metrics.startIndexProcessing(type);
                            UnigramNerStitchGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new UnigramNerStitchGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                        sharedSynonymManager);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating {} index: {}", type, e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }

                        if (type.equals(UnigramPosStitchGenerator.MY_INDEX_NAME)) {
                            metrics.startIndexProcessing(type);
                            UnigramPosStitchGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new UnigramPosStitchGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                        sharedSynonymManager);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating {} index: {}", type, e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }

                        if (type.equals(BigramPosStitchGenerator.MY_INDEX_NAME)) {
                            metrics.startIndexProcessing(type);
                            BigramPosStitchGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new BigramPosStitchGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                        sharedSynonymManager);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating {} index: {}", type, e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }
                        if (type.equals(TrigramPosStitchGenerator.MY_INDEX_NAME)) {
                            metrics.startIndexProcessing(type);
                            TrigramPosStitchGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new TrigramPosStitchGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                        sharedSynonymManager);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating {} index: {}", type, e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }

                        if (type.equals(BigramNerStitchGenerator.MY_INDEX_NAME)) {
                            metrics.startIndexProcessing(type);
                            BigramNerStitchGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new BigramNerStitchGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                        sharedSynonymManager);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating {} index: {}", type, e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }
                        if (type.equals(TrigramNerStitchGenerator.MY_INDEX_NAME)) {
                            metrics.startIndexProcessing(type);
                            TrigramNerStitchGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new TrigramNerStitchGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                        sharedSynonymManager);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating {} index: {}", type, e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }

                        if (type.equals(BigramDateStitchGenerator.MY_INDEX_NAME)) {
                            metrics.startIndexProcessing(type);
                            BigramDateStitchGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new BigramDateStitchGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                        sharedSynonymManager);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating {} index: {}", type, e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }
                        if (type.equals(TrigramDateStitchGenerator.MY_INDEX_NAME)) {
                            metrics.startIndexProcessing(type);
                            TrigramDateStitchGenerator gen = null;
                            long itemsWritten = -1;
                            try {
                                gen = new TrigramDateStitchGenerator(
                                        indexAccess, stopwordsPath, conn, progress, batchSize, customTempPath,
                                        sharedSynonymManager);
                                progress.startIndex(type, gen.getDocumentCountForIndex());
                                gen.generateIndex();
                                itemsWritten = gen.getTotalTermsWrittenToIndex();
                            } catch (Exception e) {
                                logger.error("Error generating {} index: {}", type, e.getMessage(), e);
                            } finally {
                                metrics.endIndexProcessing(type, itemsWritten);
                                progress.completeIndex();
                                if (gen != null)
                                    gen.close();
                            }
                        }

                    } catch (IndexAccessException e) {
                        logger.error("Failed to initialize IndexAccess for type {}: {}", type, e.getMessage(), e);
                        metrics.endIndexProcessing(type, -1);
                        progress.completeIndex();
                    }
                }

            } catch (Exception e) {
                logger.error("An error occurred during index generation: {}", e.getMessage(), e);
                throw e;
            } finally {
                metrics.logOverallMetrics();
                if (sharedSynonymManager != null) {
                    try {
                        sharedSynonymManager.close();
                        logger.info("Shared SynonymManager closed successfully.");
                    } catch (Exception e) {
                        logger.error("Error closing shared SynonymManager: {}", e.getMessage(), e);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("SQL error during indexing: {}", e.getMessage(), e);
            throw e;
        }
        logger.info("Indexing process completed. Total time: {}s", totalTime.stop().elapsed(TimeUnit.SECONDS));
    }

    private static void setupIndexDirectories(String indexBaseDirStr, List<String> indexTypesToEnsure, boolean force)
            throws IOException {
        Path baseDir = Path.of(indexBaseDirStr);
        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir);
            logger.debug("Created base index directory: {}", baseDir.toAbsolutePath());
        }

        for (String type : indexTypesToEnsure) {
            if (!"all".equalsIgnoreCase(type)) {
                ensureDirectoryExists(baseDir.resolve(type), force);
            }
        }
    }

    private static void ensureDirectoryExists(Path dirPath, boolean force) throws IOException {
        if (force && Files.exists(dirPath) && Files.isDirectory(dirPath)) {
            logger.debug("--force: Cleaning up existing directory: {}", dirPath.toAbsolutePath());
            try (Stream<Path> walk = Files.walk(dirPath)) {
                walk.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(file -> {
                            try {
                                if (!file.delete()) {
                                    if (file.exists()) {
                                        logger.warn("Could not delete file/directory during force cleanup: {}",
                                                file.getAbsolutePath());
                                    }
                                }
                            } catch (SecurityException se) {
                                logger.warn(
                                        "SecurityException while trying to delete file/directory during force cleanup: {}. Error: {}",
                                        file.getAbsolutePath(), se.getMessage());
                            }
                        });
            } catch (IOException e) {
                logger.warn(
                        "Failed to walk directory {} during force cleanup: {}. Attempting to delete directory root.",
                        dirPath.toAbsolutePath(), e.getMessage());
            }
            if (Files.exists(dirPath)) {
                if (!Files.deleteIfExists(dirPath)) {
                    logger.warn(
                            "Could not delete directory {} itself after attempting to delete its contents during force cleanup.",
                            dirPath.toAbsolutePath());
                } else {
                    logger.debug("Successfully deleted directory root {} after content cleanup.",
                            dirPath.toAbsolutePath());
                }
            }
        }
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
            logger.debug("Created/ensured index directory exists: {}", dirPath.toAbsolutePath());
        }
    }

    /**
     * Checks whether a table exists in the SQLite database and contains at least
     * one row.
     *
     * @param conn      The database connection
     * @param tableName The name of the table to check
     * @return true if the table exists and has rows, false otherwise
     */
    private static boolean tableExistsAndHasRows(Connection conn, String tableName) {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + tableName + "')")) {
            if (rs.next() && rs.getInt(1) == 1) {
                try (Statement countStmt = conn.createStatement();
                        ResultSet countRs = countStmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                    return countRs.next() && countRs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            logger.warn("Could not check for table {}: {}", tableName, e.getMessage());
        }
        return false;
    }
}
