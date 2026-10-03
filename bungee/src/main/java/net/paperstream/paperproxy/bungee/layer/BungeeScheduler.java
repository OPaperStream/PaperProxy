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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.api.scheduler.TaskScheduler;

/**
 * Bungee's scheduler on Virtual Threads. Like on BungeeCord every task runs asynchronously; a
 * repeating task never overlaps with itself because the next delay starts after the run.
 */
final class BungeeScheduler implements TaskScheduler {

  private final AtomicInteger ids = new AtomicInteger();
  private final Map<Integer, Task> tasks = new ConcurrentHashMap<>();

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
  }

  private final class Task implements ScheduledTask {

    private final int id;
    private final Plugin owner;
    private final Runnable runnable;
    private final long delayNanos;
    private final long periodNanos;
    private volatile boolean cancelled;
    private volatile Thread thread;

    Task(final int id, final Plugin owner, final Runnable runnable, final long delayNanos,
         final long periodNanos) {
      this.id = id;
      this.owner = owner;
      this.runnable = runnable;
      this.delayNanos = delayNanos;
      this.periodNanos = periodNanos;
    }

    void start() {
      thread = Thread.ofVirtual()
          .name(owner.getDescription().getName() + " Task #" + id)
          .start(this::loop);
    }

    private void loop() {
      try {
        if (delayNanos > 0) {
          TimeUnit.NANOSECONDS.sleep(delayNanos);
        }
        while (!cancelled) {
          try {
            runnable.run();
          } catch (final Throwable t) {
            owner.getLogger().log(Level.SEVERE, "Task " + id + " encountered an exception", t);
          }
          if (periodNanos <= 0) {
            break;
          }
          TimeUnit.NANOSECONDS.sleep(periodNanos);
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        tasks.remove(id);
      }
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
