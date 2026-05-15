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

import io.gravitee.reporter.otellogs.config.TracesConfiguration;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelTraceWriter implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(
    OtelTraceWriter.class
  );

  private final SdkTracerProvider sdkTracerProvider;
  private final Tracer tracer;

  public OtelTraceWriter(
    SpanExporter exporter,
    TracesConfiguration cfg,
    Resource resource
  ) {
    if (cfg.getBatchSize() <= 0) {
      throw new IllegalArgumentException(
        "traces.batchSize must be > 0, got: " + cfg.getBatchSize()
      );
    }
    if (cfg.getScheduledDelayMs() <= 0) {
      throw new IllegalArgumentException(
        "traces.scheduledDelayMs must be > 0, got: " + cfg.getScheduledDelayMs()
      );
    }

    BatchSpanProcessor processor = BatchSpanProcessor.builder(exporter)
      .setMaxExportBatchSize(cfg.getBatchSize())
      .setScheduleDelay(Duration.ofMillis(cfg.getScheduledDelayMs()))
      .build();

    sdkTracerProvider = SdkTracerProvider.builder()
      .setResource(resource)
      .addSpanProcessor(processor)
      .setSampler(buildSampler(cfg))
      .build();

    tracer = sdkTracerProvider.get("gravitee-otel-traces");
  }

  public Tracer tracer() {
    return tracer;
  }

  public void flush() {
    var result = sdkTracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    if (!result.isSuccess()) {
      log.warn("OTel trace flush did not complete successfully");
    }
  }

  @Override
  public void close() {
    var result = sdkTracerProvider.shutdown().join(10, TimeUnit.SECONDS);
    if (!result.isSuccess()) {
      log.warn("OTel tracer provider shutdown did not complete successfully");
    }
  }

  private static Sampler buildSampler(TracesConfiguration cfg) {
    return switch (cfg.getSampler()) {
      case "always-on" -> Sampler.alwaysOn();
      case "parent-based-always-on" -> Sampler.parentBased(Sampler.alwaysOn());
      case "ratio" -> Sampler.parentBased(
        Sampler.traceIdRatioBased(cfg.getSampleRatio())
      );
      default -> throw new IllegalArgumentException(
        "Unknown traces.sampler '" +
          cfg.getSampler() +
          "' — expected always-on|parent-based-always-on|ratio"
      );
    };
  }
}
