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
package io.gravitee.reporter.otellogs.mapper;

import com.google.gson.Gson;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.opentelemetry.api.logs.Severity;
import java.util.Optional;
import java.util.regex.Pattern;

public final class OtelLabels {

  private static final Gson GSON = new Gson();

  /** Typed result of parsing a {@code sentry-trace} header. */
  public record SentryTrace(String traceId, String spanId) {}

  private static final Pattern HEX_32 = Pattern.compile("[0-9a-fA-F]{32}");
  private static final Pattern HEX_16 = Pattern.compile("[0-9a-fA-F]{16}");
  private static final Pattern NUMERIC_SEGMENT = Pattern.compile("/\\d+");
  private static final Pattern UUID_SEGMENT = Pattern.compile(
    "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
  );

  private OtelLabels() {}

  /**
   * Normalizes a value to a 32-char lowercase hex OTel trace ID.
   * Accepts UUIDs (strips dashes) or already-32-hex strings.
   * Returns null for anything else.
   */
  public static String normalizeTraceId(String value) {
    if (value == null || value.isBlank()) return null;
    String stripped = value.strip().replace("-", "");
    if (stripped.length() == 32 && HEX_32.matcher(stripped).matches()) {
      return stripped.toLowerCase();
    }
    return null;
  }

  /**
   * Parses a sentry-trace header of the form "{traceId}-{spanId}-{sampled}".
   * Returns Optional.empty() when the header is absent, malformed (fewer than 2 segments),
   * or when traceId/spanId segments fail hex-length validation.
   */
  public static Optional<SentryTrace> parseSentryTrace(String header) {
    if (header == null || header.isBlank()) return Optional.empty();
    String[] parts = header.strip().split("-", 3);
    if (parts.length < 2) return Optional.empty();
    String traceId = parts[0];
    String spanId = parts[1];
    if (
      !HEX_32.matcher(traceId).matches() || !HEX_16.matcher(spanId).matches()
    ) {
      return Optional.empty();
    }
    return Optional.of(new SentryTrace(traceId, spanId));
  }

  /**
   * Maps an HTTP status code to an OTel Severity level.
   */
  public static Severity severityFromStatus(int status) {
    if (status >= 500) return Severity.ERROR;
    if (status >= 400) return Severity.WARN;
    return Severity.INFO;
  }

  /**
   * Serialises an {@link HttpHeaders} bundle to a JSON string of name → list-of-values.
   * Returns null when the bundle is null or empty so callers can skip setting an
   * empty-string attribute on the log record.
   */
  public static String headersAsJson(HttpHeaders headers) {
    if (headers == null || headers.isEmpty()) return null;
    return GSON.toJson(headers.toListValuesMap());
  }

  /**
   * Replaces numeric path segments (/42) and UUID path segments with {id}.
   */
  public static String sanitizePath(String path) {
    if (path == null) return null;
    return NUMERIC_SEGMENT.matcher(
      UUID_SEGMENT.matcher(path).replaceAll("/{id}")
    ).replaceAll("/{id}");
  }
}
