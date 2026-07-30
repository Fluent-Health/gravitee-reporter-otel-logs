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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer 1 of the PII sanitizer — the header-name denylist.
 *
 * <p>The set unions common credential header names, direct identifiers, and the FHIR PII field names
 * that can appear as header names.
 *
 * <p>Deliberately excluded: dotted span-data keys and a namespace-prefix pass
 * ({@code db.statement}, {@code http.request.headers.}, {@code db.}). Those make sense when the check
 * is applied to dotted telemetry attribute keys; here it is applied to HTTP header names only, and no
 * HTTP header is ever named {@code db.statement}. Trailing-{@code *} prefix matching replaces them,
 * which is the form that earns its keep for headers.
 *
 * <p>Matching is case-insensitive: exact name, or prefix when the entry ends in {@code *}.
 */
public final class SensitiveKeys {

  /** Exact-match names. Lowercase — {@link #isSensitive} lowercases its argument. */
  private static final Set<String> BUILT_IN_EXACT = Set.of(
    // Credentials — the five the EE data-logging-masking policy covers for us today,
    // plus the token names Android already carries.
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    "x-gravitee-api-key",
    "x-gravitee-token",
    "x-zdesk-jwt",
    "token",
    "access_token",
    "refresh_token",
    "password",
    "otp",
    "pin",
    // Direct identifiers (DPDPA)
    "email",
    "phone",
    "mobile",
    // FHIR PII field names, for the cases where they appear as header names
    "name",
    "address",
    "contact",
    "birthdate",
    "gender",
    "pronouns",
    "nationality",
    "language",
    "deceased",
    "identifier",
    "telecom",
    "qualification",
    "practitioner",
    "patient",
    "subject",
    "given",
    "family",
    "line",
    "city",
    "postalcode"
  );

  /**
   * Prefix-match entries, stored WITHOUT the trailing {@code *}. Lowercase.
   *
   * <p>{@code x-clevertap-} covers both {@code X-CleverTap-Account-Id} and
   * {@code X-CleverTap-Passcode}, injected at {@code apim-clevertap/main.tf:69-71}.
   */
  private static final Set<String> BUILT_IN_PREFIXES = Set.of("x-clevertap-");

  private static final String WILDCARD_SUFFIX = "*";

  private final Set<String> exact;
  private final Set<String> prefixes;

  public SensitiveKeys(List<String> extraHeaders) {
    Set<String> exactAcc = new HashSet<>(BUILT_IN_EXACT);
    Set<String> prefixAcc = new HashSet<>(BUILT_IN_PREFIXES);
    if (extraHeaders != null) {
      for (String entry : extraHeaders) {
        if (entry == null || entry.isBlank()) continue;
        String normalised = entry.strip().toLowerCase();
        if (normalised.endsWith(WILDCARD_SUFFIX)) {
          String prefix = normalised.substring(0, normalised.length() - 1);
          if (!prefix.isEmpty()) prefixAcc.add(prefix);
        } else {
          exactAcc.add(normalised);
        }
      }
    }
    this.exact = Set.copyOf(exactAcc);
    this.prefixes = Set.copyOf(prefixAcc);
  }

  /** Built-ins only — no operator additions. */
  public static SensitiveKeys withDefaults() {
    return new SensitiveKeys(List.of());
  }

  public boolean isSensitive(String headerName) {
    if (headerName == null || headerName.isEmpty()) return false;
    String lower = headerName.toLowerCase();
    if (exact.contains(lower)) return true;
    for (String prefix : prefixes) {
      if (lower.startsWith(prefix)) return true;
    }
    return false;
  }
}
