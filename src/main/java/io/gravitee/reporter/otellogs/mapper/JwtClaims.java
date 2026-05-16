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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Decoded subset of a JWT payload used for human navigation only — never
 * for authz decisions. Signature is not validated; the gateway has already
 * validated the token by the time the reporter sees the request.
 *
 * <p>Each field is nullable: a claim absent from the payload (or a payload
 * we can't parse) yields {@link Optional#empty()} on the corresponding
 * accessor, never throws.
 */
public record JwtClaims(String aud, String sub, String iss, Long exp) {
  /**
   * Decodes the payload of an {@code Authorization: Bearer <jwt>} header.
   * Returns {@link Optional#empty()} for anything other than a well-formed,
   * three-segment JWT whose middle segment is base64url-encoded JSON — that
   * includes JWE tokens, malformed strings, missing headers, and non-bearer
   * schemes. Callers must treat the result as best-effort: the goal is to
   * surface claims for click-through when we can, and silently no-op when
   * we can't.
   *
   * <p>{@code aud} may appear as a string or as an array; arrays collapse to
   * their first element (the AM application ID is single-valued in practice).
   */
  public static Optional<JwtClaims> fromAuthorizationHeader(String header) {
    if (header == null || header.isBlank()) return Optional.empty();
    String stripped = header.strip();
    if (stripped.length() < 7) return Optional.empty();
    if (!stripped.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return Optional.empty();
    }
    String token = stripped.substring(7).strip();
    return fromToken(token);
  }

  static Optional<JwtClaims> fromToken(String token) {
    if (token == null || token.isBlank()) return Optional.empty();
    String[] parts = token.split("\\.");
    if (parts.length != 3) return Optional.empty();
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
      String json = new String(decoded, StandardCharsets.UTF_8);
      JsonElement parsed = JsonParser.parseString(json);
      if (!parsed.isJsonObject()) return Optional.empty();
      JsonObject obj = parsed.getAsJsonObject();
      return Optional.of(
        new JwtClaims(
          firstAud(obj.get("aud")),
          asString(obj.get("sub")),
          asString(obj.get("iss")),
          asLong(obj.get("exp"))
        )
      );
    } catch (RuntimeException e) {
      // base64 errors, JSON syntax errors, unexpected element types — anything
      // that gets thrown during decode is treated as "no claims available".
      return Optional.empty();
    }
  }

  private static String firstAud(JsonElement el) {
    if (el == null || el.isJsonNull()) return null;
    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
      return el.getAsString();
    }
    if (el.isJsonArray()) {
      JsonArray arr = el.getAsJsonArray();
      for (JsonElement item : arr) {
        if (
          item != null &&
          item.isJsonPrimitive() &&
          item.getAsJsonPrimitive().isString()
        ) {
          return item.getAsString();
        }
      }
    }
    return null;
  }

  private static String asString(JsonElement el) {
    if (el == null || el.isJsonNull()) return null;
    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
      return el.getAsString();
    }
    return null;
  }

  private static Long asLong(JsonElement el) {
    if (el == null || el.isJsonNull()) return null;
    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
      return el.getAsLong();
    }
    return null;
  }
}
