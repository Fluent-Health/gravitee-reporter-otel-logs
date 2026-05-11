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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.reporter.otellogs.OtelTestSupport;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetricsToLogRecordMapperTest {

  private MetricsToLogRecordMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new MetricsToLogRecordMapper(OtelTestSupport.config());
  }

  @Test
  void bodyContainsMethodSanitizedPathAndStatus() {
    var record = mapper.map(OtelTestSupport.metrics(200));
    assertThat(record.body()).isEqualTo("GET /api/v1/users/{id} → 200");
  }

  @Test
  void status200HasSeverityInfo() {
    assertThat(mapper.map(OtelTestSupport.metrics(200)).severity()).isEqualTo(
      Severity.INFO
    );
  }

  @Test
  void status400HasSeverityWarn() {
    assertThat(mapper.map(OtelTestSupport.metrics(400)).severity()).isEqualTo(
      Severity.WARN
    );
  }

  @Test
  void status500HasSeverityError() {
    assertThat(mapper.map(OtelTestSupport.metrics(500)).severity()).isEqualTo(
      Severity.ERROR
    );
  }

  @Test
  void apiAttributesAreMapped() {
    var record = mapper.map(OtelTestSupport.metrics(200));
    assertThat(
      record.attributes().get(AttributeKey.stringKey("api.name"))
    ).isEqualTo("Test API");
    assertThat(
      record.attributes().get(AttributeKey.stringKey("api.id"))
    ).isEqualTo("api-123");
  }

  @Test
  void httpAttributesAreMapped() {
    var record = mapper.map(OtelTestSupport.metrics(200));
    assertThat(
      record.attributes().get(AttributeKey.stringKey("http.method"))
    ).isEqualTo("GET");
    assertThat(
      record.attributes().get(AttributeKey.longKey("http.status"))
    ).isEqualTo(200L);
    assertThat(
      record.attributes().get(AttributeKey.longKey("http.latency_ms"))
    ).isEqualTo(42L);
  }

  @Test
  void upstreamEndpointPathIsAlsoSanitized() {
    var record = mapper.map(OtelTestSupport.metrics(200));
    assertThat(
      record.attributes().get(AttributeKey.stringKey("upstream.endpoint"))
    ).isEqualTo("https://backend.example.com/api/users/{id}");
  }

  @Test
  void contentLengthsAreMapped() {
    var record = mapper.map(OtelTestSupport.metrics(200));
    assertThat(
      record
        .attributes()
        .get(AttributeKey.longKey("entrypoint.request.content_length"))
    ).isEqualTo(128L);
    assertThat(
      record
        .attributes()
        .get(AttributeKey.longKey("entrypoint.response.content_length"))
    ).isEqualTo(512L);
  }

  @Test
  void xRequestIdHeaderBecomesTraceId() {
    // Headers are embedded in the Log's entrypointRequest (v4 Metrics has no direct header field)
    var m = OtelTestSupport.metricsWithHeaders(
      200,
      Map.of("X-Request-ID", "550e8400-e29b-41d4-a716-446655440000")
    );
    var record = mapper.map(m);
    assertThat(record.traceId()).isEqualTo("550e8400e29b41d4a716446655440000");
  }

  @Test
  void traceparentHeaderFallsBackWhenCorrelationHeaderAbsent() {
    var m = OtelTestSupport.metricsWithHeaders(
      200,
      Map.of(
        "traceparent",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
      )
    );
    var record = mapper.map(m);
    assertThat(record.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(record.spanId()).isEqualTo("00f067aa0ba902b7");
  }

  @Test
  void sentryTraceHeaderExtractsSentryTraceIdAndSpanId() {
    var m = OtelTestSupport.metricsWithHeaders(
      200,
      Map.of(
        "sentry-trace",
        "771a43a4192642f0b136d5159a501700-7d17675a3e4f44e8-1"
      )
    );
    var record = mapper.map(m);
    assertThat(record.sentryTraceId()).isEqualTo(
      "771a43a4192642f0b136d5159a501700"
    );
    assertThat(record.sentrySpanId()).isEqualTo("7d17675a3e4f44e8");
  }

  @Test
  void noHeadersMeansNullTraceId() {
    var record = mapper.map(OtelTestSupport.metrics(200));
    assertThat(record.traceId()).isNull();
    assertThat(record.sentryTraceId()).isNull();
  }

  @Test
  void timestampIsConvertedToNanoseconds() {
    var record = mapper.map(OtelTestSupport.metrics(200));
    // 2026-01-15T12:00:00Z = 1736942400000 ms → * 1_000_000
    assertThat(record.timestampEpochNanos()).isEqualTo(
      OtelTestSupport.FIXTURE_TIMESTAMP_MS * 1_000_000L
    );
  }
}
