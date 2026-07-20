---
description: Bump lunula, sync termtastic's pin, and refresh the lunamux and lunicle libs-repos to only the latest version. No commits.
---

Arguments: `$ARGUMENTS` — an explicit version (e.g. `0.2.16`), or empty to bump the patch number.

Do this, then stop. **Never commit or push** (not in termtastic, not in lunula).

1. Read the current version from `../../lunula/main/build.gradle.kts` (the `allprojects { version = "..." }` line). The new version is `$ARGUMENTS` if given, otherwise the current version with its patch bumped by 1.
2. Set that new version in `../../lunula/main/build.gradle.kts`.
3. Set `lunula = "<new version>"` in both consumers' version catalogs: `gradle/libs.versions.toml` (lunamux's own) **and** `../../lunicle/main/gradle/libs.versions.toml` (lunicle's).
4. From `../../lunula/main`, run `./gradlew publishAllToLibsRepo`.
5. Clean both consumer libs-repos so each holds only the new version. In `libs-repo/se/soderbjorn/lunula/` (lunamux's own) **and** in `../../lunicle/main/libs-repo/se/soderbjorn/lunula/` (lunicle's), for every `toolkit-*` module, delete every version subdirectory except the new version's.
6. Report the old → new version and confirm both libs-repos (lunamux and lunicle) now hold only the latest.
