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
package io.gravitee.reporter.otellogs.writer;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports OTel log records directly to Google Cloud Logging via the REST API v2
 * ({@code entries:write}), without requiring an OTel Collector intermediary.
 *
 * <p>Authentication uses Application Default Credentials by default; an optional
 * service-account key file path may be supplied via {@code credentialsFile}.
 *
 * <p>The OTel SDK's {@code BatchLogRecordProcessor} drives batching and retry —
 * this exporter handles only the HTTP write for each batch.
 */
public class GclLogRecordExporter implements LogRecordExporter {

  private static final Logger log = LoggerFactory.getLogger(
    GclLogRecordExporter.class
  );

  private static final String ENTRIES_WRITE_URL =
    "https://logging.googleapis.com/v2/entries:write";
  private static final String CLOUD_PLATFORM_SCOPE =
    "https://www.googleapis.com/auth/cloud-platform";
  private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
  ).withZone(ZoneOffset.UTC);

  private final String projectId;
  private final String logName;
  private final GoogleCredentials credentials;
  private final HttpClient http;
  private final GcpResource resource;

  public GclLogRecordExporter(
    String projectId,
    String logName,
    String credentialsFile
  ) {
    this.projectId = projectId;
    this.logName = logName;
    this.credentials = loadCredentials(credentialsFile);
    this.http = HttpClient.newHttpClient();
    this.resource = GcpResource.detect(projectId);
    log.info(
      "GCL logs MonitoredResource: type={} labels={}",
      resource.type(),
      resource.labels()
    );
  }

  @Override
  public CompletableResultCode export(Collection<LogRecordData> records) {
    try {
      credentials.refreshIfExpired();
      String token = credentials.getAccessToken().getTokenValue();

      String body = buildRequestBody(projectId, logName, resource, records);

      var request = HttpRequest.newBuilder()
        .uri(URI.create(ENTRIES_WRITE_URL))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return CompletableResultCode.ofSuccess();
      }
      log.warn(
        "GCL write failed: status={} body={}",
        response.statusCode(),
        response.body().substring(0, Math.min(500, response.body().length()))
      );
      return CompletableResultCode.ofFailure();
    } catch (Exception e) {
      log.warn("GCL write error: {}", e.getMessage(), e);
      return CompletableResultCode.ofFailure();
    }
  }

  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  // Package-private + static so it can be unit-tested without GCP credentials.
  static String buildRequestBody(
    String projectId,
    String logName,
    GcpResource resource,
    Collection<LogRecordData> records
  ) {
    var entries = new StringBuilder("[");
    boolean first = true;
    for (var rec : records) {
      if (!first) entries.append(",");
      first = false;
      appendEntry(entries, projectId, rec);
    }
    entries.append("]");

    return (
      """
      {"logName":"projects/%s/logs/%s","resource":%s,"entries":%s}
      """.formatted(projectId, logName, resource.toJson(), entries)
    ).strip();
  }

  private static void appendEntry(
    StringBuilder out,
    String projectId,
    LogRecordData rec
  ) {
    long nanos = rec.getTimestampEpochNanos();
    String timestamp = RFC3339.format(
      Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L)
    );

    // Bucket attributes once: HTTP request fields and Sentry labels are promoted
    // to top-level LogEntry fields so Cloud Logging renders them natively.
    var httpRequest = new LinkedHashMap<String, Object>();
    var labels = new LinkedHashMap<String, String>();
    var payload = new LinkedHashMap<String, Object>();
    rec
      .getAttributes()
      .forEach((key, value) -> {
        switch (key.getKey()) {
          case "http.method" -> httpRequest.put("requestMethod", value);
          case "http.status" -> httpRequest.put("status", value);
          case "http.latency_ms" -> httpRequest.put("latency", value);
          case "entrypoint.request.content_length" -> httpRequest.put(
            "requestSize",
            value
          );
          case "entrypoint.response.content_length" -> httpRequest.put(
            "responseSize",
            value
          );
          case "sentry.trace_id" -> labels.put(
            "sentry_trace_id",
            String.valueOf(value)
          );
          case "sentry.span_id" -> labels.put(
            "sentry_span_id",
            String.valueOf(value)
          );
          default -> payload.put(key.getKey(), value);
        }
      });

    out.append("{");
    out.append("\"timestamp\":\"").append(timestamp).append("\"");
    out
      .append(",\"severity\":\"")
      .append(mapSeverity(rec.getSeverity()))
      .append("\"");

    appendHttpRequest(out, httpRequest);
    appendLabels(out, labels);
    appendJsonPayload(out, rec.getBody().asString(), payload);

    var spanCtx = rec.getSpanContext();
    if (spanCtx.isValid()) {
      out
        .append(",\"trace\":\"projects/")
        .append(projectId)
        .append("/traces/")
        .append(spanCtx.getTraceId())
        .append("\"");
      out.append(",\"spanId\":\"").append(spanCtx.getSpanId()).append("\"");
      out.append(",\"traceSampled\":true");
    }

    out.append("}");
  }

  private static void appendHttpRequest(
    StringBuilder out,
    Map<String, Object> fields
  ) {
    if (fields.isEmpty()) return;
    out.append(",\"httpRequest\":{");
    boolean first = true;
    for (var e : fields.entrySet()) {
      if (!first) out.append(",");
      first = false;
      String k = e.getKey();
      Object v = e.getValue();
      switch (k) {
        case "status" -> out.append("\"status\":").append(v);
        case "latency" -> out
          .append("\"latency\":\"")
          .append(formatLatency(((Number) v).longValue()))
          .append("\"");
        case "requestSize", "responseSize" -> out
          .append("\"")
          .append(k)
          .append("\":\"")
          .append(v)
          .append("\"");
        default -> out
          .append("\"")
          .append(k)
          .append("\":\"")
          .append(escapeJson(String.valueOf(v)))
          .append("\"");
      }
    }
    out.append("}");
  }

  private static void appendLabels(
    StringBuilder out,
    Map<String, String> labels
  ) {
    if (labels.isEmpty()) return;
    out.append(",\"labels\":{");
    boolean first = true;
    for (var e : labels.entrySet()) {
      if (!first) out.append(",");
      first = false;
      out
        .append("\"")
        .append(escapeJson(e.getKey()))
        .append("\":\"")
        .append(escapeJson(e.getValue()))
        .append("\"");
    }
    out.append("}");
  }

  private static void appendJsonPayload(
    StringBuilder out,
    String message,
    Map<String, Object> attrs
  ) {
    out.append(",\"jsonPayload\":{");
    out.append("\"message\":\"").append(escapeJson(message)).append("\"");
    for (var e : attrs.entrySet()) {
      out.append(",\"").append(escapeJson(e.getKey())).append("\":");
      appendJsonValue(out, e.getValue());
    }
    out.append("}");
  }

  // Preserves numeric/boolean types so GCL renders/sorts them correctly.
  private static void appendJsonValue(StringBuilder out, Object v) {
    if (v == null) {
      out.append("null");
    } else if (v instanceof Number || v instanceof Boolean) {
      out.append(v);
    } else {
      out.append("\"").append(escapeJson(String.valueOf(v))).append("\"");
    }
  }

  // GCL HttpRequest.latency is a Duration encoded as "<seconds>.<nanos>s".
  private static String formatLatency(long ms) {
    return String.format(Locale.ROOT, "%d.%03ds", ms / 1000, ms % 1000);
  }

  private static String mapSeverity(Severity severity) {
    return switch (severity) {
      case
        TRACE,
        TRACE2,
        TRACE3,
        TRACE4,
        DEBUG,
        DEBUG2,
        DEBUG3,
        DEBUG4 -> "DEBUG";
      case INFO, INFO2, INFO3, INFO4 -> "INFO";
      case WARN, WARN2, WARN3, WARN4 -> "WARNING";
      case
        ERROR,
        ERROR2,
        ERROR3,
        ERROR4,
        FATAL,
        FATAL2,
        FATAL3,
        FATAL4 -> "ERROR";
      default -> "DEFAULT";
    };
  }

  private static String escapeJson(String s) {
    if (s == null) return "";
    return s
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t");
  }

  private static GoogleCredentials loadCredentials(String credentialsFile) {
    try {
      GoogleCredentials creds;
      if (credentialsFile != null && !credentialsFile.isBlank()) {
        try (var stream = new FileInputStream(credentialsFile)) {
          creds = ServiceAccountCredentials.fromStream(stream);
        }
      } else {
        creds = GoogleCredentials.getApplicationDefault();
      }
      return creds.createScoped(CLOUD_PLATFORM_SCOPE);
    } catch (IOException e) {
      throw new IllegalStateException(
        "Failed to load GCP credentials: " + e.getMessage(),
        e
      );
    }
  }
}
