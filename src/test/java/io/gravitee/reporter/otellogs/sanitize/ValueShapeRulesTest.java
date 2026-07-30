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
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValueShapeRulesTest {

  @Test
  void redactsEmailAddresses() {
    assertThat(
      ValueShapeRules.apply("contact jane.doe+x@example.co.in now")
    ).isEqualTo("contact [REDACTED] now");
  }

  @Test
  void redactsBearerTokens() {
    assertThat(
      ValueShapeRules.apply("Bearer eyJhbGciOiJIUzI1NiJ9.abc.def")
    ).isEqualTo("[REDACTED]");
  }

  @Test
  void redactsBearerTokensCaseInsensitively() {
    assertThat(ValueShapeRules.apply("bearer abc123")).isEqualTo("[REDACTED]");
  }

  @Test
  void redactsNamedTokenAssignments() {
    assertThat(ValueShapeRules.apply("access_token=abc.def-ghi")).isEqualTo(
      "[REDACTED]"
    );
    assertThat(ValueShapeRules.apply("refresh_token: xyz789")).isEqualTo(
      "[REDACTED]"
    );
  }

  @Test
  void redactsUuids() {
    assertThat(
      ValueShapeRules.apply(
        "subject 3f2504e0-4f89-11d3-9a0c-0305e82c3301 failed"
      )
    ).isEqualTo("subject [REDACTED] failed");
  }

  @Test
  void redactsIsoDates() {
    assertThat(ValueShapeRules.apply("birthDate 1967-09-03")).isEqualTo(
      "birthDate [REDACTED]"
    );
  }

  @Test
  void redactsDisplayDates() {
    assertThat(ValueShapeRules.apply("dob 03 Sept 1967")).isEqualTo(
      "dob [REDACTED]"
    );
    assertThat(ValueShapeRules.apply("dob 3 Jan 67")).isEqualTo(
      "dob [REDACTED]"
    );
  }

  @Test
  void redactsIndianPhoneNumbers() {
    assertThat(ValueShapeRules.apply("+919876543210")).isEqualTo("[REDACTED]");
    assertThat(ValueShapeRules.apply("call 91-9876543210")).isEqualTo(
      "call [REDACTED]"
    );
  }

  @Test
  void redactsDashedAbhaIds() {
    assertThat(ValueShapeRules.apply("id 12-3456-7890-1234")).isEqualTo(
      "id [REDACTED]"
    );
  }

  @Test
  void redactsDashlessAbhaOnlyWithContextKeyword() {
    // Context present → redacted.
    assertThat(
      ValueShapeRules.apply("{\"abha\":\"12345678901234\"}")
    ).isEqualTo("{\"abha\":\"[REDACTED]\"}");

    // No ABHA context → a bare 14-digit run is left alone, so unrelated
    // timestamps and transaction ids survive. This is the whole point of the gate.
    assertThat(ValueShapeRules.apply("txn 12345678901234")).isEqualTo(
      "txn 12345678901234"
    );
  }

  @Test
  void leavesBenignTextUnchanged() {
    assertThat(ValueShapeRules.apply("application/fhir+json")).isEqualTo(
      "application/fhir+json"
    );
    assertThat(ValueShapeRules.apply("GET /Patient/{id} 200")).isEqualTo(
      "GET /Patient/{id} 200"
    );
  }

  @Test
  void toleratesNullAndEmpty() {
    assertThat(ValueShapeRules.apply(null)).isNull();
    assertThat(ValueShapeRules.apply("")).isEmpty();
  }

  @Test
  void redactsTokensInQueryStrings() {
    assertThat(
      ValueShapeRules.apply("/Patient?email=a@b.com&access_token=xyz123")
    ).isEqualTo("/Patient?email=[REDACTED]&[REDACTED]");
  }

  // The token-shape matrix below is the actual requirement from code review round 1 on
  // PiiSanitizer: the Android tokenPattern's [:=\s]+ delimiter both missed JSON-quoted
  // credentials and swallowed the next word of ordinary prose containing "token". Every row
  // must hold; see ValueShapeRules.TOKEN_JSON / TOKEN_ASSIGNMENT for the fix.

  @Test
  void redactsJsonQuotedTokenValuesRegardlessOfWhitespaceAroundTheColon() {
    assertThat(
      ValueShapeRules.apply("{\"access_token\":\"eyJhbGciOi.abc.def\"}")
    ).isEqualTo("{\"access_token\": [REDACTED]}");
    assertThat(
      ValueShapeRules.apply("{\"access_token\" : \"eyJhbGciOi.abc.def\"}")
    ).isEqualTo("{\"access_token\": [REDACTED]}");
    assertThat(
      ValueShapeRules.apply("{\"access_token\":  \"eyJhbGciOi.abc.def\"}")
    ).isEqualTo("{\"access_token\": [REDACTED]}");
  }

  @Test
  void redactsSingleQuotedTokenAssignments() {
    assertThat(ValueShapeRules.apply("access_token='abc123'")).isEqualTo(
      "[REDACTED]"
    );
  }

  @Test
  void leavesTokenProseUnchanged() {
    // Bare "token" (no access_/refresh_/id_ prefix) followed by a bare colon is prose, not a
    // credential assignment — only "=" or a prefix earns redaction. Destroying these diagnostic
    // sentences would cost compliance nothing, since there is no secret in any of them.
    assertThat(
      ValueShapeRules.apply("Could not exchange token for subject 'abc'")
    ).isEqualTo("Could not exchange token for subject 'abc'");
    assertThat(ValueShapeRules.apply("the token expired yesterday")).isEqualTo(
      "the token expired yesterday"
    );
    assertThat(
      ValueShapeRules.apply("Invalid token: session expired")
    ).isEqualTo("Invalid token: session expired");
    assertThat(
      ValueShapeRules.apply("token: economy class seating available")
    ).isEqualTo("token: economy class seating available");
  }

  // ===== credential coverage beyond access_/refresh_/id_token =====

  @ParameterizedTest
  @ValueSource(
    strings = {
      "subject_token",
      "csrf_token",
      "api_token",
      "subjectToken",
      "x-auth-token",
      "access_token",
      "refresh_token",
      "id_token",
      "client_secret",
      "password",
      "passcode",
      "api_key",
      "apiKey",
      "api-key",
      "otp",
      "pin",
    }
  )
  void redactsEveryCredentialKeyInJsonForm(String key) {
    String out = ValueShapeRules.apply(
      "{\"" + key + "\":\"eyJhbGciOi.abc.def\"}"
    );

    assertThat(out).doesNotContain("eyJhbGciOi.abc.def");
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "subject_token",
      "csrf_token",
      "api_token",
      "subjectToken",
      "x-auth-token",
      "client_secret",
      "api_key",
      "apiKey",
      "api-key",
    }
  )
  void redactsEveryCompoundCredentialKeyInAssignmentForm(String key) {
    // Both delimiters: the colon form is safe for these keys because every one of them is a
    // compound name that does not occur in a sentence.
    assertThat(ValueShapeRules.apply(key + "=s3cr3tV4lue")).doesNotContain(
      "s3cr3tV4lue"
    );
    assertThat(ValueShapeRules.apply(key + ": s3cr3tV4lue")).doesNotContain(
      "s3cr3tV4lue"
    );
  }

  @ParameterizedTest
  @ValueSource(strings = { "password", "passcode", "otp", "pin" })
  void redactsWordShapedCredentialKeysOnTheEqualsFormOnly(String key) {
    // These key names are also ordinary English words, so they follow the same rule as the bare
    // word "token": "=" earns redaction, a bare ":" does not. The colon form is prose — see
    // leavesWordShapedCredentialKeyProseUnchanged — and the JSON-quoted form is covered by
    // TOKEN_JSON above, which is the unambiguous structural signal.
    assertThat(ValueShapeRules.apply(key + "=s3cr3tV4lue")).doesNotContain(
      "s3cr3tV4lue"
    );
  }

  @Test
  void redactsAnOauth2TokenExchangeRequestBody() {
    // A gateway policy posting an RFC 8693 token exchange upstream: every field but
    // grant_type carries a credential, and all of them used to ship verbatim.
    String out = ValueShapeRules.apply(
      "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" +
        "&subject_token=eyJhbGciOiJSUzI1NiJ9.payload.signature" +
        "&client_secret=example-client-secret"
    );

    assertThat(out)
      .doesNotContain("eyJhbGciOiJSUzI1NiJ9.payload.signature")
      .doesNotContain("example-client-secret");
  }

  @Test
  void redactsBasicAuthorizationNotJustBearer() {
    assertThat(ValueShapeRules.apply("Basic Y2xpZW50OnNlY3JldA==")).isEqualTo(
      "[REDACTED]"
    );
    assertThat(ValueShapeRules.apply("basic Y2xpZW50OnNlY3JldA==")).isEqualTo(
      "[REDACTED]"
    );
  }

  // ===== the Basic arm must gate on base64 shape, not on the word "Basic" =====

  @ParameterizedTest
  @ValueSource(
    strings = {
      // FHIR clinical content: "Basic Metabolic Panel" is a real LOINC display name.
      "{\"code\":{\"text\":\"Basic Metabolic Panel\",\"coding\":[{\"display\":\"Basic metabolic 2000 panel\"}]}}",
      "Basic authentication is required for this endpoint",
      "Basic Life Support training",
      "Client authentication with Basic scheme failed",
    }
  )
  void leavesProseFollowingTheWordBasicUnchanged(String prose) {
    // basic\s+[A-Za-z0-9+/=]+ ate the next word of every one of these. The compliance gain was
    // near-zero — Authorization / Proxy-Authorization already die by NAME on the layer-1
    // denylist — so destroying diagnostic and clinical text bought nothing. A minimum-length
    // guard cannot separate the classes either: "Metabolic" is 9 characters.
    assertThat(ValueShapeRules.apply(prose)).isEqualTo(prose);
  }

  @Test
  void leavesTheWordBasicAloneWhenBothSchemesAppearInProse() {
    // The Basic arm no longer fires here. The trailing redaction is the Bearer arm, which is
    // unchanged by this fix and stays deliberately permissive (see redactsBearerTokens*, where
    // "bearer abc123" must still redact) — a bearer token has no shape to gate on.
    assertThat(
      ValueShapeRules.apply("Only Basic and Bearer schemes are supported")
    ).isEqualTo("Only Basic and [REDACTED] are supported");
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "dXNlcjpwYXNz", // user:pass
      "Y2xpZW50OnNlY3JldA==", // client:secret
      "QWxhZGRpbjpvcGVuIHNlc2FtZQ==", // Aladdin:open sesame (RFC 7617 example)
      "c3ZjOjlmM2M4YTExLTRiMmU=", // svc:9f3c8a11-4b2e
    }
  )
  void stillRedactsRealBasicCredentials(String base64) {
    assertThat(ValueShapeRules.apply("Basic " + base64)).isEqualTo(
      "[REDACTED]"
    );
    assertThat(ValueShapeRules.apply("basic " + base64)).isEqualTo(
      "[REDACTED]"
    );
  }

  // ===== word-shaped credential keys must not eat prose either =====

  @ParameterizedTest
  @ValueSource(
    strings = {
      "Invalid pin: must be 6 digits",
      "otp: expired, request a new one",
      "password: too short",
      "pin: not set",
      "OTP: please retry",
    }
  )
  void leavesWordShapedCredentialKeyProseUnchanged(String prose) {
    // error.message carries the raw Gravitee AM response body on the EMR token-exchange failure
    // path, so "Invalid [REDACTED] be 6 digits" is exactly the diagnostic loss this feature
    // promised not to cause. There is no secret in any of these strings.
    assertThat(ValueShapeRules.apply(prose)).isEqualTo(prose);
  }

  @Test
  void stillRedactsWordShapedCredentialKeysWithAnEqualsDelimiter() {
    assertThat(ValueShapeRules.apply("otp=123456")).isEqualTo("[REDACTED]");
    assertThat(ValueShapeRules.apply("pin=1234")).isEqualTo("[REDACTED]");
    assertThat(ValueShapeRules.apply("pin=9999")).isEqualTo("[REDACTED]");
    assertThat(ValueShapeRules.apply("?otp=987654&x=1")).isEqualTo(
      "?[REDACTED]&x=1"
    );
  }

  @Test
  void stillRedactsWordShapedCredentialKeysInJsonForm() {
    // JSON quoting is the structural signal, so no delimiter restriction applies here.
    assertThat(ValueShapeRules.apply("{\"otp\":\"123456\"}")).isEqualTo(
      "{\"otp\": [REDACTED]}"
    );
    assertThat(ValueShapeRules.apply("{\"pin\":\"1234\"}")).isEqualTo(
      "{\"pin\": [REDACTED]}"
    );
  }

  @Test
  void keepsJsonQuotesBalancedAfterRedactingATokenAmongSiblingFields() {
    String redacted = ValueShapeRules.apply(
      "{\"error\":\"invalid_grant\",\"access_token\":\"eyJhbGciOi.abc.def\"}"
    );

    assertThat(redacted).isEqualTo(
      "{\"error\":\"invalid_grant\",\"access_token\": [REDACTED]}"
    );
    long quoteCount = redacted
      .chars()
      .filter(c -> c == '"')
      .count();
    assertThat(quoteCount % 2)
      .as("double quotes must balance in %s", redacted)
      .isZero();
  }

  // ===== ABHA context spellings =====

  @ParameterizedTest
  @ValueSource(
    strings = {
      "healthId",
      "healthIdNumber",
      "abhaNumber",
      "ABHA",
      "health_id",
      "AbhaAddress",
    }
  )
  void recognisesEverySpellingAbdmActuallyUsesAsAbhaContext(String key) {
    // The needles used to be lowercase, quote-terminated literals ("\"healthid\""), so every
    // real ABDM field name missed and the dashless rule was dead code in production.
    String out = ValueShapeRules.apply("{\"" + key + "\":\"12345678901234\"}");

    assertThat(out).doesNotContain("12345678901234");
  }

  @Test
  void stillLeavesABare14DigitRunAloneWithoutAbhaContext() {
    assertThat(ValueShapeRules.apply("txn 12345678901234")).isEqualTo(
      "txn 12345678901234"
    );
    assertThat(
      ValueShapeRules.apply("{\"timestamp\":\"17224531200000\"}")
    ).contains("17224531200000");
  }

  // ===== gates must match what their patterns require =====

  @Test
  void redactsDisplayDatesSeparatedByWhitespaceOtherThanSpace() {
    // The pattern's separator is \s+, but the gate used to test for ' ' specifically.
    assertThat(ValueShapeRules.apply("dob\t12\tJan\t1990")).isEqualTo(
      "dob\t[REDACTED]"
    );
    assertThat(ValueShapeRules.apply("dob\n03 Sept 1967")).isEqualTo(
      "dob\n[REDACTED]"
    );
  }

  // ===== pathological input =====

  @Test
  void doesNotThrowOnALargeQuotedCredentialValue() {
    // "(?:[^"\\]|\\.)*" recursed per iteration and threw StackOverflowError above ~2 KB.
    // A 2 KB+ JWT in {"access_token":"…"} is an everyday payload, not a crafted one.
    String jwt = "e" + "y".repeat(60_000);

    assertThatCode(() ->
      assertThat(
        ValueShapeRules.apply("{\"access_token\":\"" + jwt + "\"}")
      ).doesNotContain(jwt)
    ).doesNotThrowAnyException();
  }

  @Test
  void matchesQuotedValuesContainingEscapedQuotesIdentically() {
    // The possessive rewrite must not change which span is matched.
    assertThat(
      ValueShapeRules.apply("{\"access_token\":\"ab\\\"cd\"}")
    ).isEqualTo("{\"access_token\": [REDACTED]}");
  }

  @Test
  void handlesAPathologicalBase64ValueInBoundedTime() {
    // 65 000 base64 chars plus one '@': 11-17 s of CPU with the textbook EMAIL pattern,
    // because [A-Za-z0-9.-]+ overlapped the \. separator that followed it.
    // Includes an '@' and a credential key so every gate in apply() opens and every
    // pattern actually sweeps the 65 KB run, not just the EMAIL one.
    String value =
      "{\"data\":\"" +
      base64ish(65_000) +
      "\",\"who\":\"x@y\",\"access_token\":\"abc\"}";

    long startNanos = System.nanoTime();
    ValueShapeRules.apply(value);
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

    assertThat(elapsedMs)
      .as(
        "applying value shapes to %d chars took %d ms",
        value.length(),
        elapsedMs
      )
      .isLessThan(500);
  }

  @Test
  void stillMatchesTheSameEmailsAfterTheRewrite() {
    assertThat(ValueShapeRules.apply("jane@example.com")).isEqualTo(
      "[REDACTED]"
    );
    assertThat(ValueShapeRules.apply("jane.doe+x@sub.example.co.uk")).isEqualTo(
      "[REDACTED]"
    );
    assertThat(ValueShapeRules.apply("a@b.co")).isEqualTo("[REDACTED]");
    // No dot after the '@' is not an address shape — these must stay readable.
    assertThat(ValueShapeRules.apply("no@tld")).isEqualTo("no@tld");
    assertThat(ValueShapeRules.apply("user@localhost")).isEqualTo(
      "user@localhost"
    );
  }

  private static String base64ish(int n) {
    String alphabet =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    StringBuilder sb = new StringBuilder(n);
    for (int i = 0; i < n; i++) sb.append(
      alphabet.charAt(i % alphabet.length())
    );
    return sb.toString();
  }
}
