# Memory / workspace mechanics — section index (rke2lab memory)

Section of the rke2lab memory index. Loaded on demand; the injected root is [MEMORY.md](MEMORY.md).
One line per entry (~200 chars); detail lives in the linked file.

## Memory / workspace mechanics (rke2lab-local; cross-cutting in hub)

- [Claude memory cascade state](claude-memory-cascade-state.md) — reference: 3-tier cascade worktree→hub→home + clean-split. **★ DEFECT RESOLVED (2026-08-14):** memory is pinned per-worktree via `autoMemoryDirectory` (absolute path in settings.local.json) — no slug, no symlink; `link-memory.sh` deleted. See [[hub:claude-auto-memory-mechanics]].
- [JDT.LS heap in generated workspaces](jdtls-heap-workspace-generation.md) — **★ DEFECT+FIX:** folder-scoped `.vscode` -Xmx wins over `.code-workspace` → effective heap was the lower; set both ≥8G in lock-step. See [[claude-memory-cascade-state]].
- [Worktree-provisioning handoff](worktree-provisioning-handoff.md) — **★ HANDOFF:** 3 worktree provisioning gaps fixed manually, must be automated (worktree:dir repoint; sops re-smudge; per-worktree Pulumi backend, stack named after branch slug). See [[sops-worktree-smudge-noise]] [[pulumi-stack-per-worktree-backlog]].
- [Pulumi stack per worktree (BACKLOG)](pulumi-stack-per-worktree-backlog.md) — flox PULUMI_BACKEND_URL project-relative → each worktree empty state; real dev only in main.
- [Sops worktree re-smudge](sops-worktree-smudge-noise.md) — `git worktree add` leaves sops files ENCRYPTED; FIX: `rm <files> && git checkout -- <them>`.
- [Handoff prompt opens on Progress narration](handoff-prompt-opens-on-progress-narration.md) — **★ feedback:** every next-conversation/next-workspace handoff prompt must OPEN with the obligation to read `.claude/hub/instructions.md` § "Progress narration" and apply it all session (auto-loaded ≠ heeded). See [[external-edges-chantier-handoff]].

- [Une session garde le chemin mémoire résolu au démarrage](running-session-keeps-its-resolved-memory-path.md) — **★ feedback:** changer `autoMemoryDirectory` en cours de session ne redirige RIEN (mesuré: réglage corrigé 09-27 22:44, écritures encore dans `main` le 09-28 12:48). A forké l'arbre mémoire → 7 fausses morts dans l'audit de liens. **Résolu**: la mémoire est une branche ORPHELINE (`memory`) avec son worktree, + hooks SessionStart/SessionEnd.
- [Récupérer un worktree orphelin](orphaned-worktree-recovery.md) — supprimer le clone principal tue l'historique du worktree mais PAS ses fichiers : si l'arbre était propre ils sont le commit. Recette + preuve par chemin de store. ndh est passé en dépôt **bare** pour que ça ne puisse plus arriver.
