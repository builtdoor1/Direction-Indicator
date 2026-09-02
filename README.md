# Direction Indicator

Shows which way the player in front of you is actually moving.

A client-side [Fabric](https://fabricmc.net/) mod for **Minecraft 1.21.11**. By *builtdoor*.

A flat bar floats above every nearby player's head and turns **green** when they are moving
forward, **red** when they are backpedalling and **yellow** when they are not meaningfully
moving. A sound plays the moment a nearby player jumps.

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
repeat while they are in the air, and stepping off a ledge is not a jump. By default it plays
only for *other* players, not for your own jumps.

Knockback does not count as a jump. Getting hit launches a grounded player upward almost exactly as
hard as a jump does, so on a PvP server the cue would otherwise fire on every hit.

Both features use the same radius, 24 blocks by default.

---

## Install

1. [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.3 or newer, for Minecraft 1.21.11.
2. [Fabric API](https://modrinth.com/mod/fabric-api).
3. Drop `direction-indicator-1.1.1.jar` from [Releases](https://github.com/builtdoor1/Direction-Indicator/releases) into your `mods` folder.

Java 21 is required, which is what the 1.21.11 launcher already uses.

Optional, for the settings screen: [Mod Menu](https://modrinth.com/mod/modmenu) and
[Cloth Config](https://modrinth.com/mod/cloth-config). The mod runs fine without either, it just
uses the defaults and the JSON file.

---

## Settings

With Mod Menu and Cloth Config installed, open **Mods → Direction Indicator → Configure**.
Otherwise edit `config/directionindicator.json`, which is written on first launch.

**General**

| Setting | Default | What it does |
|---|---|---|
| Radius | 24 | Blocks. Used by both the jump sound and the bar. |

**Jump Sound**

| Setting | Default | What it does |
|---|---|---|
| Enabled | on | |
| Play for your own jumps | **off** | Leave off to hear only other players jump. |
| Volume | 60% | |
| Pitch | 160% | |
| Ignore knockback for | 10 ticks | After a player is hit while standing, ignore their next launch for this long. Cleared the moment they land, so a larger value costs very little. 0 turns it off. |

**Direction Bar**

| Setting | Default | What it does |
|---|---|---|
| Enabled | on | |
| Show above yourself | off | Only visible in third person (F5). Handy for checking the mod works. |
| Moving forward / backward / not moving | green / red / yellow | |
| Movement threshold | 20/1000 blocks per tick | Below this the bar reads as idle. Sneaking is ~65, walking ~216, sprinting ~280. |
| Width / Height | 55 / 11 hundredths of a block | |
| Height above head | 30 hundredths of a block | Tucks the bar between the head and the nametag. |
| Fill / Backdrop opacity | 235 / 140 | Out of 255. |

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

**Ignoring knockback.** A hit launches a grounded victim at `min(0.4, vy/2 + 0.4)` = 0.4, against a
jump's 0.42. Through the ~3-tick interpolation those are the same number, so velocity cannot separate
them. The mod keys off the hurt animation instead: `handleDamageEvent` sets `hurtTime` on every
tracking client, so a launch that begins just after a hit is knockback.

Two details stop that from silencing every jump in a fight. Only a hit landing on a *grounded* player
can launch them, so nothing is suppressed while they are already airborne. And landing clears the
suppression, because every genuine jump after a hit has a landing in between and a knockback launch
never does.

The limits are worth stating: a jump made on the *same tick* as a hit, the classic jump reset, is
genuinely indistinguishable from pure knockback on the client and will be suppressed. And launches
that deal no damage at all, such as wind charges and fishing rod pulls, set no `hurtTime` and will
still ping.

**Drawing the bar.** The quad is built from the camera's own up and right vectors, which is what
makes it face you, and uses `RenderTypes.debugQuads()`: `POSITION_COLOR` quads with translucent
blending, no depth write and no back-face culling, so the dark backdrop and the coloured fill can
share a plane and simply draw in order.

It is drawn on `WorldRenderEvents.END_MAIN`. That matters more than it looks. In
fabric-rendering-v1 16.2.x, `BEFORE_DEBUG_RENDER` is the one "drawing" event injected into the
*extraction* region of `renderLevel`, before the frame graph is even built, so a callback there is
handed the previous frame's `PoseStack` and opens a vertex batch outside any executing render
pass. `END_MAIN` fires inside the main pass, immediately before the world renderer's own
`endBatch()`, which is the phase Fabric's own documentation points at for content that must not be
overdrawn or cleared.

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
