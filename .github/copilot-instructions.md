# SuzukiScope release process

Private project: no Jira, commit and push directly to `main`. No CI — build and publish locally.

For every Play upload:
1. Bump `versionCode` and `versionName` in `androidApp/build.gradle.kts`.
2. Replace `androidApp/src/main/play/release-notes/en-US/default.txt` with this release's user-facing changes (max 500 chars, short `- ` bullets).
3. `./gradlew :androidApp:publishReleaseBundle` (internal track), then commit and push.

To change notes on an already-uploaded build: `./gradlew :androidApp:promoteReleaseArtifact --from-track internal --promote-track internal --release-status completed`.
