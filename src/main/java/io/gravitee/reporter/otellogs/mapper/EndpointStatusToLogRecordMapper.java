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

import io.gravitee.reporter.api.health.EndpointStatus;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Severity;

public class EndpointStatusToLogRecordMapper {

  public OtelLogRecord map(EndpointStatus es) {
    String statusLabel = es.isAvailable() ? "UP" : "DOWN";
    String endpoint = es.getEndpoint() != null ? es.getEndpoint() : "unknown";
    String body = "Endpoint " + endpoint + " is " + statusLabel;

    AttributesBuilder b = Attributes.builder();
    if (es.getApiName() != null) b.put(
      AttributeKey.stringKey("api.name"),
      es.getApiName()
    );
    b.put(AttributeKey.stringKey("endpoint.url"), endpoint);
    b.put(AttributeKey.stringKey("endpoint.status"), statusLabel);
    b.put(
      AttributeKey.longKey("endpoint.response_time_ms"),
      es.getResponseTime()
    );

    return new OtelLogRecord(
      null,
      null,
      null,
      null,
      es.isAvailable() ? Severity.INFO : Severity.ERROR,
      es.getTimestamp() * 1_000_000L,
      body,
      b.build()
    );
  }
}
