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
import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.common.Response;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.sanitize.PiiSanitizer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;

public class MetricsToLogRecordMapper {

  private final OtelLogsReporterConfiguration config;
  private final TraceContextResolver traceResolver;
  private final boolean includePayloads;
  private final boolean includeHeaders;
  private final PiiSanitizer sanitizer;

  public MetricsToLogRecordMapper(
    OtelLogsReporterConfiguration config,
    boolean includePayloads,
    boolean includeHeaders,
    PiiSanitizer sanitizer
  ) {
    this.config = config;
    this.traceResolver = new TraceContextResolver(
      config.getCorrelationHeader()
    );
    this.includePayloads = includePayloads;
    this.includeHeaders = includeHeaders;
    this.sanitizer = sanitizer == null ? PiiSanitizer.disabled() : sanitizer;
  }

  /** Back-compat: no masking, matching the default-off configuration. */
  public MetricsToLogRecordMapper(
    OtelLogsReporterConfiguration config,
    boolean includePayloads,
    boolean includeHeaders
  ) {
    this(config, includePayloads, includeHeaders, PiiSanitizer.disabled());
  }

  /** Convenience constructor for tests + back-compat: both flags off, no masking. */
  public MetricsToLogRecordMapper(OtelLogsReporterConfiguration config) {
    this(config, false, false, PiiSanitizer.disabled());
  }

  public OtelLogRecord map(Metrics m) {
    HttpHeaders headers = extractRequestHeaders(m);

    TraceContextResolver.Resolved tc = traceResolver.resolve(headers);
    String traceId = tc.traceId();
    String spanId = tc.spanId();

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
    // ONE sanitized URL for the whole record. The record body is promoted to Cloud Logging's
    // `message` field, so building it from a differently-sanitized string than the http.url
    // attribute is exactly how a raw query string used to ship next to a masked one.
    String sanitizedUrl = sanitizedUrl(m.getUri());
    String path = sanitizedUrl != null ? sanitizedUrl : "-";
    String body = method + " " + path + " → " + m.getStatus();

    if (traceId != null) {
      body += " [trace_id=" + traceId + "]";
    }
    if (sentryTraceId != null) {
      body += " [sentry_trace_id=" + sentryTraceId + "]";
    }

    return new OtelLogRecord(
      traceId,
      spanId,
      sentryTraceId,
      sentrySpanId,
      OtelLabels.severityFromStatus(m.getStatus()),
      m.getTimestamp() * 1_000_000L,
      body,
      buildAttributes(m, sanitizedUrl)
    );
  }

  /**
   * Path-templating plus value-shape masking, in that order. {@code sanitizePath} only rewrites
   * numeric/UUID path segments — it never touches the query string, which is where the credentials
   * are.
   */
  private String sanitizedUrl(String uri) {
    return sanitizer.url(OtelLabels.sanitizePath(uri));
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

  private Attributes buildAttributes(Metrics m, String sanitizedUrl) {
    AttributesBuilder b = Attributes.builder();
    if (m.getApiName() != null) b.put(
      AttributeKey.stringKey("api.name"),
      m.getApiName()
    );
    if (m.getApiType() != null) b.put(
      AttributeKey.stringKey("api.type"),
      m.getApiType()
    );
    if (m.getHttpMethod() != null) b.put(
      AttributeKey.stringKey("http.method"),
      m.getHttpMethod().name()
    );
    b.put(AttributeKey.longKey("http.status"), (long) m.getStatus());
    if (sanitizedUrl != null) {
      // Same value the record body was built from — see map(). Not gated by reportHeaders.
      b.put(AttributeKey.stringKey("http.url"), sanitizedUrl);
    }
    b.put(
      AttributeKey.longKey("http.latency_ms"),
      m.getGatewayResponseTimeMs()
    );
    b.put(
      AttributeKey.longKey("gateway.proxy_latency_ms"),
      m.getGatewayLatencyMs()
    );
    b.put(
      AttributeKey.longKey("gateway.api_latency_ms"),
      m.getEndpointResponseTimeMs()
    );
    if (m.getEndpoint() != null) {
      // The upstream URL carries the same query string as the inbound one — templating its
      // path segments without masking its values leaked the identical credentials.
      b.put(
        AttributeKey.stringKey("upstream.endpoint"),
        sanitizedUrl(m.getEndpoint())
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
    if (m.getApplicationId() != null) {
      b.put(
        AttributeKey.stringKey("context.application"),
        m.getApplicationId()
      );
    }
    if (m.getPlanId() != null) {
      b.put(AttributeKey.stringKey("context.plan"), m.getPlanId());
    }
    if (m.getSubscriptionId() != null) {
      b.put(
        AttributeKey.stringKey("context.subscription"),
        m.getSubscriptionId()
      );
    }
    if (m.getErrorMessage() != null) {
      // Policy-authored text: a gateway policy can template request data or an upstream
      // response body into its error message, so this needs the same treatment as a body.
      // Ungated by reportHeaders/reportPayloads — it is emitted on every record.
      b.put(
        AttributeKey.stringKey("error.message"),
        sanitizer.text(m.getErrorMessage())
      );
    }
    if (includeHeaders || includePayloads) {
      // Headers and bodies live on the embedded Log's entrypoint sub-objects
      // — same place LogToLogRecordMapper reads them from. Whatever arrives
      // here has already been filtered upstream by the API logging config.
      Log lg = m.getLog();
      Request req = lg != null ? lg.getEntrypointRequest() : null;
      Response resp = lg != null ? lg.getEntrypointResponse() : null;
      if (includeHeaders) {
        String reqHeadersJson = sanitizer.headersAsJson(
          req != null ? req.getHeaders() : null
        );
        if (reqHeadersJson != null) {
          b.put(AttributeKey.stringKey("http.request.headers"), reqHeadersJson);
        }
        String respHeadersJson = sanitizer.headersAsJson(
          resp != null ? resp.getHeaders() : null
        );
        if (respHeadersJson != null) {
          b.put(
            AttributeKey.stringKey("http.response.headers"),
            respHeadersJson
          );
        }
      }
      if (includePayloads) {
        if (req != null && req.getBody() != null && !req.getBody().isEmpty()) {
          b.put(
            AttributeKey.stringKey("http.request.body"),
            sanitizer.body(req.getBody())
          );
        }
        if (
          resp != null && resp.getBody() != null && !resp.getBody().isEmpty()
        ) {
          b.put(
            AttributeKey.stringKey("http.response.body"),
            sanitizer.body(resp.getBody())
          );
        }
      }
    }
    return b.build();
  }
}
