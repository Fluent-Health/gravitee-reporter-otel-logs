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
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CustomOtlpHttpSpanExporterTest {

  @Test
  void posts_protobuf_to_endpoint_with_auth_header() throws Exception {
    AtomicReference<String> seenAuth = new AtomicReference<>();
    AtomicReference<String> seenContentType = new AtomicReference<>();
    HttpServer srv = HttpServer.create(
      new InetSocketAddress("127.0.0.1", 0),
      0
    );
    srv.createContext("/v1/traces", ex -> {
      seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
      seenContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
      ex.sendResponseHeaders(200, 0);
      ex.close();
    });
    srv.start();

    try {
      String endpoint = "http://127.0.0.1:" + srv.getAddress().getPort();
      SpanExporter exp = new CustomOtlpHttpSpanExporter(endpoint, () ->
        Map.of("Authorization", "Bearer token-1")
      );
      CompletableResultCode r = exp.export(List.of(sampleSpan()));
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
    SpanExporter exp = new CustomOtlpHttpSpanExporter(
      "http://127.0.0.1:1",
      () -> Map.of()
    );
    CompletableResultCode r = exp.export(List.of());
    r.join(1, TimeUnit.SECONDS);
    assertThat(r.isSuccess()).isTrue();
  }

  private static SpanData sampleSpan() {
    return TestSpanData.builder()
      .setName("test")
      .setKind(SpanKind.SERVER)
      .setStartEpochNanos(1L)
      .setEndEpochNanos(2L)
      .setHasEnded(true)
      .setSpanContext(
        SpanContext.create(
          "0af7651916cd43dd8448eb211c80319c",
          "b7ad6b7169203331",
          TraceFlags.getSampled(),
          TraceState.getDefault()
        )
      )
      .setResource(Resource.empty())
      .setAttributes(Attributes.empty())
      .setStatus(StatusData.unset())
      .build();
  }
}
