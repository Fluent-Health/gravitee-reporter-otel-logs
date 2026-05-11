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
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OtelLogWriterTest {

  private InMemoryLogRecordExporter exporter;
  private OtelLogWriter writer;

  @BeforeEach
  void setUp() {
    exporter = InMemoryLogRecordExporter.create();
    writer = new OtelLogWriter(exporter, 512, 5000);
  }

  @AfterEach
  void tearDown() {
    writer.close();
  }

  @Test
  void emittedRecordAppearsAfterFlush() {
    var record = new OtelLogRecord(
      null,
      null,
      null,
      null,
      Severity.INFO,
      Instant.parse("2026-01-15T12:00:00Z").toEpochMilli() * 1_000_000L,
      "GET /api/v1/health → 200",
      Attributes.builder()
        .put(AttributeKey.stringKey("api.name"), "Health API")
        .put(AttributeKey.longKey("http.status"), 200L)
        .build()
    );

    writer.emit(record);
    writer.flush();

    var logs = exporter.getFinishedLogRecordItems();
    assertThat(logs).hasSize(1);
    assertThat(logs.get(0).getBody().asString()).isEqualTo(
      "GET /api/v1/health → 200"
    );
    assertThat(logs.get(0).getSeverity()).isEqualTo(Severity.INFO);
    assertThat(
      logs.get(0).getAttributes().get(AttributeKey.stringKey("api.name"))
    ).isEqualTo("Health API");
    assertThat(
      logs.get(0).getAttributes().get(AttributeKey.longKey("http.status"))
    ).isEqualTo(200L);
  }

  @Test
  void traceIdAndSpanIdAreSetOnSpanContext() {
    var record = new OtelLogRecord(
      "550e8400e29b41d4a716446655440000",
      "7d17675a3e4f44e8",
      null,
      null,
      Severity.ERROR,
      Instant.now().toEpochMilli() * 1_000_000L,
      "POST /api/v1/upload → 500",
      Attributes.empty()
    );

    writer.emit(record);
    writer.flush();

    var logs = exporter.getFinishedLogRecordItems();
    assertThat(logs).hasSize(1);
    assertThat(logs.get(0).getSpanContext().getTraceId()).isEqualTo(
      "550e8400e29b41d4a716446655440000"
    );
    assertThat(logs.get(0).getSpanContext().getSpanId()).isEqualTo(
      "7d17675a3e4f44e8"
    );
  }

  @Test
  void recordWithOnlyTraceIdDoesNotSetSpanContext() {
    String traceId = "a1b2c3d4e5f60718a1b2c3d4e5f60718";
    writer.emit(
      new OtelLogRecord(
        traceId,
        null,
        null,
        null,
        Severity.INFO,
        Instant.now().toEpochMilli() * 1_000_000L,
        "trace only",
        Attributes.empty()
      )
    );
    writer.flush();
    var records = exporter.getFinishedLogRecordItems();
    assertThat(records).hasSize(1);
    var record = records.get(0);
    // No span context should be set — span context is invalid (all zeros)
    assertThat(record.getSpanContext().isValid()).isFalse();
  }

  @Test
  void emitIsThreadSafe() throws Exception {
    int count = 500;
    java.util.stream.IntStream.range(0, count)
      .parallel()
      .forEach(i ->
        writer.emit(
          new OtelLogRecord(
            null,
            null,
            null,
            null,
            Severity.INFO,
            System.nanoTime(),
            "msg-" + i,
            Attributes.empty()
          )
        )
      );
    writer.flush();
    assertThat(exporter.getFinishedLogRecordItems()).hasSize(count);
  }

  @Test
  void recordWithNoTraceIdHasInvalidSpanContext() {
    var record = new OtelLogRecord(
      null,
      null,
      null,
      null,
      Severity.INFO,
      Instant.now().toEpochMilli() * 1_000_000L,
      "GET /api/v1/health → 200",
      Attributes.empty()
    );

    writer.emit(record);
    writer.flush();

    var logs = exporter.getFinishedLogRecordItems();
    assertThat(logs).hasSize(1);
    assertThat(logs.get(0).getSpanContext().isValid()).isFalse();
  }

  @Test
  void sentryTraceIdIsAddedAsAttribute() {
    var record = new OtelLogRecord(
      null,
      null,
      "771a43a4192642f0b136d5159a501700",
      "7d17675a3e4f44e8",
      Severity.INFO,
      Instant.now().toEpochMilli() * 1_000_000L,
      "GET /api/v1/health → 200",
      Attributes.empty()
    );

    writer.emit(record);
    writer.flush();

    var logs = exporter.getFinishedLogRecordItems();
    assertThat(
      logs.get(0).getAttributes().get(AttributeKey.stringKey("sentry.trace_id"))
    ).isEqualTo("771a43a4192642f0b136d5159a501700");
    assertThat(
      logs.get(0).getAttributes().get(AttributeKey.stringKey("sentry.span_id"))
    ).isEqualTo("7d17675a3e4f44e8");
  }
}
