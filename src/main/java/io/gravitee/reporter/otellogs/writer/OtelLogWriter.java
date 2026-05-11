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
import org.slf4j.LoggerFactory;

public class OtelLogWriter implements AutoCloseable {

  private static final org.slf4j.Logger log = LoggerFactory.getLogger(
    OtelLogWriter.class
  );

  private final SdkLoggerProvider sdkLoggerProvider;
  private final Logger otelLogger;

  public OtelLogWriter(
    LogRecordExporter exporter,
    int batchSize,
    int scheduledDelayMs
  ) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException(
        "batchSize must be > 0, got: " + batchSize
      );
    }
    if (scheduledDelayMs <= 0) {
      throw new IllegalArgumentException(
        "scheduledDelayMs must be > 0, got: " + scheduledDelayMs
      );
    }
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
    try {
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

      if (record.traceId() != null && record.spanId() != null) {
        var spanCtx = SpanContext.create(
          record.traceId(),
          record.spanId(),
          TraceFlags.getSampled(),
          TraceState.getDefault()
        );
        builder.setContext(Context.root().with(Span.wrap(spanCtx)));
      } else if (record.traceId() != null) {
        // traceId present but no spanId (e.g. correlation header only) — store as attribute
        // so the trace identifier is preserved and queryable in the log backend.
        attrsBuilder.put(
          AttributeKey.stringKey("http.request.trace_id"),
          record.traceId()
        );
        builder.setAllAttributes(attrsBuilder.build());
      }

      builder.emit();
    } catch (Throwable t) {
      log.warn("Failed to emit OTel log record: {}", t.getMessage(), t);
    }
  }

  public void flush() {
    var result = sdkLoggerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    if (!result.isSuccess()) {
      log.warn("OTel log flush did not complete successfully");
    }
  }

  @Override
  public void close() {
    var result = sdkLoggerProvider.shutdown().join(10, TimeUnit.SECONDS);
    if (!result.isSuccess()) {
      log.warn("OTel log provider shutdown did not complete successfully");
    }
  }
}
