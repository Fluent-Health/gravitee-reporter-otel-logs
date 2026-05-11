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

import io.opentelemetry.api.logs.Severity;
import org.junit.jupiter.api.Test;

class OtelLabelsTest {

  // ===== normalizeTraceId =====

  @Test
  void uuidBecomesLowercase32HexTraceId() {
    String result = OtelLabels.normalizeTraceId(
      "550e8400-e29b-41d4-a716-446655440000"
    );
    assertThat(result).isEqualTo("550e8400e29b41d4a716446655440000");
  }

  @Test
  void already32HexIsReturnedAsLowercase() {
    String result = OtelLabels.normalizeTraceId(
      "550E8400E29B41D4A716446655440000"
    );
    assertThat(result).isEqualTo("550e8400e29b41d4a716446655440000");
  }

  @Test
  void nullTraceIdReturnsNull() {
    assertThat(OtelLabels.normalizeTraceId(null)).isNull();
  }

  @Test
  void nonHexStringReturnsNull() {
    assertThat(OtelLabels.normalizeTraceId("not-a-trace-id")).isNull();
  }

  @Test
  void blankTraceIdReturnsNull() {
    assertThat(OtelLabels.normalizeTraceId("   ")).isNull();
  }

  // ===== parseSentryTrace =====

  @Test
  void validSentryTraceHeaderParsesTraceAndSpanId() {
    String[] parts = OtelLabels.parseSentryTrace(
      "771a43a4192642f0b136d5159a501700-7d17675a3e4f44e8-1"
    ).orElseThrow();
    assertThat(parts[0]).isEqualTo("771a43a4192642f0b136d5159a501700");
    assertThat(parts[1]).isEqualTo("7d17675a3e4f44e8");
  }

  @Test
  void nullSentryTraceHeaderReturnsEmpty() {
    assertThat(OtelLabels.parseSentryTrace(null)).isEmpty();
  }

  @Test
  void blankSentryTraceHeaderReturnsEmpty() {
    assertThat(OtelLabels.parseSentryTrace("")).isEmpty();
  }

  @Test
  void tooFewSentryTraceSegmentsReturnsEmpty() {
    assertThat(OtelLabels.parseSentryTrace("onlyone")).isEmpty();
  }

  // ===== severityFromStatus =====

  @Test
  void status200IsSeverityInfo() {
    assertThat(OtelLabels.severityFromStatus(200)).isEqualTo(Severity.INFO);
  }

  @Test
  void status400IsSeverityWarn() {
    assertThat(OtelLabels.severityFromStatus(400)).isEqualTo(Severity.WARN);
  }

  @Test
  void status404IsSeverityWarn() {
    assertThat(OtelLabels.severityFromStatus(404)).isEqualTo(Severity.WARN);
  }

  @Test
  void status500IsSeverityError() {
    assertThat(OtelLabels.severityFromStatus(500)).isEqualTo(Severity.ERROR);
  }

  @Test
  void status503IsSeverityError() {
    assertThat(OtelLabels.severityFromStatus(503)).isEqualTo(Severity.ERROR);
  }

  // ===== sanitizePath =====

  @Test
  void numericPathSegmentIsReplacedWithId() {
    assertThat(OtelLabels.sanitizePath("/api/v1/users/42")).isEqualTo(
      "/api/v1/users/{id}"
    );
  }

  @Test
  void uuidPathSegmentIsReplacedWithId() {
    assertThat(
      OtelLabels.sanitizePath(
        "/api/v1/items/550e8400-e29b-41d4-a716-446655440000"
      )
    ).isEqualTo("/api/v1/items/{id}");
  }

  @Test
  void pathWithNoIdsIsUnchanged() {
    assertThat(OtelLabels.sanitizePath("/api/v1/health")).isEqualTo(
      "/api/v1/health"
    );
  }

  @Test
  void nullPathReturnsNull() {
    assertThat(OtelLabels.sanitizePath(null)).isNull();
  }
}
