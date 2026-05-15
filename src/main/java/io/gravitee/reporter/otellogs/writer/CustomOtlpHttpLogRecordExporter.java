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

import io.opentelemetry.exporter.internal.otlp.logs.LogsRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
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
 * A custom OTLP HTTP log exporter that uses the built-in Java HttpClient to avoid
 * the SPI issues common in Gravitee plugins when using the official OTel exporters.
 * Headers are sourced from a {@link Supplier} per export so ADC tokens can be
 * refreshed without rebuilding the exporter.
 */
public class CustomOtlpHttpLogRecordExporter implements LogRecordExporter {

  private static final Logger log = LoggerFactory.getLogger(
    CustomOtlpHttpLogRecordExporter.class
  );

  private final HttpClient httpClient;
  private final URI endpoint;
  private final Supplier<Map<String, String>> headers;

  /** Convenience constructor — no extra headers (backwards-compatible). */
  public CustomOtlpHttpLogRecordExporter(String endpoint) {
    this(endpoint, OtlpAuthHeaders.none());
  }

  public CustomOtlpHttpLogRecordExporter(
    String endpoint,
    Supplier<Map<String, String>> headers
  ) {
    this.httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();
    this.endpoint = URI.create(
      endpoint.endsWith("/") ? endpoint : endpoint + "/"
    );
    this.headers = headers;
  }

  @Override
  public CompletableResultCode export(Collection<LogRecordData> logs) {
    if (logs.isEmpty()) {
      return CompletableResultCode.ofSuccess();
    }

    try {
      LogsRequestMarshaler marshaler = LogsRequestMarshaler.create(logs);
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      marshaler.writeBinaryTo(baos);

      log.debug(
        "Sending OTLP logs request to {} ({} records)",
        endpoint,
        logs.size()
      );

      HttpRequest.Builder rb = HttpRequest.newBuilder()
        .uri(endpoint.resolve("v1/logs"))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/x-protobuf")
        .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()));
      headers.get().forEach(rb::header);

      HttpRequest request = rb.build();

      CompletableResultCode result = new CompletableResultCode();

      httpClient
        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .whenComplete((response, throwable) -> {
          if (throwable != null) {
            log.error("Failed to send OTLP logs request", throwable);
            result.fail();
          } else if (
            response.statusCode() >= 200 && response.statusCode() < 300
          ) {
            result.succeed();
          } else {
            log.warn(
              "OTLP export failed with status={} body={}",
              response.statusCode(),
              truncate(response.body(), 500)
            );
            result.fail();
          }
        });

      return result;
    } catch (IOException e) {
      log.error("Failed to marshal OTLP logs request", e);
      return CompletableResultCode.ofFailure();
    } catch (Exception e) {
      log.error("Unexpected error during OTLP export", e);
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
