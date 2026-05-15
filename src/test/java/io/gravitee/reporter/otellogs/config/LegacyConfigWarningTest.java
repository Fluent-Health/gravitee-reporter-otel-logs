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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

class LegacyConfigWarningTest {

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void attachAppender() {
    appender = new ListAppender<>();
    appender.start();
    logger = (Logger) LoggerFactory.getLogger(LegacyConfigWarning.class);
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
  }

  @Test
  void warns_once_for_each_legacy_key_in_use() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("reporters.otellogs.exporter", "gcloud");
    env.setProperty("reporters.otellogs.gcloud.projectId", "my-project");
    env.setProperty("reporters.otellogs.reportLogs", "true");

    new LegacyConfigWarning(env).run();

    assertThat(appender.list)
      .filteredOn(e -> e.getLevel() == Level.WARN)
      .extracting(ILoggingEvent::getFormattedMessage)
      .anyMatch(
        m ->
          m.contains("reporters.otellogs.exporter") &&
          m.contains("reporters.otellogs.logs.exporter")
      )
      .anyMatch(
        m ->
          m.contains("reporters.otellogs.gcloud.projectId") &&
          m.contains("reporters.otellogs.resource.gcp.projectId")
      )
      .anyMatch(
        m ->
          m.contains("reporters.otellogs.reportLogs") &&
          m.contains("reporters.otellogs.logs.reportRequestLogs")
      )
      .hasSize(3);
  }

  @Test
  void no_warnings_when_no_legacy_keys_set() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("reporters.otellogs.logs.exporter", "otlp");

    new LegacyConfigWarning(env).run();

    assertThat(appender.list)
      .filteredOn(e -> e.getLevel() == Level.WARN)
      .isEmpty();
  }
}
