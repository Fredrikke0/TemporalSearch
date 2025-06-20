import argparse
import csv
import itertools
import os
import queue
import random
import re
import subprocess
import threading
import time
import traceback

DEFAULT_JAR_PATH = "target/query-cli.jar"
# Timeouts for interactive mode
QUERY_EXEC_TIMEOUT_SECONDS = 60 * 15  # 15 minutes for a single query to execute and return PROMPT
CLI_STARTUP_TIMEOUT_SECONDS = 60 * 5    # Timeout for QueryCLI to start and print initial PROMPT
COMMAND_ACK_TIMEOUT_SECONDS = 30    # Timeout for ACK responses from QueryCLI (SET STRATEGY, SET OUTPUT)
PROCESS_TERMINATION_TIMEOUT_SECONDS = 15 # Timeout for QueryCLI to exit after QUIT command

PROMPT = "Query>" # Define the new prompt

# Helper function for reading a stream in a separate thread
def _enqueue_output(stream, q, stream_name, is_verbose):
    try:
        for line in iter(stream.readline, ''):
            q.put(line)
            if is_verbose:
                print(f"[CLI {stream_name.upper()}] {line.strip()}", flush=True)
    except ValueError: # I/O operation on closed file.
        if is_verbose: print(f"[BENCHMARK.PY DEBUG] ValueError in _enqueue_output for {stream_name} (stream closed).", flush=True)
        pass
    except Exception as e:
        print(f"[BENCHMARK.PY DEBUG] Unhandled exception in _enqueue_output for {stream_name}: {e}", flush=True)
    finally:
        q.put(None) # Sentinel to indicate EOF or error, ensuring consumers can terminate.

class QueryCLIInteractiveProcess:
    def __init__(self, jar_path, db_file, index_dir, initial_temporal_strategy, initial_pushdown_strategy, initial_stitch_strategy, is_verbose=False):
        print("[BENCHMARK.PY] Initializing QueryCLIInteractiveProcess...", flush=True)
        self.jar_path = jar_path
        self.db_file = db_file
        self.index_dir = index_dir
        self.is_verbose = is_verbose
        self.process = None
        self._last_command_timestamp = time.monotonic()

        self._stdout_q = queue.Queue()
        self._stderr_q = queue.Queue()
        self._stdout_thread = None
        self._stderr_thread = None

        self._current_cycle_stdout = []
        self._current_cycle_stderr = []

        if not os.path.exists(jar_path):
            raise FileNotFoundError(f"Error: JAR file not found at {jar_path}. Please build the project.")
        if not os.path.exists(db_file):
            raise FileNotFoundError(f"Error: Database file not found: {db_file}")

        command = [
            "java",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow",
            "-jar", jar_path,
            "--db-file", db_file,
            "--index-dir", index_dir,
            "--temporal-strategy", initial_temporal_strategy,
            "--pushdown-strategy", initial_pushdown_strategy,
            "--stitch-strategy", initial_stitch_strategy
        ]

        if self.is_verbose:
            print(f"[BENCHMARK.PY DEBUG] Starting QueryCLI: {' '.join(command)}", flush=True)

        try:
            self.process = subprocess.Popen(
                command,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True, bufsize=1, universal_newlines=True
            )

            self._stdout_thread = threading.Thread(target=_enqueue_output, args=(self.process.stdout, self._stdout_q, "stdout", self.is_verbose))
            self._stderr_thread = threading.Thread(target=_enqueue_output, args=(self.process.stderr, self._stderr_q, "stderr", self.is_verbose))
            self._stdout_thread.daemon = True
            self._stderr_thread.daemon = True
            self._stdout_thread.start()
            self._stderr_thread.start()

            initial_prompt_found, _, _ = self._read_until_prompts([PROMPT], timeout_seconds=CLI_STARTUP_TIMEOUT_SECONDS)
            if not initial_prompt_found:
                stdout_at_fail, stderr_at_fail = self._collect_current_cycle_output(clear_buffers=True)
                self.close()
                raise TimeoutError(f"QueryCLI did not emit initial '{PROMPT}' prompt within {CLI_STARTUP_TIMEOUT_SECONDS}s.\nStdout: {stdout_at_fail}\nStderr: {stderr_at_fail}")

            if self.is_verbose:
                print(f"[BENCHMARK.PY DEBUG] QueryCLI started successfully and '{PROMPT}' received.", flush=True)

        except FileNotFoundError as e:
             raise FileNotFoundError(f"Failed to start QueryCLI java process: {e}. Is Java installed and in PATH, and is JAR path correct?")
        except Exception as e:
            stdout_at_fail, stderr_at_fail = self._collect_current_cycle_output(clear_buffers=True)
            if self.process: self.close()
            raise RuntimeError(f"Failed to start or initialize QueryCLI process: {e}.\nStdout: {stdout_at_fail}\nStderr: {stderr_at_fail}")

    def _drain_queue_into_list(self, q, target_list, stop_event=None):
        """Drains all available items from a queue into a list. Returns True if sentinel (None) was hit."""
        sentinel_hit = False
        while True:
            try:
                item = q.get_nowait()
                if item is None: # Sentinel found
                    sentinel_hit = True
                    break
                target_list.append(item)
            except queue.Empty:
                break # Queue is empty
        return sentinel_hit

    def _read_until_prompts(self, target_prompts, timeout_seconds):
        start_time = time.monotonic()
        call_specific_stdout = []
        call_specific_stderr = []

        stdout_eof = False
        stderr_eof = False

        while True:
            if self.process is None or self.process.poll() is not None:
                if self.is_verbose: print("[BENCHMARK.PY DEBUG] QueryCLI process terminated unexpectedly.", flush=True)
                stdout_eof = self._drain_queue_into_list(self._stdout_q, call_specific_stdout) or stdout_eof
                stderr_eof = self._drain_queue_into_list(self._stderr_q, call_specific_stderr) or stderr_eof
                break

            if not stdout_eof:
                stdout_eof = self._drain_queue_into_list(self._stdout_q, call_specific_stdout)

            if not stderr_eof:
                stderr_eof = self._drain_queue_into_list(self._stderr_q, call_specific_stderr)

            for line_idx in range(len(call_specific_stdout) - 1, -1, -1):
                line = call_specific_stdout[line_idx]
                if isinstance(line, str): # Ensure it's a string (not None sentinel if it somehow got in list)
                    for prompt_text in target_prompts:
                        if prompt_text in line:
                            if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] Found prompt '{prompt_text}'", flush=True)
                            return prompt_text, "".join(call_specific_stdout), "".join(call_specific_stderr)

            if time.monotonic() - start_time > timeout_seconds:
                if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] Timeout ({timeout_seconds}s) waiting for {target_prompts}. Time since command: {time.monotonic() - self._last_command_timestamp:.2f}s", flush=True)
                break

            if stdout_eof and stderr_eof and self._stdout_q.empty() and self._stderr_q.empty():
                 if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] Both streams EOF and queues empty, but prompt not found.", flush=True)
                 break

            time.sleep(0.01)

        if not stdout_eof: self._drain_queue_into_list(self._stdout_q, call_specific_stdout)
        if not stderr_eof: self._drain_queue_into_list(self._stderr_q, call_specific_stderr)

        return None, "".join(call_specific_stdout), "".join(call_specific_stderr)

    def _collect_current_cycle_output(self, clear_buffers=True):
        """ Collects all output from the queues into the _current_cycle_ lists and returns them. """
        self._drain_queue_into_list(self._stdout_q, self._current_cycle_stdout)
        self._drain_queue_into_list(self._stderr_q, self._current_cycle_stderr)

        stdout_output = "".join(self._current_cycle_stdout)
        stderr_output = "".join(self._current_cycle_stderr)

        if clear_buffers:
            self._current_cycle_stdout = []
            self._current_cycle_stderr = []
        return stdout_output, stderr_output

    def send_command(self, command_str):
        if self.process is None or self.process.poll() is not None:
            stdout_final, stderr_final = self._collect_current_cycle_output(clear_buffers=False)
            raise ConnectionAbortedError(f"QueryCLI process not running. Cannot send '{command_str}'.\nStdout: {stdout_final}\nStderr: {stderr_final}")

        if self.is_verbose: print(f"[CLI INPUT] {command_str}", flush=True)

        self._current_cycle_stdout = []
        self._current_cycle_stderr = []
        self._last_command_timestamp = time.monotonic()

        try:
            if self.process.stdin:
                self.process.stdin.write(command_str + "\n")
                self.process.stdin.flush()
            else:
                # This case should ideally not be reached if Popen initializes stdin=PIPE correctly
                # and stdin hasn't been closed and set to None.
                stdout_final, stderr_final = self._collect_current_cycle_output(clear_buffers=False)
                raise ConnectionAbortedError(f"QueryCLI process stdin is not available (None). Cannot send '{command_str}'.\nStdout: {stdout_final}\nStderr: {stderr_final}")
        except (IOError, ValueError) as e: # Catch BrokenPipeError (subclass of IOError) or ValueError if stdin closed
            stdout_final, stderr_final = self._collect_current_cycle_output(clear_buffers=False)
            raise ConnectionAbortedError(f"IOError/ValueError sending '{command_str}' (QueryCLI died or stdin closed?): {e}.\nStdout: {stdout_final}\nStderr: {stderr_final}")

    def set_strategy(self, temporal, pushdown, stitch):
        cmd = f"SET STRATEGY temporal={temporal} pushdown={pushdown} stitch={stitch}"
        self.send_command(cmd)

        # Now only wait for the single PROMPT
        prompt_found_str, stdout_cycle, stderr_cycle = self._read_until_prompts([PROMPT], timeout_seconds=COMMAND_ACK_TIMEOUT_SECONDS) # Or a general prompt timeout

        if not prompt_found_str:
            raise TimeoutError(f"Timeout/Error waiting for '{PROMPT}' after SET STRATEGY.\nCmd: {cmd}\nStdout: {stdout_cycle}\nStderr: {stderr_cycle}")

        return True, stdout_cycle, stderr_cycle

    def set_output(self, export_format=None, filename=None):
        cmd = f"SET OUTPUT {export_format} {filename}" if export_format and filename else "SET OUTPUT NONE"
        self.send_command(cmd)

        # Now only wait for the single PROMPT
        prompt_found_str, stdout_cycle, stderr_cycle = self._read_until_prompts([PROMPT], timeout_seconds=COMMAND_ACK_TIMEOUT_SECONDS) # Or a general prompt timeout

        if not prompt_found_str:
            raise TimeoutError(f"Timeout/Error waiting for '{PROMPT}' after SET OUTPUT.\nCmd: {cmd}\nStdout: {stdout_cycle}\nStderr: {stderr_cycle}")

        return True, stdout_cycle, stderr_cycle

    def execute_query(self, query_string):
        self.send_command(query_string)

        query_complete_prompt, full_stdout_query, stderr_query = self._read_until_prompts([PROMPT], timeout_seconds=QUERY_EXEC_TIMEOUT_SECONDS)

        benchmark_time_ms = None
        if full_stdout_query:
            for line in full_stdout_query.splitlines(): # Search in combined output
                if line.startswith("BENCHMARK_EXECUTION_TIME_MS:"):
                    match = re.search(r"BENCHMARK_EXECUTION_TIME_MS: (\d+\.?\d*)", line)
                    if match: benchmark_time_ms = float(match.group(1))
                    break

        if not query_complete_prompt:
            elapsed_time = time.monotonic() - self._last_command_timestamp
            additional_err_msg = f"\nPYTHON_BENCHMARK: Query '{query_string[:50]}...' did not complete with {PROMPT}. Elapsed: {elapsed_time:.2f}s."
            if elapsed_time >= QUERY_EXEC_TIMEOUT_SECONDS:
                 additional_err_msg += f" (Exceeded timeout of {QUERY_EXEC_TIMEOUT_SECONDS}s)"
            stderr_query = (stderr_query or "") + additional_err_msg

        return benchmark_time_ms, full_stdout_query, stderr_query

    def close(self):
        if self.is_verbose: print("[BENCHMARK.PY DEBUG] Attempting to close QueryCLI process interface...", flush=True)

        if self.process and self.process.poll() is None:
            if self.is_verbose: print("[BENCHMARK.PY DEBUG] Sending EXIT to QueryCLI stdin.", flush=True)
            try:
                if self.process.stdin and not self.process.stdin.closed:
                    self.process.stdin.write("EXIT\n")
                    self.process.stdin.flush()
                    self.process.stdin.close()
            except (IOError, ValueError) as e_stdin:
                if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] Error closing QueryCLI stdin (possibly already closed): {e_stdin}", flush=True)

        if self.process:
            if self.is_verbose: print("[BENCHMARK.PY DEBUG] Waiting for QueryCLI process to terminate...", flush=True)
            try:
                self.process.wait(timeout=PROCESS_TERMINATION_TIMEOUT_SECONDS)
                if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] QueryCLI process terminated with rc: {self.process.returncode}", flush=True)
            except subprocess.TimeoutExpired:
                if self.is_verbose: print("[BENCHMARK.PY DEBUG] QueryCLI did not terminate after EXIT and wait, killing.", flush=True)
                self.process.kill()
                try:
                    self.process.wait(timeout=PROCESS_TERMINATION_TIMEOUT_SECONDS / 2)
                except subprocess.TimeoutExpired:
                    if self.is_verbose: print("[BENCHMARK.PY DEBUG] QueryCLI kill command also timed out.", flush=True)
                except Exception as e_kill_wait_final:
                    if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] Exception during post-kill wait: {e_kill_wait_final}", flush=True)
            except Exception as e_wait:
                if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] Error waiting for QueryCLI process: {e_wait}. Attempting kill.", flush=True)
                self.process.kill()
                try: self.process.wait(timeout=PROCESS_TERMINATION_TIMEOUT_SECONDS / 2)
                except Exception as e_kill_generic:
                    if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] Exception during post-error kill wait: {e_kill_generic}", flush=True)

        if self._stdout_thread and self._stdout_thread.is_alive():
            if self.is_verbose: print("[BENCHMARK.PY DEBUG] Waiting for stdout reader thread to join...", flush=True)
            self._stdout_thread.join(timeout=5)
            if self._stdout_thread.is_alive():
                 if self.is_verbose: print("[BENCHMARK.PY DEBUG] Stdout reader thread did not join in time.", flush=True)

        if self._stderr_thread and self._stderr_thread.is_alive():
            if self.is_verbose: print("[BENCHMARK.PY DEBUG] Waiting for stderr reader thread to join...", flush=True)
            self._stderr_thread.join(timeout=5)
            if self._stderr_thread.is_alive():
                 if self.is_verbose: print("[BENCHMARK.PY DEBUG] Stderr reader thread did not join in time.", flush=True)

        final_stdout, final_stderr = self._collect_current_cycle_output(clear_buffers=True)
        if self.is_verbose and (final_stdout or final_stderr):
            print(f"[BENCHMARK.PY DEBUG] Final output drained post-close: STDOUT_LEN={len(final_stdout)}, STDERR_LEN={len(final_stderr)}", flush=True)

        self.process = None
        if self.is_verbose: print("[BENCHMARK.PY DEBUG] QueryCLI process interface definitively closed.", flush=True)


if __name__ == "__main__":
    # print("[BENCHMARK.PY] Initializing benchmark script...", flush=True) # Loading message removed
    parser = argparse.ArgumentParser(description="Benchmark QueryCLI.java using interactive mode.")
    parser.add_argument("--query-dir", required=True, help="Path to a directory containing query files. Each file's first line must be 'BENCHMARK_TYPE: <TYPE>', where TYPE is 1HOP, 2HOP, or 3HOP.")
    parser.add_argument("--jar-path", default=DEFAULT_JAR_PATH, help=f"Path to QueryCLI JAR (default: {DEFAULT_JAR_PATH})")
    parser.add_argument("--db-file", required=True, help="Path to QueryCLI SQLite database file.")
    parser.add_argument("--index-dir", required=True, help="Path to QueryCLI index directory.")
    parser.add_argument("--initial-temporal-strategy", default="nash", choices=["naive", "nash"], help="Initial temporal strategy for QueryCLI.")
    parser.add_argument("--initial-pushdown-strategy", default="optimized", choices=["none", "optimized"], help="Initial pushdown strategy.")
    parser.add_argument("--initial-stitch-strategy", default="optimized", choices=["none", "optimized"], help="Initial stitch strategy.")
    parser.add_argument("--full", action="store_true", help="Run cold and warm cache modes. Default: cold only.")
    parser.add_argument("--runs-per-query", type=int, default=3, help="Number of timed runs per query/strategy (after warmup for warm mode).")
    parser.add_argument("--verbose", action="store_true", help="Verbose output from benchmark script and QueryCLI.")
    parser.add_argument("--export-dir", help="Directory for exported CSV results (for verification). NOTE: This is for raw query output, not the benchmark summary.")
    args = parser.parse_args()

    queries_to_run_orig = []
    if args.query_dir:
        if not os.path.isdir(args.query_dir):
            exit(f"Error: Query directory not found: {args.query_dir}")

        query_files = [f for f in os.listdir(args.query_dir) if os.path.isfile(os.path.join(args.query_dir, f))]
        if not query_files:
            exit(f"Error: No files found in query directory: {args.query_dir}")

        query_id_counter = 0
        for filename in query_files:
            filepath = os.path.join(args.query_dir, filename)
            try:
                with open(filepath, 'r', encoding='utf-8') as f:
                    first_line = f.readline().strip()
                    if not first_line.startswith("BENCHMARK_TYPE:"):
                        print(f"Warning: File {filename} does not start with 'BENCHMARK_TYPE:'. Skipping.", flush=True)
                        continue

                    try:
                        benchmark_type_str = first_line.split(":", 1)[1].strip().upper()
                        if benchmark_type_str not in ["1HOP", "2HOP", "3HOP"]:
                            print(f"Warning: File {filename} has invalid BENCHMARK_TYPE '{benchmark_type_str}'. Must be 1HOP, 2HOP, or 3HOP. Skipping.", flush=True)
                            continue
                    except IndexError:
                        print(f"Warning: File {filename} has malformed 'BENCHMARK_TYPE:' line. Skipping.", flush=True)
                        continue

                    file_queries_loaded = 0
                    for line_idx, line in enumerate(f, start=1): # Start line_idx from 1 for user messages
                        line = line.strip()
                        if not line or line.startswith('#'): continue
                        parts = line.split(' ::: ', 1)
                        queries_to_run_orig.append({
                            'id': query_id_counter,
                            'text': parts[0],
                            'expected': parts[1] if len(parts) > 1 else None,
                            'benchmark_type': benchmark_type_str, # Add benchmark_type
                            'source_file': filename
                        })
                        query_id_counter += 1
                        file_queries_loaded +=1
                    if file_queries_loaded == 0:
                         print(f"Warning: File {filename} (Type: {benchmark_type_str}) contained no queries after the type definition. Skipping file's contribution to benchmark.", flush=True)

            except FileNotFoundError: # Should not happen given os.isfile check, but defensive
                 print(f"Warning: File {filename} not found during processing (should not occur). Skipping.", flush=True)
            except Exception as e:
                 print(f"Warning: Error reading query file {filename}: {e}. Skipping.", flush=True)
    else: # Should be caught by required=True in argparse
        exit("Error: --query-dir is a required argument.")


    if not queries_to_run_orig: exit("No valid queries loaded from any file in the query directory. Exiting.")

    if args.export_dir and not os.path.exists(args.export_dir):
        try: os.makedirs(args.export_dir); print(f"Created export directory: {args.export_dir}", flush=True)
        except OSError as e: exit(f"Error creating export directory {args.export_dir}: {e}")

    print(f"Using JAR: {args.jar_path}", flush=True)
    print(f"Using DB File: {args.db_file}", flush=True)
    print(f"Using Index Dir: {args.index_dir}", flush=True)
    print(f"Initial CLI strategies: T:{args.initial_temporal_strategy}, P:{args.initial_pushdown_strategy}, S:{args.initial_stitch_strategy}", flush=True)
    print(f"Cache Mode: {'full (cold + warm)' if args.full else 'cold only'}. Runs per query/strategy: {args.runs_per_query}", flush=True)
    print(f"Timeouts: QueryExec={QUERY_EXEC_TIMEOUT_SECONDS}s, CLIStartup={CLI_STARTUP_TIMEOUT_SECONDS}s, CmdAck={COMMAND_ACK_TIMEOUT_SECONDS}s, PromptWait={PROMPT}", flush=True)
    if args.verbose: print("Verbose output enabled.", flush=True)
    if args.export_dir: print(f"Verification exports (if any) to: {args.export_dir}", flush=True)
    print(f"Benchmark summary CSVs will be saved alongside their respective input query files in: {args.query_dir}", flush=True)

    all_run_results_accumulator = []
    cli_process = None
    try:
        cli_process = QueryCLIInteractiveProcess(
            args.jar_path, args.db_file, args.index_dir,
            args.initial_temporal_strategy, args.initial_pushdown_strategy, args.initial_stitch_strategy,
            is_verbose=args.verbose
        )
        print("\n[BENCHMARK.PY] QueryCLI process initialized for interactive benchmarking.", flush=True)

        temporal_strategies_base = ["naive", "nash"]
        pushdown_strategies_base = ["none", "optimized"]
        stitch_strategies_base = ["none", "optimized"]
        # Removed: strategy_combinations = list(itertools.product(temporal_strategies, pushdown_strategies_list, stitch_strategies_list))

        cache_modes = ["cold"]
        if args.full: cache_modes.append("warm")

        for cache_mode in cache_modes:
            print(f"\n========== RUNNING CACHE MODE: {cache_mode.upper()} ==========", flush=True)
            current_queries_for_cache_mode = list(queries_to_run_orig) # Make a mutable copy for this cache mode
            if cache_mode == "cold":
                random.shuffle(current_queries_for_cache_mode)
                print(f"  Cold Cache Mode: Queries shuffled. {args.runs_per_query} timed runs per setting.", flush=True)
            else:
                print(f"  Warm Cache Mode: Queries in order. 1 warm-up + {args.runs_per_query} timed runs per setting.", flush=True)

            for query_info in current_queries_for_cache_mode:
                original_query_id_str = f"q{query_info['id']+1}" # Use the global unique ID
                query_text = query_info['text']
                expected_answer = query_info['expected']
                benchmark_type = query_info['benchmark_type'] # Get benchmark_type
                source_file = query_info['source_file']

                print(f"\n--- Query {original_query_id_str} (File: {source_file}, Type: {benchmark_type}) [{cache_mode.upper()}]: {query_text[:100]}{'...' if len(query_text)>100 else ''} ---", flush=True)

                # Determine strategy combinations for the current query based on BENCHMARK_TYPE
                current_temporal_strategies = list(temporal_strategies_base)
                current_stitch_strategies = list(stitch_strategies_base)

                if benchmark_type == "1HOP":
                    current_pushdown_strategies = ["none"]
                    if args.verbose: print(f"    (Q:{original_query_id_str} - Type {benchmark_type}: Pushdown strategy fixed to 'none')", flush=True)
                else: # 2HOP or 3HOP
                    current_pushdown_strategies = list(pushdown_strategies_base)
                    if args.verbose: print(f"    (Q:{original_query_id_str} - Type {benchmark_type}: Using all pushdown strategies {current_pushdown_strategies})", flush=True)

                # Rule: Nash optimization (temporal strategy) only if "DATE" keyword is in the query.
                # This rule is kept as it's general, not specific to 1/2/3HOP types.
                has_date_keyword_for_nash = "DATE" in query_text.upper()
                if "nash" in current_temporal_strategies and not has_date_keyword_for_nash:
                    current_temporal_strategies.remove("nash")
                    if args.verbose: print(f"    (Q:{original_query_id_str} - Removing NASH temporal strategy as query lacks 'DATE' keyword. Remaining: {current_temporal_strategies})", flush=True)
                if not current_temporal_strategies: # If removing nash leaves no temporal strategies (e.g. if base was only nash)
                    if args.verbose: print(f"    (Q:{original_query_id_str} - No temporal strategies applicable after DATE keyword check. Skipping this query for this cache mode.)", flush=True)
                    continue


                strategies_to_run_for_this_query = list(itertools.product(
                    current_temporal_strategies,
                    current_pushdown_strategies,
                    current_stitch_strategies
                ))

                if not strategies_to_run_for_this_query:
                    if args.verbose: print(f"    (Q:{original_query_id_str} - No strategy combinations to run after filtering by BENCHMARK_TYPE and DATE keyword. This query will be skipped for this cache mode.)", flush=True)
                    continue

                # The old complex filtering logic based on JOIN, is_temporal_join_condition, has_granularity_sentence_for_stitch is REMOVED.
                # Lines 440-491 in the original file are effectively replaced by the BENCHMARK_TYPE logic above.

                current_strats_for_query = strategies_to_run_for_this_query # Already computed

                for temp_s, push_s, stitch_s in current_strats_for_query:
                    progress_prefix = f"    [{cache_mode.upper()} Q:{original_query_id_str} File:{source_file} T:{temp_s},P:{push_s},S:{stitch_s},BT:{benchmark_type}]"
                    print(f"{progress_prefix} Preparing...", flush=True)

                    run_stdout_details = "" # For verbose or last run verification
                    run_stderr_details = "" # Accumulates stderr across setup and runs

                    try:
                        _, _, strat_set_stderr = cli_process.set_strategy(temp_s, push_s, stitch_s)
                        if strat_set_stderr: run_stderr_details += f"SET_STRATEGY_STDERR: {strat_set_stderr.strip()}\n"
                    except Exception as e_strat_set:
                        print(f"{progress_prefix} ERROR setting strategy: {e_strat_set}. Skipping.", flush=True)
                        all_run_results_accumulator.append({
                            "original_query_id": original_query_id_str, "query_text": query_text, "expected_answer": expected_answer,
                            "benchmark_type": benchmark_type, "source_file": source_file, # Add new fields
                            "temporal_strategy": temp_s, "pushdown_strategy": push_s, "stitch_strategy": stitch_s, "cache_mode": cache_mode,
                            "avg_time_ms": None, "individual_run_times_ms": [],
                            "verification_status": "STRATEGY_SETUP_FAIL", "stderr_output": str(e_strat_set) + "\n" + run_stderr_details, # include existing stderr
                            "stdout_output": ""
                        })
                        continue # Skip to next strategy combination

                    timed_exec_times = []

                    if cache_mode == "warm":
                        print(f"{progress_prefix}  Warm-up 1/1...", flush=True)
                        try:
                            # Execute query for warmup, ignore timing results
                            _, wu_out, wu_err = cli_process.execute_query(query_text)
                            if wu_err: run_stderr_details += f"WARMUP_QUERY_STDERR: {wu_err.strip()}\n"
                            if args.verbose and wu_out: run_stdout_details += f"WARMUP_QUERY_STDOUT: {wu_out.strip()}\n"
                        except Exception as e_warmup:
                            print(f"{progress_prefix}  Warm-up ERROR: {e_warmup}", flush=True)
                            run_stderr_details += f"WARMUP_QUERY_EXCEPTION: {str(e_warmup)}\n"
                            # Continue to timed runs even if warmup fails, but log it

                    verification_export_file = None
                    if args.export_dir and expected_answer: # Only create filename if we might verify
                        safe_query_part = re.sub(r'[^a-zA-Z0-9_-]', '_', query_text)[:30]
                        fn_base = f"{original_query_id_str}_{safe_query_part}_T{temp_s}_P{push_s}_S{stitch_s}_{cache_mode}"
                        verification_export_file = os.path.join(args.export_dir, f"{fn_base}_FOR_VERIFICATION.csv")

                    for run_num in range(args.runs_per_query):
                        print(f"{progress_prefix}  Timed Run {run_num+1}/{args.runs_per_query}...", flush=True)
                        current_run_stdout, current_run_stderr = "", ""
                        try:
                            # Set output only for the last run if verification is active
                            if verification_export_file and run_num == args.runs_per_query - 1:
                                _, _, set_out_err = cli_process.set_output("csv", verification_export_file)
                                if set_out_err: run_stderr_details += f"SET_OUTPUT_VERIFY_STDERR (Run {run_num+1}): {set_out_err.strip()}\n"

                            b_time, out_q, err_q = cli_process.execute_query(query_text)
                            current_run_stdout = out_q # Stdout from this specific query execution
                            current_run_stderr = err_q # Stderr from this specific query execution

                            if err_q: run_stderr_details += f"QUERY_STDERR (Run {run_num+1}): {err_q.strip()}\n"

                            if args.verbose and out_q: # If verbose, accumulate all stdout
                                run_stdout_details += f"QUERY_STDOUT (Run {run_num+1}): {out_q.strip()}\n"
                            elif run_num == args.runs_per_query - 1: # If not verbose, capture only last run's stdout for potential verification
                                run_stdout_details = out_q # Overwrite with last run's full stdout

                            if b_time is not None:
                                timed_exec_times.append(b_time)
                                print(f"{progress_prefix}    Run {run_num+1}: ExecT={b_time:.3f}ms", flush=True)
                            else:
                                print(f"{progress_prefix}    Run {run_num+1}: ExecT=N/A. Check stderr.", flush=True)

                            # Revert output to NONE after the last run if it was set for verification
                            if verification_export_file and run_num == args.runs_per_query - 1:
                                _, _, set_out_none_err = cli_process.set_output() # Revert to NONE
                                if set_out_none_err: run_stderr_details += f"SET_OUTPUT_NONE_STDERR (Run {run_num+1}): {set_out_none_err.strip()}\n"

                        except Exception as e_query_run:
                            print(f"{progress_prefix}  Run {run_num+1} ERROR: {e_query_run}", flush=True)
                            run_stderr_details += f"QUERY_RUN_EXCEPTION (Run {run_num+1}): {str(e_query_run)}\n{traceback.format_exc()}\n"
                            # Try to get any buffered output from CLI process if it's still there
                            bfr_out, bfr_err = cli_process._collect_current_cycle_output(clear_buffers=True)
                            if bfr_out and args.verbose: run_stdout_details += f"BUFFERED_STDOUT_ON_EXC (Run {run_num+1}): {bfr_out.strip()}\n"
                            if bfr_err: run_stderr_details += f"BUFFERED_STDERR_ON_EXC (Run {run_num+1}): {bfr_err.strip()}\n"
                            break # Stop runs for this strategy combination if one fails critically

                    avg_exec_t = sum(timed_exec_times) / len(timed_exec_times) if timed_exec_times else None

                    ver_status = "SKIPPED" # Default verification status

                    # 1. Check for TIMEOUT
                    if avg_exec_t is None and "timeout" in run_stderr_details.lower(): # Check if timeout specifically caused no avg time
                        ver_status = "TIMEOUT"
                    else:
                        # Logic for sourcing content and then checking for EMPTY or expected answers
                        content_for_analysis = ""
                        source_of_content = "N/A"
                        is_content_from_file = False # True if content_for_analysis is from verification_export_file

                        if verification_export_file and os.path.exists(verification_export_file):
                            try:
                                with open(verification_export_file, 'r', encoding='utf-8') as vf:
                                    content_for_analysis = vf.read()
                                source_of_content = verification_export_file
                                is_content_from_file = True
                            except Exception as e_vf:
                                ver_status = f"VERIFY_EXPORT_READ_ERR: {e_vf}"
                                run_stderr_details += f"VERIFY_FILE_READ_EXCEPTION ({verification_export_file}): {str(e_vf)}\n"
                        elif run_stdout_details: # Fallback to stdout if no export file or it didn't exist
                            content_for_analysis = run_stdout_details
                            source_of_content = "stdout (last run or verbose accumulated)"
                            is_content_from_file = False
                        # If neither export file nor stdout details, content_for_analysis remains ""

                        # 2. Check for EMPTY status (if not already TIMEOUT or VERIFY_EXPORT_READ_ERR)
                        if ver_status == "SKIPPED": # Only proceed if no prior critical status

                            # Helper function to determine if output is effectively empty
                            def is_output_effectively_empty(text_content, is_csv_file, prompt_text_val):
                                if text_content is None: return True
                                # An empty string or string with only whitespace is considered empty
                                if not text_content.strip(): return True

                                lines = text_content.splitlines()
                                if not lines: return True # No lines after split (e.g. if content was just '\\n')

                                if is_csv_file:
                                    # CSV is empty if 0 lines, or 1 line (header), or >1 lines but all after header are blank
                                    if len(lines) <= 1:
                                        return True
                                    # Check if all lines after a potential header are blank
                                    for i in range(1, len(lines)):
                                        if lines[i].strip():
                                            return False # Found non-blank data line after header
                                    return True # All lines after header are blank
                                else: # Standard output
                                    actual_data_lines = []
                                    known_no_results_stdout_messages = [
                                        "No results to display.",
                                        "Query returned successfully with no results."
                                        # Add any other specific "no data" messages from CLI if known
                                    ]
                                    for line in lines:
                                        stripped_line = line.strip()
                                        if "BENCHMARK_EXECUTION_TIME_MS:" in stripped_line:
                                            continue
                                        if stripped_line == prompt_text_val: # Exact match for prompt
                                            continue

                                        is_known_empty_message = False
                                        for msg in known_no_results_stdout_messages:
                                            if stripped_line == msg:
                                                is_known_empty_message = True
                                                break
                                        if is_known_empty_message:
                                            continue # Skip this line from being considered actual data

                                        if stripped_line: # If a line has content and wasn't filtered
                                            actual_data_lines.append(stripped_line)

                                    # If no data lines collected after filtering, it's empty
                                    return not actual_data_lines

                            if is_output_effectively_empty(content_for_analysis, is_content_from_file, PROMPT):
                                ver_status = "EMPTY"
                                if args.verbose: print(f"{progress_prefix} Verification 'EMPTY' determined from: {source_of_content}", flush=True)

                        # 3. If expected_answer exists and status is still SKIPPED (i.e., not TIMEOUT, not VERIFY_EXPORT_READ_ERR, not EMPTY)
                        #    Then proceed with PASSED/FAILED or NO_VERIFIABLE_CONTENT.
                        if expected_answer and ver_status == "SKIPPED":
                            if content_for_analysis: # If there's some content (not deemed "EMPTY")
                                ver_status = "PASSED" if expected_answer.lower() in content_for_analysis.lower() else "FAILED"
                                if args.verbose: print(f"{progress_prefix} Verification '{ver_status}' using: {source_of_content} against expected answer.", flush=True)
                            else:
                                # This path means: not TIMEOUT, not EXPORT_ERR, not EMPTY, expected_answer exists,
                                # BUT content_for_analysis is falsey (e.g. None or empty string from the start,
                                # and is_output_effectively_empty didn't classify it as EMPTY).
                                # This should be rare if is_output_effectively_empty is robust.
                                ver_status = "NO_VERIFIABLE_CONTENT (Expected answer, but no output from query to check)"
                                if args.verbose: print(f"{progress_prefix} Verification 'NO_VERIFIABLE_CONTENT' from: {source_of_content}", flush=True)
                        # If no expected_answer, ver_status remains as TIMEOUT, EMPTY, or SKIPPED.
                        # If expected_answer and ver_status was already VERIFY_EXPORT_READ_ERR or EMPTY, it remains so.

                    # Store results for this strategy combination
                    all_run_results_accumulator.append({
                        "original_query_id": original_query_id_str, "query_text": query_text, "expected_answer": expected_answer,
                        "benchmark_type": benchmark_type, "source_file": source_file, # Add new fields
                        "temporal_strategy": temp_s, "pushdown_strategy": push_s, "stitch_strategy": stitch_s, "cache_mode": cache_mode,
                        "avg_time_ms": avg_exec_t,
                        "individual_run_times_ms": timed_exec_times,
                        "verification_status": ver_status,
                        # Store stdout only if verbose or if verification wasn't via export file and was attempted
                        "stdout_output": run_stdout_details.strip() if args.verbose or (ver_status not in ["SKIPPED", "NO_VERIFIABLE_CONTENT"] and not (verification_export_file and os.path.exists(verification_export_file))) else "",
                        "stderr_output": run_stderr_details.strip()
                    })

    except (FileNotFoundError, TimeoutError, ConnectionAbortedError, RuntimeError) as e_cli_main:
        print(f"\nCRITICAL SCRIPT ERROR related to QueryCLI process: {e_cli_main}", flush=True)
        print(f"Traceback: {traceback.format_exc()}", flush=True)
        print("Benchmark run ABORTED.", flush=True)
    except KeyboardInterrupt:
        print("\nBenchmark interrupted by user (Ctrl+C). Cleaning up...", flush=True)
    except Exception as e_global: # Catch-all for unexpected errors in main benchmark loop
        print(f"\nUNEXPECTED GLOBAL SCRIPT ERROR: {e_global}", flush=True)
        print(f"Traceback: {traceback.format_exc()}", flush=True)
    finally:
        if cli_process:
            print("\n[BENCHMARK.PY] Ensuring QueryCLI process is closed in finally block...", flush=True)
            cli_process.close()
        print("[BENCHMARK.PY] Script execution finished or aborted.", flush=True)

    if not all_run_results_accumulator:
        print("\nNo benchmark results were collected. No summary CSV files will be generated.", flush=True)
        exit(0)

    print("\n========== BENCHMARK OVERALL SUMMARY (CONSOLE) ==========", flush=True)
    for res_item in all_run_results_accumulator:
        # Construct the summary line part by part for clarity
        summary_line = (
            f"  Q:{res_item['original_query_id']} File:{res_item['source_file']} BT:{res_item['benchmark_type']} C:{res_item['cache_mode']} " # Add new fields
            f"T:{res_item['temporal_strategy']},P:{res_item['pushdown_strategy']},S:{res_item['stitch_strategy']} "
        )
        summary_line += f"AvgTime:{res_item['avg_time_ms']:.3f}ms " if res_item['avg_time_ms'] is not None else "AvgTime:N/A "
        summary_line += f"Verify:{res_item['verification_status']}"
        print(summary_line, flush=True)

        if res_item['stderr_output'] and (args.verbose or "ERROR" in res_item['stderr_output'].upper() or "FAIL" in res_item['verification_status'].upper() or "TIMEOUT" in res_item['verification_status'].upper()):
            # Print the query text first for context when there's an error
            print(f"      Query Text: {res_item['query_text']}", flush=True)
            indented_stderr = "\n".join([f"      Stderr: {line}" for line in res_item['stderr_output'].strip().split('\n')])
            print(indented_stderr, flush=True)
        if args.verbose and res_item['stdout_output']: # Only print stdout in summary if verbose
             indented_stdout = "\n".join([f"      Stdout: {line}" for line in res_item['stdout_output'].strip().split('\n')])
             print(indented_stdout, flush=True)


    # Logic for exporting to multiple CSV files based on source_file and benchmark_type
    print(f"\nExporting benchmark results to separate CSV files in {args.query_dir}...", flush=True)

    # Define fieldnames once
    fieldnames_csv = [
        'original_query_id', 'query_text', 'expected_answer',
        'benchmark_type', 'source_file',
        'temporal_strategy', 'pushdown_strategy', 'stitch_strategy', 'cache_mode',
        'avg_time_ms', 'individual_run_times_ms',
        'verification_status', 'stderr_output', 'stdout_output'
    ]

    grouped_results = {}
    for row_data in all_run_results_accumulator:
        key = (row_data['source_file'], row_data['benchmark_type'])
        if key not in grouped_results:
            grouped_results[key] = []
        grouped_results[key].append(row_data)

    files_written_count = 0
    for (source_file, benchmark_type), results_for_group in grouped_results.items():
        original_filename_without_ext = os.path.splitext(source_file)[0]
        output_csv_basename = f"{original_filename_without_ext}_{benchmark_type}_results.csv"

        # ALWAYS save alongside the original query file in its directory
        output_filepath = os.path.join(args.query_dir, output_csv_basename)

        print(f"  Writing results for {source_file} (Type: {benchmark_type}) to {output_filepath}", flush=True)
        try:
            with open(output_filepath, 'w', newline='', encoding='utf-8') as csvfile:
                writer = csv.DictWriter(csvfile, fieldnames=fieldnames_csv, extrasaction='ignore')
                writer.writeheader()
                for row_data_item in results_for_group:
                    row_data_copy = row_data_item.copy()
                    row_data_copy['individual_run_times_ms'] = ';'.join([f"{t:.3f}" for t in row_data_copy.get('individual_run_times_ms', [])])

                    if not args.verbose:
                        if args.export_dir and row_data_copy['verification_status'] == "PASSED":
                             row_data_copy['stdout_output'] = "See verification export file or run with --verbose"
                        elif row_data_copy['verification_status'] not in ["SKIPPED", "NO_VERIFIABLE_CONTENT"]:
                            pass
                        else:
                            row_data_copy['stdout_output'] = ""

                    writer.writerow(row_data_copy)
            files_written_count += 1
        except Exception as e_csv_export_multi:
            print(f"  Error exporting benchmark results to CSV {output_filepath}: {e_csv_export_multi}\n{traceback.format_exc()}", flush=True)

    if files_written_count > 0:
        print(f"\nSuccessfully exported {files_written_count} benchmark result CSV file(s) to {args.query_dir}.", flush=True)
    else:
        print(f"\nNo benchmark result CSV files were generated (e.g. no results for any group).", flush=True)

    print("\nBenchmark script execution complete.", flush=True)

