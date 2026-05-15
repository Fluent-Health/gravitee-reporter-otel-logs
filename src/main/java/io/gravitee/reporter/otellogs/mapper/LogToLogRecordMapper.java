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
 * <p>Note: The v4 Log class stores metadata on sub-objects (entrypointRequest / entrypointResponse).
 * Raw HTTP method, URI, and response status come from those sub-objects.
 * Content lengths are not exposed by the v4 Log API and are therefore omitted.
 */
public class LogToLogRecordMapper {

  private final boolean includePayloads;

  public LogToLogRecordMapper(boolean includePayloads) {
    this.includePayloads = includePayloads;
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
    if (includePayloads) {
      if (req != null && req.getBody() != null && !req.getBody().isEmpty()) {
        b.put(AttributeKey.stringKey("http.request.body"), req.getBody());
      }
      if (resp != null && resp.getBody() != null && !resp.getBody().isEmpty()) {
        b.put(AttributeKey.stringKey("http.response.body"), resp.getBody());
      }
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
