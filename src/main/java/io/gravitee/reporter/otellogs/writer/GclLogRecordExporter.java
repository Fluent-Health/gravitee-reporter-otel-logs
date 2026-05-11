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

  public GclLogRecordExporter(
    String projectId,
    String logName,
    String credentialsFile
  ) {
    this.projectId = projectId;
    this.logName = logName;
    this.credentials = loadCredentials(credentialsFile);
    this.http = HttpClient.newHttpClient();
  }

  @Override
  public CompletableResultCode export(Collection<LogRecordData> records) {
    try {
      credentials.refreshIfExpired();
      String token = credentials.getAccessToken().getTokenValue();

      String body = buildRequestBody(records);

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

  private String buildRequestBody(Collection<LogRecordData> records) {
    var entries = new StringBuilder("[");
    boolean first = true;
    for (var rec : records) {
      if (!first) entries.append(",");
      first = false;

      long nanos = rec.getTimestampEpochNanos();
      String timestamp = RFC3339.format(
        Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L)
      );

      entries.append("{");
      entries.append("\"timestamp\":\"").append(timestamp).append("\",");
      entries
        .append("\"severity\":\"")
        .append(mapSeverity(rec.getSeverity()))
        .append("\",");

      // Use jsonPayload to preserve all OTel attributes as structured fields
      entries.append("\"jsonPayload\":{");
      entries
        .append("\"message\":\"")
        .append(escapeJson(rec.getBody().asString()))
        .append("\"");

      rec
        .getAttributes()
        .forEach((key, value) -> {
          entries
            .append(",\"")
            .append(escapeJson(key.getKey()))
            .append("\":\"")
            .append(escapeJson(String.valueOf(value)))
            .append("\"");
        });

      entries.append("}");

      var spanCtx = rec.getSpanContext();
      if (spanCtx.isValid()) {
        entries
          .append(",\"trace\":\"projects/")
          .append(projectId)
          .append("/traces/")
          .append(spanCtx.getTraceId())
          .append("\"");
        entries
          .append(",\"spanId\":\"")
          .append(spanCtx.getSpanId())
          .append("\"");
        entries.append(",\"traceSampled\":true");
      }

      entries.append("}");
    }
    entries.append("]");

    return (
      """
      {"logName":"projects/%s/logs/%s","resource":{"type":"global"},"entries":%s}
      """.formatted(projectId, logName, entries)
    ).strip();
  }

  private String mapSeverity(Severity severity) {
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

  private String escapeJson(String s) {
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
