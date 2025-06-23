package com.example;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.IndexAccessException;
import com.example.core.IndexAccessInterface;
import com.example.index.util.SynonymManager;
import com.example.query.QueryParseException;
import com.example.query.QueryParser;
import com.example.query.QuerySemanticValidator;
import com.example.query.executor.ConditionExecutorFactory;
import com.example.query.executor.PushdownStrategy;
import com.example.query.executor.QueryExecutor;
import com.example.query.executor.QueryResultSoA;
import com.example.query.index.IndexManager;
import com.example.query.model.Query;
import com.example.query.result.TableResultService;
import com.example.query.sqlite.SqliteAccessor;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import tech.tablesaw.api.Table;

/**
 * Command-line interface for executing queries against the indexed corpus.
 * Serves as the entry point and orchestrator for the query engine.
 */
public class QueryCLI implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(QueryCLI.class);
    private final String dbFilePath;
    private final Path indexDirPath;

    // Core components
    private final QueryParser parser;
    private final QuerySemanticValidator validator;

    // Current strategy settings - mutable for interactive mode
    private String currentTemporalStrategyName;
    private PushdownStrategy currentPushdownStrategy;
    private String currentStitchStrategyName;

    // Current output settings - mutable for interactive mode
    private Optional<String> currentExportFormat = Optional.empty();
    private Optional<String> currentExportFilename = Optional.empty();

    // Shared components for interactive mode
    private IndexManager sharedIndexManager;
    private TableResultService sharedTableResultService;
    private SynonymManager sharedSynonymManager;
    private final boolean interactiveMode;

    /**
     * Creates a new QueryCLI instance.
     *
     * @param dbFilePath The path to the project's SQLite database file.
     * @param indexDirPath The path to the directory containing project indexes.
     * @param initialTemporalStrategy The initial temporal execution strategy ("nash" or "naive")
     * @param initialPushdownStrategy The initial pushdown strategy
     * @param initialStitchStrategy The initial stitch execution strategy ("none" or "optimized")
     * @param initialExportFormat Optional initial export format
     * @param initialExportFilename Optional initial export filename
     * @param interactiveMode True if running in interactive mode, false for single query execution.
     */
    public QueryCLI(String dbFilePath, Path indexDirPath, String initialTemporalStrategy, PushdownStrategy initialPushdownStrategy, String initialStitchStrategy, Optional<String> initialExportFormat, Optional<String> initialExportFilename, boolean interactiveMode) {
        this.dbFilePath = dbFilePath;
        this.indexDirPath = indexDirPath;
        this.parser = new QueryParser();
        this.validator = new QuerySemanticValidator();

        this.currentTemporalStrategyName = initialTemporalStrategy;
        this.currentPushdownStrategy = initialPushdownStrategy;
        this.currentStitchStrategyName = initialStitchStrategy;
        this.currentExportFormat = initialExportFormat;
        this.currentExportFilename = initialExportFilename;

        this.interactiveMode = interactiveMode;

        if (this.interactiveMode) {
            initializeSharedComponents();
        }

        logger.info("Initialized QueryCLI. DB file: {}, Index dir: {}. Interactive: {}", dbFilePath, indexDirPath, interactiveMode);
        logger.info("Initial Strategies - Temporal: {}, Pushdown: {}, Stitch: {}", currentTemporalStrategyName, currentPushdownStrategy, currentStitchStrategyName);
        initialExportFormat.ifPresent(format -> logger.info("Initial Export: {} to {}", format, initialExportFilename.orElse("N/A")));
    }

    private String deriveProjectNameFromIndexPath(Path indexPath) {
        if (indexPath == null || indexPath.getFileName() == null) {
            logger.warn("Could not derive project name from index path: {}. Defaulting to 'unknown_project'.", indexPath);
            return "unknown_project";
        }
        String fileName = indexPath.getFileName().toString();
        // Remove common suffixes like "_indexes" or "_indices"
        fileName = fileName.replaceAll("(?i)_indexes$", "").replaceAll("(?i)_indices$", "");
        return fileName;
    }

    private void initializeSharedComponents() {
        try {
            logger.info("Initializing shared components for interactive mode...");
            SqliteAccessor.initialize(this.dbFilePath);
            this.sharedTableResultService = new TableResultService(this.dbFilePath);

            String projectName = deriveProjectNameFromIndexPath(this.indexDirPath);
            // Maximal preloading query for IndexManager initialization
            // FROM clause uses the derived project name.
            String maxPreloadQueryStr = String.format("SELECT DOCUMENT_ID FROM %s WHERE CONTAINS('preload_a preload_b preload_c') AND NER(PERSON) AND POS(NN) AND DEPENDS('dep', 'rel', 'dep_rel') AND DATE(= 2000-01-01)", projectName);

            Query maxPreloadQuery = this.parser.parse(maxPreloadQueryStr);
            // No validation needed for this internal preload query.

            // Use "nash" and "optimized" for maximal preloading as per design
            this.sharedIndexManager = new IndexManager(this.indexDirPath, projectName, maxPreloadQuery, "nash", "optimized");
            this.sharedSynonymManager = this.sharedIndexManager.getSynonymManager();
            logger.info("Shared components initialized successfully (IndexManager, TableResultService, SynonymManager). Project: '{}'", projectName);

        } catch (QueryParseException e) {
            logger.error("Failed to parse maximal preloading query during shared component initialization: {}", e.getMessage(), e);
            System.err.println("ERROR: Critical failure initializing shared components (preload query parse): " + e.getMessage());
            // sharedIndexManager, etc., will remain null. Query execution will fail later.
        } catch (IndexAccessException e) {
            logger.error("Failed to initialize IndexManager or SynonymManager for shared components: {}", e.getMessage(), e);
            System.err.println("ERROR: Critical failure initializing shared components (IndexManager/SynonymManager): " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected critical error during shared component initialization: {}", e.getMessage(), e);
            System.err.println("ERROR: Unexpected critical failure during shared component initialization: " + e.getMessage());
        }
    }

    /**
     * Executes a query string using current strategy and output settings.
     *
     * @param queryStr The query string to execute
     */
    public void executeQuery(String queryStr) {
        long startTimeNs = System.nanoTime();

        IndexManager localIndexManager = null; // For non-interactive mode or if shared init failed

        try {
            Query query = parser.parse(queryStr);
            validator.validate(query, Optional.empty());

            String projectName = query.source();
            logger.debug("Using project name from FROM clause: {}", projectName);
            logger.debug("Using index base directory: {}", this.indexDirPath);

            if (!new java.io.File(this.dbFilePath).exists()) {
                String errorMessage = String.format("Database file not found: %s.", this.dbFilePath);
                logger.error(errorMessage);
                System.err.println("Error: " + errorMessage);
                System.err.println("Ensure the specified database file exists.");
                return;
            }

            TableResultService currentTableResultService;
            SynonymManager currentSynonymManager;
            IndexManager currentIndexManagerToUse; // The IndexManager instance for this execution

            if (this.interactiveMode) {
                if (this.sharedIndexManager == null || this.sharedTableResultService == null || this.sharedSynonymManager == null) {
                    logger.error("Interactive mode active, but shared components are not initialized. Cannot execute query '{}'.", queryStr);
                    System.err.println("Error: Shared components not ready. Query execution aborted.");
                    // Still print benchmark times to not hang benchmark.py, even if zero/error.
                    return; // Exit executeQuery early
                }
                currentTableResultService = this.sharedTableResultService;
                currentSynonymManager = this.sharedSynonymManager;
                currentIndexManagerToUse = this.sharedIndexManager; // Use shared, not closed here
                logger.debug("Using shared IndexManager for interactive query.");
            } else {
                // Non-interactive: create all components locally for this single query
                SqliteAccessor.initialize(this.dbFilePath); // Initialize for this specific call
                currentTableResultService = new TableResultService(this.dbFilePath);
                // Create IndexManager with current strategy settings from CLI args
                localIndexManager = new IndexManager(this.indexDirPath, projectName, query, this.currentTemporalStrategyName, this.currentStitchStrategyName);
                currentIndexManagerToUse = localIndexManager; // Will be closed in finally
                currentSynonymManager = currentIndexManagerToUse.getSynonymManager();
                logger.debug("Created local IndexManager for single-shot query.");
            }
            logger.info("Using database at: {}", this.dbFilePath); // Re-log for clarity if needed

                // Add warning for stitch strategy and granularity mismatch
            Query.Granularity queryGranularity = query.granularity();
            if ("optimized".equalsIgnoreCase(this.currentStitchStrategyName) && queryGranularity != Query.Granularity.SENTENCE) {
                String warningMessage = String.format(
                    "Warning: Stitch optimization ('optimized') is active with strategy '%s', but it is primarily designed for SENTENCE granularity. Current query granularity is %s. Results might not be optimal.",
                    this.currentStitchStrategyName, queryGranularity
                );
                    logger.warn(warningMessage);
                    System.err.println(warningMessage);
                }

            ConditionExecutorFactory factory = new ConditionExecutorFactory(currentSynonymManager, this.currentStitchStrategyName, queryGranularity);
            factory.setTemporalStrategy(this.currentTemporalStrategyName);
            logger.debug("ConditionExecutorFactory configured with T:{}, S:{}, Granularity:{}", this.currentTemporalStrategyName, this.currentStitchStrategyName, queryGranularity);

            QueryExecutor queryExecutor = new QueryExecutor(currentTableResultService, this.currentStitchStrategyName, currentSynonymManager, factory);
            queryExecutor.setPushdownStrategy(this.currentPushdownStrategy);
            logger.debug("QueryExecutor configured with P:{}", this.currentPushdownStrategy);

                int windowSize = query.granularitySize().orElse(0);
                logger.info("Query granularity: {} with size: {}", queryGranularity, windowSize);

            QueryResultSoA execResult = queryExecutor.execute(query, currentIndexManagerToUse);

                Table resultTable;
                int matchCount;
                String matchUnit;

                if (execResult != null) {
                    matchCount = execResult.size();
                matchUnit = (query.joinSteps().isEmpty() ?
                            (queryGranularity == Query.Granularity.DOCUMENT ? "documents" : "sentences") :
                            "conceptual joined rows");
                logger.info("Query executed, found {} matching {} (granularity: {})", matchCount, matchUnit, queryGranularity);

                Map<String, IndexAccessInterface> allIndexes = currentIndexManagerToUse.getAllIndexes();
                resultTable = currentTableResultService.generateTable(query, execResult, allIndexes);
                } else {
                logger.error("Query execution returned a null QueryResultSoA for query: {}", queryStr);
                    System.err.println("Error: Query execution resulted in an unexpected null result.");
                resultTable = Table.create("Empty Result - Execution Error");
                    matchCount = 0;
                    matchUnit = "results";
                }

            // Use currentExportFormat and currentExportFilename from instance fields
            if (this.currentExportFormat.isPresent() && this.currentExportFilename.isPresent()) {
                String format = this.currentExportFormat.get();
                String filename = this.currentExportFilename.get();
                    logger.info("Exporting results to {} format in file: {}", format, filename);
                    try {
                    currentTableResultService.exportTable(resultTable, format, filename);
                        System.out.println("Results exported to " + filename);
                    } catch (IOException e) {
                        logger.error("Error exporting results: {}", e.getMessage());
                        System.err.println("Error exporting results: " + e.getMessage());
                    }
                } else {
                String formattedResults = currentTableResultService.formatTable(resultTable);
                    System.out.println(formattedResults);
            }

        } catch (QueryParseException e) {
            logger.error("Query parse error: {}", e.getMessage());
            System.err.println("Error parsing query: " + e.getMessage());
        } catch (IndexAccessException e) { // Catch errors from IndexManager creation in non-interactive
            logger.error("Index access error during query execution: {}", e.getMessage(), e);
            System.err.println("Error accessing index: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error executing query: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
        } finally {
            if (localIndexManager != null) { // Only close if it was locally created
                try {
                    localIndexManager.close();
                    logger.debug("Closed locally created IndexManager.");
                } catch (IndexAccessException e) {
                    logger.error("Error closing locally created IndexManager: {}", e.getMessage(), e);
                }
            }
            long endTimeNs = System.nanoTime();
            double executionTimeMs = (endTimeNs - startTimeNs) / 1_000_000.0;
            System.out.printf("BENCHMARK_EXECUTION_TIME_MS: %.3f%n", executionTimeMs);
        }
    }

    // Setters for interactive mode
    public void setCurrentTemporalStrategyName(String name) { this.currentTemporalStrategyName = name; }
    public void setCurrentPushdownStrategy(PushdownStrategy strategy) { this.currentPushdownStrategy = strategy; }
    public void setCurrentStitchStrategyName(String name) { this.currentStitchStrategyName = name; }
    public void setCurrentExportFormat(Optional<String> format) { this.currentExportFormat = format; }
    public void setCurrentExportFilename(Optional<String> filename) { this.currentExportFilename = filename; }

    // Getters for ACK messages
    public String getCurrentTemporalStrategyName() { return currentTemporalStrategyName; }
    public PushdownStrategy getCurrentPushdownStrategy() { return currentPushdownStrategy; }
    public String getCurrentStitchStrategyName() { return currentStitchStrategyName; }

    @Override
    public void close() throws IndexAccessException {
        logger.info("Closing QueryCLI resources...");
        if (sharedIndexManager != null) {
            try {
                sharedIndexManager.close();
                logger.info("Shared IndexManager closed successfully.");
            } catch (IndexAccessException e) {
                logger.error("Failed to close shared IndexManager: {}", e.getMessage(), e);
                throw e; // Propagate to signal error during close
            } finally {
                sharedIndexManager = null;
                sharedSynonymManager = null; // Was part of sharedIndexManager
                sharedTableResultService = null; // Not AutoCloseable, just dereference
            }
        }
    }

    private boolean sharedComponentsReady() {
        return this.sharedIndexManager != null && this.sharedTableResultService != null && this.sharedSynonymManager != null;
    }

    /**
     * Main entry point for the CLI.
     *
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        ArgumentParser cliArgParser = ArgumentParsers.newFor("QueryCLI").build()
                .defaultHelp(true)
                .description("Execute queries against indexed projects. Queries specify the project via the FROM clause.");

        cliArgParser.addArgument("--db-file")
                .required(true)
                .help("Path to the project's SQLite database file.");

        cliArgParser.addArgument("--index-dir")
                .required(true)
                .help("Path to the directory containing project indexes.");

        cliArgParser.addArgument("--export")
                .help("Export results to a file in the specified format: csv:filename.csv, json:filename.json, or html:filename.html. Sets initial export for single query or interactive mode.");

        cliArgParser.addArgument("--temporal-strategy")
                .choices("nash", "naive").setDefault("naive")
                .help("Specify the initial temporal execution strategy (nash or naive).");

        cliArgParser.addArgument("--pushdown-strategy")
                .choices("none", "optimized").setDefault("optimized") // Default changed to optimized as per typical usage
                .type(String.class)
                .help("Specify the initial predicate pushdown strategy (none or optimized). Default: optimized.");

        cliArgParser.addArgument("--stitch-strategy")
                .choices("none", "optimized").setDefault("none")
                .help("Specify the initial stitch execution strategy (none or optimized).");

        cliArgParser.addArgument("query")
                .nargs("?")
                .help("The query string to execute. If not provided, enters interactive mode.");

        try {
            Namespace ns = cliArgParser.parseArgs(args);
            String dbFile = ns.getString("db_file");
            Path indexDir = Path.of(ns.getString("index_dir")); // Use Path directly
            String exportArg = ns.getString("export");
            String initialTemporalStrategy = ns.getString("temporal_strategy");
            PushdownStrategy initialPushdownStrategy = PushdownStrategy.fromString(ns.getString("pushdown_strategy"));
            String initialStitchStrategy = ns.getString("stitch_strategy");
            String queryStr = ns.getString("query");

            Optional<String> initialExportFormat = Optional.empty();
            Optional<String> initialExportFilename = Optional.empty();

            if (exportArg != null) {
                String[] parts = exportArg.split(":", 2);
                if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                    initialExportFormat = Optional.of(parts[0].toLowerCase());
                    initialExportFilename = Optional.of(parts[1]);
                } else {
                    System.err.println("Invalid --export format. Use format:filename (e.g., csv:output.csv). Export disabled.");
                }
            }

            boolean interactive = (queryStr == null);

            try (QueryCLI cli = new QueryCLI(dbFile, indexDir, initialTemporalStrategy, initialPushdownStrategy, initialStitchStrategy, initialExportFormat, initialExportFilename, interactive)) {
                if (interactive) {
                    if (!cli.sharedComponentsReady()) {
                        System.err.println("FATAL: Shared components failed to initialize. Interactive mode cannot start.");
                        return; // Exit if shared components are not ready
                    }

                Scanner scanner = new Scanner(System.in);
                    System.out.println("QueryCLI Interactive Mode");
                    System.out.println("Type 'EXIT' or 'QUIT' to leave.");
                    System.out.println("Commands: SET STRATEGY temporal=<val> pushdown=<val> stitch=<val>");
                    System.out.println("          SET OUTPUT <format> <filename> | SET OUTPUT NONE");
                    System.out.println("Current strategies: T:" + cli.getCurrentTemporalStrategyName() +
                                       " P:" + cli.getCurrentPushdownStrategy().name().toLowerCase() +
                                       " S:" + cli.getCurrentStitchStrategyName());
                    cli.currentExportFormat.ifPresentOrElse(
                        f -> System.out.println("Current output: " + f + " to " + cli.currentExportFilename.orElse("N/A")),
                        () -> System.out.println("Current output: Console")
                    );

                while (true) {
                    System.out.println(); // Ensures a blank line before the prompt
                    System.out.print("Query>"); // Print the prompt text itself
                    System.out.println();   // Crucially, print a newline *after* the prompt text
                    System.out.flush();     // Ensure it's sent

                    String inputLine = scanner.nextLine().trim();

                    if (inputLine.equalsIgnoreCase("EXIT") || inputLine.equalsIgnoreCase("QUIT")) {
                        break;
                    }

                    if (inputLine.matches("(?i)^SET\\s+STRATEGY.*")) {
                        Pattern pattern = Pattern.compile("(\\w+)=(\\S+)"); // Value can be non-alphanum now
                        Matcher matcher = pattern.matcher(inputLine);
                        boolean strategyUpdated = false;
                        while (matcher.find()) {
                            String key = matcher.group(1).toLowerCase();
                            String value = matcher.group(2); // Keep case for filenames, strategy names are case-insensitive handled by enum/logic
                            try {
                                switch (key) {
                                    case "temporal":
                                        if (Arrays.asList("nash", "naive").contains(value.toLowerCase())) {
                                           cli.setCurrentTemporalStrategyName(value.toLowerCase());
                                           strategyUpdated = true;
                                        } else {
                                            System.err.println("Invalid temporal strategy value: " + value + ". Must be 'nash' or 'naive'.");
                                        }
                                        break;
                                    case "pushdown":
                                        cli.setCurrentPushdownStrategy(PushdownStrategy.fromString(value)); // fromString handles invalid
                                        strategyUpdated = true;
                                        break;
                                    case "stitch":
                                         if (Arrays.asList("none", "optimized").contains(value.toLowerCase())) {
                                            cli.setCurrentStitchStrategyName(value.toLowerCase());
                                            strategyUpdated = true;
                                         } else {
                                             System.err.println("Invalid stitch strategy value: " + value + ". Must be 'none' or 'optimized'.");
                                         }
                                        break;
                                    default:
                                        System.err.println("Unknown strategy key: " + key);
                                }
                            } catch (IllegalArgumentException e) {
                                 System.err.println("Invalid value for strategy " + key + ": " + value + " (" + e.getMessage() + ")");
                            }
                        }
                        if (strategyUpdated) {
                            // Strategy is set, QueryCLI will print prompt at end of loop iteration.
                        } else if (!inputLine.trim().equalsIgnoreCase("SET STRATEGY")) { // Avoid error if just "SET STRATEGY"
                            System.err.println("No valid strategy key-value pairs found in SET STRATEGY command. Format: key=value ...");
                        }

                    } else if (inputLine.matches("(?i)^SET\\s+OUTPUT.*")) {
                        String outputCmdArgs = inputLine.substring(inputLine.toLowerCase().indexOf("output") + "output".length()).trim();
                        if (outputCmdArgs.equalsIgnoreCase("none")) {
                            cli.setCurrentExportFormat(Optional.empty());
                            cli.setCurrentExportFilename(Optional.empty());
                        } else {
                            String[] parts = outputCmdArgs.split("\\s+", 2);
                            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                                String format = parts[0].toLowerCase();
                                if (!Arrays.asList("csv", "json", "html").contains(format)) {
                                    System.err.println("Unsupported export format: " + format + ". Supported: csv, json, html.");
                                } else {
                                    cli.setCurrentExportFormat(Optional.of(format));
                                    cli.setCurrentExportFilename(Optional.of(parts[1]));
                                }
                            } else {
                                System.err.println("Invalid SET OUTPUT command. Use 'SET OUTPUT <format> <filename>' or 'SET OUTPUT NONE'.");
                            }
                        }

                    } else if (!inputLine.isEmpty()) {
                        cli.executeQuery(inputLine);
                    }
                }
                scanner.close();
                System.out.println("Exiting QueryCLI interactive mode.");

            } else { // Single query mode
                // Initial export settings from CLI args are already set in currentExportFormat/Filename by constructor
                // No need to set them again here.
                cli.executeQuery(queryStr);
            }
        } // QueryCLI.close() called here by try-with-resources

        } catch (ArgumentParserException e) {
            cliArgParser.handleError(e);
            System.exit(1);
        } catch (IndexAccessException e) { // Catch from QueryCLI.close()
            logger.error("Error during QueryCLI close: {}", e.getMessage(), e);
            System.err.println("Error closing QueryCLI resources: " + e.getMessage());
            System.exit(1); // Exit if closing fails, as resources might be unstable
        } catch (Exception e) { // Catch any other unexpected top-level errors
            logger.error("An unexpected error occurred in main: {}", e.getMessage(), e);
            System.err.println("An unexpected error occurred: " + e.getMessage());
            System.exit(1);
        }
    }
}