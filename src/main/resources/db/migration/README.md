# Database migrations (Flyway)

Versions are not contiguous: V7 was skipped during the M3 build. An early
outbox draft carried V7; the outbox landed as V9. Flyway tolerates version
gaps, so the missing V7 is historical, not a missing file. No action needed.
