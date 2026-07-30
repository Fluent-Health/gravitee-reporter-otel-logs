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

class MaskingConfigurationTest {

  @Test
  void defaultsToDisabledWithNoExtraHeaders() {
    MaskingConfiguration cfg = new MaskingConfiguration();

    assertThat(cfg.isEnabled()).isFalse();
    assertThat(cfg.getExtraHeaders()).isEmpty();
  }

  @Test
  void parsesCommaSeparatedExtraHeadersStrippingBlanks() {
    MaskingConfiguration cfg = new MaskingConfiguration();
    cfg.setExtraHeadersRaw(" X-Internal-Token , ,x-tenant-* ");

    assertThat(cfg.getExtraHeaders()).containsExactly(
      "X-Internal-Token",
      "x-tenant-*"
    );
  }

  @Test
  void treatsNullAndBlankRawValueAsEmptyList() {
    MaskingConfiguration cfg = new MaskingConfiguration();

    cfg.setExtraHeadersRaw(null);
    assertThat(cfg.getExtraHeaders()).isEmpty();

    cfg.setExtraHeadersRaw("   ");
    assertThat(cfg.getExtraHeaders()).isEmpty();
  }

  @Test
  void logsConfigurationExposesNonNullMaskingByDefault() {
    LogsConfiguration logs = new LogsConfiguration();

    assertThat(logs.getMasking()).isNotNull();
    assertThat(logs.getMasking().isEnabled()).isFalse();
  }

  @Test
  void settingNullMaskingFallsBackToDisabledInstance() {
    LogsConfiguration logs = new LogsConfiguration();
    logs.setMasking(null);

    assertThat(logs.getMasking()).isNotNull();
    assertThat(logs.getMasking().isEnabled()).isFalse();
  }
}
