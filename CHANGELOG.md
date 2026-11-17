# Changelog

All notable changes to Feedline are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/), and versions correspond to project milestones.

## [0.1.0] — 2026-11-17

Milestone 1 — the ingestion core.

- Scheduled RSS/Atom poller (fixed delay) with primary/fallback feed handling
- Rome-based feed parsing and normalization into a JPA `Item` entity
- Idempotent storage: a unique `(source_id, guid)` constraint makes re-polls insert-if-absent
- PostgreSQL 16 schema managed by Flyway from the first migration
- Unit tests against a captured real feed, plus Testcontainers integration tests against real PostgreSQL
- SpotBugs static analysis wired into the Maven build
- Docker Compose quick start (PostgreSQL 16)
