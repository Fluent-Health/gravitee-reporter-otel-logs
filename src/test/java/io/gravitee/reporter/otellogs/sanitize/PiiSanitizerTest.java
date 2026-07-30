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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.otellogs.config.MaskingConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PiiSanitizerTest {

  private ListAppender<ILoggingEvent> appender;
  private ch.qos.logback.classic.Logger logger;

  @BeforeEach
  void attachAppender() {
    appender = new ListAppender<>();
    appender.start();
    logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
      PiiSanitizer.class
    );
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
  }

  private static PiiSanitizer enabled() {
    MaskingConfiguration cfg = new MaskingConfiguration();
    cfg.setEnabled(true);
    return PiiSanitizer.from(cfg);
  }

  @Test
  void disabledSanitizerLeavesEverythingUntouched() {
    PiiSanitizer off = PiiSanitizer.disabled();

    assertThat(off.body("{\"family\":\"Doe\"}")).isEqualTo(
      "{\"family\":\"Doe\"}"
    );
    assertThat(off.text("Bearer abc123")).isEqualTo("Bearer abc123");
    assertThat(off.url("/p?access_token=abc")).isEqualTo("/p?access_token=abc");
  }

  @Test
  void disabledSanitizerStillSerialisesHeaders() {
    HttpHeaders headers = HttpHeaders.create();
    headers.add("Authorization", "Bearer secret");

    // Disabled means "do not mask", NOT "do not emit" — reportHeaders governs emission.
    assertThat(PiiSanitizer.disabled().headersAsJson(headers)).contains(
      "Bearer secret"
    );
  }

  @Test
  void fromConfigurationHonoursTheDisabledDefault() {
    assertThat(
      PiiSanitizer.from(new MaskingConfiguration()).text("Bearer abc")
    ).isEqualTo("Bearer abc");
  }

  @Test
  void masksSensitiveHeadersByName() {
    HttpHeaders headers = HttpHeaders.create();
    headers.add("Authorization", "Bearer secret-token");
    headers.add("Cookie", "session=abc");
    headers.add("Content-Type", "application/fhir+json");

    String json = enabled().headersAsJson(headers);

    assertThat(json)
      .doesNotContain("secret-token")
      .doesNotContain("session=abc");
    assertThat(json).contains("[REDACTED]");
    // Non-sensitive headers pass through unchanged — the acceptance criterion.
    assertThat(json).contains("application/fhir+json");
  }

  @Test
  void masksValueShapesInHeadersNotOnTheDenylist() {
    HttpHeaders headers = HttpHeaders.create();
    headers.add("X-Custom-Trace", "user jane@example.com");

    String json = enabled().headersAsJson(headers);

    assertThat(json).doesNotContain("jane@example.com");
  }

  @Test
  void returnsNullForAbsentOrEmptyHeaders() {
    assertThat(enabled().headersAsJson(null)).isNull();
    assertThat(enabled().headersAsJson(HttpHeaders.create())).isNull();
  }

  @Test
  void masksBodiesUsingBothValueAndFhirLayers() {
    String out = enabled().body(
      "{\"family\":\"Doe\",\"contactEmail\":\"jane@example.com\"}"
    );

    assertThat(out).doesNotContain("Doe").doesNotContain("jane@example.com");
  }

  @Test
  void dropsBodiesOverTheCapRatherThanShippingThemUnsanitized() {
    String huge = "x".repeat(PiiSanitizer.MAX_BODY_CHARS + 1);

    assertThat(enabled().body(huge)).isEqualTo("[REDACTED]");
  }

  @Test
  void keepsBodiesAtExactlyTheCap() {
    String atCap = "x".repeat(PiiSanitizer.MAX_BODY_CHARS);

    assertThat(enabled().body(atCap)).isEqualTo(atCap);
  }

  @Test
  void textIsNotSubjectToTheBodyCap() {
    // An error message is never megabytes; dropping a long one would destroy the
    // diagnostic for no compliance benefit.
    String longMessage = "y".repeat(PiiSanitizer.MAX_BODY_CHARS + 1);

    assertThat(enabled().text(longMessage)).isEqualTo(longMessage);
  }

  @Test
  void masksAPolicyAuthoredTokenExchangeErrorShape() {
    // A gateway policy can template a JWT claim and the raw upstream response body into
    // its error content, which lands in metrics.errorMessage ungated by any flag.
    String actual = enabled().text(
      "Could not exchange token for subject '3f2504e0-4f89-11d3-9a0c-0305e82c3301': " +
        "(401) {\"error\":\"invalid_grant\",\"access_token\":\"eyJhbGciOi.abc.def\"}"
    );

    assertThat(actual).doesNotContain("3f2504e0-4f89-11d3-9a0c-0305e82c3301");
    assertThat(actual).doesNotContain("eyJhbGciOi.abc.def");
    // The diagnostic shape survives, which is the point of masking rather than dropping.
    assertThat(actual).contains("Could not exchange token for subject");
    assertThat(actual).contains("(401)");
  }

  @Test
  void masksQueryStringsInUrls() {
    String out = enabled().url("/Patient?email=a@b.com&access_token=xyz");

    assertThat(out).doesNotContain("a@b.com").doesNotContain("xyz");
  }

  @Test
  void urlUsesValueShapesOnlyAndKeepsThePath() {
    assertThat(enabled().url("/Patient/{id}/Observation")).isEqualTo(
      "/Patient/{id}/Observation"
    );
  }

  @Test
  void toleratesNullAndEmptyEverywhere() {
    PiiSanitizer s = enabled();

    assertThat(s.body(null)).isNull();
    assertThat(s.text(null)).isNull();
    assertThat(s.url(null)).isNull();
    assertThat(s.body("")).isEmpty();
    assertThat(s.text("")).isEmpty();
    assertThat(s.url("")).isEmpty();
  }

  @Test
  void addsExtraHeadersFromConfiguration() {
    MaskingConfiguration cfg = new MaskingConfiguration();
    cfg.setEnabled(true);
    cfg.setExtraHeadersRaw("X-Internal-Secret");

    HttpHeaders headers = HttpHeaders.create();
    headers.add("X-Internal-Secret", "hunter2");

    assertThat(PiiSanitizer.from(cfg).headersAsJson(headers)).doesNotContain(
      "hunter2"
    );
  }

  @Test
  void neverPropagatesAnExceptionToTheCaller() {
    // Contract: sanitisation must not drop a log record. A mangled attribute is
    // recoverable; a leaked one is not — so failures redact rather than rethrow.
    // Mockito (already a dependency) rather than a hand-written stub: HttpHeaders is a
    // wide interface and implementing every abstract method by hand would not compile.
    HttpHeaders throwing = mock(HttpHeaders.class);
    when(throwing.isEmpty()).thenReturn(false);
    when(throwing.toListValuesMap()).thenThrow(
      new IllegalStateException("boom")
    );

    // Not a bare marker: the attribute is documented as JSON, so anything parsing it
    // downstream must not choke on the failure path.
    assertThat(enabled().headersAsJson(throwing)).isEqualTo(
      "{\"_\":\"[REDACTED]\"}"
    );
  }

  @Test
  void headersFailureMarkerIsStillParseableJson() {
    HttpHeaders throwing = mock(HttpHeaders.class);
    when(throwing.isEmpty()).thenThrow(new IllegalStateException("boom"));

    JsonObject parsed = new Gson().fromJson(
      enabled().headersAsJson(throwing),
      JsonObject.class
    );

    assertThat(parsed.get("_").getAsString()).isEqualTo("[REDACTED]");
  }

  @Test
  void neverPropagatesAnExceptionFromIsEmptyEither() {
    // Round-1 review finding: isEmpty() used to be called before the try block, so an
    // HttpHeaders whose isEmpty() itself throws would have escaped uncaught. The test above only
    // stubbed isEmpty() to return false, so it never exercised this path.
    HttpHeaders throwing = mock(HttpHeaders.class);
    when(throwing.isEmpty()).thenThrow(new IllegalStateException("boom"));

    assertThat(enabled().headersAsJson(throwing)).isEqualTo(
      "{\"_\":\"[REDACTED]\"}"
    );
  }

  // ===== failure visibility =====

  @Test
  void logsTheExceptionClassWhenSanitisationFails() {
    // Silent swallowing meant a bug that made every call throw would redact 100% of
    // attributes with no operator signal whatsoever.
    HttpHeaders throwing = mock(HttpHeaders.class);
    when(throwing.isEmpty()).thenThrow(new IllegalStateException("boom"));

    enabled().headersAsJson(throwing);

    assertThat(appender.list)
      .filteredOn(e -> e.getLevel() == Level.WARN)
      .extracting(ILoggingEvent::getFormattedMessage)
      .anyMatch(
        m ->
          m.contains("headersAsJson") &&
          m.contains("java.lang.IllegalStateException")
      );
  }

  @Test
  void throttlesFailureWarningsSoASystematicFailureCannotFloodTheLog() {
    HttpHeaders throwing = mock(HttpHeaders.class);
    when(throwing.isEmpty()).thenThrow(new IllegalStateException("boom"));
    PiiSanitizer sanitizer = enabled();

    for (int i = 0; i < 50; i++) {
      sanitizer.headersAsJson(throwing);
    }

    assertThat(appender.list)
      .filteredOn(e -> e.getLevel() == Level.WARN)
      .hasSize(PiiSanitizer.MAX_FAILURE_LOGS);
  }

  // ===== pathological input: cost and stack depth =====

  @Test
  void doesNotThrowOnALargeTriggeredJsonStringValue() {
    // The old JSON_STRING idiom "(?:[^"\\]|\\.)*" recursed once per iteration and threw
    // StackOverflowError above roughly 2 KB. An Error is not an Exception, so it escaped
    // both this class and OtelLogsReporter.report() and the whole record was lost.
    // Real triggers: a 2 KB+ JWT in {"access_token":"…"}, or a FHIR clinical narrative.
    String longJwt = "e" + "y".repeat(60_000);
    String fhirNarrative =
      "{\"note\":[{\"text\":\"" + "A".repeat(60_000) + "\"}]}";
    PiiSanitizer sanitizer = enabled();

    assertThatCode(() -> {
      assertThat(
        sanitizer.body("{\"access_token\":\"" + longJwt + "\"}")
      ).doesNotContain(longJwt);
      assertThat(sanitizer.body(fhirNarrative)).contains("[REDACTED]");
      assertThat(sanitizer.text(fhirNarrative)).contains("[REDACTED]");
    }).doesNotThrowAnyException();
  }

  @Test
  void sanitisesAPathologicalBase64BodyInBoundedTime() {
    // A FHIR Binary.data payload plus any stray '@' used to make the EMAIL pattern
    // backtrack quadratically: 17 s of gateway CPU, measured, on this input.
    String body =
      "{\"resourceType\":\"Binary\",\"data\":\"" +
      base64ish(60_000) +
      "\",\"who\":\"x@y\"}";
    PiiSanitizer sanitizer = enabled();

    long startNanos = System.nanoTime();
    sanitizer.body(body);
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

    assertThat(elapsedMs)
      .as(
        "sanitising a %d-char base64 body took %d ms",
        body.length(),
        elapsedMs
      )
      .isLessThan(500);
  }

  @Test
  void dropsFreeTextOverTheGenerousCap() {
    // text() is uncapped in spirit — a long error message keeps its diagnostic value —
    // but "uncapped" left the regex cost of a pathological value unbounded.
    String huge = "z".repeat(PiiSanitizer.MAX_TEXT_CHARS + 1);

    assertThat(enabled().text(huge)).isEqualTo("[REDACTED]");
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
