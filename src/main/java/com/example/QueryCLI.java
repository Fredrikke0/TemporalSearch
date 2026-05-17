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
import com.example.project.ProjectManifest;
import com.example.query.QueryParseException;
import com.example.query.QueryParser;
import com.example.query.QuerySemanticValidator;
import com.example.query.executor.ConditionExecutorFactory;
import com.example.query.executor.PushdownStrategy;
import com.example.query.executor.QueryExecutor;
import com.example.query.executor.CellResult;
import com.example.query.index.IndexManager;
import com.example.query.model.Query;
import com.example.query.result.ResultMaterializer;
import com.example.query.result.Row;
import com.example.query.result.Schema;
import com.example.query.result.SortSpec;
import com.example.query.result.Table;
import com.example.query.result.Aggregators;
import com.example.query.model.SelectedCount;
import com.example.query.model.SelectedCount.CountAll;
import com.example.query.model.SelectedCount.CountUnique;
import com.example.query.model.SelectedCount.CountDocuments;
import com.example.query.sqlite.SqliteAccessor;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import one.profiler.AsyncProfiler;

/**
 * Command-line interface for executing queries against the indexed corpus.
 * Serves as the entry point and orchestrator for the query engine.
 */
public class QueryCLI implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(QueryCLI.class);
    private String dbFilePath;
    private final Path indexRootDir;

    private final QueryParser parser;
    private final QuerySemanticValidator validator;

    // Current strategy settings - mutable for interactive mode
    private PushdownStrategy currentPushdownStrategy;
    private String currentStitchStrategyName;

    // Current output settings - mutable for interactive mode
    private Optional<String> currentExportFormat = Optional.empty();
    private Optional<String> currentExportFilename = Optional.empty();

    // No shared components in the new design; every query builds its own managers.
    private final boolean interactiveMode;

    // Caches for interactive mode (project name -> resources)
    private final java.util.Map<String, IndexManager> projectIndexManagers = new java.util.HashMap<>();

    // Profiling options
    private Optional<String> profileOptions;
    private boolean profileAroundExecution;

    /**
     * Creates a new QueryCLI instance.
     *
     * @param dbFilePath              The path to the project's SQLite database
     *                                file.
     * @param indexDirPath            The path to the directory containing project
     *                                indexes.
     * @param initialPushdownStrategy The initial pushdown strategy
     * @param initialStitchStrategy   The initial stitch execution strategy ("none"
     *                                or "optimized")
     * @param initialExportFormat     Optional initial export format
     * @param initialExportFilename   Optional initial export filename
     * @param interactiveMode         True if running in interactive mode, false for
     *                                single query execution.
     */
    public QueryCLI(String dbFilePath, Path indexDirPath, PushdownStrategy initialPushdownStrategy,
            String initialStitchStrategy, Optional<String> initialExportFormat, Optional<String> initialExportFilename,
            boolean interactiveMode) {
        this.dbFilePath = dbFilePath;
        this.indexRootDir = indexDirPath;
        this.parser = new QueryParser();
        this.validator = new QuerySemanticValidator();

        this.currentPushdownStrategy = initialPushdownStrategy;
        this.currentStitchStrategyName = initialStitchStrategy;
        this.currentExportFormat = initialExportFormat;
        this.currentExportFilename = initialExportFilename;

        this.interactiveMode = interactiveMode;
        this.profileOptions = Optional.empty();
        this.profileAroundExecution = false;

        logger.info("Initialized QueryCLI. DB file: {}, Index dir: {}. Interactive: {}", dbFilePath, indexDirPath,
                interactiveMode);
        logger.info("Initial Strategies - Pushdown: {}, Stitch: {}", currentPushdownStrategy,
                currentStitchStrategyName);
        initialExportFormat.ifPresent(
                format -> logger.info("Initial Export: {} to {}", format, initialExportFilename.orElse("N/A")));
    }

    public QueryCLI(String dbFilePath, Path indexDirPath, PushdownStrategy initialPushdownStrategy,
            String initialStitchStrategy, Optional<String> initialExportFormat, Optional<String> initialExportFilename,
            boolean interactiveMode, Optional<String> profileOptions, boolean profileAroundExecution) {
        this(dbFilePath, indexDirPath, initialPushdownStrategy, initialStitchStrategy, initialExportFormat,
                initialExportFilename, interactiveMode);
        this.profileOptions = profileOptions != null ? profileOptions : Optional.empty();
        this.profileAroundExecution = profileAroundExecution;
    }

    private static String buildStartCommand(String profileOptions) {
        java.util.Map<String, String> opts = parseProfileOptions(profileOptions);
        String event = opts.getOrDefault("event", "wall");
        String output = opts.getOrDefault("output", "html");
        String interval = opts.getOrDefault("interval", "1ms");
        String duration = opts.getOrDefault("duration", null);
        String file = opts.getOrDefault("file", "/tmp/querycli-%p." + output);

        StringBuilder cmd = new StringBuilder();
        cmd.append("start");
        if ("jfr".equalsIgnoreCase(output)) {
            cmd.append(",jfr");
            cmd.append(",file=").append(file);
        }
        cmd.append(",event=").append(event);
        if (interval != null)
            cmd.append(",interval=").append(interval);
        if (duration != null)
            cmd.append(",duration=").append(duration);
        return cmd.toString();
    }

    private static String buildStopCommand(String profileOptions) {
        java.util.Map<String, String> opts = parseProfileOptions(profileOptions);
        String output = opts.getOrDefault("output", "html");
        String file = opts.getOrDefault("file", "/tmp/querycli-%p." + output);
        if ("jfr".equalsIgnoreCase(output)) {
            return "stop"; // file was specified at start
        }
        return "stop,file=" + file + ",output=" + output;
    }

    private static java.util.Map<String, String> parseProfileOptions(String profileOptions) {
        java.util.Map<String, String> opts = new java.util.HashMap<>();
        if (profileOptions != null && !profileOptions.isBlank()) {
            String normalized = profileOptions.replace(';', ',');
            for (String kv : normalized.split(",")) {
                if (kv == null)
                    continue;
                kv = kv.trim();
                if (kv.isEmpty())
                    continue;
                int eq = kv.indexOf('=');
                if (eq > 0 && eq < kv.length() - 1) {
                    String k = kv.substring(0, eq).trim().toLowerCase();
                    String v = kv.substring(eq + 1).trim();
                    if (!v.isEmpty())
                        opts.put(k, v);
                } else {
                    opts.put(kv.toLowerCase(), "true");
                }
            }
        }
        return opts;
    }

    private static String expandProfilerFilename(String filePattern, String output) {
        String pattern = (filePattern == null || filePattern.isBlank()) ? "/tmp/querycli-%p." + output : filePattern;
        long pid = java.lang.ProcessHandle.current().pid();
        String ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(java.time.LocalDateTime.now());
        return pattern.replace("%p", Long.toString(pid)).replace("%t", ts);
    }

    /**
     * Executes a query string using current strategy and output settings.
     *
     * @param queryStr The query string to execute
     */
    public void executeQuery(String queryStr) {
        long startTimeNs = System.nanoTime();

        IndexManager localIndexManager = null; // For non-interactive mode or if shared init failed
        AsyncProfiler ap = null;
        boolean profiling = false;

        try {
            Query query = parser.parse(queryStr);
            validator.validate(query, Optional.empty());

            String projectName = query.source();
            logger.debug("Using project name from FROM clause: {}", projectName);
            logger.debug("Using index root directory: {}", this.indexRootDir);

            // Resolve the project-specific index directory
            java.nio.file.Path projectIndexDir = this.indexRootDir.resolve(projectName);

            // If dbFilePath is not provided, attempt to read from manifest
            if (this.dbFilePath == null) {
                java.nio.file.Path manifestPath = projectIndexDir.resolve(ProjectManifest.defaultFileName());
                try {
                    this.dbFilePath = ProjectManifest.load(manifestPath).dbFile().toString();
                    logger.info("Resolved database path from manifest: {}", this.dbFilePath);
                } catch (IOException ex) {
                    String msg = "Failed to read project manifest at " + manifestPath + ": " + ex.getMessage();
                    logger.error(msg, ex);
                    System.err.println("Error: " + msg);
                    return;
                }
            }

            if (!new java.io.File(this.dbFilePath).exists()) {
                String errorMessage = String.format("Database file not found: %s.", this.dbFilePath);
                logger.error(errorMessage);
                System.err.println("Error: " + errorMessage);
                System.err.println("Ensure the specified database file exists.");
                return;
            }

            // Initialize SQLite access (needed by structural field resolvers)
            SqliteAccessor.initialize(this.dbFilePath);

            IndexManager currentIndexManagerToUse;
            SynonymManager currentSynonymManager;

            if (interactiveMode) {
                // Reuse or create and cache IndexManager for this project
                currentIndexManagerToUse = projectIndexManagers.get(projectName);
                if (currentIndexManagerToUse == null) {
                    // Build a maximal preload query to force all index types to open.
                    // Uses CONTAINS + NER + POS + DATE (4 diverse types) to reliably
                    // trigger maximal preloading (threshold is >= 3). DEPENDS is omitted
                    // because dependency parsing may be disabled system-wide.
                    String preloadQueryStr = String.format(
                            "SELECT DOCUMENT_ID FROM %s WHERE CONTAINS('preload_x') AND NER(PERSON) AND POS(NN) AND DATE(= 2000)",
                            projectName);
                    Query preloadQuery;
                    try {
                        preloadQuery = parser.parse(preloadQueryStr);
                    } catch (QueryParseException e) {
                        // Fallback: use the actual query for required indexes only
                        preloadQuery = query;
                    }
                    currentIndexManagerToUse = new IndexManager(projectIndexDir, projectName, preloadQuery,
                            "optimized");
                    projectIndexManagers.put(projectName, currentIndexManagerToUse);
                    logger.info("Initialized and cached IndexManager for project '{}'.", projectName);
                }
                currentSynonymManager = currentIndexManagerToUse.getSynonymManager();
            } else {
                // Non-interactive: fresh components each query
                localIndexManager = new IndexManager(projectIndexDir, projectName, query,
                        this.currentStitchStrategyName);
                currentIndexManagerToUse = localIndexManager;
                currentSynonymManager = currentIndexManagerToUse.getSynonymManager();
            }
            logger.debug("Ready IndexManager for execution.");
            logger.info("Using database at: {}", this.dbFilePath); // Re-log for clarity if needed

            // Add warning for stitch strategy and granularity mismatch
            Query.Granularity queryGranularity = query.granularity();
            if ("optimized".equalsIgnoreCase(this.currentStitchStrategyName)
                    && queryGranularity != Query.Granularity.SENTENCE) {
                String warningMessage = String.format(
                        "Warning: Stitch optimization ('optimized') is active with strategy '%s', but it is primarily designed for SENTENCE granularity. Current query granularity is %s. Results might not be optimal.",
                        this.currentStitchStrategyName, queryGranularity);
                logger.warn(warningMessage);
                System.err.println(warningMessage);
            }

            ConditionExecutorFactory factory = new ConditionExecutorFactory(currentSynonymManager,
                    this.currentStitchStrategyName, queryGranularity);
            logger.debug("ConditionExecutorFactory configured with S:{}, Granularity:{}",
                    this.currentStitchStrategyName, queryGranularity);

            QueryExecutor queryExecutor = new QueryExecutor(this.currentStitchStrategyName,
                    currentSynonymManager, factory);
            queryExecutor.setPushdownStrategy(this.currentPushdownStrategy);
            logger.debug("QueryExecutor configured with P:{}", this.currentPushdownStrategy);

            int windowSize = query.granularitySize().orElse(0);
            logger.info("Query granularity: {} with size: {}", queryGranularity, windowSize);

            if (profileAroundExecution && profileOptions.isPresent()) {
                try {
                    ap = AsyncProfiler.getInstance();
                    String cmd = buildStartCommand(profileOptions.get());
                    ap.execute(cmd);
                    profiling = true;
                    logger.info("async-profiler started: {}", cmd);
                } catch (Throwable t) {
                    logger.warn("Failed to start async-profiler: {}", t.toString());
                }
            }

            CellResult execResult;
            try {
                execResult = queryExecutor.execute(query, currentIndexManagerToUse);
            } finally {
            }

            // --- New result pipeline ---
            var materializer = new ResultMaterializer(query.source());
            java.util.Iterator<Row> rows = materializer.materialize(execResult, query);

            Table resultTable;
            if (!query.orderBy().isEmpty()) {
                Table unsorted = Table.collect(rows, Schema.fromQuery(query));
                var specs = query.orderBy().stream().map(s -> {
                    boolean desc = s.startsWith("-");
                    String col = (desc || s.startsWith("+")) ? s.substring(1) : s;
                    return new SortSpec(col, desc);
                }).toArray(SortSpec[]::new);
                resultTable = unsorted.sortBy(specs);
            } else {
                resultTable = Table.collect(rows, Schema.fromQuery(query));
            }

            long matchCount = execResult != null ? execResult.cellCount() : 0;
            String matchUnit = (query.joinSteps().isEmpty()
                    ? (queryGranularity == Query.Granularity.DOCUMENT ? "documents" : "sentences")
                    : "conceptual joined rows");
            logger.info("Query executed, found {} matching {} (granularity: {})", matchCount, matchUnit,
                    queryGranularity);

            // LIMIT
            if (query.limit().isPresent())
                resultTable = resultTable.first(query.limit().get());

            // GROUP BY
            if (!query.groupByColumns().isEmpty()) {
                var aggs = buildAggregators(query);
                resultTable = resultTable.groupBy(query.groupByColumns(), aggs);
            }

            // Output
            if (this.currentExportFormat.isPresent() && this.currentExportFilename.isPresent()) {
                String format = this.currentExportFormat.get();
                String filename = this.currentExportFilename.get();
                logger.info("Exporting results to {} format in file: {}", format, filename);
                try {
                    if ("csv".equalsIgnoreCase(format)) {
                        resultTable.writeCsv(java.nio.file.Path.of(filename));
                    } else {
                        // Fallback to CSV for other formats
                        logger.warn("Export format '{}' not fully supported, falling back to CSV", format);
                        resultTable.writeCsv(java.nio.file.Path.of(filename));
                    }
                    System.out.println("Results exported to " + filename);
                } catch (IOException e) {
                    logger.error("Error exporting results: {}", e.getMessage());
                    System.err.println("Error exporting results: " + e.getMessage());
                }
            } else {
                System.out.println(resultTable.print(20));
            }

            // Print execution time after results have been printed
            long endTimeNs = System.nanoTime();
            double executionTimeMs = (endTimeNs - startTimeNs) / 1_000_000.0;
            System.out.printf("BENCHMARK_EXECUTION_TIME_MS: %.3f%n", executionTimeMs);

            // Stop profiler after benchmark time is printed
            if (profiling && ap != null && profileOptions.isPresent()) {
                try {
                    String stopCmd = buildStopCommand(profileOptions.get());
                    ap.execute(stopCmd);
                    logger.info("async-profiler stopped.");
                    // Print resolved output file location
                    java.util.Map<String, String> opts = parseProfileOptions(profileOptions.get());
                    String output = opts.getOrDefault("output", "html");
                    String filePattern = opts.getOrDefault("file", "/tmp/querycli-%p." + output);
                    String resolved = expandProfilerFilename(filePattern, output);
                    System.out.println("Profiler output: " + resolved);
                } catch (Throwable t) {
                    logger.warn("Failed to stop async-profiler: {}", t.toString());
                }
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
        }
    }

    // Setters for interactive mode
    public void setCurrentPushdownStrategy(PushdownStrategy strategy) {
        this.currentPushdownStrategy = strategy;
    }

    public void setCurrentStitchStrategyName(String name) {
        this.currentStitchStrategyName = name;
    }

    public void setCurrentExportFormat(Optional<String> format) {
        this.currentExportFormat = format;
    }

    public void setCurrentExportFilename(Optional<String> filename) {
        this.currentExportFilename = filename;
    }

    // Getters for ACK messages
    public PushdownStrategy getCurrentPushdownStrategy() {
        return currentPushdownStrategy;
    }

    public String getCurrentStitchStrategyName() {
        return currentStitchStrategyName;
    }

    /**
     * Builds aggregators from the query's SELECT columns for use with
     * Table.groupBy.
     */
    private static java.util.Map<String, Aggregators.Aggregator> buildAggregators(Query query) {
        var aggs = new java.util.LinkedHashMap<String, Aggregators.Aggregator>();
        for (var sc : query.selectColumns()) {
            if (sc instanceof SelectedCount c) {
                switch (c.spec()) {
                    case CountAll __ -> aggs.put(c.columnName(), Aggregators.count());
                    case CountUnique(var v) -> aggs.put(c.columnName(), Aggregators.first());
                    case CountDocuments __ -> aggs.put(c.columnName(), Aggregators.count());
                }
            }
        }
        // If no count columns, add a default count
        if (aggs.isEmpty()) {
            aggs.put("count", Aggregators.count());
        }
        return aggs;
    }

    @Override
    public void close() throws IndexAccessException {
        logger.info("Closing QueryCLI resources...");
        if (!projectIndexManagers.isEmpty()) {
            for (IndexManager mgr : projectIndexManagers.values()) {
                try {
                    mgr.close();
                } catch (IndexAccessException e) {
                    logger.warn("Error closing IndexManager during CLI shutdown: {}", e.getMessage());
                }
            }
            projectIndexManagers.clear();
        }
    }

    /**
     * Main entry point for the CLI.
     *
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        ArgumentParser cliArgParser = ArgumentParsers.newFor("QueryCLI").build()
                .defaultHelp(true)
                .description(
                        "Execute queries against indexed projects. Queries specify the project via the FROM clause.\n\n"
                                +
                                "Profiling example: --profile 'event=wall,output=html,file=/tmp/profile-%p.html,interval=1ms' --profile-around-execution");

        cliArgParser.addArgument("--db-file")
                .required(false)
                .help("Optional absolute path to a SQLite database file. If omitted, the path is read from the manifest inside the project index directory determined by the FROM clause.");

        cliArgParser.addArgument("--index-root-dir")
                .required(false)
                .setDefault(".")
                .help("Path to the root directory containing all project index folders. Defaults to current working directory.");

        cliArgParser.addArgument("--export")
                .help("Export results to a file in the specified format: csv:filename.csv, json:filename.json, or html:filename.html. Sets initial export for single query or interactive mode.");

        cliArgParser.addArgument("--pushdown-strategy")
                .choices("none", "optimized").setDefault("optimized")
                .type(String.class)
                .help("Specify the initial predicate pushdown strategy (none or optimized). Default: optimized.");

        cliArgParser.addArgument("--stitch-strategy")
                .choices("none", "optimized").setDefault("none")
                .help("Specify the initial stitch execution strategy (none or optimized).");

        // Profiling options (async-profiler Java API)
        cliArgParser.addArgument("--profile")
                .help("Enable async-profiler. Pass options as a single quoted, comma-separated string. " +
                        "Example: 'event=wall,file=/tmp/profile-%p.html,interval=1ms'. " +
                        "Keys: event, file, interval, duration. Output defaults to HTML.")
                .required(false);
        cliArgParser.addArgument("--profile-around-execution")
                .action(Arguments.storeTrue())
                .help("If set with --profile, starts profiler after indexes are ready and stops after query execution.")
                .required(false);

        cliArgParser.addArgument("query")
                .nargs("?")
                .help("The query string to execute. If not provided, enters interactive mode.");

        try {
            Namespace ns = cliArgParser.parseArgs(args);
            String dbFile = ns.getString("db_file");
            Path indexDir = Path.of(ns.getString("index_root_dir"));
            String exportArg = ns.getString("export");
            PushdownStrategy initialPushdownStrategy = PushdownStrategy.fromString(ns.getString("pushdown_strategy"));
            String initialStitchStrategy = ns.getString("stitch_strategy");
            String queryStr = ns.getString("query");
            String profileOptions = ns.getString("profile");
            boolean profileAroundExecution = ns.getBoolean("profile_around_execution") != null
                    && ns.getBoolean("profile_around_execution");

            Optional<String> initialExportFormat = Optional.empty();
            Optional<String> initialExportFilename = Optional.empty();

            if (exportArg != null) {
                String[] parts = exportArg.split(":", 2);
                if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                    initialExportFormat = Optional.of(parts[0].toLowerCase());
                    initialExportFilename = Optional.of(parts[1]);
                } else {
                    System.err.println(
                            "Invalid --export format. Use format:filename (e.g., csv:output.csv). Export disabled.");
                }
            }

            boolean interactive = (queryStr == null);

            try (QueryCLI cli = new QueryCLI(dbFile, indexDir, initialPushdownStrategy, initialStitchStrategy,
                    initialExportFormat, initialExportFilename, interactive, Optional.ofNullable(profileOptions),
                    profileAroundExecution)) {
                if (interactive) {

                    Scanner scanner = new Scanner(System.in);
                    System.out.println("QueryCLI Interactive Mode");
                    System.out.println("Type 'EXIT' or 'QUIT' to leave.");
                    System.out.println("Commands: SET STRATEGY pushdown=<val> stitch=<val>");
                    System.out.println("          SET OUTPUT <format> <filename> | SET OUTPUT NONE");
                    System.out
                            .println("Current strategies: P:" + cli.getCurrentPushdownStrategy().name().toLowerCase() +
                                    " S:" + cli.getCurrentStitchStrategyName());
                    cli.currentExportFormat.ifPresentOrElse(
                            f -> System.out
                                    .println("Current output: " + f + " to " + cli.currentExportFilename.orElse("N/A")),
                            () -> System.out.println("Current output: Console"));

                    while (true) {
                        System.out.println(); // Ensures a blank line before the prompt
                        System.out.print("Query>"); // Print the prompt text itself
                        System.out.println(); // Crucially, print a newline *after* the prompt text
                        System.out.flush(); // Ensure it's sent

                        String inputLine = scanner.nextLine().trim();

                        if (inputLine.equalsIgnoreCase("EXIT") || inputLine.equalsIgnoreCase("QUIT")) {
                            break;
                        }

                        if (inputLine.matches("(?i)^SET\\s+STRATEGY.*")) {
                            Pattern pattern = Pattern.compile("(\\w+)=(\\S+)");
                            Matcher matcher = pattern.matcher(inputLine);
                            boolean strategyUpdated = false;
                            while (matcher.find()) {
                                String key = matcher.group(1).toLowerCase();
                                String value = matcher.group(2);
                                try {
                                    switch (key) {
                                        case "pushdown":
                                            cli.setCurrentPushdownStrategy(PushdownStrategy.fromString(value));
                                            strategyUpdated = true;
                                            break;
                                        case "stitch":
                                            if (Arrays.asList("none", "optimized").contains(value.toLowerCase())) {
                                                cli.setCurrentStitchStrategyName(value.toLowerCase());
                                                strategyUpdated = true;
                                            } else {
                                                System.err.println("Invalid stitch strategy value: " + value
                                                        + ". Must be 'none' or 'optimized'.");
                                            }
                                            break;
                                        default:
                                            System.err.println("Unknown strategy key: " + key);
                                    }
                                } catch (IllegalArgumentException e) {
                                    System.err.println("Invalid value for strategy " + key + ": " + value + " ("
                                            + e.getMessage() + ")");
                                }
                            }
                            if (strategyUpdated) {
                            } else if (!inputLine.trim().equalsIgnoreCase("SET STRATEGY")) {
                                System.err.println(
                                        "No valid strategy key-value pairs found in SET STRATEGY command. Format: key=value ...");
                            }

                        } else if (inputLine.matches("(?i)^SET\\s+OUTPUT.*")) {
                            String outputCmdArgs = inputLine
                                    .substring(inputLine.toLowerCase().indexOf("output") + "output".length()).trim();
                            if (outputCmdArgs.equalsIgnoreCase("none")) {
                                cli.setCurrentExportFormat(Optional.empty());
                                cli.setCurrentExportFilename(Optional.empty());
                            } else {
                                String[] parts = outputCmdArgs.split("\\s+", 2);
                                if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                                    String format = parts[0].toLowerCase();
                                    if (!Arrays.asList("csv", "json", "html").contains(format)) {
                                        System.err.println("Unsupported export format: " + format
                                                + ". Supported: csv, json, html.");
                                    } else {
                                        cli.setCurrentExportFormat(Optional.of(format));
                                        cli.setCurrentExportFilename(Optional.of(parts[1]));
                                    }
                                } else {
                                    System.err.println(
                                            "Invalid SET OUTPUT command. Use 'SET OUTPUT <format> <filename>' or 'SET OUTPUT NONE'.");
                                }
                            }

                        } else if (!inputLine.isEmpty()) {
                            cli.executeQuery(inputLine);
                        }
                    }
                    scanner.close();
                    System.out.println("Exiting QueryCLI interactive mode.");

                } else { // Single query mode
                    cli.executeQuery(queryStr);
                }
            }

        } catch (ArgumentParserException e) {
            cliArgParser.handleError(e);
            System.exit(1);
        } catch (IndexAccessException e) {
            logger.error("Error during QueryCLI close: {}", e.getMessage(), e);
            System.err.println("Error closing QueryCLI resources: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            logger.error("An unexpected error occurred in main: {}", e.getMessage(), e);
            System.err.println("An unexpected error occurred: " + e.getMessage());
            System.exit(1);
        }
    }
}
