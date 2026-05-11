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
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.gravitee.reporter.otellogs.OtelTestSupport;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogToLogRecordMapperTest {

  private LogToLogRecordMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new LogToLogRecordMapper();
  }

  @Test
  void bodyContainsMethodAndPath() {
    var record = mapper.map(OtelTestSupport.log(201));
    assertThat(record.body()).isEqualTo("POST /api/v1/data → 201");
  }

  @Test
  void status201HasSeverityInfo() {
    assertThat(mapper.map(OtelTestSupport.log(201)).severity()).isEqualTo(
      Severity.INFO
    );
  }

  @Test
  void status500HasSeverityError() {
    assertThat(mapper.map(OtelTestSupport.log(500)).severity()).isEqualTo(
      Severity.ERROR
    );
  }

  @Test
  void apiAttributesAreMapped() {
    var record = mapper.map(OtelTestSupport.log(200));
    assertThat(
      record.attributes().get(AttributeKey.stringKey("api.name"))
    ).isEqualTo("Test API");
    assertThat(
      record.attributes().get(AttributeKey.stringKey("api.id"))
    ).isEqualTo("api-123");
  }

  @Test
  void httpMethodAndStatusAreMapped() {
    var record = mapper.map(OtelTestSupport.log(200));
    assertThat(
      record.attributes().get(AttributeKey.stringKey("http.method"))
    ).isEqualTo("POST");
    assertThat(
      record.attributes().get(AttributeKey.longKey("http.status"))
    ).isEqualTo(200L);
  }

  @Test
  void requestHeadersCountIsMapped() {
    // log() fixture sets 2 request headers: Content-Type and X-Request-ID
    var record = mapper.map(OtelTestSupport.log(200));
    assertThat(
      record.attributes().get(AttributeKey.longKey("log.request.headers_count"))
    ).isEqualTo(2L);
  }

  @Test
  void responseHeadersCountIsMapped() {
    // log() fixture sets 1 response header: Content-Type
    var record = mapper.map(OtelTestSupport.log(200));
    assertThat(
      record
        .attributes()
        .get(AttributeKey.longKey("log.response.headers_count"))
    ).isEqualTo(1L);
  }

  @Test
  void nullResponseProducesBodyWithoutArrow() {
    var record = mapper.map(OtelTestSupport.logWithNullResponse());
    assertThat(record.body()).isEqualTo("POST /api/v1/data");
  }

  @Test
  void nullResponseProducesSeverityWarn() {
    var record = mapper.map(OtelTestSupport.logWithNullResponse());
    assertThat(record.severity()).isEqualTo(Severity.WARN);
  }

  @Test
  void nullEntrypointRequestProducesNoNpe() {
    assertThatNoException().isThrownBy(() ->
      mapper.map(OtelTestSupport.logWithNullRequest(200))
    );
    var record = mapper.map(OtelTestSupport.logWithNullRequest(200));
    assertThat(
      record.attributes().get(AttributeKey.longKey("log.request.headers_count"))
    ).isNull();
  }

  @Test
  void nullUriProducesDashInBody() {
    var record = mapper.map(OtelTestSupport.logWithNullUri(200));
    assertThat(record.body()).doesNotContain("null");
    assertThat(record.body()).contains("-");
  }
}
