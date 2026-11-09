# 0.4.0 validation

- Exact runtime pair: CobbleLeague 0.8.0 / CobbleSynergy 0.4.0 / Cobblemon 1.7.3 / Minecraft 1.21.1.
- Source and integrated-module release JARs have identical SHA-256 hashes.
- 9 JUnit tests passed, including 0.8.0 protocol mapping and configuration migration.
- Showdown simulations: 343 generic + 32 Fairy accuracy checks passed.
- Core: 112 JUnit, 110 live server session checks and 12 registry/data checks passed. Session checks include observer lifecycle, temporary-party preservation, menu reopening/closing, damage/block guards, ready status, ping cooldown and battle recovery.
- Client updater: 8 checks; compatibility installer: 6 checks passed. The bundle contains both exact matching addons and test=false.
- UI classes were compiled; these headless checks do not substitute for a visual in-game review.
