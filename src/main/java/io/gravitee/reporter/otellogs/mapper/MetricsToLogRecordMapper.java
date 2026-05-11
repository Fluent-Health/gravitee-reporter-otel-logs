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
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;

public class MetricsToLogRecordMapper {

  private final OtelLogsReporterConfiguration config;

  public MetricsToLogRecordMapper(OtelLogsReporterConfiguration config) {
    this.config = config;
  }

  public OtelLogRecord map(Metrics m) {
    HttpHeaders headers = extractRequestHeaders(m);

    String traceId = resolveTraceId(headers);
    String spanId = resolveSpanId(headers);

    String sentryTraceHeader = headers != null
      ? headers.get("sentry-trace")
      : null;
    var sentryTrace = OtelLabels.parseSentryTrace(sentryTraceHeader);
    String sentryTraceId = sentryTrace
      .map(OtelLabels.SentryTrace::traceId)
      .orElse(null);
    String sentrySpanId = sentryTrace
      .map(OtelLabels.SentryTrace::spanId)
      .orElse(null);

    String method = m.getHttpMethod() != null
      ? m.getHttpMethod().name()
      : "UNKNOWN";
    String path = OtelLabels.sanitizePath(m.getUri());
    String body = method + " " + path + " → " + m.getStatus();

    return new OtelLogRecord(
      traceId,
      spanId,
      sentryTraceId,
      sentrySpanId,
      OtelLabels.severityFromStatus(m.getStatus()),
      m.getTimestamp() * 1_000_000L,
      body,
      buildAttributes(m)
    );
  }

  /**
   * Extracts request headers from the embedded Log's entrypointRequest.
   * Metrics v4 does not carry raw headers directly; they live on the Log sub-object.
   */
  private HttpHeaders extractRequestHeaders(Metrics m) {
    Log log = m.getLog();
    if (log == null) return null;
    var req = log.getEntrypointRequest();
    if (req == null) return null;
    return req.getHeaders();
  }

  private String resolveTraceId(HttpHeaders headers) {
    if (headers == null) return null;
    String corrValue = headers.get(config.getCorrelationHeader());
    if (corrValue != null) {
      String normalized = OtelLabels.normalizeTraceId(corrValue);
      if (normalized != null) return normalized;
    }
    String traceparent = headers.get("traceparent");
    if (traceparent != null) {
      String[] parts = traceparent.split("-", 4);
      if (parts.length >= 3) return parts[1];
    }
    return null;
  }

  private String resolveSpanId(HttpHeaders headers) {
    if (headers == null) return null;
    String traceparent = headers.get("traceparent");
    if (traceparent != null) {
      String[] parts = traceparent.split("-", 4);
      if (parts.length >= 3) return parts[2];
    }
    return null;
  }

  private Attributes buildAttributes(Metrics m) {
    AttributesBuilder b = Attributes.builder();
    if (m.getApiName() != null) b.put(
      AttributeKey.stringKey("api.name"),
      m.getApiName()
    );
    if (m.getApiId() != null) b.put(
      AttributeKey.stringKey("api.id"),
      m.getApiId()
    );
    if (m.getHttpMethod() != null) b.put(
      AttributeKey.stringKey("http.method"),
      m.getHttpMethod().name()
    );
    b.put(AttributeKey.longKey("http.status"), (long) m.getStatus());
    b.put(
      AttributeKey.longKey("http.latency_ms"),
      m.getGatewayResponseTimeMs()
    );
    if (m.getEndpoint() != null) {
      b.put(
        AttributeKey.stringKey("upstream.endpoint"),
        OtelLabels.sanitizePath(m.getEndpoint())
      );
    }
    if (m.getRequestContentLength() > 0) {
      b.put(
        AttributeKey.longKey("entrypoint.request.content_length"),
        m.getRequestContentLength()
      );
    }
    if (m.getResponseContentLength() > 0) {
      b.put(
        AttributeKey.longKey("entrypoint.response.content_length"),
        m.getResponseContentLength()
      );
    }
    return b.build();
  }
}
