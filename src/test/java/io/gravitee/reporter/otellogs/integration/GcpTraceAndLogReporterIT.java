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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.common.Response;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.otellogs.OtelLogsReporter;
import io.gravitee.reporter.otellogs.config.LogsConfiguration;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.config.ResourceConfiguration;
import io.gravitee.reporter.otellogs.config.TracesConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test that exports one log entry to Google Cloud Logging AND one span
 * to Google Cloud Trace through the full {@link OtelLogsReporter}, using the same
 * trace ID so the two are cross-referenced in the GCP console.
 *
 * <p>Requires Application Default Credentials (locally via
 * {@code gcloud auth application-default login}; in CI via Workload Identity
 * Federation) and the GCP project resolved from {@code GOOGLE_CLOUD_PROJECT}
 * (or {@code GCLOUD_PROJECT} / {@code CLOUDSDK_CORE_PROJECT}).
 *
 * <p>The log is read back from GCL via REST to confirm arrival. The span is not
 * read back — Cloud Trace's readback API is more involved and the OTLP export
 * success is verified by the exporter itself. Visual verification of the span
 * is via the printed Cloud Trace console URL.
 *
 * <p>Run with: {@code mvn verify -Pgcloud-integration-test -Dit.test=GcpTraceAndLogReporterIT}
 */
@Tag("integration")
@Tag("gcloud-integration")
class GcpTraceAndLogReporterIT {

  private static final String CLOUD_PLATFORM_SCOPE =
    "https://www.googleapis.com/auth/cloud-platform";
  private static final String ENTRIES_LIST_URL =
    "https://logging.googleapis.com/v2/entries:list";
  private static final String LOG_NAME = "gravitee-otel-it";
  private static final SecureRandom RNG = new SecureRandom();

  @Test
  void exportsLogAndSpanWithSameTraceIdToGcp() throws Exception {
    String projectId = resolveProjectId();
    assertThat(projectId)
      .as(
        "GCP project ID must be set via GOOGLE_CLOUD_PROJECT env var or discoverable from ADC"
      )
      .isNotNull()
      .isNotBlank();

    // Unique 16-byte trace ID + 8-byte span ID — encodes per-run identity into
    // the W3C traceparent so the log and span land under a fresh trace.
    String traceId = randomHex(16);
    String spanId = randomHex(8);
    String traceparent = "00-" + traceId + "-" + spanId + "-01";

    OtelLogsReporterConfiguration cfg = buildCfg(projectId);
    OtelLogsReporter reporter = new OtelLogsReporter(cfg);

    System.out.println(
      """

      ===========================================================================
      GcpTraceAndLogReporterIT — exporting to project: %s
      ===========================================================================
      trace ID    : %s
      span ID     : %s (synthetic, for the simulated inbound traceparent)

      After the test passes, visually verify both signals in your project:

        Cloud Trace UI (look for span 'gravitee.gateway.request'):
          https://console.cloud.google.com/traces/list?project=%s&tid=%s

        Cloud Logging UI (filter for trace=%s):
          https://console.cloud.google.com/logs/query;query=trace%%3D%%22projects%%2F%s%%2Ftraces%%2F%s%%22?project=%s

        Linked view (open the trace, scroll to the "Logs" panel inside):
          https://console.cloud.google.com/traces/list?project=%s&tid=%s

      ===========================================================================
      """.formatted(
          projectId,
          traceId,
          spanId,
          projectId,
          traceId,
          traceId,
          projectId,
          traceId,
          projectId,
          projectId,
          traceId
        )
    );

    reporter.start();
    try {
      // Drive both event types for the same logical request. With
      // reportRequestSummary=true + reportRequestLogs=false, only the
      // Metrics-derived summary record should be emitted; the Log event
      // is filtered out by the reporter routing.
      reporter.report(metricsWithTraceparent(traceparent));
      reporter.report(logWithTraceparent(traceparent));
    } finally {
      reporter.stop(); // stop() flushes both pipelines
    }

    // Verify the log entry actually arrived in Cloud Logging by reading it back.
    String filter = "trace=\"projects/%s/traces/%s\"".formatted(
      projectId,
      traceId
    );
    String expectedTraceField =
      "\"projects/" + projectId + "/traces/" + traceId + "\"";

    GoogleCredentials creds =
      GoogleCredentials.getApplicationDefault().createScoped(
        CLOUD_PLATFORM_SCOPE
      );
    HttpClient http = HttpClient.newHttpClient();

    // GCL's entries:list occasionally returns transient 5xx during ingest
    // propagation; ignoreExceptions lets awaitility retry through those.
    await("log entry with traceId=" + traceId + " to appear in GCL")
      .atMost(Duration.ofSeconds(120))
      .pollInterval(Duration.ofSeconds(5))
      .ignoreException(IllegalStateException.class)
      .conditionEvaluationListener(condition ->
        System.out.printf(
          "Polling Cloud Logging (elapsed %.0fs / 120s)%n",
          condition.getElapsedTimeInMS() / 1000.0
        )
      )
      .until(() ->
        queryLogEntries(http, creds, projectId, filter, 10).contains(
          expectedTraceField
        )
      );

    String responseBody = queryLogEntries(http, creds, projectId, filter, 10);
    assertThat(responseBody)
      .as("GCL readback must include our trace field %s", expectedTraceField)
      .contains(expectedTraceField);
    assertThat(responseBody)
      .as("GCL readback must include the log body we wrote")
      .contains("GET /gravitee-it-probe");

    // Combined-record assertion: exactly ONE entry per request, and that
    // entry must carry both the request side (method) AND the response side
    // (status) — proving the Metrics-derived summary record fuses the two.
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = new Gson().fromJson(responseBody, Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) parsed.get(
      "entries"
    );
    assertThat(entries)
      .as(
        "exactly one log record expected per request when reportRequestSummary=true + reportRequestLogs=false; both Metrics and Log events were reported but the Log path must be suppressed"
      )
      .hasSize(1);

    @SuppressWarnings("unchecked")
    Map<String, Object> httpRequest = (Map<String, Object>) entries
      .get(0)
      .get("httpRequest");
    assertThat(httpRequest)
      .as("summary record must carry httpRequest with combined req+resp fields")
      .isNotNull();
    assertThat(httpRequest.get("requestMethod"))
      .as("request side must be present (method from inbound request)")
      .isEqualTo("GET");
    assertThat(httpRequest.get("requestUrl"))
      .as("request side must include the sanitized URL")
      .isEqualTo("/gravitee-it-probe");
    // GCL returns numeric status as a double via JSON; coerce for comparison.
    assertThat(((Number) httpRequest.get("status")).intValue())
      .as("response side must be present (status from outbound response)")
      .isEqualTo(200);

    System.out.println(
      """

      One combined log record confirmed in Cloud Logging (request method +
      URL + response status all on httpRequest). The span was exported to
      telemetry.googleapis.com/v1/traces — open the Cloud Trace URL above to
      verify it in the UI (typical ingestion lag: 30s–2min).
      """
    );
  }

  // ── Test fixtures ────────────────────────────────────────────────────────

  private static OtelLogsReporterConfiguration buildCfg(String projectId) {
    LogsConfiguration logs = new LogsConfiguration();
    logs.setEnabled(true);
    logs.setExporter("gcloud");
    logs.setLogName(LOG_NAME);
    logs.setBatchSize(1);
    logs.setScheduledDelayMs(100);
    logs.setReportRequestLogs(false); // Log event path suppressed
    logs.setReportRequestSummary(true); // Metrics-derived summary emitted
    logs.setReportHealthChecks(false);
    logs.setReportMessageMetrics(false);

    TracesConfiguration traces = new TracesConfiguration();
    traces.setEnabled(true);
    traces.setExporter("otlp");
    traces.setEndpoint("https://telemetry.googleapis.com/v1/traces");
    traces.setAuthMode("gcp-adc");
    traces.setBatchSize(1);
    traces.setScheduledDelayMs(100);
    traces.setSampler("always-on");

    ResourceConfiguration res = new ResourceConfiguration();
    res.setServiceName("gravitee-otel-it");
    res.setServiceNamespace("apim");
    res.setGcpProjectId(projectId);
    res.setGcpAutoDetect(false);

    OtelLogsReporterConfiguration cfg = new OtelLogsReporterConfiguration(
      logs,
      traces,
      res
    );
    cfg.setEnabled(true);
    cfg.setCorrelationHeader("X-Request-ID");
    return cfg;
  }

  private static Metrics metricsWithTraceparent(String traceparent) {
    Metrics m = mock(Metrics.class);
    Log lg = mock(Log.class);
    Request req = mock(Request.class);
    HttpHeaders h = mock(HttpHeaders.class);
    when(h.get("traceparent")).thenReturn(traceparent);
    when(req.getHeaders()).thenReturn(h);
    when(lg.getEntrypointRequest()).thenReturn(req);
    when(m.getLog()).thenReturn(lg);
    when(m.getHttpMethod()).thenReturn(HttpMethod.GET);
    when(m.getStatus()).thenReturn(200);
    when(m.getTimestamp()).thenReturn(System.currentTimeMillis());
    when(m.getGatewayResponseTimeMs()).thenReturn(123L);
    when(m.getApiName()).thenReturn("Gravitee OTel IT");
    when(m.getEndpoint()).thenReturn("https://upstream.example.com/probe");
    when(m.getUri()).thenReturn("/gravitee-it-probe");
    return m;
  }

  /**
   * Builds a Log event that should be SUPPRESSED by the reporter when
   * reportRequestLogs=false. Reported alongside the Metrics event to prove
   * the suppression actually happens — if it didn't, the readback would
   * find two entries instead of one.
   */
  private static Log logWithTraceparent(String traceparent) {
    Log lg = mock(Log.class);
    Request req = mock(Request.class);
    Response resp = mock(Response.class);
    HttpHeaders h = mock(HttpHeaders.class);
    when(h.get("traceparent")).thenReturn(traceparent);
    when(req.getHeaders()).thenReturn(h);
    when(req.getMethod()).thenReturn(HttpMethod.GET);
    when(req.getUri()).thenReturn("/gravitee-it-probe");
    when(resp.getStatus()).thenReturn(200);
    when(lg.getEntrypointRequest()).thenReturn(req);
    when(lg.getEntrypointResponse()).thenReturn(resp);
    when(lg.getApiName()).thenReturn("Gravitee OTel IT");
    when(lg.getTimestamp()).thenReturn(System.currentTimeMillis());
    return lg;
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static String queryLogEntries(
    HttpClient http,
    GoogleCredentials credentials,
    String projectId,
    String filter,
    int pageSize
  ) throws Exception {
    credentials.refreshIfExpired();
    String token = credentials.getAccessToken().getTokenValue();
    String escapedFilter = filter.replace("\"", "\\\"");
    String body = """
      {"resourceNames":["projects/%s"],"filter":"%s","pageSize":%d,"orderBy":"timestamp desc"}
      """.formatted(projectId, escapedFilter, pageSize)
      .strip();

    var request = HttpRequest.newBuilder()
      .uri(URI.create(ENTRIES_LIST_URL))
      .header("Authorization", "Bearer " + token)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build();

    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
        "GCL query failed: status=" +
          response.statusCode() +
          " body=" +
          response.body()
      );
    }
    return response.body();
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

  private static String randomHex(int bytes) {
    byte[] buf = new byte[bytes];
    RNG.nextBytes(buf);
    StringBuilder sb = new StringBuilder(bytes * 2);
    for (byte b : buf) sb.append(String.format("%02x", b));
    return sb.toString();
  }
}
