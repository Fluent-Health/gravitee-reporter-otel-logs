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

import io.gravitee.gateway.api.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TraceContextResolverTest {

  private final TraceContextResolver resolver = new TraceContextResolver(
    "X-Request-ID"
  );

  @Test
  void w3c_traceparent_wins_over_correlation_header() {
    HttpHeaders h = Mockito.mock(HttpHeaders.class);
    Mockito.when(h.get("X-Request-ID")).thenReturn("ignored-value");
    Mockito.when(h.get("traceparent")).thenReturn(
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
    );

    TraceContextResolver.Resolved r = resolver.resolve(h);

    assertThat(r.traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    assertThat(r.spanId()).isEqualTo("b7ad6b7169203331");
    assertThat(r.isRemoteParent()).isTrue();
  }

  @Test
  void correlation_header_only_yields_deterministic_trace_id() {
    HttpHeaders h = Mockito.mock(HttpHeaders.class);
    Mockito.when(h.get("X-Request-ID")).thenReturn("req-abc-123");

    TraceContextResolver.Resolved r1 = resolver.resolve(h);
    TraceContextResolver.Resolved r2 = resolver.resolve(h);

    assertThat(r1.traceId()).isEqualTo(r2.traceId()); // deterministic
    assertThat(r1.traceId()).matches("[0-9a-f]{32}"); // 16 bytes hex
    assertThat(r1.spanId()).isNull(); // no upstream span
    assertThat(r1.isRemoteParent()).isFalse();
  }

  @Test
  void neither_present_returns_empty() {
    HttpHeaders h = Mockito.mock(HttpHeaders.class);
    Mockito.when(h.get("X-Request-ID")).thenReturn(null);
    Mockito.when(h.get("traceparent")).thenReturn(null);

    assertThat(resolver.resolve(h).isEmpty()).isTrue();
  }

  @Test
  void null_headers_returns_empty() {
    assertThat(resolver.resolve(null).isEmpty()).isTrue();
  }

  @Test
  void malformed_traceparent_falls_back_to_correlation_header() {
    HttpHeaders h = Mockito.mock(HttpHeaders.class);
    Mockito.when(h.get("X-Request-ID")).thenReturn("req-xyz");
    Mockito.when(h.get("traceparent")).thenReturn("garbage");

    TraceContextResolver.Resolved r = resolver.resolve(h);

    assertThat(r.traceId()).matches("[0-9a-f]{32}");
    assertThat(r.spanId()).isNull();
  }
}
