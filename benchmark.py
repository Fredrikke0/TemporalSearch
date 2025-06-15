import argparse
import csv
import itertools
import os
import random
import re
import subprocess

DEFAULT_JAR_PATH = "target/query-cli.jar"
QUERY_TIMEOUT_SECONDS = 60 * 60  # 1 hour

def run_query_cli(query_string, cli_db_file, cli_index_dir, temporal_strategy, pushdown_strategy_arg, stitch_strategy_arg, jar_path, export_path=None, is_verbose=False):
    """
    Constructs and runs the QueryCLI command, then captures and returns the benchmark time.
    Optionally exports results.
    """
    if not os.path.exists(jar_path):
        print(f"Error: JAR file not found at {jar_path}")
        print("Please build the project first (e.g., using 'mvn package')")
        return None, None, None

    # Check for existence of user-supplied db_file path directly
    if not os.path.exists(cli_db_file):
        print(f"Error: Database file specified by --db-file not found: {cli_db_file}")
        return None, None, None
    # We don't check cli_index_dir here as QueryCLI.java/IndexManager will handle its contents and existence.

    command = [
        "java",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "-jar", jar_path,
        "--db-file", cli_db_file,
        "--index-dir", cli_index_dir,
        "--temporal-strategy", temporal_strategy,
        "--pushdown-strategy", pushdown_strategy_arg,
        "--stitch-strategy", stitch_strategy_arg,
        query_string
    ]

    if export_path:
        command.extend(["--export", f"csv:{export_path}"])

    if is_verbose:
        print(f"Executing command: {' '.join(command)}")

    process = None # Initialize process to None
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        stdout, _ = process.communicate(timeout=QUERY_TIMEOUT_SECONDS) # stderr is already DEVNULL
        stderr_output = "" # Initialize as empty since we redirected original stderr

        benchmark_time_ms = None
        for line in stdout.splitlines():
            if line.startswith("BENCHMARK_EXECUTION_TIME_MS:"):
                match = re.search(r"BENCHMARK_EXECUTION_TIME_MS: (\d+\.?\d*)", line)
                if match:
                    benchmark_time_ms = float(match.group(1))
                break
        return benchmark_time_ms, stdout, stderr_output

    except subprocess.TimeoutExpired:
        timeout_msg = f"Query timed out after {QUERY_TIMEOUT_SECONDS} seconds."
        if process:
            process.kill()
            try:
                stdout_after_kill, _ = process.communicate(timeout=1)
                stdout = stdout_after_kill if stdout_after_kill else ""
            except Exception:
                stdout = ""
        else:
            stdout = ""
        return None, stdout, timeout_msg

    except Exception as e:
        error_msg = f"An error occurred during query execution: {e}"
        if process:
            process.kill()
            try:
                process.communicate(timeout=1)
            except Exception:
                pass
        return None, "", error_msg

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Benchmark QueryCLI.java. Runs all strategy combinations.")
    query_group = parser.add_mutually_exclusive_group(required=True)
    query_group.add_argument("--query", help="The query string to execute.")
    query_group.add_argument("--query-file", help="Path to a file containing queries to execute (one per line).")

    parser.add_argument("-pd", "--projects-dir", help="Base directory for project data (e.g. where NYT corpus might be stored, if needed by other parts of a workflow, but not directly used for QueryCLI db/index paths anymore)")
    parser.add_argument("--jar-path", default=DEFAULT_JAR_PATH, help=f"Path to the QueryCLI JAR file (default: {DEFAULT_JAR_PATH})")

    parser.add_argument("--db-file", required=True, help="Path to the QueryCLI database file (e.g., projects/nyt/nyt.db).")
    parser.add_argument("--index-dir", required=True, help="Path to the QueryCLI index directory (e.g., projects/nyt_indexes).")

    parser.add_argument("--full", action="store_true", help="Run both cold and warm cache modes. Default: cold cache only.")

    parser.add_argument("--verbose", action="store_true", help="Print full QueryCLI stdout for each run.")
    parser.add_argument("--export-dir", help="Directory to save exported results from QueryCLI (one file per run).")
    parser.add_argument("--benchmark-output", help="CSV filename to save benchmark results (e.g., benchmark_results.csv). If not provided, only console summary is shown.")

    args = parser.parse_args()

    queries_to_run_orig = []
    if args.query_file:
        try:
            with open(args.query_file, 'r') as f:
                queries_to_run_orig = []
                for idx, line in enumerate(f):
                    line = line.strip()
                    if not line or line.startswith('#'):
                        continue
                    parts = line.split(' ::: ', 1)
                    query_text = parts[0]
                    expected_answer = parts[1] if len(parts) > 1 else None
                    queries_to_run_orig.append((idx, query_text, expected_answer))
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
        queries_to_run_orig.append((0, args.query, None))

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

    print(f"Using JAR: {args.jar_path}")
    print(f"Using DB File: {args.db_file}")
    print(f"Using Index Dir: {args.index_dir}")
    if args.projects_dir: # Only print if provided, as it's now optional
        print(f"Projects Dir (for other uses): {args.projects_dir}")

    if args.full:
        print("Cache Mode: full (cold + warm with 3 timed runs each)")
    else:
        print("Cache Mode: cold (queries shuffled)")
    print(f"Query timeout set to: {QUERY_TIMEOUT_SECONDS} seconds") # Print timeout info
    print(f"Verbose output: {args.verbose}")
    if args.export_dir:
        print(f"Exporting results to: {args.export_dir}")

    print("\n--- Command Execution Template ---")
    print(f"java --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -jar {args.jar_path} \\")
    print(f"     --db-file {args.db_file} \\" )
    print(f"     --index-dir {args.index_dir} \\" )
    print( "     --temporal-strategy {temporal_strategy_value} \\" )
    print( "     --pushdown-strategy {pushdown_strategy_value} \\" )
    print( "     --stitch-strategy {stitch_strategy_value} \\" )
    print( "     \"{query_text_from_file}\" \\" )
    print( "     [--export csv:{export_filename}]" )
    print("-------------------------------\n")

    temporal_strategies = ["naive", "nash"]
    pushdown_strategies_list = ["none", "optimized"]
    stitch_strategies_list = ["none", "optimized"]

    strategy_combinations = list(itertools.product(temporal_strategies, pushdown_strategies_list, stitch_strategies_list))

    cache_modes_to_run = ["cold"]
    if args.full:
        cache_modes_to_run = ["cold", "warm"]

    all_run_results = []
    current_run_count = 0

    for cache_mode in cache_modes_to_run:
        print(f"\n========== Running {cache_mode.upper()} cache mode ==========")

        if cache_mode == "cold":
            queries_to_run = list(queries_to_run_orig)
            random.shuffle(queries_to_run)
            print("Cold cache mode: Queries will be run in a random order for this benchmark execution.")
        else:  # warm mode
            queries_to_run = queries_to_run_orig

        # Enumerate provides exec_idx (execution order) and the tuple (original_idx, query_text, expected_answer)
        for exec_idx, (original_idx, query_text, expected_answer) in enumerate(queries_to_run):
            query_results_for_strategies_this_query = [] # Stores results for this query across all strategies
            original_query_id_str = f"q{original_idx+1}" # 1-based ID for filenames and logging

            print(f"========== Executing Query {exec_idx+1}/{len(queries_to_run)} (Original ID: {original_query_id_str}) [{cache_mode.upper()}]: {query_text} ==========")

            # Filter strategy combinations based on query content
            query_has_join = "JOIN" in query_text.upper()
            if query_has_join:
                current_strategy_combinations = strategy_combinations
                print(f"Query contains JOIN - testing all pushdown strategies")
            else:
                # Filter out "optimized" pushdown strategies for queries without JOIN
                current_strategy_combinations = [
                    (temp, push, stitch) for temp, push, stitch in strategy_combinations
                    if push == "none"
                ]
                print(f"Query does not contain JOIN - skipping optimized pushdown strategies")

            for strat_idx, (temporal_strategy_val, pushdown_strategy_val, stitch_strategy_val) in enumerate(current_strategy_combinations):
                current_run_count +=1
                # Update progress prefix to include original query ID and cache mode
                progress_prefix = f"[{cache_mode.upper()} - Exec Query {exec_idx+1}/{len(queries_to_run)} (Orig {original_query_id_str}), Strategy {strat_idx+1}/{len(current_strategy_combinations)}]"

                print(f"{progress_prefix} Running with Strategies: T:{temporal_strategy_val}, P:{pushdown_strategy_val}, S:{stitch_strategy_val}")

                timed_run_times_ms = []
                final_stdout = ""
                final_stderr = ""
                verification_status_for_run = "SKIPPED" # Default if no expected answer or export issues

                # Determine export filename base
                current_export_file_path = None # Will store the actual path to the CSV for the last timed run
                export_filename_base = None
                if args.export_dir:
                    safe_query_part = re.sub(r'[^a-zA-Z0-9_-]', '_', query_text)[:50] # Sanitize query for filename
                    # Use original_query_id_str (e.g., "q1", "q2") for the filename prefix
                    # MODIFIED to include cache mode in filename
                    export_filename_base = f"{original_query_id_str}_{safe_query_part}_T{temporal_strategy_val}_P{pushdown_strategy_val}_S{stitch_strategy_val}_{cache_mode}"

                # --- Cache Mode Logic ---
                if cache_mode == "warm":
                    warm_runs = 3  # HARDCODED
                    # 1. Warm-up run
                    warmup_export_path_str = None
                    if export_filename_base: # Ensure export_dir is also checked by os.path.join
                        warmup_export_path_str = os.path.join(args.export_dir, f"{export_filename_base}_warmup.csv")

                    _, warmup_stdout, warmup_stderr = run_query_cli(
                        query_text, args.db_file, args.index_dir,
                        temporal_strategy_val, pushdown_strategy_val, stitch_strategy_val, args.jar_path, warmup_export_path_str, is_verbose=args.verbose
                    )
                    if args.verbose and warmup_stdout: print(f"  Warm-up QueryCLI Output:\n{warmup_stdout}")
                    if warmup_stderr: final_stderr += warmup_stderr if warmup_stderr else "" # Collect stderr

                    # Optional short delay after warm-up, can sometimes help ensure caches are fully "warmed"
                    # time.sleep(0.1)

                    # 2. Timed runs
                    for run_num in range(warm_runs):
                        timed_export_path_str = os.path.join(args.export_dir, f"{export_filename_base}_timed{run_num+1}.csv") if export_filename_base else None
                        if run_num == warm_runs - 1 and timed_export_path_str:
                            current_export_file_path = timed_export_path_str

                        time_taken, stdout_output, stderr_output = run_query_cli(
                            query_text, args.db_file, args.index_dir,
                            temporal_strategy_val, pushdown_strategy_val, stitch_strategy_val, args.jar_path, timed_export_path_str, is_verbose=args.verbose
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

                else:  # cold mode
                    cold_runs = 3 # Number of timed runs for cold mode
                    for run_num in range(cold_runs):
                        # Each cold run gets its own export file if export_dir is specified
                        cold_run_export_path_str = None
                        if export_filename_base:
                            cold_run_export_path_str = os.path.join(args.export_dir, f"{export_filename_base}_cold_run{run_num+1}.csv")
                            if run_num == cold_runs - 1: # Last run's export path for verification
                                current_export_file_path = cold_run_export_path_str

                        time_taken, stdout_output, stderr_output = run_query_cli(
                            query_text, args.db_file, args.index_dir,
                            temporal_strategy_val, pushdown_strategy_val, stitch_strategy_val, args.jar_path, cold_run_export_path_str, is_verbose=args.verbose
                        )
                        if time_taken is not None:
                            timed_run_times_ms.append(time_taken)
                            print(f"{progress_prefix}    BENCHMARK_EXECUTION_TIME_MS (Cold Run {run_num+1}): {time_taken:.3f}")
                        else:
                            print(f"{progress_prefix}    Failed to retrieve benchmark time for Cold Run {run_num+1}.")

                        if args.verbose and stdout_output:
                            if run_num == 0: final_stdout = "" # Clear previous stdout for cold mode runs if verbose
                            final_stdout += f"--- Cold Run {run_num+1} Output ---\n{stdout_output}\n"
                        if stderr_output:
                            if run_num == 0: final_stderr = "" # Clear previous stderr for cold mode runs
                            final_stderr += f"--- Cold Run {run_num+1} Errors ---\n{stderr_output}\n"

                        if args.verbose and stdout_output: print(f"  Cold Run {run_num+1} QueryCLI Output:\n{stdout_output}")
                        if stderr_output: print(f"  Cold Run {run_num+1} QueryCLI Errors:\n{stderr_output}")

                # Calculate average time for this query/strategy combination
                avg_time_ms = None
                if timed_run_times_ms:
                    avg_time_ms = sum(timed_run_times_ms) / len(timed_run_times_ms)

                # --- Verification Step ---
                if avg_time_ms is None and "timed out" in final_stderr.lower():
                    verification_status_for_run = "TIMEOUT"
                elif expected_answer: # Proceed with normal verification if not a timeout that prevented any result
                    content_to_verify = ""
                    source_of_verification = ""
                    if current_export_file_path and os.path.exists(current_export_file_path):
                        try:
                            with open(current_export_file_path, 'r', encoding='utf-8') as f_verify:
                                content_to_verify = f_verify.read()
                            source_of_verification = f"exported file ({os.path.basename(current_export_file_path)})"
                        except Exception as e_verify:
                            verification_status_for_run = "ERROR_READING_EXPORT"
                    elif final_stdout: # Fallback to stdout if no export or export failed
                        content_to_verify = final_stdout
                        source_of_verification = "QueryCLI stdout"
                    else:
                        verification_status_for_run = "NO_OUTPUT_TO_VERIFY"

                    if content_to_verify and verification_status_for_run not in ["ERROR_READING_EXPORT", "NO_OUTPUT_TO_VERIFY", "TIMEOUT"]:
                        if expected_answer.lower() in content_to_verify.lower():
                            verification_status_for_run = "PASSED"
                        else:
                            verification_status_for_run = "FAILED"
                    elif verification_status_for_run not in ["ERROR_READING_EXPORT", "NO_OUTPUT_TO_VERIFY", "TIMEOUT"]:
                         verification_status_for_run = "EMPTY_OUTPUT_CONTENT"

                result_entry = {
                    "original_query_id": original_query_id_str, # Store the "qN" identifier
                    "original_idx": original_idx, # Store the 0-based original index
                    "query_text": query_text,
                    "expected_answer": expected_answer, # Store expected answer
                    "verification_status": verification_status_for_run, # Store verification status
                    "temporal_strategy": temporal_strategy_val,
                    "pushdown_strategy": pushdown_strategy_val,
                    "stitch_strategy": stitch_strategy_val,
                    "time_ms": avg_time_ms, # This is now an average for warm mode
                    "individual_times_ms": list(timed_run_times_ms), # Store all timed runs
                    "cache_mode": cache_mode,
                    # Storing full stdout/stderr can be memory intensive; only store if verbose or errors.
                    "stdout": final_stdout if args.verbose else ("See exported files" if args.export_dir and avg_time_ms is not None else ""),
                    "stderr": final_stderr if final_stderr else ""
                }
                query_results_for_strategies_this_query.append(result_entry)

                if final_stderr and not args.verbose: # Print stderr if not already shown by verbose and contains something
                    # This print is for actual errors like timeouts, not verbose QueryCLI output
                    print(f"  {progress_prefix} QueryCLI Errors/Info:\n{final_stderr}")
            # ADDED: Append the results for this query/cache_mode combination to all_run_results
            all_run_results.append(query_results_for_strategies_this_query)

    # Enhanced summary
    print("\n========== Benchmark Overall Summary ==========")
    for exec_idx, query_strategy_results in enumerate(all_run_results):
        if not query_strategy_results:
            print(f"No results for executed query {exec_idx+1}.")
            continue

        # All results in query_strategy_results are for the same original query
        first_result_for_query = query_strategy_results[0]
        original_id = first_result_for_query['original_query_id']
        q_text = first_result_for_query['query_text']
        cache_mode = first_result_for_query['cache_mode']

        print(f"\n--- Results for Query (Original ID: {original_id}, Cache: {cache_mode.upper()}): {q_text} ---")
        print(f"  Cache Mode during these runs: {cache_mode}")
        if cache_mode == 'warm':
            print(f"  (Warm mode: 1 warm-up run, 3 timed runs per strategy)")
        elif cache_mode == 'cold':
            print(f"  (Cold mode: 3 timed runs per strategy)")
        print("  Strategy                            | Avg Time (ms) | Individual Times (ms) | Verification")
        print("  ------------------------------------|---------------|-----------------------|---------------")

        # Sort strategies for consistent output, e.g., by name or a predefined order if necessary
        # For now, printing in order of execution for strategies.
        for result in query_strategy_results:
            strategy_str = f"T:{result['temporal_strategy']}, P:{result['pushdown_strategy']}, S:{result['stitch_strategy']}".ljust(35)
            avg_time_str = f"{result['time_ms']:.3f}" if result['time_ms'] is not None else "N/A"
            avg_time_str = avg_time_str.rjust(13)

            individual_times_str = ", ".join([f"{t:.3f}" for t in result['individual_times_ms']])
            if not result['individual_times_ms']:
                individual_times_str = "N/A"
            individual_times_str = individual_times_str.ljust(21) # Adjust spacing

            verification_display = result.get('verification_status', 'SKIPPED') # Default if key missing

            print(f"  {strategy_str} | {avg_time_str} | {individual_times_str} | {verification_display}")

            if result['stderr']:
                # Indent stderr for readability under its respective strategy run
                indented_stderr = "\n".join([f"    Error: {line}" for line in result['stderr'].strip().split('\n')])
                print(indented_stderr)

    print("\n=====================================")
    print("Benchmark finished.")

    # Calculate and print average times per strategy combination across all queries
    print("\n----- Average Times Per Strategy Combination (across all successful queries) -----")
    summary_by_strategy = {}
    for temp_strat, push_strat, stitch_strat in strategy_combinations:
        key = (temp_strat, push_strat, stitch_strat)
        times_for_combo = []
        # For verification summary, we can count PASSED/FAILED/SKIPPED per strategy
        verification_counts = {"PASSED": 0, "FAILED": 0, "SKIPPED": 0, "ERROR_READING_EXPORT": 0, "NO_OUTPUT_TO_VERIFY": 0, "EMPTY_OUTPUT_CONTENT":0 }

        for query_res_list in all_run_results:
            for res_entry in query_res_list:
                if (res_entry['temporal_strategy'] == temp_strat and
                    res_entry['pushdown_strategy'] == push_strat and
                    res_entry['stitch_strategy'] == stitch_strat):
                    if res_entry['time_ms'] is not None:
                        times_for_combo.append(res_entry['time_ms'])
                    status = res_entry.get('verification_status', 'SKIPPED')
                    if status in verification_counts:
                        verification_counts[status] += 1
                    else: # Should not happen if all statuses are handled
                        verification_counts["SKIPPED"] +=1


        avg_time_for_combo_str = "N/A (no successful runs)"
        if times_for_combo:
            avg_time_for_combo = sum(times_for_combo) / len(times_for_combo)
            avg_time_for_combo_str = f"{avg_time_for_combo:.3f} ms ({len(times_for_combo)} queries)"

        verification_summary_str = ", ".join([f"{k}:{v}" for k,v in verification_counts.items() if v > 0])
        if not verification_summary_str: verification_summary_str = "All Skipped or N/A"


        strat_key_str = f"T:{temp_strat}, P:{push_strat}, S:{stitch_strat}"
        print(f"  {strat_key_str.ljust(45)}: {avg_time_for_combo_str.ljust(25)} Verification: {verification_summary_str}")

    total_successful_timed_runs = sum(1 for qr_list in all_run_results for r in qr_list if r['time_ms'] is not None)
    total_verified_passed = sum(1 for qr_list in all_run_results for r in qr_list if r.get('verification_status') == 'PASSED')

    # Calculate total_verification_attempts based on entries that had an expected_answer
    total_verification_attempts = 0
    for qr_list in all_run_results:
        for r_entry in qr_list:
            if r_entry.get('expected_answer') is not None:
                total_verification_attempts +=1

    print(f"\nTotal benchmark strategy executions attempted: {current_run_count}")
    print(f"(Note: Queries without JOIN keyword skip optimized pushdown strategies)")
    print(f"Total successful timed primary metric calculations: {total_successful_timed_runs}")

    if total_verification_attempts > 0:
        print(f"Total verification checks made (for queries with expected answers): {total_verification_attempts}")
        print(f"Total verifications PASSED: {total_verified_passed}")
    else:
        print("No verification checks made (no expected answers provided in query file or all queries with them failed early).")

    if args.benchmark_output:
        print(f"\nExporting benchmark results to: {args.benchmark_output}")
        try:
            with open(args.benchmark_output, 'w', newline='', encoding='utf-8') as csvfile:
                fieldnames = [
                    'original_query_id', 'original_query_index', 'query_text', 'expected_answer',
                    'temporal_strategy', 'pushdown_strategy', 'stitch_strategy',
                    'cache_mode', 'avg_time_ms', 'individual_times_ms', 'verification_status',
                    'execution_order', 'strategy_combination_index'
                ]
                writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
                writer.writeheader()

                for exec_idx, query_strategy_results in enumerate(all_run_results):
                    for strat_idx, result in enumerate(query_strategy_results):
                        individual_times_str = ';'.join([f"{t:.3f}" for t in result.get('individual_times_ms', [])])

                        csv_row = {
                            'original_query_id': result.get('original_query_id', ''),
                            'original_query_index': result.get('original_idx', ''),
                            'query_text': result.get('query_text', ''),
                            'expected_answer': result.get('expected_answer', ''),
                            'temporal_strategy': result.get('temporal_strategy', ''),
                            'pushdown_strategy': result.get('pushdown_strategy', ''),
                            'stitch_strategy': result.get('stitch_strategy', ''),
                            'cache_mode': result.get('cache_mode', ''),
                            'avg_time_ms': result.get('time_ms', ''),
                            'individual_times_ms': individual_times_str,
                            'verification_status': result.get('verification_status', ''),
                            'execution_order': exec_idx + 1,
                            'strategy_combination_index': strat_idx + 1
                        }
                        writer.writerow(csv_row)

            print(f"Benchmark results successfully exported to {args.benchmark_output}")
        except Exception as e:
            print(f"Error exporting benchmark results to CSV: {e}")