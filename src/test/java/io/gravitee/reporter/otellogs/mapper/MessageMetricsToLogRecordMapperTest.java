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

import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.otellogs.OtelTestSupport;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageMetricsToLogRecordMapperTest {

  private MessageMetricsToLogRecordMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new MessageMetricsToLogRecordMapper();
  }

  @Test
  void severityIsWarnWhenErrorsExist() {
    // messageMetrics() fixture has errorCount=1, so severity must be WARN
    assertThat(
      mapper.map(OtelTestSupport.messageMetrics()).severity()
    ).isEqualTo(Severity.WARN);
  }

  @Test
  void severityIsInfoWhenNoErrors() {
    var mm = MessageMetrics.builder()
      .apiId("api-123")
      .apiName("Test API")
      .count(5L)
      .errorCount(0L)
      .build();
    assertThat(mapper.map(mm).severity()).isEqualTo(Severity.INFO);
  }

  @Test
  void bodyDescribesMessageCounts() {
    var record = mapper.map(OtelTestSupport.messageMetrics());
    assertThat(record.body()).contains("10").contains("1 error");
  }

  @Test
  void apiAttributesAreMapped() {
    var record = mapper.map(OtelTestSupport.messageMetrics());
    assertThat(
      record.attributes().get(AttributeKey.stringKey("api.name"))
    ).isEqualTo("Test API");
    assertThat(
      record.attributes().get(AttributeKey.stringKey("api.id"))
    ).isEqualTo("api-123");
  }

  @Test
  void messageCountAttributesAreMapped() {
    var record = mapper.map(OtelTestSupport.messageMetrics());
    assertThat(
      record.attributes().get(AttributeKey.longKey("message.count"))
    ).isEqualTo(10L);
    assertThat(
      record.attributes().get(AttributeKey.longKey("message.error_count"))
    ).isEqualTo(1L);
  }

  @Test
  void contentLengthAttributeIsMappedWhenPresent() {
    // messageMetrics() fixture sets contentLength=2048
    var record = mapper.map(OtelTestSupport.messageMetrics());
    assertThat(
      record.attributes().get(AttributeKey.longKey("message.content_length"))
    ).isEqualTo(2048L);
  }

  @Test
  void contentLengthAttributeIsAbsentWhenZero() {
    var mm = MessageMetrics.builder()
      .apiId("api-123")
      .apiName("Test API")
      .count(5L)
      .errorCount(0L)
      .contentLength(0L)
      .build();
    var record = mapper.map(mm);
    assertThat(
      record.attributes().get(AttributeKey.longKey("message.content_length"))
    ).isNull();
  }
}
