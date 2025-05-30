import argparse
import itertools  # Added for strategy combinations
import os
import random  # For cold cache shuffling
import re
import subprocess
import time  # For potential delays

# Assuming the QueryCLI.jar is in the target/ directory relative to the script
# and the script is in the root of the java-nlp project.
DEFAULT_JAR_PATH = "target/query-cli.jar"
# This might need to be adjusted based on the actual JAR name and location

def run_query_cli(query_string, projects_dir, temporal_strategy, join_strategy, stitch_strategy, jar_path, export_path=None):
    """
    Constructs and runs the QueryCLI command, then captures and returns the benchmark time.
    Optionally exports results.
    """
    if not os.path.exists(jar_path):
        print(f"Error: JAR file not found at {jar_path}")
        print("Please build the project first (e.g., using 'mvn package')")
        return None, None, None

    command = [
        "java",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "-jar", jar_path,
        "-pd", projects_dir,
        "--temporal-strategy", temporal_strategy,
        "--join-strategy", join_strategy,
        "--stitch-strategy", stitch_strategy,
        query_string
    ]

    if export_path:
        command.extend(["--export", f"csv:{export_path}"]) # Assuming CSV for now, can be made configurable

    print(f"Executing command: {' '.join(command)}")

    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        stdout, stderr = process.communicate()

        benchmark_time_ms = None
        for line in stdout.splitlines():
            if line.startswith("BENCHMARK_EXECUTION_TIME_MS:"):
                match = re.search(r"BENCHMARK_EXECUTION_TIME_MS: (\d+\.?\d*)", line)
                if match:
                    benchmark_time_ms = float(match.group(1))
                break

        return benchmark_time_ms, stdout, stderr

    except FileNotFoundError:
        print("Error: Java command not found. Ensure Java is installed and in your PATH.")
        return None, stdout, stderr
    except Exception as e:
        print(f"An error occurred: {e}")
        return None, stdout, stderr

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Benchmark QueryCLI.java. Runs all strategy combinations.")
    query_group = parser.add_mutually_exclusive_group(required=True)
    query_group.add_argument("--query", help="The query string to execute.")
    query_group.add_argument("--query-file", help="Path to a file containing queries to execute (one per line).")

    parser.add_argument("-pd", "--projects-dir", default="projects", help="Base directory for project data (default: projects)")
    parser.add_argument("--jar-path", default=DEFAULT_JAR_PATH, help=f"Path to the QueryCLI JAR file (default: {DEFAULT_JAR_PATH})")

    # Cache strategy arguments
    parser.add_argument("--cache-mode", choices=["none", "cold", "warm"], default="none",
                        help="Caching strategy for benchmark runs (default: none). "
                             "'none': 1 run per query/strategy. "
                             "'cold': 1 run per query/strategy, queries shuffled. "
                             "'warm': 1 warm-up run + N timed runs per query/strategy.")
    parser.add_argument("--warm-runs", type=int, default=3,
                        help="Number of timed runs after warm-up for 'warm' cache mode (default: 3).")

    # Output control
    parser.add_argument("--verbose", action="store_true", help="Print full QueryCLI stdout for each run.")
    parser.add_argument("--export-dir", help="Directory to save exported results from QueryCLI (one file per run).")

    args = parser.parse_args()

    if args.cache_mode == "warm" and args.warm_runs <= 0:
        print("Error: --warm-runs must be positive for 'warm' cache mode.")
        exit(1)

    queries_to_run_orig = []
    if args.query_file:
        try:
            with open(args.query_file, 'r') as f:
                queries_to_run_orig = [line.strip() for line in f if line.strip()]
            if not queries_to_run_orig:
                print(f"Error: Query file {args.query_file} is empty or contains no valid queries.")
                exit(1)
        except FileNotFoundError:
            print(f"Error: Query file not found at {args.query_file}")
            exit(1)
        except Exception as e:
            print(f"Error reading query file {args.query_file}: {e}")
            exit(1)
    elif args.query:
        queries_to_run_orig.append(args.query)

    if not queries_to_run_orig:
        print("No queries to execute.")
        exit(0)

    if args.export_dir and not os.path.exists(args.export_dir):
        try:
            os.makedirs(args.export_dir)
            print(f"Created export directory: {args.export_dir}")
        except OSError as e:
            print(f"Error: Could not create export directory {args.export_dir}: {e}")
            exit(1)

    print(f"Using JAR: {args.jar_path}, Projects Dir: {args.projects_dir}")
    print(f"Cache Mode: {args.cache_mode}", end="")
    if args.cache_mode == "warm":
        print(f" (1 warm-up + {args.warm_runs} timed runs per query/strategy)")
    else:
        print()
    print(f"Verbose output: {args.verbose}")
    if args.export_dir:
        print(f"Exporting results to: {args.export_dir}")
    print("\n")

    temporal_strategies = ["naive", "nash"]
    join_strategies = ["independent", "dependent"]
    stitch_strategies = ["none", "optimized"]

    strategy_combinations = list(itertools.product(temporal_strategies, join_strategies, stitch_strategies))

    # Prepare queries based on cache mode
    if args.cache_mode == "cold":
        queries_to_run = list(queries_to_run_orig) # Make a copy
        random.shuffle(queries_to_run)
        print("Cold cache mode: Queries will be run in a random order for this benchmark execution.")
    else:
        queries_to_run = queries_to_run_orig

    all_run_results = []

    total_query_strategy_combinations = len(queries_to_run) * len(strategy_combinations)
    current_run_count = 0

    for i, query_text in enumerate(queries_to_run):
        query_results_for_strategies = [] # Stores results for this query across all strategies
        print(f"========== Query {i+1}/{len(queries_to_run)}: {query_text} ==========")

        for strat_idx, (temporal_strategy, join_strategy, stitch_strategy) in enumerate(strategy_combinations):
            current_run_count +=1
            progress_prefix = f"[Query {i+1}/{len(queries_to_run)}, Strategy {strat_idx+1}/{len(strategy_combinations)} ({current_run_count}/{total_query_strategy_combinations})]"

            print(f"{progress_prefix} Running with Strategies: T:{temporal_strategy}, J:{join_strategy}, S:{stitch_strategy}")

            timed_run_times_ms = []
            final_stdout = ""
            final_stderr = ""

            # Determine export filename base
            export_filename_base = None
            if args.export_dir:
                safe_query_part = re.sub(r'[^a-zA-Z0-9_-]', '_', query_text)[:50] # Sanitize query for filename
                export_filename_base = f"q{i+1}_{safe_query_part}_T{temporal_strategy}_J{join_strategy}_S{stitch_strategy}"

            # --- Cache Mode Logic ---
            if args.cache_mode == "warm":
                # 1. Warm-up run
                print(f"{progress_prefix}  Warm-up run 1/1...")
                warmup_export_path = os.path.join(args.export_dir, f"{export_filename_base}_warmup.csv") if export_filename_base else None

                if args.verbose: print(f"  Executing command for warm-up: java -jar {args.jar_path} ... {query_text}")
                _, warmup_stdout, warmup_stderr = run_query_cli(
                    query_text, args.projects_dir, temporal_strategy, join_strategy, stitch_strategy, args.jar_path, warmup_export_path
                )
                if args.verbose and warmup_stdout: print(f"  Warm-up QueryCLI Output:\n{warmup_stdout}")
                if warmup_stderr: print(f"  Warm-up QueryCLI Errors:\n{warmup_stderr}")
                final_stderr += warmup_stderr if warmup_stderr else "" # Collect stderr

                # Optional short delay after warm-up, can sometimes help ensure caches are fully "warmed"
                # time.sleep(0.1)

                # 2. Timed runs
                for run_num in range(args.warm_runs):
                    print(f"{progress_prefix}  Timed run {run_num + 1}/{args.warm_runs}...")
                    timed_export_path = os.path.join(args.export_dir, f"{export_filename_base}_timed{run_num+1}.csv") if export_filename_base else None

                    if args.verbose: print(f"  Executing command for timed run {run_num+1}: java -jar {args.jar_path} ... {query_text}")
                    time_taken, stdout_output, stderr_output = run_query_cli(
                        query_text, args.projects_dir, temporal_strategy, join_strategy, stitch_strategy, args.jar_path, timed_export_path
                    )
                    if time_taken is not None:
                        timed_run_times_ms.append(time_taken)
                        print(f"{progress_prefix}    BENCHMARK_EXECUTION_TIME_MS: {time_taken:.3f}")
                    else:
                        print(f"{progress_prefix}    Failed to retrieve benchmark time for this run.")

                    if args.verbose and stdout_output: final_stdout += stdout_output # Collect stdout if verbose
                    if stderr_output: final_stderr += stderr_output # Collect all stderr
                    if args.verbose and stdout_output: print(f"  Timed Run {run_num+1} QueryCLI Output:\n{stdout_output}")
                    if stderr_output: print(f"  Timed Run {run_num+1} QueryCLI Errors:\n{stderr_output}")

            elif args.cache_mode == "cold" or args.cache_mode == "none":
                # Single run for 'cold' (after shuffling) or 'none' mode
                run_label = "Cold cache run" if args.cache_mode == "cold" else "Standard run"
                print(f"{progress_prefix}  {run_label} 1/1...")
                single_run_export_path = os.path.join(args.export_dir, f"{export_filename_base}_run.csv") if export_filename_base else None

                if args.verbose: print(f"  Executing command for {run_label}: java -jar {args.jar_path} ... {query_text}")
                time_taken, stdout_output, stderr_output = run_query_cli(
                    query_text, args.projects_dir, temporal_strategy, join_strategy, stitch_strategy, args.jar_path, single_run_export_path
                )
                if time_taken is not None:
                    timed_run_times_ms.append(time_taken)
                    print(f"{progress_prefix}    BENCHMARK_EXECUTION_TIME_MS: {time_taken:.3f}")
                else:
                    print(f"{progress_prefix}    Failed to retrieve benchmark time for this run.")

                if args.verbose and stdout_output: final_stdout = stdout_output
                if stderr_output: final_stderr = stderr_output
                if args.verbose and stdout_output: print(f"  {run_label} QueryCLI Output:\n{stdout_output}")
                if stderr_output: print(f"  {run_label} QueryCLI Errors:\n{stderr_output}")

            # Calculate average time for this query/strategy combination
            avg_time_ms = None
            if timed_run_times_ms:
                avg_time_ms = sum(timed_run_times_ms) / len(timed_run_times_ms)

            result_entry = {
                "query": query_text,
                "temporal_strategy": temporal_strategy,
                "join_strategy": join_strategy,
                "stitch_strategy": stitch_strategy,
                "time_ms": avg_time_ms, # This is now an average for warm mode
                "individual_times_ms": list(timed_run_times_ms), # Store all timed runs
                "cache_mode": args.cache_mode,
                # Storing full stdout/stderr can be memory intensive; only store if verbose or errors.
                "stdout": final_stdout if args.verbose else ("See exported files" if args.export_dir and avg_time_ms is not None else ""),
                "stderr": final_stderr if final_stderr else ""
            }
            query_results_for_strategies.append(result_entry)

            if avg_time_ms is not None:
                print(f"{progress_prefix}  Average BENCHMARK_EXECUTION_TIME_MS: {avg_time_ms:.3f} (from {len(timed_run_times_ms)} run(s))")
            else:
                print(f"{progress_prefix}  Failed to retrieve any benchmark execution times for this strategy combination.")
            print("-------------------------------------")
        all_run_results.append(query_results_for_strategies)

    # Enhanced summary
    print("\n========== Benchmark Overall Summary ==========")
    for i, query_text in enumerate(queries_to_run):
        print(f"\n----- Query: {query_text} -----")
        results_for_this_query = all_run_results[i]
        for res in results_for_this_query:
            strat_key = f"T:{res['temporal_strategy']}, J:{res['join_strategy']}, S:{res['stitch_strategy']}"
            time_str = f"{res['time_ms']:.3f} ms" if res['time_ms'] is not None else "Failed"
            print(f"  {strat_key.ljust(45)}: {time_str}")

    # Calculate and print average times per strategy combination across all queries
    print("\n----- Average Times Per Strategy Combination (across all successful queries) -----")
    summary_by_strategy = {}
    for temporal_strategy, join_strategy, stitch_strategy in strategy_combinations:
        key = (temporal_strategy, join_strategy, stitch_strategy)
        times_for_combo = []
        for query_res_list in all_run_results:
            for res_entry in query_res_list:
                if (res_entry['temporal_strategy'] == temporal_strategy and
                    res_entry['join_strategy'] == join_strategy and
                    res_entry['stitch_strategy'] == stitch_strategy and
                    res_entry['time_ms'] is not None):
                    times_for_combo.append(res_entry['time_ms'])

        avg_time_for_combo_str = "N/A (no successful runs)"
        if times_for_combo:
            avg_time_for_combo = sum(times_for_combo) / len(times_for_combo)
            avg_time_for_combo_str = f"{avg_time_for_combo:.3f} ms ({len(times_for_combo)} queries)"

        strat_key_str = f"T:{temporal_strategy}, J:{join_strategy}, S:{stitch_strategy}"
        print(f"  {strat_key_str.ljust(45)}: {avg_time_for_combo_str}")

    total_runs = len(queries_to_run) * len(strategy_combinations)
    total_successful_timed_runs = sum(1 for qr_list in all_run_results for r in qr_list if r['time_ms'] is not None)
    print(f"\nTotal individual benchmark runs attempted: {total_runs}")
    print(f"Total successful timed primary metric calculations: {total_successful_timed_runs}")

    # Further improvements:
    # - CSV/JSON output for easier analysis
    # - Implementation of Cold/Warm cache strategies
    # - Result verification