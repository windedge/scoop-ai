# Create Multi-Tab Sessions + Discover "Modify with AI"

Status: consensus reached via grilling (2026-10-06). Feature spans scooper UI only.

## Goal

1. Create page supports multiple concurrent sessions as browser-style tabs (ephemeral).
2. Discover detail panel gains a "Modify with AI" button that opens a Create tab prefilled with `@bucket/app`, reusing the existing manifest-reference pipeline (read_manifest_reference tool, save lock).

## Decisions

| Question | Decision |
|---|---|
| Discover entry point | Detail panel button only (no row-level button) |
| Create handoff | Prefill `@bucket/app` in composer, user completes and sends (no auto-send) |
| Save semantics | In-place overwrite of the referenced manifest (existing reference save lock already defaults bucket/name) plus an "updated existing manifest" hint in the save result panel |
| Multi-tab timing | Implement together with the button in this effort |
| Tab lifecycle | In-page tabs, close discards session, not persisted |
| Tab limit | Unlimited, horizontal scroll when crowded |
| Tab bar form | Browser-style bar under the Create page header: per-tab close x, active highlight, horizontal scroll (mouse wheel y forwarded). No + button on the bar; "+ New Session" in the header is the single entry point |
| Manual new tab | Opens a blank session titled "New Chat" (same as first-run Create) |
| Discover open behavior | Reuse current tab if it is still blank/unused, otherwise open a new tab |
| Close guard | Confirm dialog when the tab has an unsaved manifest or a running generation/install |
| Global state | Settings, model selection, bucket list stay global and shared across tabs |

## Architecture direction

- CreateViewModel is currently a single Orbit container wired once. Multi-tab requires per-tab session state (chat messages, manifestJson, saveResult, running flags, epoch, composer text) while sharing repositories/bridge/settings.
- Preferred shape: a lightweight `CreateTabState`/holder per tab owned by an upper-level container (or a tabId-keyed map in a reworked CreateViewModel); each tab's VM instance gets the shared dependencies injected. UI routes tab index to the active VM instance.
- The Discover handoff reuses the existing `@bucket/app` pipeline end to end: prefill composer text only; userManifestRefs scanning activates the read_manifest_reference tool on send; save lock prefills bucket/name.
- Save-result hint: extend the existing reference save path to surface "Updated <bucket>/<name>" in SaveResultPanel when the output name matches a referenced manifest and was saved in place.

## Batches

1. Engine-side none (0.1.13 already suffices). Host: extract per-tab session state in CreateViewModel layer + tab bar UI skeleton (new/close/switch/confirm guard).
2. Discover detail panel button + navigation handoff (reuse blank tab or new tab, prefill `@bucket/app`, focus composer).
3. Save-in-place hint in SaveResultPanel + tests; full regression.

## Out of scope

- Tab persistence across restarts.
- Per-tab settings/model selection.
- Row-level Discover buttons.
