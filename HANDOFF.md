# Feedline M1 — Handoff

## Toolchain (STEP 0) — all MISSING
- java: command not found
- mvn: command not found
- docker: command not found
- docker compose: command not found
- Host: Ubuntu 26.04.1 LTS (resolute), apt available, network OK (Maven Central reachable)
- Conclusion: no JDK 21, no Maven, no Docker -> do NOT claim tests passed; write scaffold only, report exact apt install commands, mark IT as NOT RUN.

## Feed probe (report feeding)
- https://www.cbc.ca/webfeed/rss/rss-topstories -> HTTP 200, valid RSS 2.0, 20 entries, all GUIDs (CONFIGURED feed)
- https://feeds.bbci.co.uk/news/world/rss.xml -> HTTP 200, valid RSS 2.0, 29 entries (fallback, unused)
- Fixture files already downloaded to ~/feedline/src/test/resources/fixtures/{cbc-topstories.xml,bbc-world-rss.xml}
- Next: create V1 migration, entities, repos, normalizer (guid fallback->link), @Scheduled poller, FeedService (UA + 10s timeout), config (one feed), tests (normalizer unit + Testcontainers IT), .github/workflows/ci.yml, README, DECISIONS.md, pom (SpotBugs, failsafe), REPORT-M1.md.

## Confirmed versions
- Spring Boot parent: 3.5.16 (Spring Boot 3.x stable line; 3.4.13 confirmed earlier)
- rome: 1.18.0 | postgresql: 4.4.1 | spotbugs-maven-plugin: 4.8.6.0 | spotbugs core: 4.8.6 | failsafe: BOM-managed
- Testcontainers: BOM-managed testcontainers + junit-jupiter + postgresql; @Container + @DynamicPropertySource(spring.datasource.url/username/password, spring.flyway.url) from postgres::getJdbcUrl() etc.

## Apt install commands for the operator (exact)
# 1) Java 21 + Maven (Ubuntu repos)
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk maven

# 2) Docker (Docker CE, includes docker compose plugin)
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.sources > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"   # log out and back in

# 3) Docker alternative (Ubuntu repo, simpler)
sudo apt-get install -y docker.io

# quick verify
java -version; mvn -version; docker --version; docker compose version

## Verification matrix for REPORT-M1.md
- Build/test not run anywhere (no JDK/Maven/Docker present)
- Unit tests: NOT RUN (JDK/Maven missing)
- IT (Testcontainers): NOT RUN (Docker missing) - test written, must never be deleted
- Command that will run everything: mvn verify
