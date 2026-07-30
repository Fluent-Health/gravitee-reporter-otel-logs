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
package io.gravitee.reporter.otellogs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Pins the plugin manifest's shape.
 *
 * <p>A {@code feature} key in plugin.properties is what makes Gravitee treat a plugin as
 * licence-gated. Its absence is load-bearing for running this reporter on a community licence, and
 * it is currently absent only because nobody ever added it. This test makes that explicit so it
 * cannot be reintroduced by accident.
 */
class PluginManifestTest {

  @Test
  void manifestDeclaresNoFeatureKey() throws Exception {
    Properties props = loadManifest();

    assertThat(props.stringPropertyNames()).doesNotContain("feature");
  }

  @Test
  void manifestKeepsOurOwnPluginId() throws Exception {
    Properties props = loadManifest();

    assertThat(props.getProperty("id")).isEqualTo("otellogs");
    assertThat(props.getProperty("type")).isEqualTo("reporter");
  }

  private Properties loadManifest() throws Exception {
    Properties props = new Properties();
    try (
      InputStream in = getClass().getResourceAsStream("/plugin.properties")
    ) {
      assertThat(in).as("plugin.properties on the test classpath").isNotNull();
      props.load(in);
    }
    return props;
  }
}
