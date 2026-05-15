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
import com.google.gson.Gson;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * <p>Authentication uses Application Default Credentials (ADC). In GKE this is
 * satisfied automatically by Workload Identity; locally, run
 * {@code gcloud auth application-default login}.
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
  private static final Gson GSON = new Gson();

  private final String projectId;
  private final String logName;
  private final GoogleCredentials credentials;
  private final HttpClient http;
  private final GcpResource resource;

  public GclLogRecordExporter(String projectId, String logName) {
    this.projectId = projectId;
    this.logName = logName;
    this.credentials = loadAdc();
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
    var entries = new ArrayList<Map<String, Object>>(records.size());
    for (var rec : records) {
      entries.add(buildEntry(projectId, rec));
    }

    var root = new LinkedHashMap<String, Object>();
    root.put("logName", "projects/" + projectId + "/logs/" + logName);
    root.put("resource", resourceMap(resource));
    root.put("entries", entries);
    return GSON.toJson(root);
  }

  private static Map<String, Object> resourceMap(GcpResource resource) {
    var m = new LinkedHashMap<String, Object>();
    m.put("type", resource.type());
    if (!resource.labels().isEmpty()) {
      m.put("labels", resource.labels());
    }
    return m;
  }

  private static Map<String, Object> buildEntry(
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
    payload.put("message", rec.getBody().asString());

    rec
      .getAttributes()
      .forEach((key, value) -> {
        switch (key.getKey()) {
          case "http.method" -> httpRequest.put("requestMethod", value);
          case "http.status" -> httpRequest.put("status", value);
          case "http.latency_ms" -> httpRequest.put(
            "latency",
            formatLatency(((Number) value).longValue())
          );
          case "entrypoint.request.content_length" -> httpRequest.put(
            "requestSize",
            String.valueOf(value)
          );
          case "entrypoint.response.content_length" -> httpRequest.put(
            "responseSize",
            String.valueOf(value)
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

    var entry = new LinkedHashMap<String, Object>();
    entry.put("timestamp", timestamp);
    entry.put("severity", mapSeverity(rec.getSeverity()));
    if (!httpRequest.isEmpty()) entry.put("httpRequest", httpRequest);
    if (!labels.isEmpty()) entry.put("labels", labels);
    entry.put("jsonPayload", payload);

    var spanCtx = rec.getSpanContext();
    if (spanCtx.isValid()) {
      entry.put(
        "trace",
        "projects/" + projectId + "/traces/" + spanCtx.getTraceId()
      );
      entry.put("spanId", spanCtx.getSpanId());
      entry.put("traceSampled", true);
    }
    return entry;
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

  private static GoogleCredentials loadAdc() {
    try {
      return GoogleCredentials.getApplicationDefault().createScoped(
        CLOUD_PLATFORM_SCOPE
      );
    } catch (IOException e) {
      throw new IllegalStateException(
        "Failed to load Application Default Credentials: " + e.getMessage(),
        e
      );
    }
  }
}
