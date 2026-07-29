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
package io.gravitee.reporter.otellogs.integration;

import java.time.Duration;

/**
 * Container image versions shared by the integration tests.
 *
 * <p>CI runs the suite across a matrix of supported APIM releases — see
 * {@code .github/workflows/integration-matrix.yml}. Keeping the value here means every IT moves
 * together and a version can never be bumped in one and forgotten in another.
 */
final class TestVersions {

  /** Override locally with {@code -Dapim.version=4.12.0}. */
  static final String APIM = System.getProperty("apim.version", "4.12.12");

  /**
   * How long to allow an APIM container to become healthy.
   *
   * <p>Testcontainers defaults to 60s, which the management API and gateway routinely exceed on a
   * loaded machine or a cold image — producing a {@code ContainerLaunchException: Timed out
   * waiting for URL to be accessible} that looks like a product failure but is only a slow boot.
   */
  static final Duration CONTAINER_STARTUP_TIMEOUT = Duration.ofSeconds(300);

  private TestVersions() {}
}
