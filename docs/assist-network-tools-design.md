# Assist Network Tools

Status: consensus reached via grilling. Scope: engine (scoop-ai) plus a host dependency bump.

## Problem

In a Chat with AI session the model can rewrite the manifest, but it has no way to read the
network. Asked to check a GitHub description, it answers that it cannot fetch the page and
edits the manifest from memory instead.

## Decision

| Question | Decision |
|---|---|
| Tool scope | Read-only network tools only: GitHub repo info, latest release, all releases, release assets, file content, URL parsing, plus web page fetch, JSON fetch and redirect resolve |
| Limits | Reuse the existing ToolCallBudget counters (fetchWebPage 10, resolveRedirect 6, per run). No extra confirmation prompt |
| Prompt | Tell the model it can verify facts and require it to cite the source of verified claims |
| Gate | Always on. No settings toggle |

Excluded on purpose: download and hash tools (computeSha256FromUrl), archive inspection and
manifest file writes. They are heavy, need a download confirmation channel, and a wrong hash is
already caught by the existing hash auto fix and verify steps.

## Change points

- `core/.../ScoopManifestGenerator.kt`: include the read-only network tool descriptors in the
  assist whitelist and turn on the prompt section that documents them.
- `core/.../agent/prompt/PhasePrompts.kt`: assist kernel gains a verification section that names
  the tools and asks for source citations.
- Host: no new handlers, the tools live in the engine registry. Only the engine version bump
  reaches the desktop app.

## Verification

Engine tests assert the assist whitelist contains the network tools and the prompt mentions
verification. Host smoke test is a Chat with AI request that asks the model to check a
repository description.
