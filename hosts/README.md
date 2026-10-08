# hosts/

Host adapters live on **per-host branches**, not on `main`.

- `main` contains only the game-independent framework: `protocol/`, `bindings/`, `minecraft/`.
- Each host adapter is developed on its own branch and checked out as a sibling worktree, e.g.:

```text
CrossMC/                     container
├─ CrossMC/                  worktree — branch `main`  (framework only)
└─ HowToFishMC/              worktree — branch `HowToFishMC`  (framework + hosts/HowToFish)
```

The first host adapter (How to Fish) is on the **`HowToFishMC`** branch under `hosts/HowToFish/`
(BepInEx plugin + the adapter-specific configuration). `main` intentionally carries no
game-specific code, so the framework stays game-agnostic.

Conventions for a host branch:
- branch name `CrossMC-<Game>` or `<Game>MC` (here: `HowToFishMC`);
- keep the adapter under `hosts/<Game>/`;
- the generic `protocol/`, `bindings/` and `minecraft/` stay identical to `main`.

Syncing framework changes onto a host branch: `main` has no `hosts/HowToFish`, so a plain
`git merge main` marks it deleted — when that happens, restore it (`git checkout HEAD~ -- hosts/<Game>`)
and commit. Framework changes are infrequent, so this is cheap; a rebase-based flow is possible too.
