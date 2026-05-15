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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

class MetricsToSpanMapperTest {

  @Test
  void parents_to_inbound_traceparent_and_carries_http_semconv_attrs() {
    InMemorySpanExporter exp = InMemorySpanExporter.create();
    SdkTracerProvider provider = SdkTracerProvider.builder()
      .setResource(Resource.empty())
      .addSpanProcessor(SimpleSpanProcessor.create(exp))
      .build();
    TraceContextResolver resolver = new TraceContextResolver("X-Request-ID");
    MetricsToSpanMapper mapper = new MetricsToSpanMapper(
      provider.get("test"),
      resolver
    );

    Metrics m = mock(Metrics.class);
    Log lg = mock(Log.class);
    Request req = mock(Request.class);
    HttpHeaders h = mock(HttpHeaders.class);
    when(h.get("traceparent")).thenReturn(
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
    );
    when(req.getHeaders()).thenReturn(h);
    when(lg.getEntrypointRequest()).thenReturn(req);
    when(m.getLog()).thenReturn(lg);
    when(m.getHttpMethod()).thenReturn(HttpMethod.GET);
    when(m.getStatus()).thenReturn(200);
    when(m.getTimestamp()).thenReturn(1_700_000_000_000L); // ms
    when(m.getGatewayResponseTimeMs()).thenReturn(150L);
    when(m.getApiName()).thenReturn("orders-api");
    when(m.getEndpoint()).thenReturn("https://upstream/orders");
    when(m.getUri()).thenReturn("/api/orders");

    mapper.map(m);
    provider.forceFlush().join(2, java.util.concurrent.TimeUnit.SECONDS);

    assertThat(exp.getFinishedSpanItems()).hasSize(1);
    var span = exp.getFinishedSpanItems().get(0);
    assertThat(span.getName()).isEqualTo("gravitee.gateway.request");
    assertThat(span.getKind().name()).isEqualTo("SERVER");
    assertThat(span.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    assertThat(span.getStartEpochNanos()).isEqualTo(
      (1_700_000_000_000L - 150L) * 1_000_000L
    );
    assertThat(span.getEndEpochNanos()).isEqualTo(
      1_700_000_000_000L * 1_000_000L
    );
    assertThat(
      span.getAttributes().get(AttributeKey.stringKey("http.request.method"))
    ).isEqualTo("GET");
    assertThat(
      span
        .getAttributes()
        .get(AttributeKey.longKey("http.response.status_code"))
    ).isEqualTo(200L);
    assertThat(
      span.getAttributes().get(AttributeKey.stringKey("http.route"))
    ).isEqualTo("/api/orders");
    assertThat(
      span.getAttributes().get(AttributeKey.stringKey("gravitee.api.name"))
    ).isEqualTo("orders-api");
    assertThat(
      span
        .getAttributes()
        .get(AttributeKey.stringKey("gravitee.upstream.endpoint"))
    ).isEqualTo("https://upstream/orders");
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
  }

  @Test
  void five_hundred_sets_error_status() {
    InMemorySpanExporter exp = InMemorySpanExporter.create();
    SdkTracerProvider provider = SdkTracerProvider.builder()
      .addSpanProcessor(SimpleSpanProcessor.create(exp))
      .build();
    MetricsToSpanMapper mapper = new MetricsToSpanMapper(
      provider.get("test"),
      new TraceContextResolver("X-Request-ID")
    );
    Metrics m = mock(Metrics.class);
    when(m.getStatus()).thenReturn(503);
    when(m.getTimestamp()).thenReturn(1L);
    when(m.getGatewayResponseTimeMs()).thenReturn(1L);

    mapper.map(m);
    provider.forceFlush().join(2, java.util.concurrent.TimeUnit.SECONDS);

    var span = exp.getFinishedSpanItems().get(0);
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(span.getStatus().getDescription()).contains("503");
  }

  @Test
  void no_trace_context_creates_root_span() {
    InMemorySpanExporter exp = InMemorySpanExporter.create();
    SdkTracerProvider provider = SdkTracerProvider.builder()
      .addSpanProcessor(SimpleSpanProcessor.create(exp))
      .build();
    MetricsToSpanMapper mapper = new MetricsToSpanMapper(
      provider.get("test"),
      new TraceContextResolver("X-Request-ID")
    );
    Metrics m = mock(Metrics.class);
    when(m.getStatus()).thenReturn(200);
    when(m.getTimestamp()).thenReturn(1L);
    when(m.getGatewayResponseTimeMs()).thenReturn(1L);
    when(m.getLog()).thenReturn(null);

    mapper.map(m);
    provider.forceFlush().join(2, java.util.concurrent.TimeUnit.SECONDS);

    var span = exp.getFinishedSpanItems().get(0);
    assertThat(span.getParentSpanContext().isValid()).isFalse();
  }
}
