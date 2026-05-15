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

import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OTLP/HTTP span exporter built on java.net.http.HttpClient — same rationale as
 * {@link CustomOtlpHttpLogRecordExporter}. Headers are sourced from a {@link Supplier}
 * per export so ADC tokens can be refreshed without rebuilding the exporter.
 */
public class CustomOtlpHttpSpanExporter implements SpanExporter {

  private static final Logger log = LoggerFactory.getLogger(
    CustomOtlpHttpSpanExporter.class
  );

  private final HttpClient httpClient;
  private final URI endpoint;
  private final Supplier<Map<String, String>> headers;

  public CustomOtlpHttpSpanExporter(
    String endpoint,
    Supplier<Map<String, String>> headers
  ) {
    this.httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();
    // Normalise to a trailing slash so URI.resolve("v1/traces") works correctly.
    URI base = URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/");
    // If the caller already supplied a /v1/traces path, keep it; otherwise resolve.
    this.endpoint = base.getPath().endsWith("/v1/traces/") ||
      base.getPath().endsWith("/v1/traces")
      ? URI.create(endpoint)
      : base.resolve("v1/traces");
    this.headers = headers;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    if (spans.isEmpty()) {
      return CompletableResultCode.ofSuccess();
    }

    try {
      TraceRequestMarshaler marshaler = TraceRequestMarshaler.create(spans);
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      marshaler.writeBinaryTo(baos);

      log.debug(
        "Sending OTLP spans request to {} ({} spans)",
        endpoint,
        spans.size()
      );

      HttpRequest.Builder rb = HttpRequest.newBuilder()
        .uri(endpoint)
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/x-protobuf")
        .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()));
      headers.get().forEach(rb::header);

      CompletableResultCode result = new CompletableResultCode();

      httpClient
        .sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString())
        .whenComplete((response, throwable) -> {
          if (throwable != null) {
            log.warn("OTLP span export failed: {}", throwable.getMessage());
            result.fail();
          } else if (
            response.statusCode() >= 200 && response.statusCode() < 300
          ) {
            result.succeed();
          } else {
            log.warn(
              "OTLP span export status={} body={}",
              response.statusCode(),
              truncate(response.body(), 500)
            );
            result.fail();
          }
        });

      return result;
    } catch (IOException e) {
      log.warn("Failed to marshal OTLP spans: {}", e.getMessage());
      return CompletableResultCode.ofFailure();
    } catch (Exception e) {
      log.warn("Unexpected error during OTLP span export: {}", e.getMessage());
      return CompletableResultCode.ofFailure();
    }
  }

  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }
}
