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
package io.gravitee.reporter.otellogs.sanitize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SensitiveKeysTest {

  private final SensitiveKeys keys = SensitiveKeys.withDefaults();

  @Test
  void matchesCredentialHeadersExactly() {
    assertThat(keys.isSensitive("Authorization")).isTrue();
    assertThat(keys.isSensitive("Proxy-Authorization")).isTrue();
    assertThat(keys.isSensitive("Cookie")).isTrue();
    assertThat(keys.isSensitive("Set-Cookie")).isTrue();
    assertThat(keys.isSensitive("X-Gravitee-Api-Key")).isTrue();
  }

  @Test
  void matchesCaseInsensitively() {
    assertThat(keys.isSensitive("AUTHORIZATION")).isTrue();
    assertThat(keys.isSensitive("authorization")).isTrue();
    assertThat(keys.isSensitive("aUtHoRiZaTiOn")).isTrue();
  }

  @Test
  void matchesHeadersInfraInjectsToday() {
    // apim-clevertap/main.tf:69-71 — covered by the x-clevertap-* prefix entry
    assertThat(keys.isSensitive("X-CleverTap-Account-Id")).isTrue();
    assertThat(keys.isSensitive("X-CleverTap-Passcode")).isTrue();
    // apim-infra-automator/main.tf:374 — a raw JWT read from an inbound header
    assertThat(keys.isSensitive("X-ZDesk-JWT")).isTrue();
  }

  @Test
  void matchesFhirIdentifierHeaderNames() {
    assertThat(keys.isSensitive("email")).isTrue();
    assertThat(keys.isSensitive("family")).isTrue();
    assertThat(keys.isSensitive("postalCode")).isTrue();
  }

  @Test
  void leavesOrdinaryHeadersAlone() {
    assertThat(keys.isSensitive("Content-Type")).isFalse();
    assertThat(keys.isSensitive("Accept")).isFalse();
    assertThat(keys.isSensitive("X-Request-ID")).isFalse();
    assertThat(keys.isSensitive("traceparent")).isFalse();
    assertThat(keys.isSensitive("sentry-trace")).isFalse();
    // Guards against the prefix entries being written too loosely.
    assertThat(keys.isSensitive("Accept-Language")).isFalse();
    assertThat(keys.isSensitive("Content-Language")).isFalse();
  }

  @Test
  void toleratesNullAndBlankNames() {
    assertThat(keys.isSensitive(null)).isFalse();
    assertThat(keys.isSensitive("")).isFalse();
  }

  @Test
  void addsExtraHeadersFromConfiguration() {
    SensitiveKeys extended = new SensitiveKeys(List.of("X-Internal-Secret"));

    assertThat(extended.isSensitive("x-internal-secret")).isTrue();
    assertThat(keys.isSensitive("x-internal-secret")).isFalse();
  }

  @Test
  void supportsTrailingWildcardInExtraHeaders() {
    SensitiveKeys extended = new SensitiveKeys(List.of("X-Tenant-*"));

    assertThat(extended.isSensitive("X-Tenant-Id")).isTrue();
    assertThat(extended.isSensitive("X-Tenant-Secret")).isTrue();
    assertThat(extended.isSensitive("X-Tenancy")).isFalse();
  }

  @Test
  void extraHeadersCannotRemoveABuiltIn() {
    // There is deliberately no API for removal — an operator supplying a narrow list
    // still gets every built-in.
    SensitiveKeys extended = new SensitiveKeys(List.of("X-Only-This"));

    assertThat(extended.isSensitive("Authorization")).isTrue();
    assertThat(extended.isSensitive("Cookie")).isTrue();
  }

  @Test
  void ignoresBlankAndNullExtraHeaderEntries() {
    SensitiveKeys extended = new SensitiveKeys(
      java.util.Arrays.asList("  ", null, "X-Real")
    );

    assertThat(extended.isSensitive("X-Real")).isTrue();
    assertThat(extended.isSensitive("Content-Type")).isFalse();
  }
}
