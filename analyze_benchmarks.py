#!/usr/bin/env python3
import argparse
import collections
import csv
import math
import re
import statistics
import sys

# Global mapping for specific strategy tuples to their display names
STRATEGY_DISPLAY_NAMES = {
    ('naive', 'none', 'none'): "B",
    ('nash', 'none', 'none'): "N",
    ('naive', 'none', 'optimized'): "S",
    ('naive', 'optimized', 'none'): "P",
    ('nash', 'none', 'optimized'): "S+N",
    ('naive', 'optimized', 'optimized'): "S+P",
    ('nash', 'optimized', 'optimized'): "S+P+N"
}

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

def generate_latex_table(stats, strategy_tuples, table_title="Benchmark Performance Summary", dataset_slug=None, hop_label=None, dataset_display=None, caption_override=None, label_override=None):
    """
    Generates a LaTeX table from the aggregated benchmark results with mean ± SD format.
    Outputs a fragment suitable for inclusion in a larger document, wrapped in a table environment.
    """
    # Note: This table requires \\usepackage[separate-uncertainty,group-digits=none,mode=text]{siunitx} in your LaTeX preamble.
    if not stats:
        return "\\textbf{No data to display or error in processing.}"

    latex_string = "\\begin{table}[htbp]\n"
    latex_string += "\\centering\n"

    # Build hop-specific caption/label if provided/detectable
    if caption_override is not None:
        caption_text = caption_override
    elif dataset_slug and hop_label and dataset_display:
        # Map hop_label like '1hop' -> '1-hop' for caption
        hop_caption = hop_label.replace('hop', '-hop') if hop_label.endswith('hop') else hop_label
        caption_text = f"{dataset_display} {hop_caption} evaluation. Times shown as mean ± standard deviation in milliseconds (ms). Includes percentage improvement from previous strategy."
    else:
        caption_text = f"{table_title.replace('-', ' ').title()}. Times shown as mean ± standard deviation in milliseconds (ms)."

    if label_override is not None:
        label_text = label_override
    elif dataset_slug and hop_label:
        label_text = f"benchmark_summary_{dataset_slug}_{hop_label}"
    else:
        sanitized_title_for_label = re.sub(r'[^a-zA-Z0-9_]', '', table_title.lower().replace(' ', '_'))
        label_text = f"benchmark_summary_{sanitized_title_for_label}"

    latex_string += f"\\caption{{{caption_text}}}\n"
    latex_string += f"\\label{{fig:{label_text}}}\n"

    latex_string += "\\begin{tabular}{@{}\n"
    latex_string += "c\n"
    latex_string += "S[table-format=5.2(6), separate-uncertainty, group-digits=false, mode=math]\n"
    latex_string += "S[table-format=5.2(6), separate-uncertainty, group-digits=false, mode=math]\n"
    latex_string += "S[table-format=4.1, table-sign-mantissa=true, table-auto-round=false, table-alignment=right]\n"
    latex_string += "@{}}\n"
    latex_string += "\\toprule\n"
    latex_string += "Strategy & {Cold (ms)} & {Warm (ms)} & {\\% Improv.} \\\\ \n"
    latex_string += "\\midrule\n"

    renderable_tuples_with_data = [
        st for st in strategy_tuples
        if st in stats and (stats[st].get('cold',{}).get('count',0) > 0 or stats[st].get('warm',{}).get('count',0) > 0)
    ]

    if not renderable_tuples_with_data:
        latex_string += "\\multicolumn{4}{c}{No data available for selected strategies.} \\\\\\\\ \n"
    else:
        last_renderable_strat_tuple_with_data = renderable_tuples_with_data[-1]

        # Find baseline (B strategy) average time for improvement calculation
        baseline_strat_tuple = ('naive', 'none', 'none')  # B strategy
        baseline_avg_time = None
        if baseline_strat_tuple in stats:
            baseline_cold_stats = stats[baseline_strat_tuple].get('cold', {'mean': 0.0, 'std': 0.0, 'count': 0})
            baseline_warm_stats = stats[baseline_strat_tuple].get('warm', {'mean': 0.0, 'std': 0.0, 'count': 0})
            baseline_cold_mean = baseline_cold_stats['mean'] if baseline_cold_stats['count'] > 0 else None
            baseline_warm_mean = baseline_warm_stats['mean'] if baseline_warm_stats['count'] > 0 else None

            if baseline_cold_mean is not None and baseline_warm_mean is not None:
                baseline_avg_time = (baseline_cold_mean + baseline_warm_mean) / 2.0
            elif baseline_cold_mean is not None:
                baseline_avg_time = baseline_cold_mean
            elif baseline_warm_mean is not None:
                baseline_avg_time = baseline_warm_mean

        for i, strat_tuple in enumerate(renderable_tuples_with_data):
            temporal, pushdown, stitch = strat_tuple
            cold_stats = stats[strat_tuple].get('cold', {'mean': 0.0, 'std': 0.0, 'count': 0})
            warm_stats = stats[strat_tuple].get('warm', {'mean': 0.0, 'std': 0.0, 'count': 0})

            cold_val = f"{cold_stats['mean']:.2f}({cold_stats['std']:.2f})" if cold_stats['count'] > 0 else "{N/A}"
            warm_val = f"{warm_stats['mean']:.2f}({warm_stats['std']:.2f})" if warm_stats['count'] > 0 else "{N/A}"

            strategy_display_name = STRATEGY_DISPLAY_NAMES.get(
                strat_tuple,
                f"{temporal.capitalize()}, {pushdown.replace('none', 'naive').capitalize()}, {stitch.replace('none', 'naive').capitalize()}"
            )

            # Calculate current row average time and improvement from baseline
            current_cold_mean = cold_stats['mean'] if cold_stats['count'] > 0 else None
            current_warm_mean = warm_stats['mean'] if warm_stats['count'] > 0 else None
            current_row_avg_time = None
            improvement_val_for_siunitx = "{--}" # Default for LaTeX S-column (text)

            if current_cold_mean is not None and current_warm_mean is not None:
                current_row_avg_time = (current_cold_mean + current_warm_mean) / 2.0
            elif current_cold_mean is not None:
                current_row_avg_time = current_cold_mean
            elif current_warm_mean is not None:
                current_row_avg_time = current_warm_mean

            # Calculate improvement from baseline (B strategy) instead of previous row
            if strat_tuple == baseline_strat_tuple:
                # For baseline row, show no improvement (it's the reference)
                improvement_val_for_siunitx = "{--}"
            elif baseline_avg_time is not None and current_row_avg_time is not None and abs(baseline_avg_time) > 1e-9:
                percentage_improvement = ((baseline_avg_time - current_row_avg_time) / baseline_avg_time) * 100
                if percentage_improvement > 0:
                    improvement_val_for_siunitx = f"\color{{ForestGreen}}{{{percentage_improvement:.1f}\\%}}"
                else:
                    improvement_val_for_siunitx = f"\color{{red}}{{{percentage_improvement:.1f}\\%}}"

            is_last_row_to_highlight = (strat_tuple == last_renderable_strat_tuple_with_data)

            if is_last_row_to_highlight:
                latex_string += f"{strategy_display_name} & {cold_val} & {warm_val} & {improvement_val_for_siunitx} \\\\ \n"
            else:
                latex_string += f"{strategy_display_name} & {cold_val} & {warm_val} & {improvement_val_for_siunitx} \\\\ \n"

            if strat_tuple != last_renderable_strat_tuple_with_data:
                latex_string += "\\midrule\n"

    latex_string += "\\bottomrule\n"
    latex_string += "\\end{tabular}\n"
    latex_string += "\\vspace{1em}\n"
    latex_string += "\\end{table}\n"
    return latex_string

def generate_summary_table(stats, strategy_tuples, table_title="BENCHMARK RESULTS SUMMARY"):
    """
    Generates a simple text summary table for console output.
    """
    if not stats:
        return "No data to display or error in processing."

    output = []
    output.append("=" * 80)
    output.append(f"{table_title.upper()} (mean ± standard deviation)")
    output.append("=" * 80)

    output.append("-" * 80) # Adjusted width
    # Updated header for a single strategy column, adjusted width
    header_line = f"{'Strategy':<35} {'Cold (ms)':<20} {'Warm (ms)':<20} {'% Improv.':<10}"
    output.append(header_line)
    output.append("-" * len(header_line)) # Adjusted width

    # Find baseline (B strategy) average time for improvement calculation
    baseline_strat_tuple = ('naive', 'none', 'none')  # B strategy
    baseline_avg_time = None
    if baseline_strat_tuple in stats:
        baseline_cold_stats = stats[baseline_strat_tuple].get('cold', {'mean': 0.0, 'std': 0.0, 'count': 0})
        baseline_warm_stats = stats[baseline_strat_tuple].get('warm', {'mean': 0.0, 'std': 0.0, 'count': 0})
        baseline_cold_mean = baseline_cold_stats['mean'] if baseline_cold_stats['count'] > 0 else None
        baseline_warm_mean = baseline_warm_stats['mean'] if baseline_warm_stats['count'] > 0 else None

        if baseline_cold_mean is not None and baseline_warm_mean is not None:
            baseline_avg_time = (baseline_cold_mean + baseline_warm_mean) / 2.0
        elif baseline_cold_mean is not None:
            baseline_avg_time = baseline_cold_mean
        elif baseline_warm_mean is not None:
            baseline_avg_time = baseline_warm_mean

    for strat_tuple in strategy_tuples:
        temporal, pushdown, stitch = strat_tuple
        if strat_tuple in stats: # Check directly in stats
            # Get display name from map or generate a fallback
            strategy_name = STRATEGY_DISPLAY_NAMES.get(
                strat_tuple,
                f"{temporal.capitalize()}/{pushdown.replace('none', 'naive').capitalize()}/{stitch.replace('none', 'naive').capitalize()}"
            )

            cold_s = stats[strat_tuple].get('cold', {'mean': 0.0, 'std': 0.0, 'count': 0})
            warm_s = stats[strat_tuple].get('warm', {'mean': 0.0, 'std': 0.0, 'count': 0})

            cold_str = f"{cold_s['mean']:.2f} ± {cold_s['std']:.2f}" if cold_s['count'] > 0 else "N/A"
            warm_str = f"{warm_s['mean']:.2f} ± {warm_s['std']:.2f}" if warm_s['count'] > 0 else "N/A"

            # Calculate current row average time and improvement from baseline
            current_cold_mean = cold_s['mean'] if cold_s['count'] > 0 else None
            current_warm_mean = warm_s['mean'] if warm_s['count'] > 0 else None
            current_row_avg_time = None
            improvement_display_str = "N/A"

            if current_cold_mean is not None and current_warm_mean is not None:
                current_row_avg_time = (current_cold_mean + current_warm_mean) / 2.0
            elif current_cold_mean is not None:
                current_row_avg_time = current_cold_mean
            elif current_warm_mean is not None:
                current_row_avg_time = current_warm_mean

            # Calculate improvement from baseline (B strategy) instead of previous row
            if strat_tuple == baseline_strat_tuple:
                # For baseline row, show no improvement (it's the reference)
                improvement_display_str = "--"
            elif baseline_avg_time is not None and current_row_avg_time is not None and abs(baseline_avg_time) > 1e-9:
                percentage_improvement = ((baseline_avg_time - current_row_avg_time) / baseline_avg_time) * 100
                improvement_display_str = f"{percentage_improvement:.1f}%"

            # Use the new strategy_name and adjusted width
            output.append(f"{strategy_name:<35} {cold_str:<20} {warm_str:<20} {improvement_display_str:<10}")

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
  python analyze_benchmarks.py --file results_1hop.csv --table-title "1-hop Benchmark Performance" # Example for specific formatting
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
        help='Title for the generated tables. Include "1-hop", "2-hops", or "3-hops" to get specific row formats.'
    )

    args = parser.parse_args()
    csv_path = args.file
    table_title = args.table_title

    print(f"Analyzing benchmark data from: {csv_path}")

    # Call analyze_benchmarks once to get all data and the full list of defined strategy permutations
    aggregated_data, all_defined_strategy_permutations = analyze_benchmarks(csv_path)

    if aggregated_data:
        hop_type_in_title = None
        filename_lower = csv_path.lower()

        # Hop type detection: ONLY from filename
        if "1hop" in filename_lower:
            hop_type_in_title = "1-hop"
        elif "2hop" in filename_lower:
            hop_type_in_title = "2-hops"
        elif "3hop" in filename_lower:
            hop_type_in_title = "3-hops"

        strategies_to_render = []

        if not hop_type_in_title:
            print(f"Warning: Hop type ('1hop', '2hop', '3hop') not detected in CSV filename ('{csv_path}').", file=sys.stderr)
            print("Displaying table with all strategy permutations defined by the analyzer.", file=sys.stderr)
            print("To use specific row formatting, ensure hop type is in the CSV filename (e.g., 'my_1hop_data.csv').", file=sys.stderr)
            strategies_to_render = all_defined_strategy_permutations
        else:
            # Define strategy tuples: (temporal, pushdown, stitch)
            baseline = ('naive', 'none', 'none')          # B
            nash_only = ('nash', 'none', 'none')          # N
            stitch_optimized = ('naive', 'none', 'optimized') # S
            pushdown_only = ('naive', 'optimized', 'none') # P
            stitch_nash_optimized = ('nash', 'none', 'optimized') # S+N
            stitch_pushdown_optimized = ('naive', 'optimized', 'optimized') # S+P
            all_optimized_spn = ('nash', 'optimized', 'optimized') # S+P+N (was all_optimized)

            if hop_type_in_title == "1-hop":
                strategies_to_render = [
                    baseline,
                    nash_only,
                    stitch_optimized,
                    stitch_nash_optimized  # S+N
                ]
                print(f"Detected {hop_type_in_title}. Displaying strategies: B, N, S, S+N.")
            elif hop_type_in_title in ["2-hops", "3-hops"]:
                strategies_to_render = [
                    baseline,                 # B
                    nash_only,               # N
                    stitch_optimized,         # S
                    pushdown_only,           # P
                    stitch_pushdown_optimized,# S+P
                    all_optimized_spn         # S+P+N
                ]
                print(f"Detected {hop_type_in_title}. Displaying strategies: B, N, S, P, S+P, S+P+N.")
            else: # Should not be reached if hop_type_in_title is one of the above or None
                print(f"Internal Warning: Unhandled hop_type_in_title '{hop_type_in_title}'. Falling back to all defined permutations.", file=sys.stderr)
                strategies_to_render = all_defined_strategy_permutations

        if not strategies_to_render:
             # This case would typically mean all_defined_strategy_permutations was empty and no hop type was matched.
             print("Error: No strategies selected or defined for display. Table will be empty or may not generate.", file=sys.stderr)
        else:
            # Print summary to console
            summary_output = generate_summary_table(aggregated_data, strategies_to_render, table_title)
            print(summary_output)
            print("=" * 80)
            # Prepare hop label for LaTeX caption/label
            hop_label_for_label = None
            if hop_type_in_title == "1-hop":
                hop_label_for_label = "1hop"
            elif hop_type_in_title == "2-hops":
                hop_label_for_label = "2hop"
            elif hop_type_in_title == "3-hops":
                hop_label_for_label = "3hop"

            # Generate and print LaTeX table with hop-specific caption/label when detectable
            latex_output = generate_latex_table(
                aggregated_data,
                strategies_to_render,
                table_title,
                dataset_slug="X",
                hop_label=hop_label_for_label,
                dataset_display="X"
            )
            print(latex_output)
    else:
        # Errors from analyze_benchmarks (e.g., file not found) are printed to stderr within the function
        print("Could not generate tables due to errors in data processing or file access.", file=sys.stderr)
