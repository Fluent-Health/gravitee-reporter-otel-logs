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

import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Severity;

public class MessageMetricsToLogRecordMapper {

  public OtelLogRecord map(MessageMetrics mm) {
    long errorCount = mm.getErrorCount();
    long count = mm.getCount();
    String body =
      "Messages: " +
      count +
      (errorCount > 0
          ? " (" + errorCount + " error" + (errorCount > 1 ? "s" : "") + ")"
          : "");

    AttributesBuilder b = Attributes.builder();
    if (mm.getApiName() != null) b.put(
      AttributeKey.stringKey("api.name"),
      mm.getApiName()
    );
    b.put(AttributeKey.longKey("message.count"), count);
    b.put(AttributeKey.longKey("message.error_count"), errorCount);
    // MessageMetrics exposes a single content-length field (not separate in/out byte counts).
    // The spec attributes message.bytes_in / message.bytes_out are not individually available
    // in this API version; map the available field to message.content_length instead.
    if (mm.getContentLength() > 0) {
      b.put(
        AttributeKey.longKey("message.content_length"),
        mm.getContentLength()
      );
    }

    return new OtelLogRecord(
      null,
      null,
      null,
      null,
      errorCount > 0 ? Severity.WARN : Severity.INFO,
      mm.getTimestamp() * 1_000_000L,
      body,
      b.build()
    );
  }
}
