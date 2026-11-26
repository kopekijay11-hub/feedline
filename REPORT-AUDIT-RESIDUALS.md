Feedline — M3 Audit Residuals
=============================

base: 44eba71ad6c9cfd66a29289a65eed892b372c193 == ls-remote 44eba71ad6c9cfd66a29289a65eed892b372c193

Branch: audit-residuals (cut from origin/main tip 44eba71a, fast-forward only; no force, no rebase, no dropped commits; no cleanup deletions).

This report closes the residuals of the 2026-11-22 M3 audit: B-03, B-06,
B-08 (P2, UNRESOLVED), §18 (P1, ACCEPTED/OPEN docker-compose host ports),
and B-07 (P2, ACCEPTED/OPEN unevidenced documentation homework). The
disposition table is committed to the repo at
docs/audit-disposition-2026-11-22.md. HOLD — no merge without grading.

The report names no hash of a commit containing it or descending from it.

------------------------------------------------------------
PART 1 — B-03: UrlSafety error messages (sanitized)
------------------------------------------------------------

Read every error/exception message produced by the SSRF guard
(UrlSafety and its callers' rejection paths). Callers
(FeedFetcher.fetch at FeedFetcher.java:121-124 and
FeedFetcher.fetchWithRetry at FeedFetcher.java:278-280; FeedHttpClient
validateHop at FeedHttpClient.java:87-91) only log e.getMessage() and
return a failure — they append no topology. The topology leakage was in
two UrlSafety messages themselves, both embedding the resolved address
via address.getHostAddress():

- src/main/java/ca/feedline/service/UrlSafety.java:68 —
  "Blocked target address: " + address.getHostAddress()
  -> sanitized to: "Blocked target address"
- src/main/java/ca/feedline/service/UrlSafety.java:84-85 —
  "Blocked target address after DNS recheck: " + address.getHostAddress()
  -> sanitized to: "Blocked target address after DNS recheck"

All other messages name only the submitted URL/scheme/host (user input,
not resolved topology) and were left verbatim:

- "URL must not be null"
- "Unsupported scheme: " + scheme
- "URL has no host: " + uri
- "Localhost target blocked: " + host
- "IPv4-mapped IPv6 target blocked: " + host
- "DNS answer changed for " + host + " before connect"
- "DNS resolution failed for " + host
- "DNS returned no addresses for " + host

Test added: UrlSafetyTest.blockedAddressMessagesNameNoTopology — asserts
a blocked private target (http://10.0.0.1/) yields exactly
"Blocked target address" and that the message contains neither the
resolved IP ("10.0.0.1") nor any host detail.

Outcome: B-03 FIXED — messages sanitized to generic rejections naming no
topology; sanitizing shape asserted by test. Evidence: this report Part 1
and the UrlSafety diff.

------------------------------------------------------------
PART 2 — B-06: GUID scoping integration test (test-only)
------------------------------------------------------------

Dedup contract is enforced per (source_id, guid) by the unique
constraint uq_items_source_guid (Item.java:25) and the
findBySourceIdAndGuid guard in ItemIngestService.java:97. Verified
against current code — no defect, so no fix was in scope.

Test added: src/test/java/ca/feedline/GuidScopingIntegrationTest
(Testcontainers PostgreSQL 16; mirrors FeedPollerDedupeIntegrationTest).

Three assertions, all green:
(a) sameGuidAcrossTwoSources_bothStored — two different sources ingest
    items with the SAME guid; both items are stored (count 2), each
    source finds its own (source_id, guid) row. -> dedup scoped per
    (source_id, guid): PASS.
(b) sameSourceSameGuidReingest_noDuplicate — the same source re-ingests
    the same guid; first stored=true, second stored=false; count stays 1.
    -> PASS.
(c) changedGuidBetweenPolls_storedAsNewItem — an item whose guid changes
    between polls is stored as a new item (count 2). -> current behaviour
    asserted; documented contract, not a defect fixed here.

Outcome: B-06 FIXED — test added (GuidScopingIntegrationTest). Evidence:
this report Part 2; surefire verdict below.

------------------------------------------------------------
PART 3 — B-08 + B-07: README documentation
------------------------------------------------------------

Kafka producer settings read verbatim from
src/main/resources/application.yml (spring.kafka.producer):

- bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
- acks: all
- properties.enable.idempotence: true
- properties.max.in.flight.requests.per.connection: ABSENT from
  application.yml -> not set — Kafka default 5 (idempotence permits at most 5 while preserving per-partition order)
- properties.delivery.timeout.ms, request.timeout.ms: 2000, retries: 10

Added a short "Messaging" paragraph under the README messaging area
(stating the ordering posture AS CONFIGURED):

- idempotence: on (enable.idempotence: true)
- acks: all
- max.in.flight.requests.per.connection: not set — Kafka default 5 (idempotence permits at most 5 while preserving per-partition order)
- honest claim: ordering is per-partition only; events sharing a key
  (sourceId:guid) keep their order; no global ordering is claimed.

Also added one B-07 line to the README "What this is" section:

"Items without a feed-provided GUID receive a synthetic GUID: SHA-256
of link/title/published_at; collision resistance is assumed (audit
B-07, accepted/open)."

Every value matches the config as read; the absent setting is named
"default" (framework default).

Outcome: B-08 FIXED — README ordering paragraph (Part 3).
B-07 disposition unchanged (ACCEPTED/OPEN); evidence now names the
README line added in Part 3. Evidence: this report Part 3; README diff.

------------------------------------------------------------
PART 4 — §18: compose ports to loopback
------------------------------------------------------------

docker-compose.yml: bound the published ports to loopback, nothing else
changed:

- Postgres: "5432:5432" -> "127.0.0.1:5432:5432"
- Kafka: "9092:19092" -> "127.0.0.1:9092:19092"

The feedline service port (8080:8080) and all environment, healthchecks,
and advertised listeners (PLAINTEXT_HOST://localhost:9092) are
unchanged. Internal compose DNS (postgres:5432, kafka:9092) is
unchanged, so the containerised app reaches both as before. From the
host, a host-run app reaches Postgres at localhost:5432 and Kafka at
localhost:9092 — unchanged endpoints, now loopback-only instead of LAN
exposed. Quickstart instructions (free ports 8080, 5432, 9092;
curl http://localhost:8080) are unchanged.

Outcome: §18 FIXED — loopback binding (Part 4). Evidence: this report
Part 4; docker-compose diff.

------------------------------------------------------------
PART 5 — disposition table + report
------------------------------------------------------------

Created docs/audit-disposition-2026-11-22.md from the audit appendix,
all rows copied verbatim except the five updated rows:

- §18: FIXED — loopback binding, this job (Part 4).
- B-03: FIXED — topology-leaking messages sanitized; evidence pointer:
  REPORT-AUDIT-RESIDUALS.md (this report).
- B-06: FIXED — test added, GuidScopingIntegrationTest.
- B-08: FIXED — README ordering paragraph (Part 3).
- B-07: disposition unchanged (ACCEPTED/OPEN); evidence now names the
  README line added in Part 3.

------------------------------------------------------------
VERIFY OF RECORD
------------------------------------------------------------

mvn clean verify on the committed branch tip; git status --short empty
for tracked changes before and after (the only untracked entry is the
pre-existing empty FETCH_HEAD, present before this job and not created
by it; no cleanup deletions performed).

Surefire verdict (verbatim): "Tests run: 240, Failures: 0, Errors: 0,
Skipped: 0". Suite expectation met: 236 baseline + 1 UrlSafety test +
3 GuidScopingIntegrationTest tests = 240, all green. BUILD SUCCESS.

SpotBugs verdict (verbatim): "BugInstance size is 0 / Error size is 0 /
No errors/warnings found" (failOnError=true; 0 findings). BUILD SUCCESS.

------------------------------------------------------------
PUSH PROOF
------------------------------------------------------------

Pushed to origin/audit-residuals via fast-forward (no force, no rebase,
no dropped commits); branch base 44eba71a. Push proof — local tip ==
origin/audit-residuals tip, full SHAs — is stated in the completion
message (the report names no hash of a commit containing it or
descending from it).
