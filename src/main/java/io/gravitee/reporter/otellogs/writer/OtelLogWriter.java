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

import io.gravitee.reporter.otellogs.mapper.OtelLogRecord;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class OtelLogWriter implements AutoCloseable {

  private final SdkLoggerProvider sdkLoggerProvider;
  private final Logger otelLogger;

  public OtelLogWriter(
    LogRecordExporter exporter,
    int batchSize,
    int scheduledDelayMs
  ) {
    var processor = BatchLogRecordProcessor.builder(exporter)
      .setMaxExportBatchSize(batchSize)
      .setScheduleDelay(Duration.ofMillis(scheduledDelayMs))
      .build();
    sdkLoggerProvider = SdkLoggerProvider.builder()
      .addLogRecordProcessor(processor)
      .build();
    otelLogger = sdkLoggerProvider.get("gravitee-otel-logs");
  }

  public void emit(OtelLogRecord record) {
    AttributesBuilder attrsBuilder = record.attributes().toBuilder();

    if (record.sentryTraceId() != null) {
      attrsBuilder.put(
        AttributeKey.stringKey("sentry.trace_id"),
        record.sentryTraceId()
      );
    }
    if (record.sentrySpanId() != null) {
      attrsBuilder.put(
        AttributeKey.stringKey("sentry.span_id"),
        record.sentrySpanId()
      );
    }

    var builder = otelLogger
      .logRecordBuilder()
      .setSeverity(record.severity())
      .setTimestamp(record.timestampEpochNanos(), TimeUnit.NANOSECONDS)
      .setBody(record.body())
      .setAllAttributes(attrsBuilder.build());

    if (record.traceId() != null) {
      String spanId = record.spanId() != null
        ? record.spanId()
        : record.traceId().substring(0, 16);
      var spanCtx = SpanContext.create(
        record.traceId(),
        spanId,
        TraceFlags.getDefault(),
        TraceState.getDefault()
      );
      builder.setContext(Context.root().with(Span.wrap(spanCtx)));
    }

    builder.emit();
  }

  public void flush() {
    sdkLoggerProvider.forceFlush().join(10, TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    sdkLoggerProvider.shutdown().join(10, TimeUnit.SECONDS);
  }
}
