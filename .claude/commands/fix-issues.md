---
description: Autonomously fix a given list of GitHub issue numbers in the current working tree — one at a time, comment on each, no commits/pushes/PRs, no runtime testing.
---

Arguments: $ARGUMENTS

Fix the GitHub issues listed in `$ARGUMENTS` (repo `soderbjorn/termtastic`) **fully autonomously — never ask the user for input**. Make reasonable assumptions and keep going. When a genuine judgement call comes up, pick the most sensible option and record the assumption in the issue comment rather than stopping to ask.

This skill is deliberately different from `/pick-issue`: it does **not** branch, worktree, commit, push, or open a PR, and it does **not** test the changes at runtime. It edits the current working tree in place, leaves everything uncommitted for the owner to review, and reports what it did as a comment on each issue.

## 0. Ground rules (apply to every issue)

- **Work in the current directory.** Do not create worktrees or new branches. All edits land in the current checkout's working tree, left uncommitted.
- **Do not commit or push anything.** Not in this repo, and not in the darkness toolkit. Leave all changes staged in the working tree exactly as-is. The owner reviews and tests tomorrow.
- **You may edit the darkness toolkit** if an issue genuinely requires it — but the same no-commit / no-push rule applies there too.
- **Do not test at runtime.** Do not launch the Android emulator, do not run the Electron build, do not start the app to exercise the change. The owner will test everything manually tomorrow. A compile check / `./gradlew build` is acceptable if it's fast and helps you catch obvious breakage, but it is optional and never required — skip it rather than block on a slow or unavailable toolchain.
- **One issue at a time, strictly sequential.** Because every issue writes to the same shared working tree, parallel work would race and intermingle. Never run two issues concurrently.
- **Follow `CLAUDE.md`.** Its documentation standards (file-level block comment on new files; KDoc/JSDoc on new public classes, functions, and significant properties with purpose, caller context, `@param`, `@return`, `@see`; update existing doc blocks when behaviour changes; preserve inline comments) are hard requirements.

## 1. Parse the issue list

`$ARGUMENTS` contains the issues to fix — a list of numbers in any reasonable format (`58 61 74`, `#58, #61, #74`, `58,61,74`, etc.). Extract the numbers, preserve the given order, and de-duplicate. That order is the processing order.

If `$ARGUMENTS` contains no parseable issue numbers, stop and tell the user you need a list of issue numbers. Do not fall back to picking issues yourself — this skill only works the list it's given.

Fetch all of them up front so you have the full set before starting:

```
gh issue view <N> --repo soderbjorn/termtastic --json number,title,body,state,comments
```

If an issue is already closed, note it and skip it (don't re-implement closed work) unless the number was clearly given deliberately — when in doubt, process it and mention in the comment that it was already closed.

## 2. Process each issue (recommended: one subagent per issue)

To keep context clean between issues, process **each** issue inside its own `general-purpose` subagent (Agent tool), spawned **sequentially** — spawn the next only after the previous returns. This clears accumulated context between issues while keeping the shared working tree consistent. Working solo in the parent is also acceptable for a very short list, but the subagent-per-issue approach is preferred.

Give each subagent this brief (substitute the concrete issue number, and pass the issue title/body so it doesn't re-fetch unnecessarily):

```
You are fixing exactly one GitHub issue in the current working tree of repo `soderbjorn/termtastic`. Work fully autonomously — never ask for input; make reasonable assumptions and record them.

Issue #<N>: <title>
<body>

Rules:
- Edit the current working directory in place. Do NOT create a branch/worktree, and do NOT commit or push anything (not here, not in the darkness toolkit). Leave all changes uncommitted.
- You MAY edit the darkness toolkit if the fix needs it — still no commit/push.
- Do NOT test at runtime (no emulator, no Electron build, no launching the app). A quick optional compile/build check is fine but never required.
- Follow CLAUDE.md documentation standards for any code you add or change.
- Implement the whole issue. If you hit a TRUE blocker where no reasonable assumption lets you continue (genuinely contradictory requirements, or a decision only the owner can make and every option is unsafe), stop working THIS issue and report it as blocked — do not stall.

When done, post ONE comment on the issue describing what you did (or why you're blocked), using the exact format below, then return a one-line summary to me.

gh issue comment <N> --repo soderbjorn/termtastic --body "$(cat <<'EOF'
**Claude Code** (an AI coding agent) worked on this issue autonomously via the `/fix-issues` skill.

<2–5 sentences: what you changed and why. Name the user-facing behaviour and the key files touched. List any assumptions you made. If you edited the darkness toolkit, say so. If you were blocked, state exactly what blocked you and what you'd need to proceed.>

⚠️ Changes are **uncommitted** in the owner's working tree and have **not** been tested at runtime — the owner will review and test manually.

🤖 This comment was posted by [Claude Code](https://claude.com/claude-code) acting autonomously.
EOF
)"

Return: `#<N> — <done | blocked>: <one sentence>`.
```

## 3. Blockers — move on, don't stop the batch

A "true blocker" is when you cannot make a reasonable assumption to continue: internally contradictory requirements, or a design decision only the owner can make where every option is genuinely unsafe. Ambiguity you can resolve with a sensible default is **not** a blocker — decide, note the assumption, and proceed.

When an issue is truly blocked: post the blocker comment (format above, describing what's blocking and what you'd need), record it as blocked, and **move on to the next issue**. One blocked issue must never halt the rest of the batch.

## 4. Running low on tokens — schedule a resume

If you're going to run out of context/tokens before finishing the list, don't drop the remaining work. Finish the issue you're on (post its comment), then schedule yourself to continue with the **unfinished** issues via `ScheduleWakeup`:

- `prompt`: `/fix-issues <remaining issue numbers>` (only the ones not yet done).
- `delaySeconds`: a longer idle interval (e.g. 1200–1800) — there's no external signal to poll, you're just resuming later.
- `reason`: e.g. `"resuming /fix-issues on remaining issues #<...> after token budget refresh"`.

Report in your summary that you scheduled a resume and which issues remain.

## 5. Final report

After the last issue, print a concise summary — one line per issue, in processing order:

```
Fixed <D>/<T> issue(s) from the list. All changes are uncommitted in the working tree; nothing pushed; nothing runtime-tested.
  • #<N> — done: <one-line summary>
  • #<N> — blocked: <reason>
  ...
```

If you scheduled a resume for unfinished issues, add a final line naming them and the scheduled time.

Remind the owner at the end that the working tree now holds intermingled uncommitted changes from multiple issues, and that they should review/test before committing.
