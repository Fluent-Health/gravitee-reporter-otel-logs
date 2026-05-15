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

import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.otellogs.OtelLogsReporter;
import io.gravitee.reporter.otellogs.OtelTestSupport;
import io.gravitee.reporter.otellogs.mapper.EndpointStatusToLogRecordMapper;
import io.gravitee.reporter.otellogs.mapper.LogToLogRecordMapper;
import io.gravitee.reporter.otellogs.mapper.MessageMetricsToLogRecordMapper;
import io.gravitee.reporter.otellogs.mapper.MetricsToLogRecordMapper;
import io.gravitee.reporter.otellogs.writer.OtelLogWriter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link OtelLogsReporter} that wires up the reporter end-to-end
 * (mappers + writer + processor + exporter) using an in-memory {@link
 * InMemoryLogRecordExporter} as the sink.
 *
 * <p>Why no Docker containers here? The reporter's responsibilities are:
 * (1) routing reportables to the correct mapper, and (2) producing well-formed
 * {@link io.opentelemetry.sdk.logs.data.LogRecordData} via the OTel Logs SDK. Both
 * can be verified without standing up a collector or a storage backend. The real
 * OTLP HTTP transport is exercised in the heavier
 * {@link OtelLogsReporterIT E2E test}, which stands up the full Gravitee stack and
 * an OTel Collector container.
 */
@Tag("integration")
class OtelLogsReporterManualIT {

  private static InMemoryLogRecordExporter exporter;
  private static OtelLogsReporter reporter;
  private static OtelLogWriter writer;

  @BeforeAll
  static void setUp() throws Exception {
    exporter = InMemoryLogRecordExporter.create();
    // Small batch + short delay → flush() returns quickly in tests.
    writer = new OtelLogWriter(exporter, 10, 100);

    var cfg = OtelTestSupport.config();

    reporter = new OtelLogsReporter(cfg);
    inject(reporter, "writer", writer);
    inject(reporter, "metricsMapper", new MetricsToLogRecordMapper(cfg));
    inject(reporter, "logMapper", new LogToLogRecordMapper(false));
    inject(reporter, "endpointMapper", new EndpointStatusToLogRecordMapper());
    inject(reporter, "messageMapper", new MessageMetricsToLogRecordMapper());
    // Public start() flips the lifecycle to STARTED, which report() checks.
    reporter.start();
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (reporter != null) reporter.stop();
  }

  @BeforeEach
  void resetExporter() {
    exporter.reset();
  }

  // ── Scenario 1: GET 200 metric produces a single INFO log record ────────────

  @Test
  void metricsProducesInfoLogRecord() {
    Metrics m = OtelTestSupport.metrics(200);
    m.setTimestamp(System.currentTimeMillis());
    reporter.report(m);
    writer.flush();

    List<LogRecordData> records = exporter.getFinishedLogRecordItems();
    assertThat(records).hasSize(1);
    LogRecordData r = records.get(0);
    assertThat(r.getSeverity()).isEqualTo(Severity.INFO);
    // Sanitised path appears in the body: /api/v1/users/42 → /api/v1/users/{id}
    assertThat(r.getBody().asString()).contains("users");
  }

  // ── Scenario 2: 500 status → SEVERITY_ERROR ─────────────────────────────────

  @Test
  void errorStatusProducesSeverityErrorLog() {
    Metrics m500 = OtelTestSupport.metrics(500);
    m500.setTimestamp(System.currentTimeMillis());
    reporter.report(m500);
    writer.flush();

    List<LogRecordData> records = exporter.getFinishedLogRecordItems();
    assertThat(records).hasSize(1);
    assertThat(records.get(0).getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(records.get(0).getBody().asString()).contains("500");
  }

  // ── Scenario 3: X-Request-ID UUID → traceId attribute ────────────────────────

  @Test
  void traceIdFromXRequestIdIsSetOnRecord() {
    // X-Request-ID derives to a SHA-256-based 16-byte trace ID via TraceContextResolver
    var m = OtelTestSupport.metricsWithHeaders(
      200,
      Map.of("X-Request-ID", "550e8400-e29b-41d4-a716-446655440000")
    );
    m.setTimestamp(System.currentTimeMillis());
    reporter.report(m);
    writer.flush();

    LogRecordData r = exporter.getFinishedLogRecordItems().get(0);
    // No spanId was provided, so traceId lives as the http.request.trace_id attribute.
    assertThat(
      r.getAttributes().get(AttributeKey.stringKey("http.request.trace_id"))
    ).isEqualTo("a3a9e1ed9732cab28868127be00f1ce9");
  }

  // ── Scenario 4: traceparent header → trace context set on log record ────────

  @Test
  void traceparentHeaderSetsSpanContext() {
    // traceparent: version-traceId-parentId-flags
    // traceId = 4bf92f3577b34da6a3ce929d0e0e4736, spanId = 00f067aa0ba902b7
    var m = OtelTestSupport.metricsWithHeaders(
      200,
      Map.of(
        "traceparent",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
      )
    );
    m.setTimestamp(System.currentTimeMillis());
    reporter.report(m);
    writer.flush();

    LogRecordData r = exporter.getFinishedLogRecordItems().get(0);
    assertThat(r.getSpanContext().getTraceId()).isEqualTo(
      "4bf92f3577b34da6a3ce929d0e0e4736"
    );
    assertThat(r.getSpanContext().getSpanId()).isEqualTo("00f067aa0ba902b7");
  }

  // ── Scenario 5: sentry-trace header → sentry.trace_id attribute ─────────────

  @Test
  void sentryTraceHeaderProducesSentryAttributes() {
    var m = OtelTestSupport.metricsWithHeaders(
      200,
      Map.of(
        "sentry-trace",
        "771a43a4192642f0b136d5159a501700-7d17675a3e4f44e8-1"
      )
    );
    m.setTimestamp(System.currentTimeMillis());
    reporter.report(m);
    writer.flush();

    LogRecordData r = exporter.getFinishedLogRecordItems().get(0);
    assertThat(
      r.getAttributes().get(AttributeKey.stringKey("sentry.trace_id"))
    ).isEqualTo("771a43a4192642f0b136d5159a501700");
    assertThat(
      r.getAttributes().get(AttributeKey.stringKey("sentry.span_id"))
    ).isEqualTo("7d17675a3e4f44e8");
  }

  // ── Scenario 6: EndpointStatus DOWN → ERROR record mentioning DOWN ─────────

  @Test
  void endpointStatusDownProducesErrorRecord() {
    EndpointStatus es = OtelTestSupport.endpointStatus(false);
    es.setTimestamp(System.currentTimeMillis());
    reporter.report(es);
    writer.flush();

    LogRecordData r = exporter.getFinishedLogRecordItems().get(0);
    assertThat(r.getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(r.getBody().asString()).contains("DOWN");
  }

  // ── Scenario 7: reportLogs=false → canHandle(Log) returns false ──────────────

  @Test
  void canHandleReturnsFalseForLogWhenReportLogsDisabled() {
    // reportLogs=false in OtelTestSupport.config().
    var l = OtelTestSupport.log(200);
    assertThat(reporter.canHandle(l))
      .as("reporter must reject Log events when reportLogs=false")
      .isFalse();
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private static void inject(Object target, String fieldName, Object value)
    throws Exception {
    Field f = findField(target.getClass(), fieldName);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static Field findField(Class<?> clazz, String name)
    throws NoSuchFieldException {
    try {
      return clazz.getDeclaredField(name);
    } catch (NoSuchFieldException e) {
      if (clazz.getSuperclass() != null) return findField(
        clazz.getSuperclass(),
        name
      );
      throw e;
    }
  }
}
