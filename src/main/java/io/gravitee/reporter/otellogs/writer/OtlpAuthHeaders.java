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
package io.gravitee.reporter.otellogs.writer;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.function.Supplier;

public final class OtlpAuthHeaders {

  private OtlpAuthHeaders() {}

  public static Supplier<Map<String, String>> none() {
    return Map::of;
  }

  public static Supplier<Map<String, String>> staticHeaders(
    Map<String, String> headers
  ) {
    Map<String, String> snapshot = Map.copyOf(headers);
    return () -> snapshot;
  }

  public static Supplier<Map<String, String>> gcpAdc() {
    GoogleCredentials creds;
    try {
      creds = GoogleCredentials.getApplicationDefault().createScoped(
        "https://www.googleapis.com/auth/cloud-platform"
      );
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load ADC for OTLP auth", e);
    }
    return () -> {
      try {
        creds.refreshIfExpired();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return Map.of(
        "Authorization",
        "Bearer " + creds.getAccessToken().getTokenValue()
      );
    };
  }
}
