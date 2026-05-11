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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.reporter.otellogs.mapper.OtelLogRecord;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the OtelLogWriter + GclLogRecordExporter pipeline using
 * InMemoryLogRecordExporter to verify record structure without GCP connectivity.
 *
 * <p>The actual HTTP export logic in GclLogRecordExporter is covered by GclLogsReporterIT.
 */
class GclLogRecordExporterTest {

  @Test
  void writerEmitsRecordWithTraceContext() throws Exception {
    var exporter = InMemoryLogRecordExporter.create();
    var writer = new OtelLogWriter(exporter, 1, 100);

    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    String spanId = "00f067aa0ba902b7";

    var record = new OtelLogRecord(
      traceId,
      spanId,
      null,
      null,
      Severity.INFO,
      1_000_000_000L,
      "GET /test → 200",
      Attributes.builder()
        .put(AttributeKey.stringKey("api.name"), "Test API")
        .put(AttributeKey.longKey("http.status"), 200L)
        .build()
    );

    writer.emit(record);
    writer.flush();

    var records = exporter.getFinishedLogRecordItems();
    assertThat(records).hasSize(1);

    var emitted = records.get(0);
    assertThat(emitted.getBody().asString()).isEqualTo("GET /test → 200");
    assertThat(emitted.getSeverity()).isEqualTo(Severity.INFO);
    assertThat(emitted.getSpanContext().getTraceId()).isEqualTo(traceId);
    assertThat(emitted.getSpanContext().getSpanId()).isEqualTo(spanId);
    assertThat(
      emitted.getAttributes().get(AttributeKey.stringKey("api.name"))
    ).isEqualTo("Test API");
    assertThat(
      emitted.getAttributes().get(AttributeKey.longKey("http.status"))
    ).isEqualTo(200L);

    writer.close();
  }

  @Test
  void writerEmitsRecordWithoutSpanContextWhenSpanIdAbsent() throws Exception {
    var exporter = InMemoryLogRecordExporter.create();
    var writer = new OtelLogWriter(exporter, 1, 100);

    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

    var record = new OtelLogRecord(
      traceId,
      null,
      null,
      null,
      Severity.WARN,
      2_000_000_000L,
      "health-check degraded",
      Attributes.empty()
    );

    writer.emit(record);
    writer.flush();

    var records = exporter.getFinishedLogRecordItems();
    assertThat(records).hasSize(1);

    var emitted = records.get(0);
    assertThat(emitted.getSeverity()).isEqualTo(Severity.WARN);
    assertThat(emitted.getSpanContext().isValid()).isFalse();
    assertThat(
      emitted
        .getAttributes()
        .get(AttributeKey.stringKey("http.request.trace_id"))
    ).isEqualTo(traceId);

    writer.close();
  }
}
