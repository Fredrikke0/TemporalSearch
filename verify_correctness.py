import argparse
import csv
import filecmp
import os
import queue
import re
import subprocess
import threading
import time
import traceback

DEFAULT_JAR_PATH = "target/query-cli.jar"
# Timeouts for interactive mode
QUERY_EXEC_TIMEOUT_SECONDS = 60 * 15  # 15 minutes for a single query to execute and return PROMPT
CLI_STARTUP_TIMEOUT_SECONDS = 60 * 5    # Timeout for QueryCLI to start and print initial PROMPT
COMMAND_ACK_TIMEOUT_SECONDS = 30    # Timeout for ACK responses from QueryCLI
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
        if is_verbose: print(f"[VERIFY.PY DEBUG] ValueError in _enqueue_output for {stream_name} (stream closed).", flush=True)
        pass
    except Exception as e:
        print(f"[VERIFY.PY DEBUG] Unhandled exception in _enqueue_output for {stream_name}: {e}", flush=True)
    finally:
        q.put(None) # Sentinel to indicate EOF or error, ensuring consumers can terminate.

def _determine_strategies_for_query(query_info, is_verbose):
    benchmark_type = query_info['benchmark_type']
    original_query_id_str = f"q{query_info['id']+1}"

    strategies_to_run = []
    if benchmark_type == "1HOP":
        if is_verbose: print(f"    (StrategyDeterminer Q:{original_query_id_str} - Type {benchmark_type}: Using specific strategies for 1-hop.)", flush=True)
        strategies_to_run = [
            # (temporal, pushdown, stitch)
            ('naive', 'none', 'none'),      # B
            ('nash', 'none', 'none'),       # N
            ('naive', 'none', 'optimized'), # S
            ('nash', 'none', 'optimized')   # S+N
        ]
    elif benchmark_type in ["2HOP", "3HOP"]:
        if is_verbose: print(f"    (StrategyDeterminer Q:{original_query_id_str} - Type {benchmark_type}: Using specific strategies for 2/3-hop.)", flush=True)
        strategies_to_run = [
            # (temporal, pushdown, stitch)
            ('naive', 'none', 'none'),          # B
            ('nash', 'none', 'none'),           # N
            ('naive', 'none', 'optimized'),     # S
            ('naive', 'optimized', 'none'),     # P
            ('naive', 'optimized', 'optimized'),# S+P
            ('nash', 'optimized', 'optimized')  # S+P+N
        ]
    else: # Should not happen with current logic, but good to have a fallback
        if is_verbose: print(f"    (StrategyDeterminer Q:{original_query_id_str} - Type {benchmark_type}: Unknown type, no strategies will be run.)", flush=True)
        strategies_to_run = []


    if not strategies_to_run and is_verbose:
        print(f"    (StrategyDeterminer Q:{original_query_id_str} - No strategy combinations generated for this benchmark type. No strategies for this query.)", flush=True)

    return strategies_to_run

def compare_csv_files_deep(file1, file2):
    """
    Compares two files. If they appear to be CSVs, it performs a deep, order-agnostic comparison
    of headers and rows. Otherwise, it compares them as plain text files with sorted lines.
    """
    try:
        with open(file1, 'r', newline='', encoding='utf-8') as f1, open(file2, 'r', newline='', encoding='utf-8') as f2:
            # Clean lines and remove benchmark execution time, which is non-deterministic
            lines1 = [l.strip() for l in f1 if l.strip() and not l.startswith("BENCHMARK_EXECUTION_TIME_MS:")]
            lines2 = [l.strip() for l in f2 if l.strip() and not l.startswith("BENCHMARK_EXECUTION_TIME_MS:")]

            if not lines1 and not lines2:
                return True, "Both files are empty"
            if not lines1 or not lines2:
                return False, f"One file is empty, the other is not. F1 lines: {len(lines1)}, F2 lines: {len(lines2)}"

            # Heuristic: if it's not a real CSV, do a simple line sort comparison
            if ',' not in lines1[0] or ',' not in lines2[0]:
                if sorted(lines1) == sorted(lines2):
                    return True, "Plain text content matches"
                else:
                    return False, f"Plain text content differs. L1 count: {len(lines1)}, L2 count: {len(lines2)}"

            # --- Full CSV Comparison ---
            reader1 = csv.reader(lines1)
            reader2 = csv.reader(lines2)

            header1 = next(reader1)
            header2 = next(reader2)

            if sorted(header1) != sorted(header2):
                return False, f"Headers are different. H1: {header1}, H2: {header2}"

            # Map from header1 column order to header2's indices to allow column reordering
            col_map_for_h2 = [header2.index(h) for h in header1]

            # Read all rows and sort them to make comparison row-order independent
            rows1_as_tuples = sorted([tuple(row) for row in reader1])

            rows2_reordered = []
            for row2 in reader2:
                # Reorder columns in row2 to match the column order of header1
                reordered_row = tuple(row2[i] for i in col_map_for_h2)
                rows2_reordered.append(reordered_row)

            rows2_as_tuples = sorted(rows2_reordered)

            if rows1_as_tuples == rows2_as_tuples:
                return True, "CSV content matches"
            else:
                if len(rows1_as_tuples) != len(rows2_as_tuples):
                     return False, f"CSV content differs. Row count mismatch: Base has {len(rows1_as_tuples)}, Other has {len(rows2_as_tuples)}."
                # Find the first differing row for better error message
                for i, r1 in enumerate(rows1_as_tuples):
                    if r1 != rows2_as_tuples[i]:
                        return False, f"CSV content differs. First mismatch at sorted row {i+1}.\nBase: {r1}\nOther: {rows2_as_tuples[i]}"
                return False, f"CSV content differs. R1 count: {len(rows1_as_tuples)}, R2 count: {len(rows2_as_tuples)}"


    except Exception as e:
        return False, f"Error during deep comparison: {e}\n{traceback.format_exc()}"


def run_verification_for_file(cli_process, queries_to_run, args, file_output_dir):
    """
    Runs each query with a base strategy and then with other strategies,
    comparing the output for correctness.
    """
    print(f"  Running verification for {len(queries_to_run)} queries.", flush=True)
    all_results = []

    for query_info in queries_to_run:
        original_query_id_str = f"q{query_info['id']+1}"
        query_text = query_info['text']
        benchmark_type = query_info['benchmark_type']
        source_file = query_info['source_file']

        print(f"\n--- Verifying Query {original_query_id_str} (File: {source_file}, Type: {benchmark_type}) ---", flush=True)
        if args.verbose: print(f"    Query Text: {query_text}", flush=True)


        strategies_to_run = _determine_strategies_for_query(query_info, args.verbose)
        if not strategies_to_run:
            print(f"    (Q:{original_query_id_str} - No strategies to run. Skipping.)", flush=True)
            continue

        base_strategy_tuple = ('naive', 'none', 'none')
        if base_strategy_tuple not in strategies_to_run:
            print(f"    (Q:{original_query_id_str} - Base strategy {base_strategy_tuple} not in list of strategies to run. Skipping.)", flush=True)
            continue

        # --- Run base strategy first to get the ground truth ---
        base_temp_s, base_push_s, base_stitch_s = base_strategy_tuple
        progress_prefix_base = f"    [BASE Q:{original_query_id_str} T:{base_temp_s},P:{base_push_s},S:{base_stitch_s}]"

        safe_query_part = re.sub(r'[^a-zA-Z0-9_-]', '_', query_text)[:30]
        base_output_filename = f"{original_query_id_str}_{safe_query_part}_BASE.csv"
        base_output_filepath = os.path.join(file_output_dir, base_output_filename)

        base_run_ok = False
        try:
            # Set strategy
            _, _, strat_set_stderr = cli_process.set_strategy(base_temp_s, base_push_s, base_stitch_s)
            if strat_set_stderr and args.verbose: print(f"{progress_prefix_base} WARNING: Stderr on set_strategy: {strat_set_stderr.strip()}", flush=True)

            # Execute and capture output directly from stdout
            _, base_output_content, err_q = cli_process.execute_query(query_text)

            if err_q:
                 print(f"{progress_prefix_base} ERROR executing query. Stderr: {err_q.strip()}", flush=True)
                 all_results.append({
                    "query_id": original_query_id_str, "strategy": "BASE", "status": "BASE_FAILED_EXEC", "details": err_q.strip()
                 })
            else:
                base_run_ok = True
                print(f"{progress_prefix_base}  -> Base execution successful. Output saved to {os.path.basename(base_output_filepath)}.", flush=True)
                with open(base_output_filepath, 'w', encoding='utf-8') as f:
                    f.write(base_output_content)
        except Exception as e:
            print(f"{progress_prefix_base} CRITICAL ERROR running base strategy: {e}. This query will be skipped.", flush=True)
            all_results.append({
                "query_id": original_query_id_str, "strategy": "BASE", "status": "BASE_ERROR", "details": str(e)
            })
            continue # Skip to next query

        if not base_run_ok:
            print(f"    (Q:{original_query_id_str} - Base run failed, cannot verify other strategies. Skipping.)", flush=True)
            continue

        # --- Run other strategies and compare against the base run ---
        other_strategies = [s for s in strategies_to_run if s != base_strategy_tuple]
        for temp_s, push_s, stitch_s in other_strategies:
            strategy_str = f"T:{temp_s},P:{push_s},S:{stitch_s}"
            progress_prefix_other = f"    [CMP Q:{original_query_id_str} {strategy_str}]"

            other_output_filename = f"{original_query_id_str}_{safe_query_part}_T{temp_s}_P{push_s}_S{stitch_s}.csv"
            other_output_filepath = os.path.join(file_output_dir, other_output_filename)

            status = "UNKNOWN"
            details = ""

            try:
                _, _, strat_set_stderr = cli_process.set_strategy(temp_s, push_s, stitch_s)
                if strat_set_stderr and args.verbose: print(f"{progress_prefix_other} WARNING: Stderr on set_strategy: {strat_set_stderr.strip()}", flush=True)

                _, other_output_content, err_q = cli_process.execute_query(query_text)
                if err_q:
                    print(f"{progress_prefix_other} ERROR executing query. Stderr: {err_q.strip()}", flush=True)
                    status = "EXECUTION_ERROR"
                    details = err_q.strip()
                else:
                    # Write output and compare with base
                    with open(other_output_filepath, 'w', encoding='utf-8') as f:
                        f.write(other_output_content)

                    are_equal, reason = compare_csv_files_deep(base_output_filepath, other_output_filepath)
                    if are_equal:
                        status = "PASSED"
                        print(f"{progress_prefix_other}  -> PASSED.", flush=True)
                        os.remove(other_output_filepath) # Clean up if it matches
                    else:
                        status = "FAILED"
                        details = f"Output differs from base. Reason: {reason}. Files saved: {base_output_filename}, {os.path.basename(other_output_filepath)}"
                        print(f"{progress_prefix_other}  -> FAILED. {details}", flush=True)

            except Exception as e:
                status = "VERIFY_ERROR"
                details = str(e)
                print(f"{progress_prefix_other} CRITICAL ERROR during verification: {e}", flush=True)

            all_results.append({
                "query_id": original_query_id_str,
                "strategy": strategy_str,
                "status": status,
                "details": details
            })

    return all_results


class QueryCLIInteractiveProcess:
    def __init__(self, jar_path, db_file, index_dir, initial_temporal_strategy, initial_pushdown_strategy, initial_stitch_strategy, is_verbose=False):
        print("[VERIFY.PY] Initializing QueryCLIInteractiveProcess...", flush=True)
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
            "-Xms100G",
            "-Xmx100G",
            "-XX:+UseG1GC",
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
            print(f"[VERIFY.PY DEBUG] Starting QueryCLI: {' '.join(command)}", flush=True)

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

            # Wait for initial prompt using the new helper
            stdout_init, stderr_init = self._send_and_await_prompt(None, PROMPT, CLI_STARTUP_TIMEOUT_SECONDS, "CLI Startup")
            if self.is_verbose: print(f"[VERIFY.PY DEBUG] QueryCLI started successfully and '{PROMPT}' received.", flush=True)

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
                if self.is_verbose: print("[VERIFY.PY DEBUG] QueryCLI process terminated unexpectedly.", flush=True)
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
                            if self.is_verbose: print(f"[VERIFY.PY DEBUG] Found prompt '{prompt_text}'", flush=True)
                            return prompt_text, "".join(call_specific_stdout), "".join(call_specific_stderr)

            if time.monotonic() - start_time > timeout_seconds:
                if self.is_verbose: print(f"[VERIFY.PY DEBUG] Timeout ({timeout_seconds}s) waiting for {target_prompts}. Time since command: {time.monotonic() - self._last_command_timestamp:.2f}s", flush=True)
                break

            if stdout_eof and stderr_eof and self._stdout_q.empty() and self._stderr_q.empty():
                 if self.is_verbose: print(f"[VERIFY.PY DEBUG] Both streams EOF and queues empty, but prompt not found.", flush=True)
                 break

            time.sleep(0.001)

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
                stdout_final, stderr_final = self._collect_current_cycle_output(clear_buffers=False)
                raise ConnectionAbortedError(f"QueryCLI process stdin is not available (None). Cannot send '{command_str}'.\nStdout: {stdout_final}\nStderr: {stderr_final}")
        except (IOError, ValueError) as e: # Catch BrokenPipeError (subclass of IOError) or ValueError if stdin closed
            stdout_final, stderr_final = self._collect_current_cycle_output(clear_buffers=False)
            raise ConnectionAbortedError(f"IOError/ValueError sending '{command_str}' (QueryCLI died or stdin closed?): {e}.\nStdout: {stdout_final}\nStderr: {stderr_final}")

    def _send_and_await_prompt(self, command_to_send, prompt_to_wait_for, timeout_seconds, command_description):
        if command_to_send:
            self.send_command(command_to_send)

        prompt_found_str, stdout_cycle, stderr_cycle = self._read_until_prompts([prompt_to_wait_for], timeout_seconds=timeout_seconds)

        if not prompt_found_str:
            err_msg = f"Timeout/Error: Did not receive '{prompt_to_wait_for}' for {command_description} within {timeout_seconds}s."
            if command_to_send: err_msg += f"\nCommand: {command_to_send}"
            err_msg += f"\nStdout: {stdout_cycle}\nStderr: {stderr_cycle}"
            raise TimeoutError(err_msg)

        return stdout_cycle, stderr_cycle

    def set_strategy(self, temporal, pushdown, stitch):
        cmd = f"SET STRATEGY temporal={temporal} pushdown={pushdown} stitch={stitch}"
        stdout_cycle, stderr_cycle = self._send_and_await_prompt(cmd, PROMPT, COMMAND_ACK_TIMEOUT_SECONDS, "SET STRATEGY")
        return None, stdout_cycle, stderr_cycle

    def execute_query(self, query_string):
        self.send_command(query_string)

        query_complete_prompt, full_stdout_query, stderr_query = self._read_until_prompts([PROMPT], timeout_seconds=QUERY_EXEC_TIMEOUT_SECONDS)

        if not query_complete_prompt:
            elapsed_time = time.monotonic() - self._last_command_timestamp
            additional_err_msg = f"\nPYTHON_VERIFY: Query '{query_string[:50]}...' did not complete with {PROMPT}. Elapsed: {elapsed_time:.2f}s."
            if elapsed_time >= QUERY_EXEC_TIMEOUT_SECONDS:
                 additional_err_msg += f" (Exceeded timeout of {QUERY_EXEC_TIMEOUT_SECONDS}s)"
            stderr_query = (stderr_query or "") + additional_err_msg

        return None, full_stdout_query, stderr_query

    def close(self):
        if self.is_verbose: print("[VERIFY.PY DEBUG] Attempting to close QueryCLI process interface...", flush=True)

        if self.process and self.process.poll() is None:
            if self.is_verbose: print("[VERIFY.PY DEBUG] Sending EXIT to QueryCLI stdin.", flush=True)
            try:
                if self.process.stdin and not self.process.stdin.closed:
                    self.process.stdin.write("EXIT\n")
                    self.process.stdin.flush()
                    self.process.stdin.close() # Close stdin after sending EXIT
            except (IOError, ValueError) as e_stdin:
                if self.is_verbose: print(f"[VERIFY.PY DEBUG] Error interacting with QueryCLI stdin during close (possibly already closed): {e_stdin}", flush=True)

        if self.process:
            if self.is_verbose: print("[VERIFY.PY DEBUG] Waiting for QueryCLI process to terminate...", flush=True)
            try:
                self.process.wait(timeout=PROCESS_TERMINATION_TIMEOUT_SECONDS)
                if self.is_verbose: print(f"[VERIFY.PY DEBUG] QueryCLI process terminated with rc: {self.process.returncode}", flush=True)
            except subprocess.TimeoutExpired:
                if self.is_verbose: print("[VERIFY.PY DEBUG] QueryCLI did not terminate after EXIT and wait, killing.", flush=True)
                self.process.kill()
                self.process.wait()
            except Exception as e_wait:
                if self.is_verbose: print(f"[VERIFY.PY DEBUG] Error waiting for QueryCLI process: {e_wait}. Attempting kill.", flush=True)
                self.process.kill()
                self.process.wait()

        for thread, name in [(self._stdout_thread, "stdout"), (self._stderr_thread, "stderr")]:
            if thread and thread.is_alive():
                thread.join(timeout=2)

        self.process = None
        if self.is_verbose: print("[VERIFY.PY DEBUG] QueryCLI process interface definitively closed.", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run queries with different strategies and verify that their outputs are identical.")
    parser.add_argument("--query-dir", required=True, help="Path to a directory containing query files. Each file's name should contain '1hop', '2hop', or '3hop'.")
    parser.add_argument("--jar-path", default=DEFAULT_JAR_PATH, help=f"Path to QueryCLI JAR (default: {DEFAULT_JAR_PATH})")
    parser.add_argument("--db-file", required=True, help="Path to QueryCLI SQLite database file.")
    parser.add_argument("--index-dir", required=True, help="Path to QueryCLI index directory.")
    parser.add_argument("--verbose", action="store_true", help="Verbose output from verification script and QueryCLI.")
    parser.add_argument("--output-dir", required=True, help="Directory to store outputs for failed verifications.")
    args = parser.parse_args()

    if not args.query_dir or not os.path.isdir(args.query_dir):
        exit(f"Error: Query directory not found or not specified: {args.query_dir}")

    all_query_files = [f for f in os.listdir(args.query_dir) if os.path.isfile(os.path.join(args.query_dir, f)) and f.lower().endswith('.txt')]
    if not all_query_files:
        exit(f"Error: No .txt query files found in query directory: {args.query_dir}")

    os.makedirs(args.output_dir, exist_ok=True)

    print(f"Using JAR: {args.jar_path}", flush=True)
    print(f"Using DB File: {args.db_file}", flush=True)
    print(f"Using Index Dir: {args.index_dir}", flush=True)
    print(f"Initial CLI strategies (hardcoded): T:nash, P:optimized, S:optimized", flush=True)
    print(f"Verification output for failures will be saved in subdirectories under: {args.output_dir}", flush=True)

    overall_results = []
    total_files_processed = 0

    for file_idx, current_filename in enumerate(all_query_files):
        print(f"\n\n========== PROCESSING FILE {file_idx + 1}/{len(all_query_files)}: {current_filename} ==========", flush=True)
        filepath = os.path.join(args.query_dir, current_filename)

        queries_for_this_file = []
        query_id_counter_local = 0
        benchmark_type_for_file = None

        fn_lower = current_filename.lower()
        if "1hop" in fn_lower: benchmark_type_for_file = "1HOP"
        elif "2hop" in fn_lower: benchmark_type_for_file = "2HOP"
        elif "3hop" in fn_lower: benchmark_type_for_file = "3HOP"
        else:
            print(f"Warning: Could not determine benchmark type (1HOP, 2HOP, 3HOP) from filename '{current_filename}'. Skipping this file.", flush=True)
            continue

        print(f"  Inferred benchmark type: {benchmark_type_for_file} for file: {current_filename}", flush=True)

        try:
            with open(filepath, 'r', encoding='utf-8') as f:
                for line_content in f:
                    line_content = line_content.strip()
                    if not line_content or line_content.startswith('#'): continue
                    parts = line_content.split(' ::: ', 1)
                    queries_for_this_file.append({
                        'id': query_id_counter_local,
                        'text': parts[0],
                        'expected': parts[1] if len(parts) > 1 else None,
                        'benchmark_type': benchmark_type_for_file,
                        'source_file': current_filename
                    })
                    query_id_counter_local += 1
        except Exception as e:
            print(f"Warning: Error reading query file {current_filename}: {e}. Skipping.", flush=True)
            continue

        if not queries_for_this_file:
            print(f"No valid queries loaded from {current_filename}. File will be skipped.", flush=True)
            continue

        total_files_processed += 1
        original_filename_no_ext = os.path.splitext(current_filename)[0]
        file_output_dir = os.path.join(args.output_dir, benchmark_type_for_file, original_filename_no_ext)
        os.makedirs(file_output_dir, exist_ok=True)
        print(f"  Output for this file will be stored in: {file_output_dir}", flush=True)

        cli_process = None
        try:
            cli_process = QueryCLIInteractiveProcess(
                args.jar_path, args.db_file, args.index_dir,
                "nash", "optimized", "optimized", # Hardcoded initial strategies
                is_verbose=args.verbose
            )
            file_results = run_verification_for_file(cli_process, queries_for_this_file, args, file_output_dir)
            overall_results.extend(file_results)

        except (FileNotFoundError, TimeoutError, ConnectionAbortedError, RuntimeError) as e:
            print(f"\nCRITICAL SCRIPT ERROR for file {current_filename} related to QueryCLI process: {e}", flush=True)
            print(f"Traceback: {traceback.format_exc()}", flush=True)
            print(f"Verification for {current_filename} and subsequent files will be ABORTED.", flush=True)
            break
        except KeyboardInterrupt:
            print(f"\nVerification for {current_filename} interrupted by user (Ctrl+C). Cleaning up...", flush=True)
            raise
        except Exception as e:
            print(f"\nUNEXPECTED SCRIPT ERROR during processing of {current_filename}: {e}", flush=True)
            print(f"Traceback: {traceback.format_exc()}", flush=True)
            break
        finally:
            if cli_process:
                print(f"\n[VERIFY.PY] Ensuring QueryCLI process for {current_filename} is closed...", flush=True)
                cli_process.close()

    # --- Final Summary ---
    print("\n\n========== OVERALL VERIFICATION SUMMARY ==========", flush=True)
    if not overall_results:
        print("No verification checks were performed.", flush=True)
    else:
        passed_count = sum(1 for r in overall_results if r['status'] == 'PASSED')
        failed_count = sum(1 for r in overall_results if r['status'] != 'PASSED' and r.get('strategy') != 'BASE')
        base_errors = sum(1 for r in overall_results if r.get('strategy') == 'BASE')

        print(f"Total files processed: {total_files_processed}")
        print(f"Total strategy comparisons: {len(overall_results) - base_errors}")
        print(f"  Passed: {passed_count}")
        print(f"  Failed/Errored: {failed_count}")
        if base_errors > 0:
            print(f"  Base execution errors (queries skipped): {base_errors}")

        if failed_count > 0 or base_errors > 0:
            print("\n--- FAILURE & ERROR DETAILS ---", flush=True)
            for r in sorted(overall_results, key=lambda x: x['query_id']):
                if r['status'] != 'PASSED':
                    print(f"  Query: {r['query_id']}, Strategy: {r['strategy']}, Status: {r['status']}", flush=True)
                    if r.get('details'):
                        indented_details = "\n".join([f"    Details: {line}" for line in r['details'].strip().split('\n')])
                        print(indented_details, flush=True)

    print("\nVerification script execution complete.", flush=True)