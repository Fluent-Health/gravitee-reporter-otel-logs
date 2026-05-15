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

import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a finished SERVER span per {@link Metrics} event, using the
 * recorded start/end timestamps so spans appear as already-complete rather
 * than running live in-process.
 */
public class MetricsToSpanMapper {

  private static final Logger log = LoggerFactory.getLogger(
    MetricsToSpanMapper.class
  );

  private final Tracer tracer;
  private final TraceContextResolver resolver;

  public MetricsToSpanMapper(Tracer tracer, TraceContextResolver resolver) {
    this.tracer = tracer;
    this.resolver = resolver;
  }

  public void map(Metrics m) {
    try {
      long endNanos = m.getTimestamp() * 1_000_000L;
      long durationNanos = m.getGatewayResponseTimeMs() * 1_000_000L;
      long startNanos = endNanos - durationNanos;

      HttpHeaders headers = extractHeaders(m);
      TraceContextResolver.Resolved tc = resolver.resolve(headers);

      var builder = tracer
        .spanBuilder("gravitee.gateway.request")
        .setSpanKind(SpanKind.SERVER)
        .setStartTimestamp(startNanos, TimeUnit.NANOSECONDS);

      Context parent = buildParentContext(tc);
      if (parent != null) builder.setParent(parent);
      else builder.setNoParent();

      Span span = builder.startSpan();
      try {
        if (m.getHttpMethod() != null) span.setAttribute(
          AttributeKey.stringKey("http.request.method"),
          m.getHttpMethod().name()
        );
        span.setAttribute(
          AttributeKey.longKey("http.response.status_code"),
          (long) m.getStatus()
        );
        if (m.getUri() != null) span.setAttribute(
          AttributeKey.stringKey("http.route"),
          m.getUri()
        );
        if (m.getApiName() != null) span.setAttribute(
          AttributeKey.stringKey("gravitee.api.name"),
          m.getApiName()
        );
        if (m.getEndpoint() != null) span.setAttribute(
          AttributeKey.stringKey("gravitee.upstream.endpoint"),
          m.getEndpoint()
        );
        if (m.getStatus() >= 500) span.setStatus(
          StatusCode.ERROR,
          "HTTP " + m.getStatus()
        );
      } finally {
        span.end(endNanos, TimeUnit.NANOSECONDS);
      }
    } catch (Throwable t) {
      log.warn("Failed to map Metrics → span: {}", t.getMessage(), t);
    }
  }

  private static HttpHeaders extractHeaders(Metrics m) {
    Log lg = m.getLog();
    if (lg == null) return null;
    var req = lg.getEntrypointRequest();
    if (req == null) return null;
    return req.getHeaders();
  }

  private static Context buildParentContext(TraceContextResolver.Resolved tc) {
    if (tc.isEmpty()) return null;
    String spanId = tc.spanId();
    if (spanId == null) {
      // Trace ID derived from correlation header: synthesise a non-zero span ID
      // so the new span inherits the trace ID via a remote parent. The synthetic
      // parent is never exported — it is just a context carrier.
      spanId = "0000000000000001";
    }
    SpanContext sc = SpanContext.createFromRemoteParent(
      tc.traceId(),
      spanId,
      TraceFlags.getSampled(),
      TraceState.getDefault()
    );
    return Context.root().with(Span.wrap(sc));
  }
}
