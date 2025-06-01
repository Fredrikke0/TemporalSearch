package com.example.logging;

import java.util.concurrent.atomic.AtomicLong;

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;

/**
 * Manages nested progress bars for index generation processes.
 * Provides tracking for overall progress, individual index progress, and batch progress.
 */
public class ProgressTracker implements AutoCloseable {
    private ProgressBar overallProgress;
    private ProgressBar currentIndexProgress;
    private ProgressBar batchProgress;
    private ProgressBar rocksDBWriteProgress;
    private final AtomicLong overallCount = new AtomicLong(0);
    private final AtomicLong indexCount = new AtomicLong(0);
    private final AtomicLong batchCount = new AtomicLong(0);
    private final AtomicLong rocksDBTermCount = new AtomicLong(0);
    private final boolean isEnabled;

    public ProgressTracker() {
        // Check if we're in a test environment by looking for common test runners
        this.isEnabled = !isTestEnvironment();
    }

    private boolean isTestEnvironment() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName().toLowerCase();
            if (className.contains("junit") ||
                className.contains("test") ||
                className.contains("mock")) {
                return true;
            }
        }
        return false;
    }

    public void startOverall(String task, long total) {
        if (!isEnabled) return;
        overallProgress = new ProgressBarBuilder()
            .setTaskName(task)
            .setInitialMax(total)
            .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
            .setUpdateIntervalMillis(100)
            .showSpeed()
            .build();
    }

    public void stepTo(long n) {
        if (!isEnabled) return;
        if (overallProgress != null) {
            overallProgress.stepTo(n);
            overallCount.set(n);
        }
    }

    public void updateOverallMessage(String message) {
        if (!isEnabled) return;
        if (overallProgress != null) {
            long current = overallProgress.getCurrent();
            long max = overallProgress.getMax();
            overallProgress.close();
            overallProgress = new ProgressBarBuilder()
                .setTaskName(message)
                .setInitialMax(max)
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setUpdateIntervalMillis(100)
                .showSpeed()
                .build();
            overallProgress.stepTo(current);
        }
    }

    public void startIndex(String indexType, long total) {
        if (!isEnabled) return;
        if (currentIndexProgress != null) {
            currentIndexProgress.close();
        }
        indexCount.set(0);

        ProgressBarBuilder pbb = new ProgressBarBuilder()
            .setTaskName(indexType)
            .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
            .setUpdateIntervalMillis(100)
            .showSpeed();

        if (total > 0) {
            pbb.setInitialMax(total);
        }
        // If total <= 0, don't set an initial max, leading to an indeterminate bar showing progress count and speed.

        currentIndexProgress = pbb.build();
    }

    public void startBatch(long total) {
        if (!isEnabled) return;
        if (batchProgress != null) {
            batchProgress.close();
        }
        batchCount.set(0);
        // We don't need batch progress bars for now as they add too much noise
        // batchProgress = new ProgressBarBuilder()
        //     .setTaskName("Processing batch")
        //     .setInitialMax(total)
        //     .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
        //     .setUpdateIntervalMillis(100)
        //     .setConsumer(new DelegatingProgressBarConsumer(logger::info))
        //     .showSpeed()
        //     .build();
    }

    public void updateOverall(long step) {
        if (!isEnabled) return;
        if (overallProgress != null) {
            overallProgress.stepBy(step);
            overallCount.addAndGet(step);
        }
    }

    public void updateIndex(long step) {
        if (!isEnabled) return;
        if (currentIndexProgress != null) {
            currentIndexProgress.stepBy(step);
            indexCount.addAndGet(step);
        }
    }

    public void updateBatch(long step) {
        if (!isEnabled) return;
        if (batchProgress != null) {
            batchProgress.stepBy(step);
            batchCount.addAndGet(step);
        }
    }

    public void completeOverall() {
        if (!isEnabled) return;
        if (overallProgress != null) {
            overallProgress.stepTo(overallProgress.getMax());
            overallProgress.close();
            overallProgress = null;
        }
    }

    public void completeIndex() {
        if (!isEnabled) return;
        if (currentIndexProgress != null) {
            // Only step to max if a max was set (i.e., total > 0 during start)
            if (currentIndexProgress.getMax() > 0) {
                currentIndexProgress.stepTo(currentIndexProgress.getMax());
            }
            currentIndexProgress.close();
            currentIndexProgress = null;
        }
    }

    public void completeBatch() {
        if (!isEnabled) return;
        if (batchProgress != null) {
            batchProgress.stepTo(batchProgress.getMax());
            batchProgress.close();
            batchProgress = null;
        }
    }

    public void startRocksDBWrite(String indexName) {
        if (!isEnabled) return;
        if (rocksDBWriteProgress != null) {
            rocksDBWriteProgress.close();
        }
        rocksDBTermCount.set(0);
        rocksDBWriteProgress = new ProgressBarBuilder()
            .setTaskName("Writing " + indexName + " to RocksDB")
            .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
            .setUpdateIntervalMillis(200)
            .showSpeed()
            .setUnit("terms", 1)
            .build();
    }

    public void updateRocksDBWrite(long termsProcessedInStep) {
        if (!isEnabled) return;
        if (rocksDBWriteProgress != null) {
            rocksDBWriteProgress.stepBy(termsProcessedInStep);
            rocksDBTermCount.addAndGet(termsProcessedInStep);
        }
    }

    public void completeRocksDBWrite() {
        if (!isEnabled) return;
        if (rocksDBWriteProgress != null) {
            rocksDBWriteProgress.close();
            rocksDBWriteProgress = null;
        }
    }

    @Override
    public void close() {
        completeBatch();
        completeIndex();
        completeRocksDBWrite();
        completeOverall();
    }
}