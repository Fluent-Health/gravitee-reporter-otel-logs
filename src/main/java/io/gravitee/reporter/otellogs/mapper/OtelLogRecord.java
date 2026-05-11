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

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;

/**
 * Intermediate value object produced by mappers and consumed by OtelLogWriter.
 * All fields except severity, timestampEpochNanos, body, and attributes may be null.
 */
public record OtelLogRecord(
  String traceId, // 32 lowercase hex chars; null if no trace context
  String spanId, // 16 lowercase hex chars; null if unavailable
  String sentryTraceId, // from sentry-trace header; null if absent
  String sentrySpanId, // from sentry-trace header; null if absent
  Severity severity,
  long timestampEpochNanos,
  String body,
  Attributes attributes
) {
  public OtelLogRecord {
    if (attributes == null) attributes = Attributes.empty();
  }
}
