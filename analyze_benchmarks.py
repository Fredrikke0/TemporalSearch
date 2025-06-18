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
    """
    # Structure: results[hop_type][(temporal, pushdown, stitch)][cache_mode] = [list of individual run times]
    results = collections.defaultdict(
        lambda: collections.defaultdict(
            lambda: collections.defaultdict(list)
        )
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

                query_text = row['query_text']
                hop_type = get_hop_type(query_text)

                temporal = row['temporal_strategy']
                pushdown = row['pushdown_strategy']
                stitch = row['stitch_strategy']
                cache = row['cache_mode']

                # Parse individual run times from semicolon-separated string
                individual_times_str = row.get('individual_run_times_ms', '')
                if individual_times_str:
                    try:
                        individual_times = [float(t.strip()) for t in individual_times_str.split(';') if t.strip()]
                        results[hop_type][(temporal, pushdown, stitch)][cache].extend(individual_times)
                    except ValueError as e:
                        print(f"Warning: Could not parse individual_run_times_ms '{individual_times_str}' for query_id {row['original_query_id']}: {e}. Skipping.", file=sys.stderr)
                        continue
                else:
                    # Fallback to avg_time_ms if individual times not available
                    try:
                        avg_time = float(row['avg_time_ms'])
                        results[hop_type][(temporal, pushdown, stitch)][cache].append(avg_time)
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
        lambda: collections.defaultdict(
            lambda: collections.defaultdict(lambda: {'mean': 0.0, 'std': 0.0, 'count': 0})
        )
    )

    for hop_type, strats_data in results.items():
        for strat_tuple, cache_data in strats_data.items():
            for cache_mode, times in cache_data.items():
                if times:
                    mean_time = statistics.mean(times)
                    std_time = statistics.stdev(times) if len(times) > 1 else 0.0
                    stats[hop_type][strat_tuple][cache_mode] = {
                        'mean': mean_time,
                        'std': std_time,
                        'count': len(times)
                    }

    return stats, strategy_tuples

def generate_latex_table(stats, strategy_tuples):
    """
    Generates a LaTeX table from the aggregated benchmark results with mean ± SD format.
    Outputs a fragment suitable for inclusion in a larger document, wrapped in a figure environment.
    """
    if not stats:
        return "\\textbf{No data to display or error in processing.}"

    latex_string = "\\begin{figure}[htbp]\n"
    latex_string += "\\centering\n"
    latex_string += "\\caption{Benchmark Performance Summary. Times shown as mean ± standard deviation in milliseconds (ms).}\n"
    # If you need to reference this figure, you can add a label:
    # latex_string += "\\label{fig:benchmark_summary}\\n"

    # Sort hop types: 1-hop, 2-hops, 3-hops, then others numerically if any
    sorted_hop_types = sorted(
        stats.keys(),
        key=lambda x: (
            int(x.split('-')[0]) if x.endswith('-hops') else float('inf'),
            x
        )
    )

    # Ensure 1-hop, 2-hops, 3-hops are processed first if they exist
    desired_order = ["1-hop", "2-hops", "3-hops"]
    ordered_hop_types = [ht for ht in desired_order if ht in sorted_hop_types]
    ordered_hop_types += [ht for ht in sorted_hop_types if ht not in desired_order]

    for i, hop_type in enumerate(ordered_hop_types):
        if not stats[hop_type]:
            continue

        if i > 0: # Add some vertical space between tables within the figure
             latex_string += "\\vspace{1em}\n\n"

        latex_string += f"\\textbf{{{hop_type.replace('-', ' ').title()}}}\n" # Title for each table
        latex_string += "\\begin{tabular}{@{}lllcc@{}}\n"
        latex_string += "\\toprule\n"
        latex_string += "Temporal & Pushdown & Stitch & Cold (ms) & Warm (ms) \\\\ \\midrule\n"

        for strat_tuple in strategy_tuples:
            temporal, pushdown, stitch = strat_tuple
            if strat_tuple in stats[hop_type]:
                cold_stats = stats[hop_type][strat_tuple].get('cold', {'mean': 0.0, 'std': 0.0, 'count': 0})
                warm_stats = stats[hop_type][strat_tuple].get('warm', {'mean': 0.0, 'std': 0.0, 'count': 0})

                # Format as "mean ± SD" if we have data, otherwise "N/A"
                if cold_stats['count'] > 0:
                    cold_str = f"{cold_stats['mean']:.2f} ± {cold_stats['std']:.2f}"
                else:
                    cold_str = "N/A"

                if warm_stats['count'] > 0:
                    warm_str = f"{warm_stats['mean']:.2f} ± {warm_stats['std']:.2f}"
                else:
                    warm_str = "N/A"

                # Capitalize first letter and rename 'none' to 'naive'
                temporal_display = temporal.capitalize()
                pushdown_display = pushdown.replace('none', 'naive').capitalize()
                stitch_display = stitch.replace('none', 'naive').capitalize()

                latex_string += f"{temporal_display} & {pushdown_display} & {stitch_display} & {cold_str} & {warm_str} \\\\ \n"

        latex_string += "\\bottomrule\n"
        latex_string += "\\end{tabular}\n"

    latex_string += "\\end{figure}\n"
    return latex_string

def generate_summary_table(stats, strategy_tuples):
    """
    Generates a simple text summary table for console output.
    """
    if not stats:
        return "No data to display or error in processing."

    output = []
    output.append("=" * 80)
    output.append("BENCHMARK RESULTS SUMMARY (mean ± standard deviation in ms)")
    output.append("=" * 80)

    # Sort hop types
    sorted_hop_types = sorted(
        stats.keys(),
        key=lambda x: (
            int(x.split('-')[0]) if x.endswith('-hops') else float('inf'),
            x
        )
    )

    desired_order = ["1-hop", "2-hops", "3-hops"]
    ordered_hop_types = [ht for ht in desired_order if ht in sorted_hop_types]
    ordered_hop_types += [ht for ht in sorted_hop_types if ht not in desired_order]

    for hop_type in ordered_hop_types:
        if not stats[hop_type]:
            continue

        output.append(f"\n{hop_type.replace('-', ' ').title()}:")
        output.append("-" * 60)
        output.append(f"{'Strategy':<25} {'Cold (ms)':<20} {'Warm (ms)':<20}")
        output.append("-" * 60)

        for strat_tuple in strategy_tuples:
            temporal, pushdown, stitch = strat_tuple
            if strat_tuple in stats[hop_type]:
                # Capitalize first letter and rename 'none' to 'naive'
                temporal_display = temporal.capitalize()
                pushdown_display = pushdown.replace('none', 'naive').capitalize()
                stitch_display = stitch.replace('none', 'naive').capitalize()
                strategy_name = f"{temporal_display},{pushdown_display},{stitch_display}"

                cold_stats = stats[hop_type][strat_tuple].get('cold', {'mean': 0.0, 'std': 0.0, 'count': 0})
                warm_stats = stats[hop_type][strat_tuple].get('warm', {'mean': 0.0, 'std': 0.0, 'count': 0})

                cold_str = f"{cold_stats['mean']:.2f} ± {cold_stats['std']:.2f}" if cold_stats['count'] > 0 else "N/A"
                warm_str = f"{warm_stats['mean']:.2f} ± {warm_stats['std']:.2f}" if warm_stats['count'] > 0 else "N/A"

                output.append(f"{strategy_name:<25} {cold_str:<20} {warm_str:<20}")

    return "\n".join(output)

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

    args = parser.parse_args()
    csv_path = args.file

    print(f"Analyzing benchmark data from: {csv_path}")

    aggregated_data, strategy_ordering = analyze_benchmarks(csv_path)

    if aggregated_data:
        # Print summary to console
        summary_output = generate_summary_table(aggregated_data, strategy_ordering)
        print(summary_output)

        print("\n" + "=" * 80)
        print("LATEX TABLE OUTPUT:")
        print("=" * 80)

        # Generate and print LaTeX table
        latex_output = generate_latex_table(aggregated_data, strategy_ordering)
        print(latex_output)
    else:
        # Errors from analyze_benchmarks are printed to stderr within the function
        print("Could not generate tables due to errors in data processing or file not found.", file=sys.stderr)