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

import com.google.gson.Gson;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.otellogs.config.MaskingConfiguration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Facade over the three sanitizer layers — the only class the mappers know about.
 *
 * <p>Sits at the mapper layer, upstream of the gcloud/otlp exporter split in
 * {@code OtelLogsReporter#buildLogExporter}, so both export modes are covered by construction and a
 * third exporter added later cannot bypass it.
 *
 * <p>Every entry point is failure-safe: sanitisation must never drop a log record, so a failure
 * returns the redaction marker rather than propagating. A mangled attribute is recoverable; a leaked
 * one is not.
 */
public final class PiiSanitizer {

  private static final Logger log = LoggerFactory.getLogger(PiiSanitizer.class);

  /**
   * Bodies longer than this are dropped wholesale rather than sanitized — fail-CLOSED. An
   * over-cap body is replaced entirely instead of being partially scanned, because the input is an
   * HTTP body of arbitrary, operator-influenced size and a partial scan would ship the unscanned
   * tail verbatim.
   */
  static final int MAX_BODY_CHARS = 65536;

  /**
   * Free-text cap for {@link #text}, four times the body cap. Generous on purpose: the point of
   * {@link #text} is that a long {@code error.message} keeps its diagnostic value, so the cap only
   * exists to bound the regex cost of a pathological input, not to trim ordinary messages.
   */
  static final int MAX_TEXT_CHARS = 262144;

  /**
   * Returned instead of a bare marker when {@link #headersAsJson} fails, so the attribute stays
   * parseable JSON for anything reading it downstream.
   */
  static final String REDACTED_JSON =
    "{\"_\":\"" + ValueShapeRules.REDACTED + "\"}";

  /**
   * Sanitisation failures are logged at WARN, but only the first few: a systematic failure would
   * redact every attribute on every record, and an unthrottled log line would then flood the gateway
   * log with the very volume it is trying to report. Silence was worse — before this, a bug making
   * every call throw would have redacted 100% of attributes with zero operator signal.
   *
   * <p>Counted per sanitizer instance rather than per class: the reporter builds exactly one instance
   * and shares it with every mapper, so per-instance is per-process in production, and it keeps the
   * throttle observable in a test instead of depending on JVM-wide test ordering.
   */
  static final int MAX_FAILURE_LOGS = 5;

  private static final Gson GSON = new Gson();

  private final boolean enabled;
  private final SensitiveKeys keys;
  private final AtomicInteger failureLogs = new AtomicInteger();

  private PiiSanitizer(boolean enabled, SensitiveKeys keys) {
    this.enabled = enabled;
    this.keys = keys;
  }

  /** No-op sanitizer. The default, matching {@code logs.masking.enabled=false}. */
  public static PiiSanitizer disabled() {
    return new PiiSanitizer(false, SensitiveKeys.withDefaults());
  }

  public static PiiSanitizer from(MaskingConfiguration cfg) {
    if (cfg == null || !cfg.isEnabled()) return disabled();
    return new PiiSanitizer(true, new SensitiveKeys(cfg.getExtraHeaders()));
  }

  /**
   * Serialises headers to JSON, masking sensitive ones by name and running the value layers over
   * whatever survives — so a bearer token in a header that is not on the denylist still dies.
   *
   * @return null when there are no headers, so callers can skip the attribute entirely
   */
  public String headersAsJson(HttpHeaders headers) {
    if (headers == null) return null;
    try {
      if (headers.isEmpty()) return null;
      if (!enabled) return GSON.toJson(headers.toListValuesMap());
      Map<String, List<String>> masked = new LinkedHashMap<>();
      headers
        .toListValuesMap()
        .forEach((name, values) -> masked.put(name, maskValues(name, values)));
      return GSON.toJson(masked);
    } catch (Throwable t) {
      warnFailure("headersAsJson", t);
      return REDACTED_JSON;
    }
  }

  /**
   * Every entry point catches {@link Throwable}, not {@link RuntimeException}. A
   * {@link StackOverflowError} from a pathological regex input is an {@code Error}, so it used to
   * escape both this class and {@code OtelLogsReporter.report}'s {@code catch (Exception)} — losing
   * the record entirely and propagating an {@code Error} into the gateway's reporting call. The
   * fail-closed guarantee this class documents only holds if it covers that case.
   */
  private void warnFailure(String entryPoint, Throwable t) {
    int seen = failureLogs.incrementAndGet();
    if (seen > MAX_FAILURE_LOGS) return;
    log.warn(
      "PII sanitisation failed in {} ({}) — the affected attribute was redacted{}",
      entryPoint,
      t.getClass().getName(),
      seen == MAX_FAILURE_LOGS
        ? "; further sanitisation failures will not be logged"
        : ""
    );
  }

  private List<String> maskValues(String name, List<String> values) {
    if (keys.isSensitive(name)) return List.of(ValueShapeRules.REDACTED);
    if (values == null) return List.of();
    List<String> out = new ArrayList<>(values.size());
    for (String value : values) {
      out.add(FhirJsonRules.apply(ValueShapeRules.apply(value)));
    }
    return out;
  }

  /** Request/response bodies — value + FHIR layers, subject to {@link #MAX_BODY_CHARS}. */
  public String body(String raw) {
    if (!enabled || raw == null || raw.isEmpty()) return raw;
    try {
      if (raw.length() > MAX_BODY_CHARS) return ValueShapeRules.REDACTED;
      return FhirJsonRules.apply(ValueShapeRules.apply(raw));
    } catch (Throwable t) {
      warnFailure("body", t);
      return ValueShapeRules.REDACTED;
    }
  }

  /**
   * Short free text such as {@code error.message} — value + FHIR layers, under the much more generous
   * {@link #MAX_TEXT_CHARS} cap rather than {@link #MAX_BODY_CHARS}. Kept distinct from {@link #body}
   * because the tight cap is a body-specific concern: silently dropping a long error string would
   * destroy the diagnostic for no compliance benefit. The loose cap still exists, because "no cap at
   * all" leaves the regex cost of a pathological value unbounded.
   */
  public String text(String raw) {
    if (!enabled || raw == null || raw.isEmpty()) return raw;
    try {
      if (raw.length() > MAX_TEXT_CHARS) return ValueShapeRules.REDACTED;
      return FhirJsonRules.apply(ValueShapeRules.apply(raw));
    } catch (Throwable t) {
      warnFailure("text", t);
      return ValueShapeRules.REDACTED;
    }
  }

  /**
   * URLs — value layer only. The FHIR layer keys on JSON shapes that cannot occur in a URL, and the
   * path has already been through {@code OtelLabels.sanitizePath}. This closes the query-string gap:
   * {@code ?email=…&access_token=…} was previously emitted verbatim on every record.
   */
  public String url(String raw) {
    if (!enabled || raw == null || raw.isEmpty()) return raw;
    try {
      return ValueShapeRules.apply(raw);
    } catch (Throwable t) {
      warnFailure("url", t);
      return ValueShapeRules.REDACTED;
    }
  }
}
