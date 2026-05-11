# OTel Logs Reporter — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Gravitee APIM reporter plugin that emits structured per-request log records via the OpenTelemetry Logs SDK, exported via OTLP gRPC to Google Cloud Logging (and testable against Loki).

**Architecture:** Mappers convert Gravitee `Reportable` types into `OtelLogRecord` value objects carrying trace context and attributes. `OtelLogWriter` wraps `SdkLoggerProvider` and emits records using the OTel Logger API. The Spring configuration wires the `OtlpGrpcLogRecordExporter` and injects it into the writer, keeping the writer testable via `InMemoryLogRecordExporter`.

**Tech Stack:** Java 21, Maven, Spring (provided by Gravitee runtime), OpenTelemetry Java SDK 1.44.x (BOM-managed), gRPC/Netty (pulled by OTLP exporter), TestContainers (OTel Collector contrib + Loki for integration tests), JUnit 5, Mockito, AssertJ, Awaitility.

---

## File Map

```
pom.xml
src/main/resources/
  plugin.properties
  gravitee.json
  assembly/plugin-assembly.xml
src/main/java/io/gravitee/reporter/otellogs/
  OtelLogsReporter.java
  config/OtelLogsReporterConfiguration.java
  writer/OtelLogWriter.java
  mapper/OtelLogRecord.java
  mapper/OtelLabels.java
  mapper/MetricsToLogRecordMapper.java
  mapper/LogToLogRecordMapper.java
  mapper/EndpointStatusToLogRecordMapper.java
  mapper/MessageMetricsToLogRecordMapper.java
  spring/OtelLogsReporterSpringConfiguration.java
src/test/java/io/gravitee/reporter/otellogs/
  OtelTestSupport.java
  OtelLogsReporterTest.java
  mapper/OtelLabelsTest.java
  mapper/MetricsToLogRecordMapperTest.java
  mapper/LogToLogRecordMapperTest.java
  mapper/EndpointStatusToLogRecordMapperTest.java
  mapper/MessageMetricsToLogRecordMapperTest.java
  writer/OtelLogWriterTest.java
  integration/OtelLogsReporterIT.java
src/test/resources/
  otel-collector-config.yaml
  logback-test.xml
.github/workflows/ci.yml
```

---

## Task 1: Maven scaffold + build files

**Files:**
- Create: `pom.xml`
- Create: `src/main/resources/plugin.properties`
- Create: `src/main/resources/gravitee.json`
- Create: `src/main/resources/assembly/plugin-assembly.xml`
- Create: `src/test/resources/logback-test.xml`

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p src/main/java/io/gravitee/reporter/otellogs/{config,mapper,writer,spring}
mkdir -p src/test/java/io/gravitee/reporter/otellogs/{mapper,writer,integration}
mkdir -p src/main/resources/assembly
mkdir -p src/test/resources
```

- [ ] **Step 2: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--

    Copyright © 2026 Fluent Health (https://fluentinhealth.com)

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

-->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.gravitee</groupId>
        <artifactId>gravitee-parent</artifactId>
        <version>22.6.0</version>
    </parent>

    <groupId>io.gravitee.reporter</groupId>
    <artifactId>gravitee-reporter-otel-logs</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <name>Gravitee.io APIM - Reporter - OTel Logs</name>
    <description>Reports API gateway request logs to Google Cloud Logging via OpenTelemetry OTLP</description>
    <inceptionYear>2026</inceptionYear>

    <properties>
        <gravitee-apim.version>4.9.0</gravitee-apim.version>
        <opentelemetry.version>1.44.1</opentelemetry.version>
        <maven.compiler.release>21</maven.compiler.release>
        <publish-folder-path>graviteeio-apim/plugins/reporters</publish-folder-path>
        <testcontainers.version>1.21.4</testcontainers.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.gravitee.apim</groupId>
                <artifactId>gravitee-apim-bom</artifactId>
                <version>${gravitee-apim.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>io.gravitee.reporter</groupId>
                <artifactId>gravitee-reporter-api</artifactId>
                <version>1.35.1</version>
            </dependency>
            <dependency>
                <groupId>io.opentelemetry</groupId>
                <artifactId>opentelemetry-bom</artifactId>
                <version>${opentelemetry.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>${testcontainers.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- ===== PROVIDED — supplied by the Gravitee gateway runtime ===== -->
        <dependency>
            <groupId>io.gravitee.gateway</groupId>
            <artifactId>gravitee-gateway-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.gravitee.reporter</groupId>
            <artifactId>gravitee-reporter-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.gravitee.common</groupId>
            <artifactId>gravitee-common</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.gravitee.node</groupId>
            <artifactId>gravitee-node-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-beans</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- ===== BUNDLED — shipped inside the plugin ZIP under lib/ ===== -->
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-sdk-logs</artifactId>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-otlp</artifactId>
        </dependency>

        <!-- ===== TEST ===== -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>5.14.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <version>5.14.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-sdk-testing</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <version>4.2.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <resources>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>true</filtering>
                <excludes><exclude>assembly/**</exclude></excludes>
            </resource>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>false</filtering>
                <includes><include>assembly/**</include></includes>
            </resource>
        </resources>

        <plugins>
            <plugin>
                <groupId>com.mycila</groupId>
                <artifactId>license-maven-plugin</artifactId>
                <configuration>
                    <properties>
                        <owner>Fluent Health</owner>
                        <email>https://fluentinhealth.com</email>
                    </properties>
                </configuration>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration><release>21</release></configuration>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <useModulePath>false</useModulePath>
                    <excludedGroups>integration</excludedGroups>
                    <argLine>
                        -Dnet.bytebuddy.experimental=true
                        --add-opens java.base/java.lang=ALL-UNNAMED
                        --add-opens java.base/java.util=ALL-UNNAMED
                    </argLine>
                </configuration>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <executions>
                    <execution>
                        <id>copy-dependencies</id>
                        <phase>prepare-package</phase>
                        <goals><goal>copy-dependencies</goal></goals>
                        <configuration>
                            <outputDirectory>${project.build.directory}/dependencies</outputDirectory>
                            <includeScope>runtime</includeScope>
                            <excludeScope>provided</excludeScope>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-assembly-plugin</artifactId>
                <configuration>
                    <appendAssemblyId>false</appendAssemblyId>
                    <descriptors>
                        <descriptor>src/main/resources/assembly/plugin-assembly.xml</descriptor>
                    </descriptors>
                </configuration>
                <executions>
                    <execution>
                        <id>make-plugin-assembly</id>
                        <phase>package</phase>
                        <goals><goal>single</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <profile>
            <id>integration-test</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-failsafe-plugin</artifactId>
                        <version>3.2.5</version>
                        <executions>
                            <execution>
                                <goals>
                                    <goal>integration-test</goal>
                                    <goal>verify</goal>
                                </goals>
                            </execution>
                        </executions>
                        <configuration>
                            <includes><include>**/*IT.java</include></includes>
                            <argLine>
                                -Dnet.bytebuddy.experimental=true
                                --add-opens java.base/java.lang=ALL-UNNAMED
                                --add-opens java.base/java.util=ALL-UNNAMED
                            </argLine>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
```

- [ ] **Step 3: Create `src/main/resources/plugin.properties`**

```properties
id=otel-logs
name=${project.name}
version=${project.version}
description=${project.description}
class=io.gravitee.reporter.otellogs.OtelLogsReporter
type=reporter
```

- [ ] **Step 4: Create `src/main/resources/gravitee.json`**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "title": "OTel Logs Reporter",
  "description": "Reports API gateway request logs to Google Cloud Logging via OpenTelemetry OTLP",
  "properties": {
    "enabled": {
      "type": "boolean",
      "default": true,
      "title": "Enabled",
      "description": "Activate the OTel logs reporter"
    },
    "endpoint": {
      "type": "string",
      "default": "https://logging.googleapis.com",
      "title": "OTLP endpoint",
      "description": "OTLP gRPC endpoint (e.g. https://logging.googleapis.com for GCL)"
    },
    "insecure": {
      "type": "boolean",
      "default": false,
      "title": "Insecure",
      "description": "Disable TLS — for local development and integration tests only"
    },
    "correlationHeader": {
      "type": "string",
      "default": "X-Request-ID",
      "title": "Correlation header",
      "description": "Request header whose value is used as the OTel traceId; falls back to traceparent then sentry-trace"
    },
    "batchSize": {
      "type": "integer",
      "default": 512,
      "title": "Batch size",
      "description": "Maximum number of log records per OTLP export batch"
    },
    "scheduledDelayMs": {
      "type": "integer",
      "default": 5000,
      "title": "Flush interval (ms)",
      "description": "How often the batch processor flushes pending records"
    },
    "reportHealthChecks": {
      "type": "boolean",
      "default": true,
      "title": "Report health checks",
      "description": "Log EndpointStatus transitions (backend up/down events)"
    },
    "reportLogs": {
      "type": "boolean",
      "default": false,
      "title": "Report full logs",
      "description": "Log request/response metadata (disabled by default — high volume)"
    },
    "reportMessageMetrics": {
      "type": "boolean",
      "default": true,
      "title": "Report message metrics",
      "description": "Log MessageMetrics for async/event-driven APIs"
    }
  }
}
```

- [ ] **Step 5: Create `src/main/resources/assembly/plugin-assembly.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<assembly xmlns="http://maven.apache.org/ASSEMBLY/2.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.2.0
              https://maven.apache.org/xsd/assembly-2.2.0.xsd">
    <id>plugin-assembly</id>
    <formats><format>zip</format></formats>
    <includeBaseDirectory>false</includeBaseDirectory>
    <files>
        <file>
            <source>${project.build.directory}/${project.build.finalName}.jar</source>
            <outputDirectory>/</outputDirectory>
        </file>
    </files>
    <fileSets>
        <fileSet>
            <directory>${project.build.directory}/dependencies</directory>
            <outputDirectory>lib</outputDirectory>
        </fileSet>
    </fileSets>
</assembly>
```

- [ ] **Step 6: Create `src/test/resources/logback-test.xml`**

```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern></encoder>
  </appender>
  <root level="WARN"><appender-ref ref="STDOUT"/></root>
  <logger name="io.gravitee.reporter.otellogs" level="DEBUG"/>
  <logger name="tc" level="INFO"/>
</configuration>
```

- [ ] **Step 7: Verify the project compiles**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS` (no Java source yet — just verify Maven resolves dependencies and resources filter correctly).

- [ ] **Step 8: Commit**

```bash
git init
git add pom.xml src/main/resources/ src/test/resources/logback-test.xml
git commit -m "chore: maven scaffold, plugin descriptors, build config"
```

---

## Task 2: Configuration class

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/config/OtelLogsReporterConfiguration.java`

- [ ] **Step 1: Create the configuration class**

```java
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
package io.gravitee.reporter.otellogs.config;

import org.springframework.beans.factory.annotation.Value;

public class OtelLogsReporterConfiguration {

    @Value("${reporters.otel-logs.enabled:true}")
    private boolean enabled;

    @Value("${reporters.otel-logs.endpoint:https://logging.googleapis.com}")
    private String endpoint;

    @Value("${reporters.otel-logs.insecure:false}")
    private boolean insecure;

    @Value("${reporters.otel-logs.correlationHeader:X-Request-ID}")
    private String correlationHeader;

    @Value("${reporters.otel-logs.batchSize:512}")
    private int batchSize;

    @Value("${reporters.otel-logs.scheduledDelayMs:5000}")
    private int scheduledDelayMs;

    @Value("${reporters.otel-logs.reportHealthChecks:true}")
    private boolean reportHealthChecks;

    @Value("${reporters.otel-logs.reportLogs:false}")
    private boolean reportLogs;

    @Value("${reporters.otel-logs.reportMessageMetrics:true}")
    private boolean reportMessageMetrics;

    public boolean isEnabled() { return enabled; }
    public String getEndpoint() { return endpoint; }
    public boolean isInsecure() { return insecure; }
    public String getCorrelationHeader() { return correlationHeader; }
    public int getBatchSize() { return batchSize; }
    public int getScheduledDelayMs() { return scheduledDelayMs; }
    public boolean isReportHealthChecks() { return reportHealthChecks; }
    public boolean isReportLogs() { return reportLogs; }
    public boolean isReportMessageMetrics() { return reportMessageMetrics; }

    // Setters used by tests (Spring uses @Value injection, not these)
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public void setInsecure(boolean insecure) { this.insecure = insecure; }
    public void setCorrelationHeader(String correlationHeader) { this.correlationHeader = correlationHeader; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public void setScheduledDelayMs(int scheduledDelayMs) { this.scheduledDelayMs = scheduledDelayMs; }
    public void setReportHealthChecks(boolean reportHealthChecks) { this.reportHealthChecks = reportHealthChecks; }
    public void setReportLogs(boolean reportLogs) { this.reportLogs = reportLogs; }
    public void setReportMessageMetrics(boolean reportMessageMetrics) { this.reportMessageMetrics = reportMessageMetrics; }
}
```

- [ ] **Step 2: Compile to verify**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/config/
git commit -m "feat: OtelLogsReporterConfiguration with Spring @Value bindings"
```

---

## Task 3: OtelLogRecord value object + OtelLabels utilities + tests

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/mapper/OtelLogRecord.java`
- Create: `src/main/java/io/gravitee/reporter/otellogs/mapper/OtelLabels.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/mapper/OtelLabelsTest.java`

- [ ] **Step 1: Write the failing tests for `OtelLabels`**

```java
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

import io.opentelemetry.api.logs.Severity;
import org.junit.jupiter.api.Test;

class OtelLabelsTest {

    // ===== normalizeTraceId =====

    @Test
    void uuidBecomesLowercase32HexTraceId() {
        String result = OtelLabels.normalizeTraceId("550e8400-e29b-41d4-a716-446655440000");
        assertThat(result).isEqualTo("550e8400e29b41d4a716446655440000");
    }

    @Test
    void already32HexIsReturnedAsLowercase() {
        String result = OtelLabels.normalizeTraceId("550E8400E29B41D4A716446655440000");
        assertThat(result).isEqualTo("550e8400e29b41d4a716446655440000");
    }

    @Test
    void nullTraceIdReturnsNull() {
        assertThat(OtelLabels.normalizeTraceId(null)).isNull();
    }

    @Test
    void nonHexStringReturnsNull() {
        assertThat(OtelLabels.normalizeTraceId("not-a-trace-id")).isNull();
    }

    @Test
    void blankTraceIdReturnsNull() {
        assertThat(OtelLabels.normalizeTraceId("   ")).isNull();
    }

    // ===== parseSentryTrace =====

    @Test
    void validSentryTraceHeaderParsesTraceAndSpanId() {
        String[] parts = OtelLabels.parseSentryTrace(
            "771a43a4192642f0b136d5159a501700-7d17675a3e4f44e8-1"
        ).orElseThrow();
        assertThat(parts[0]).isEqualTo("771a43a4192642f0b136d5159a501700");
        assertThat(parts[1]).isEqualTo("7d17675a3e4f44e8");
    }

    @Test
    void nullSentryTraceHeaderReturnsEmpty() {
        assertThat(OtelLabels.parseSentryTrace(null)).isEmpty();
    }

    @Test
    void blankSentryTraceHeaderReturnsEmpty() {
        assertThat(OtelLabels.parseSentryTrace("")).isEmpty();
    }

    @Test
    void tooFewSentryTraceSegmentsReturnsEmpty() {
        assertThat(OtelLabels.parseSentryTrace("onlyone")).isEmpty();
    }

    // ===== severityFromStatus =====

    @Test
    void status200IsSeverityInfo() {
        assertThat(OtelLabels.severityFromStatus(200)).isEqualTo(Severity.INFO);
    }

    @Test
    void status400IsSeverityWarn() {
        assertThat(OtelLabels.severityFromStatus(400)).isEqualTo(Severity.WARN);
    }

    @Test
    void status404IsSeverityWarn() {
        assertThat(OtelLabels.severityFromStatus(404)).isEqualTo(Severity.WARN);
    }

    @Test
    void status500IsSeverityError() {
        assertThat(OtelLabels.severityFromStatus(500)).isEqualTo(Severity.ERROR);
    }

    @Test
    void status503IsSeverityError() {
        assertThat(OtelLabels.severityFromStatus(503)).isEqualTo(Severity.ERROR);
    }

    // ===== sanitizePath =====

    @Test
    void numericPathSegmentIsReplacedWithId() {
        assertThat(OtelLabels.sanitizePath("/api/v1/users/42")).isEqualTo("/api/v1/users/{id}");
    }

    @Test
    void uuidPathSegmentIsReplacedWithId() {
        assertThat(OtelLabels.sanitizePath("/api/v1/items/550e8400-e29b-41d4-a716-446655440000"))
            .isEqualTo("/api/v1/items/{id}");
    }

    @Test
    void pathWithNoIdsIsUnchanged() {
        assertThat(OtelLabels.sanitizePath("/api/v1/health")).isEqualTo("/api/v1/health");
    }

    @Test
    void nullPathReturnsNull() {
        assertThat(OtelLabels.sanitizePath(null)).isNull();
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -pl . -Dtest=OtelLabelsTest -q 2>&1 | tail -5
```

Expected: compilation error — `OtelLabels` does not exist yet.

- [ ] **Step 3: Create `OtelLogRecord.java`**

```java
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

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;

/**
 * Intermediate value object produced by mappers and consumed by OtelLogWriter.
 * All fields except severity, timestampEpochNanos, body, and attributes may be null.
 */
public record OtelLogRecord(
    String traceId,          // 32 lowercase hex chars; null if no trace context
    String spanId,           // 16 lowercase hex chars; null if unavailable
    String sentryTraceId,    // from sentry-trace header; null if absent
    String sentrySpanId,     // from sentry-trace header; null if absent
    Severity severity,
    long timestampEpochNanos,
    String body,
    Attributes attributes
) {}
```

- [ ] **Step 4: Create `OtelLabels.java`**

```java
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

import io.opentelemetry.api.logs.Severity;
import java.util.Optional;
import java.util.regex.Pattern;

public final class OtelLabels {

    private static final Pattern HEX_32 = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("/\\d+");
    private static final Pattern UUID_SEGMENT = Pattern.compile(
        "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    private OtelLabels() {}

    /**
     * Normalizes a value to a 32-char lowercase hex OTel trace ID.
     * Accepts UUIDs (strips dashes) or already-32-hex strings.
     * Returns null for anything else.
     */
    public static String normalizeTraceId(String value) {
        if (value == null || value.isBlank()) return null;
        String stripped = value.strip().replace("-", "");
        if (stripped.length() == 32 && HEX_32.matcher(stripped).matches()) {
            return stripped.toLowerCase();
        }
        return null;
    }

    /**
     * Parses a sentry-trace header of the form "{traceId}-{spanId}-{sampled}".
     * Returns Optional.empty() when the header is absent or malformed (fewer than 2 segments).
     * Returned array: [0] = traceId (32 hex), [1] = spanId (16 hex), [2] = sampled (optional).
     */
    public static Optional<String[]> parseSentryTrace(String header) {
        if (header == null || header.isBlank()) return Optional.empty();
        String[] parts = header.strip().split("-", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parts);
    }

    /**
     * Maps an HTTP status code to an OTel Severity level.
     * 5xx → ERROR, 4xx → WARN, everything else → INFO.
     */
    public static Severity severityFromStatus(int status) {
        if (status >= 500) return Severity.ERROR;
        if (status >= 400) return Severity.WARN;
        return Severity.INFO;
    }

    /**
     * Replaces numeric path segments (/42) and UUID path segments
     * (/550e8400-...) with {id} to reduce cardinality.
     */
    public static String sanitizePath(String path) {
        if (path == null) return null;
        return NUMERIC_SEGMENT.matcher(
            UUID_SEGMENT.matcher(path).replaceAll("/{id}")
        ).replaceAll("/{id}");
    }
}
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
mvn test -pl . -Dtest=OtelLabelsTest -q
```

Expected: `BUILD SUCCESS` — all OtelLabelsTest tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/mapper/OtelLogRecord.java \
        src/main/java/io/gravitee/reporter/otellogs/mapper/OtelLabels.java \
        src/test/java/io/gravitee/reporter/otellogs/mapper/OtelLabelsTest.java
git commit -m "feat: OtelLogRecord value object and OtelLabels utilities"
```

---

## Task 4: OtelLogWriter + tests

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/writer/OtelLogWriter.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/writer/OtelLogWriterTest.java`

- [ ] **Step 1: Write the failing tests**

```java
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

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.gravitee.reporter.otellogs.mapper.OtelLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

class OtelLogWriterTest {

    private InMemoryLogRecordExporter exporter;
    private OtelLogWriter writer;

    @BeforeEach
    void setUp() {
        exporter = InMemoryLogRecordExporter.create();
        writer = new OtelLogWriter(exporter, 512, 5000);
    }

    @AfterEach
    void tearDown() {
        writer.close();
    }

    @Test
    void emittedRecordAppearsAfterFlush() {
        var record = new OtelLogRecord(
            null, null, null, null,
            Severity.INFO,
            Instant.parse("2026-01-15T12:00:00Z").toEpochMilli() * 1_000_000L,
            "GET /api/v1/health → 200",
            Attributes.builder()
                .put(AttributeKey.stringKey("api.name"), "Health API")
                .put(AttributeKey.longKey("http.status"), 200L)
                .build()
        );

        writer.emit(record);
        writer.flush();

        var logs = exporter.getFinishedLogRecordItems();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getBody().asString()).isEqualTo("GET /api/v1/health → 200");
        assertThat(logs.get(0).getSeverity()).isEqualTo(Severity.INFO);
        assertThat(logs.get(0).getAttributes().get(AttributeKey.stringKey("api.name"))).isEqualTo("Health API");
        assertThat(logs.get(0).getAttributes().get(AttributeKey.longKey("http.status"))).isEqualTo(200L);
    }

    @Test
    void traceIdAndSpanIdAreSetOnSpanContext() {
        var record = new OtelLogRecord(
            "550e8400e29b41d4a716446655440000",
            "7d17675a3e4f44e8",
            null, null,
            Severity.ERROR,
            Instant.now().toEpochMilli() * 1_000_000L,
            "POST /api/v1/upload → 500",
            Attributes.empty()
        );

        writer.emit(record);
        writer.flush();

        var logs = exporter.getFinishedLogRecordItems();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getSpanContext().getTraceId())
            .isEqualTo("550e8400e29b41d4a716446655440000");
        assertThat(logs.get(0).getSpanContext().getSpanId())
            .isEqualTo("7d17675a3e4f44e8");
    }

    @Test
    void recordWithOnlyTraceIdUsesFirstHalfAsSpanId() {
        var record = new OtelLogRecord(
            "550e8400e29b41d4a716446655440000",
            null, null, null,
            Severity.WARN,
            Instant.now().toEpochMilli() * 1_000_000L,
            "GET /api/v1/items/42 → 404",
            Attributes.empty()
        );

        writer.emit(record);
        writer.flush();

        var logs = exporter.getFinishedLogRecordItems();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getSpanContext().getTraceId())
            .isEqualTo("550e8400e29b41d4a716446655440000");
        assertThat(logs.get(0).getSpanContext().getSpanId())
            .isEqualTo("550e8400e29b41d4"); // first 16 chars of traceId
    }

    @Test
    void recordWithNoTraceIdHasInvalidSpanContext() {
        var record = new OtelLogRecord(
            null, null, null, null,
            Severity.INFO,
            Instant.now().toEpochMilli() * 1_000_000L,
            "GET /api/v1/health → 200",
            Attributes.empty()
        );

        writer.emit(record);
        writer.flush();

        var logs = exporter.getFinishedLogRecordItems();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getSpanContext().isValid()).isFalse();
    }

    @Test
    void sentryTraceIdIsAddedAsAttribute() {
        var record = new OtelLogRecord(
            null, null,
            "771a43a4192642f0b136d5159a501700",
            "7d17675a3e4f44e8",
            Severity.INFO,
            Instant.now().toEpochMilli() * 1_000_000L,
            "GET /api/v1/health → 200",
            Attributes.empty()
        );

        writer.emit(record);
        writer.flush();

        var logs = exporter.getFinishedLogRecordItems();
        assertThat(logs.get(0).getAttributes().get(AttributeKey.stringKey("sentry.trace_id")))
            .isEqualTo("771a43a4192642f0b136d5159a501700");
        assertThat(logs.get(0).getAttributes().get(AttributeKey.stringKey("sentry.span_id")))
            .isEqualTo("7d17675a3e4f44e8");
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -pl . -Dtest=OtelLogWriterTest -q 2>&1 | tail -5
```

Expected: compilation error — `OtelLogWriter` does not exist yet.

- [ ] **Step 3: Create `OtelLogWriter.java`**

```java
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

import io.gravitee.reporter.otellogs.mapper.OtelLogRecord;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class OtelLogWriter implements AutoCloseable {

    private final SdkLoggerProvider sdkLoggerProvider;
    private final Logger otelLogger;

    public OtelLogWriter(LogRecordExporter exporter, int batchSize, int scheduledDelayMs) {
        var processor = BatchLogRecordProcessor.builder(exporter)
            .setMaxExportBatchSize(batchSize)
            .setScheduledDelay(Duration.ofMillis(scheduledDelayMs))
            .build();
        sdkLoggerProvider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(processor)
            .build();
        otelLogger = sdkLoggerProvider.get("gravitee-otel-logs");
    }

    public void emit(OtelLogRecord record) {
        AttributesBuilder attrsBuilder = record.attributes().toBuilder();

        if (record.sentryTraceId() != null) {
            attrsBuilder.put(AttributeKey.stringKey("sentry.trace_id"), record.sentryTraceId());
        }
        if (record.sentrySpanId() != null) {
            attrsBuilder.put(AttributeKey.stringKey("sentry.span_id"), record.sentrySpanId());
        }

        var builder = otelLogger.logRecordBuilder()
            .setSeverity(record.severity())
            .setTimestamp(record.timestampEpochNanos(), TimeUnit.NANOSECONDS)
            .setBody(record.body())
            .setAllAttributes(attrsBuilder.build());

        if (record.traceId() != null) {
            String spanId = record.spanId() != null
                ? record.spanId()
                : record.traceId().substring(0, 16);
            var spanCtx = SpanContext.create(
                record.traceId(), spanId, TraceFlags.getDefault(), TraceState.getDefault()
            );
            builder.setContext(Context.root().with(Span.wrap(spanCtx)));
        }

        builder.emit();
    }

    public void flush() {
        sdkLoggerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        sdkLoggerProvider.shutdown().join(10, TimeUnit.SECONDS);
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
mvn test -pl . -Dtest=OtelLogWriterTest -q
```

Expected: `BUILD SUCCESS` — all 5 writer tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/writer/ \
        src/test/java/io/gravitee/reporter/otellogs/writer/
git commit -m "feat: OtelLogWriter wrapping SdkLoggerProvider with trace context support"
```

---

## Task 5: MetricsToLogRecordMapper + tests

**Files:**
- Create: `src/test/java/io/gravitee/reporter/otellogs/OtelTestSupport.java`
- Create: `src/main/java/io/gravitee/reporter/otellogs/mapper/MetricsToLogRecordMapper.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/mapper/MetricsToLogRecordMapperTest.java`

- [ ] **Step 1: Create shared test support**

```java
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
package io.gravitee.reporter.otellogs;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;

import java.time.Instant;
import java.util.Map;

public final class OtelTestSupport {

    private OtelTestSupport() {}

    public static OtelLogsReporterConfiguration config() {
        var cfg = new OtelLogsReporterConfiguration();
        cfg.setEnabled(true);
        cfg.setEndpoint("http://localhost:4317");
        cfg.setInsecure(true);
        cfg.setCorrelationHeader("X-Request-ID");
        cfg.setBatchSize(512);
        cfg.setScheduledDelayMs(5000);
        cfg.setReportHealthChecks(true);
        cfg.setReportLogs(false);
        cfg.setReportMessageMetrics(true);
        return cfg;
    }

    public static Metrics metrics(int status) {
        Metrics m = new Metrics();
        m.setApiId("api-123");
        m.setApiName("Test API");
        m.setHttpMethod(HttpMethod.GET);
        m.setUri("/api/v1/users/42");
        m.setStatus(status);
        m.setRequestContentLength(128);
        m.setResponseContentLength(512);
        m.setGatewayResponseTimeMs(42);
        m.setEndpoint("https://backend.example.com/api/users/42");
        m.setApplicationName("My App");
        m.setPlanName("Gold");
        m.setSubscriptionId("sub-abc");
        m.setTimestamp(Instant.parse("2026-01-15T12:00:00Z").toEpochMilli());
        return m;
    }

    public static Metrics metricsWithHeaders(int status, Map<String, String> headers) {
        Metrics m = metrics(status);
        m.setRequestHeaders(headers);
        return m;
    }

    public static EndpointStatus endpointStatus(boolean available) {
        EndpointStatus s = EndpointStatus.forEndpoint(
            "api-123", "Test API", "https://backend.example.com/health"
        ).on(Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()).build();
        s.setAvailable(available);
        s.setResponseTime(100);
        return s;
    }

    public static Log log(int status) {
        return Log.builder()
            .apiId("api-123")
            .apiName("Test API")
            .status(status)
            .method(HttpMethod.POST)
            .uri("/api/v1/data")
            .requestContentLength(256)
            .responseContentLength(64)
            .timestamp(Instant.parse("2026-01-15T12:00:00Z").toEpochMilli())
            .build();
    }

    public static MessageMetrics messageMetrics() {
        return MessageMetrics.builder()
            .apiId("api-123")
            .apiName("Test API")
            .count(10)
            .errorCount(1)
            .build();
    }
}
```

> **Note:** `Metrics.setRequestHeaders(Map<String,String>)` may not exist in all reporter API versions. If the setter is absent, remove it from `metricsWithHeaders` and skip header-extraction tests — trace context will fall back to `traceparent`/`sentry-trace` only.

- [ ] **Step 2: Write the failing mapper tests**

```java
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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.gravitee.reporter.otellogs.OtelTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

class MetricsToLogRecordMapperTest {

    private MetricsToLogRecordMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new MetricsToLogRecordMapper(OtelTestSupport.config());
    }

    @Test
    void bodyContainsMethodSanitizedPathAndStatus() {
        var record = mapper.map(OtelTestSupport.metrics(200));
        assertThat(record.body()).isEqualTo("GET /api/v1/users/{id} → 200");
    }

    @Test
    void status200HasSeverityInfo() {
        assertThat(mapper.map(OtelTestSupport.metrics(200)).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void status400HasSeverityWarn() {
        assertThat(mapper.map(OtelTestSupport.metrics(400)).severity()).isEqualTo(Severity.WARN);
    }

    @Test
    void status500HasSeverityError() {
        assertThat(mapper.map(OtelTestSupport.metrics(500)).severity()).isEqualTo(Severity.ERROR);
    }

    @Test
    void apiAttributesAreMapped() {
        var record = mapper.map(OtelTestSupport.metrics(200));
        assertThat(record.attributes().get(AttributeKey.stringKey("api.name"))).isEqualTo("Test API");
        assertThat(record.attributes().get(AttributeKey.stringKey("api.id"))).isEqualTo("api-123");
    }

    @Test
    void httpAttributesAreMapped() {
        var record = mapper.map(OtelTestSupport.metrics(200));
        assertThat(record.attributes().get(AttributeKey.stringKey("http.method"))).isEqualTo("GET");
        assertThat(record.attributes().get(AttributeKey.longKey("http.status"))).isEqualTo(200L);
        assertThat(record.attributes().get(AttributeKey.longKey("http.latency_ms"))).isEqualTo(42L);
    }

    @Test
    void upstreamEndpointPathIsAlsoSanitized() {
        var record = mapper.map(OtelTestSupport.metrics(200));
        assertThat(record.attributes().get(AttributeKey.stringKey("upstream.endpoint")))
            .isEqualTo("https://backend.example.com/api/users/{id}");
    }

    @Test
    void contextAttributesAreMapped() {
        var record = mapper.map(OtelTestSupport.metrics(200));
        assertThat(record.attributes().get(AttributeKey.stringKey("context.application"))).isEqualTo("My App");
        assertThat(record.attributes().get(AttributeKey.stringKey("context.plan"))).isEqualTo("Gold");
        assertThat(record.attributes().get(AttributeKey.stringKey("context.subscription"))).isEqualTo("sub-abc");
    }

    @Test
    void contentLengthsAreMapped() {
        var record = mapper.map(OtelTestSupport.metrics(200));
        assertThat(record.attributes().get(AttributeKey.longKey("entrypoint.request.content_length"))).isEqualTo(128L);
        assertThat(record.attributes().get(AttributeKey.longKey("entrypoint.response.content_length"))).isEqualTo(512L);
    }

    @Test
    void xRequestIdHeaderBecomesTraceId() {
        var m = OtelTestSupport.metricsWithHeaders(200,
            Map.of("X-Request-ID", "550e8400-e29b-41d4-a716-446655440000")
        );
        var record = mapper.map(m);
        assertThat(record.traceId()).isEqualTo("550e8400e29b41d4a716446655440000");
    }

    @Test
    void traceparentHeaderFallsBackWhenCorrelationHeaderAbsent() {
        var m = OtelTestSupport.metricsWithHeaders(200,
            Map.of("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        );
        var record = mapper.map(m);
        assertThat(record.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(record.spanId()).isEqualTo("00f067aa0ba902b7");
    }

    @Test
    void sentryTraceHeaderExtractsSentryTraceIdAndSpanId() {
        var m = OtelTestSupport.metricsWithHeaders(200,
            Map.of("sentry-trace", "771a43a4192642f0b136d5159a501700-7d17675a3e4f44e8-1")
        );
        var record = mapper.map(m);
        assertThat(record.sentryTraceId()).isEqualTo("771a43a4192642f0b136d5159a501700");
        assertThat(record.sentrySpanId()).isEqualTo("7d17675a3e4f44e8");
    }

    @Test
    void noHeadersMeansNullTraceId() {
        var record = mapper.map(OtelTestSupport.metrics(200));
        assertThat(record.traceId()).isNull();
        assertThat(record.sentryTraceId()).isNull();
    }

    @Test
    void timestampIsConvertedToNanoseconds() {
        var record = mapper.map(OtelTestSupport.metrics(200));
        // 2026-01-15T12:00:00Z = 1736942400000 ms → * 1_000_000
        assertThat(record.timestampEpochNanos()).isEqualTo(1736942400000L * 1_000_000L);
    }
}
```

- [ ] **Step 3: Run tests to confirm they fail**

```bash
mvn test -pl . -Dtest=MetricsToLogRecordMapperTest -q 2>&1 | tail -5
```

Expected: compilation error — `MetricsToLogRecordMapper` does not exist.

- [ ] **Step 4: Create `MetricsToLogRecordMapper.java`**

```java
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

import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;

import java.util.Map;

public class MetricsToLogRecordMapper {

    private final OtelLogsReporterConfiguration config;

    public MetricsToLogRecordMapper(OtelLogsReporterConfiguration config) {
        this.config = config;
    }

    public OtelLogRecord map(Metrics m) {
        Map<String, String> headers = m.getRequestHeaders() != null ? m.getRequestHeaders() : Map.of();

        String traceId = resolveTraceId(headers);
        String spanId = resolveSpanId(headers);

        String[] sentryParts = OtelLabels.parseSentryTrace(headers.get("sentry-trace")).orElse(null);
        String sentryTraceId = sentryParts != null ? sentryParts[0] : null;
        String sentrySpanId = sentryParts != null ? sentryParts[1] : null;

        String method = m.getHttpMethod() != null ? m.getHttpMethod().name() : "UNKNOWN";
        String path = OtelLabels.sanitizePath(m.getUri());
        String body = method + " " + path + " → " + m.getStatus();

        return new OtelLogRecord(
            traceId, spanId, sentryTraceId, sentrySpanId,
            OtelLabels.severityFromStatus(m.getStatus()),
            m.getTimestamp() * 1_000_000L,
            body,
            buildAttributes(m)
        );
    }

    private String resolveTraceId(Map<String, String> headers) {
        String corrValue = headers.get(config.getCorrelationHeader());
        if (corrValue != null) {
            String normalized = OtelLabels.normalizeTraceId(corrValue);
            if (normalized != null) return normalized;
        }
        String traceparent = headers.get("traceparent");
        if (traceparent != null) {
            String[] parts = traceparent.split("-", 4);
            if (parts.length >= 3) return parts[1];
        }
        String sentryTrace = headers.get("sentry-trace");
        if (sentryTrace != null) {
            String[] parts = sentryTrace.split("-", 3);
            if (parts.length >= 1 && !parts[0].isBlank()) return parts[0];
        }
        return null;
    }

    private String resolveSpanId(Map<String, String> headers) {
        String traceparent = headers.get("traceparent");
        if (traceparent != null) {
            String[] parts = traceparent.split("-", 4);
            if (parts.length >= 3) return parts[2];
        }
        String sentryTrace = headers.get("sentry-trace");
        if (sentryTrace != null) {
            String[] parts = sentryTrace.split("-", 3);
            if (parts.length >= 2 && !parts[1].isBlank()) return parts[1];
        }
        return null;
    }

    private Attributes buildAttributes(Metrics m) {
        AttributesBuilder b = Attributes.builder();
        if (m.getApiName() != null) b.put(AttributeKey.stringKey("api.name"), m.getApiName());
        if (m.getApiId() != null) b.put(AttributeKey.stringKey("api.id"), m.getApiId());
        if (m.getHttpMethod() != null) b.put(AttributeKey.stringKey("http.method"), m.getHttpMethod().name());
        b.put(AttributeKey.longKey("http.status"), (long) m.getStatus());
        b.put(AttributeKey.longKey("http.latency_ms"), m.getGatewayResponseTimeMs());
        if (m.getEndpoint() != null) {
            b.put(AttributeKey.stringKey("upstream.endpoint"), OtelLabels.sanitizePath(m.getEndpoint()));
        }
        if (m.getApplicationName() != null) b.put(AttributeKey.stringKey("context.application"), m.getApplicationName());
        if (m.getPlanName() != null) b.put(AttributeKey.stringKey("context.plan"), m.getPlanName());
        if (m.getSubscriptionId() != null) b.put(AttributeKey.stringKey("context.subscription"), m.getSubscriptionId());
        if (m.getRequestContentLength() > 0) {
            b.put(AttributeKey.longKey("entrypoint.request.content_length"), m.getRequestContentLength());
        }
        if (m.getResponseContentLength() > 0) {
            b.put(AttributeKey.longKey("entrypoint.response.content_length"), m.getResponseContentLength());
        }
        return b.build();
    }
}
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
mvn test -pl . -Dtest=MetricsToLogRecordMapperTest -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add src/test/java/io/gravitee/reporter/otellogs/OtelTestSupport.java \
        src/main/java/io/gravitee/reporter/otellogs/mapper/MetricsToLogRecordMapper.java \
        src/test/java/io/gravitee/reporter/otellogs/mapper/MetricsToLogRecordMapperTest.java
git commit -m "feat: MetricsToLogRecordMapper with trace context extraction"
```

---

## Task 6: LogToLogRecordMapper + tests

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/mapper/LogToLogRecordMapper.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/mapper/LogToLogRecordMapperTest.java`

- [ ] **Step 1: Write the failing tests**

```java
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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.gravitee.reporter.otellogs.OtelTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogToLogRecordMapperTest {

    private LogToLogRecordMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new LogToLogRecordMapper();
    }

    @Test
    void bodyContainsMethodAndPath() {
        var record = mapper.map(OtelTestSupport.log(201));
        assertThat(record.body()).isEqualTo("POST /api/v1/data → 201");
    }

    @Test
    void status201HasSeverityInfo() {
        assertThat(mapper.map(OtelTestSupport.log(201)).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void status500HasSeverityError() {
        assertThat(mapper.map(OtelTestSupport.log(500)).severity()).isEqualTo(Severity.ERROR);
    }

    @Test
    void apiAttributesAreMapped() {
        var record = mapper.map(OtelTestSupport.log(200));
        assertThat(record.attributes().get(AttributeKey.stringKey("api.name"))).isEqualTo("Test API");
        assertThat(record.attributes().get(AttributeKey.stringKey("api.id"))).isEqualTo("api-123");
    }

    @Test
    void contentLengthsAreMappedNotPayloads() {
        var record = mapper.map(OtelTestSupport.log(200));
        assertThat(record.attributes().get(AttributeKey.longKey("log.request.content_length"))).isEqualTo(256L);
        assertThat(record.attributes().get(AttributeKey.longKey("log.response.content_length"))).isEqualTo(64L);
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -pl . -Dtest=LogToLogRecordMapperTest -q 2>&1 | tail -5
```

Expected: compilation error.

- [ ] **Step 3: Create `LogToLogRecordMapper.java`**

```java
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

import io.gravitee.reporter.api.v4.log.Log;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;

public class LogToLogRecordMapper {

    public OtelLogRecord map(Log log) {
        String method = log.getMethod() != null ? log.getMethod().name() : "UNKNOWN";
        String path = OtelLabels.sanitizePath(log.getUri());
        String body = method + " " + path + " → " + log.getStatus();

        AttributesBuilder b = Attributes.builder();
        if (log.getApiName() != null) b.put(AttributeKey.stringKey("api.name"), log.getApiName());
        if (log.getApiId() != null) b.put(AttributeKey.stringKey("api.id"), log.getApiId());
        if (log.getMethod() != null) b.put(AttributeKey.stringKey("http.method"), log.getMethod().name());
        b.put(AttributeKey.longKey("http.status"), (long) log.getStatus());
        if (log.getRequestContentLength() > 0) {
            b.put(AttributeKey.longKey("log.request.content_length"), log.getRequestContentLength());
        }
        if (log.getResponseContentLength() > 0) {
            b.put(AttributeKey.longKey("log.response.content_length"), log.getResponseContentLength());
        }

        return new OtelLogRecord(
            null, null, null, null,
            OtelLabels.severityFromStatus(log.getStatus()),
            log.getTimestamp() * 1_000_000L,
            body,
            b.build()
        );
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
mvn test -pl . -Dtest=LogToLogRecordMapperTest -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/mapper/LogToLogRecordMapper.java \
        src/test/java/io/gravitee/reporter/otellogs/mapper/LogToLogRecordMapperTest.java
git commit -m "feat: LogToLogRecordMapper (metadata only, no payload)"
```

---

## Task 7: EndpointStatusToLogRecordMapper + tests

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/mapper/EndpointStatusToLogRecordMapper.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/mapper/EndpointStatusToLogRecordMapperTest.java`

- [ ] **Step 1: Write the failing tests**

```java
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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.gravitee.reporter.otellogs.OtelTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EndpointStatusToLogRecordMapperTest {

    private EndpointStatusToLogRecordMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new EndpointStatusToLogRecordMapper();
    }

    @Test
    void availableEndpointHasSeverityInfo() {
        assertThat(mapper.map(OtelTestSupport.endpointStatus(true)).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void unavailableEndpointHasSeverityError() {
        assertThat(mapper.map(OtelTestSupport.endpointStatus(false)).severity()).isEqualTo(Severity.ERROR);
    }

    @Test
    void bodyDescribesTransition() {
        assertThat(mapper.map(OtelTestSupport.endpointStatus(true)).body()).contains("UP");
        assertThat(mapper.map(OtelTestSupport.endpointStatus(false)).body()).contains("DOWN");
    }

    @Test
    void endpointAttributesAreMapped() {
        var record = mapper.map(OtelTestSupport.endpointStatus(false));
        assertThat(record.attributes().get(AttributeKey.stringKey("api.name"))).isEqualTo("Test API");
        assertThat(record.attributes().get(AttributeKey.stringKey("endpoint.url")))
            .isEqualTo("https://backend.example.com/health");
        assertThat(record.attributes().get(AttributeKey.stringKey("endpoint.status"))).isEqualTo("DOWN");
        assertThat(record.attributes().get(AttributeKey.longKey("endpoint.response_time_ms"))).isEqualTo(100L);
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -pl . -Dtest=EndpointStatusToLogRecordMapperTest -q 2>&1 | tail -5
```

Expected: compilation error.

- [ ] **Step 3: Create `EndpointStatusToLogRecordMapper.java`**

```java
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

import io.gravitee.reporter.api.health.EndpointStatus;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Severity;

public class EndpointStatusToLogRecordMapper {

    public OtelLogRecord map(EndpointStatus es) {
        String status = es.isAvailable() ? "UP" : "DOWN";
        String endpoint = es.getEndpoint() != null ? es.getEndpoint() : "unknown";
        String body = "Endpoint " + endpoint + " is " + status;

        AttributesBuilder b = Attributes.builder();
        if (es.getApiName() != null) b.put(AttributeKey.stringKey("api.name"), es.getApiName());
        if (es.getApiId() != null) b.put(AttributeKey.stringKey("api.id"), es.getApiId());
        b.put(AttributeKey.stringKey("endpoint.url"), endpoint);
        b.put(AttributeKey.stringKey("endpoint.status"), status);
        b.put(AttributeKey.longKey("endpoint.response_time_ms"), es.getResponseTime());

        return new OtelLogRecord(
            null, null, null, null,
            es.isAvailable() ? Severity.INFO : Severity.ERROR,
            es.getTimestamp() * 1_000_000L,
            body,
            b.build()
        );
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
mvn test -pl . -Dtest=EndpointStatusToLogRecordMapperTest -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/mapper/EndpointStatusToLogRecordMapper.java \
        src/test/java/io/gravitee/reporter/otellogs/mapper/EndpointStatusToLogRecordMapperTest.java
git commit -m "feat: EndpointStatusToLogRecordMapper for health check transitions"
```

---

## Task 8: MessageMetricsToLogRecordMapper + tests

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/mapper/MessageMetricsToLogRecordMapper.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/mapper/MessageMetricsToLogRecordMapperTest.java`

- [ ] **Step 1: Write the failing tests**

```java
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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.gravitee.reporter.otellogs.OtelTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageMetricsToLogRecordMapperTest {

    private MessageMetricsToLogRecordMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new MessageMetricsToLogRecordMapper();
    }

    @Test
    void severityIsInfoWhenNoErrors() {
        assertThat(mapper.map(OtelTestSupport.messageMetrics()).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void bodyDescribesMessageCounts() {
        var record = mapper.map(OtelTestSupport.messageMetrics());
        assertThat(record.body()).contains("10").contains("1 error");
    }

    @Test
    void apiAttributesAreMapped() {
        var record = mapper.map(OtelTestSupport.messageMetrics());
        assertThat(record.attributes().get(AttributeKey.stringKey("api.name"))).isEqualTo("Test API");
        assertThat(record.attributes().get(AttributeKey.stringKey("api.id"))).isEqualTo("api-123");
    }

    @Test
    void messageCountAttributesAreMapped() {
        var record = mapper.map(OtelTestSupport.messageMetrics());
        assertThat(record.attributes().get(AttributeKey.longKey("message.count"))).isEqualTo(10L);
        assertThat(record.attributes().get(AttributeKey.longKey("message.error_count"))).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -pl . -Dtest=MessageMetricsToLogRecordMapperTest -q 2>&1 | tail -5
```

Expected: compilation error.

- [ ] **Step 3: Create `MessageMetricsToLogRecordMapper.java`**

> **Note:** Check which fields `MessageMetrics` actually exposes. The builder in `OtelTestSupport` calls `.count(10).errorCount(1)`. Use getters like `mm.getCount()` / `mm.getErrorCount()`. If the class also exposes `getBytesIn()`/`getBytesOut()`, add those attributes too.

```java
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

import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Severity;

public class MessageMetricsToLogRecordMapper {

    public OtelLogRecord map(MessageMetrics mm) {
        long errorCount = mm.getErrorCount();
        String body = "Messages: " + mm.getCount()
            + (errorCount > 0 ? " (" + errorCount + " error" + (errorCount > 1 ? "s" : "") + ")" : "");

        AttributesBuilder b = Attributes.builder();
        if (mm.getApiName() != null) b.put(AttributeKey.stringKey("api.name"), mm.getApiName());
        if (mm.getApiId() != null) b.put(AttributeKey.stringKey("api.id"), mm.getApiId());
        b.put(AttributeKey.longKey("message.count"), mm.getCount());
        b.put(AttributeKey.longKey("message.error_count"), errorCount);

        return new OtelLogRecord(
            null, null, null, null,
            errorCount > 0 ? Severity.WARN : Severity.INFO,
            mm.getTimestamp() * 1_000_000L,
            body,
            b.build()
        );
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
mvn test -pl . -Dtest=MessageMetricsToLogRecordMapperTest -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/mapper/MessageMetricsToLogRecordMapper.java \
        src/test/java/io/gravitee/reporter/otellogs/mapper/MessageMetricsToLogRecordMapperTest.java
git commit -m "feat: MessageMetricsToLogRecordMapper for async/event-driven APIs"
```

---

## Task 9: Spring configuration + OtelLogsReporter + reporter unit tests

**Files:**
- Create: `src/main/java/io/gravitee/reporter/otellogs/spring/OtelLogsReporterSpringConfiguration.java`
- Create: `src/main/java/io/gravitee/reporter/otellogs/OtelLogsReporter.java`
- Create: `src/test/java/io/gravitee/reporter/otellogs/OtelLogsReporterTest.java`

- [ ] **Step 1: Write the failing reporter tests**

```java
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
package io.gravitee.reporter.otellogs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.gravitee.node.api.monitor.Monitor;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.mapper.*;
import io.gravitee.reporter.otellogs.writer.OtelLogWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OtelLogsReporterTest {

    @Mock private OtelLogsReporterConfiguration cfg;
    @Mock private OtelLogWriter writer;
    @Mock private MetricsToLogRecordMapper metricsMapper;
    @Mock private LogToLogRecordMapper logMapper;
    @Mock private EndpointStatusToLogRecordMapper endpointMapper;
    @Mock private MessageMetricsToLogRecordMapper messageMapper;

    private OtelLogsReporter reporter;

    @BeforeEach
    void setUp() throws Exception {
        when(cfg.isEnabled()).thenReturn(true);
        when(cfg.isReportHealthChecks()).thenReturn(true);
        when(cfg.isReportLogs()).thenReturn(false);
        when(cfg.isReportMessageMetrics()).thenReturn(true);

        reporter = new OtelLogsReporter();
        inject(reporter, "cfg", cfg);
        inject(reporter, "writer", writer);
        inject(reporter, "metricsMapper", metricsMapper);
        inject(reporter, "logMapper", logMapper);
        inject(reporter, "endpointMapper", endpointMapper);
        inject(reporter, "messageMapper", messageMapper);
    }

    // ===== canHandle =====

    @Test
    void metricsAreAlwaysHandledWhenEnabled() {
        assertThat(reporter.canHandle(OtelTestSupport.metrics(200))).isTrue();
    }

    @Test
    void endpointStatusHandledWhenReportHealthChecksEnabled() {
        assertThat(reporter.canHandle(OtelTestSupport.endpointStatus(true))).isTrue();
    }

    @Test
    void endpointStatusNotHandledWhenReportHealthChecksDisabled() {
        when(cfg.isReportHealthChecks()).thenReturn(false);
        assertThat(reporter.canHandle(OtelTestSupport.endpointStatus(true))).isFalse();
    }

    @Test
    void logNotHandledWhenReportLogsDisabled() {
        assertThat(reporter.canHandle(OtelTestSupport.log(200))).isFalse();
    }

    @Test
    void logHandledWhenReportLogsEnabled() {
        when(cfg.isReportLogs()).thenReturn(true);
        assertThat(reporter.canHandle(OtelTestSupport.log(200))).isTrue();
    }

    @Test
    void messageMetricsHandledWhenEnabled() {
        assertThat(reporter.canHandle(OtelTestSupport.messageMetrics())).isTrue();
    }

    @Test
    void messageMetricsNotHandledWhenDisabled() {
        when(cfg.isReportMessageMetrics()).thenReturn(false);
        assertThat(reporter.canHandle(OtelTestSupport.messageMetrics())).isFalse();
    }

    @Test
    void monitorIsNeverHandled() {
        assertThat(reporter.canHandle(mock(Monitor.class))).isFalse();
    }

    @Test
    void disabledReporterHandlesNothing() {
        when(cfg.isEnabled()).thenReturn(false);
        assertThat(reporter.canHandle(OtelTestSupport.metrics(200))).isFalse();
        assertThat(reporter.canHandle(OtelTestSupport.endpointStatus(true))).isFalse();
    }

    // ===== report =====

    @Test
    void metricsAreMappedAndEmitted() {
        var m = OtelTestSupport.metrics(200);
        var record = new io.gravitee.reporter.otellogs.mapper.OtelLogRecord(
            null, null, null, null,
            io.opentelemetry.api.logs.Severity.INFO,
            0L, "body", io.opentelemetry.api.common.Attributes.empty()
        );
        when(metricsMapper.map(m)).thenReturn(record);
        reporter.report(m);
        verify(writer).emit(record);
    }

    @Test
    void endpointStatusIsMappedAndEmitted() {
        var es = OtelTestSupport.endpointStatus(false);
        var record = new io.gravitee.reporter.otellogs.mapper.OtelLogRecord(
            null, null, null, null,
            io.opentelemetry.api.logs.Severity.ERROR,
            0L, "body", io.opentelemetry.api.common.Attributes.empty()
        );
        when(endpointMapper.map(es)).thenReturn(record);
        reporter.report(es);
        verify(writer).emit(record);
    }

    @Test
    void logNotEmittedWhenReportLogsDisabled() {
        reporter.report(OtelTestSupport.log(200));
        verify(writer, never()).emit(any());
    }

    @Test
    void disabledReporterDoesNotEmit() {
        when(cfg.isEnabled()).thenReturn(false);
        reporter.report(OtelTestSupport.metrics(200));
        verify(writer, never()).emit(any());
    }

    // ===== helpers =====

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) return findField(clazz.getSuperclass(), name);
            throw e;
        }
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
mvn test -pl . -Dtest=OtelLogsReporterTest -q 2>&1 | tail -5
```

Expected: compilation error.

- [ ] **Step 3: Create `OtelLogsReporter.java`**

```java
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
package io.gravitee.reporter.otellogs;

import io.gravitee.common.service.AbstractService;
import io.gravitee.reporter.api.Reportable;
import io.gravitee.reporter.api.Reporter;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.mapper.*;
import io.gravitee.reporter.otellogs.writer.OtelLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class OtelLogsReporter extends AbstractService<Reporter> implements Reporter {

    private static final Logger log = LoggerFactory.getLogger(OtelLogsReporter.class);

    @Autowired private OtelLogsReporterConfiguration cfg;
    @Autowired private OtelLogWriter writer;
    @Autowired private MetricsToLogRecordMapper metricsMapper;
    @Autowired private LogToLogRecordMapper logMapper;
    @Autowired private EndpointStatusToLogRecordMapper endpointMapper;
    @Autowired private MessageMetricsToLogRecordMapper messageMapper;

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        if (!cfg.isEnabled()) {
            log.info("OTel logs reporter is disabled");
            return;
        }
        log.info("OTel logs reporter started — exporting to {}", cfg.getEndpoint());
    }

    @Override
    protected void doStop() throws Exception {
        if (cfg.isEnabled()) {
            writer.close();
        }
        super.doStop();
    }

    @Override
    public boolean canHandle(Reportable reportable) {
        if (!cfg.isEnabled()) return false;
        return switch (reportable) {
            case Metrics ignored -> true;
            case EndpointStatus ignored -> cfg.isReportHealthChecks();
            case Log ignored -> cfg.isReportLogs();
            case MessageMetrics ignored -> cfg.isReportMessageMetrics();
            default -> false;
        };
    }

    @Override
    public void report(Reportable reportable) {
        if (!cfg.isEnabled()) return;
        try {
            OtelLogRecord record = switch (reportable) {
                case Metrics m -> metricsMapper.map(m);
                case EndpointStatus es -> endpointMapper.map(es);
                case Log l -> logMapper.map(l);
                case MessageMetrics mm -> messageMapper.map(mm);
                default -> null;
            };
            if (record != null) {
                writer.emit(record);
            }
        } catch (Exception e) {
            log.warn("Unexpected error while reporting OTel log — skipping", e);
        }
    }
}
```

- [ ] **Step 4: Create `OtelLogsReporterSpringConfiguration.java`**

```java
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
package io.gravitee.reporter.otellogs.spring;

import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.mapper.*;
import io.gravitee.reporter.otellogs.writer.OtelLogWriter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OtelLogsReporterSpringConfiguration {

    @Bean
    public OtelLogsReporterConfiguration otelLogsReporterConfiguration() {
        return new OtelLogsReporterConfiguration();
    }

    @Bean
    public OtelLogWriter otelLogWriter(OtelLogsReporterConfiguration cfg) {
        var exporterBuilder = OtlpGrpcLogRecordExporter.builder()
            .setEndpoint(cfg.getEndpoint());
        if (cfg.isInsecure()) {
            exporterBuilder.setCompression("none");
            // Use plaintext channel for insecure connections
            exporterBuilder = OtlpGrpcLogRecordExporter.builder()
                .setEndpoint(cfg.getEndpoint())
                .setCompression("none");
            // Note: for true insecure (no TLS), the endpoint must use http:// scheme
            // OTel SDK detects http:// and skips TLS automatically
        }
        return new OtelLogWriter(exporterBuilder.build(), cfg.getBatchSize(), cfg.getScheduledDelayMs());
    }

    @Bean
    public MetricsToLogRecordMapper metricsToLogRecordMapper(OtelLogsReporterConfiguration cfg) {
        return new MetricsToLogRecordMapper(cfg);
    }

    @Bean
    public LogToLogRecordMapper logToLogRecordMapper() {
        return new LogToLogRecordMapper();
    }

    @Bean
    public EndpointStatusToLogRecordMapper endpointStatusToLogRecordMapper() {
        return new EndpointStatusToLogRecordMapper();
    }

    @Bean
    public MessageMetricsToLogRecordMapper messageMetricsToLogRecordMapper() {
        return new MessageMetricsToLogRecordMapper();
    }
}
```

> **Note on insecure connections:** The OTel gRPC exporter automatically uses plaintext when the endpoint scheme is `http://`. For insecure integration tests, set `endpoint: "http://localhost:4317"`. The `insecure` flag in config is a reminder/validator but the scheme drives actual TLS behaviour. Simplify the Spring config if the double-builder pattern is awkward — the key is that `http://` endpoints skip TLS.

- [ ] **Step 5: Run all unit tests**

```bash
mvn test -q
```

Expected: `BUILD SUCCESS` — all unit tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/gravitee/reporter/otellogs/OtelLogsReporter.java \
        src/main/java/io/gravitee/reporter/otellogs/spring/ \
        src/test/java/io/gravitee/reporter/otellogs/OtelLogsReporterTest.java
git commit -m "feat: OtelLogsReporter and Spring configuration wiring"
```

---

## Task 10: Integration tests (OTel Collector + Loki)

**Files:**
- Create: `src/test/resources/otel-collector-config.yaml`
- Create: `src/test/java/io/gravitee/reporter/otellogs/integration/OtelLogsReporterIT.java`

- [ ] **Step 1: Create the OTel Collector config**

```yaml
# src/test/resources/otel-collector-config.yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: "0.0.0.0:4317"

exporters:
  loki:
    endpoint: "http://loki:3100/loki/api/v1/push"
    labels:
      attributes:
        api.name: "api_name"
        severity: "severity"

service:
  pipelines:
    logs:
      receivers: [otlp]
      exporters: [loki]
```

- [ ] **Step 2: Write the integration test**

```java
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
package io.gravitee.reporter.otellogs.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.gravitee.reporter.otellogs.OtelTestSupport;
import io.gravitee.reporter.otellogs.OtelLogsReporter;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.mapper.*;
import io.gravitee.reporter.otellogs.writer.OtelLogWriter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Tag("integration")
class OtelLogsReporterIT {

    private static final Logger log = LoggerFactory.getLogger(OtelLogsReporterIT.class);
    private static final int OTLP_PORT = 4317;
    private static final int LOKI_PORT = 3100;

    private static final Network NETWORK = Network.newNetwork();
    private static GenericContainer<?> loki;
    private static GenericContainer<?> collector;

    private static OtelLogsReporter reporter;
    private static OtelLogWriter writer;
    private static HttpClient http;
    private static String lokiBase;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        loki = new GenericContainer<>("grafana/loki:3.0.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("loki")
            .withExposedPorts(LOKI_PORT)
            .withCommand("-config.file=/etc/loki/local-config.yaml")
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tc.loki")))
            .waitingFor(Wait.forHttp("/ready").forPort(LOKI_PORT).forStatusCode(200)
                .withStartupTimeout(Duration.ofSeconds(60)));

        collector = new GenericContainer<>("otel/opentelemetry-collector-contrib:0.104.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("collector")
            .withExposedPorts(OTLP_PORT)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("otel-collector-config.yaml"),
                "/etc/otel-collector-config.yaml"
            )
            .withCommand("--config=/etc/otel-collector-config.yaml")
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tc.collector")))
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)))
            .dependsOn(loki);

        Startables.deepStart(loki, collector).join();

        lokiBase = "http://localhost:" + loki.getMappedPort(LOKI_PORT);
        http = HttpClient.newHttpClient();

        String collectorEndpoint = "http://localhost:" + collector.getMappedPort(OTLP_PORT);
        var exporter = OtlpGrpcLogRecordExporter.builder()
            .setEndpoint(collectorEndpoint)
            .build();
        writer = new OtelLogWriter(exporter, 10, 500);

        var cfg = OtelTestSupport.config();

        reporter = new OtelLogsReporter();
        inject(reporter, "cfg", cfg);
        inject(reporter, "writer", writer);
        inject(reporter, "metricsMapper", new MetricsToLogRecordMapper(cfg));
        inject(reporter, "logMapper", new LogToLogRecordMapper());
        inject(reporter, "endpointMapper", new EndpointStatusToLogRecordMapper());
        inject(reporter, "messageMapper", new MessageMetricsToLogRecordMapper());
        reporter.doStart();
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (reporter != null) reporter.doStop();
        Stream.of(collector, loki).filter(Objects::nonNull).forEach(GenericContainer::stop);
        NETWORK.close();
    }

    @Test
    void metricsLogAppearsInLoki() {
        reporter.report(OtelTestSupport.metrics(200));
        writer.flush();

        await("log to appear in Loki")
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(2))
            .until(() -> queryLoki("{api_name=\"Test API\"}").contains("Test API"));
    }

    @Test
    void errorStatusProducesSeverityErrorInLoki() {
        reporter.report(OtelTestSupport.metrics(500));
        writer.flush();

        await("ERROR log to appear in Loki")
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(2))
            .until(() -> queryLoki("{severity=\"ERROR\"}").contains("Test API"));
    }

    @Test
    void traceIdFromXRequestIdIsQueryableInLoki() {
        var m = OtelTestSupport.metricsWithHeaders(200,
            Map.of("X-Request-ID", "550e8400-e29b-41d4-a716-446655440000")
        );
        reporter.report(m);
        writer.flush();

        // OTel Collector maps the OTel traceId to a Loki label or log line attribute.
        // Query for any log with the trace ID in the line.
        await("trace log to appear")
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(2))
            .until(() -> queryLoki("{app=\"gravitee\"} |= \"550e8400e29b41d4a716446655440000\"")
                .contains("550e8400e29b41d4a716446655440000"));
    }

    @Test
    void endpointStatusDownAppearsInLoki() {
        reporter.report(OtelTestSupport.endpointStatus(false));
        writer.flush();

        await("endpoint DOWN log to appear")
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(2))
            .until(() -> queryLoki("{api_name=\"Test API\"}").contains("DOWN"));
    }

    @Test
    void sentryTraceAttributeAppearsInLogLine() {
        var m = OtelTestSupport.metricsWithHeaders(200,
            Map.of("sentry-trace", "771a43a4192642f0b136d5159a501700-7d17675a3e4f44e8-1")
        );
        reporter.report(m);
        writer.flush();

        await("sentry trace attribute to appear")
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofSeconds(2))
            .until(() -> queryLoki("{api_name=\"Test API\"} |= \"771a43a4\"")
                .contains("771a43a4"));
    }

    private String queryLoki(String logqlQuery) {
        try {
            String encoded = URLEncoder.encode(logqlQuery, StandardCharsets.UTF_8);
            long now = System.currentTimeMillis();
            long start = (now - 120_000) * 1_000_000L; // 2 minutes ago in nanoseconds
            long end = (now + 5_000) * 1_000_000L;
            String url = lokiBase + "/loki/api/v1/query_range"
                + "?query=" + encoded
                + "&start=" + start
                + "&end=" + end
                + "&limit=50";
            var response = http.send(
                HttpRequest.newBuilder().uri(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString()
            );
            return response.body();
        } catch (Exception e) {
            log.warn("Loki query failed: {}", e.getMessage());
            return "";
        }
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) return findField(clazz.getSuperclass(), name);
            throw e;
        }
    }
}
```

- [ ] **Step 3: Run unit tests to confirm they still all pass (ITs are excluded by default)**

```bash
mvn test -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Run integration tests**

```bash
mvn verify -Pintegration-test -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS` — all 5 IT scenarios pass. If Loki query for trace ID returns empty, check that the OTel Collector config maps `traceId` to a Loki label or that the log line JSON contains it. Adjust the `queryLoki` filter string accordingly.

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/otel-collector-config.yaml \
        src/test/java/io/gravitee/reporter/otellogs/integration/
git commit -m "test: integration tests with TestContainers OTel Collector + Loki"
```

---

## Task 11: CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Create the CI workflow**

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      - name: Unit tests
        run: mvn verify -q

      - name: Integration tests
        run: mvn verify -Pintegration-test -q

      - name: Build plugin ZIP
        run: mvn package -DskipTests -q

      - uses: actions/upload-artifact@v4
        with:
          name: plugin-zip
          path: target/gravitee-reporter-otel-logs-*.zip
          retention-days: 7
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: GitHub Actions workflow — unit tests, integration tests, plugin ZIP artifact"
```

---

## Self-Review Checklist

**Spec coverage:**
- [x] OTel SDK pipeline (SdkLoggerProvider → BatchLogRecordProcessor → OtlpGrpcExporter) — Task 4, 9
- [x] All 4 reportable types (Metrics, Log, EndpointStatus, MessageMetrics) — Tasks 5–8
- [x] `correlationHeader` → trace ID extraction with traceparent / sentry-trace fallback — Task 5
- [x] `sentry-trace` header → `sentry.trace_id` / `sentry.span_id` attributes — Tasks 4, 5
- [x] No payload values logged — LogToLogRecordMapper uses content_length only — Task 6
- [x] `insecure` flag for local testing — Task 9 (Spring config, noted http:// scheme behaviour)
- [x] TestContainers integration test with OTel Collector + Loki — Task 10
- [x] Integration test cases: trace_id, severity=ERROR, endpoint DOWN, sentry-trace — Task 10
- [x] `reportLogs=false` default + canHandle gate — Task 9
- [x] Maven plugin ZIP assembly — Task 1
- [x] `plugin.properties` + `gravitee.json` — Task 1
- [x] CI workflow — Task 11

All self-review issues resolved inline.
