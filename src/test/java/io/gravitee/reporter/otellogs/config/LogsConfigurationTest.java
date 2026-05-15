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
package io.gravitee.reporter.otellogs.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import org.junit.jupiter.api.Test;

class LogsConfigurationTest {

  /**
   * Setter-getter round-trip across every field. Cheap smoke test for accidental
   * field-mixup or rename mistakes (e.g. a setter writing to the wrong field).
   * The actual {@code @Value} defaults are exercised by integration tests, not
   * here — Spring's defaults only inject inside a Spring context.
   */
  @Test
  void field_round_trip() {
    LogsConfiguration cfg = new LogsConfiguration();
    cfg.setEnabled(true);
    cfg.setExporter("gcloud");
    cfg.setLogName("gravitee-api-gateway");
    cfg.setEndpoint("http://localhost:4317");
    cfg.setAuthMode("none");
    cfg.setBatchSize(512);
    cfg.setScheduledDelayMs(5000);
    cfg.setReportHealthChecks(true);
    cfg.setReportRequestLogs(false);
    cfg.setReportMessageMetrics(true);

    assertThat(cfg.isEnabled()).isTrue();
    assertThat(cfg.getExporter()).isEqualTo("gcloud");
    assertThat(cfg.getLogName()).isEqualTo("gravitee-api-gateway");
    assertThat(cfg.getEndpoint()).isEqualTo("http://localhost:4317");
    assertThat(cfg.getAuthMode()).isEqualTo("none");
    assertThat(cfg.getBatchSize()).isEqualTo(512);
    assertThat(cfg.getScheduledDelayMs()).isEqualTo(5000);
    assertThat(cfg.isReportHealthChecks()).isTrue();
    assertThat(cfg.isReportRequestLogs()).isFalse();
    assertThat(cfg.isReportMessageMetrics()).isTrue();
  }

  @Test
  void headers_setter_is_defensively_copied() {
    LogsConfiguration cfg = new LogsConfiguration();
    HashMap<String, String> src = new HashMap<>();
    src.put("Authorization", "Bearer tok");
    cfg.setHeaders(src);
    src.put("Authorization", "Bearer mutated");

    assertThat(cfg.getHeaders()).containsEntry("Authorization", "Bearer tok");

    cfg.setHeaders(null);
    assertThat(cfg.getHeaders()).isEmpty();
  }
}
