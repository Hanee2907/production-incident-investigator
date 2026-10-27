# Production Incident Investigation Tool

A lightweight tool for IT support teams to investigate application failures. It ingests application logs, groups similar errors into signatures, opens and tracks incidents automatically, and shows when failures began and which services they hit.

**Tech:** Java 21 · Spring Boot 3.3 · Spring Data JPA · H2 (SQL) · regex/JSON log parsing · JUnit 5 + MockMvc

## Features

| Capability | How it works |
|---|---|
| **Log ingestion** | Upload a file or paste lines. Parses plain-text logs (`2026-10-27 08:15:02.123 ERROR [service] logger - message`, with stack traces) and JSON lines. Timestamps with offsets are normalised to UTC. |
| **Error grouping** | Each error message is normalised (UUIDs, numbers, IPs, emails, URLs, quoted values masked) and combined with the root-cause exception type into a SHA-256 fingerprint, so `order 100231 timed out` and `order 100377 timed out` form one group. |
| **Recurring failures** | A group seen in several distinct hours (configurable), or one that came back after being resolved, is flagged as recurring. |
| **Incident tracking** | An incident opens automatically when a group reaches the threshold (immediately for `FATAL`). Severity is derived from level, volume and number of affected services, and escalates as things get worse. If a resolved failure shows up again, the incident is reopened with its history kept. |
| **Timeline & impact** | Per-service first/last error, per-group hourly occurrence chart, and when each incident's failure began vs. when it was detected and resolved. |
| **Searchable history** | Full-text search over incident titles, descriptions, resolution notes and every history entry (status changes, notes, recurrences), plus filters by status, severity, service and date. |
| **Summary reports** | JSON summary and a downloadable Markdown report: totals, incidents by status/severity, mean time to resolve, affected services and top recurring failures. |

## Run it

Requires Java 21. Maven is downloaded automatically by the wrapper.

```bash
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

Open http://localhost:8080. Sample logs in `src/main/resources/sample-logs/` load on first start. The H2 console is at `/h2-console` (JDBC URL `jdbc:h2:file:./data/incidents`, user `sa`).

```bash
./mvnw test                   # 21 unit + integration tests
```

## REST API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/logs/ingest` | Upload a log file (multipart `file`) or post `text/plain` (`?source=name`) |
| `GET` | `/api/logs` | Search logs: `q`, `service`, `minLevel`, `groupId`, `from`, `to`, `page`, `size` |
| `GET` | `/api/logs/services` | Services seen in the logs |
| `GET` | `/api/error-groups` | Error groups by occurrence count: `service`, `recurringOnly` |
| `GET` | `/api/error-groups/{id}` | Group detail with hourly timeline and recent occurrences |
| `GET` | `/api/incidents` | Search incidents: `q`, `status`, `severity`, `service`, `from`, `to` |
| `POST` | `/api/incidents` | Open an incident manually |
| `GET` | `/api/incidents/{id}` | Incident with full history |
| `PATCH` | `/api/incidents/{id}` | Change status, severity, assignee, resolution notes (notes required to resolve) |
| `POST` | `/api/incidents/{id}/notes` | Add an investigation note |
| `GET` | `/api/reports/summary` | Summary report (JSON), optional `from`/`to` |
| `GET` | `/api/reports/summary.md` | Summary report (Markdown download) |
| `GET` | `/api/reports/services` | Per-service error impact |

Example:

```bash
curl -F file=@app.log http://localhost:8080/api/logs/ingest
```

## Configuration (`application.properties`)

| Property | Default | Meaning |
|---|---|---|
| `incident.auto-open-threshold` | `3` | Occurrences before an incident opens automatically |
| `incident.recurring-min-hours` | `2` | Distinct hours a group must appear in to count as recurring |
| `incident.sample-data.enabled` | `true` | Load bundled sample logs when the database is empty |

## Project layout

```
src/main/java/com/production/incidents/
  parsing/     LogParser, ErrorFingerprinter
  domain/      LogEntry, ErrorGroup, Incident, IncidentEvent (JPA entities)
  repository/  Spring Data repositories and SQL aggregate queries
  service/     Ingestion, incident lifecycle, search, reports, severity policy
  web/         REST controllers and DTOs
src/main/resources/static/   Dashboard (HTML/CSS/JS)
src/test/java/               Parser, fingerprint, service and API tests
```
