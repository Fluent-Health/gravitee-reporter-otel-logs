# Gravitee Reporter OTel Logs

A Gravitee APIM reporter plugin that emits structured per-request log records via the OpenTelemetry Logs SDK. Two export modes are supported:

- **`otlp`** (default) — sends records via OTLP gRPC to an OpenTelemetry Collector, which forwards them to Google Cloud Logging. Grafana Alloy can then bridge them to Grafana Cloud Loki for querying.
- **`gcloud`** — writes records directly to Cloud Logging via the REST API v2, without an OTel Collector. Authentication uses Application Default Credentials (ADC) or an optional service-account key file.

W3C trace context fields (`trace_id`, `span_id`) are first-class citizens in every record, enabling cross-service correlation with Cloud Trace and Sentry.

A project by [Fluent Health](https://github.com/Fluent-Health).

## Quick Links

- [GitHub Releases](https://github.com/Fluent-Health/gravitee-reporter-otel-logs/releases)

## Development

Prerequisites: [`asdf`](https://asdf-vm.com) and Docker.

```bash
asdf plugin add java
asdf plugin add maven
asdf install
mvn verify
```

The `.tool-versions` file pins Java 21 (Temurin) and Maven 3.9. `mvn verify` compiles, runs the unit test suite, and produces the plugin ZIP at `target/gravitee-reporter-otel-logs-*.zip`.

To load the plugin into a local Gravitee gateway, copy the ZIP to the gateway's `plugins-ext/` directory and configure it in `gravitee.yml`. See [Configuration](#configuration) below for the full property reference and examples.

## Configuration

Add a `reporters.otellogs` block to `gravitee.yml`. Configuration is grouped into three subtrees — `logs`, `traces`, `resource` — plus a few top-level keys. The two signals are independently enabled.

### Property reference

#### Top-level

| Property | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `true` | Master switch. Set `false` to disable both signals. |
| `correlationHeader` | string | `X-Request-ID` | Request header used as the trace ID when no W3C `traceparent` is present. Value is SHA-256-hashed and truncated to 16 bytes to form a valid trace ID. |

#### `logs.*` — log export

| Property | Type | Default | Description |
|---|---|---|---|
| `logs.enabled` | boolean | `true` | Enable log record export. |
| `logs.exporter` | string | `gcloud` | `gcloud` (direct to Cloud Logging REST), `otlp` (OTLP/HTTP to a collector), or `none`. |
| `logs.logName` | string | `gravitee-api-gateway` | Cloud Logging log name. Used only when `logs.exporter: gcloud`. |
| `logs.endpoint` | string | `http://localhost:4317` | OTLP/HTTP base URL. `/v1/logs` is appended automatically. Used only when `logs.exporter: otlp`. |
| `logs.authMode` | string | `none` | OTLP auth mode: `none`, `gcp-adc` (refreshing ADC bearer per export), or `static` (use `logs.headers`). |
| `logs.batchSize` | integer | `512` | Maximum records per export batch. |
| `logs.scheduledDelayMs` | integer | `5000` | Maximum delay between batch exports (ms). |
| `logs.reportHealthChecks` | boolean | `true` | Emit records for `EndpointStatus` events. |
| `logs.reportRequestLogs` | boolean | `false` | Emit records for `Log` (request/response metadata) events. |
| `logs.reportMessageMetrics` | boolean | `true` | Emit records for `MessageMetrics` (async/event-driven) events. |
| `logs.reportRequestSummary` | boolean | `true` | Emit a per-request "summary" record from `Metrics` events. Set `false` (with `reportRequestLogs: true`) to suppress it and rely solely on the detailed Log-derived record — one log per request instead of two. |
| `logs.reportHeaders` | boolean | `false` | **Dev/stage only.** When combined with `reportRequestLogs: true`, attaches request/response headers as JSON-encoded `http.request.headers` / `http.response.headers` attributes. **Keep `false` in production environments handling PII/PHI.** |
| `logs.reportPayloads` | boolean | `false` | **Dev/stage only.** When combined with `reportRequestLogs: true`, attaches request/response bodies as `http.request.body` / `http.response.body` attributes. Bodies must already be filtered upstream by the API logging config. **Keep `false` in production environments handling PII/PHI.** |

#### `traces.*` — span export

| Property | Type | Default | Description |
|---|---|---|---|
| `traces.enabled` | boolean | `false` | Enable span export. Opt-in. |
| `traces.exporter` | string | `otlp` | Only `otlp` is supported. |
| `traces.endpoint` | string | `https://telemetry.googleapis.com/v1/traces` | OTLP/HTTP endpoint. Default targets Cloud Trace's native OTLP ingestion. |
| `traces.authMode` | string | `gcp-adc` | OTLP auth mode: `none`, `gcp-adc`, or `static`. |
| `traces.batchSize` | integer | `512` | Maximum spans per export batch. |
| `traces.scheduledDelayMs` | integer | `5000` | Maximum delay between batch exports (ms). |
| `traces.sampler` | string | `parent-based-always-on` | `parent-based-always-on` respects inbound W3C `traceparent`; `always-on` samples every request; `ratio` samples at `traces.sampleRatio`. |
| `traces.sampleRatio` | number | `1.0` | Sample fraction (0.0–1.0) when `traces.sampler: ratio`. |

#### `resource.*` — shared OTel Resource attributes

| Property | Type | Default | Description |
|---|---|---|---|
| `resource.serviceName` | string | `gravitee-api-gateway` | `service.name` attribute on every record and span. |
| `resource.serviceNamespace` | string | `apim` | `service.namespace` attribute. |
| `resource.gcp.projectId` | string | — | GCP project ID. **Required** when `logs.exporter: gcloud` or `traces.enabled: true`. |
| `resource.gcp.autoDetect` | boolean | `true` | Probe GKE/GCE metadata server to populate `k8s.*` / `cloud.region` attributes. |

### Examples

#### Logs to Cloud Logging only (the common case)

Writes directly to Cloud Logging via REST. No OTel Collector needed. Auth is Application Default Credentials — on GKE this is Workload Identity.

```yaml
reporters:
  otellogs:
    enabled: true
    correlationHeader: "X-Request-ID"
    logs:
      enabled: true
      exporter: gcloud
      logName: "gravitee-api-gateway"
    resource:
      gcp:
        projectId: "your-gcp-project-id"
```

#### Logs via OTel Collector (OTLP/HTTP)

Routes records through an OpenTelemetry Collector. Use this for multi-destination fanout or to centralise credential management.

```yaml
reporters:
  otellogs:
    enabled: true
    logs:
      enabled: true
      exporter: otlp
      endpoint: "http://otel-collector.YOUR_NAMESPACE.svc.cluster.local:4318"
      authMode: none
```

See [OTel Collector configuration](#2-otel-collector-configuration) for a ready-to-use collector config.

#### Traces to Cloud Trace

Exports one server-kind span per request to Cloud Trace via its native OTLP/HTTP endpoint. Authenticates with ADC. Requires the **Telemetry API** to be enabled on the project (`gcloud services enable telemetry.googleapis.com --project=YOUR_PROJECT_ID`) and the ADC quota project to be set (`gcloud auth application-default set-quota-project YOUR_PROJECT_ID` when using user credentials locally).

```yaml
reporters:
  otellogs:
    enabled: true
    traces:
      enabled: true
      endpoint: "https://telemetry.googleapis.com/v1/traces"
      authMode: gcp-adc
    resource:
      gcp:
        projectId: "your-gcp-project-id"
```

#### Combined logs + traces (recommended)

Logs land in Cloud Logging; spans land in Cloud Trace; both share the same trace ID so the Cloud Trace UI shows linked log entries automatically.

```yaml
reporters:
  otellogs:
    enabled: true
    correlationHeader: "X-Request-ID"
    logs:
      enabled: true
      exporter: gcloud
      logName: "gravitee-api-gateway"
    traces:
      enabled: true
      endpoint: "https://telemetry.googleapis.com/v1/traces"
      authMode: gcp-adc
    resource:
      serviceName: "gravitee-api-gateway"
      serviceNamespace: "apim"
      gcp:
        projectId: "your-gcp-project-id"
```

#### Dev/stage with full request logging — one combined log per request

Enables full request log events (which carry method, URI, headers, status, content lengths) plus request/response headers and bodies, and suppresses the redundant Metrics-derived summary so there's exactly one log per request. Use only in non-production environments handling no PII/PHI. Headers and bodies still pass through the upstream API logging filter first.

```yaml
reporters:
  otellogs:
    logs:
      enabled: true
      exporter: gcloud
      reportRequestLogs: true       # use the detailed Log-derived record
      reportRequestSummary: false   # suppress the Metrics-derived summary so we get ONE log per request
      reportHeaders: true           # include headers as JSON attributes
      reportPayloads: true          # include bodies as attributes — do NOT enable in production
    resource:
      gcp:
        projectId: "your-gcp-project-id"
```

Behaviour matrix:

| `reportRequestLogs` | `reportRequestSummary` | Records per request |
|---|---|---|
| `false` (default) | `true` (default) | **1** — summary only (default) |
| `true` | `true` (default) | **2** — summary + detailed (legacy "logs on") |
| `true` | `false` | **1** — detailed only (recommended for dev/stage with full logging) |
| `false` | `false` | **0** — degenerate; emits a startup WARN |

### Backward compatibility

The legacy flat keys (`reporters.otellogs.exporter`, `reporters.otellogs.endpoint`, `reporters.otellogs.reportLogs`, `reporters.otellogs.gcloud.projectId`, etc.) are still read for one release with a startup `WARN` log naming each legacy key in use and its replacement. The `gcloud.credentialsFile` option is removed entirely — ADC is the only supported GCP auth path (Workload Identity on GKE, `gcloud auth application-default login` locally, `GOOGLE_APPLICATION_CREDENTIALS` in CI).

See [GCP Configuration](#gcp-configuration) for IAM setup, querying, and the full OTel Collector config for OTLP mode.

## Testing

**Unit tests** (no Docker required):

```bash
mvn verify
```

**Integration tests** (Docker required — pulls OTel Collector and Loki images, ~1–2 minutes):

```bash
mvn verify --activate-profiles integration-test
```

Starts an OTel Collector and Loki in containers, emits sample log records for each reportable type, and asserts via the Loki HTTP API that records arrived with the correct attributes and trace IDs.

**GCP live integration test** (requires Application Default Credentials and Cloud Logging API enabled):

```bash
export GOOGLE_CLOUD_PROJECT=your-project-id
mvn verify --activate-profiles gcloud-integration-test
```

Writes a uniquely-identified log record to Cloud Logging via the REST API and polls for up to 90 seconds until it appears, verifying end-to-end credential validity, write, and readback.

## Deployment

Releases follow **semver tagging**. To publish a new release:

1. Create a GitHub Release with a semver tag (e.g. `v1.0.0`).
2. The `release.yml` workflow sets the Maven version, builds the plugin ZIP, and attaches it to the release automatically.
3. Copy the released ZIP into the Gravitee gateway `plugins-ext/` directory and restart the gateway pod. The plugin is picked up on startup.

## Additional Info

### GCP Configuration

#### Background

Google's native OTLP ingestion as of mid-2026 is **traces-only**. `telemetry.googleapis.com/v1/traces` accepts OTLP/HTTP spans directly — this plugin uses it for the traces signal with no collector required. For logs, `telemetry.googleapis.com` returns `UNIMPLEMENTED`; `logging.googleapis.com` speaks the proprietary `LoggingServiceV2` API, not OTLP. The plugin therefore supports two logs paths: `logs.exporter: gcloud` calls the Cloud Logging REST API directly (no collector), and `logs.exporter: otlp` sends OTLP/HTTP to a collector that translates to Cloud Logging.

When Google adds native OTLP log ingestion to `telemetry.googleapis.com`, switching to collector-free OTLP logs will be a one-line config change (`logs.endpoint: https://telemetry.googleapis.com` + `logs.authMode: gcp-adc`) — no code changes needed.

#### 1. IAM — grant write access

The service account running the pod that hosts the plugin (Gravitee gateway for `gcloud` mode; OTel Collector for `otlp` mode) must have `roles/logging.logWriter`. Using Workload Identity Federation on GKE:

```bash
# Create a GCP service account
gcloud iam service-accounts create gravitee-reporter \
  --project=YOUR_PROJECT_ID \
  --display-name="Gravitee Reporter"

# Grant Cloud Logging write permission
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="serviceAccount:gravitee-reporter@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/logging.logWriter"

# Bind to the Kubernetes service account (Workload Identity)
gcloud iam service-accounts add-iam-policy-binding \
  gravitee-reporter@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="serviceAccount:YOUR_PROJECT_ID.svc.id.goog[YOUR_NAMESPACE/gravitee-reporter]"
```

Then annotate the Kubernetes service account:

```bash
kubectl annotate serviceaccount gravitee-reporter \
  --namespace=YOUR_NAMESPACE \
  iam.gke.io/gcp-service-account=gravitee-reporter@YOUR_PROJECT_ID.iam.gserviceaccount.com
```

For the `otlp` mode, apply the same steps to the OTel Collector's service account instead.

#### 2. OTel Collector configuration

The plugin's OTLP exporter speaks OTLP/HTTP (not gRPC), so configure the collector's `otlp` receiver to listen on the HTTP port (`4318`). Use [`otelcol-contrib`](https://github.com/open-telemetry/opentelemetry-collector-contrib), which includes the `googlecloud` exporter:

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: "0.0.0.0:4318"

processors:
  resource:
    # Optional: the plugin already sets these via resource.* config.
    # Keep upserts only if you want collector-side overrides.
    attributes:
      - action: upsert
        key: gcp.project_id
        value: "YOUR_PROJECT_ID"

exporters:
  googlecloud:
    log:
      default_log_name: "gravitee-api-gateway"

service:
  pipelines:
    logs:
      receivers: [otlp]
      processors: [resource]
      exporters: [googlecloud]
```

In `gravitee.yml`, point the plugin at this collector with `logs.endpoint: http://otel-collector.YOUR_NAMESPACE.svc.cluster.local:4318` (and `logs.exporter: otlp`). The `googlecloud` exporter picks up Application Default Credentials automatically on GKE via the pod's Workload Identity annotation — no credentials file needed.

#### 3. Gravitee gateway configuration

See the [Configuration](#configuration) section above for full `gravitee.yml` examples. Minimal additions for GCP:

- **Logs to Cloud Logging direct**: set `logs.exporter: gcloud` and `resource.gcp.projectId: YOUR_PROJECT_ID`. No endpoint needed.
- **Logs via OTel Collector**: set `logs.exporter: otlp` and `logs.endpoint: http://otel-collector.YOUR_NAMESPACE.svc.cluster.local:4318`.
- **Traces to Cloud Trace**: set `traces.enabled: true`. Default endpoint and auth (`gcp-adc`) hit `telemetry.googleapis.com/v1/traces` correctly when ADC is configured.

#### 4. Querying logs in Cloud Logging

Logs land under the `gravitee-api-gateway` log name in your project. Open [Cloud Logging](https://console.cloud.google.com/logs) and use these filters:

```
# All gateway logs
logName="projects/YOUR_PROJECT_ID/logs/gravitee-api-gateway"

# Filter by API name
logName="projects/YOUR_PROJECT_ID/logs/gravitee-api-gateway"
jsonPayload.api_name="Appointments API"

# Errors only
logName="projects/YOUR_PROJECT_ID/logs/gravitee-api-gateway"
severity>=ERROR

# Correlate with a specific request by trace ID
trace="projects/YOUR_PROJECT_ID/traces/TRACE_ID_HERE"
```

#### 5. Querying logs in Grafana (via Grafana Alloy → Loki)

Once Grafana Alloy is configured to bridge Cloud Logging to Loki, query with LogQL:

```logql
# All gateway logs
{job="gravitee/gravitee-api-gateway"}

# Errors for a specific API
{job="gravitee/gravitee-api-gateway", severity="ERROR"} | json | api_name=`Appointments API`

# Latency outliers (over 1 second)
{job="gravitee/gravitee-api-gateway"} | json | http_latency_ms > 1000

# Correlate with a Sentry issue by trace ID
{job="gravitee/gravitee-api-gateway"} | json | sentry_trace_id=`TRACE_ID_HERE`
```

### Record and Span Schema

#### Logs signal

Every log record carries these OTel SDK fields:

| Field | Source |
|---|---|
| `traceId` | W3C `traceparent` (preferred); else SHA-256(`correlationHeader` value) truncated to 16 bytes |
| `spanId` | `traceparent` span component when present |
| `severity` | HTTP status: 5xx → ERROR, 4xx → WARN, else INFO |
| `timestamp` | Event timestamp (nanosecond precision) |
| `body` | `"{METHOD} {path} → {status}"` |

In Cloud Logging the `http.method`, `http.url`, `http.status`, and `http.latency_ms` attributes are promoted to the native `httpRequest` LogEntry field so the Logs Explorer renders method + URL + status + latency as chips on the summary line.

Attributes by event type:

**Metrics (HTTP request):** `api.name`, `api.type`, `http.method`, `http.url`, `http.status`, `http.latency_ms`, `upstream.endpoint`, `context.application`, `context.plan`, `context.subscription`, `gateway.proxy_latency_ms`, `gateway.api_latency_ms`, `error.message`. Path segments matching numeric IDs or UUIDs are replaced with `{id}` before logging.

**EndpointStatus (health checks):** `api.name`, `endpoint.name`, `endpoint.url`, `endpoint.status`, `endpoint.response_time_ms`

**MessageMetrics (async/event-driven APIs):** `api.name`, `message.count`, `message.error_count`, `message.content_length`

**Log (request/response metadata, opt-in via `logs.reportRequestLogs: true`):** `api.name`, `http.method`, `http.status`, `log.request.headers_count`, `log.response.headers_count`. When `logs.reportPayloads: true` is **also** set (dev/stage only), request and response bodies are attached as `http.request.body` and `http.response.body`.

#### Traces signal

When `traces.enabled: true`, one span per request is emitted:

| Field | Value |
|---|---|
| `name` | `gravitee.gateway.request` |
| `kind` | `SERVER` |
| `startTime` | `event.timestamp - gatewayResponseTimeMs` |
| `endTime` | `event.timestamp` |
| `traceId` | Same resolution as the log record's `traceId` — so logs and spans for the same request match |
| `parentSpanId` | From inbound W3C `traceparent` when present; otherwise the span is the trace root |
| `status` | `ERROR` with description `"HTTP {status}"` for 5xx responses; `UNSET` otherwise |

Attributes follow OTel HTTP semantic conventions (not the logs-side names — the two namespaces serve different rendering targets):

| Attribute | Source |
|---|---|
| `http.request.method` | OTel HTTP semconv |
| `http.response.status_code` | OTel HTTP semconv |
| `http.route` | Request URI (path) |
| `gravitee.api.name` | Gravitee-namespaced |
| `gravitee.upstream.endpoint` | Gravitee-namespaced |

#### Compliance posture

No payload values are ever logged in the default configuration. No PII or PHI in any attribute unless `logs.reportPayloads: true` is explicitly enabled — which is intended for dev/stage/test only. Production deployments handling regulated data must keep that flag `false` and rely on the upstream API logging configuration to omit bodies entirely.

### Sentry Integration

When a Sentry SDK is installed in the client application, it automatically attaches a `sentry-trace` header to outgoing HTTP requests. The plugin reads this header and records it alongside the standard OTel trace context, enabling you to navigate directly from a Sentry issue to the corresponding gateway log entry — and vice versa.

#### How it works

The `sentry-trace` header format is `{traceId}-{spanId}-{sampled}`, e.g.:

```
sentry-trace: 4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1
```

The plugin parses this and emits two additional attributes on every matching log record:

| Attribute | Value |
|---|---|
| `sentry.trace_id` | The 32-hex Sentry trace ID |
| `sentry.span_id` | The 16-hex Sentry span ID |

These are stored separately from the OTel `traceId`/`spanId` fields. The OTel fields come from the `X-Request-ID` or W3C `traceparent` headers and drive Cloud Logging's native trace linking (`trace="projects/.../traces/..."`). The Sentry fields are plain log attributes, queryable in both Cloud Logging and Loki.

The header is only parsed — its value is never used to override the OTel trace context. Both systems get their own identifiers on the same record.

#### Querying by Sentry trace ID

**Cloud Logging:**

```
logName="projects/YOUR_PROJECT_ID/logs/gravitee-api-gateway"
jsonPayload.sentry.trace_id="4bf92f3577b34da6a3ce929d0e0e4736"
```

**Loki (LogQL):**

```logql
{job="gravitee/gravitee-api-gateway"} | json | sentry_trace_id=`4bf92f3577b34da6a3ce929d0e0e4736`
```

Note: LogQL flattens nested JSON keys with underscores, so `sentry.trace_id` becomes `sentry_trace_id`.

#### Workflow

1. A Sentry error fires for a user request.
2. Copy the trace ID from the Sentry issue breadcrumb or the `sentry-trace` value in Sentry's network tab.
3. Paste it into the Cloud Logging or Loki query above.
4. The matching gateway log shows the full request context: API name, HTTP status, latency, upstream endpoint, and the OTel `traceId` for Cloud Trace correlation.

### Architecture

Logs and traces are independent pipelines. Both share one OTel `Resource` (so service/k8s/cloud attributes are consistent) and one `TraceContextResolver` (so logs and spans for the same request carry identical trace IDs).

**Logs — `logs.exporter: gcloud`** (the default):

```
Gravitee Gateway (JVM)
  └── OtelLogsReporter
        └── mapper/* → OtelLogWriter
              └── SdkLoggerProvider
                    └── BatchLogRecordProcessor
                          └── GclLogRecordExporter (Cloud Logging REST API v2, ADC bearer)
                                └── Google Cloud Logging
                                      └── Grafana Alloy → Grafana Cloud Loki
```

**Logs — `logs.exporter: otlp`**:

```
Gravitee Gateway (JVM)
  └── OtelLogsReporter
        └── mapper/* → OtelLogWriter
              └── SdkLoggerProvider
                    └── BatchLogRecordProcessor
                          └── CustomOtlpHttpLogRecordExporter (java.net.http, optional ADC headers)
                                └── OTel Collector (in-cluster, otelcol-contrib)
                                      └── googlecloud exporter
                                            └── Google Cloud Logging
```

**Traces — `traces.enabled: true`**:

```
Gravitee Gateway (JVM)
  └── OtelLogsReporter
        └── MetricsToSpanMapper → OtelTraceWriter
              └── SdkTracerProvider
                    └── BatchSpanProcessor
                          └── CustomOtlpHttpSpanExporter (java.net.http, refreshing ADC bearer)
                                └── telemetry.googleapis.com/v1/traces
                                      └── Google Cloud Trace
```

The OTel SDK owns batching and retry in every pipeline. The custom HTTP exporters exist to avoid OkHttp/SPI classloader issues inside the Gravitee plugin runtime — the wire format is standard OTLP/HTTP protobuf either way. A failing exporter logs a `WARN` (including a truncated response body for diagnostics) and drops the batch; the other signal is unaffected.

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md). Report security issues via [GitHub private vulnerability reporting](https://github.com/Fluent-Health/gravitee-reporter-otel-logs/security/advisories/new).

## License

Apache 2.0 — see [LICENSE](./LICENSE).
