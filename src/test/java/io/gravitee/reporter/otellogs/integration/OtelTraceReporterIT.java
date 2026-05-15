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

import com.sun.net.httpserver.HttpServer;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.otellogs.OtelLogsReporter;
import io.gravitee.reporter.otellogs.config.LogsConfiguration;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.config.ResourceConfiguration;
import io.gravitee.reporter.otellogs.config.TracesConfiguration;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration test: stands up a tiny HTTP server that captures POSTs to
 * {@code /v1/traces}, runs the reporter with {@code traces.enabled=true},
 * sends a fake {@link Metrics} event that carries an inbound W3C traceparent
 * header, and asserts that:
 * <ol>
 *   <li>A span batch arrives at the OTLP receiver.</li>
 *   <li>The batch's binary payload contains the expected 16-byte trace-ID from
 *       the inbound traceparent ({@code 0af7651916cd43dd8448eb211c80319c}).</li>
 * </ol>
 *
 * <p>No external container is needed — the OTLP receiver is a plain
 * {@link HttpServer} bound to a random port.
 */
@Tag("integration")
class OtelTraceReporterIT {

  private HttpServer otlpServer;
  private final List<byte[]> receivedBodies = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startOtlpReceiver() throws Exception {
    otlpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    otlpServer.createContext("/v1/traces", ex -> {
      receivedBodies.add(ex.getRequestBody().readAllBytes());
      ex.sendResponseHeaders(200, 0);
      ex.close();
    });
    otlpServer.start();
  }

  @AfterEach
  void stopOtlpReceiver() {
    if (otlpServer != null) otlpServer.stop(0);
    receivedBodies.clear();
  }

  @Test
  void emits_span_with_inbound_traceparent_through_otlp() throws Exception {
    int port = otlpServer.getAddress().getPort();
    String endpoint = "http://127.0.0.1:" + port + "/v1/traces";
    OtelLogsReporterConfiguration cfg = buildCfg(endpoint);
    OtelLogsReporter reporter = new OtelLogsReporter(cfg);
    reporter.start();

    reporter.report(metricsWithTraceparent());

    await()
      .atMost(Duration.ofSeconds(5))
      .untilAsserted(() ->
        assertThat(receivedBodies).hasSizeGreaterThanOrEqualTo(1)
      );

    // The OTLP payload is serialised protobuf. The 16-byte trace-ID propagated
    // from the inbound traceparent appears verbatim as a bytes field in the
    // binary encoding — assert its presence without pulling in a full proto
    // dependency (opentelemetry-exporter-otlp-common ships only internal
    // marshalers, not standard proto-generated parseFrom() classes).
    byte[] traceIdBytes = hexToBytes("0af7651916cd43dd8448eb211c80319c");
    assertThat(containsSubsequence(receivedBodies.get(0), traceIdBytes))
      .as("OTLP payload should contain the propagated trace-ID bytes")
      .isTrue();

    reporter.stop();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static OtelLogsReporterConfiguration buildCfg(String tracesEndpoint) {
    LogsConfiguration logs = new LogsConfiguration();
    logs.setEnabled(false); // traces-only IT
    TracesConfiguration traces = new TracesConfiguration();
    traces.setEnabled(true);
    traces.setExporter("otlp");
    traces.setEndpoint(tracesEndpoint);
    traces.setAuthMode("none");
    traces.setBatchSize(1);
    traces.setScheduledDelayMs(50);
    traces.setSampler("always-on");
    ResourceConfiguration res = new ResourceConfiguration();
    res.setServiceName("test-gateway");
    res.setServiceNamespace("apim");
    res.setGcpProjectId("test-project");
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

  private static Metrics metricsWithTraceparent() {
    Metrics m = mock(Metrics.class);
    Log lg = mock(Log.class);
    Request req = mock(Request.class);
    HttpHeaders h = mock(HttpHeaders.class);
    when(h.get("traceparent")).thenReturn(
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
    );
    when(req.getHeaders()).thenReturn(h);
    when(lg.getEntrypointRequest()).thenReturn(req);
    when(m.getLog()).thenReturn(lg);
    when(m.getHttpMethod()).thenReturn(HttpMethod.GET);
    when(m.getStatus()).thenReturn(200);
    when(m.getTimestamp()).thenReturn(System.currentTimeMillis());
    when(m.getGatewayResponseTimeMs()).thenReturn(42L);
    when(m.getApiName()).thenReturn("orders");
    when(m.getEndpoint()).thenReturn("https://upstream/orders");
    when(m.getUri()).thenReturn("/api/orders");
    return m;
  }

  private static byte[] hexToBytes(String hex) {
    int len = hex.length();
    byte[] out = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
    }
    return out;
  }

  /**
   * Returns {@code true} when {@code needle} appears as a contiguous
   * sub-sequence anywhere in {@code haystack}.
   */
  private static boolean containsSubsequence(byte[] haystack, byte[] needle) {
    outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) continue outer;
      }
      return true;
    }
    return false;
  }
}
