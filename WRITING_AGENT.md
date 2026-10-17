# RikkaHub Writing Agent fork

## Confirmed direction

Base: official stable release 2.5.6, published 2026-10-01.
Keep RikkaHub's existing UI, themes, chat behavior and settings. New writing features must use the same Compose components and Material theme. Do not port the previous standalone HTML design.

Writing scope: multiple originals and projects; collaborative planning; source-grounded detailed extraction; character progressions and knowledge limits; outline/scene/manuscript tools; review; resumable jobs; story-state updates; revisions and export.

## Source provenance

Official repository: https://github.com/rikkahub/rikkahub
Release: https://github.com/rikkahub/rikkahub/releases/tag/2.5.6
License: AGPL-3.0, see LICENSE. Preserve upstream notices and provide corresponding source when distributing under the license.

This checkout was initialized from the official release source archive after Git transport timed out. The local tag upstream/2.5.6 marks an archive snapshot, NOT the upstream Git commit or original history. Remote upstream is configured but its history has not yet been fetched. Before enabling normal upstream merges, fetch official history, identify the release commit and attach the writing commits to it; do not pretend the local snapshot is the official history.

## Integration points verified in source

- app/data/ai/tools/ChatToolFactory.kt: model tool assembly.
- app/data/ai/tools/WorkspaceTools.kt: current workspace tools and approval conventions.
- app/data/ai/transformers/WorkspaceReminderTransformer.kt: workspace context injection.
- document module: existing document parsers.
- workspace module: per-workspace files and execution environment.
- existing Compose UI/theme/navigation: reuse rather than duplicate.

Paths above are abbreviated; app Kotlin sources are under app/src/main/java/me/rerere/rikkahub/.

## Implementation order

1. Reproduce upstream build, establish separate application identity/signing so stock RikkaHub can coexist. Keep namespace unchanged unless required; avoid broad renames.
2. Add isolated project/source/chapter/evidence/state/revision storage and tested text indexing; source IDs cannot rely on chapter numbers being unique.
3. Expose scoped writing tools in the existing chat agent loop; bind conversation to project and surface selected evidence/context.
4. Add native project/source/task/editor pages using upstream widgets and navigation; keep ordinary chat usable.
5. Add resumable extraction, entity merging and semantic review; draft changes remain separate from approved story state.
6. End-to-end source import → discussion → outline → draft → review → revision/export; validate on more than one original.

## Upstream maintenance

For each new stable release: detect tag → fetch → inspect changes → integrate in a staging branch → resolve conflicts → build/test/lint → test data migrations and writing/chat regressions → produce signed fork APK plus source/change log. Do not publish or overwrite a working release merely because an upstream tag exists.

Use a stable fork signing key across updates. Back it up privately, never commit it. Stock official APKs will not normally update a fork signed with our own key. Fork updater must target fork releases, not silently offer the official APK.

Automated periodic checks require a persistent scheduler or hosted CI. No scheduler, hosted fork, CI account access or automatic release process has been configured yet; updates are not currently monitored in the background.

## Current build limits

Downloaded source and initialized local Git branch writing-agent. No writing feature code or APK delivered yet.
Environment: aarch64 Ubuntu/proot, JDK 17. Source declares Gradle 9.6.0, AGP 9.4.0, Kotlin 2.4.20, SDK 37.2. SDK/toolchain and ARM host compatibility still require verification. If local Android build binaries do not support this host, use a suitable x86_64 CI builder; don't downgrade upstream just to bypass verification.
