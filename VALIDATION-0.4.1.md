# 0.8.1 / 0.4.1 validation

Runtime: Minecraft 1.21.1 / Cobblemon 1.7.3 / COBBLEVERSE 1.7.42.

- Core JUnit: 112 passed. Synergy JUnit: 13 passed.
- Live server session regression: 111 passed, including real selection/cloning after candidate-entity recovery, with base Charizard and the matching Charizardite X retained.
- Registry regression: 20 passed. All 909 candidate forms can be created with legal move slots.
- 92 ordinary Mega forms / 76 ordinary Mega-capable species. Every Mega draw includes its exact stone and satisfies the installed native canMega check. X/Y variants and cloned held items are checked.
- First-round typed rerolls cover 18 types; the initial starter-only offer is retained.
- Rare species AND rare Mega-form labels are excluded from the Mega category, including the pack's mythical Floette MegaE exception.
- Loaded registry: all 1025 species implemented. The reported 130 missing species was not reproduced. The main world's saved pack configuration enables COBBLEVERSE-DP-v31.zip, and the tested pack archive matches the main server.
- Per-form filtering restores seven terminal Pikachu costume/partner forms. The final pool covers 576 species / 909 forms; unevolved and battle-only restrictions remain documented.
- Server/client release artifacts and updater hashes are verified separately at deployment.
- Tests include an isolated injected Mega offer; no test fixture is distributed. This headless validation does not substitute for visual UI review.
