---
name: copilot-review-is-measured-by-gh-run
description: "A running Copilot review is invisible to `gh api .../reviews` and `requested_reviewers` — it is a GitHub Actions job (`copilot-pull-request-reviewer`), so measure it with `gh run`. The 422 on requested_reviewers is NORMAL, not a blocker"
metadata:
  node_type: memory
  type: reference
  originSessionId: 62e4b01e-08e6-453e-9c0f-6cfcc67c6820
  modified: 2026-10-03T06:32:53.534Z
---

Measured on 2026-10-02 in rke2lab. **Two sessions got this wrong the same day**, and one of them
(me) told the user the review could not be triggered.

## The wrong measurements

While a review was actually running:

```text
gh api repos/<o>/<r>/pulls/<n>/reviews   -> 0
gh api repos/<o>/<r>/pulls/<n>/comments  -> 0
requested_reviewers                      -> []  (empty)
```

And trying to request it:

```text
gh api -X POST …/pulls/<n>/requested_reviewers -f 'reviewers[]=copilot-pull-request-reviewer'
  -> 422 "Reviews may only be requested from collaborators."
gh pr edit <n> --add-reviewer copilot-pull-request-reviewer
  -> prints the PR URL, changes nothing
```

⚠️ **The 422 is NORMAL** — `requested_reviewers` is simply not the mechanism. Do **not** conclude the
review is impossible to trigger.

## ✅ The right measurement

The review is a **GitHub Actions job** named `copilot-pull-request-reviewer`, triggered "via
dynamic":

```bash
gh run list                 # the job appears here
gh run view <id>            # its steps: Set up job, Initialize, Prepare Copilot, …
```

`reviews` and `comments` only populate **after** Copilot posts.

★ `.github/workflows/` does **not** exist in this repo, so the job is supplied by the **GitHub
app**, not by a workflow in the tree. Therefore "there is no CI here" stays true for **building**
(the manual barrier remains indispensable) and is false for **reviewing**.

★ Triggering is done from the **web UI** — an agent cannot arm it through the API.

## Why the loop is worth it, measured

Over one increment: **4 rounds, 6 behaviour defects**, on 220 lines that had shipped without a
single test — and none had been caught by two humans-plus-agents reading the diff. Worse, the first
two rounds of fixes **each introduced the next defect**.
★ The stopping rule that worked: stop when a round yields nothing that **changes behaviour**, only
prose consistency — and **bound it**: one more round, then a firm stop unless a high-severity
finding appears. Classify findings by **nature**, never count them; counting says "continue"
for ever.

See [[gh-stack-operating-rules]]
