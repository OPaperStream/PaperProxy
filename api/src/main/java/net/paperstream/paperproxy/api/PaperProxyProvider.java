/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * The PaperProxy API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package net.paperstream.paperproxy.api;

import org.jspecify.annotations.Nullable;

/**
 * Holds the API instance. Set once by PaperProxy at startup; not for plugins.
 */
public final class PaperProxyProvider {

  private static volatile @Nullable PaperProxy instance;

  private PaperProxyProvider() {
    throw new AssertionError();
  }

  static PaperProxy get() {
    final PaperProxy current = instance;
    if (current == null) {
      throw new IllegalStateException("PaperProxy API is not available (not running on "
          + "PaperProxy?)");
    }
    return current;
  }

  static boolean isSet() {
    return instance != null;
  }

  /**
   * Sets the instance. Called by PaperProxy itself.
   *
   * @param api the implementation
   */
  public static void set(final PaperProxy api) {
    if (instance != null) {
      throw new IllegalStateException("PaperProxy API is already set");
    }
    instance = api;
  }
}
