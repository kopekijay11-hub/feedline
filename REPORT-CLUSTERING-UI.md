# REPORT-CLUSTERING-UI

Branch: `story-clustering-ui` off `main` at `b7a1f2d` (Merge story-clustering). Base commit `b7a1f2d`.
HOLD: no merge without grading.

## Parts as built

0. ItemDto gains `clusterSize` (Integer, null when unclustered): member count of the item's cluster, derived from the same `story_cluster_members` rows the `clusterId` join uses. `ItemQueryService.list` batch-loads sizes via `StoryClusterMemberRepository.clusterSizesByIds`; `findById` uses `countByClusterId`. No other API change. Testcontainers API test `itemDtoClusterSizeMatchesMemberCount` added to `StoryClusterSeamTest`: after a pass over a fixture story, clustered items show `clusterId` set and `clusterSize` equal to the true member count (3); unclustered item shows both null.

1. TOP STORIES PANEL added immediately above the Items panel in `index.html`, titled "Top stories — same story, many sources" (panel `clusters`). `loadClusters()` fetches `GET /api/v1/clusters?sinceHours=48`, lists up to 10 (API returns sorted list; UI renders rows): representative title, member count ("N reports"), up to 4 source names plus "+N more", last-seen time. `toggleCluster(id)` expands in place to members via `GET /api/v1/clusters/{id}` (cached in `state.clusterMembersCache`), rendering source name + title; title is a link only when `safeHttpUrl` passes, else escaped text (`clusterMembersHtml`). Click again collapses (`collapseCluster`). Panel follows `setPanelState` conventions (Loading/Up-to-date/Empty/Error/Stale) and refreshes with the dashboard cycle (`loadClusters(true)` in `setAutoRefresh`) and manual refresh (`loadClusters(false)` in `refresh-all` click). New state entry `state.panel.clusters`.

2. ITEM BADGE: items with `clusterId != null` render a button-styled badge "Same story · N sources" (N = `clusterSize`) in a new column; unclustered items show "—". `toggleItemCluster(clusterId, clusterSize, btn)` expands the member list directly under that item (same `clusterMembersHtml` rendering; fetched from `GET /api/v1/clusters/{clusterId}`, cached in memory per cluster id for the session). Click again collapses. Existing rendering, filters, chips, picker, paging unchanged (only an added column).

3. ESCAPING: all feed-supplied strings pass through `esc()`; titles/links only through `safeHttpUrl`. No `innerHTML` with unescaped feed text in new code (`clusterMembersHtml` escapes source names and titles; member links use `esc(safeUrl)`).

## clusterSize test

`StoryClusterSeamTest.itemDtoClusterSizeMatchesMemberCount`: three outlets one story (member count 3) plus one unclustered item. After `scheduledPass()`, clustered items report `clusterId` = cluster id and `clusterSize` = 3; unclustered item reports `clusterId` null and `clusterSize` null.

## 8081 evidence

Branch build booted on port 8081 against recreated scratch DB `feedline_cluster_probe` (live 8080 and live DB untouched). Live data copied via JDBC (41 sources, 4352 items). One clustering pass ran (pass-delay 2000ms).

`GET /api/v1/clusters?sinceHours=48` after one pass: 61 clusters. Top 10:
- 46 | 7 reports | Nvidia board reinstates Jensen Huang as CEO | Al Jazeera English, France 24 English, Los Angeles Times, RTÉ News +2 more | lastSeen 2026-11-23T18:26:29Z
- 12 | 5 reports | Ukraine says it was behind drone attack that sank a Russian warship in Libya | Anadolu Agency, France 24 English, RTÉ News, The Straits Times +1 more | lastSeen 2026-11-23T10:04:19Z
- 5 | 5 reports | Audience evacuated from Don Toliver concert after unknown substance released in the crowd | AP via Flipboard, Euronews, South China Morning Post, The Independent +1 more | lastSeen 2026-11-23T10:04:14Z
- 8 | 4 reports | Democrats clinch a historic House majority of at least 258 seats — the largest since 1992 | AP via Flipboard, CTV News, The Straits Times, Yahoo News Top Stories | lastSeen 2026-11-23T10:04:14Z
- 13 | 4 reports | Suspected Ukrainian sea drones hit Russian warship at Benghazi naval base, Libyan officials say | AP via Flipboard, CTV News, France 24 English, The Straits Times | lastSeen 2026-11-23T10:04:14Z
- 57 | 3 reports | South Korea education minister resigns over exam cheating scandal | BBC News World, Euronews | lastSeen 2026-11-23T18:26:25Z
- 7 | 3 reports | Thai court upholds royal defamation conviction for opposition lawmaker and cuts sentence to 3 years | AP via Flipboard, The Independent | lastSeen 2026-11-23T18:26:03Z
- 45 | 3 reports | What's next after no deal at COP31 climate talks? | Al Jazeera English, BBC News World | lastSeen 2026-11-23T18:25:56Z
- 15 | 3 reports | Man with Alzheimer's found alive after 11 days in Amazon | CTV News, RTÉ News, The Straits Times | lastSeen 2026-11-23T10:04:14Z
- 18 | 3 reports | US Army plans to invoke emergency provisions to detonate bomb-making chemicals in Iowa | AP via Flipboard, CTV News, The Straits Times | lastSeen 2026-11-23T10:04:14Z

Item badge evidence: `GET /api/v1/items?size=100&fetchedTo=2026-11-23T12:00:00Z` returns clustered rows e.g. id 1849 clusterId=52 clusterSize=2; id 1803 clusterId=44 clusterSize=2. Cluster detail expansion: `GET /api/v1/clusters/46` returns 15 members (sourceName + title + link).

ops.js functions driving the panel: `loadClusters`, `toggleCluster`, `collapseCluster`, `clusterMembersHtml`. Driving the badge: `toggleItemCluster`, `clusterMembersHtml`.

## Verify of record

`mvn clean verify`: BUILD SUCCESS.
- Surefire: `Tests run: 205, Failures: 0, Errors: 0, Skipped: 0`
- SpotBugs: `BugInstance size is 0`, `Error size is 0`, `No errors/warnings found`
- `git status` at branch tip: clean working tree.

## Push proof

Branch `story-clustering-ui` pushed to origin fast-forward only (no force, no rebase, no dropped commits). No merge performed (HOLD).

## Cleanup

Sanctioned scratch DB `feedline_cluster_probe` dropped. Port 8081 stopped. No other deletions. Stray `.attach_pid*` file removed (not a project file).
