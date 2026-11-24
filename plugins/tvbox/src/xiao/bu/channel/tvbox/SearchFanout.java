package xiao.bu.channel.tvbox;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A fixed number of workers consume sites; cancellation never delivers late results. */
final class SearchFanout<T, R> {
    interface Query<T, R> { R run(T site) throws Exception; }
    interface Observer<T, R> {
        void result(T site, R result, Throwable error);
        void finished(boolean cancelled);
    }
    private final List<T> sites;
    private final Query<T, R> query;
    private final Observer<T, R> observer;
    private final List<Future<?>> jobs = new ArrayList<>();
    private final AtomicInteger cursor = new AtomicInteger(), running;
    private final AtomicBoolean cancelled = new AtomicBoolean(), notified = new AtomicBoolean();

    SearchFanout(ExecutorService pool, int concurrency, List<T> sites, Query<T, R> query, Observer<T, R> observer) {
        this.sites = new ArrayList<>(sites); this.query = query; this.observer = observer;
        int count = Math.min(concurrency, sites.size()); running = new AtomicInteger(count);
        if (count == 0) { finish(); return; }
        try { for (int i = 0; i < count; i++) jobs.add(pool.submit(this::worker)); }
        catch (RejectedExecutionException busy) { cancel(); throw busy; }
    }
    private void worker() {
        try {
            for (int next; !cancelled.get() && (next = cursor.getAndIncrement()) < sites.size();) {
                T site = sites.get(next); R result = null; Throwable error = null;
                try { result = query.run(site); }
                catch (Exception | LinkageError failure) { error = failure; }
                if (!cancelled.get()) observer.result(site, result, error);
            }
        } finally { if (running.decrementAndGet() == 0) finish(); }
    }
    void cancel() {
        cancelled.set(true);
        for (Future<?> job : jobs) job.cancel(true);
        finish();
    }
    private void finish() {
        if (notified.compareAndSet(false, true)) observer.finished(cancelled.get());
    }
}
