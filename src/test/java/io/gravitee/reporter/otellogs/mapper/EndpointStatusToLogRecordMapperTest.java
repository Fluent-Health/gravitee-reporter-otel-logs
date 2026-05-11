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

import io.gravitee.reporter.otellogs.OtelTestSupport;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EndpointStatusToLogRecordMapperTest {

  private EndpointStatusToLogRecordMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new EndpointStatusToLogRecordMapper();
  }

  @Test
  void availableEndpointHasSeverityInfo() {
    assertThat(
      mapper.map(OtelTestSupport.endpointStatus(true)).severity()
    ).isEqualTo(Severity.INFO);
  }

  @Test
  void unavailableEndpointHasSeverityError() {
    assertThat(
      mapper.map(OtelTestSupport.endpointStatus(false)).severity()
    ).isEqualTo(Severity.ERROR);
  }

  @Test
  void bodyDescribesTransition() {
    assertThat(
      mapper.map(OtelTestSupport.endpointStatus(true)).body()
    ).contains("UP");
    assertThat(
      mapper.map(OtelTestSupport.endpointStatus(false)).body()
    ).contains("DOWN");
  }

  @Test
  void endpointAttributesAreMapped() {
    var record = mapper.map(OtelTestSupport.endpointStatus(false));
    assertThat(
      record.attributes().get(AttributeKey.stringKey("api.name"))
    ).isEqualTo("Test API");
    assertThat(
      record.attributes().get(AttributeKey.stringKey("endpoint.url"))
    ).isEqualTo("https://backend.example.com/health");
    assertThat(
      record.attributes().get(AttributeKey.stringKey("endpoint.status"))
    ).isEqualTo("DOWN");
    assertThat(
      record.attributes().get(AttributeKey.longKey("endpoint.response_time_ms"))
    ).isEqualTo(100L);
  }
}
