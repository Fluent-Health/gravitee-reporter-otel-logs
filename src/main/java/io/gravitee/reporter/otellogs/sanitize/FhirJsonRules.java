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

import static io.gravitee.reporter.otellogs.sanitize.ValueShapeRules.REDACTED;
import static io.gravitee.reporter.otellogs.sanitize.ValueShapeRules.containsAny;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 3 of the PII sanitizer — FHIR R4 JSON key-shape redaction.
 *
 * <p>Six datatypes, ten patterns: HumanName, Address,
 * ContactPoint (+companion), Annotation.text (+companion), Reference.display (+companion), and
 * ContactDetail.name (+companion). The companions exist because the bounded {@code [^}]*} in each
 * primary pattern cannot cross a nested object.
 *
 * <p>Every pattern keys on the JSON *shape* — quoted key, colon, string-or-array value — so prose
 * containing words like "given" or "city" does not match. Trigger gates must stay in sync with the
 * patterns they guard; Android records a PII leak caused by exactly that drift ({@code :958-962}).
 *
 * <p>HumanName and Address preserve the matched key as a triage signal so operators can see which
 * field was redacted. The remaining datatypes replace wholesale, because they match bidirectional
 * key orderings where group-aware replacement is unreliable.
 */
public final class FhirJsonRules {

  /**
   * The single source of truth for HumanName and Address keys: the trigger gate AND the pattern's key
   * alternation are both derived from these lists, so the two cannot drift apart.
   *
   * <p>They HAD drifted: {@code district} and {@code state} were in the {@link #ADDRESS} pattern but
   * absent from its trigger list, so {@code {"district":"Bengaluru South"}} and
   * {@code {"state":"Karnataka"}} were shipped verbatim. Package-private so
   * {@code FhirJsonRulesTest} can assert every key in both directions.
   */
  static final List<String> HUMAN_NAME_KEYS = List.of(
    "given",
    "family",
    "prefix",
    "suffix"
  );

  static final List<String> ADDRESS_KEYS = List.of(
    "line",
    "city",
    "district",
    "state",
    "postalCode",
    "country"
  );

  private static final List<String> HUMAN_NAME_TRIGGERS = quoted(
    HUMAN_NAME_KEYS
  );
  private static final List<String> ADDRESS_TRIGGERS = quoted(ADDRESS_KEYS);
  private static final List<String> CONTACT_POINT_TRIGGERS = List.of(
    "\"system\""
  );
  private static final List<String> ANNOTATION_TRIGGERS = List.of("\"note\"");
  private static final List<String> REFERENCE_TRIGGERS = List.of(
    "\"reference\""
  );
  private static final List<String> CONTACT_DETAIL_TRIGGERS = List.of(
    "\"contact\""
  );

  /** ContactPoint.system code-system values, per FHIR R4. */
  private static final String CP_SYSTEMS =
    "phone|email|sms|fax|pager|url|other";

  /**
   * A JSON string value, honouring backslash escapes.
   *
   * <p>Possessive "unrolled loop" form rather than the equivalent {@code "(?:[^"\\]|\\.)*"}. Java
   * compiles {@code (?:A|B)*} to a node that recurses once per iteration, so that form throws
   * {@link StackOverflowError} — not an exception, so it escaped both catch layers and dropped the
   * whole record — on any matched string value over roughly 2 KB. A FHIR {@code note}/{@code text}
   * clinical narrative reaches that trivially. This form matches the identical span (including
   * escaped quotes) iteratively: 60 KB in 2 ms.
   */
  private static final String JSON_STRING =
    "\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\"";

  /** A JSON string OR array value. */
  private static final String JSON_STRING_OR_ARRAY =
    "(" + JSON_STRING + "|\\[[^\\]]*\\])";

  private static final Pattern HUMAN_NAME = Pattern.compile(
    "\"(" + alternation(HUMAN_NAME_KEYS) + ")\"\\s*:\\s*" + JSON_STRING_OR_ARRAY
  );

  private static final Pattern ADDRESS = Pattern.compile(
    "\"(" + alternation(ADDRESS_KEYS) + ")\"\\s*:\\s*" + JSON_STRING_OR_ARRAY
  );

  private static final Pattern CONTACT_POINT = Pattern.compile(
    "\"value\"\\s*:\\s*" +
      JSON_STRING +
      "\\s*,\\s*\"system\"\\s*:\\s*\"(?:" +
      CP_SYSTEMS +
      ")\"" +
      "|\"system\"\\s*:\\s*\"(?:" +
      CP_SYSTEMS +
      ")\"\\s*,\\s*\"value\"\\s*:\\s*" +
      JSON_STRING
  );

  private static final Pattern CONTACT_POINT_VALUE = Pattern.compile(
    "\"value\"\\s*:\\s*" + JSON_STRING
  );

  private static final Pattern ANNOTATION_TEXT = Pattern.compile(
    "\"note\"\\s*:\\s*\\[\\s*\\{[^}]*\"text\"\\s*:\\s*" + JSON_STRING
  );

  private static final Pattern ANNOTATION_TEXT_VALUE = Pattern.compile(
    "\"text\"\\s*:\\s*" + JSON_STRING
  );

  private static final Pattern REFERENCE_DISPLAY = Pattern.compile(
    "\"reference\"\\s*:\\s*" +
      JSON_STRING +
      "\\s*,\\s*\"display\"\\s*:\\s*" +
      JSON_STRING +
      "|\"display\"\\s*:\\s*" +
      JSON_STRING +
      "\\s*,\\s*\"reference\"\\s*:\\s*" +
      JSON_STRING
  );

  private static final Pattern REFERENCE_DISPLAY_VALUE = Pattern.compile(
    "\"display\"\\s*:\\s*" + JSON_STRING
  );

  private static final Pattern CONTACT_DETAIL_NAME = Pattern.compile(
    "\"contact\"\\s*:\\s*\\[\\s*\\{[^}]*\"name\"\\s*:\\s*" + JSON_STRING
  );

  private static final Pattern CONTACT_DETAIL_NAME_VALUE = Pattern.compile(
    "\"name\"\\s*:\\s*" + JSON_STRING
  );

  private FhirJsonRules() {}

  /** {@code given} → {@code "given"} — the trigger form of a key. */
  private static List<String> quoted(List<String> keys) {
    return keys
      .stream()
      .map(k -> "\"" + k + "\"")
      .toList();
  }

  /** {@code [given, family]} → {@code given|family} — the pattern form of a key set. */
  private static String alternation(List<String> keys) {
    return String.join("|", keys);
  }

  public static String apply(String value) {
    if (value == null || value.isEmpty()) return value;
    String v = value;

    if (containsAny(v, HUMAN_NAME_TRIGGERS)) {
      v = replacePreservingKey(HUMAN_NAME, v);
    }
    if (containsAny(v, ADDRESS_TRIGGERS)) {
      v = replacePreservingKey(ADDRESS, v);
    }
    if (containsAny(v, CONTACT_POINT_TRIGGERS)) {
      v = CONTACT_POINT.matcher(v).replaceAll(REDACTED);
      // Companion pass: FHIR allows id/extension/use/rank/period between system and value.
      // Gated on a real ContactPoint system code so generic "value" keys are untouched.
      if (
        v.contains("\"system\"") &&
        v.contains("\"value\"") &&
        hasContactPointSystem(v)
      ) {
        v = CONTACT_POINT_VALUE.matcher(v).replaceAll(REDACTED);
      }
    }
    if (containsAny(v, ANNOTATION_TRIGGERS) && v.contains("\"text\"")) {
      v = ANNOTATION_TEXT.matcher(v).replaceAll(REDACTED);
      v = ANNOTATION_TEXT_VALUE.matcher(v).replaceAll(REDACTED);
    }
    if (containsAny(v, REFERENCE_TRIGGERS) && v.contains("\"display\"")) {
      v = REFERENCE_DISPLAY.matcher(v).replaceAll(REDACTED);
      v = REFERENCE_DISPLAY_VALUE.matcher(v).replaceAll(REDACTED);
    }
    if (containsAny(v, CONTACT_DETAIL_TRIGGERS) && v.contains("\"name\"")) {
      v = CONTACT_DETAIL_NAME.matcher(v).replaceAll(REDACTED);
      v = CONTACT_DETAIL_NAME_VALUE.matcher(v).replaceAll(REDACTED);
    }
    return v;
  }

  private static boolean hasContactPointSystem(String v) {
    return (
      v.contains("\"phone\"") ||
      v.contains("\"email\"") ||
      v.contains("\"sms\"") ||
      v.contains("\"fax\"") ||
      v.contains("\"pager\"") ||
      v.contains("\"url\"") ||
      v.contains("\"other\"")
    );
  }

  /**
   * Replaces each match's VALUE while keeping its key, so {@code "family":"Doe"} becomes
   * {@code "family": [REDACTED]}. Operators keep the signal of which field was redacted.
   */
  private static String replacePreservingKey(Pattern pattern, String input) {
    Matcher m = pattern.matcher(input);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String match = m.group();
      int colon = match.indexOf(':');
      String replacement = match.substring(0, colon + 1) + " " + REDACTED;
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }
}
