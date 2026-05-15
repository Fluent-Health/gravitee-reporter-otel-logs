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

import org.junit.jupiter.api.Test;

class ResourceConfigurationTest {

  /**
   * Setter-getter round-trip across every field. Cheap smoke test for accidental
   * field-mixup or rename mistakes (e.g. a setter writing to the wrong field).
   * The actual {@code @Value} defaults are exercised by integration tests, not
   * here — Spring's defaults only inject inside a Spring context.
   */
  @Test
  void field_round_trip() {
    ResourceConfiguration cfg = new ResourceConfiguration();
    cfg.setServiceName("gravitee-api-gateway");
    cfg.setServiceNamespace("apim");
    cfg.setGcpProjectId(null);
    cfg.setGcpAutoDetect(true);

    assertThat(cfg.getServiceName()).isEqualTo("gravitee-api-gateway");
    assertThat(cfg.getServiceNamespace()).isEqualTo("apim");
    assertThat(cfg.getGcpProjectId()).isNull();
    assertThat(cfg.isGcpAutoDetect()).isTrue();
  }
}
