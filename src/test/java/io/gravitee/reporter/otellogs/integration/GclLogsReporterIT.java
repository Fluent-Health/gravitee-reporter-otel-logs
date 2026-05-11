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
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test that writes an OTel log record to Google Cloud Logging via
 * OTLP gRPC and verifies it arrives via the Cloud Logging REST API. Requires
 * Application Default Credentials: in CI via Workload Identity Federation,
 * locally via {@code gcloud auth application-default login}.
 *
 * <p>Run with: {@code mvn verify -Pgcloud-integration-test}
 */
@Tag("integration")
@Tag("gcloud-integration")
class GclLogsReporterIT {

  private static final String LOGGING_SCOPE =
    "https://www.googleapis.com/auth/logging.read";
  private static final String ENTRIES_LIST_URL =
    "https://logging.googleapis.com/v2/entries:list";

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
      LOGGING_SCOPE
    );
    http = HttpClient.newHttpClient();

    var exporter = OtlpGrpcLogRecordExporter.builder()
      .setEndpoint("https://logging.googleapis.com")
      .build();
    // Batch size 1 + minimal delay so records flush immediately in tests
    writer = new OtelLogWriter(exporter, 1, 100);
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
    // Provide a spanId so OtelLogWriter sets the OTel span context, which GCL
    // maps to the LogEntry trace field as "projects/{project}/traces/{traceId}".
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

    // GCL maps the OTel traceId to the LogEntry trace field:
    //   "projects/{project}/traces/{traceId}"
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

    // JSON-escape the filter string for embedding in the request body
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
    return (
      response.statusCode() == 200 && response.body().contains("\"entries\"")
    );
  }

  private static String resolveProjectId() {
    // Prefer the explicit env var; fall back to the env vars google-github-actions/auth sets
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
}
