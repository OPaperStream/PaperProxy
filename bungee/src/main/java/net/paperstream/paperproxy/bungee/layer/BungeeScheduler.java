/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.paperstream.paperproxy.bungee.layer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.api.scheduler.TaskScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Bungee's scheduler. Like on BungeeCord every task runs asynchronously on a normal (platform)
 * thread; a repeating task never overlaps with itself because the next delay starts after the
 * run.
 *
 * <p>Virtual threads are not used on purpose: plugins run blocking JDBC and native code (SQLite,
 * for example) in their tasks, which pins virtual threads to their few carrier threads and can
 * stall every other task under load.
 */
final class BungeeScheduler implements TaskScheduler {

  private final AtomicInteger ids = new AtomicInteger();
  private final AtomicInteger threads = new AtomicInteger();
  private final Map<Integer, Task> tasks = new ConcurrentHashMap<>();
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
      runnable -> daemon(runnable, "PaperProxy Bungee Scheduler"));
  private final ExecutorService workers = Executors.newCachedThreadPool(
      runnable -> daemon(runnable, "PaperProxy Bungee Task #" + threads.incrementAndGet()));

  private static Thread daemon(final Runnable runnable, final String name) {
    final Thread thread = new Thread(runnable, name);
    thread.setDaemon(true);
    return thread;
  }

  @Override
  public void cancel(final int id) {
    final Task task = tasks.get(id);
    if (task != null) {
      task.cancel();
    }
  }

  @Override
  public void cancel(final ScheduledTask task) {
    task.cancel();
  }

  @Override
  public int cancel(final Plugin plugin) {
    int cancelled = 0;
    for (final Task task : tasks.values()) {
      if (task.owner == plugin) {
        task.cancel();
        cancelled++;
      }
    }
    return cancelled;
  }

  @Override
  public ScheduledTask runAsync(final Plugin owner, final Runnable task) {
    return schedule(owner, task, 0, TimeUnit.MILLISECONDS);
  }

  @Override
  public ScheduledTask schedule(final Plugin owner, final Runnable task, final long delay,
                                final TimeUnit unit) {
    return schedule(owner, task, delay, 0, unit);
  }

  @Override
  public ScheduledTask schedule(final Plugin owner, final Runnable task, final long delay,
                                final long period, final TimeUnit unit) {
    if (owner == null || task == null || unit == null) {
      throw new NullPointerException("owner, task and unit must not be null");
    }
    final Task scheduled = new Task(ids.incrementAndGet(), owner, task,
        unit.toNanos(Math.max(0, delay)), unit.toNanos(Math.max(0, period)));
    tasks.put(scheduled.id, scheduled);
    scheduled.start();
    return scheduled;
  }

  @Override
  public Unsafe unsafe() {
    return Plugin::getExecutorService;
  }

  /**
   * Cancels every task, used on shutdown.
   */
  void shutdown() {
    tasks.values().forEach(Task::cancel);
    timer.shutdownNow();
    workers.shutdown();
  }

  private final class Task implements ScheduledTask {

    private final int id;
    private final Plugin owner;
    private final Runnable runnable;
    private final long delayNanos;
    private final long periodNanos;
    private volatile boolean cancelled;
    private volatile @Nullable Thread thread;
    private volatile @Nullable Future<?> pending;

    Task(final int id, final Plugin owner, final Runnable runnable, final long delayNanos,
         final long periodNanos) {
      this.id = id;
      this.owner = owner;
      this.runnable = runnable;
      this.delayNanos = delayNanos;
      this.periodNanos = periodNanos;
    }

    void start() {
      if (delayNanos > 0) {
        pending = timer.schedule(this::submit, delayNanos, TimeUnit.NANOSECONDS);
      } else {
        submit();
      }
    }

    private void submit() {
      if (cancelled) {
        return;
      }
      try {
        pending = workers.submit(this::run);
      } catch (final RejectedExecutionException e) {
        tasks.remove(id);
      }
    }

    private void run() {
      thread = Thread.currentThread();
      try {
        if (!cancelled) {
          runnable.run();
        }
      } catch (final Throwable t) {
        owner.getLogger().log(Level.SEVERE, "Task " + id + " encountered an exception", t);
      } finally {
        thread = null;
        // Clear an interrupt from cancel() so it does not leak into the next task on this thread.
        Thread.interrupted();
      }
      if (periodNanos > 0 && !cancelled) {
        try {
          pending = timer.schedule(this::submit, periodNanos, TimeUnit.NANOSECONDS);
          return;
        } catch (final RejectedExecutionException e) {
          // Shutting down.
        }
      }
      tasks.remove(id);
    }

    @Override
    public int getId() {
      return id;
    }

    @Override
    public Plugin getOwner() {
      return owner;
    }

    @Override
    public Runnable getTask() {
      return runnable;
    }

    @Override
    public void cancel() {
      cancelled = true;
      tasks.remove(id);
      final Future<?> scheduled = pending;
      if (scheduled != null) {
        scheduled.cancel(false);
      }
      final Thread current = thread;
      // A task cancelling itself must be allowed to finish its current run.
      if (current != null && current != Thread.currentThread()) {
        current.interrupt();
      }
    }
  }

  /**
   * Visible for the reload support: number of tasks still alive.
   *
   * @return the number of live tasks
   */
  int size() {
    return tasks.size();
  }
}
