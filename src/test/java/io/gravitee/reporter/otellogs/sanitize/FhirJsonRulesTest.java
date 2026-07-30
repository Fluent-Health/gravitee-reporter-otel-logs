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

class FhirJsonRulesTest {

  @Test
  void redactsHumanNamePreservingTheKey() {
    String out = FhirJsonRules.apply(
      "{\"name\":[{\"given\":[\"Jane\"],\"family\":\"Doe\"}]}"
    );

    // Key preserved as a triage signal — operators see WHICH field was redacted.
    assertThat(out).contains("\"given\": [REDACTED]");
    assertThat(out).contains("\"family\": [REDACTED]");
    assertThat(out).doesNotContain("Jane").doesNotContain("Doe");
  }

  @Test
  void redactsAddressPreservingTheKey() {
    String out = FhirJsonRules.apply(
      "{\"address\":[{\"line\":[\"42 Main St\"],\"city\":\"Mumbai\",\"postalCode\":\"400001\"}]}"
    );

    assertThat(out).contains("\"line\": [REDACTED]");
    assertThat(out).contains("\"city\": [REDACTED]");
    assertThat(out).contains("\"postalCode\": [REDACTED]");
    assertThat(out).doesNotContain("Main St").doesNotContain("Mumbai");
  }

  @Test
  void redactsContactPointInBothOrderings() {
    assertThat(
      FhirJsonRules.apply("{\"system\":\"phone\",\"value\":\"+15551234567\"}")
    ).doesNotContain("5551234567");

    assertThat(
      FhirJsonRules.apply("{\"value\":\"+15551234567\",\"system\":\"phone\"}")
    ).doesNotContain("5551234567");
  }

  @Test
  void redactsContactPointValueAcrossIntermediateFields() {
    String out = FhirJsonRules.apply(
      "{\"system\":\"email\",\"use\":\"home\",\"rank\":1,\"value\":\"jane@example.com\"}"
    );

    assertThat(out).doesNotContain("jane@example.com");
  }

  @Test
  void redactsAnnotationText() {
    String out = FhirJsonRules.apply(
      "{\"note\":[{\"text\":\"Patient reports chest pain\"}]}"
    );

    assertThat(out).doesNotContain("chest pain");
  }

  @Test
  void redactsReferenceDisplayInBothOrderings() {
    assertThat(
      FhirJsonRules.apply(
        "{\"reference\":\"Patient/123\",\"display\":\"Jane Doe\"}"
      )
    ).doesNotContain("Jane Doe");

    assertThat(
      FhirJsonRules.apply(
        "{\"display\":\"Jane Doe\",\"reference\":\"Patient/123\"}"
      )
    ).doesNotContain("Jane Doe");
  }

  @Test
  void redactsContactDetailName() {
    String out = FhirJsonRules.apply(
      "{\"contact\":[{\"name\":\"Dr. Smith\",\"telecom\":[]}]}"
    );

    assertThat(out).doesNotContain("Dr. Smith");
  }

  @Test
  void leavesNonFhirJsonAlone() {
    String benign = "{\"status\":\"ok\",\"count\":3}";

    assertThat(FhirJsonRules.apply(benign)).isEqualTo(benign);
  }

  @Test
  void leavesGenericDisplayKeyAloneWithoutReferenceContext() {
    // "display" without "reference" is not a FHIR Reference.display — the gate must hold,
    // otherwise every UI label in every payload gets redacted.
    String benign = "{\"display\":\"Dashboard\"}";

    assertThat(FhirJsonRules.apply(benign)).isEqualTo(benign);
  }

  @Test
  void leavesGenericTextKeyAloneWithoutNoteContext() {
    String benign = "{\"text\":\"Submit\"}";

    assertThat(FhirJsonRules.apply(benign)).isEqualTo(benign);
  }

  @Test
  void toleratesNullAndEmpty() {
    assertThat(FhirJsonRules.apply(null)).isNull();
    assertThat(FhirJsonRules.apply("")).isEmpty();
  }

  @Test
  void everyKeyIsRedactedInBothDirections() {
    // Guards trigger/pattern drift, a known PII-leak class: a pattern whose gate does not
    // fire is dead code, and a gate without its pattern silently passes PHI through.
    //
    // The old version of this test only walked trigger → pattern, so the LEAKING direction
    // — a key in the pattern but missing from the trigger list — was untested, and
    // "district" and "state" were shipping raw. Iterating the shared key constants covers
    // both directions by construction: the triggers and the pattern alternation are now
    // derived from these same lists, and every key in them must actually redact.
    for (String key : FhirJsonRules.HUMAN_NAME_KEYS) {
      assertThat(FhirJsonRules.apply("{\"" + key + "\":\"X\"}"))
        .as("HumanName key '%s' must be redacted", key)
        .doesNotContain("\"X\"");
    }
    for (String key : FhirJsonRules.ADDRESS_KEYS) {
      assertThat(FhirJsonRules.apply("{\"" + key + "\":\"X\"}"))
        .as("Address key '%s' must be redacted", key)
        .doesNotContain("\"X\"");
    }
  }

  @Test
  void redactsTheAddressKeysThatUsedToHaveNoTrigger() {
    // Live PHI leak: both keys were in the ADDRESS pattern but absent from its trigger list.
    assertThat(
      FhirJsonRules.apply("{\"district\":\"Bengaluru South\"}")
    ).doesNotContain("Bengaluru South");
    assertThat(FhirJsonRules.apply("{\"state\":\"Karnataka\"}")).doesNotContain(
      "Karnataka"
    );
    // And in the realistic shape, where a trigger for a sibling key would have masked the bug.
    assertThat(
      FhirJsonRules.apply(
        "{\"address\":[{\"district\":\"Bengaluru South\",\"state\":\"Karnataka\"}]}"
      )
    )
      .doesNotContain("Bengaluru South")
      .doesNotContain("Karnataka");
  }

  @Test
  void doesNotThrowOnALongClinicalNarrative() {
    // The old JSON_STRING idiom recursed once per iteration and threw StackOverflowError —
    // an Error, so it escaped every catch layer and the whole log record was lost. A FHIR
    // note/text narrative over ~2 KB was enough to trigger it.
    String narrative = "A".repeat(60_000);

    assertThatCode(() -> {
      assertThat(
        FhirJsonRules.apply("{\"note\":[{\"text\":\"" + narrative + "\"}]}")
      ).doesNotContain(narrative);
      assertThat(
        FhirJsonRules.apply("{\"family\":\"" + narrative + "\"}")
      ).doesNotContain(narrative);
    }).doesNotThrowAnyException();
  }

  @Test
  void matchesQuotedValuesContainingEscapedQuotesIdentically() {
    // The possessive rewrite must match exactly the span the recursive form did.
    assertThat(FhirJsonRules.apply("{\"family\":\"Do\\\"e\"}")).isEqualTo(
      "{\"family\": [REDACTED]}"
    );
  }
}
