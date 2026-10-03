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

package net.paperstream.paperproxy.messages;

import java.text.MessageFormat;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.translation.Translator;
import net.kyori.adventure.util.TriState;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Asks {@code primary} first and {@code fallback} only if the primary has no answer.
 */
final class LayeredTranslator implements Translator {

  private static final Key NAME = Key.key("paperproxy", "translations");

  private final Translator primary;
  private final Translator fallback;

  LayeredTranslator(final Translator primary, final Translator fallback) {
    this.primary = primary;
    this.fallback = fallback;
  }

  @Override
  public Key name() {
    return NAME;
  }

  @Override
  public TriState hasAnyTranslations() {
    return TriState.TRUE;
  }

  @Override
  public @Nullable MessageFormat translate(final String key, final Locale locale) {
    final MessageFormat format = primary.translate(key, locale);
    return format != null ? format : fallback.translate(key, locale);
  }

  @Override
  public @Nullable Component translate(final TranslatableComponent component,
                                       final Locale locale) {
    final Component result = primary.translate(component, locale);
    return result != null ? result : fallback.translate(component, locale);
  }
}
