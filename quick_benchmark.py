import argparse
import csv
import os
import re
import shutil
import subprocess
import tempfile
import time
from datetime import datetime, timezone

# Reuse helpers from verify_correctness to avoid duplication
try:
    from verify_correctness import (
        QueryCLIInteractiveProcess,
        compare_csv_files_deep,
        _determine_strategies_for_query,
        _parse_selected_dims,
        DEFAULT_JAR_PATH,
    )
except Exception:
    # Fallbacks if direct import fails
    DEFAULT_JAR_PATH = "target/query-cli.jar"
    QueryCLIInteractiveProcess = None
    compare_csv_files_deep = None
    _determine_strategies_for_query = None
    _parse_selected_dims = None


PROMPT = "Query>"


def _get_git_commit_short(repo_dir):
    try:
        out = subprocess.check_output(["git", "rev-parse", "--short=12", "HEAD"], cwd=repo_dir, text=True)
        return out.strip()
    except Exception:
        return "unknown"


def _parse_benchmark_time_ms(stdout_text):
    if not stdout_text:
        return None
    try:
        for line in stdout_text.splitlines():
            if line.startswith("BENCHMARK_EXECUTION_TIME_MS:"):
                m = re.search(r"BENCHMARK_EXECUTION_TIME_MS: (\d+\.?\d*)", line)
                if m:
                    return float(m.group(1))
    except Exception:
        return None
    return None


def _load_first_n_queries(query_dir, n_per_file):
    files = [f for f in os.listdir(query_dir) if os.path.isfile(os.path.join(query_dir, f)) and f.lower().endswith('.txt')]
    selected = []
    for fname in files:
        fpath = os.path.join(query_dir, fname)
        bt = None
        low = fname.lower()
        if "1hop" in low: bt = "1HOP"
        elif "2hop" in low: bt = "2HOP"
        elif "3hop" in low: bt = "3HOP"
        else:
            continue

        queries = []
        try:
            with open(fpath, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith('#'):
                        continue
                    parts = line.split(' ::: ', 1)
                    queries.append({
                        'id': len(queries),
                        'text': parts[0],
                        'expected': parts[1] if len(parts) > 1 else None,
                        'benchmark_type': bt,
                        'source_file': fname,
                    })
                    if len(queries) >= n_per_file:
                        break
        except Exception:
            continue

        if queries:
            selected.append((fname, bt, queries))
    return selected


def _append_log_row(log_csv_path, header, row_dict):
    # Read existing rows (if any)
    existing_rows = []
    if os.path.exists(log_csv_path):
        try:
            with open(log_csv_path, 'r', newline='', encoding='utf-8') as rf:
                reader = csv.DictReader(rf)
                for r in reader:
                    existing_rows.append(r)
        except Exception:
            existing_rows = []

    # Dedup logic
    commit = row_dict.get('git_commit') or ''
    run_tag = row_dict.get('run_tag') or ''

    filtered = []
    for r in existing_rows:
        r_commit = (r.get('git_commit') or '')
        r_tag = (r.get('run_tag') or '')
        if run_tag:  # uniqueness by (commit, tag)
            if r_commit == commit and r_tag == run_tag:
                continue  # drop older same-key row
            filtered.append(r)
        else:  # no tag: keep rows with different commit, and also keep tagged rows even if same commit
            if r_commit == commit and not r_tag:
                continue  # drop older untagged row for same commit
            filtered.append(r)

    filtered.append(row_dict)

    # Write back entire CSV atomically
    tmp_path = log_csv_path + ".tmp"
    with open(tmp_path, 'w', newline='', encoding='utf-8') as wf:
        writer = csv.DictWriter(wf, fieldnames=header, extrasaction='ignore')
        writer.writeheader()
        for r in filtered:
            writer.writerow(r)
    os.replace(tmp_path, log_csv_path)


def _ensure_helpers_available():
    missing = []
    if QueryCLIInteractiveProcess is None: missing.append("QueryCLIInteractiveProcess")
    if compare_csv_files_deep is None: missing.append("compare_csv_files_deep")
    if _determine_strategies_for_query is None: missing.append("_determine_strategies_for_query")
    if _parse_selected_dims is None: missing.append("_parse_selected_dims")
    if missing:
        raise RuntimeError(f"Missing helpers from verify_correctness: {', '.join(missing)}. Ensure verify_correctness.py is present and importable.")


def main():
    parser = argparse.ArgumentParser(description="Quick benchmark: run a small subset of queries, time them, and verify consistency across strategies. Appends a compact row to a CSV log.")
    parser.add_argument("-q", "--query-dir", default="queries", help="Directory with query .txt files (expects names containing 1hop/2hop/3hop)")
    parser.add_argument("-j", "--jar-path", default=DEFAULT_JAR_PATH, help=f"Path to QueryCLI JAR (default: {DEFAULT_JAR_PATH})")
    parser.add_argument("-d", "--db-file", required=False, help="Path to SQLite DB file (optional; CLI can resolve from manifest)")
    parser.add_argument("-i", "--index-root-dir", required=True, help="Root directory containing project index folders")
    parser.add_argument("-n", "--num-per-file", type=int, default=10, help="Number of queries to take from each hop file (from the top)")
    parser.add_argument("-l", "--log-csv", default="quick_benchmark_log.csv", help="CSV log file to append a one-line summary per run")
    parser.add_argument("-t", "--run-tag", default=None, help="Optional tag to allow multiple runs per commit (dedupe key becomes commit+tag)")
    parser.add_argument("-o", "--output-dir", default=None, help="Optional temp output dir for intermediate files (default: ephemeral temp dir)")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose output from script and QueryCLI")
    parser.add_argument("-s", "--strategies", action="append", metavar="DIMENSIONS", help="Select strategy dimensions to vary vs base (t/temporal, p/pushdown, s/stitch). Example: -s s or -s t,p")

    args = parser.parse_args()

    _ensure_helpers_available()

    if not args.query_dir or not os.path.isdir(args.query_dir):
        exit(f"Error: Query directory not found: {args.query_dir}")

    try:
        selected_dims = _parse_selected_dims(args.strategies) if args.strategies is not None else {"stitch"}
    except ValueError as e:
        exit(str(e))

    selections = _load_first_n_queries(args.query_dir, args.num_per_file)
    if not selections:
        exit("No queries found to run in the specified directory.")

    repo_dir = os.path.abspath(os.path.dirname(__file__))
    git_commit = _get_git_commit_short(repo_dir)
    iso_ts = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')

    # Prepare temp output dir
    temp_root = args.output_dir or tempfile.mkdtemp(prefix="quick_bench_")
    os.makedirs(temp_root, exist_ok=True)

    hop_to_times = {"1HOP": [], "2HOP": [], "3HOP": []}
    all_consistent = True
    failures = 0

    cli = None
    try:
        cli = QueryCLIInteractiveProcess(
            args.jar_path,
            args.db_file,
            args.index_root_dir,
            "nash",
            "optimized",
            "optimized",
            is_verbose=args.verbose,
        )

        for fname, bt, queries in selections:
            subdir = os.path.join(temp_root, bt, os.path.splitext(fname)[0])
            os.makedirs(subdir, exist_ok=True)
            print(f"Running {len(queries)} queries from {fname}", flush=True)

            for q in queries:
                # Build strategies to test (base plus selected variants)
                strategies = _determine_strategies_for_query(q, args.verbose, selected_dims) or []
                base = ('naive', 'none', 'none')
                if base not in strategies:
                    strategies = [base] + strategies

                safe_q_part = re.sub(r'[^a-zA-Z0-9_-]', '_', q['text'])[:30]
                base_fp = os.path.join(subdir, f"{q['id']+1:04d}_{safe_q_part}_BASE.csv")
                # Base run
                try:
                    # Ensure base strategy
                    cli.set_strategy('naive', 'none', 'none')
                    _, _, set_out_err = cli.set_output('csv', base_fp)
                    if set_out_err and args.verbose:
                        print(f"[QB] WARN set_output base: {set_out_err.strip()}")
                    _, stdout_q, stderr_q = cli.execute_query(q['text'])
                    bench_ms = _parse_benchmark_time_ms(stdout_q)
                    if bench_ms is None:
                        # Fallback to rough wall time if CLI didn't emit benchmark line
                        # Note: We cannot re-run; so we won't wall-time here to avoid extra cost.
                        pass
                    if stderr_q:
                        all_consistent = False
                        failures += 1
                    if bench_ms is not None:
                        hop_to_times[bt].append(bench_ms)
                except Exception:
                    all_consistent = False
                    failures += 1
                    continue

                # Compare other strategies against base
                for (t_strat, p_strat, s_strat) in [s for s in strategies if s != base]:
                    other_fp = os.path.join(subdir, f"{q['id']+1:04d}_{safe_q_part}_T{t_strat}_P{p_strat}_S{s_strat}.csv")
                    try:
                        # Set variant strategy
                        cli.set_strategy(t_strat, p_strat, s_strat)
                        _, _, set_out_err2 = cli.set_output('csv', other_fp)
                        if set_out_err2 and args.verbose:
                            print(f"[QB] WARN set_output other: {set_out_err2.strip()}")
                        _, _, err = cli.execute_query(q['text'])
                        if err:
                            all_consistent = False
                            failures += 1
                            continue
                        ok, reason = compare_csv_files_deep(base_fp, other_fp)
                        if ok:
                            try:
                                os.remove(other_fp)
                            except Exception:
                                pass
                        else:
                            all_consistent = False
                            failures += 1
                            if args.verbose:
                                print(f"[QB] Inconsistent output ({bt} {fname} q{q['id']+1}): {reason}")
                    except Exception as e:
                        all_consistent = False
                        failures += 1
                        if args.verbose:
                            print(f"[QB] ERROR comparing strategies: {e}")

                # Reset output to console between queries
                try:
                    cli.set_output('csv', None)
                except Exception:
                    pass

        # Aggregate averages in seconds
        def avg_ms(vals):
            return sum(vals) / len(vals) if vals else None

        avg_1 = avg_ms(hop_to_times['1HOP'])
        avg_2 = avg_ms(hop_to_times['2HOP'])
        avg_3 = avg_ms(hop_to_times['3HOP'])

        row = {
            'timestamp': iso_ts,
            'git_commit': git_commit,
            'queries_per_file': args.num_per_file,
            'avg_1hop_ms': f"{avg_1:.3f}" if avg_1 is not None else "",
            'avg_2hop_ms': f"{avg_2:.3f}" if avg_2 is not None else "",
            'avg_3hop_ms': f"{avg_3:.3f}" if avg_3 is not None else "",
            'consistent': 'YES' if all_consistent else 'NO',
            'failures': failures,
            'run_tag': args.run_tag or "",
        }

        header = ['timestamp', 'git_commit', 'queries_per_file', 'avg_1hop_ms', 'avg_2hop_ms', 'avg_3hop_ms', 'consistent', 'failures', 'run_tag']
        _append_log_row(args.log_csv, header, row)

        # Console summary (compact)
        def fmt_sec(ms):
            if ms is None:
                return "N/A"
            return f"{ms/1000.0:.2f}s"
        # Unified table per request
        print("\nBENCHMARK RESULTS\n")
        headers = ["COMMIT", "1HOP", "2HOP", "3HOP", "CONSISTENT", "TAG"]
        top_row = [git_commit, fmt_sec(avg_1), fmt_sec(avg_2), fmt_sec(avg_3), ('YES' if all_consistent else 'NO'), (args.run_tag or "")]

        # Load last 10 rows from CSV (excluding the newest we just wrote when possible)
        history_rows = []
        try:
            rows = []
            if os.path.exists(args.log_csv):
                with open(args.log_csv, 'r', newline='', encoding='utf-8') as rf:
                    reader = csv.DictReader(rf)
                    for r in reader:
                        rows.append(r)
            # Exclude the last row (current) if present
            prior = rows[:-1] if rows else []
            last_10 = prior[-10:]
            for r in last_10:
                def ms_to_sec_cell(ms_str):
                    try:
                        if not ms_str:
                            return "N/A"
                        val = float(ms_str) / 1000.0
                        return f"{val:.2f}s"
                    except Exception:
                        return "N/A"
                history_rows.append([
                    (r.get('git_commit') or ""),
                    ms_to_sec_cell(r.get('avg_1hop_ms')),
                    ms_to_sec_cell(r.get('avg_2hop_ms')),
                    ms_to_sec_cell(r.get('avg_3hop_ms')),
                    (r.get('consistent') or "").upper(),
                    (r.get('run_tag') or ""),
                ])
        except Exception:
            history_rows = []

        # Compute column widths across header, top row, and history
        widths = [len(h) for h in headers]
        for idx, cell in enumerate(top_row):
            if len(cell) > widths[idx]: widths[idx] = len(cell)
        for hr in history_rows:
            for idx, cell in enumerate(hr):
                if len(cell) > widths[idx]: widths[idx] = len(cell)

        def fmt_row(cells):
            return " | ".join(c.ljust(widths[idx]) for idx, c in enumerate(cells))

        print(fmt_row(headers))
        print(fmt_row(top_row))
        print("----")
        for hr in history_rows:
            print(fmt_row(hr))

    finally:
        if cli:
            try:
                cli.close()
            except Exception:
                pass
        if args.output_dir is None and os.path.isdir(temp_root):
            # Clean ephemeral temp dir
            try:
                shutil.rmtree(temp_root)
            except Exception:
                pass


if __name__ == "__main__":
    main()


