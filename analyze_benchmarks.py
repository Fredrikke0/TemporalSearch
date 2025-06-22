#!/usr/bin/env python3
import argparse
import collections
import csv
import math
import re
import statistics
import sys


def get_hop_type(query_text):
    """Determines the hop type based on the number of JOIN clauses."""
    # Use word boundary to match JOIN as a complete word
    join_count = len(re.findall(r'\bJOIN\b', query_text, re.IGNORECASE))
    if join_count == 0:
        return "1-hop"
    elif join_count == 1:
        return "2-hops"
    elif join_count == 2:
        return "3-hops"
    else:
        return f"{join_count + 1}-hops" # For queries with >2 joins


def analyze_benchmarks(csv_filepath):
    """
    Analyzes benchmark data from a CSV file and returns aggregated results with mean and standard deviation.
    Assumes all data in the CSV pertains to a single benchmark type.
    """
    # Structure: results[(temporal, pushdown, stitch)][cache_mode] = [list of individual run times]
    results = collections.defaultdict(
            lambda: collections.defaultdict(list)
    )

    # Define all possible strategy values to ensure consistent ordering
    temporal_strategies = sorted(['naive', 'nash']) # Add others if they exist
    pushdown_strategies = sorted(['none', 'optimized']) # Add others if they exist
    stitch_strategies = sorted(['none', 'optimized']) # Add others if they exist
    cache_modes = sorted(['cold', 'warm'])

    # Generate all strategy combinations for ordering later
    strategy_tuples = []
    for ts in temporal_strategies:
        for ps in pushdown_strategies:
            for ss in stitch_strategies:
                strategy_tuples.append((ts, ps, ss))

    try:
        with open(csv_filepath, 'r', newline='') as csvfile:
            reader = csv.DictReader(csvfile)
            for row in reader:
                # Include all queries regardless of verification status
                # (verification is about answer correctness, not execution success)

                temporal = row['temporal_strategy']
                pushdown = row['pushdown_strategy']
                stitch = row['stitch_strategy']
                cache = row['cache_mode']

                # Parse individual run times from semicolon-separated string
                individual_times_str = row.get('individual_run_times_ms', '')
                if individual_times_str:
                    try:
                        individual_times = [float(t.strip()) for t in individual_times_str.split(';') if t.strip()]
                        results[(temporal, pushdown, stitch)][cache].extend(individual_times)
                    except ValueError as e:
                        print(f"Warning: Could not parse individual_run_times_ms '{individual_times_str}' for query_id {row['original_query_id']}: {e}. Skipping.", file=sys.stderr)
                        continue
                else:
                    # Fallback to avg_time_ms if individual times not available
                    try:
                        avg_time = float(row['avg_time_ms'])
                        results[(temporal, pushdown, stitch)][cache].append(avg_time)
                    except ValueError:
                        print(f"Warning: Could not parse avg_time_ms '{row['avg_time_ms']}' for query_id {row['original_query_id']}. Skipping.", file=sys.stderr)
                        continue

    except FileNotFoundError:
        print(f"Error: The file {csv_filepath} was not found.", file=sys.stderr)
        return None, None
    except Exception as e:
        print(f"An error occurred while reading the CSV: {e}", file=sys.stderr)
        return None, None

    # Calculate means and standard deviations
    stats = collections.defaultdict(
            lambda: collections.defaultdict(lambda: {'mean': 0.0, 'std': 0.0, 'count': 0})
    )

    for strat_tuple, cache_data in results.items():
        for cache_mode, times in cache_data.items():
            if times:
                mean_time = statistics.mean(times)
                std_time = statistics.stdev(times) if len(times) > 1 else 0.0
                stats[strat_tuple][cache_mode] = {
                    'mean': mean_time,
                    'std': std_time,
                    'count': len(times)
                }

    return stats, strategy_tuples

def generate_latex_table(stats, strategy_tuples, table_title="Benchmark Performance Summary"):
    """
    Generates a LaTeX table from the aggregated benchmark results with mean ± SD format.
    Outputs a fragment suitable for inclusion in a larger document, wrapped in a figure environment.
    """
    # Note: This table requires \\usepackage[separate-uncertainty,group-digits=none,mode=text]{siunitx} in your LaTeX preamble.
    if not stats:
        return "\\textbf{No data to display or error in processing.}"

    latex_string = "\\begin{figure}[htbp]\n"
    latex_string += "\\centering\n"
    latex_string += "\\caption{Benchmark Performance Summary. Times shown as mean ± standard deviation in milliseconds (ms).}\n"
    sanitized_title_for_label = re.sub(r'[^a-zA-Z0-9_]', '', table_title.lower().replace(' ', '_'))
    latex_string += f"\\label{{fig:benchmark_summary_{sanitized_title_for_label}}}\n"
    latex_string += f"\\textbf{{{table_title.replace('-', ' ').title()}}}\n"
    latex_string += "\\begin{tabular}{@{}lllS[table-format=5.2(2), separate-uncertainty, group-digits=false, mode=math]S[table-format=5.2(2), separate-uncertainty, group-digits=false, mode=math]@{}}\n"
    latex_string += "\\toprule\n"
    latex_string += "Temporal & Pushdown & Stitch & {Cold (ms)} & {Warm (ms)} \\\\ \n" # LaTeX newline
    latex_string += "\\midrule\n"

    for strat_tuple in strategy_tuples:
        temporal, pushdown, stitch = strat_tuple
        if strat_tuple in stats: # Check directly in stats
            cold_stats = stats[strat_tuple].get('cold', {'mean': 0.0, 'std': 0.0, 'count': 0})
            warm_stats = stats[strat_tuple].get('warm', {'mean': 0.0, 'std': 0.0, 'count': 0})

            if cold_stats['count'] > 0:
                cold_val = f"{cold_stats['mean']:.2f}({cold_stats['std']:.2f})"
            else:
                cold_val = "{N/A}"

            if warm_stats['count'] > 0:
                warm_val = f"{warm_stats['mean']:.2f}({warm_stats['std']:.2f})"
            else:
                warm_val = "{N/A}"

            temporal_display = temporal.capitalize()
            pushdown_display = pushdown.replace('none', 'naive').capitalize()
            stitch_display = stitch.replace('none', 'naive').capitalize()

            latex_string += f"{temporal_display} & {pushdown_display} & {stitch_display} & {cold_val} & {warm_val} \\\\ \n" # LaTeX newline

    latex_string += "\\bottomrule\n"
    latex_string += "\\end{tabular}\n"
    latex_string += "\\vspace{1em}\n"
    latex_string += "\\end{figure}\n"
    return latex_string

def generate_summary_table(stats, strategy_tuples, table_title="BENCHMARK RESULTS SUMMARY"):
    """
    Generates a simple text summary table for console output.
    """
    if not stats:
        return "No data to display or error in processing."

    output = []
    output.append("=" * 80)
    output.append(f"{table_title.upper()} (mean ± standard deviation in ms)")
    output.append("=" * 80)

    output.append("-" * 60)
    output.append(f"{'Strategy':<25} {'Cold (ms)':<20} {'Warm (ms)':<20}")
    output.append("-" * 60)

    for strat_tuple in strategy_tuples:
        temporal, pushdown, stitch = strat_tuple
        if strat_tuple in stats: # Check directly in stats
            # Capitalize first letter and rename 'none' to 'naive'
            temporal_display = temporal.capitalize()
            pushdown_display = pushdown.replace('none', 'naive').capitalize()
            stitch_display = stitch.replace('none', 'naive').capitalize()
            strategy_name = f"{temporal_display},{pushdown_display},{stitch_display}"

            cold_stats = stats[strat_tuple].get('cold', {'mean': 0.0, 'std': 0.0, 'count': 0})
            warm_stats = stats[strat_tuple].get('warm', {'mean': 0.0, 'std': 0.0, 'count': 0})

            cold_str = f"{cold_stats['mean']:.2f} ± {cold_stats['std']:.2f}" if cold_stats['count'] > 0 else "N/A"
            warm_str = f"{warm_stats['mean']:.2f} ± {warm_stats['std']:.2f}" if warm_stats['count'] > 0 else "N/A"

            output.append(f"{strategy_name:<25} {cold_str:<20} {warm_str:<20}")

    return "\n ".join(output)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Analyze benchmark results from CSV and generate summary tables with mean ± standard deviation.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python analyze_benchmarks.py                    # Uses default 'bench_results.csv'
  python analyze_benchmarks.py -f bench.csv      # Uses 'bench.csv'
  python analyze_benchmarks.py --file data.csv   # Uses 'data.csv'
        """
    )
    parser.add_argument(
        '-f', '--file',
        default='bench_results.csv',
        help='Path to the CSV file containing benchmark results (default: bench_results.csv)'
    )
    parser.add_argument(
        '-t', '--table-title',
        default='Benchmark Performance Summary',
        help='Title for the generated tables (default: Benchmark Performance Summary)'
    )

    args = parser.parse_args()
    csv_path = args.file
    table_title = args.table_title

    print(f"Analyzing benchmark data from: {csv_path}")

    aggregated_data, strategy_ordering = analyze_benchmarks(csv_path)

    if aggregated_data:
        # Print summary to console
        summary_output = generate_summary_table(aggregated_data, strategy_ordering, table_title)
        print(summary_output)

        # Generate and print LaTeX table
        latex_output = generate_latex_table(aggregated_data, strategy_ordering, table_title)
        print(latex_output)
    else:
        # Errors from analyze_benchmarks are printed to stderr within the function
        print("Could not generate tables due to errors in data processing or file not found.", file=sys.stderr)