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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 2 of the PII sanitizer — value-shape redaction, independent of any surrounding key.
 *
 * <p>Each regex sits behind a cheap {@code contains} gate. The gates are load-bearing, not
 * decoration: this runs synchronously on the gateway's reporting path, and a substring probe is
 * orders of magnitude cheaper than a regex sweep.
 *
 * <p>Pattern order matters — more specific shapes run before broader ones, so a value matching two
 * patterns is consumed by the tighter one.
 */
public final class ValueShapeRules {

  /** Shared with the rest of the sanitize package; matches Android's marker exactly. */
  public static final String REDACTED = "[REDACTED]";

  /**
   * Deliberately NOT the textbook {@code [A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}}: that form
   * lets the domain class {@code [A-Za-z0-9.-]} overlap the {@code \.} separator that follows it, so
   * every dot is two ways to match and the engine backtracks quadratically. The base64url alphabet
   * sits entirely inside that class, so a FHIR {@code Binary.data} payload plus any stray {@code @}
   * was enough to burn 17 s of gateway CPU on a 65 KB body (measured). Here the local part is bounded
   * and possessive, and each domain label is possessive with the dot factored out of the class, so no
   * position has two ways to match: 33 ms on the same input.
   */
  private static final Pattern EMAIL = Pattern.compile(
    "[A-Za-z0-9._%+-]{1,64}+@[A-Za-z0-9-]++(?:\\.[A-Za-z0-9-]++)+"
  );

  /**
   * {@code Authorization} scheme values. {@code Basic} sits alongside {@code Bearer} because an EMR
   * token-exchange leg authenticates with base64 client credentials, and only the Bearer form used to
   * be matched.
   *
   * <p>The {@code Basic} arm gates on base64 <em>shape</em>, not on length. The plain
   * {@code basic\s+[A-Za-z0-9+/=]+} form turned {@code "Basic Metabolic Panel"} into
   * {@code "[REDACTED] Panel"}, {@code "Basic metabolic 2000 panel"} into
   * {@code "[REDACTED] 2000 panel"} and {@code "Basic authentication is required"} into
   * {@code "[REDACTED] is required"} — real FHIR {@code code.text} content and real error strings
   * destroyed for near-zero compliance gain, because {@code authorization} and
   * {@code proxy-authorization} are already on the layer-1 header-name denylist and die by NAME
   * regardless. A minimum-length guard does not fix it: "Metabolic" is 9 characters and
   * "authentication" is 14, so length cannot separate credentials from clinical prose.
   *
   * <p>Instead the two lookaheads demand at least 8 base64 characters AND at least one signal that
   * English prose does not carry: a digit, a {@code +}/{@code /}, or a lowercase→uppercase
   * transition. That still catches the base64 of shapes like {@code user:pass},
   * {@code client:secret}, {@code Aladdin:open sesame} and {@code svc:9f3c8a11-4b2e}; it only misses
   * base64 under 8 characters, which is not a real credential.
   *
   * <p>Compiled deliberately WITHOUT {@link Pattern#CASE_INSENSITIVE}: the flag would neuter the
   * {@code [a-z][A-Z]} transition signal that distinguishes base64 from prose. Case-insensitivity is
   * scoped inline to the two scheme words with {@code (?i:…)}.
   */
  private static final Pattern AUTH_SCHEME = Pattern.compile(
    "(?i:bearer)\\s+[A-Za-z0-9\\-._~+/]+=*" +
      "|(?i:basic)\\s+(?=[A-Za-z0-9+/]{8,})(?=[A-Za-z0-9+/]*?(?:[0-9+/]|[a-z][A-Z]))[A-Za-z0-9+/]+={0,2}"
  );

  /**
   * A JSON string value, honouring backslash escapes — same idiom as {@code FhirJsonRules}' private
   * constant of the same name, kept local here rather than shared so this file has no compile
   * dependency on layer 3.
   *
   * <p>Possessive "unrolled loop" form rather than the equivalent {@code "(?:[^"\\]|\\.)*"}. Java
   * compiles {@code (?:A|B)*} to a node that recurses once per iteration, so that form throws
   * {@link StackOverflowError} — not an exception — on any matched string value over roughly 2 KB: a
   * long JWT, or a FHIR clinical narrative. This form matches the identical span (including escaped
   * quotes) iteratively: 60 KB in 2 ms.
   */
  private static final String JSON_STRING =
    "\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\"";

  /**
   * A credential key whose name ENDS in {@code token} but is not the bare word — {@code
   * access_token}, {@code refresh_token}, {@code id_token}, {@code subject_token}, {@code
   * csrf_token}, {@code api_token}, {@code x-auth-token}, {@code subjectToken}.
   *
   * <p>The quantifier is bounded and lazy on purpose. An unbounded greedy {@code [A-Za-z0-9_-]+}
   * would scan to the end of any base64 run and backtrack a character at a time looking for
   * {@code token}, reintroducing the quadratic cost that the EMAIL rewrite above exists to remove.
   */
  private static final String PREFIXED_TOKEN_KEY = "[A-Za-z0-9_-]{1,40}?token";

  /**
   * Credential keys that are not token-shaped but ARE compound and unambiguous — nobody writes
   * "client_secret" or "api_key" in a sentence. Safe with either delimiter, including a bare
   * {@code :}. {@code subject_token} / {@code client_secret} together are the whole EMR
   * token-exchange request body, which used to pass through untouched.
   */
  private static final String COMPOUND_CREDENTIAL_KEYS =
    "client_secret|api[_-]?key";

  /**
   * Credential keys that are also ordinary English words. They are real credential names, but they
   * appear in diagnostic prose as the subject of the sentence — {@code "Invalid pin: must be 6
   * digits"}, {@code "otp: expired, request a new one"}, {@code "password: too short"} — so giving
   * them the bare-{@code :} delimiter swallowed the following word and left
   * {@code "Invalid [REDACTED] be 6 digits"}. That lands on {@code error.message}, which carries the
   * raw Gravitee AM response body on the EMR token-exchange failure path: the exact diagnostic this
   * feature promised to preserve.
   *
   * <p>They therefore sit in the {@code =}-only arm of {@link #TOKEN_ASSIGNMENT}, alongside the bare
   * word {@code token}, for the same reason and by the same rule. The JSON-quoted forms
   * ({@code {"otp":"123456"}}) are unaffected — {@link #TOKEN_JSON} covers those, because JSON
   * quoting is an unambiguous structural signal rather than prose.
   *
   * <p>All four are also on the layer-1 denylist, but layer 1 only matches header NAMES — in a
   * request body or a query string they would otherwise be unprotected.
   */
  private static final String AMBIGUOUS_CREDENTIAL_KEYS =
    "password|passcode|otp|pin";

  /** Value shape of an unquoted credential: the URL-safe / base64 alphabet plus padding. */
  private static final String CREDENTIAL_VALUE = "[A-Za-z0-9\\-._~+/]+=*";

  /**
   * Two patterns, not one. A single pattern using a permissive {@code [:=\s]+} delimiter has two
   * real defects on a gateway, and cannot fix both without reintroducing one or the other:
   *
   * <ul>
   *   <li>{@link #TOKEN_JSON} — the JSON key/value shape, e.g. {@code "access_token":"…"}. Matches
   *       key-quote through value-quote as one span (the {@code FhirJsonRules} idiom) so the
   *       replacement stays valid JSON, and rebuilds a canonical {@code "<key>": [REDACTED]} instead
   *       of reusing the original spacing — which is what lets {@code {"k" : "v"}} and
   *       {@code {"k":  "v"}} both redact identically regardless of the whitespace around the colon.
   *       A bare {@code "token"} key is matched unconditionally here: JSON quoting is already an
   *       unambiguous structural signal, not prose, so there is no false-positive risk to gate
   *       against.
   *   <li>{@link #TOKEN_ASSIGNMENT} — the unquoted {@code key=value} / {@code key: value} shape.
   *       Here a key that is also an ordinary English word — the bare {@code token} plus
   *       {@link #AMBIGUOUS_CREDENTIAL_KEYS} — is only matched when the delimiter is {@code =},
   *       never a bare {@code :}. That is what keeps {@code "Invalid token: session expired"},
   *       {@code "token: economy class seating available"} and {@code "Invalid pin: must be 6
   *       digits"} untouched while still redacting {@code "refresh_token: xyz789"} and
   *       {@code "otp=123456"}. Without that restriction, any prose sentence built on one of these
   *       words followed by a colon swallows the next word as if it were a credential.
   *       {@link #PREFIXED_TOKEN_KEY} and {@link #COMPOUND_CREDENTIAL_KEYS} are compound names that
   *       do not occur in prose, so they keep both delimiters: in "Invalid token:" the character
   *       before {@code token} is a space, which the prefixed-key class excludes.
   * </ul>
   */
  private static final Pattern TOKEN_JSON = Pattern.compile(
    "\"(" +
      PREFIXED_TOKEN_KEY +
      "|token|" +
      COMPOUND_CREDENTIAL_KEYS +
      "|" +
      AMBIGUOUS_CREDENTIAL_KEYS +
      ")\"\\s*:\\s*" +
      JSON_STRING,
    Pattern.CASE_INSENSITIVE
  );

  private static final Pattern TOKEN_ASSIGNMENT = Pattern.compile(
    "\\b(?:" +
      PREFIXED_TOKEN_KEY +
      "|" +
      COMPOUND_CREDENTIAL_KEYS +
      ")\\s*[:=]\\s*['\"]?" +
      CREDENTIAL_VALUE +
      "['\"]?" +
      "|\\b(?:token|" +
      AMBIGUOUS_CREDENTIAL_KEYS +
      ")\\s*=\\s*['\"]?" +
      CREDENTIAL_VALUE +
      "['\"]?",
    Pattern.CASE_INSENSITIVE
  );

  /**
   * Cheap substring gate for {@link #TOKEN_JSON} / {@link #TOKEN_ASSIGNMENT}. Must cover every key in
   * both patterns, or a credential shape is unreachable dead code — the same trigger/pattern drift
   * that {@code FhirJsonRules} guards against.
   */
  private static final List<String> CREDENTIAL_NEEDLES = List.of(
    "token",
    "secret",
    "password",
    "passcode",
    "key",
    "otp",
    "pin"
  );
  private static final Pattern UUID = Pattern.compile(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
  );
  private static final Pattern ISO_DATE = Pattern.compile(
    "\\b\\d{4}-\\d{2}-\\d{2}\\b"
  );
  private static final Pattern DISPLAY_DATE = Pattern.compile(
    "(?i)\\b\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\s+\\d{2,4}\\b"
  );
  private static final Pattern INDIAN_PHONE = Pattern.compile(
    "(?:\\+|\\b)91[\\s-]?\\d{10}\\b"
  );
  /** ABHA digital-health-ID, dashed form: XX-XXXX-XXXX-XXXX. */
  private static final Pattern ABHA = Pattern.compile(
    "\\b\\d{2}-\\d{4}-\\d{4}-\\d{4}\\b"
  );
  /** ABHA dashless form — context-gated, see {@link #ABHA_CONTEXT}. */
  private static final Pattern ABHA_DASHLESS = Pattern.compile("\\b\\d{14}\\b");

  /** Cheap substring gate for {@link #AUTH_SCHEME}. */
  private static final List<String> AUTH_SCHEME_NEEDLES = List.of(
    "bearer",
    "basic"
  );

  /**
   * A bare 14-digit run is only treated as an ABHA id when one of these appears in the same value.
   * Without the gate, Unix timestamps, transaction ids, and hash fragments would all be redacted.
   *
   * <p>Matched as a case-insensitive substring, NOT as a quoted JSON key. The quoted lowercase form
   * this used to carry missed every spelling ABDM actually ships — {@code healthId},
   * {@code healthIdNumber}, {@code abhaNumber}, {@code ABHA} — which meant the dashless-ABHA rule was
   * effectively unreachable in production payloads.
   */
  private static final List<String> ABHA_CONTEXT = List.of(
    "abha",
    "healthid",
    "health_id",
    "abdm"
  );

  private ValueShapeRules() {}

  public static String apply(String value) {
    if (value == null || value.isEmpty()) return value;
    String v = value;
    if (v.indexOf('@') >= 0) {
      v = EMAIL.matcher(v).replaceAll(REDACTED);
    }
    if (containsAnyIgnoreCase(v, AUTH_SCHEME_NEEDLES)) {
      v = AUTH_SCHEME.matcher(v).replaceAll(REDACTED);
    }
    if (containsAnyIgnoreCase(v, CREDENTIAL_NEEDLES)) {
      v = TOKEN_JSON.matcher(v).replaceAll(
        "\"$1\": " + Matcher.quoteReplacement(REDACTED)
      );
      v = TOKEN_ASSIGNMENT.matcher(v).replaceAll(
        Matcher.quoteReplacement(REDACTED)
      );
    }
    if (v.indexOf('-') >= 0) {
      v = UUID.matcher(v).replaceAll(REDACTED);
      v = ISO_DATE.matcher(v).replaceAll(REDACTED);
      v = ABHA.matcher(v).replaceAll(REDACTED);
    }
    if (containsAnyIgnoreCase(v, ABHA_CONTEXT)) {
      v = ABHA_DASHLESS.matcher(v).replaceAll(REDACTED);
    }
    // Gated on any whitespace, not on ' ' specifically: the pattern's separator is \s+, so a
    // tab-separated "dob\t12\tJan\t1990" is a match the narrower gate used to hide.
    if (containsWhitespace(v)) {
      v = DISPLAY_DATE.matcher(v).replaceAll(REDACTED);
    }
    if (v.contains("91")) {
      v = INDIAN_PHONE.matcher(v).replaceAll(REDACTED);
    }
    return v;
  }

  private static boolean containsWhitespace(String haystack) {
    for (int i = 0; i < haystack.length(); i++) {
      if (Character.isWhitespace(haystack.charAt(i))) return true;
    }
    return false;
  }

  private static boolean containsAnyIgnoreCase(
    String haystack,
    List<String> needlesLower
  ) {
    String lower = haystack.toLowerCase();
    for (String needle : needlesLower) {
      if (lower.contains(needle)) return true;
    }
    return false;
  }

  static boolean containsAny(String haystack, List<String> needles) {
    for (String needle : needles) {
      if (haystack.contains(needle)) return true;
    }
    return false;
  }
}
