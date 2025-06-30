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

def _determine_strategies_for_query(query_info, temporal_strategies_base, pushdown_strategies_base, stitch_strategies_base, is_verbose):
    query_text = query_info['text']
    benchmark_type = query_info['benchmark_type']
    original_query_id_str = f"q{query_info['id']+1}"

    current_temporal_strategies = list(temporal_strategies_base)
    current_stitch_strategies = list(stitch_strategies_base)

    if benchmark_type == "1HOP":
        current_pushdown_strategies = ["none"]
        if is_verbose: print(f"    (StrategyDeterminer Q:{original_query_id_str} - Type {benchmark_type}: Pushdown strategy fixed to 'none')", flush=True)
    else: # 2HOP or 3HOP
        current_pushdown_strategies = list(pushdown_strategies_base)
        if is_verbose: print(f"    (StrategyDeterminer Q:{original_query_id_str} - Type {benchmark_type}: Using all pushdown strategies {current_pushdown_strategies})", flush=True)

    if not current_temporal_strategies:
        if is_verbose: print(f"    (StrategyDeterminer Q:{original_query_id_str} - No temporal strategies applicable after DATE keyword check. No strategies for this query.)", flush=True)
        return []

    strategies_to_run = list(itertools.product(
        current_temporal_strategies,
        current_pushdown_strategies,
        current_stitch_strategies
    ))

    if not strategies_to_run and is_verbose:
        print(f"    (StrategyDeterminer Q:{original_query_id_str} - No strategy combinations generated after product. No strategies for this query.)", flush=True)

    return strategies_to_run

def run_warm_mode(cli_process, queries_to_run, args, temporal_strategies_base, pushdown_strategies_base, stitch_strategies_base, all_run_results_accumulator):
    NUM_TIMED_RUNS_WARM = 3
    print(f"  Warm Cache Mode: Queries in order. 1 warm-up + {NUM_TIMED_RUNS_WARM} timed runs per setting.", flush=True)
    current_queries_for_warm_mode = list(queries_to_run) # Process in original order

    for query_info in current_queries_for_warm_mode:
        original_query_id_str = f"q{query_info['id']+1}" # Use the global unique ID
        query_text = query_info['text']
        expected_answer = query_info['expected']
        benchmark_type = query_info['benchmark_type']
        source_file = query_info['source_file']

        print(f"\n--- Query {original_query_id_str} (File: {source_file}, Type: {benchmark_type}) [WARM]: {query_text[:100]}{'...' if len(query_text)>100 else ''} ---", flush=True)

        strategies_to_run_for_this_query = _determine_strategies_for_query(
            query_info, temporal_strategies_base, pushdown_strategies_base, stitch_strategies_base, args.verbose
        )

        if not strategies_to_run_for_this_query:
            if args.verbose: print(f"    (Q:{original_query_id_str} - WARM - No strategy combinations to run after filtering. This query will be skipped for WARM mode.)", flush=True)
            continue

        for temp_s, push_s, stitch_s in strategies_to_run_for_this_query:
            progress_prefix = f"    [WARM Q:{original_query_id_str} File:{source_file} T:{temp_s},P:{push_s},S:{stitch_s},BT:{benchmark_type}]"

            run_stdout_details = ""
            run_stderr_details = ""
            current_strategy_critical_failure = False

            try:
                _, _, strat_set_stderr = cli_process.set_strategy(temp_s, push_s, stitch_s)
                if strat_set_stderr: run_stderr_details += f"SET_STRATEGY_STDERR: {strat_set_stderr.strip()}\n"
            except (ConnectionAbortedError, TimeoutError, RuntimeError) as e_strat_set_critical:
                print(f"{progress_prefix} CRITICAL ERROR setting strategy: {e_strat_set_critical}. Re-raising.", flush=True)
                run_stderr_details += f"CRITICAL_ERROR_SET_STRATEGY: {str(e_strat_set_critical)}\n{traceback.format_exc()}\n"
                all_run_results_accumulator.append({
                    "original_query_id": original_query_id_str, "query_text": query_text, "expected_answer": expected_answer,
                    "benchmark_type": benchmark_type, "source_file": source_file,
                    "temporal_strategy": temp_s, "pushdown_strategy": push_s, "stitch_strategy": stitch_s, "cache_mode": "warm",
                    "avg_time_ms": None, "individual_run_times_ms": [],
                    "verification_status": "CLI_PROCESS_ERROR_STRATEGY", "stderr_output": run_stderr_details,
                    "stdout_output": ""
                })
                raise # Re-raise to be caught by per-file handler
            except Exception as e_strat_set:
                print(f"{progress_prefix} ERROR setting strategy: {e_strat_set}. Skipping this strategy combination.", flush=True)
                run_stderr_details += f"NON_CRITICAL_ERROR_SET_STRATEGY: {str(e_strat_set)}\n"
                all_run_results_accumulator.append({
                    "original_query_id": original_query_id_str, "query_text": query_text, "expected_answer": expected_answer,
                    "benchmark_type": benchmark_type, "source_file": source_file,
                    "temporal_strategy": temp_s, "pushdown_strategy": push_s, "stitch_strategy": stitch_s, "cache_mode": "warm",
                    "avg_time_ms": None, "individual_run_times_ms": [],
                    "verification_status": "STRATEGY_SETUP_FAIL_NON_CRITICAL", "stderr_output": run_stderr_details,
                    "stdout_output": ""
                })
                current_strategy_critical_failure = True # Mark to skip further operations for this combo
                # continue # No, let it flow to the append, but the flag will prevent exec

            timed_exec_times = []

            if not current_strategy_critical_failure:
                print(f"{progress_prefix}  Warm-up 1/1...", flush=True)
                try:
                    _, wu_out, wu_err = cli_process.execute_query(query_text)
                    if wu_err: run_stderr_details += f"WARMUP_QUERY_STDERR: {wu_err.strip()}\n"
                    if args.verbose and wu_out: run_stdout_details += f"WARMUP_QUERY_STDOUT: {wu_out.strip()}\n"
                except (ConnectionAbortedError, TimeoutError, RuntimeError) as e_warmup_critical:
                    print(f"{progress_prefix}  Warm-up CRITICAL ERROR: {e_warmup_critical}. Re-raising.", flush=True)
                    run_stderr_details += f"CRITICAL_ERROR_WARMUP: {str(e_warmup_critical)}\n{traceback.format_exc()}\n"
                    all_run_results_accumulator.append({ # Log partial failure before re-raising
                        "original_query_id": original_query_id_str, "query_text": query_text, "expected_answer": expected_answer,
                        "benchmark_type": benchmark_type, "source_file": source_file,
                        "temporal_strategy": temp_s, "pushdown_strategy": push_s, "stitch_strategy": stitch_s, "cache_mode": "warm",
                        "avg_time_ms": None, "individual_run_times_ms": [],
                        "verification_status": "CLI_PROCESS_ERROR_WARMUP", "stderr_output": run_stderr_details, "stdout_output": ""
                    })
                    raise # Re-raise
                except Exception as e_warmup:
                    print(f"{progress_prefix}  Warm-up ERROR: {e_warmup}. This strategy combination will be marked as failed.", flush=True)
                    run_stderr_details += f"NON_CRITICAL_ERROR_WARMUP: {str(e_warmup)}\n"
                    current_strategy_critical_failure = True # Mark to skip timed runs

            verification_export_file = None
            if args.export_dir and expected_answer and not current_strategy_critical_failure:
                safe_query_part = re.sub(r'[^a-zA-Z0-9_-]', '_', query_text)[:30]
                fn_base = f"{original_query_id_str}_{safe_query_part}_T{temp_s}_P{push_s}_S{stitch_s}_warm"
                verification_export_file = os.path.join(args.export_dir, f"{fn_base}_FOR_VERIFICATION.csv")

            for run_num in range(NUM_TIMED_RUNS_WARM):
                current_run_stdout, current_run_stderr = "", ""
                try:
                    if verification_export_file and run_num == NUM_TIMED_RUNS_WARM - 1: # Last timed run
                        _, _, set_out_err = cli_process.set_output("csv", verification_export_file)
                        if set_out_err: run_stderr_details += f"SET_OUTPUT_VERIFY_STDERR (Run {run_num+1}): {set_out_err.strip()}\n"

                    b_time, out_q, err_q = cli_process.execute_query(query_text)
                    current_run_stdout = out_q
                    current_run_stderr = err_q

                    if err_q: run_stderr_details += f"QUERY_STDERR (Run {run_num+1}): {err_q.strip()}\n"

                    if args.verbose and out_q:
                        run_stdout_details += f"QUERY_STDOUT (Run {run_num+1}): {out_q.strip()}\n"
                    elif run_num == NUM_TIMED_RUNS_WARM - 1: # Capture last run's stdout if not verbose
                        run_stdout_details = out_q

                    if b_time is not None:
                        timed_exec_times.append(b_time)
                        print(f"{progress_prefix}    Run {run_num+1}: ExecT={b_time:.3f}ms", flush=True)
                    else:
                        print(f"{progress_prefix}    Run {run_num+1}: ExecT=N/A. Check stderr.", flush=True)

                    if verification_export_file and run_num == NUM_TIMED_RUNS_WARM - 1: # Last timed run
                        _, _, set_out_none_err = cli_process.set_output() # Revert to NONE
                        if set_out_none_err: run_stderr_details += f"SET_OUTPUT_NONE_STDERR (Run {run_num+1}): {set_out_none_err.strip()}\n"

                except (ConnectionAbortedError, TimeoutError, RuntimeError) as e_query_run_critical:
                    print(f"{progress_prefix}  Run {run_num+1} CRITICAL ERROR: {e_query_run_critical}. Re-raising.", flush=True)
                    run_stderr_details += f"CRITICAL_ERROR_QUERY_RUN (Run {run_num+1}): {str(e_query_run_critical)}\n{traceback.format_exc()}\n"
                    # Log partial failure for this strategy before re-raising
                    all_run_results_accumulator.append({
                        "original_query_id": original_query_id_str, "query_text": query_text, "expected_answer": expected_answer,
                        "benchmark_type": benchmark_type, "source_file": source_file,
                        "temporal_strategy": temp_s, "pushdown_strategy": push_s, "stitch_strategy": stitch_s, "cache_mode": "warm",
                        "avg_time_ms": sum(timed_exec_times) / len(timed_exec_times) if timed_exec_times else None,
                        "individual_run_times_ms": timed_exec_times,
                        "verification_status": "CLI_PROCESS_ERROR_EXEC", "stderr_output": run_stderr_details,
                        "stdout_output": run_stdout_details.strip() if args.verbose else "" # Include what we have
                    })
                    raise # Re-raise critical error
                except Exception as e_query_run:
                    print(f"{progress_prefix}  Run {run_num+1} ERROR: {e_query_run}. This run will be skipped, but other runs for this strategy may proceed.", flush=True)
                    run_stderr_details += f"NON_CRITICAL_ERROR_QUERY_RUN (Run {run_num+1}): {str(e_query_run)}\n{traceback.format_exc()}\n"
                    bfr_out, bfr_err = cli_process._collect_current_cycle_output(clear_buffers=True)
                    if bfr_out and args.verbose: run_stdout_details += f"BUFFERED_STDOUT_ON_EXC (Run {run_num+1}): {bfr_out.strip()}\n"
                    if bfr_err: run_stderr_details += f"BUFFERED_STDERR_ON_EXC (Run {run_num+1}): {bfr_err.strip()}\n"
                    # continue # Allow other runs for this strategy to proceed if this was a non-critical error for this specific run

            avg_exec_t = sum(timed_exec_times) / len(timed_exec_times) if timed_exec_times else None
            ver_status = "SKIPPED"

            if current_strategy_critical_failure: # If setup or warmup failed non-critically
                 ver_status = "STRATEGY_PREP_FAIL_NON_CRITICAL" # Specific status for this case
            elif avg_exec_t is None and "timeout" in run_stderr_details.lower() and "CRITICAL_ERROR" not in run_stderr_details.upper():
                # This 'timeout' is likely from the query execution itself within QueryCLI, not a Python-level TimeoutError from process interaction
                ver_status = "TIMEOUT_IN_CLI"
            else:
                content_for_analysis = ""
                source_of_content = "N/A"
                is_content_from_file = False

                if verification_export_file and os.path.exists(verification_export_file):
                    try:
                        with open(verification_export_file, 'r', encoding='utf-8') as vf:
                            content_for_analysis = vf.read()
                        source_of_content = verification_export_file
                        is_content_from_file = True
                    except Exception as e_vf:
                        ver_status = f"VERIFY_EXPORT_READ_ERR: {e_vf}"
                        run_stderr_details += f"VERIFY_FILE_READ_EXCEPTION ({verification_export_file}): {str(e_vf)}\n"
                elif run_stdout_details: # Fallback to (potentially accumulated) stdout
                    content_for_analysis = run_stdout_details
                    source_of_content = "stdout (last run or verbose accumulated)"
                    is_content_from_file = False

                if ver_status == "SKIPPED":
                    if is_output_effectively_empty(content_for_analysis, is_content_from_file, PROMPT):
                        ver_status = "EMPTY"
                        if args.verbose: print(f"{progress_prefix} Verification 'EMPTY' determined from: {source_of_content}", flush=True)
                    elif expected_answer:
                        if content_for_analysis:
                            ver_status = "PASSED" if expected_answer.lower() in content_for_analysis.lower() else "FAILED"
                            if args.verbose: print(f"{progress_prefix} Verification '{ver_status}' using: {source_of_content} against expected answer.", flush=True)
                        else:
                            ver_status = "NO_VERIFIABLE_CONTENT (Expected answer, but no output from query to check)"
                            if args.verbose: print(f"{progress_prefix} Verification 'NO_VERIFIABLE_CONTENT' from: {source_of_content}", flush=True)

            all_run_results_accumulator.append({
                "original_query_id": original_query_id_str, "query_text": query_text, "expected_answer": expected_answer,
                "benchmark_type": benchmark_type, "source_file": source_file,
                "temporal_strategy": temp_s, "pushdown_strategy": push_s, "stitch_strategy": stitch_s, "cache_mode": "warm",
                "avg_time_ms": avg_exec_t,
                "individual_run_times_ms": timed_exec_times,
                "verification_status": ver_status,
                "stdout_output": run_stdout_details.strip() if args.verbose or (ver_status not in ["SKIPPED", "NO_VERIFIABLE_CONTENT"] and not (verification_export_file and os.path.exists(verification_export_file))) else "",
                "stderr_output": run_stderr_details.strip()
            })

def run_cold_mode(cli_process, queries_to_run, args, temporal_strategies_base, pushdown_strategies_base, stitch_strategies_base, all_run_results_accumulator):
    NUM_COLD_PASSES = 3
    print(f"  Cold Cache Mode: {NUM_COLD_PASSES} passes. All (query, strategy) combinations are generated, then shuffled before each pass. 1 execution per combination per pass.", flush=True)

    cold_mode_tasks_definitions = []

    for query_info_orig_for_cold in queries_to_run:
        q_id_cold_task_gen = f"q{query_info_orig_for_cold['id']+1}"

        # Call the helper function with base strategies
        strategies_for_this_query_cold = _determine_strategies_for_query(
            query_info_orig_for_cold,
            temporal_strategies_base,
            pushdown_strategies_base,
            stitch_strategies_base,
            args.verbose
        )

        if not strategies_for_this_query_cold:
            if args.verbose: print(f"    (ColdTaskGen Q:{q_id_cold_task_gen} - No strategy combinations returned by helper. Skipping task generation for this query.)", flush=True)
            continue

        for temp_s_c, push_s_c, stitch_s_c in strategies_for_this_query_cold:
            cold_mode_tasks_definitions.append({
                'query_info': query_info_orig_for_cold,
                'temporal_strategy': temp_s_c,
                'pushdown_strategy': push_s_c,
                'stitch_strategy': stitch_s_c,
                'pass_results': []
            })

    if not cold_mode_tasks_definitions:
        print("  Cold Cache Mode: No tasks generated after filtering. Skipping cold mode.", flush=True)
        return

    for pass_num in range(NUM_COLD_PASSES):
        print(f"\n  --- COLD CACHE: PASS {pass_num + 1}/{NUM_COLD_PASSES} ---", flush=True)
        current_pass_tasks = list(cold_mode_tasks_definitions)
        random.shuffle(current_pass_tasks)

        for task_idx, task_data_ref in enumerate(current_pass_tasks):
            query_info = task_data_ref['query_info']
            original_query_id_str = f"q{query_info['id']+1}"
            query_text = query_info['text']
            benchmark_type = query_info['benchmark_type']
            source_file = query_info['source_file']
            temp_s = task_data_ref['temporal_strategy']
            push_s = task_data_ref['pushdown_strategy']
            stitch_s = task_data_ref['stitch_strategy']

            progress_prefix = f"    [COLD P:{pass_num+1}/{NUM_COLD_PASSES} Task:{task_idx+1}/{len(current_pass_tasks)} Q:{original_query_id_str} File:{source_file} T:{temp_s},P:{push_s},S:{stitch_s},BT:{benchmark_type}]"

            pass_stdout = ""
            pass_stderr = ""
            pass_time = None
            pass_error_flag = False

            try:
                _, _, strat_set_stderr = cli_process.set_strategy(temp_s, push_s, stitch_s)
                if strat_set_stderr: pass_stderr += f"SET_STRATEGY_STDERR: {strat_set_stderr.strip()}\n"

                b_time, out_q, err_q = cli_process.execute_query(query_text)
                pass_stdout = out_q or ""
                if err_q: pass_stderr += f"QUERY_STDERR (Pass {pass_num+1}): {err_q.strip()}\n"
                pass_time = b_time

                if pass_time is not None:
                    print(f"{progress_prefix}    Pass {pass_num+1} ExecT={pass_time:.3f}ms", flush=True)
                else:
                    print(f"{progress_prefix}    Pass {pass_num+1} ExecT=N/A. Check stderr.", flush=True)

            except (ConnectionAbortedError, TimeoutError, RuntimeError) as e_query_run_cold_critical:
                print(f"{progress_prefix}  Pass {pass_num+1} CRITICAL ERROR: {e_query_run_cold_critical}. Re-raising.", flush=True)
                pass_stderr += f"CRITICAL_ERROR_QUERY_RUN_COLD (Pass {pass_num+1}): {str(e_query_run_cold_critical)}\n{traceback.format_exc()}\n"
                pass_error_flag = True # Mark this pass as having an error
                # We need to record this pass attempt before re-raising.
                task_data_ref['pass_results'].append({
                    'time': None,
                    'stdout': pass_stdout, # Capture whatever stdout was collected
                    'stderr': pass_stderr,
                    'error_during_pass': True,
                    'critical_error_details': f"CRITICAL_ERROR_QUERY_RUN_COLD: {str(e_query_run_cold_critical)}\n{traceback.format_exc()}"
                })
                raise # Re-raise critical error to be caught by per-file handler
            except Exception as e_query_run_cold:
                print(f"{progress_prefix}  Pass {pass_num+1} ERROR: {e_query_run_cold}", flush=True)
                pass_stderr += f"NON_CRITICAL_ERROR_QUERY_RUN_COLD (Pass {pass_num+1}): {str(e_query_run_cold)}\n{traceback.format_exc()}\n"
                pass_error_flag = True
                bfr_out_cold, bfr_err_cold = cli_process._collect_current_cycle_output(clear_buffers=True)
                if bfr_out_cold and args.verbose : pass_stdout += f"BUFFERED_STDOUT_ON_EXC (Pass {pass_num+1}): {bfr_out_cold.strip()}\n"
                if bfr_err_cold : pass_stderr += f"BUFFERED_STDERR_ON_EXC (Pass {pass_num+1}): {bfr_err_cold.strip()}\n"

            task_data_ref['pass_results'].append({
                'time': pass_time,
                'stdout': pass_stdout,
                'stderr': pass_stderr,
                'error_during_pass': pass_error_flag
            })

    print("\n  --- COLD CACHE: AGGREGATING RESULTS & VERIFYING ---", flush=True)
    for task_data in cold_mode_tasks_definitions:
        query_info = task_data['query_info']
        original_query_id_str = f"q{query_info['id']+1}"
        query_text = query_info['text']
        expected_answer = query_info['expected']
        benchmark_type = query_info['benchmark_type']
        source_file = query_info['source_file']
        temp_s = task_data['temporal_strategy']
        push_s = task_data['pushdown_strategy']
        stitch_s = task_data['stitch_strategy']

        pass_results_list = task_data['pass_results']
        timed_exec_times_cold = [res['time'] for res in pass_results_list if res['time'] is not None and not res.get('error_during_pass')]
        avg_exec_t_cold = sum(timed_exec_times_cold) / len(timed_exec_times_cold) if timed_exec_times_cold else None

        stderr_final_cold = ""
        critical_failure_in_passes = False
        for i, res in enumerate(pass_results_list):
            if res.get('stderr'): stderr_final_cold += f"PASS_{i+1}_STDERR: {res['stderr'].strip()}\n"
            if res.get('error_during_pass'): stderr_final_cold += f"PASS_{i+1}_FLAGGED_ERROR: True\n"
            if res.get('critical_error_details'):
                stderr_final_cold += f"PASS_{i+1}_CRITICAL_ERROR_DETAILS: {res['critical_error_details'].strip()}\n"
                critical_failure_in_passes = True

        verification_status_cold = "SKIPPED"
        stdout_for_verification_cold = ""
        source_of_content_cold = "N/A"
        is_content_from_file_cold = False
        verification_export_file_cold = None

        if not pass_results_list:
            stderr_final_cold += "NO_PASS_RESULTS_RECORDED_FOR_TASK\n"
            verification_status_cold = "ERROR_NO_PASS_DATA"
        else:
            last_valid_pass_for_stdout = next((p for p in reversed(pass_results_list) if not p.get('error_during_pass')), None)
            if last_valid_pass_for_stdout:
                    stdout_for_verification_cold = last_valid_pass_for_stdout.get('stdout', "")
                    source_of_content_cold = f"stdout (last valid pass, index {pass_results_list.index(last_valid_pass_for_stdout)+1})"
            elif pass_results_list:
                    stdout_for_verification_cold = pass_results_list[-1].get('stdout', "")
                    source_of_content_cold = f"stdout (last pass - had error, index {len(pass_results_list)})"

            if avg_exec_t_cold is None and any("timeout" in res.get('stderr', "").lower() for res in pass_results_list if res.get('stderr')) and not critical_failure_in_passes:
                verification_status_cold = "TIMEOUT_IN_CLI"
            elif critical_failure_in_passes : # If any pass had a critical failure that was re-raised (and thus stopped file processing) this task aggregation might not be fully representative
                verification_status_cold = "CLI_PROCESS_ERROR_COLD_PASS"
            else:
                content_for_analysis_cold = stdout_for_verification_cold

                if args.export_dir and expected_answer:
                    safe_query_part = re.sub(r'[^a-zA-Z0-9_-]', '_', query_text)[:30]
                    fn_base = f"{original_query_id_str}_{safe_query_part}_T{temp_s}_P{push_s}_S{stitch_s}_cold_verify"
                    verification_export_file_cold = os.path.join(args.export_dir, f"{fn_base}.csv")
                    try:
                        with open(verification_export_file_cold, 'w', encoding='utf-8') as vf_cold:
                            vf_cold.write(stdout_for_verification_cold)
                        content_for_analysis_cold = stdout_for_verification_cold
                        source_of_content_cold = verification_export_file_cold
                        is_content_from_file_cold = True
                        if args.verbose: print(f"    (ColdVerify Q:{original_query_id_str} - Wrote verification output to {verification_export_file_cold}) from {source_of_content_cold}", flush=True)
                    except Exception as e_vf_cold:
                        verification_status_cold = f"VERIFY_EXPORT_WRITE_ERR: {e_vf_cold}"
                        stderr_final_cold += f"VERIFY_FILE_WRITE_EXCEPTION ({verification_export_file_cold}): {str(e_vf_cold)}\n"

                if verification_status_cold == "SKIPPED":
                    if is_output_effectively_empty(content_for_analysis_cold, is_content_from_file_cold, PROMPT):
                        verification_status_cold = "EMPTY"
                        if args.verbose: print(f"    (ColdVerify Q:{original_query_id_str} - Status 'EMPTY' from {source_of_content_cold})", flush=True)
                    elif expected_answer:
                        if content_for_analysis_cold:
                            verification_status_cold = "PASSED" if expected_answer.lower() in content_for_analysis_cold.lower() else "FAILED"
                            if args.verbose: print(f"    (ColdVerify Q:{original_query_id_str} - Status '{verification_status_cold}' from {source_of_content_cold})", flush=True)
                        else:
                            verification_status_cold = "NO_VERIFIABLE_CONTENT (Expected answer, but no output from selected pass to check)"
                            if args.verbose: print(f"    (ColdVerify Q:{original_query_id_str} - Status 'NO_VERIFIABLE_CONTENT' from {source_of_content_cold})", flush=True)

        all_run_results_accumulator.append({
            "original_query_id": original_query_id_str, "query_text": query_text, "expected_answer": expected_answer,
            "benchmark_type": benchmark_type, "source_file": source_file,
            "temporal_strategy": temp_s, "pushdown_strategy": push_s, "stitch_strategy": stitch_s,
            "cache_mode": "cold",
            "avg_time_ms": avg_exec_t_cold,
            "individual_run_times_ms": timed_exec_times_cold,
            "verification_status": verification_status_cold,
            "stdout_output": stdout_for_verification_cold.strip() if args.verbose or (verification_status_cold not in ["SKIPPED", "NO_VERIFIABLE_CONTENT"] and not is_content_from_file_cold) else "",
            "stderr_output": stderr_final_cold.strip()
        })

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

            # Wait for initial prompt using the new helper
            stdout_init, stderr_init = self._send_and_await_prompt(None, PROMPT, CLI_STARTUP_TIMEOUT_SECONDS, "CLI Startup")
            if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] QueryCLI started successfully and '{PROMPT}' received.", flush=True)

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
                # This case should ideally not be reached if Popen initializes stdin=PIPE correctly
                # and stdin hasn't been closed and set to None.
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

    def set_output(self, export_format=None, filename=None):
        cmd = f"SET OUTPUT {export_format} {filename}" if export_format and filename else "SET OUTPUT NONE"
        stdout_cycle, stderr_cycle = self._send_and_await_prompt(cmd, PROMPT, COMMAND_ACK_TIMEOUT_SECONDS, "SET OUTPUT")
        return None, stdout_cycle, stderr_cycle

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
                    self.process.stdin.close() # Close stdin after sending EXIT
            except (IOError, ValueError) as e_stdin:
                if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] Error interacting with QueryCLI stdin during close (possibly already closed): {e_stdin}", flush=True)

        if self.process:
            if self.is_verbose: print("[BENCHMARK.PY DEBUG] Waiting for QueryCLI process to terminate...", flush=True)
            try:
                self.process.wait(timeout=PROCESS_TERMINATION_TIMEOUT_SECONDS)
                if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] QueryCLI process terminated with rc: {self.process.returncode}", flush=True)
            except subprocess.TimeoutExpired:
                if self.is_verbose: print("[BENCHMARK.PY DEBUG] QueryCLI did not terminate after EXIT and wait, killing.", flush=True)
                self.process.kill()
                try:
                    self.process.wait(timeout=PROCESS_TERMINATION_TIMEOUT_SECONDS / 2) # Shorter wait after kill
                    if self.is_verbose: print("[BENCHMARK.PY DEBUG] QueryCLI process killed and waited.", flush=True)
                except subprocess.TimeoutExpired:
                    if self.is_verbose: print("[BENCHMARK.PY DEBUG] QueryCLI kill command also timed out. Process might be orphaned.", flush=True)
                except Exception as e_kill_wait_final:
                    if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] Exception during post-kill wait: {e_kill_wait_final}", flush=True)
            except Exception as e_wait: # Catch other exceptions during the initial wait
                if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] Error waiting for QueryCLI process: {e_wait}. Attempting kill.", flush=True)
                self.process.kill()
                try:
                    self.process.wait(timeout=PROCESS_TERMINATION_TIMEOUT_SECONDS / 2)
                    if self.is_verbose: print("[BENCHMARK.PY DEBUG] QueryCLI process killed after initial wait error.", flush=True)
                except Exception as e_kill_generic:
                    if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] Exception during post-error kill wait: {e_kill_generic}", flush=True)

        # Attempt to join threads with a timeout, less verbose
        for thread, name in [(self._stdout_thread, "stdout"), (self._stderr_thread, "stderr")]:
            if thread and thread.is_alive():
                if self.is_verbose: print(f"[BENCHMARK.PY DEBUG] Waiting for {name} reader thread to join...", flush=True)
                thread.join(timeout=2) # Shorter timeout for thread joins
                if thread.is_alive() and self.is_verbose:
                     print(f"[BENCHMARK.PY DEBUG] {name} reader thread did not join in time.", flush=True)

        # Drain any remaining output, less verbose about it
        _, _ = self._collect_current_cycle_output(clear_buffers=True)

        self.process = None
        if self.is_verbose: print("[BENCHMARK.PY DEBUG] QueryCLI process interface definitively closed.", flush=True)


if __name__ == "__main__":
    # print("[BENCHMARK.PY] Initializing benchmark script...", flush=True) # Loading message removed
    parser = argparse.ArgumentParser(description="Benchmark QueryCLI.java using interactive mode.")
    parser.add_argument("--query-dir", required=True, help="Path to a directory containing query files. Each file's first line must be 'BENCHMARK_TYPE: <TYPE>', where TYPE is 1HOP, 2HOP, or 3HOP.")
    parser.add_argument("--jar-path", default=DEFAULT_JAR_PATH, help=f"Path to QueryCLI JAR (default: {DEFAULT_JAR_PATH})")
    parser.add_argument("--db-file", required=True, help="Path to QueryCLI SQLite database file.")
    parser.add_argument("--index-dir", required=True, help="Path to QueryCLI index directory.")
    parser.add_argument("--full", action="store_true", help="Run cold and warm cache modes. Default: cold only.")
    parser.add_argument("--verbose", action="store_true", help="Verbose output from benchmark script and QueryCLI.")
    parser.add_argument("--export-dir", help="Directory for exported CSV results (for verification). NOTE: This is for raw query output, not the benchmark summary.")
    args = parser.parse_args()

    queries_to_run_orig = [] # This will be populated per file now
    if not args.query_dir or not os.path.isdir(args.query_dir): # Combined check
        exit(f"Error: Query directory not found or not specified: {args.query_dir}")

    all_query_files = [f for f in os.listdir(args.query_dir) if os.path.isfile(os.path.join(args.query_dir, f)) and f.lower().endswith('.txt')]
    if not all_query_files:
        exit(f"Error: No .txt query files found in query directory: {args.query_dir}")

    if args.export_dir and not os.path.exists(args.export_dir):
        try: os.makedirs(args.export_dir); print(f"Created export directory: {args.export_dir}", flush=True)
        except OSError as e: exit(f"Error creating export directory {args.export_dir}: {e}")

    print(f"Using JAR: {args.jar_path}", flush=True)
    print(f"Using DB File: {args.db_file}", flush=True)
    print(f"Using Index Dir: {args.index_dir}", flush=True)
    print(f"Initial CLI strategies (hardcoded): T:nash, P:optimized, S:optimized", flush=True)
    print(f"Cache Mode: {'full (cold + warm)' if args.full else 'cold only'}. Runs/Passes: Warm (1 warmup + 3 timed), Cold (3 passes, 1 exec per pass).", flush=True)
    print(f"Timeouts: QueryExec={QUERY_EXEC_TIMEOUT_SECONDS}s, CLIStartup={CLI_STARTUP_TIMEOUT_SECONDS}s, CmdAck={COMMAND_ACK_TIMEOUT_SECONDS}s, PromptWait={PROMPT}", flush=True)
    if args.verbose: print("Verbose output enabled.", flush=True)
    if args.export_dir: print(f"Verification exports (if any) to: {args.export_dir}", flush=True)
    print(f"Benchmark summary CSVs will be saved alongside their respective input query files in: {args.query_dir}", flush=True)

    total_files_processed = 0
    total_csv_files_written = 0

    for file_idx, current_filename in enumerate(all_query_files):
        print(f"\n\n========== PROCESSING FILE {file_idx + 1}/{len(all_query_files)}: {current_filename} ==========", flush=True)
        filepath = os.path.join(args.query_dir, current_filename)

        queries_for_this_file = []
        query_id_counter_local = 0
        benchmark_type_for_file = None

        # Infer benchmark_type_for_file from filename
        fn_lower = current_filename.lower()
        if "1hop" in fn_lower:
            benchmark_type_for_file = "1HOP"
        elif "2hop" in fn_lower:
            benchmark_type_for_file = "2HOP"
        elif "3hop" in fn_lower:
            benchmark_type_for_file = "3HOP"
        else:
            print(f"Warning: Could not determine benchmark type (1HOP, 2HOP, 3HOP) from filename '{current_filename}'. Skipping this file.", flush=True)
            continue

        print(f"  Inferred benchmark type: {benchmark_type_for_file} for file: {current_filename}", flush=True)

        try:
            with open(filepath, 'r', encoding='utf-8') as f:
                # Removed logic for reading BENCHMARK_TYPE from the first line.
                # All lines are now considered potential queries or comments.
                file_queries_loaded_count = 0
                for line_num_in_file, line_content in enumerate(f, start=1): # Start from line 1
                    line_content = line_content.strip()
                    if not line_content or line_content.startswith('#'): continue # Skip empty lines and comments

                    parts = line_content.split(' ::: ', 1)
                    queries_for_this_file.append({
                        'id': query_id_counter_local,
                        'text': parts[0],
                        'expected': parts[1] if len(parts) > 1 else None,
                        'benchmark_type': benchmark_type_for_file, # Use inferred type
                        'source_file': current_filename
                    })
                    query_id_counter_local += 1
                    file_queries_loaded_count += 1

                if file_queries_loaded_count == 0:
                    print(f"Warning: File {current_filename} (Type: {benchmark_type_for_file}) contained no queries. Skipping this file's benchmarks.", flush=True)
                    continue
        except FileNotFoundError:
            print(f"Warning: File {current_filename} not found during processing. Skipping.", flush=True)
            continue
        except Exception as e_file_read:
            print(f"Warning: Error reading query file {current_filename}: {e_file_read}. Skipping.", flush=True)
            continue

        if not queries_for_this_file:
            print(f"No valid queries loaded from {current_filename}. File will be skipped.", flush=True)
            continue

        total_files_processed += 1
        results_for_this_file_accumulator = []

        temporal_strategies_base = ["naive", "nash"]
        pushdown_strategies_base = ["none", "optimized"]
        stitch_strategies_base = ["none", "optimized"]

        cache_modes_to_run = ["cold"]
        if args.full: cache_modes_to_run.append("warm")

        for cache_mode in cache_modes_to_run:
            print(f"\n----- Running Cache Mode: {cache_mode.upper()} for {current_filename} -----", flush=True)
            cli_process_for_mode = None
            try:
                cli_process_for_mode = QueryCLIInteractiveProcess(
                    args.jar_path, args.db_file, args.index_dir,
                    "nash", "optimized", "optimized", # Hardcoded initial strategies
                    is_verbose=args.verbose
                )
                print(f"\n[BENCHMARK.PY] QueryCLI process initialized for '{cache_mode}' mode on file: {current_filename}", flush=True)

                if cache_mode == "cold":
                    run_cold_mode(cli_process_for_mode, queries_for_this_file, args, temporal_strategies_base, pushdown_strategies_base, stitch_strategies_base, results_for_this_file_accumulator)
                elif cache_mode == "warm":
                    run_warm_mode(cli_process_for_mode, queries_for_this_file, args, temporal_strategies_base, pushdown_strategies_base, stitch_strategies_base, results_for_this_file_accumulator)

            except (FileNotFoundError, TimeoutError, ConnectionAbortedError, RuntimeError) as e_cli_main_file:
                print(f"\nCRITICAL SCRIPT ERROR for file {current_filename} in '{cache_mode}' mode related to QueryCLI process: {e_cli_main_file}", flush=True)
                print(f"Traceback: {traceback.format_exc()}", flush=True)
                print(f"Benchmarks for {current_filename} in this mode and subsequent modes will be ABORTED.", flush=True)
                break # Exit the loop over cache modes for this file
            except KeyboardInterrupt:
                print(f"\nBenchmark for {current_filename} in '{cache_mode}' mode interrupted by user (Ctrl+C). Cleaning up...", flush=True)
                # This will fall through to finally, then exit the script because of the outer loop structure.
                raise
            except Exception as e_global_file:
                print(f"\nUNEXPECTED SCRIPT ERROR during processing of {current_filename} in '{cache_mode}' mode: {e_global_file}", flush=True)
                print(f"Traceback: {traceback.format_exc()}", flush=True)
                break # Exit the loop over cache modes for this file
            finally:
                if cli_process_for_mode:
                    print(f"\n[BENCHMARK.PY] Ensuring QueryCLI process for '{cache_mode}' mode on {current_filename} is closed...", flush=True)
                    cli_process_for_mode.close()
                print(f"[BENCHMARK.PY] Finished '{cache_mode}' mode for {current_filename}.", flush=True)

        if not results_for_this_file_accumulator:
            print(f"\nNo benchmark results were collected for {current_filename}. No summary CSV will be generated for this file.", flush=True)
            continue # Next file

        # Console Summary for the current file
        print(f"\n========== BENCHMARK SUMMARY FOR FILE: {current_filename} (Type: {benchmark_type_for_file}) ==========", flush=True)
        for res_item in results_for_this_file_accumulator:
            summary_line = (
                f"  Q:{res_item['original_query_id']} C:{res_item['cache_mode']} " # File and BT removed as they are per-file context now
                f"T:{res_item['temporal_strategy']},P:{res_item['pushdown_strategy']},S:{res_item['stitch_strategy']} "
            )
            summary_line += f"AvgTime:{res_item['avg_time_ms']:.3f}ms " if res_item['avg_time_ms'] is not None else "AvgTime:N/A "
            summary_line += f"Verify:{res_item['verification_status']}"
            print(summary_line, flush=True)

            if res_item['stderr_output'] and (args.verbose or "ERROR" in res_item['stderr_output'].upper() or "FAIL" in res_item['verification_status'].upper() or "TIMEOUT" in res_item['verification_status'].upper()):
                print(f"      Query Text (QID {res_item['original_query_id']}): {res_item['query_text']}", flush=True)
                indented_stderr = "\n".join([f"      Stderr: {line}" for line in res_item['stderr_output'].strip().split('\n')])
                print(indented_stderr, flush=True)
            if args.verbose and res_item['stdout_output']:
                 indented_stdout = "\n".join([f"      Stdout: {line}" for line in res_item['stdout_output'].strip().split('\n')])
                 print(indented_stdout, flush=True)

        # CSV Export for the current file
        print(f"\nExporting benchmark results for {current_filename} to a CSV file...", flush=True)
        fieldnames_csv = [
            'original_query_id', 'query_text', 'expected_answer',
            'benchmark_type', 'source_file', # benchmark_type and source_file are constant per CSV
            'temporal_strategy', 'pushdown_strategy', 'stitch_strategy', 'cache_mode',
            'avg_time_ms', 'individual_run_times_ms',
            'verification_status', 'stderr_output', 'stdout_output'
        ]
        original_filename_no_ext = os.path.splitext(current_filename)[0]
        # benchmark_type_for_file should be correctly set from file reading
        output_csv_filename = f"{original_filename_no_ext}_{benchmark_type_for_file}_results.csv"
        output_csv_filepath = os.path.join(args.query_dir, output_csv_filename)

        print(f"  Writing results for {current_filename} (Type: {benchmark_type_for_file}) to {output_csv_filepath}", flush=True)
        try:
            with open(output_csv_filepath, 'w', newline='', encoding='utf-8') as csvfile:
                writer = csv.DictWriter(csvfile, fieldnames=fieldnames_csv, extrasaction='ignore')
                writer.writeheader()
                for row_data_item in results_for_this_file_accumulator:
                    row_data_copy = row_data_item.copy()
                    row_data_copy['individual_run_times_ms'] = ';'.join([f"{t:.3f}" for t in row_data_copy.get('individual_run_times_ms', []) if t is not None])

                    if not args.verbose:
                        row_data_copy['stdout_output'] = ""
                        if args.export_dir and row_data_copy['verification_status'] == "PASSED":
                            if "VERIFY_EXPORT_WRITE_ERR" not in row_data_copy.get('stderr_output', ''):
                                row_data_copy['stdout_output'] = "See verification export file or run with --verbose"
                    writer.writerow(row_data_copy)
            total_csv_files_written += 1
        except Exception as e_csv_export_file:
            print(f"  Error exporting benchmark results for {current_filename} to CSV {output_csv_filepath}: {e_csv_export_file}\n{traceback.format_exc()}", flush=True)

    # --- End of loop over query files ---

    if total_files_processed == 0:
        print("\nNo query files were successfully processed from the directory.", flush=True)
    elif total_csv_files_written == 0:
        print(f"\nProcessed {total_files_processed} file(s), but no benchmark result CSV files were generated.", flush=True)
    else:
        print(f"\nSuccessfully exported {total_csv_files_written} benchmark result CSV file(s) in total to {args.query_dir}.", flush=True)

    print("\nBenchmark script execution complete.", flush=True)

