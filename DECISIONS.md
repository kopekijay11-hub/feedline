# DECISIONS

## M1

**How does dedupe work?**
(source_id, guid). The insert is insert-if-absent, so polling the same feed a second time inserts zero new rows. The existing rows are left as they are.

**Why pin the database image to an exact tag?**
postgres:16 is a moving tag, so the same compose file can pull a different minor version next month. An exact tag like 16.16-alpine means the database you verified against is the one every future build runs, so a failure points to your change and not to a silent upgrade.

**Why Flyway instead of Hibernate auto-create?**
Flyway applies versioned SQL files in order, keeps them in git, and checksums them, so schema changes are reviewable and repeatable. Hibernate auto-create guesses the schema from the entity classes, leaves no change history, and can't be trusted with real data. Set Hibernate to validate only.

**The lesson of the three walls**
The lesson of the three walls: all three failures presented as something they weren't, and each was diagnosed the moment someone stopped trusting the label and asked what the error actually meant—so the discipline isn't "fix the error," it's "prove the error is what it claims to be before fixing it."
