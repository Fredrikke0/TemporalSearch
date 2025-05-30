package com.example;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.query.QueryParseException;
import com.example.query.QueryParser;
import com.example.query.QuerySemanticValidator;
import com.example.query.executor.ConditionExecutorFactory;
import com.example.query.executor.JoinOptimizationStrategy;
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
public class QueryCLI {
    private static final Logger logger = LoggerFactory.getLogger(QueryCLI.class);
    private final Path projectsDir;

    // Core components
    private final QueryParser parser;
    private final QuerySemanticValidator validator;
    private final ConditionExecutorFactory executorFactory;
    private final QueryExecutor executor;
    private final JoinOptimizationStrategy joinStrategy;
    private final String stitchStrategy;

    /**
     * Creates a new QueryCLI instance.
     *
     * @param projectsDir The base directory containing project folders.
     * @param temporalStrategy The desired temporal execution strategy ("nash" or "naive")
     * @param joinStrategy The desired join execution strategy ("independent" or "dependent")
     * @param stitchStrategy The desired stitch execution strategy ("none" or "optimized")
     */
    public QueryCLI(Path projectsDir, String temporalStrategy, JoinOptimizationStrategy joinStrategy, String stitchStrategy) {
        this.projectsDir = projectsDir;
        this.parser = new QueryParser();
        this.validator = new QuerySemanticValidator();
        this.executorFactory = new ConditionExecutorFactory();
        this.executorFactory.setTemporalStrategy(temporalStrategy);
        this.executor = new QueryExecutor(this.executorFactory, stitchStrategy);
        this.executor.setJoinOptimizationStrategy(joinStrategy);
        this.joinStrategy = joinStrategy;
        this.stitchStrategy = stitchStrategy;
        logger.info("Initialized QueryCLI with base projects directory: {}", projectsDir);
        logger.info("Project structure expected: {}/[PROJECT_NAME]/[PROJECT_NAME].db and {}/[PROJECT_NAME]/indexes/", projectsDir, projectsDir);
        logger.info("Temporal Strategy: {}, Join Strategy: {}, Stitch Strategy: {}", temporalStrategy, joinStrategy, stitchStrategy);
    }

    /**
     * Executes a query string.
     *
     * @param queryStr The query string to execute
     * @param exportFormat Optional export format (csv, json, html)
     * @param exportFilename Optional export filename
     */
    public void executeQuery(String queryStr, Optional<String> exportFormat, Optional<String> exportFilename) {
        long startTimeNs = System.nanoTime(); // Start timing
        try {
            // 1. Parse query string into Query object
            logger.debug("Parsing query: {}", queryStr);
            Query query = parser.parse(queryStr);

            // 2. Validate query semantics
            logger.debug("Validating query: {}", query);
            validator.validate(query);

            // Check for date queries and display helpful information
            checkAndDisplayDateQueryHelp(queryStr, query);

            // 3. Get project name from FROM clause
            String projectName = query.source();
            logger.debug("Using project name from FROM clause: {}", projectName);

            // Construct paths based on projectsDir and projectName
            Path projectPath = projectsDir.resolve(projectName);
            String corpusDbPath = projectPath.resolve(projectName + ".db").toString();
            Path indexBasePath = projectPath.resolve("indexes"); // Path object for IndexManager

            logger.debug("Resolved project path: {}", projectPath);
            logger.debug("Using database path for project '{}': {}", projectName, corpusDbPath);
            logger.debug("Using index base path for project '{}': {}", projectName, indexBasePath);

            // Check if project-specific database exists
            if (!new java.io.File(corpusDbPath).exists()) {
                String errorMessage = String.format(
                    "Database file not found for project '%s': %s. Expected location: %s/%s/%s.db",
                    projectName, corpusDbPath, projectsDir, projectName, projectName);
                logger.error(errorMessage);
                System.err.println("Error: " + errorMessage);
                System.err.println("Ensure the project directory and its corresponding database exist.");
                return; // Early return
            }

            // Initialize SqliteAccessor for this specific database *before* TableResultService or IndexManager might need it.
            // This assumes SqliteAccessor needs initialization per DB. If it's truly global or managed differently, adjust this.
            SqliteAccessor.initialize(corpusDbPath);
            logger.debug("Initialized SqliteAccessor for database: {}", corpusDbPath);

            // Initialize Nash temporal index for this corpus (project) - conceptually the same
            logger.debug("Initializing Nash temporal index (if applicable) for project: {}", projectName);

            // Create a new TableResultService with the project-specific database path
            TableResultService tableResultService = new TableResultService(corpusDbPath);
            logger.info("Using project-specific database at: {}", corpusDbPath);

            // 4. Create IndexManager for the resolved paths, passing the query and strategy
            // Pass the projectPath, IndexManager will resolve its own indexBaseDir
            try (IndexManager indexManager = new IndexManager(projectPath, projectName, query, this.executorFactory.getTemporalStrategy())) {
                logger.debug("Created IndexManager for project: {} using project path: {}", projectName, projectPath);

                // Initialize Nash index with the index manager
                executor.initializeNashIndex(projectName, indexManager); // Pass projectName

                // 5. Execute query using QueryExecutor
                logger.debug("Executing query against project: {}", projectName);
                Query.Granularity granularity = query.granularity();
                int windowSize = query.granularitySize().orElse(0); // Use 0 if not present
                logger.info("Query granularity: {} with size: {}", granularity, windowSize);

                // QueryExecutor now consistently returns QueryResultSoA
                QueryResultSoA execResult = executor.execute(query, indexManager.getAllIndexes());

                Table resultTable;
                int matchCount;
                String matchUnit;

                // Simplified result handling: execResult is always QueryResultSoA
                if (execResult != null) {
                    matchCount = execResult.size();
                    // Determine match unit based on whether it was a join (by checking query structure) or simple query
                    if (query.joinCondition().isPresent()) {
                        matchUnit = "conceptual joined rows";
                    } else {
                        matchUnit = (granularity == Query.Granularity.DOCUMENT) ? "documents" : "sentences";
                    }
                    logger.info("Query executed, found {} matching {} (granularity for base parts: {})", matchCount, matchUnit, granularity);

                    logger.debug("Generating result table from QueryResultSoA");
                    resultTable = tableResultService.generateTable(
                        query,
                        execResult,
                        indexManager.getAllIndexes()
                    );
                } else {
                    // This case should ideally not happen if QueryExecutor guarantees a non-null QueryResultSoA (even if empty)
                    logger.error("Query execution returned a null QueryResultSoA.");
                    System.err.println("Error: Query execution resulted in an unexpected null result.");
                    // Create an empty table or handle error appropriately
                    resultTable = Table.create("Empty Result"); // Placeholder for an empty table
                    matchCount = 0;
                    matchUnit = "results";
                }

                // 6. Generate results using TableResultService (removed from inside if/else)
                // The specific generation logic is now handled within the if/else branches above

                // NOTE: We're now using Tablesaw's sorting capabilities directly
                // The orderBy list in Query now contains Tablesaw-compatible sort strings
                // (column names with optional "-" prefix for descending order)

                // 7. Handle export if requested
                if (exportFormat.isPresent() && exportFilename.isPresent()) {
                    String format = exportFormat.get();
                    String filename = exportFilename.get();
                    logger.info("Exporting results to {} format in file: {}", format, filename);

                    try {
                        tableResultService.exportTable(resultTable, format, filename);
                        System.out.println("Results exported to " + filename);
                    } catch (IOException e) {
                        logger.error("Error exporting results: {}", e.getMessage());
                        System.err.println("Error exporting results: " + e.getMessage());
                    }
                } else {
                    // 8. Format and display results
                    logger.debug("Formatting results for display");
                    String formattedResults = tableResultService.formatTable(resultTable);

                    // Output the formatted results
                    System.out.println(formattedResults);
                }
            }

        } catch (QueryParseException e) {
            logger.error("Query parse error: {}", e.getMessage());
            System.err.println("Error parsing query: " + e.getMessage());
            // No position information in QueryParseException
        } catch (Exception e) {
            logger.error("Error executing query: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
        } finally {
            long endTimeNs = System.nanoTime();
            double executionTimeMs = (endTimeNs - startTimeNs) / 1_000_000.0;
            System.out.printf("BENCHMARK_EXECUTION_TIME_MS: %.3f%n", executionTimeMs);
        }
    }

    /**
     * Checks if the query involves date operations and displays helpful information.
     *
     * @param queryStr The original query string
     * @param query The parsed query object
     */
    private void checkAndDisplayDateQueryHelp(String queryStr, Query query) {
        if (queryStr.toUpperCase().contains("DATE(")) {
            // Check for specific predicates
            if (queryStr.toUpperCase().contains("DATE(CONTAINS [")) {
                logger.info("Date CONTAINS query detected - requires dates to be fully within range");
                System.out.println("\nQuery Help: You're using DATE(CONTAINS [range]) which requires dates to be fully within the specified range.");
                System.out.println("For broader matches, consider using DATE(INTERSECT [range]) which matches any overlap with the range.\n");
            } else if (queryStr.toUpperCase().contains("DATE(INTERSECT [")) {
                logger.info("Date INTERSECT query detected - matches any overlap with date range");
            }

            // Check for granularity
            if (query.granularity() == Query.Granularity.SENTENCE) {
                logger.info("Date query with sentence granularity detected");
                System.out.println("Note: With sentence granularity, the query will return specific sentences containing date mentions in the specified range.");
            } else {
                logger.info("Date query with document granularity detected");
                System.out.println("Note: With document granularity, the query will return documents containing date mentions in the specified range.");
            }
        }
    }

    /**
     * Main entry point for the CLI.
     *
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        // Set up argument parser
        ArgumentParser parser = ArgumentParsers.newFor("QueryCLI").build()
                .defaultHelp(true)
                .description("Execute queries against indexed projects. Queries specify the project via the FROM clause.");

        parser.addArgument("-pd", "--projects-dir")
                .setDefault("projects")
                .help("Base directory containing project folders (default: projects)");

        parser.addArgument("--export")
                .help("Export results to a file in the specified format: csv:filename.csv, json:filename.json, or html:filename.html");

        // Add the new temporal strategy flag
        parser.addArgument("--temporal-strategy")
                .choices("nash", "naive") // Define allowed choices
                .setDefault("naive")      // Set the default value
                .help("Select the execution strategy for temporal conditions (default: naive)");

        // Add the new join strategy flag
        parser.addArgument("--join-strategy")
                .choices("independent", "dependent")
                .setDefault("independent")
                .help("Specifies the execution strategy for JOIN operations. 'independent' executes both sides fully before joining (default). 'dependent' attempts to optimize by filtering one side based on the results of the other.");

        // Add the new stitch strategy flag
        parser.addArgument("--stitch-strategy")
                .choices("none", "optimized") // Define allowed choices
                .setDefault("none")      // Set the default value
                .help("Select the execution strategy for stitch index optimization (default: none)");

        parser.addArgument("query")
                .nargs("?")
                .help("Query string to execute");

        try {
            // Parse arguments
            Namespace ns = parser.parseArgs(args);
            String projectsDirStr = ns.getString("projects_dir");
            String query = ns.getString("query");
            String exportArg = ns.getString("export");
            String temporalStrategy = ns.getString("temporal_strategy"); // Get the strategy name
            String joinStrategyStr = ns.getString("join_strategy");
            String stitchStrategy = ns.getString("stitch_strategy"); // Get stitch strategy
            JoinOptimizationStrategy joinStrategy;
            if ("dependent".equalsIgnoreCase(joinStrategyStr)) {
                joinStrategy = JoinOptimizationStrategy.DEPENDENT;
            } else {
                joinStrategy = JoinOptimizationStrategy.INDEPENDENT;
            }

            // Parse export argument if provided
            Optional<String> exportFormat = Optional.empty();
            Optional<String> exportFilename = Optional.empty();

            if (exportArg != null && !exportArg.isEmpty()) {
                String[] parts = exportArg.split(":", 2);
                if (parts.length == 2) {
                    exportFormat = Optional.of(parts[0]);
                    exportFilename = Optional.of(parts[1]);
                } else {
                    System.err.println("Invalid export format. Use format:filename (e.g., csv:results.csv)");
                    System.exit(1);
                }
            }

            // Create and run CLI, passing the chosen strategies
            logger.info("Configuring temporal strategy: {}", temporalStrategy);
            logger.info("Configuring join strategy: {}", joinStrategy);
            logger.info("Configuring stitch strategy: {}", stitchStrategy);
            QueryCLI cli = new QueryCLI(Path.of(projectsDirStr), temporalStrategy, joinStrategy, stitchStrategy);

            if (query != null) {
                // Execute the provided query
                cli.executeQuery(query, exportFormat, exportFilename);
            } else {
                // Interactive mode
                Scanner scanner = new Scanner(System.in);
                System.out.println("Query CLI - Enter queries or 'exit' to quit");
                System.out.println("Using base projects directory: " + projectsDirStr);
                System.out.println("Project structure expected: " + projectsDirStr + "/[PROJECT_NAME]/[PROJECT_NAME].db");
                System.out.println("Index structure expected: " + projectsDirStr + "/[PROJECT_NAME]/indexes/");
                System.out.println("Specify project in query using: FROM [PROJECT_NAME]");
                System.out.println("Temporal Strategy: " + temporalStrategy + " (Use --temporal-strategy nash|naive to change at startup)");
                System.out.println("Join Strategy: " + joinStrategy.name().toLowerCase() + " (Use --join-strategy independent|dependent to change at startup)");
                System.out.println("Stitch Strategy: " + stitchStrategy + " (Use --stitch-strategy none|optimized to change at startup)");
                System.out.println("Snippet support is enabled. Use SNIPPET(variable) in SELECT clause to show text context.");
                System.out.println("Export support: Add --export=format:filename to export results (formats: csv, json, html)");

                while (true) {
                    System.out.print("\nQuery> ");
                    String input = scanner.nextLine().trim();

                    if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                        break;
                    }

                    if (!input.isEmpty()) {
                        // Note: In interactive mode, the strategy chosen at startup is used for all queries.
                        cli.executeQuery(input, exportFormat, exportFilename);
                    }
                }

                scanner.close();
            }

        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        }
    }
}