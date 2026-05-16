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
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Severity;

/**
 * Maps a Gravitee v4 {@link Log} (entrypoint request/response metadata) to an {@link OtelLogRecord}.
 *
 * <p>The Log v4 class stores metadata on sub-objects (entrypointRequest / entrypointResponse).
 * Raw HTTP method, URI, and response status come from those sub-objects.
 *
 * <p>Three compliance-sensitive flags control what extra data is forwarded:
 * <ul>
 *   <li>{@code includeHeaders} — attach JSON-encoded request/response header maps as
 *       {@code http.request.headers} / {@code http.response.headers}.
 *   <li>{@code includePayloads} — attach raw bodies as {@code http.request.body} /
 *       {@code http.response.body}.
 *   <li>{@code includeAuthClaims} — decode the Bearer JWT in {@code Authorization}
 *       and attach {@code auth.aud}, {@code auth.sub}, {@code auth.iss}, {@code auth.exp}.
 * </ul>
 * All default to false and must be combined with {@code logs.reportRequestLogs: true}
 * to take effect. They rely on the upstream API logging filter to omit anything
 * sensitive — this mapper does not scrub.
 */
public class LogToLogRecordMapper {

  private final boolean includePayloads;
  private final boolean includeHeaders;
  private final boolean includeAuthClaims;

  public LogToLogRecordMapper(
    boolean includePayloads,
    boolean includeHeaders,
    boolean includeAuthClaims
  ) {
    this.includePayloads = includePayloads;
    this.includeHeaders = includeHeaders;
    this.includeAuthClaims = includeAuthClaims;
  }

  /** Back-compat constructor used by tests that don't exercise auth claims. */
  public LogToLogRecordMapper(boolean includePayloads, boolean includeHeaders) {
    this(includePayloads, includeHeaders, false);
  }

  public OtelLogRecord map(Log log) {
    Request req = log.getEntrypointRequest();
    Response resp = log.getEntrypointResponse();

    String method = (req != null && req.getMethod() != null)
      ? req.getMethod().name()
      : "UNKNOWN";
    String uri = (req != null) ? req.getUri() : null;
    String rawPath = OtelLabels.sanitizePath(uri);
    String path = rawPath != null ? rawPath : "-";
    int status = (resp != null) ? resp.getStatus() : 0;

    String statusPart = (resp != null) ? " → " + status : "";
    String body = method + " " + path + statusPart;

    AttributesBuilder b = Attributes.builder();
    if (log.getApiName() != null) b.put(
      AttributeKey.stringKey("api.name"),
      log.getApiName()
    );
    if (req != null && req.getMethod() != null) b.put(
      AttributeKey.stringKey("http.method"),
      req.getMethod().name()
    );
    if (rawPath != null) {
      // Promoted to httpRequest.requestUrl by GclLogRecordExporter so Cloud
      // Logging renders the full method+url+status chip line on Log-derived
      // records just like it does for Metrics-derived records.
      b.put(AttributeKey.stringKey("http.url"), rawPath);
    }
    if (status > 0) b.put(AttributeKey.longKey("http.status"), (long) status);
    if (req != null && req.getHeaders() != null) {
      b.put(
        AttributeKey.longKey("log.request.headers_count"),
        (long) req.getHeaders().size()
      );
    }
    if (resp != null && resp.getHeaders() != null) {
      b.put(
        AttributeKey.longKey("log.response.headers_count"),
        (long) resp.getHeaders().size()
      );
    }
    if (includeHeaders) {
      String reqHeadersJson = OtelLabels.headersAsJson(
        req != null ? req.getHeaders() : null
      );
      if (reqHeadersJson != null) {
        b.put(AttributeKey.stringKey("http.request.headers"), reqHeadersJson);
      }
      String respHeadersJson = OtelLabels.headersAsJson(
        resp != null ? resp.getHeaders() : null
      );
      if (respHeadersJson != null) {
        b.put(AttributeKey.stringKey("http.response.headers"), respHeadersJson);
      }
    }
    if (includePayloads) {
      if (req != null && req.getBody() != null && !req.getBody().isEmpty()) {
        b.put(AttributeKey.stringKey("http.request.body"), req.getBody());
      }
      if (resp != null && resp.getBody() != null && !resp.getBody().isEmpty()) {
        b.put(AttributeKey.stringKey("http.response.body"), resp.getBody());
      }
    }
    if (includeAuthClaims) {
      HttpHeaders headers = (req != null) ? req.getHeaders() : null;
      String authHeader = (headers != null)
        ? headers.get("Authorization")
        : null;
      JwtClaims.fromAuthorizationHeader(authHeader).ifPresent(claims -> {
        if (claims.aud() != null) {
          b.put(AttributeKey.stringKey("auth.aud"), claims.aud());
        }
        if (claims.sub() != null) {
          b.put(AttributeKey.stringKey("auth.sub"), claims.sub());
        }
        if (claims.iss() != null) {
          b.put(AttributeKey.stringKey("auth.iss"), claims.iss());
        }
        if (claims.exp() != null) {
          b.put(AttributeKey.longKey("auth.exp"), claims.exp());
        }
      });
    }

    return new OtelLogRecord(
      null,
      null,
      null,
      null,
      status == 0 ? Severity.WARN : OtelLabels.severityFromStatus(status),
      log.getTimestamp() * 1_000_000L,
      body,
      b.build()
    );
  }
}
