# Direction Indicator

Shows which way the player in front of you is actually moving.

A client-side [Fabric](https://fabricmc.net/) mod for **Minecraft 1.21.11**. By *builtdoor*.

A flat bar floats above every nearby player's head and turns **green** when they are moving
forward, **red** when they are backpedalling and **yellow** when they are not meaningfully
moving. A sound plays the moment any nearby player jumps.

Client-side only. It does not talk to the server, and it works on any server you can already
join.

---

## What it does

**Direction bar.** Above each nearby player, a small camera-facing bar reads their movement
against the direction they are looking:

| Colour | Meaning |
|---|---|
| Green | Moving forward |
| Red | Moving backward |
| Yellow | Standing still, or moving with no real forward or backward component |

Strafing is almost entirely sideways, so it reads yellow. The bar always faces you, sits just
above the head and just below the nametag, and is drawn behind terrain rather than through it.

**Jump sound.** A short cue fires once, on the tick a nearby player starts a jump. It does not
repeat while they are in the air, and stepping off a ledge is not a jump.

Both features use the same radius, 24 blocks by default, and both include you as well as
everyone else.

---

## Install

1. [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.3 or newer, for Minecraft 1.21.11.
2. [Fabric API](https://modrinth.com/mod/fabric-api).
3. Drop `direction-indicator-1.0.0.jar` from [Releases](https://github.com/builtdoor1/Direction-Indicator/releases) into your `mods` folder.

Java 21 is required, which is what the 1.21.11 launcher already uses.

---

## Settings

There is no config screen and no keybind on purpose. Everything is a constant at the top of
[`DirectionIndicatorClient.java`](src/main/java/direction/indicator/DirectionIndicatorClient.java):

| Constant | Default | What it does |
|---|---|---|
| `RADIUS` | `24.0` | Blocks. Governs both the bar and the jump sound. |
| `INCLUDE_SELF` | `true` | Whether your own jumps and your own bar count. |
| `JUMP_SOUND` | `EXPERIENCE_ORB_PICKUP` | Any plain `SoundEvent` constant. |
| `JUMP_VOLUME` / `JUMP_PITCH` | `0.6` / `1.6` | |
| `COLOR_FORWARD` / `COLOR_BACKWARD` / `COLOR_IDLE` | green / red / yellow | `0xRRGGBB`. |
| `MOVE_THRESHOLD` | `0.02` | Blocks per tick along the facing below which a player reads as idle. Sneaking is about `0.065`, walking `0.216`, sprinting `0.28`. |

Bar size, border and how far it floats above the head live in
[`IndicatorRenderer.java`](src/main/java/direction/indicator/IndicatorRenderer.java).

---

## How it works

Two Fabric API events, no mixins.

**Reading movement.** Remote players do not report usable `getDeltaMovement()`, so the mod
measures per-tick position deltas instead and projects them onto the player's look yaw. Yaw
comes from `getYRot()` rather than body rotation, because movement input is applied relative to
where a player is looking, and body rotation lags behind. The result is smoothed so a single
dropped position packet cannot flicker the colour, and jumps of more than four blocks in a tick
are discarded as teleports.

**Detecting jumps.** `onGround()` is synced for remote players, but their position arrives
interpolated over roughly three ticks, so on the tick the flag flips the player has only risen
about 0.14 blocks instead of the 0.42 you would see locally. Checking for a rise on that single
tick therefore misses other people's jumps. Instead, leaving the ground arms a short confirm
window, and the cue fires once the player has actually gained height while still airborne. That
is instant for you, tolerant of interpolation for everyone else, and still rejects walking off a
ledge, where height only ever decreases.

**Drawing the bar.** `RenderTypes.debugQuads()` gives `POSITION_COLOR` quads with translucent
blending, no depth write and no back-face culling, so the dark backdrop and the coloured fill can
share a plane and simply draw in order. The quad is built from the camera's own up and right
vectors, which is what makes it face you.

---

## Building from source

```bash
git clone https://github.com/builtdoor1/Direction-Indicator
cd Direction-Indicator
./gradlew build
```

The jar lands in `build/libs/`.

---

## License

MIT. See [LICENSE](LICENSE).
