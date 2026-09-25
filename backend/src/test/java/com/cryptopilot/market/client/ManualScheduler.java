package com.cryptopilot.market.client;

import com.cryptopilot.support.MutableTestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A scheduler on virtual time, for tests of code that schedules its own next step: nothing runs until the test calls
 * {@link #advance}, and then exactly the tasks that fall due run, in order, on the test's thread. A renewal set for
 * "200 ms from now" therefore happens once, when the test says so, however slow the machine is.
 *
 * <p>Tasks may be scheduled from any thread (a WebSocket callback schedules the retry of a failed handshake);
 * scheduling and advancing are synchronized, and a task runs outside the lock so that it may schedule the next one.
 */
final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {

    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private final MutableTestClock clock;
    private final Instant start;
    private long now;
    private long sequence;
    private boolean shutdown;

    /** A scheduler with no clock of its own to move. */
    ManualScheduler() {
        this(null);
    }

    /**
     * A scheduler that moves {@code clock} with its virtual time, so that code reading the clock — an idle watchdog
     * comparing now with the last message — sees the same time as the tasks it schedules.
     */
    ManualScheduler(MutableTestClock clock) {
        this.clock = clock;
        this.start = clock == null ? Instant.EPOCH : clock.instant();
    }

    /** Runs every task due within {@code amount} of virtual time, in due order, and moves the clock by it. */
    void advance(Duration amount) {
        long target;
        synchronized (this) {
            target = now + amount.toNanos();
        }
        while (true) {
            Task next;
            synchronized (this) {
                next = queue.peek();
                if (next == null || next.due > target) {
                    moveTo(target);
                    return;
                }
                queue.poll();
                moveTo(next.due);
            }
            if (!next.cancelled) {
                next.command.run();
                synchronized (this) {
                    if (next.period > 0 && !next.cancelled && !shutdown) {
                        next.due = now + next.period;
                        next.order = sequence++;
                        queue.add(next);
                    }
                }
            }
        }
    }

    private void moveTo(long nanos) {
        now = nanos;
        if (clock != null) {
            clock.set(start.plusNanos(nanos));
        }
    }

    /** Whether a task that has not been cancelled falls due within {@code amount} of virtual time. */
    synchronized boolean hasTaskDueWithin(Duration amount) {
        long limit = now + amount.toNanos();
        return queue.stream().anyMatch(task -> !task.cancelled && task.due <= limit);
    }

    @Override
    public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return add(command, unit.toNanos(delay), 0);
    }

    @Override
    public synchronized ScheduledFuture<?> scheduleAtFixedRate(
            Runnable command, long initialDelay, long period, TimeUnit unit) {
        return add(command, unit.toNanos(initialDelay), unit.toNanos(period));
    }

    @Override
    public synchronized ScheduledFuture<?> scheduleWithFixedDelay(
            Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return add(command, unit.toNanos(initialDelay), unit.toNanos(delay));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("only Runnable tasks are scheduled by the code under test");
    }

    @Override
    public void execute(Runnable command) {
        command.run();
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
        queue.clear();
    }

    @Override
    public synchronized List<Runnable> shutdownNow() {
        List<Runnable> left = new ArrayList<>();
        queue.forEach(task -> left.add(task.command));
        shutdown();
        return left;
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return isTerminated();
    }

    private Task add(Runnable command, long delayNanos, long periodNanos) {
        if (shutdown) {
            throw new IllegalStateException("the scheduler is shut down");
        }
        Task task = new Task(command, now + Math.max(0, delayNanos), periodNanos, sequence++);
        queue.add(task);
        return task;
    }

    /** One scheduled task; a periodic one is put back after each run. */
    private final class Task implements ScheduledFuture<Void> {

        private final Runnable command;
        private final long period;
        private long due;
        private long order;
        private volatile boolean cancelled;

        Task(Runnable command, long due, long period, long order) {
            this.command = command;
            this.due = due;
            this.period = period;
            this.order = order;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            synchronized (ManualScheduler.this) {
                return unit.convert(due - now, TimeUnit.NANOSECONDS);
            }
        }

        @Override
        public int compareTo(Delayed other) {
            Task that = (Task) other;
            int byDue = Long.compare(due, that.due);
            return byDue != 0 ? byDue : Long.compare(order, that.order);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Void get() {
            throw new UnsupportedOperationException("the code under test never waits on its tasks");
        }

        @Override
        public Void get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("the code under test never waits on its tasks");
        }
    }
}
