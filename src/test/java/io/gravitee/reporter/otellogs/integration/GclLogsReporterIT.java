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
package io.gravitee.reporter.otellogs.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.google.auth.oauth2.GoogleCredentials;
import io.gravitee.reporter.otellogs.mapper.OtelLogRecord;
import io.gravitee.reporter.otellogs.writer.OtelLogWriter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test that writes an OTel log record to Google Cloud Logging via
 * the Cloud Logging REST API and verifies it arrives via readback. Requires
 * Application Default Credentials: in CI via Workload Identity Federation,
 * locally via {@code gcloud auth application-default login}.
 *
 * <p>The OTLP gRPC transport tested by OtelLogsReporterIT targets an OTel
 * Collector (TestContainers). This test validates Cloud Logging connectivity
 * and the OtelLogWriter lifecycle using a lightweight REST-based exporter.
 *
 * <p>Run with: {@code mvn verify -Pgcloud-integration-test}
 */
@Tag("integration")
@Tag("gcloud-integration")
class GclLogsReporterIT {

  private static final String CLOUD_PLATFORM_SCOPE =
    "https://www.googleapis.com/auth/cloud-platform";
  private static final String ENTRIES_WRITE_URL =
    "https://logging.googleapis.com/v2/entries:write";
  private static final String ENTRIES_LIST_URL =
    "https://logging.googleapis.com/v2/entries:list";
  private static final String LOG_NAME = "gravitee-otel-it";

  private static String projectId;
  private static GoogleCredentials credentials;
  private static HttpClient http;
  private static OtelLogWriter writer;

  @BeforeAll
  static void setUp() throws Exception {
    projectId = resolveProjectId();
    assertThat(projectId)
      .as(
        "GCP project ID must be set via GOOGLE_CLOUD_PROJECT env var or discoverable from ADC"
      )
      .isNotNull()
      .isNotBlank();

    credentials = GoogleCredentials.getApplicationDefault().createScoped(
      CLOUD_PLATFORM_SCOPE
    );
    credentials.refreshIfExpired();
    http = HttpClient.newHttpClient();

    // GCL does not have a native OTLP gRPC endpoint — production deployments
    // route through an OTel Collector (tested by OtelLogsReporterIT via TestContainers).
    // Here we use a REST-based exporter to validate the OtelLogWriter lifecycle and
    // record structure (trace ID, body, attributes) against the live GCL service.
    writer = new OtelLogWriter(new GclRestLogRecordExporter(), 1, 100);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (writer != null) {
      writer.flush();
      writer.close();
    }
  }

  @Test
  void metricsLogRecordArrivesInGoogleCloudLogging() throws Exception {
    // Unique 32-hex traceId so this run's entry can be found unambiguously.
    String traceId = UUID.randomUUID().toString().replace("-", "");
    // Provide a spanId so OtelLogWriter sets the OTel span context,
    // which the GclRestLogRecordExporter maps to the LogEntry trace field.
    String spanId = traceId.substring(0, 16);

    var record = new OtelLogRecord(
      traceId,
      spanId,
      null,
      null,
      Severity.INFO,
      System.currentTimeMillis() * 1_000_000L,
      "GET /gcloud-it-probe → 200",
      Attributes.builder()
        .put(AttributeKey.stringKey("api.name"), "GCL IT Test")
        .put(AttributeKey.stringKey("api.id"), "gcloud-it")
        .put(AttributeKey.longKey("http.status"), 200L)
        .build()
    );

    writer.emit(record);
    writer.flush();

    // GCL filter uses the trace field: "projects/{project}/traces/{traceId}"
    String filter = "trace=\"projects/%s/traces/%s\"".formatted(
      projectId,
      traceId
    );

    await("log entry with traceId=" + traceId + " to appear in GCL")
      .atMost(Duration.ofSeconds(90))
      .pollInterval(Duration.ofSeconds(5))
      .conditionEvaluationListener(condition ->
        System.out.printf(
          "Polling GCL (elapsed %.0fs / 90s)%n",
          condition.getElapsedTimeInMS() / 1000.0
        )
      )
      .until(() -> queryLogEntries(filter));

    assertThat(queryLogEntries(filter)).isTrue();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static boolean queryLogEntries(String filter) throws Exception {
    credentials.refreshIfExpired();
    String token = credentials.getAccessToken().getTokenValue();

    String escapedFilter = filter.replace("\"", "\\\"");
    String body = """
      {"resourceNames":["projects/%s"],"filter":"%s","pageSize":1,"orderBy":"timestamp desc"}
      """.formatted(projectId, escapedFilter)
      .strip();

    var request = HttpRequest.newBuilder()
      .uri(URI.create(ENTRIES_LIST_URL))
      .header("Authorization", "Bearer " + token)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build();

    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    boolean found =
      response.statusCode() == 200 && response.body().contains("\"entries\"");
    if (!found) {
      System.out.printf(
        "GCL query status=%d body=%s%n",
        response.statusCode(),
        response.body().substring(0, Math.min(200, response.body().length()))
      );
    }
    return found;
  }

  private static String resolveProjectId() {
    for (String var : List.of(
      "GOOGLE_CLOUD_PROJECT",
      "GCLOUD_PROJECT",
      "CLOUDSDK_CORE_PROJECT"
    )) {
      String val = System.getenv(var);
      if (val != null && !val.isBlank()) return val;
    }
    return null;
  }

  // ── REST-based Cloud Logging exporter ────────────────────────────────────

  /**
   * Translates OTel {@code LogRecordData} to Cloud Logging v2 {@code entries:write}
   * REST calls. Used in place of the OTLP gRPC exporter because Cloud Logging
   * does not expose a native OTLP gRPC endpoint — that path requires an OTel
   * Collector intermediary (covered by OtelLogsReporterIT via TestContainers).
   */
  private static class GclRestLogRecordExporter implements LogRecordExporter {

    private static final DateTimeFormatter RFC3339 =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(
        ZoneOffset.UTC
      );

    @Override
    public CompletableResultCode export(Collection<LogRecordData> records) {
      try {
        credentials.refreshIfExpired();
        String token = credentials.getAccessToken().getTokenValue();

        var entries = new StringBuilder("[");
        boolean first = true;
        for (var rec : records) {
          if (!first) entries.append(",");
          first = false;

          long nanos = rec.getTimestampEpochNanos();
          String timestamp = RFC3339.format(
            Instant.ofEpochSecond(
              nanos / 1_000_000_000L,
              nanos % 1_000_000_000L
            )
          );

          entries.append("{");
          entries.append("\"timestamp\":\"").append(timestamp).append("\",");
          entries
            .append("\"severity\":\"")
            .append(mapSeverity(rec.getSeverity()))
            .append("\",");
          entries
            .append("\"textPayload\":\"")
            .append(escapeJson(rec.getBody().asString()))
            .append("\"");

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
          }
          entries.append("}");
        }
        entries.append("]");

        String body = ("""
          {"logName":"projects/%s/logs/%s","resource":{"type":"global"},"entries":%s}
          """.formatted(projectId, LOG_NAME, entries)).strip();

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
        System.err.printf(
          "GCL write failed: status=%d body=%s%n",
          response.statusCode(),
          response.body()
        );
        return CompletableResultCode.ofFailure();
      } catch (Exception e) {
        System.err.println("GCL write error: " + e.getMessage());
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
      return s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
    }
  }
}
