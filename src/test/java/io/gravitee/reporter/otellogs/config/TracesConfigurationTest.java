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

class TracesConfigurationTest {

  /**
   * Setter-getter round-trip across every field. Cheap smoke test for accidental
   * field-mixup or rename mistakes (e.g. a setter writing to the wrong field).
   * The actual {@code @Value} defaults are exercised by integration tests, not
   * here — Spring's defaults only inject inside a Spring context.
   */
  @Test
  void field_round_trip() {
    TracesConfiguration cfg = new TracesConfiguration();
    cfg.setEnabled(false);
    cfg.setExporter("otlp");
    cfg.setEndpoint("https://telemetry.googleapis.com/v1/traces");
    cfg.setAuthMode("gcp-adc");
    cfg.setBatchSize(512);
    cfg.setScheduledDelayMs(5000);
    cfg.setSampler("parent-based-always-on");
    cfg.setSampleRatio(1.0);

    assertThat(cfg.isEnabled()).isFalse();
    assertThat(cfg.getExporter()).isEqualTo("otlp");
    assertThat(cfg.getEndpoint()).isEqualTo(
      "https://telemetry.googleapis.com/v1/traces"
    );
    assertThat(cfg.getAuthMode()).isEqualTo("gcp-adc");
    assertThat(cfg.getBatchSize()).isEqualTo(512);
    assertThat(cfg.getScheduledDelayMs()).isEqualTo(5000);
    assertThat(cfg.getSampler()).isEqualTo("parent-based-always-on");
    assertThat(cfg.getSampleRatio()).isEqualTo(1.0);
  }

  @Test
  void headers_setter_is_defensively_copied() {
    TracesConfiguration cfg = new TracesConfiguration();
    HashMap<String, String> src = new HashMap<>();
    src.put("Authorization", "Bearer tok");
    cfg.setHeaders(src);
    src.put("Authorization", "Bearer mutated");

    assertThat(cfg.getHeaders()).containsEntry("Authorization", "Bearer tok");

    cfg.setHeaders(null);
    assertThat(cfg.getHeaders()).isEmpty();
  }
}
