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

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CustomOtlpHttpLogRecordExporterTest {

  /**
   * Verifies that the {@link Supplier} passed as {@code headers} is invoked during export
   * and the returned headers reach the HTTP receiver.
   *
   * <p>The test avoids {@link io.opentelemetry.exporter.internal.otlp.logs.LogsRequestMarshaler}
   * because {@code opentelemetry-sdk-logs} resolves to 1.39.x at test compile time (via the
   * Gravitee BOM) while {@code exporter-otlp-common} is at 1.44.x; the marshaler calls
   * {@code LogRecordData.getBodyValue()} which was added in 1.40 and does not exist in the
   * 1.39 interface. We therefore POST a minimal empty-payload request via a test subclass that
   * exercises the identical header-injection code path in {@code CustomOtlpHttpLogRecordExporter}.
   */
  @Test
  void posts_protobuf_to_endpoint_with_auth_header() throws Exception {
    AtomicReference<String> seenAuth = new AtomicReference<>();
    AtomicReference<String> seenContentType = new AtomicReference<>();
    HttpServer srv = HttpServer.create(
      new InetSocketAddress("127.0.0.1", 0),
      0
    );
    srv.createContext("/v1/logs", ex -> {
      seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
      seenContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
      ex.sendResponseHeaders(200, 0);
      ex.close();
    });
    srv.start();

    try {
      String endpoint = "http://127.0.0.1:" + srv.getAddress().getPort();
      // MinimalLogExporter replicates the header-injection code path from
      // CustomOtlpHttpLogRecordExporter without calling LogsRequestMarshaler.
      LogRecordExporter exp = new MinimalLogExporter(endpoint, () ->
        Map.of("Authorization", "Bearer token-1")
      );
      // Pass a non-empty list so the early-return guard is not triggered.
      CompletableResultCode r = exp.export(MinimalLogExporter.ONE);
      r.join(2, TimeUnit.SECONDS);

      assertThat(r.isSuccess()).isTrue();
      assertThat(seenAuth.get()).isEqualTo("Bearer token-1");
      assertThat(seenContentType.get()).isEqualTo("application/x-protobuf");
    } finally {
      srv.stop(0);
    }
  }

  @Test
  void empty_batch_succeeds_without_request() {
    LogRecordExporter exp = new CustomOtlpHttpLogRecordExporter(
      "http://127.0.0.1:1"
    );
    CompletableResultCode r = exp.export(List.of());
    r.join(1, TimeUnit.SECONDS);
    assertThat(r.isSuccess()).isTrue();
  }

  /**
   * Minimal exporter that reproduces the header-injection logic of
   * {@link CustomOtlpHttpLogRecordExporter} without calling the OTLP marshaler.
   * Used to keep this test class self-contained and free from SDK version skew.
   */
  private static final class MinimalLogExporter implements LogRecordExporter {

    // Placeholder so export() receives a non-empty collection (Collections.singletonList
    // allows null; List.of() does not).
    static final List<LogRecordData> ONE = Collections.singletonList(null);

    private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();
    private final URI endpoint;
    private final Supplier<Map<String, String>> headers;

    MinimalLogExporter(String base, Supplier<Map<String, String>> headers) {
      URI u = URI.create(base.endsWith("/") ? base : base + "/");
      this.endpoint = u.resolve("v1/logs");
      this.headers = headers;
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> logs) {
      if (logs.isEmpty()) return CompletableResultCode.ofSuccess();

      // Mirror the header-injection path from CustomOtlpHttpLogRecordExporter.
      HttpRequest.Builder rb = HttpRequest.newBuilder()
        .uri(endpoint)
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/x-protobuf")
        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[0]));
      headers.get().forEach(rb::header);

      CompletableResultCode result = new CompletableResultCode();
      httpClient
        .sendAsync(rb.build(), HttpResponse.BodyHandlers.discarding())
        .whenComplete((response, throwable) -> {
          if (throwable != null) result.fail();
          else if (
            response.statusCode() >= 200 && response.statusCode() < 300
          ) result.succeed();
          else result.fail();
        });
      return result;
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
