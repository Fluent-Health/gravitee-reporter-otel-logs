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
    mapper = new LogToLogRecordMapper(false, false);
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
    assertThat(record.attributes().get(AttributeKey.stringKey("api.id")))
      .as("api.id should not be emitted")
      .isNull();
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

  @Test
  void payloadsAreOmittedWhenFlagDisabled() {
    var log = OtelTestSupport.log(200);
    log.getEntrypointRequest().setBody("{\"hello\":\"world\"}");
    log.getEntrypointResponse().setBody("{\"ok\":true}");

    var record = mapper.map(log);

    assertThat(
      record.attributes().get(AttributeKey.stringKey("http.request.body"))
    )
      .as("request body must be absent when reportPayloads=false")
      .isNull();
    assertThat(
      record.attributes().get(AttributeKey.stringKey("http.response.body"))
    )
      .as("response body must be absent when reportPayloads=false")
      .isNull();
  }

  @Test
  void payloadsAreEmittedWhenFlagEnabled() {
    var enabledMapper = new LogToLogRecordMapper(true, false);
    var log = OtelTestSupport.log(200);
    log.getEntrypointRequest().setBody("{\"hello\":\"world\"}");
    log.getEntrypointResponse().setBody("{\"ok\":true}");

    var record = enabledMapper.map(log);

    assertThat(
      record.attributes().get(AttributeKey.stringKey("http.request.body"))
    ).isEqualTo("{\"hello\":\"world\"}");
    assertThat(
      record.attributes().get(AttributeKey.stringKey("http.response.body"))
    ).isEqualTo("{\"ok\":true}");
  }

  @Test
  void emptyOrNullPayloadsAreSkippedEvenWhenFlagEnabled() {
    var enabledMapper = new LogToLogRecordMapper(true, false);
    var log = OtelTestSupport.log(200);
    // request body left null; response body explicitly empty
    log.getEntrypointResponse().setBody("");

    var record = enabledMapper.map(log);

    assertThat(
      record.attributes().get(AttributeKey.stringKey("http.request.body"))
    ).isNull();
    assertThat(
      record.attributes().get(AttributeKey.stringKey("http.response.body"))
    ).isNull();
  }

  @Test
  void httpUrlIsEmittedSoCloudLoggingRendersChips() {
    // http.url is what GclLogRecordExporter promotes to httpRequest.requestUrl,
    // which is the field Cloud Logging needs to render the full chip line.
    var record = mapper.map(OtelTestSupport.log(200));
    assertThat(
      record.attributes().get(AttributeKey.stringKey("http.url"))
    ).isEqualTo("/api/v1/data");
  }

  @Test
  void headersAreOmittedWhenFlagDisabled() {
    // log() fixture sets two request headers (Content-Type, X-Request-ID)
    // and one response header (Content-Type).
    var record = mapper.map(OtelTestSupport.log(200));
    assertThat(
      record.attributes().get(AttributeKey.stringKey("http.request.headers"))
    )
      .as("request headers must be absent when reportHeaders=false")
      .isNull();
    assertThat(
      record.attributes().get(AttributeKey.stringKey("http.response.headers"))
    )
      .as("response headers must be absent when reportHeaders=false")
      .isNull();
  }

  @Test
  void headersAreEmittedAsJsonWhenFlagEnabled() {
    var enabledMapper = new LogToLogRecordMapper(false, true);
    var record = enabledMapper.map(OtelTestSupport.log(200));

    String reqJson = record
      .attributes()
      .get(AttributeKey.stringKey("http.request.headers"));
    String respJson = record
      .attributes()
      .get(AttributeKey.stringKey("http.response.headers"));

    assertThat(reqJson)
      .as("request headers should be JSON-encoded")
      .contains("\"Content-Type\"")
      .contains("\"application/json\"")
      .contains("\"X-Request-ID\"")
      .contains("\"test-req-id\"");
    assertThat(respJson)
      .as("response headers should be JSON-encoded")
      .contains("\"Content-Type\"")
      .contains("\"application/json\"");
  }
}
