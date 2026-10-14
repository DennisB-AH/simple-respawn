# Simple respawn

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Simple Respawn is a Fabric mod focused on configurable lives and respawning. The goal is to let server owners shape how costly death is and give players ways to help each other get back into the game.

## Lives

- Players start with `maxLives` lives, regardless of gamemode. This is also the hard life cap. The default is one life.
- Each death consumes one life and privately tells the player how many they have left. After using their last life, a player respawns in spectator mode until a life regenerates or an operator revives them.
- Players can use `/lives` to privately see their remaining lives and, when below the cap, the time until their next life, formatted as seconds, minutes and seconds (e.g. `1m 30s`), or hours, minutes and seconds (e.g. `1h 1m 30s`).
- Operators can use `/lives grant <player> <amount>` to add lives or `/lives set <player> <amount>` to set an exact count. Requests that would exceed `maxLives` are rejected rather than partially applied. Granting or setting positive lives for an eliminated player restores the gamemode they had before elimination and respawns them at their spawnpoint; setting lives to zero immediately eliminates them.
- Players can use `/revive <player>` to sacrifice the configured revive item, held in their main hand, to bring an eliminated online player back with one life. The reviver must be alive with at least one life and cannot revive themselves. The revived player is restored like any other revival.
- Spectators who join with positive lives are also restored and respawned. Restoration uses the saved pre-elimination gamemode, or survival if none is available.
- Respawning after death uses Minecraft's normal spawnpoint rules. Revival also uses the player's bed, charged respawn anchor, or configured spawnpoint, falling back to world spawn if it is missing or obstructed. Revival does not consume another respawn anchor charge.

On first launch, the mod creates `config/simplerespawn.json` with:

```json
{
  "maxLives": 1,
  "lifeRegenIntervalSeconds": 0,
  "reviveItem": "minecraft:totem_of_undying",
  "reviveItemCount": 1
}
```

Change these settings and restart the server:

- `maxLives`: a positive integer defining starting lives and the maximum life count. Lowering it clamps existing players' lives to the new cap when their world loads. Raising it lets existing players regenerate toward the new cap.
- `lifeRegenIntervalSeconds`: whole seconds between regenerated lives. The default `0` disables life regeneration, so eliminated players only return through `/revive` or operator commands. There is no application-imposed maximum duration; do not quote the number. Negative values and fractional seconds are invalid.
- `reviveItem`: the item id (including modded items) that `/revive` consumes, defaulting to `minecraft:totem_of_undying`. Unknown items and air are rejected when the server starts.
- `reviveItemCount`: how many of `reviveItem` must be held in the main hand and are consumed per revive, defaulting to `1`. It must be positive and fit in a single stack of the item.

Missing settings are added to the config with their defaults on startup.

## Life regeneration

Life regeneration applies when `lifeRegenIntervalSeconds` is positive. With `0`, no lives regenerate, `/lives` shows no countdown, and saved countdowns are discarded; re-enabling regeneration starts fresh countdowns when the world loads.

- The countdown starts when a player drops below `maxLives`. One life regenerates per interval until they reach the cap; additional deaths do not restart the countdown. Operator changes below the cap also preserve an existing countdown, while reaching the cap clears it.
- The timer uses real elapsed time, including offline time and server downtime. Missing intervals are applied when the world loads, during server ticks, or before player life operations, without exceeding the cap. Online players receive a private message when lives regenerate.
- Regenerating a life from zero automatically restores the player's saved gamemode and respawns them at their spawnpoint. Offline players are restored when they next join. If a life regenerates while the death screen is open, revival completes when they respawn.
- Per-player lives, pre-elimination gamemode, and regeneration progress are saved in `simplerespawn-lives.json` in the world save directory. Older saves remain compatible; players below the cap with no saved timer start their countdown when the world loads.
- Changing the interval on restart recalculates regeneration from the saved countdown baseline. A backward system-clock adjustment does not award lives; the timer waits for real time to catch up.

Regeneration does not add a separate respawn delay for players who still have lives.

## Planned features

- 

This mod is in early development; if you have any feedback or feature requests, please open an issue on GitHub.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
