/*
 * Copyright © 2026 Fluent Health (https://fluentinhealth.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.reporter.otellogs.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;

/**
 * The {@code logs.masking} subtree — reporter-level redaction of sensitive data.
 *
 * <p>Replaces the Gravitee EE {@code data-logging-masking} policy, which infra injects per-flow and
 * which stops working once the enterprise licence is dropped. Rules themselves live in code (see the
 * {@code sanitize} package); this class only carries the on/off switch and additive header names, so
 * a configuration edit can never weaken PHI masking without code review.
 *
 * <p>Default-off, matching every other data-forwarding flag in {@link LogsConfiguration}. When
 * {@code reportHeaders} or {@code reportPayloads} is on while this is off,
 * {@code OtelLogsReporter} warns at startup.
 */
public class MaskingConfiguration {

  @Value("${reporters.otellogs.logs.masking.enabled:false}")
  private boolean enabled;

  // Comma-separated, NOT a YAML list: Gravitee's property binding does not surface YAML lists to
  // @Value — the same limitation documented for logs.headers in LogsConfiguration. Entries may end
  // in '*' for prefix matching (e.g. "x-tenant-*").
  @Value("${reporters.otellogs.logs.masking.extraHeaders:}")
  private String extraHeaders = "";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    this.enabled = v;
  }

  public String getExtraHeadersRaw() {
    return extraHeaders;
  }

  public void setExtraHeadersRaw(String v) {
    this.extraHeaders = v == null ? "" : v;
  }

  /** Parsed view of {@link #getExtraHeadersRaw()} — stripped, blanks removed. Never null. */
  public List<String> getExtraHeaders() {
    if (extraHeaders == null || extraHeaders.isBlank()) return List.of();
    return Arrays.stream(extraHeaders.split(","))
      .map(String::strip)
      .filter(s -> !s.isEmpty())
      .toList();
  }
}
