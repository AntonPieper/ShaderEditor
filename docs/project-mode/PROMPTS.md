# Prompts for GPT-6.1 Sol

Select **GPT-6.1 Sol** in the Codex client, with **High reasoning** when available. Run S1–S9 in order. The prompts do not select the model themselves. Keep the documentation in the same repository as the implementation. Each new session needs the current checkout and these docs, not the original conversation.

## Reusable implementation prompt

Replace `S1` with the session ID to run:

```text
Implement session S1 from docs/project-mode/IMPLEMENTATION.md.

Read applicable AGENTS.md, docs/project-mode/README.md and HANDOFF.md, then
the specification/UX/architecture sections and acceptance cases listed for
this session. Inspect the current checkout; preserve unrelated changes.

Treat the docs as the implementation contract. Implement and integrate the
whole session, including focused behavioral tests and required checks.
Reuse existing architecture; do not add alternative syntax, speculative
frameworks, storage-mode UI, whole-folder imports, or work belonging to later
sessions. Use the canonical #pragma se input/target grammar. Resolve routine choices
autonomously. If a check is unavailable, record the exact blocker and
complete all unaffected work; do not claim it passed.

Update HANDOFF.md with actual changes, verification, remaining risks and the
next step. Stop at this session's exit boundary. Do not push or publish.
Return a concise implementation and validation summary.
```

## Session sequence

| Run | Replace the first line with |
| --- | --- |
| 1 | `Implement session S1 from docs/project-mode/IMPLEMENTATION.md.` |
| 2 | `Implement session S2 from docs/project-mode/IMPLEMENTATION.md.` |
| 3 | `Implement session S3 from docs/project-mode/IMPLEMENTATION.md.` |
| 4 | `Implement session S4 from docs/project-mode/IMPLEMENTATION.md.` |
| 5 | `Implement session S5 from docs/project-mode/IMPLEMENTATION.md.` |
| 6 | `Implement session S6 from docs/project-mode/IMPLEMENTATION.md.` |
| 7 | `Implement session S7 from docs/project-mode/IMPLEMENTATION.md.` |
| 8 | `Implement session S8 from docs/project-mode/IMPLEMENTATION.md.` |
| 9 | `Implement session S9 from docs/project-mode/IMPLEMENTATION.md.` |

Do not advance while the preceding session is incomplete. A blocked hardware check can be recorded and carried forward if its code dependency is complete, but it remains part of the S9 release gate. An unimplemented prerequisite is not the same as unavailable validation.

## Continue an interrupted session

```text
Continue the incomplete session recorded in docs/project-mode/HANDOFF.md.
Read applicable AGENTS.md and the relevant contract/plan sections. Inspect
the actual diff before editing. Preserve completed work and unrelated changes;
resume at the recorded next step rather than restarting the design. Finish
the session's integration and checks, update HANDOFF.md with evidence, and
stop at its exit boundary. Do not push or publish.
```

## Optional focused review after a risky session

Use after S4 or S7 when an independent review is useful. This is a separate session, not permission to spawn agents.

```text
Review the completed project-mode session recorded in HANDOFF.md against
SPEC.md, UX.md and its acceptance cases. Inspect the implementation and
relevant callers. Find concrete correctness gaps, regressions and missing
verification; prioritize user-visible behavior, frame/resource lifetime and
persistence. Avoid stylistic rewrites and speculative future features.
Do not modify production code. Record actionable findings and evidence in
HANDOFF.md, clearly separated from completed work. Keep unavailable tests
marked as unavailable. Return the prioritized findings concisely.
```

## Fix findings before advancing

```text
Fix the concrete project-mode review findings recorded in HANDOFF.md.
Read the relevant contract and code, preserve unrelated changes, and make
the smallest coherent fixes. Run checks appropriate to the changes, update
the findings and acceptance evidence, and stop when those findings are
resolved or an exact external blocker remains. Do not broaden the feature,
redesign the format, push, or publish.
```

## Efficient session boundaries

- Start with the handoff and targeted code, not another full repository audit.
- Keep the specification stable across sessions. A local implementation inconvenience is not a reason for a new authoring mode.
- Let each session complete its slice; do not ask it to implement all remaining phases at once.
- One successful focused test/build pass is enough unless subsequent edits or failures justify rerunning it.
- Record evidence and exact unfinished work before context runs out.
- If working on separate branches, include the preceding session's code and updated HANDOFF before starting the next; do not create nine independent branches from the original base.
