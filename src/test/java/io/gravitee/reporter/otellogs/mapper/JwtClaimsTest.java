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
package io.gravitee.reporter.otellogs.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtClaimsTest {

  private static String jwt(String headerJson, String payloadJson) {
    Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
    String h = enc.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
    String p = enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
    // Signature segment is arbitrary — we never validate it.
    return h + "." + p + ".sig";
  }

  @Test
  void wellFormedJwt_yieldsAllFourClaims() {
    String token = jwt(
      "{\"alg\":\"RS256\"}",
      "{\"aud\":\"app-1\",\"sub\":\"user-7\",\"iss\":\"https://am.example.com\",\"exp\":1735689600}"
    );

    var claims = JwtClaims.fromAuthorizationHeader(
      "Bearer " + token
    ).orElseThrow();

    assertThat(claims.aud()).isEqualTo("app-1");
    assertThat(claims.sub()).isEqualTo("user-7");
    assertThat(claims.iss()).isEqualTo("https://am.example.com");
    assertThat(claims.exp()).isEqualTo(1735689600L);
  }

  @Test
  void arrayValuedAud_collapsesToFirstElement() {
    String token = jwt(
      "{\"alg\":\"RS256\"}",
      "{\"aud\":[\"app-1\",\"app-2\"],\"sub\":\"user-7\"}"
    );

    var claims = JwtClaims.fromAuthorizationHeader(
      "Bearer " + token
    ).orElseThrow();

    assertThat(claims.aud()).isEqualTo("app-1");
    assertThat(claims.sub()).isEqualTo("user-7");
  }

  @Test
  void missingClaims_areNull() {
    String token = jwt("{\"alg\":\"RS256\"}", "{\"sub\":\"user-7\"}");

    var claims = JwtClaims.fromAuthorizationHeader(
      "Bearer " + token
    ).orElseThrow();

    assertThat(claims.aud()).isNull();
    assertThat(claims.sub()).isEqualTo("user-7");
    assertThat(claims.iss()).isNull();
    assertThat(claims.exp()).isNull();
  }

  @Test
  void malformedJwt_returnsEmpty_noThrow() {
    assertThat(JwtClaims.fromAuthorizationHeader("Bearer not.a.jwt")).isEmpty();
    assertThat(
      JwtClaims.fromAuthorizationHeader("Bearer onesegmentonly")
    ).isEmpty();
    assertThat(JwtClaims.fromAuthorizationHeader("Bearer a.b.c.d"))
      .as("four-segment string is not a JWS")
      .isEmpty();
  }

  @Test
  void badBase64Payload_returnsEmpty_noThrow() {
    // Middle segment contains characters invalid in base64url even with leniency.
    String token = "aGVhZGVy.@@@invalid@@@.sig";
    assertThat(JwtClaims.fromAuthorizationHeader("Bearer " + token)).isEmpty();
  }

  @Test
  void nonJsonPayload_returnsEmpty_noThrow() {
    Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
    String payload = enc.encodeToString(
      "this is not json".getBytes(StandardCharsets.UTF_8)
    );
    String token = "aGVhZGVy." + payload + ".sig";
    assertThat(JwtClaims.fromAuthorizationHeader("Bearer " + token)).isEmpty();
  }

  @Test
  void jsonArrayPayload_returnsEmpty() {
    // Valid JSON but not an object — claims live on a JSON object.
    Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
    String payload = enc.encodeToString(
      "[1,2,3]".getBytes(StandardCharsets.UTF_8)
    );
    String token = "aGVhZGVy." + payload + ".sig";
    assertThat(JwtClaims.fromAuthorizationHeader("Bearer " + token)).isEmpty();
  }

  @Test
  void missingHeader_returnsEmpty() {
    assertThat(JwtClaims.fromAuthorizationHeader(null)).isEmpty();
    assertThat(JwtClaims.fromAuthorizationHeader("")).isEmpty();
    assertThat(JwtClaims.fromAuthorizationHeader("   ")).isEmpty();
  }

  @Test
  void nonBearerScheme_returnsEmpty() {
    // Basic auth, API key, etc. — only Bearer JWTs decode.
    assertThat(
      JwtClaims.fromAuthorizationHeader("Basic dXNlcjpwYXNz")
    ).isEmpty();
    assertThat(JwtClaims.fromAuthorizationHeader("ApiKey abc123")).isEmpty();
  }

  @Test
  void bearerSchemeIsCaseInsensitive() {
    String token = jwt("{\"alg\":\"RS256\"}", "{\"sub\":\"user-7\"}");
    assertThat(JwtClaims.fromAuthorizationHeader("bearer " + token))
      .as("lower-case scheme")
      .isPresent();
    assertThat(JwtClaims.fromAuthorizationHeader("BEARER " + token))
      .as("upper-case scheme")
      .isPresent();
  }

  @Test
  void audArrayWithNoStringElement_isNull() {
    String token = jwt(
      "{\"alg\":\"RS256\"}",
      "{\"aud\":[123,true],\"sub\":\"user-7\"}"
    );

    var claims = JwtClaims.fromAuthorizationHeader(
      "Bearer " + token
    ).orElseThrow();

    assertThat(claims.aud()).isNull();
    assertThat(claims.sub()).isEqualTo("user-7");
  }

  @Test
  void numericExp_isParsedAsLong() {
    String token = jwt("{\"alg\":\"RS256\"}", "{\"exp\":1735689600}");

    var claims = JwtClaims.fromAuthorizationHeader(
      "Bearer " + token
    ).orElseThrow();

    assertThat(claims.exp()).isEqualTo(1735689600L);
  }

  @Test
  void nonNumericExp_isNull() {
    String token = jwt("{\"alg\":\"RS256\"}", "{\"exp\":\"not-a-number\"}");

    var claims = JwtClaims.fromAuthorizationHeader(
      "Bearer " + token
    ).orElseThrow();

    assertThat(claims.exp()).isNull();
  }
}
