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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class TraceContextResolver {

  /** Outcome of a single resolve call. */
  public record Resolved(
    String traceId,
    String spanId,
    boolean isRemoteParent
  ) {
    public static final Resolved EMPTY = new Resolved(null, null, false);

    public boolean isEmpty() {
      return traceId == null;
    }
  }

  private final String correlationHeader;

  public TraceContextResolver(String correlationHeader) {
    this.correlationHeader = correlationHeader;
  }

  public Resolved resolve(HttpHeaders headers) {
    if (headers == null) return Resolved.EMPTY;
    String tp = headers.get("traceparent");
    if (tp != null) {
      String[] parts = tp.split("-", 4);
      if (
        parts.length >= 3 && parts[1].length() == 32 && parts[2].length() == 16
      ) {
        return new Resolved(parts[1], parts[2], true);
      }
    }
    String corr = headers.get(correlationHeader);
    if (corr != null && !corr.isBlank()) {
      return new Resolved(deriveTraceId(corr), null, false);
    }
    return Resolved.EMPTY;
  }

  private static String deriveTraceId(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(
        value.getBytes(StandardCharsets.UTF_8)
      );
      StringBuilder sb = new StringBuilder(32);
      for (int i = 0; i < 16; i++) sb.append(String.format("%02x", digest[i]));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
