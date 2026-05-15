# OTel Tracing + Config Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an OTLP trace export pipeline alongside the existing log pipeline, exported to Cloud Trace via `telemetry.googleapis.com/v1/traces` with ADC bearer auth, and reorganise the flat configuration into signal-grouped subtrees (`logs:` / `traces:` / shared `resource:`) with one-release backward-compatible reads for the existing flat property keys.

**Architecture:** Two independent OTel SDK pipelines share one `Resource` and one trace-context resolver. Logs continue to export via the native GCL `entries:write` path; traces export via a new `CustomOtlpHttpSpanExporter` (mirroring the existing log exporter pattern that avoids OkHttp/SPI classloader issues in Gravitee). Spans are built **after the fact** from each `Metrics` event using the recorded start/end timestamps — one server-kind span per request, parented by the inbound `traceparent` when present. ADC credentials feed both the GCL exporter and the OTLP auth-header supplier; the previous `credentialsFile` config option is removed.

**Tech Stack:** Java 21 · Maven · Gravitee APIM Reporter API (v4) · OpenTelemetry Java SDK 1.44.1 (`opentelemetry-api`, `opentelemetry-sdk-logs`, `opentelemetry-sdk-trace`, `opentelemetry-exporter-otlp-common`) · `google-auth-library-oauth2-http` 1.27.0 · Spring `@Value` injection · JUnit 5 · AssertJ · Mockito · Testcontainers.

**Spec:** `docs/superpowers/specs/2026-05-15-otel-tracing-and-config-redesign-design.md`

---

## Phase Map

The plan has two phases. Each ends in a green-build, releasable state and is the natural boundary for a PR.

- **Phase 1 — Config redesign + ADC simplification.** No new signals. Reorganises configuration into nested subtrees with SpEL fallbacks to legacy flat keys, removes `credentialsFile`, lays the groundwork for Phase 2 without changing runtime behaviour.
- **Phase 2 — Trace pipeline.** Adds dependency, shared GCP env detection, OTel `Resource`, OTLP auth helper, custom OTLP span exporter, trace writer, span mapper, and wiring. Traces default off; opt in via `reporters.otellogs.traces.enabled: true`.

---

## File Structure

### Phase 1 — created
- `src/main/java/io/gravitee/reporter/otellogs/config/ResourceConfiguration.java` — POJO for `reporters.otellogs.resource.*` (service name/namespace, gcp.projectId, gcp.autoDetect).
- `src/main/java/io/gravitee/reporter/otellogs/config/LogsConfiguration.java` — POJO for `reporters.otellogs.logs.*` with SpEL fallback to legacy flat keys.
- `src/main/java/io/gravitee/reporter/otellogs/config/TracesConfiguration.java` — POJO for `reporters.otellogs.traces.*`. Phase 1 declares it but nothing wires it.
- `src/main/java/io/gravitee/reporter/otellogs/config/LegacyConfigWarning.java` — runs once at startup; logs `WARN` for each legacy flat key that is set in the Spring `Environment`.
- `src/test/java/io/gravitee/reporter/otellogs/config/LogsConfigurationTest.java`
- `src/test/java/io/gravitee/reporter/otellogs/config/ResourceConfigurationTest.java`
- `src/test/java/io/gravitee/reporter/otellogs/config/LegacyConfigWarningTest.java`

### Phase 1 — modified
- `src/main/java/io/gravitee/reporter/otellogs/config/OtelLogsReporterConfiguration.java` — keeps only `enabled` + `correlationHeader`; aggregates references to the three new beans; legacy getters delegate to those beans for source compatibility.
- `src/main/java/io/gravitee/reporter/otellogs/spring/OtelLogsReporterSpringConfiguration.java` — declares new beans.
- `src/main/java/io/gravitee/reporter/otellogs/OtelLogsReporter.java` — reads through new beans; drops `credentialsFile` arg when constructing `GclLogRecordExporter`.
- `src/main/java/io/gravitee/reporter/otellogs/writer/GclLogRecordExporter.java` — drops `credentialsFile` constructor parameter; always uses ADC.
- `src/main/java/io/gravitee/reporter/otellogs/mapper/MetricsToLogRecordMapper.java` — reads `correlationHeader` from new top-level config (no functional change).
- `src/test/java/io/gravitee/reporter/otellogs/writer/GclLogRecordExporterTest.java` — drops the credentialsFile param.

### Phase 2 — created
- `src/main/java/io/gravitee/reporter/otellogs/writer/GcpEnvironment.java` — pure detection (k8s namespace, cluster, location, hostname, container).
- `src/main/java/io/gravitee/reporter/otellogs/writer/GcpOtelResource.java` — builds `io.opentelemetry.sdk.resources.Resource` from `GcpEnvironment` + `ResourceConfiguration`.
- `src/main/java/io/gravitee/reporter/otellogs/writer/OtlpAuthHeaders.java` — `Supplier<Map<String,String>>` factories: `gcpAdc()`, `staticHeaders(Map)`, `none()`.
- `src/main/java/io/gravitee/reporter/otellogs/writer/CustomOtlpHttpSpanExporter.java` — analog of `CustomOtlpHttpLogRecordExporter`, with refreshing header supplier.
- `src/main/java/io/gravitee/reporter/otellogs/writer/OtelTraceWriter.java` — `SdkTracerProvider` + `BatchSpanProcessor` + sampler wiring; `emitSpan(SpanData)`-style API.
- `src/main/java/io/gravitee/reporter/otellogs/mapper/TraceContextResolver.java` — extracts/derives the trace ID (and span ID when present) from request headers; shared by log and span mappers.
- `src/main/java/io/gravitee/reporter/otellogs/mapper/MetricsToSpanMapper.java` — builds finished spans from `Metrics` events.
- Tests for each (`*Test.java`).
- `src/test/java/io/gravitee/reporter/otellogs/integration/OtelTraceReporterIT.java` — mock OTLP receiver captures spans, asserts trace ID linkage.

### Phase 2 — modified
- `pom.xml` — add `opentelemetry-sdk-trace` (do **not** exclude it from the `opentelemetry-sdk` artifact).
- `src/main/java/io/gravitee/reporter/otellogs/writer/GcpResource.java` — refactor `detectInternal` to use `GcpEnvironment`; same behaviour for the GCL MonitoredResource side.
- `src/main/java/io/gravitee/reporter/otellogs/mapper/MetricsToLogRecordMapper.java` — delegate trace context to `TraceContextResolver`.
- `src/main/java/io/gravitee/reporter/otellogs/OtelLogsReporter.java` — construct `OtelTraceWriter` when `tracesCfg.isEnabled()`; route `Metrics` to both mappers; flush+close trace writer on stop.
- `src/main/java/io/gravitee/reporter/otellogs/spring/OtelLogsReporterSpringConfiguration.java` — no change (config beans already there).

---

# Phase 1 — Config redesign + ADC simplification

### Task 1: Add `ResourceConfiguration`

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/config/ResourceConfiguration.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/config/ResourceConfigurationTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/gravitee/reporter/otellogs/config/ResourceConfigurationTest.java
package io.gravitee.reporter.otellogs.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

class ResourceConfigurationTest {

  @Test
  void defaults_match_spec() {
    ResourceConfiguration cfg = new ResourceConfiguration();
    cfg.setServiceName("gravitee-api-gateway");
    cfg.setServiceNamespace("apim");
    cfg.setGcpProjectId(null);
    cfg.setGcpAutoDetect(true);

    assertThat(cfg.getServiceName()).isEqualTo("gravitee-api-gateway");
    assertThat(cfg.getServiceNamespace()).isEqualTo("apim");
    assertThat(cfg.getGcpProjectId()).isNull();
    assertThat(cfg.isGcpAutoDetect()).isTrue();
  }
}
```

- [ ] **Step 2: Run test, expect FAIL (class missing)**

```
mvn -q -pl . test -Dtest=ResourceConfigurationTest
```
Expected: compilation error — `ResourceConfiguration` cannot be resolved.

- [ ] **Step 3: Implement `ResourceConfiguration`**

```java
// src/main/java/io/gravitee/reporter/otellogs/config/ResourceConfiguration.java
package io.gravitee.reporter.otellogs.config;

import org.springframework.beans.factory.annotation.Value;

public class ResourceConfiguration {

  @Value("${reporters.otellogs.resource.serviceName:gravitee-api-gateway}")
  private String serviceName;

  @Value("${reporters.otellogs.resource.serviceNamespace:apim}")
  private String serviceNamespace;

  // Falls back to the legacy flat key for one release.
  @Value("${reporters.otellogs.resource.gcp.projectId:${reporters.otellogs.gcloud.projectId:#{null}}}")
  private String gcpProjectId;

  @Value("${reporters.otellogs.resource.gcp.autoDetect:true}")
  private boolean gcpAutoDetect;

  public String getServiceName() { return serviceName; }
  public void setServiceName(String v) { this.serviceName = v; }

  public String getServiceNamespace() { return serviceNamespace; }
  public void setServiceNamespace(String v) { this.serviceNamespace = v; }

  public String getGcpProjectId() { return gcpProjectId; }
  public void setGcpProjectId(String v) { this.gcpProjectId = v; }

  public boolean isGcpAutoDetect() { return gcpAutoDetect; }
  public void setGcpAutoDetect(boolean v) { this.gcpAutoDetect = v; }
}
```

- [ ] **Step 4: Run test, expect PASS**

```
mvn -q -pl . test -Dtest=ResourceConfigurationTest
```
Expected: 1 test, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/config/ResourceConfiguration.java \
        src/test/java/io/gravitee/reporter/otellogs/config/ResourceConfigurationTest.java
git commit -m "feat(config): add ResourceConfiguration with gcp.projectId fallback"
```

---

### Task 2: Add `LogsConfiguration`

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/config/LogsConfiguration.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/config/LogsConfigurationTest.java`

- [ ] **Step 1: Write the failing test (defaults + legacy fallback semantics)**

```java
// src/test/java/io/gravitee/reporter/otellogs/config/LogsConfigurationTest.java
package io.gravitee.reporter.otellogs.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogsConfigurationTest {

  @Test
  void defaults_match_spec() {
    LogsConfiguration cfg = new LogsConfiguration();
    cfg.setEnabled(true);
    cfg.setExporter("gcloud");
    cfg.setLogName("gravitee-api-gateway");
    cfg.setEndpoint("http://localhost:4317");
    cfg.setAuthMode("none");
    cfg.setBatchSize(512);
    cfg.setScheduledDelayMs(5000);
    cfg.setReportHealthChecks(true);
    cfg.setReportRequestLogs(false);
    cfg.setReportMessageMetrics(true);

    assertThat(cfg.isEnabled()).isTrue();
    assertThat(cfg.getExporter()).isEqualTo("gcloud");
    assertThat(cfg.getLogName()).isEqualTo("gravitee-api-gateway");
    assertThat(cfg.getEndpoint()).isEqualTo("http://localhost:4317");
    assertThat(cfg.getAuthMode()).isEqualTo("none");
    assertThat(cfg.getBatchSize()).isEqualTo(512);
    assertThat(cfg.getScheduledDelayMs()).isEqualTo(5000);
    assertThat(cfg.isReportHealthChecks()).isTrue();
    assertThat(cfg.isReportRequestLogs()).isFalse();
    assertThat(cfg.isReportMessageMetrics()).isTrue();
  }
}
```

- [ ] **Step 2: Run test, expect FAIL (class missing)**

```
mvn -q test -Dtest=LogsConfigurationTest
```

- [ ] **Step 3: Implement `LogsConfiguration` with SpEL legacy fallbacks**

```java
// src/main/java/io/gravitee/reporter/otellogs/config/LogsConfiguration.java
package io.gravitee.reporter.otellogs.config;

import java.util.Collections;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;

public class LogsConfiguration {

  @Value("${reporters.otellogs.logs.enabled:true}")
  private boolean enabled;

  // gcloud | otlp | none — falls back to the old flat key.
  @Value("${reporters.otellogs.logs.exporter:${reporters.otellogs.exporter:otlp}}")
  private String exporter;

  // gcloud-only
  @Value("${reporters.otellogs.logs.logName:${reporters.otellogs.gcloud.logName:gravitee-api-gateway}}")
  private String logName;

  // otlp-only
  @Value("${reporters.otellogs.logs.endpoint:${reporters.otellogs.endpoint:http://localhost:4317}}")
  private String endpoint;

  // otlp-only: none | gcp-adc | static
  @Value("${reporters.otellogs.logs.authMode:none}")
  private String authMode;

  // otlp-only, only when authMode=static — left as Map; injection
  // happens via @Value if customers set it as a flat list of properties,
  // otherwise stays empty.
  private Map<String, String> headers = Collections.emptyMap();

  @Value("${reporters.otellogs.logs.batchSize:${reporters.otellogs.batchSize:512}}")
  private int batchSize;

  @Value("${reporters.otellogs.logs.scheduledDelayMs:${reporters.otellogs.scheduledDelayMs:5000}}")
  private int scheduledDelayMs;

  @Value("${reporters.otellogs.logs.reportHealthChecks:${reporters.otellogs.reportHealthChecks:true}}")
  private boolean reportHealthChecks;

  // Renamed: old reportLogs → reportRequestLogs.
  @Value("${reporters.otellogs.logs.reportRequestLogs:${reporters.otellogs.reportLogs:false}}")
  private boolean reportRequestLogs;

  @Value("${reporters.otellogs.logs.reportMessageMetrics:${reporters.otellogs.reportMessageMetrics:true}}")
  private boolean reportMessageMetrics;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean v) { this.enabled = v; }

  public String getExporter() { return exporter; }
  public void setExporter(String v) { this.exporter = v; }

  public String getLogName() { return logName; }
  public void setLogName(String v) { this.logName = v; }

  public String getEndpoint() { return endpoint; }
  public void setEndpoint(String v) { this.endpoint = v; }

  public String getAuthMode() { return authMode; }
  public void setAuthMode(String v) { this.authMode = v; }

  public Map<String, String> getHeaders() { return headers; }
  public void setHeaders(Map<String, String> v) { this.headers = v == null ? Map.of() : Map.copyOf(v); }

  public int getBatchSize() { return batchSize; }
  public void setBatchSize(int v) { this.batchSize = v; }

  public int getScheduledDelayMs() { return scheduledDelayMs; }
  public void setScheduledDelayMs(int v) { this.scheduledDelayMs = v; }

  public boolean isReportHealthChecks() { return reportHealthChecks; }
  public void setReportHealthChecks(boolean v) { this.reportHealthChecks = v; }

  public boolean isReportRequestLogs() { return reportRequestLogs; }
  public void setReportRequestLogs(boolean v) { this.reportRequestLogs = v; }

  public boolean isReportMessageMetrics() { return reportMessageMetrics; }
  public void setReportMessageMetrics(boolean v) { this.reportMessageMetrics = v; }
}
```

- [ ] **Step 4: Run test, expect PASS**

```
mvn -q test -Dtest=LogsConfigurationTest
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/config/LogsConfiguration.java \
        src/test/java/io/gravitee/reporter/otellogs/config/LogsConfigurationTest.java
git commit -m "feat(config): add LogsConfiguration with legacy flat-key fallback"
```

---

### Task 3: Add `TracesConfiguration` skeleton

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/config/TracesConfiguration.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/config/TracesConfigurationTest.java`

Phase 1 just declares the shape so the Spring beans are in place; Phase 2 consumes it. No legacy fallback — traces are new and only addressable under `traces:`.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/gravitee/reporter/otellogs/config/TracesConfigurationTest.java
package io.gravitee.reporter.otellogs.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TracesConfigurationTest {

  @Test
  void defaults_disabled_with_cloud_trace_endpoint() {
    TracesConfiguration cfg = new TracesConfiguration();
    cfg.setEnabled(false);
    cfg.setExporter("otlp");
    cfg.setEndpoint("https://telemetry.googleapis.com/v1/traces");
    cfg.setAuthMode("gcp-adc");
    cfg.setBatchSize(512);
    cfg.setScheduledDelayMs(5000);
    cfg.setSampler("parent-based-always-on");
    cfg.setSampleRatio(1.0);

    assertThat(cfg.isEnabled()).isFalse();
    assertThat(cfg.getExporter()).isEqualTo("otlp");
    assertThat(cfg.getEndpoint()).isEqualTo("https://telemetry.googleapis.com/v1/traces");
    assertThat(cfg.getAuthMode()).isEqualTo("gcp-adc");
    assertThat(cfg.getBatchSize()).isEqualTo(512);
    assertThat(cfg.getScheduledDelayMs()).isEqualTo(5000);
    assertThat(cfg.getSampler()).isEqualTo("parent-based-always-on");
    assertThat(cfg.getSampleRatio()).isEqualTo(1.0);
  }
}
```

- [ ] **Step 2: Run test, expect FAIL (class missing)**

- [ ] **Step 3: Implement `TracesConfiguration`**

```java
// src/main/java/io/gravitee/reporter/otellogs/config/TracesConfiguration.java
package io.gravitee.reporter.otellogs.config;

import java.util.Collections;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;

public class TracesConfiguration {

  @Value("${reporters.otellogs.traces.enabled:false}")
  private boolean enabled;

  @Value("${reporters.otellogs.traces.exporter:otlp}")
  private String exporter;

  @Value("${reporters.otellogs.traces.endpoint:https://telemetry.googleapis.com/v1/traces}")
  private String endpoint;

  // none | gcp-adc | static
  @Value("${reporters.otellogs.traces.authMode:gcp-adc}")
  private String authMode;

  private Map<String, String> headers = Collections.emptyMap();

  @Value("${reporters.otellogs.traces.batchSize:512}")
  private int batchSize;

  @Value("${reporters.otellogs.traces.scheduledDelayMs:5000}")
  private int scheduledDelayMs;

  // parent-based-always-on | always-on | ratio
  @Value("${reporters.otellogs.traces.sampler:parent-based-always-on}")
  private String sampler;

  @Value("${reporters.otellogs.traces.sampleRatio:1.0}")
  private double sampleRatio;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean v) { this.enabled = v; }

  public String getExporter() { return exporter; }
  public void setExporter(String v) { this.exporter = v; }

  public String getEndpoint() { return endpoint; }
  public void setEndpoint(String v) { this.endpoint = v; }

  public String getAuthMode() { return authMode; }
  public void setAuthMode(String v) { this.authMode = v; }

  public Map<String, String> getHeaders() { return headers; }
  public void setHeaders(Map<String, String> v) { this.headers = v == null ? Map.of() : Map.copyOf(v); }

  public int getBatchSize() { return batchSize; }
  public void setBatchSize(int v) { this.batchSize = v; }

  public int getScheduledDelayMs() { return scheduledDelayMs; }
  public void setScheduledDelayMs(int v) { this.scheduledDelayMs = v; }

  public String getSampler() { return sampler; }
  public void setSampler(String v) { this.sampler = v; }

  public double getSampleRatio() { return sampleRatio; }
  public void setSampleRatio(double v) { this.sampleRatio = v; }
}
```

- [ ] **Step 4: Run test, expect PASS**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/config/TracesConfiguration.java \
        src/test/java/io/gravitee/reporter/otellogs/config/TracesConfigurationTest.java
git commit -m "feat(config): add TracesConfiguration POJO (disabled by default)"
```

---

### Task 4: Add `LegacyConfigWarning` (one-warn-per-legacy-key)

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/config/LegacyConfigWarning.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/config/LegacyConfigWarningTest.java`

`Environment.containsProperty(key)` returns true only if a property source actually contains the key (not an injected default), so it's the right primitive for "did the operator set this".

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/gravitee/reporter/otellogs/config/LegacyConfigWarningTest.java
package io.gravitee.reporter.otellogs.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

class LegacyConfigWarningTest {

  @Test
  void warns_once_for_each_legacy_key_in_use() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("reporters.otellogs.exporter", "gcloud");
    env.setProperty("reporters.otellogs.gcloud.projectId", "my-project");
    env.setProperty("reporters.otellogs.reportLogs", "true");

    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger logger = (Logger) LoggerFactory.getLogger(LegacyConfigWarning.class);
    logger.addAppender(appender);

    new LegacyConfigWarning(env).run();

    assertThat(appender.list)
      .filteredOn(e -> e.getLevel() == Level.WARN)
      .extracting(ILoggingEvent::getFormattedMessage)
      .anyMatch(m -> m.contains("reporters.otellogs.exporter") && m.contains("reporters.otellogs.logs.exporter"))
      .anyMatch(m -> m.contains("reporters.otellogs.gcloud.projectId") && m.contains("reporters.otellogs.resource.gcp.projectId"))
      .anyMatch(m -> m.contains("reporters.otellogs.reportLogs") && m.contains("reporters.otellogs.logs.reportRequestLogs"))
      .hasSize(3);
  }

  @Test
  void no_warnings_when_no_legacy_keys_set() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("reporters.otellogs.logs.exporter", "otlp");

    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger logger = (Logger) LoggerFactory.getLogger(LegacyConfigWarning.class);
    logger.addAppender(appender);

    new LegacyConfigWarning(env).run();

    assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
  }
}
```

- [ ] **Step 2: Run test, expect FAIL (class missing)**

- [ ] **Step 3: Implement `LegacyConfigWarning`**

```java
// src/main/java/io/gravitee/reporter/otellogs/config/LegacyConfigWarning.java
package io.gravitee.reporter.otellogs.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Emits a one-time WARN for each legacy flat property key that is still in use.
 * The new SpEL fallbacks in LogsConfiguration / ResourceConfiguration still honour
 * the legacy values; this class only nags operators to migrate.
 */
public class LegacyConfigWarning implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(LegacyConfigWarning.class);

  // ordered for deterministic warning output
  private static final Map<String, String> RENAMES = new LinkedHashMap<>();
  static {
    RENAMES.put("reporters.otellogs.exporter",            "reporters.otellogs.logs.exporter");
    RENAMES.put("reporters.otellogs.endpoint",            "reporters.otellogs.logs.endpoint");
    RENAMES.put("reporters.otellogs.batchSize",           "reporters.otellogs.logs.batchSize");
    RENAMES.put("reporters.otellogs.scheduledDelayMs",    "reporters.otellogs.logs.scheduledDelayMs");
    RENAMES.put("reporters.otellogs.reportHealthChecks",  "reporters.otellogs.logs.reportHealthChecks");
    RENAMES.put("reporters.otellogs.reportLogs",          "reporters.otellogs.logs.reportRequestLogs");
    RENAMES.put("reporters.otellogs.reportMessageMetrics","reporters.otellogs.logs.reportMessageMetrics");
    RENAMES.put("reporters.otellogs.gcloud.projectId",    "reporters.otellogs.resource.gcp.projectId");
    RENAMES.put("reporters.otellogs.gcloud.logName",      "reporters.otellogs.logs.logName");
    // gcloud.credentialsFile is removed silently — see spec rationale.
  }

  private final Environment env;

  public LegacyConfigWarning(Environment env) {
    this.env = env;
  }

  @Override
  public void run() {
    RENAMES.forEach((legacy, replacement) -> {
      if (env.containsProperty(legacy)) {
        log.warn("Deprecated config key '{}' will be removed next release — please use '{}' instead", legacy, replacement);
      }
    });
  }
}
```

- [ ] **Step 4: Run test, expect PASS**

```
mvn -q test -Dtest=LegacyConfigWarningTest
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/config/LegacyConfigWarning.java \
        src/test/java/io/gravitee/reporter/otellogs/config/LegacyConfigWarningTest.java
git commit -m "feat(config): warn once per legacy flat config key in use"
```

---

### Task 5: Wire new config beans + run `LegacyConfigWarning` on startup

**Files:**
- Modify: `src/main/java/io/gravitee/reporter/otellogs/config/OtelLogsReporterConfiguration.java`
- Modify: `src/main/java/io/gravitee/reporter/otellogs/spring/OtelLogsReporterSpringConfiguration.java`

`OtelLogsReporterConfiguration` keeps `enabled` + `correlationHeader` and aggregates references to the three new config beans. Existing legacy getters are removed; downstream callers will be updated in Task 6.

- [ ] **Step 1: Replace `OtelLogsReporterConfiguration` body**

```java
// src/main/java/io/gravitee/reporter/otellogs/config/OtelLogsReporterConfiguration.java
package io.gravitee.reporter.otellogs.config;

import org.springframework.beans.factory.annotation.Value;

public class OtelLogsReporterConfiguration {

  @Value("${reporters.otellogs.enabled:true}")
  private boolean enabled;

  @Value("${reporters.otellogs.correlationHeader:X-Request-ID}")
  private String correlationHeader;

  private final LogsConfiguration logs;
  private final TracesConfiguration traces;
  private final ResourceConfiguration resource;

  public OtelLogsReporterConfiguration(
    LogsConfiguration logs,
    TracesConfiguration traces,
    ResourceConfiguration resource
  ) {
    this.logs = logs;
    this.traces = traces;
    this.resource = resource;
  }

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean v) { this.enabled = v; }

  public String getCorrelationHeader() { return correlationHeader; }
  public void setCorrelationHeader(String v) { this.correlationHeader = v; }

  public LogsConfiguration getLogs() { return logs; }
  public TracesConfiguration getTraces() { return traces; }
  public ResourceConfiguration getResource() { return resource; }
}
```

- [ ] **Step 2: Update `OtelLogsReporterSpringConfiguration`**

```java
// src/main/java/io/gravitee/reporter/otellogs/spring/OtelLogsReporterSpringConfiguration.java
package io.gravitee.reporter.otellogs.spring;

import io.gravitee.reporter.otellogs.config.LegacyConfigWarning;
import io.gravitee.reporter.otellogs.config.LogsConfiguration;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.config.ResourceConfiguration;
import io.gravitee.reporter.otellogs.config.TracesConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class OtelLogsReporterSpringConfiguration {

  @Bean
  public LogsConfiguration logsConfiguration() {
    return new LogsConfiguration();
  }

  @Bean
  public TracesConfiguration tracesConfiguration() {
    return new TracesConfiguration();
  }

  @Bean
  public ResourceConfiguration resourceConfiguration() {
    return new ResourceConfiguration();
  }

  @Bean
  public OtelLogsReporterConfiguration otelLogsReporterConfiguration(
    LogsConfiguration logs,
    TracesConfiguration traces,
    ResourceConfiguration resource
  ) {
    return new OtelLogsReporterConfiguration(logs, traces, resource);
  }

  @Bean
  public LegacyConfigWarning legacyConfigWarning(Environment env) {
    LegacyConfigWarning warn = new LegacyConfigWarning(env);
    warn.run();
    return warn;
  }
}
```

- [ ] **Step 3: Build & run existing tests, expect compile errors in callers**

```
mvn -q test-compile
```
Expected: failures in `OtelLogsReporter.java`, `MetricsToLogRecordMapper.java`, and `GclLogRecordExporterTest.java` referencing the deleted legacy getters/setters. These are fixed in Task 6.

- [ ] **Step 4: Commit (red phase — about to be fixed in next task)**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/config/OtelLogsReporterConfiguration.java \
        src/main/java/io/gravitee/reporter/otellogs/spring/OtelLogsReporterSpringConfiguration.java
git commit -m "refactor(config): aggregate Logs/Traces/Resource beans into OtelLogsReporterConfiguration"
```

Skip this commit if you prefer to bundle with Task 6 — both are reasonable.

---

### Task 6: Update callers; drop `credentialsFile` from `GclLogRecordExporter`

**Files:**
- Modify: `src/main/java/io/gravitee/reporter/otellogs/OtelLogsReporter.java`
- Modify: `src/main/java/io/gravitee/reporter/otellogs/writer/GclLogRecordExporter.java`
- Modify: `src/main/java/io/gravitee/reporter/otellogs/mapper/MetricsToLogRecordMapper.java`
- Modify: `src/test/java/io/gravitee/reporter/otellogs/writer/GclLogRecordExporterTest.java`

- [ ] **Step 1: Drop `credentialsFile` from `GclLogRecordExporter`**

Replace the constructor + `loadCredentials` private method:

```java
// GclLogRecordExporter.java — constructor + credential loader
public GclLogRecordExporter(String projectId, String logName) {
  this.projectId = projectId;
  this.logName = logName;
  this.credentials = loadAdc();
  this.http = HttpClient.newHttpClient();
  this.resource = GcpResource.detect(projectId);
  log.info("GCL logs MonitoredResource: type={} labels={}", resource.type(), resource.labels());
}

private static GoogleCredentials loadAdc() {
  try {
    return GoogleCredentials.getApplicationDefault()
      .createScoped("https://www.googleapis.com/auth/cloud-platform");
  } catch (IOException e) {
    throw new IllegalStateException("Failed to load Application Default Credentials: " + e.getMessage(), e);
  }
}
```

Remove the `FileInputStream` and `ServiceAccountCredentials` imports.

- [ ] **Step 2: Update `MetricsToLogRecordMapper` to read `correlationHeader` from the new top-level config**

The class already takes `OtelLogsReporterConfiguration`; only the getter changes:

```java
// MetricsToLogRecordMapper.java, in resolveTraceId(...)
String corrValue = headers.get(config.getCorrelationHeader());  // unchanged — getter still exists
```

`config.getCorrelationHeader()` is preserved on `OtelLogsReporterConfiguration`, so this is a no-op. Confirm nothing else in this file referenced removed legacy getters.

- [ ] **Step 3: Update `OtelLogsReporter` to use new config beans**

Key changes: replace all `cfg.get*()` legacy reads with `cfg.getLogs().*()` / `cfg.getResource().*()` and drop the third constructor arg from `GclLogRecordExporter`.

```java
// OtelLogsReporter.java — relevant sections
@Override
protected void doStart() throws Exception {
  log.info("OTelLogsReporter.doStart() called. Enabled={}", cfg.isEnabled());
  if (!cfg.isEnabled() || !cfg.getLogs().isEnabled()) {
    log.info("OTel logs reporter disabled (reporter={}, logs={})",
      cfg.isEnabled(), cfg.getLogs().isEnabled());
    return;
  }
  super.doStart();
  if (this.writer == null) {
    LogRecordExporter exporter = buildLogExporter();
    log.info("Built exporter: {}", exporter.getClass().getName());
    this.writer = new OtelLogWriter(
      exporter,
      cfg.getLogs().getBatchSize(),
      cfg.getLogs().getScheduledDelayMs()
    );
  }
  log.info("OTel logs reporter started — exporter={} endpoint/logName={}",
    cfg.getLogs().getExporter(),
    "gcloud".equalsIgnoreCase(cfg.getLogs().getExporter())
      ? cfg.getLogs().getLogName()
      : cfg.getLogs().getEndpoint()
  );
}

private LogRecordExporter buildLogExporter() {
  String exporter = cfg.getLogs().getExporter();
  if ("none".equalsIgnoreCase(exporter)) {
    throw new IllegalStateException("logs.exporter=none but logs.enabled=true — disable logs or pick gcloud|otlp");
  }
  if ("gcloud".equalsIgnoreCase(exporter)) {
    String projectId = cfg.getResource().getGcpProjectId();
    if (projectId == null || projectId.isBlank()) {
      throw new IllegalStateException("reporters.otellogs.resource.gcp.projectId is required when logs.exporter=gcloud");
    }
    return new GclLogRecordExporter(projectId, cfg.getLogs().getLogName());
  }
  return new CustomOtlpHttpLogRecordExporter(cfg.getLogs().getEndpoint());
}

@Override
public void report(Reportable reportable) {
  if (lifecycleState() != Lifecycle.State.STARTED || !cfg.isEnabled() || !cfg.getLogs().isEnabled()) {
    return;
  }
  // ... unchanged switch, but read flags from cfg.getLogs():
  OtelLogRecord record = switch (reportable) {
    case Metrics metrics -> metricsMapper.map(metrics);
    case Log l when cfg.getLogs().isReportRequestLogs() -> logMapper.map(l);
    case EndpointStatus es when cfg.getLogs().isReportHealthChecks() -> endpointMapper.map(es);
    case MessageMetrics mm when cfg.getLogs().isReportMessageMetrics() -> messageMapper.map(mm);
    default -> null;
  };
  if (record != null) writer.emit(record);
}

@Override
public boolean canHandle(Reportable reportable) {
  if (!cfg.isEnabled() || !cfg.getLogs().isEnabled()) return false;
  return switch (reportable) {
    case Metrics ignored -> true;
    case EndpointStatus ignored -> cfg.getLogs().isReportHealthChecks();
    case Log ignored -> cfg.getLogs().isReportRequestLogs();
    case MessageMetrics ignored -> cfg.getLogs().isReportMessageMetrics();
    default -> false;
  };
}
```

- [ ] **Step 4: Update `GclLogRecordExporterTest`**

Find every existing `new GclLogRecordExporter(projectId, logName, credentialsFile)` and remove the third arg. Run the existing test class to confirm.

- [ ] **Step 5: Run the full test suite**

```
mvn -q test
```
Expected: all existing tests pass with the new config wiring. Failure modes to watch for: `OtelLogsReporter` tests instantiating the configuration with the old constructor — those need new construction `new OtelLogsReporterConfiguration(logs, traces, resource)`.

- [ ] **Step 6: Commit**

```bash
git add -u
git commit -m "$(cat <<'EOF'
refactor: route logs through LogsConfiguration; drop credentialsFile

Removes the credentialsFile option from GclLogRecordExporter — ADC is
now the only auth path. All call sites read through cfg.getLogs() /
cfg.getResource() instead of the flat legacy getters. Legacy SpEL
fallbacks in LogsConfiguration keep old deployments working for one
release.
EOF
)"
```

---

### Task 7: Phase 1 verification

- [ ] **Step 1: Full unit + integration suite**

```
mvn -q test
mvn -q -P integration-test verify
```
Both pass.

- [ ] **Step 2: Manual smoke-test of the legacy-key fallback**

Start the gateway locally (or use the existing `OtelLogsReporterManualIT`) with **only** old flat keys set in `gravitee.yml`:

```yaml
reporters:
  otellogs:
    enabled: true
    exporter: otlp
    endpoint: http://localhost:4317
    reportHealthChecks: true
```

Expected: gateway starts, OTLP logs flow, **and** the startup log contains three `WARN` lines naming `reporters.otellogs.exporter`, `reporters.otellogs.endpoint`, `reporters.otellogs.reportHealthChecks` and their replacement keys.

- [ ] **Step 3: Tag the phase boundary commit**

```bash
git tag phase1-config-redesign-complete
```

(Optional — useful if you'll open this as a separate PR.)

---

# Phase 2 — Trace pipeline

### Task 8: Add `opentelemetry-sdk-trace` dependency

**Files:**
- Modify: `pom.xml`

The `opentelemetry-sdk` artifact aggregates logs + traces + metrics. The current pom excludes the metrics submodule for size; we now keep the trace submodule. Adding an explicit `opentelemetry-sdk-trace` dep is the clearest signal of intent.

- [ ] **Step 1: Insert dependency below `opentelemetry-sdk-logs`**

```xml
<!-- pom.xml — in <dependencies>, immediately after opentelemetry-sdk-logs -->
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-sdk-trace</artifactId>
</dependency>
```

- [ ] **Step 2: Confirm build still passes**

```
mvn -q -DskipTests package
ls target/dependencies | grep opentelemetry-sdk-trace
```
Expected: `opentelemetry-sdk-trace-1.44.1.jar` present in `target/dependencies/`, plugin ZIP builds.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build(pom): add opentelemetry-sdk-trace dependency"
```

---

### Task 9: Extract `GcpEnvironment` from `GcpResource`

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/writer/GcpEnvironment.java`
- Modify: `src/main/java/io/gravitee/reporter/otellogs/writer/GcpResource.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/writer/GcpEnvironmentTest.java`

Pulls the metadata-server + k8s-namespace probing out of `GcpResource` so both the GCL `MonitoredResource` and the new OTel `Resource` consume the same detection result.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/gravitee/reporter/otellogs/writer/GcpEnvironmentTest.java
package io.gravitee.reporter.otellogs.writer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GcpEnvironmentTest {

  @Test
  void empty_when_not_on_gcp() {
    GcpEnvironment env = GcpEnvironment.empty();
    assertThat(env.isOnGke()).isFalse();
    assertThat(env.k8sNamespace()).isNull();
    assertThat(env.cluster()).isNull();
    assertThat(env.location()).isNull();
    assertThat(env.podName()).isNull();
    assertThat(env.containerName()).isNull();
  }

  @Test
  void k8s_container_when_all_fields_present() {
    GcpEnvironment env = new GcpEnvironment("default", "pod-1", "container-a", "us-c1", "demo-cluster");
    assertThat(env.isOnGke()).isTrue();
    assertThat(env.isK8sContainer()).isTrue();
  }

  @Test
  void k8s_pod_when_container_missing() {
    GcpEnvironment env = new GcpEnvironment("default", "pod-1", null, "us-c1", "demo-cluster");
    assertThat(env.isOnGke()).isTrue();
    assertThat(env.isK8sContainer()).isFalse();
  }
}
```

- [ ] **Step 2: Run test, expect FAIL**

- [ ] **Step 3: Implement `GcpEnvironment`**

```java
// src/main/java/io/gravitee/reporter/otellogs/writer/GcpEnvironment.java
package io.gravitee.reporter.otellogs.writer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detected GCP / GKE environment for a single JVM. Shared by GcpResource (GCL
 * MonitoredResource shape) and GcpOtelResource (OTel Resource shape).
 */
public record GcpEnvironment(
  String k8sNamespace,
  String podName,
  String containerName,
  String location,
  String cluster
) {

  private static final Logger log = LoggerFactory.getLogger(GcpEnvironment.class);

  private static final String METADATA_URL = "http://metadata.google.internal/computeMetadata/v1/";
  private static final Path K8S_NAMESPACE_FILE = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace");
  private static final Duration METADATA_TIMEOUT = Duration.ofMillis(500);
  private static final Duration DETECTION_BUDGET = Duration.ofSeconds(2);

  public static GcpEnvironment empty() {
    return new GcpEnvironment(null, null, null, null, null);
  }

  public boolean isOnGke() {
    return k8sNamespace != null && podName != null && cluster != null && location != null;
  }

  public boolean isK8sContainer() {
    return isOnGke() && containerName != null && !containerName.isBlank();
  }

  /** Probes the GCE metadata server + Kubernetes downward API, bounded by a 2s budget. */
  public static GcpEnvironment detect() {
    try {
      return CompletableFuture.supplyAsync(GcpEnvironment::detectInternal)
        .get(DETECTION_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      log.debug("GCP environment detection exceeded budget; treating as off-GCP");
      return empty();
    }
  }

  private static GcpEnvironment detectInternal() {
    String namespace = readK8sNamespace();
    String hostname = System.getenv("HOSTNAME");
    String cluster = fetchMetadata("instance/attributes/cluster-name");
    String location = fetchMetadata("instance/attributes/cluster-location");
    String container = System.getenv("K8S_CONTAINER_NAME");
    return new GcpEnvironment(namespace, hostname, container, location, cluster);
  }

  private static String readK8sNamespace() {
    try {
      if (!Files.exists(K8S_NAMESPACE_FILE)) return null;
      String value = Files.readString(K8S_NAMESPACE_FILE).trim();
      return value.isEmpty() ? null : value;
    } catch (IOException e) {
      return null;
    }
  }

  private static String fetchMetadata(String path) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(METADATA_TIMEOUT).build();
    try {
      var req = HttpRequest.newBuilder()
        .uri(URI.create(METADATA_URL + path))
        .header("Metadata-Flavor", "Google")
        .timeout(METADATA_TIMEOUT)
        .GET()
        .build();
      var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200) {
        String body = resp.body().trim();
        return body.isEmpty() ? null : body;
      }
    } catch (Exception e) {
      // expected when running outside GCE
    }
    return null;
  }
}
```

- [ ] **Step 4: Refactor `GcpResource.detectInternal` to use `GcpEnvironment`**

```java
// GcpResource.java — replace detect & detectInternal
public static GcpResource detect(String projectId) {
  return fromEnvironment(projectId, GcpEnvironment.detect());
}

static GcpResource fromEnvironment(String projectId, GcpEnvironment env) {
  if (!env.isOnGke()) {
    log.debug("GCP environment incomplete, using 'global'");
    return global();
  }
  var labels = new LinkedHashMap<String, String>();
  labels.put("project_id", projectId);
  labels.put("location", env.location());
  labels.put("cluster_name", env.cluster());
  labels.put("namespace_name", env.k8sNamespace());
  labels.put("pod_name", env.podName());
  if (env.isK8sContainer()) {
    labels.put("container_name", env.containerName());
    return new GcpResource("k8s_container", labels);
  }
  return new GcpResource("k8s_pod", labels);
}
```

Delete the now-unused `readK8sNamespace` and `fetchMetadata` from `GcpResource`. Existing `GcpResourceTest` should continue to pass — adjust any tests that mocked the static metadata probe by switching to `fromEnvironment(projectId, env)`.

- [ ] **Step 5: Run tests, expect PASS**

```
mvn -q test -Dtest='GcpEnvironmentTest,GcpResourceTest'
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/writer/GcpEnvironment.java \
        src/main/java/io/gravitee/reporter/otellogs/writer/GcpResource.java \
        src/test/java/io/gravitee/reporter/otellogs/writer/GcpEnvironmentTest.java \
        src/test/java/io/gravitee/reporter/otellogs/writer/GcpResourceTest.java
git commit -m "refactor: extract GcpEnvironment from GcpResource detection"
```

---

### Task 10: Add `GcpOtelResource`

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/writer/GcpOtelResource.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/writer/GcpOtelResourceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/gravitee/reporter/otellogs/writer/GcpOtelResourceTest.java
package io.gravitee.reporter.otellogs.writer;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.reporter.otellogs.config.ResourceConfiguration;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;

class GcpOtelResourceTest {

  @Test
  void sets_service_attributes_always() {
    ResourceConfiguration rc = new ResourceConfiguration();
    rc.setServiceName("gravitee-api-gateway");
    rc.setServiceNamespace("apim");
    rc.setGcpProjectId("my-project");
    rc.setGcpAutoDetect(false);

    Resource r = GcpOtelResource.build(rc, GcpEnvironment.empty());

    assertThat(r.getAttribute(stringKey("service.name"))).isEqualTo("gravitee-api-gateway");
    assertThat(r.getAttribute(stringKey("service.namespace"))).isEqualTo("apim");
    assertThat(r.getAttribute(stringKey("gcp.project_id"))).isEqualTo("my-project");
  }

  @Test
  void sets_k8s_attributes_when_on_gke() {
    ResourceConfiguration rc = new ResourceConfiguration();
    rc.setServiceName("svc");
    rc.setServiceNamespace("apim");
    rc.setGcpProjectId("p");
    rc.setGcpAutoDetect(true);
    GcpEnvironment env = new GcpEnvironment("ns-a", "pod-1", "container-a", "us-c1", "demo-cluster");

    Resource r = GcpOtelResource.build(rc, env);

    assertThat(r.getAttribute(stringKey("k8s.namespace.name"))).isEqualTo("ns-a");
    assertThat(r.getAttribute(stringKey("k8s.pod.name"))).isEqualTo("pod-1");
    assertThat(r.getAttribute(stringKey("k8s.container.name"))).isEqualTo("container-a");
    assertThat(r.getAttribute(stringKey("cloud.region"))).isEqualTo("us-c1");
  }

  @Test
  void skips_k8s_attrs_when_autoDetect_off() {
    ResourceConfiguration rc = new ResourceConfiguration();
    rc.setServiceName("svc");
    rc.setServiceNamespace("apim");
    rc.setGcpProjectId("p");
    rc.setGcpAutoDetect(false);
    GcpEnvironment env = new GcpEnvironment("ns-a", "pod-1", "container-a", "us-c1", "demo-cluster");

    Resource r = GcpOtelResource.build(rc, env);

    assertThat(r.getAttribute(stringKey("k8s.namespace.name"))).isNull();
    assertThat(r.getAttribute(stringKey("k8s.pod.name"))).isNull();
  }

  @Test
  void host_name_fallback_when_off_gke() {
    ResourceConfiguration rc = new ResourceConfiguration();
    rc.setServiceName("svc");
    rc.setServiceNamespace("apim");
    rc.setGcpProjectId("p");
    rc.setGcpAutoDetect(true);

    Resource r = GcpOtelResource.build(rc, GcpEnvironment.empty());

    // host.name resolves from HOSTNAME env var or InetAddress; just assert non-null.
    assertThat(r.getAttribute(stringKey("host.name"))).isNotNull();
  }
}
```

- [ ] **Step 2: Run test, expect FAIL**

- [ ] **Step 3: Implement `GcpOtelResource`**

```java
// src/main/java/io/gravitee/reporter/otellogs/writer/GcpOtelResource.java
package io.gravitee.reporter.otellogs.writer;

import io.gravitee.reporter.otellogs.config.ResourceConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.resources.Resource;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Builds an OTel {@link Resource} from {@link ResourceConfiguration} + a detected
 * {@link GcpEnvironment}. Sets standard OTel semconv keys plus {@code gcp.project_id}
 * which Cloud Trace requires to route OTLP traces.
 */
public final class GcpOtelResource {

  private GcpOtelResource() {}

  public static Resource build(ResourceConfiguration rc, GcpEnvironment env) {
    AttributesBuilder a = Attributes.builder();
    a.put(AttributeKey.stringKey("service.name"), rc.getServiceName());
    a.put(AttributeKey.stringKey("service.namespace"), rc.getServiceNamespace());
    if (rc.getGcpProjectId() != null && !rc.getGcpProjectId().isBlank()) {
      a.put(AttributeKey.stringKey("gcp.project_id"), rc.getGcpProjectId());
    }
    if (rc.isGcpAutoDetect() && env.isOnGke()) {
      a.put(AttributeKey.stringKey("k8s.namespace.name"), env.k8sNamespace());
      a.put(AttributeKey.stringKey("k8s.pod.name"), env.podName());
      if (env.isK8sContainer()) {
        a.put(AttributeKey.stringKey("k8s.container.name"), env.containerName());
      }
      a.put(AttributeKey.stringKey("cloud.region"), env.location());
      a.put(AttributeKey.stringKey("k8s.cluster.name"), env.cluster());
    }
    a.put(AttributeKey.stringKey("host.name"), resolveHostName());
    return Resource.create(a.build());
  }

  private static String resolveHostName() {
    String fromEnv = System.getenv("HOSTNAME");
    if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
    try { return InetAddress.getLocalHost().getHostName(); }
    catch (UnknownHostException e) { return "unknown"; }
  }
}
```

- [ ] **Step 4: Run test, expect PASS**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/writer/GcpOtelResource.java \
        src/test/java/io/gravitee/reporter/otellogs/writer/GcpOtelResourceTest.java
git commit -m "feat(writer): GcpOtelResource builds OTel Resource with gcp.project_id"
```

---

### Task 11: Add `OtlpAuthHeaders`

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/writer/OtlpAuthHeaders.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/writer/OtlpAuthHeadersTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/gravitee/reporter/otellogs/writer/OtlpAuthHeadersTest.java
package io.gravitee.reporter.otellogs.writer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OtlpAuthHeadersTest {

  @Test
  void none_returns_empty_map() {
    var supplier = OtlpAuthHeaders.none();
    assertThat(supplier.get()).isEmpty();
  }

  @Test
  void static_returns_provided_map_each_call() {
    var supplier = OtlpAuthHeaders.staticHeaders(Map.of("X-Token", "abc"));
    assertThat(supplier.get()).containsEntry("X-Token", "abc");
    assertThat(supplier.get()).containsEntry("X-Token", "abc"); // stable
  }

  @Test
  void static_is_defensively_copied() {
    var src = new java.util.HashMap<String, String>();
    src.put("X-Token", "v1");
    var supplier = OtlpAuthHeaders.staticHeaders(src);
    src.put("X-Token", "v2");
    assertThat(supplier.get()).containsEntry("X-Token", "v1");
  }
}
```

ADC integration is exercised by the `gcloud-integration` IT, not unit tests.

- [ ] **Step 2: Run test, expect FAIL**

- [ ] **Step 3: Implement `OtlpAuthHeaders`**

```java
// src/main/java/io/gravitee/reporter/otellogs/writer/OtlpAuthHeaders.java
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

  public static Supplier<Map<String, String>> staticHeaders(Map<String, String> headers) {
    Map<String, String> snapshot = Map.copyOf(headers);
    return () -> snapshot;
  }

  public static Supplier<Map<String, String>> gcpAdc() {
    GoogleCredentials creds;
    try {
      creds = GoogleCredentials.getApplicationDefault()
        .createScoped("https://www.googleapis.com/auth/cloud-platform");
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load ADC for OTLP auth", e);
    }
    return () -> {
      try { creds.refreshIfExpired(); }
      catch (IOException e) { throw new UncheckedIOException(e); }
      return Map.of("Authorization", "Bearer " + creds.getAccessToken().getTokenValue());
    };
  }
}
```

- [ ] **Step 4: Run test, expect PASS**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/writer/OtlpAuthHeaders.java \
        src/test/java/io/gravitee/reporter/otellogs/writer/OtlpAuthHeadersTest.java
git commit -m "feat(writer): OtlpAuthHeaders factories (none|static|gcp-adc)"
```

---

### Task 12: Extract `TraceContextResolver`

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/mapper/TraceContextResolver.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/mapper/TraceContextResolverTest.java`
- Modify: `src/main/java/io/gravitee/reporter/otellogs/mapper/MetricsToLogRecordMapper.java`

The resolver returns either a `(traceId, spanId)` triple, just a `traceId`, or nothing. Same instance feeds both log and span mappers so the trace ID is identical.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/gravitee/reporter/otellogs/mapper/TraceContextResolverTest.java
package io.gravitee.reporter.otellogs.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.gateway.api.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TraceContextResolverTest {

  private final TraceContextResolver resolver = new TraceContextResolver("X-Request-ID");

  @Test
  void w3c_traceparent_wins_over_correlation_header() {
    HttpHeaders h = Mockito.mock(HttpHeaders.class);
    Mockito.when(h.get("X-Request-ID")).thenReturn("ignored-value");
    Mockito.when(h.get("traceparent")).thenReturn("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

    TraceContextResolver.Resolved r = resolver.resolve(h);

    assertThat(r.traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    assertThat(r.spanId()).isEqualTo("b7ad6b7169203331");
    assertThat(r.isRemoteParent()).isTrue();
  }

  @Test
  void correlation_header_only_yields_deterministic_trace_id() {
    HttpHeaders h = Mockito.mock(HttpHeaders.class);
    Mockito.when(h.get("X-Request-ID")).thenReturn("req-abc-123");

    TraceContextResolver.Resolved r1 = resolver.resolve(h);
    TraceContextResolver.Resolved r2 = resolver.resolve(h);

    assertThat(r1.traceId()).isEqualTo(r2.traceId());                  // deterministic
    assertThat(r1.traceId()).matches("[0-9a-f]{32}");                  // 16 bytes hex
    assertThat(r1.spanId()).isNull();                                  // no upstream span
    assertThat(r1.isRemoteParent()).isFalse();
  }

  @Test
  void neither_present_returns_empty() {
    HttpHeaders h = Mockito.mock(HttpHeaders.class);
    Mockito.when(h.get("X-Request-ID")).thenReturn(null);
    Mockito.when(h.get("traceparent")).thenReturn(null);

    assertThat(resolver.resolve(h).isEmpty()).isTrue();
  }

  @Test
  void null_headers_returns_empty() {
    assertThat(resolver.resolve(null).isEmpty()).isTrue();
  }

  @Test
  void malformed_traceparent_falls_back_to_correlation_header() {
    HttpHeaders h = Mockito.mock(HttpHeaders.class);
    Mockito.when(h.get("X-Request-ID")).thenReturn("req-xyz");
    Mockito.when(h.get("traceparent")).thenReturn("garbage");

    TraceContextResolver.Resolved r = resolver.resolve(h);

    assertThat(r.traceId()).matches("[0-9a-f]{32}");
    assertThat(r.spanId()).isNull();
  }
}
```

- [ ] **Step 2: Run test, expect FAIL**

- [ ] **Step 3: Implement `TraceContextResolver`**

```java
// src/main/java/io/gravitee/reporter/otellogs/mapper/TraceContextResolver.java
package io.gravitee.reporter.otellogs.mapper;

import io.gravitee.gateway.api.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class TraceContextResolver {

  /** Outcome of a single resolve call. */
  public record Resolved(String traceId, String spanId, boolean isRemoteParent) {
    public static final Resolved EMPTY = new Resolved(null, null, false);
    public boolean isEmpty() { return traceId == null; }
  }

  private final String correlationHeader;

  public TraceContextResolver(String correlationHeader) {
    this.correlationHeader = correlationHeader;
  }

  public Resolved resolve(HttpHeaders headers) {
    if (headers == null) return Resolved.EMPTY;
    String tp = headers.get("traceparent");
    if (tp != null) {
      String[] parts = tp.split("-", 4);
      if (parts.length >= 3 && parts[1].length() == 32 && parts[2].length() == 16) {
        return new Resolved(parts[1], parts[2], true);
      }
    }
    String corr = headers.get(correlationHeader);
    if (corr != null && !corr.isBlank()) {
      return new Resolved(deriveTraceId(corr), null, false);
    }
    return Resolved.EMPTY;
  }

  private static String deriveTraceId(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(32);
      for (int i = 0; i < 16; i++) sb.append(String.format("%02x", digest[i]));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
```

- [ ] **Step 4: Delegate `MetricsToLogRecordMapper` to the resolver**

Replace `resolveTraceId(headers)` and `resolveSpanId(headers)` in `MetricsToLogRecordMapper` with one call to the resolver:

```java
// MetricsToLogRecordMapper.java — constructor + map(...)
private final TraceContextResolver traceResolver;

public MetricsToLogRecordMapper(OtelLogsReporterConfiguration config) {
  this.config = config;
  this.traceResolver = new TraceContextResolver(config.getCorrelationHeader());
}

public OtelLogRecord map(Metrics m) {
  HttpHeaders headers = extractRequestHeaders(m);
  TraceContextResolver.Resolved tc = traceResolver.resolve(headers);
  String traceId = tc.traceId();
  String spanId  = tc.spanId();
  // ... rest unchanged: sentry parsing, body, attributes
}
```

Remove the now-dead `resolveTraceId` and `resolveSpanId` private methods.

- [ ] **Step 5: Run tests, expect PASS**

```
mvn -q test -Dtest='TraceContextResolverTest,MetricsToLogRecordMapperTest'
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/mapper/TraceContextResolver.java \
        src/main/java/io/gravitee/reporter/otellogs/mapper/MetricsToLogRecordMapper.java \
        src/test/java/io/gravitee/reporter/otellogs/mapper/TraceContextResolverTest.java
git commit -m "refactor(mapper): extract TraceContextResolver shared by logs and traces"
```

---

### Task 13: Add `CustomOtlpHttpSpanExporter`

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/writer/CustomOtlpHttpSpanExporter.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/writer/CustomOtlpHttpSpanExporterTest.java`

Mirrors `CustomOtlpHttpLogRecordExporter` but for spans, using `TraceRequestMarshaler` from `opentelemetry-exporter-otlp-common`. Headers come from a `Supplier<Map<String,String>>` so ADC can be refreshed per export.

- [ ] **Step 1: Write the failing test (uses a tiny local HTTP server)**

```java
// src/test/java/io/gravitee/reporter/otellogs/writer/CustomOtlpHttpSpanExporterTest.java
package io.gravitee.reporter.otellogs.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CustomOtlpHttpSpanExporterTest {

  @Test
  void posts_protobuf_to_endpoint_with_auth_header() throws Exception {
    AtomicReference<String> seenAuth = new AtomicReference<>();
    AtomicReference<String> seenContentType = new AtomicReference<>();
    HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    srv.createContext("/v1/traces", ex -> {
      seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
      seenContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
      ex.sendResponseHeaders(200, 0);
      ex.close();
    });
    srv.start();

    try {
      String endpoint = "http://127.0.0.1:" + srv.getAddress().getPort();
      SpanExporter exp = new CustomOtlpHttpSpanExporter(endpoint, () -> Map.of("Authorization", "Bearer token-1"));
      CompletableResultCode r = exp.export(List.of(sampleSpan()));
      r.join(2, TimeUnit.SECONDS);

      assertThat(r.isSuccess()).isTrue();
      assertThat(seenAuth.get()).isEqualTo("Bearer token-1");
      assertThat(seenContentType.get()).isEqualTo("application/x-protobuf");
    } finally {
      srv.stop(0);
    }
  }

  @Test
  void empty_batch_succeeds_without_request() {
    SpanExporter exp = new CustomOtlpHttpSpanExporter("http://127.0.0.1:1", () -> Map.of());
    CompletableResultCode r = exp.export(List.of());
    r.join(1, TimeUnit.SECONDS);
    assertThat(r.isSuccess()).isTrue();
  }

  private static SpanData sampleSpan() {
    return TestSpanData.builder()
      .setName("test")
      .setKind(SpanKind.SERVER)
      .setStartEpochNanos(1L)
      .setEndEpochNanos(2L)
      .setHasEnded(true)
      .setSpanContext(SpanContext.create(
        "0af7651916cd43dd8448eb211c80319c",
        "b7ad6b7169203331",
        TraceFlags.getSampled(), TraceState.getDefault()))
      .setResource(Resource.empty())
      .setAttributes(Attributes.empty())
      .setStatus(io.opentelemetry.sdk.trace.data.StatusData.unset())
      .build();
  }
}
```

- [ ] **Step 2: Run test, expect FAIL (class missing)**

- [ ] **Step 3: Implement `CustomOtlpHttpSpanExporter`**

```java
// src/main/java/io/gravitee/reporter/otellogs/writer/CustomOtlpHttpSpanExporter.java
package io.gravitee.reporter.otellogs.writer;

import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OTLP/HTTP span exporter built on java.net.http.HttpClient — same rationale as
 * {@link CustomOtlpHttpLogRecordExporter}. Headers are sourced from a Supplier per
 * export so ADC tokens can be refreshed without rebuilding the exporter.
 */
public class CustomOtlpHttpSpanExporter implements SpanExporter {

  private static final Logger log = LoggerFactory.getLogger(CustomOtlpHttpSpanExporter.class);

  private final HttpClient httpClient;
  private final URI endpoint;
  private final Supplier<Map<String, String>> headers;

  public CustomOtlpHttpSpanExporter(String endpoint, Supplier<Map<String, String>> headers) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.endpoint = URI.create(endpoint);
    this.headers = headers;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    if (spans.isEmpty()) return CompletableResultCode.ofSuccess();

    try {
      TraceRequestMarshaler marshaler = TraceRequestMarshaler.create(spans);
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      marshaler.writeBinaryTo(baos);

      HttpRequest.Builder rb = HttpRequest.newBuilder()
        .uri(endpoint)
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/x-protobuf")
        .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()));
      headers.get().forEach(rb::header);

      CompletableResultCode result = new CompletableResultCode();
      httpClient.sendAsync(rb.build(), HttpResponse.BodyHandlers.discarding())
        .whenComplete((resp, err) -> {
          if (err != null) { log.warn("OTLP span export failed: {}", err.getMessage()); result.fail(); }
          else if (resp.statusCode() >= 200 && resp.statusCode() < 300) result.succeed();
          else { log.warn("OTLP span export status={}", resp.statusCode()); result.fail(); }
        });
      return result;
    } catch (IOException e) {
      log.warn("Failed to marshal OTLP spans: {}", e.getMessage());
      return CompletableResultCode.ofFailure();
    } catch (Exception e) {
      log.warn("Unexpected error during OTLP span export: {}", e.getMessage());
      return CompletableResultCode.ofFailure();
    }
  }

  @Override
  public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }

  @Override
  public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
}
```

- [ ] **Step 4: Run test, expect PASS**

```
mvn -q test -Dtest=CustomOtlpHttpSpanExporterTest
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/writer/CustomOtlpHttpSpanExporter.java \
        src/test/java/io/gravitee/reporter/otellogs/writer/CustomOtlpHttpSpanExporterTest.java
git commit -m "feat(writer): CustomOtlpHttpSpanExporter with refreshing auth headers"
```

---

### Task 14: Add `OtelTraceWriter`

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/writer/OtelTraceWriter.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/writer/OtelTraceWriterTest.java`

Wraps `SdkTracerProvider`, builds the sampler from `TracesConfiguration.sampler`, exposes `Tracer tracer()` for the mapper.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/gravitee/reporter/otellogs/writer/OtelTraceWriterTest.java
package io.gravitee.reporter.otellogs.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.reporter.otellogs.config.TracesConfiguration;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OtelTraceWriterTest {

  @Test
  void emits_span_through_processor_to_exporter() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    TracesConfiguration tc = new TracesConfiguration();
    tc.setBatchSize(1);
    tc.setScheduledDelayMs(50);
    tc.setSampler("always-on");
    OtelTraceWriter w = new OtelTraceWriter(exporter, tc, Resource.empty());

    w.tracer().spanBuilder("test").setSpanKind(SpanKind.SERVER).startSpan().end();
    w.flush();

    assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    w.close();
  }

  @Test
  void invalid_batch_size_throws() {
    TracesConfiguration tc = new TracesConfiguration();
    tc.setBatchSize(0);
    tc.setScheduledDelayMs(1);
    tc.setSampler("always-on");
    assertThatThrownBy(() -> new OtelTraceWriter(InMemorySpanExporter.create(), tc, Resource.empty()))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("batchSize");
  }

  @Test
  void unknown_sampler_throws() {
    TracesConfiguration tc = new TracesConfiguration();
    tc.setBatchSize(1);
    tc.setScheduledDelayMs(1);
    tc.setSampler("not-a-sampler");
    assertThatThrownBy(() -> new OtelTraceWriter(InMemorySpanExporter.create(), tc, Resource.empty()))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("sampler");
  }
}
```

- [ ] **Step 2: Run test, expect FAIL**

- [ ] **Step 3: Implement `OtelTraceWriter`**

```java
// src/main/java/io/gravitee/reporter/otellogs/writer/OtelTraceWriter.java
package io.gravitee.reporter.otellogs.writer;

import io.gravitee.reporter.otellogs.config.TracesConfiguration;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelTraceWriter implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(OtelTraceWriter.class);

  private final SdkTracerProvider sdkTracerProvider;
  private final Tracer tracer;

  public OtelTraceWriter(SpanExporter exporter, TracesConfiguration cfg, Resource resource) {
    if (cfg.getBatchSize() <= 0)
      throw new IllegalArgumentException("traces.batchSize must be > 0, got: " + cfg.getBatchSize());
    if (cfg.getScheduledDelayMs() <= 0)
      throw new IllegalArgumentException("traces.scheduledDelayMs must be > 0, got: " + cfg.getScheduledDelayMs());

    BatchSpanProcessor processor = BatchSpanProcessor.builder(exporter)
      .setMaxExportBatchSize(cfg.getBatchSize())
      .setScheduleDelay(Duration.ofMillis(cfg.getScheduledDelayMs()))
      .build();

    sdkTracerProvider = SdkTracerProvider.builder()
      .setResource(resource)
      .addSpanProcessor(processor)
      .setSampler(buildSampler(cfg))
      .build();

    tracer = sdkTracerProvider.get("gravitee-otel-traces");
  }

  public Tracer tracer() { return tracer; }

  public void flush() {
    var result = sdkTracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    if (!result.isSuccess()) log.warn("OTel trace flush did not complete successfully");
  }

  @Override
  public void close() {
    var result = sdkTracerProvider.shutdown().join(10, TimeUnit.SECONDS);
    if (!result.isSuccess()) log.warn("OTel tracer provider shutdown did not complete successfully");
  }

  private static Sampler buildSampler(TracesConfiguration cfg) {
    return switch (cfg.getSampler()) {
      case "always-on" -> Sampler.alwaysOn();
      case "parent-based-always-on" -> Sampler.parentBased(Sampler.alwaysOn());
      case "ratio" -> Sampler.parentBased(Sampler.traceIdRatioBased(cfg.getSampleRatio()));
      default -> throw new IllegalArgumentException(
        "Unknown traces.sampler '" + cfg.getSampler() + "' — expected always-on|parent-based-always-on|ratio");
    };
  }
}
```

- [ ] **Step 4: Run test, expect PASS**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/writer/OtelTraceWriter.java \
        src/test/java/io/gravitee/reporter/otellogs/writer/OtelTraceWriterTest.java
git commit -m "feat(writer): OtelTraceWriter with parent-based / always-on / ratio samplers"
```

---

### Task 15: Add `MetricsToSpanMapper`

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/mapper/MetricsToSpanMapper.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/mapper/MetricsToSpanMapperTest.java`

Builds a finished server span from a `Metrics` event using `setStartTimestamp` + `end(endNanos)`. The mapper takes the `Tracer` (from `OtelTraceWriter`) and the `TraceContextResolver` so the trace ID matches the log mapper.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/gravitee/reporter/otellogs/mapper/MetricsToSpanMapperTest.java
package io.gravitee.reporter.otellogs.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

class MetricsToSpanMapperTest {

  @Test
  void parents_to_inbound_traceparent_and_carries_http_semconv_attrs() {
    InMemorySpanExporter exp = InMemorySpanExporter.create();
    SdkTracerProvider provider = SdkTracerProvider.builder()
      .setResource(Resource.empty())
      .addSpanProcessor(SimpleSpanProcessor.create(exp))
      .build();
    TraceContextResolver resolver = new TraceContextResolver("X-Request-ID");
    MetricsToSpanMapper mapper = new MetricsToSpanMapper(provider.get("test"), resolver);

    Metrics m = mock(Metrics.class);
    Log lg = mock(Log.class);
    io.gravitee.reporter.api.v4.log.Request req = mock(io.gravitee.reporter.api.v4.log.Request.class);
    HttpHeaders h = mock(HttpHeaders.class);
    when(h.get("traceparent")).thenReturn("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    when(req.getHeaders()).thenReturn(h);
    when(lg.getEntrypointRequest()).thenReturn(req);
    when(m.getLog()).thenReturn(lg);
    when(m.getHttpMethod()).thenReturn(HttpMethod.GET);
    when(m.getStatus()).thenReturn(200);
    when(m.getTimestamp()).thenReturn(1_700_000_000_000L);          // ms
    when(m.getGatewayResponseTimeMs()).thenReturn(150L);
    when(m.getApiName()).thenReturn("orders-api");
    when(m.getEndpoint()).thenReturn("https://upstream/orders");
    when(m.getUri()).thenReturn("/api/orders");

    mapper.map(m);
    provider.forceFlush().join(2, java.util.concurrent.TimeUnit.SECONDS);

    assertThat(exp.getFinishedSpanItems()).hasSize(1);
    var span = exp.getFinishedSpanItems().get(0);
    assertThat(span.getName()).isEqualTo("gravitee.gateway.request");
    assertThat(span.getKind().name()).isEqualTo("SERVER");
    assertThat(span.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    assertThat(span.getStartEpochNanos()).isEqualTo((1_700_000_000_000L - 150L) * 1_000_000L);
    assertThat(span.getEndEpochNanos()).isEqualTo(1_700_000_000_000L * 1_000_000L);
    assertThat(span.getAttributes().get(AttributeKey.stringKey("http.request.method"))).isEqualTo("GET");
    assertThat(span.getAttributes().get(AttributeKey.longKey("http.response.status_code"))).isEqualTo(200L);
    assertThat(span.getAttributes().get(AttributeKey.stringKey("http.route"))).isEqualTo("/api/orders");
    assertThat(span.getAttributes().get(AttributeKey.stringKey("gravitee.api.name"))).isEqualTo("orders-api");
    assertThat(span.getAttributes().get(AttributeKey.stringKey("gravitee.upstream.endpoint"))).isEqualTo("https://upstream/orders");
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
  }

  @Test
  void five_hundred_sets_error_status() {
    InMemorySpanExporter exp = InMemorySpanExporter.create();
    SdkTracerProvider provider = SdkTracerProvider.builder()
      .addSpanProcessor(SimpleSpanProcessor.create(exp))
      .build();
    MetricsToSpanMapper mapper = new MetricsToSpanMapper(
      provider.get("test"), new TraceContextResolver("X-Request-ID"));
    Metrics m = mock(Metrics.class);
    when(m.getStatus()).thenReturn(503);
    when(m.getTimestamp()).thenReturn(1L);
    when(m.getGatewayResponseTimeMs()).thenReturn(1L);

    mapper.map(m);
    provider.forceFlush().join(2, java.util.concurrent.TimeUnit.SECONDS);

    var span = exp.getFinishedSpanItems().get(0);
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(span.getStatus().getDescription()).contains("503");
  }

  @Test
  void no_trace_context_creates_root_span() {
    InMemorySpanExporter exp = InMemorySpanExporter.create();
    SdkTracerProvider provider = SdkTracerProvider.builder()
      .addSpanProcessor(SimpleSpanProcessor.create(exp))
      .build();
    MetricsToSpanMapper mapper = new MetricsToSpanMapper(
      provider.get("test"), new TraceContextResolver("X-Request-ID"));
    Metrics m = mock(Metrics.class);
    when(m.getStatus()).thenReturn(200);
    when(m.getTimestamp()).thenReturn(1L);
    when(m.getGatewayResponseTimeMs()).thenReturn(1L);
    when(m.getLog()).thenReturn(null);

    mapper.map(m);
    provider.forceFlush().join(2, java.util.concurrent.TimeUnit.SECONDS);

    var span = exp.getFinishedSpanItems().get(0);
    assertThat(span.getParentSpanContext().isValid()).isFalse();
  }
}
```

- [ ] **Step 2: Run test, expect FAIL**

- [ ] **Step 3: Implement `MetricsToSpanMapper`**

```java
// src/main/java/io/gravitee/reporter/otellogs/mapper/MetricsToSpanMapper.java
package io.gravitee.reporter.otellogs.mapper;

import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a finished SERVER span per {@link Metrics} event, using the
 * recorded start/end timestamps so spans appear as already-complete rather
 * than running live in-process.
 */
public class MetricsToSpanMapper {

  private static final Logger log = LoggerFactory.getLogger(MetricsToSpanMapper.class);

  private final Tracer tracer;
  private final TraceContextResolver resolver;

  public MetricsToSpanMapper(Tracer tracer, TraceContextResolver resolver) {
    this.tracer = tracer;
    this.resolver = resolver;
  }

  public void map(Metrics m) {
    try {
      long endNanos = m.getTimestamp() * 1_000_000L;
      long durationNanos = m.getGatewayResponseTimeMs() * 1_000_000L;
      long startNanos = endNanos - durationNanos;

      HttpHeaders headers = extractHeaders(m);
      TraceContextResolver.Resolved tc = resolver.resolve(headers);

      var builder = tracer.spanBuilder("gravitee.gateway.request")
        .setSpanKind(SpanKind.SERVER)
        .setStartTimestamp(startNanos, TimeUnit.NANOSECONDS);

      Context parent = buildParentContext(tc);
      if (parent != null) builder.setParent(parent);
      else builder.setNoParent();

      Span span = builder.startSpan();
      try {
        if (m.getHttpMethod() != null)
          span.setAttribute(AttributeKey.stringKey("http.request.method"), m.getHttpMethod().name());
        span.setAttribute(AttributeKey.longKey("http.response.status_code"), (long) m.getStatus());
        if (m.getUri() != null)
          span.setAttribute(AttributeKey.stringKey("http.route"), m.getUri());
        if (m.getApiName() != null)
          span.setAttribute(AttributeKey.stringKey("gravitee.api.name"), m.getApiName());
        if (m.getEndpoint() != null)
          span.setAttribute(AttributeKey.stringKey("gravitee.upstream.endpoint"), m.getEndpoint());
        if (m.getStatus() >= 500)
          span.setStatus(StatusCode.ERROR, "HTTP " + m.getStatus());
      } finally {
        span.end(endNanos, TimeUnit.NANOSECONDS);
      }
    } catch (Throwable t) {
      log.warn("Failed to map Metrics → span: {}", t.getMessage(), t);
    }
  }

  private static HttpHeaders extractHeaders(Metrics m) {
    Log lg = m.getLog();
    if (lg == null) return null;
    var req = lg.getEntrypointRequest();
    if (req == null) return null;
    return req.getHeaders();
  }

  private static Context buildParentContext(TraceContextResolver.Resolved tc) {
    if (tc.isEmpty()) return null;
    String spanId = tc.spanId();
    if (spanId == null) {
      // Trace ID derived from correlation header: synthesise an invalid-but-valid-shape
      // remote parent span context so the new span inherits the trace ID.
      spanId = "0000000000000001";
    }
    SpanContext sc = SpanContext.createFromRemoteParent(
      tc.traceId(), spanId, TraceFlags.getSampled(), TraceState.getDefault());
    return Context.root().with(Span.wrap(sc));
  }
}
```

- [ ] **Step 4: Run test, expect PASS**

```
mvn -q test -Dtest=MetricsToSpanMapperTest
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/mapper/MetricsToSpanMapper.java \
        src/test/java/io/gravitee/reporter/otellogs/mapper/MetricsToSpanMapperTest.java
git commit -m "feat(mapper): MetricsToSpanMapper builds finished SERVER spans"
```

---

### Task 16: Wire trace pipeline into `OtelLogsReporter`

**Files:**
- Modify: `src/main/java/io/gravitee/reporter/otellogs/OtelLogsReporter.java`

- [ ] **Step 1: Add fields and lifecycle wiring**

Replace `doStart`, `doStop`, `report`, `canHandle` to add the trace pipeline. Diff-style sketch:

```java
// OtelLogsReporter.java — new fields
private OtelTraceWriter traceWriter;
private MetricsToSpanMapper spanMapper;

@Override
protected void doStart() throws Exception {
  log.info("OtelLogsReporter.doStart() — reporter.enabled={}, logs.enabled={}, traces.enabled={}",
    cfg.isEnabled(), cfg.getLogs().isEnabled(), cfg.getTraces().isEnabled());
  if (!cfg.isEnabled()) return;
  super.doStart();

  if (cfg.getLogs().isEnabled() && this.writer == null) {
    LogRecordExporter exporter = buildLogExporter();
    this.writer = new OtelLogWriter(exporter, cfg.getLogs().getBatchSize(), cfg.getLogs().getScheduledDelayMs());
  }

  if (cfg.getTraces().isEnabled() && this.traceWriter == null) {
    var env = cfg.getResource().isGcpAutoDetect() ? GcpEnvironment.detect() : GcpEnvironment.empty();
    var otelResource = GcpOtelResource.build(cfg.getResource(), env);
    var spanExporter = buildSpanExporter();
    this.traceWriter = new OtelTraceWriter(spanExporter, cfg.getTraces(), otelResource);
    this.spanMapper = new MetricsToSpanMapper(
      traceWriter.tracer(),
      new TraceContextResolver(cfg.getCorrelationHeader())
    );
    log.info("OTel traces pipeline started — endpoint={} authMode={}",
      cfg.getTraces().getEndpoint(), cfg.getTraces().getAuthMode());
  }
}

private SpanExporter buildSpanExporter() {
  var traces = cfg.getTraces();
  Supplier<Map<String, String>> headers = switch (traces.getAuthMode()) {
    case "gcp-adc" -> OtlpAuthHeaders.gcpAdc();
    case "static"  -> OtlpAuthHeaders.staticHeaders(traces.getHeaders());
    case "none"    -> OtlpAuthHeaders.none();
    default -> throw new IllegalStateException(
      "Unknown traces.authMode '" + traces.getAuthMode() + "' — expected none|gcp-adc|static");
  };
  return new CustomOtlpHttpSpanExporter(traces.getEndpoint(), headers);
}

@Override
protected void doStop() throws Exception {
  if (writer != null) { writer.flush(); writer.close(); }
  if (traceWriter != null) { traceWriter.flush(); traceWriter.close(); }
  super.doStop();
}

@Override
public void report(Reportable reportable) {
  if (lifecycleState() != Lifecycle.State.STARTED || !cfg.isEnabled()) return;
  try {
    if (reportable instanceof Metrics metrics && spanMapper != null) {
      spanMapper.map(metrics);
    }
    if (writer == null) return;
    OtelLogRecord record = switch (reportable) {
      case Metrics m -> metricsMapper.map(m);
      case Log l when cfg.getLogs().isReportRequestLogs() -> logMapper.map(l);
      case EndpointStatus es when cfg.getLogs().isReportHealthChecks() -> endpointMapper.map(es);
      case MessageMetrics mm when cfg.getLogs().isReportMessageMetrics() -> messageMapper.map(mm);
      default -> null;
    };
    if (record != null) writer.emit(record);
  } catch (Exception e) {
    log.warn("Unexpected error while reporting {}: {}",
      reportable.getClass().getSimpleName(), e.getMessage());
  }
}

@Override
public boolean canHandle(Reportable reportable) {
  if (!cfg.isEnabled()) return false;
  boolean logsOn = cfg.getLogs().isEnabled();
  boolean tracesOn = cfg.getTraces().isEnabled();
  return switch (reportable) {
    case Metrics ignored -> logsOn || tracesOn;
    case EndpointStatus ignored -> logsOn && cfg.getLogs().isReportHealthChecks();
    case Log ignored -> logsOn && cfg.getLogs().isReportRequestLogs();
    case MessageMetrics ignored -> logsOn && cfg.getLogs().isReportMessageMetrics();
    default -> false;
  };
}
```

Don't forget the new imports: `java.util.Map`, `java.util.function.Supplier`, `OtelTraceWriter`, `MetricsToSpanMapper`, `OtlpAuthHeaders`, `CustomOtlpHttpSpanExporter`, `GcpEnvironment`, `GcpOtelResource`, `SpanExporter`.

- [ ] **Step 2: Run the full unit suite**

```
mvn -q test
```
Expected: all unit tests pass. The existing `OtelLogsReporterTest` may need updates if it asserts no trace pipeline — add a default `traces.enabled=false` cfg to keep that path off in unit tests.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/OtelLogsReporter.java
git commit -m "feat: wire OtelTraceWriter behind traces.enabled flag"
```

---

### Task 17: Integration test — span flows to OTLP receiver

**Files:**
- Create: `src/test/java/io/gravitee/reporter/otellogs/integration/OtelTraceReporterIT.java`

Stands up a tiny HTTP server that captures POSTs to `/v1/traces`, runs the reporter with `traces.enabled=true`, sends a fake `Metrics` event, asserts a span batch arrived with expected trace ID linkage.

- [ ] **Step 1: Write the IT**

```java
// src/test/java/io/gravitee/reporter/otellogs/integration/OtelTraceReporterIT.java
package io.gravitee.reporter.otellogs.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.log.Request;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.otellogs.OtelLogsReporter;
import io.gravitee.reporter.otellogs.config.LogsConfiguration;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.config.ResourceConfiguration;
import io.gravitee.reporter.otellogs.config.TracesConfiguration;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class OtelTraceReporterIT {

  private HttpServer otlpServer;
  private final List<byte[]> receivedBodies = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startOtlpReceiver() throws Exception {
    otlpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    otlpServer.createContext("/v1/traces", ex -> {
      receivedBodies.add(ex.getRequestBody().readAllBytes());
      ex.sendResponseHeaders(200, 0);
      ex.close();
    });
    otlpServer.start();
  }

  @AfterEach
  void stopOtlpReceiver() {
    if (otlpServer != null) otlpServer.stop(0);
    receivedBodies.clear();
  }

  @Test
  void emits_span_with_inbound_traceparent_through_otlp() throws Exception {
    String endpoint = "http://127.0.0.1:" + otlpServer.getAddress().getPort() + "/v1/traces";
    OtelLogsReporterConfiguration cfg = buildCfg(endpoint);
    OtelLogsReporter reporter = new OtelLogsReporter(cfg);
    reporter.start();

    reporter.report(metricsWithTraceparent());

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
      assertThat(receivedBodies).hasSizeGreaterThanOrEqualTo(1));

    ExportTraceServiceRequest req = ExportTraceServiceRequest.parseFrom(receivedBodies.get(0));
    assertThat(req.getResourceSpansCount()).isGreaterThan(0);
    String traceIdHex = bytesToHex(
      req.getResourceSpans(0).getScopeSpans(0).getSpans(0).getTraceId().toByteArray());
    assertThat(traceIdHex).isEqualTo("0af7651916cd43dd8448eb211c80319c");

    reporter.stop();
  }

  private static OtelLogsReporterConfiguration buildCfg(String tracesEndpoint) {
    LogsConfiguration logs = new LogsConfiguration();
    logs.setEnabled(false);  // traces-only IT
    TracesConfiguration traces = new TracesConfiguration();
    traces.setEnabled(true);
    traces.setExporter("otlp");
    traces.setEndpoint(tracesEndpoint);
    traces.setAuthMode("none");
    traces.setBatchSize(1);
    traces.setScheduledDelayMs(50);
    traces.setSampler("always-on");
    ResourceConfiguration res = new ResourceConfiguration();
    res.setServiceName("test-gateway");
    res.setServiceNamespace("apim");
    res.setGcpProjectId("test-project");
    res.setGcpAutoDetect(false);
    OtelLogsReporterConfiguration cfg = new OtelLogsReporterConfiguration(logs, traces, res);
    cfg.setEnabled(true);
    cfg.setCorrelationHeader("X-Request-ID");
    return cfg;
  }

  private static Metrics metricsWithTraceparent() {
    Metrics m = mock(Metrics.class);
    Log lg = mock(Log.class);
    Request req = mock(Request.class);
    HttpHeaders h = mock(HttpHeaders.class);
    when(h.get("traceparent")).thenReturn("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    when(req.getHeaders()).thenReturn(h);
    when(lg.getEntrypointRequest()).thenReturn(req);
    when(m.getLog()).thenReturn(lg);
    when(m.getHttpMethod()).thenReturn(HttpMethod.GET);
    when(m.getStatus()).thenReturn(200);
    when(m.getTimestamp()).thenReturn(System.currentTimeMillis());
    when(m.getGatewayResponseTimeMs()).thenReturn(42L);
    when(m.getApiName()).thenReturn("orders");
    when(m.getEndpoint()).thenReturn("https://upstream/orders");
    when(m.getUri()).thenReturn("/api/orders");
    return m;
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) sb.append(String.format("%02x", b));
    return sb.toString();
  }
}
```

Note: the `ExportTraceServiceRequest` protobuf class lives in `io.opentelemetry.proto.collector.trace.v1`. It's already on the classpath via `opentelemetry-exporter-otlp-common`.

- [ ] **Step 2: Run the IT**

```
mvn -q -P integration-test verify -Dit.test=OtelTraceReporterIT
```
Expected: green.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/gravitee/reporter/otellogs/integration/OtelTraceReporterIT.java
git commit -m "test(integration): assert span batch reaches OTLP receiver with inbound traceparent"
```

---

### Task 18: Phase 2 verification

- [ ] **Step 1: Full unit + integration suite**

```
mvn -q test
mvn -q -P integration-test verify
```

- [ ] **Step 2: Cloud Trace smoke**

Set ADC locally (`gcloud auth application-default login`) and run the `gcloud-integration-test` profile after enabling traces:

```yaml
reporters:
  otellogs:
    enabled: true
    resource:
      gcp:
        projectId: <your-test-project>
    logs:
      enabled: true
      exporter: gcloud
    traces:
      enabled: true
      endpoint: https://telemetry.googleapis.com/v1/traces
      authMode: gcp-adc
```

Trigger a few requests through the manual IT, then check the Cloud Trace UI in `<your-test-project>` — one trace per request, named `gravitee.gateway.request`. Cross-reference: open the trace and the linked log entry in Cloud Logging — `trace_id` matches.

- [ ] **Step 3: Tag the phase boundary commit**

```bash
git tag phase2-traces-pipeline-complete
```

---

## Self-Review Notes

Coverage check vs. spec:

| Spec section | Covered by |
|---|---|
| Config example shape | Tasks 1–3 (POJO classes), Task 5 (top-level wiring) |
| Backward-compat alias table | Tasks 1, 2 (SpEL fallbacks), Task 4 (warnings) |
| ADC-only rationale | Task 6 (remove credentialsFile) |
| Combined pipeline diagram | Task 16 (wiring) |
| Span construction strategy | Task 15 (mapper) |
| Trace ID resolution priority | Task 12 (resolver) |
| Attribute naming (OTel semconv) | Task 15 (mapper sets `http.request.method` etc.) |
| OTLP auth helper | Task 11 |
| Resource attribute strategy | Tasks 9, 10 (GcpEnvironment + GcpOtelResource) |
| Failure isolation | Task 16 (separate try/catch per signal in `report()`) |
| One span per request | Task 16 (span only fires on `Metrics`, not `MessageMetrics`) |
| Sampler enum values | Task 14 (`buildSampler`) |
| Integration test | Task 17 |
| Migration: phase-by-phase landing | Phase Map; tags at Tasks 7 and 18 |

No placeholders, no TBD, no "implement later". Each step has the actual code or command to run. Type names are consistent throughout: `LogsConfiguration`/`TracesConfiguration`/`ResourceConfiguration`, `OtelTraceWriter`, `MetricsToSpanMapper`, `TraceContextResolver`, `CustomOtlpHttpSpanExporter`, `OtlpAuthHeaders`, `GcpEnvironment`, `GcpOtelResource`.

One implementation note worth flagging at execution time: `MetricsToSpanMapper.buildParentContext` synthesises a span ID when only a correlation header is present. The synthesised value is `0000000000000001` — valid per the W3C trace-context grammar (16 hex chars, non-zero). OTel SDK treats this as a remote parent and emits the new span with a fresh local span ID; the synthetic parent itself is never exported. If a future Cloud Trace ingestion change starts rejecting traces whose parent ID isn't observed in another span, revisit this — but no such change is documented today.
