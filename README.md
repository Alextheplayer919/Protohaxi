# ProtoHax-Android

<img align="right" width="159px" src="https://raw.githubusercontent.com/SkidderMC/ProtoHax/stable/icon.png">

ProtoHax-Android is the Android implementation of [ProtoHax](https://github.com/SkidderMC/ProtoHax), an open-source cheat for Minecraft: Bedrock Edition that works through the network layer. This repository contains the Android-specific code for the cheat.

**This fork additionally carries the module set of [WClient](https://github.com/Alextheplayer919/WClient), ported onto ProtoHax's API - see [WClient modules](#wclient-modules) below.**

## Features
1. No modifications to Minecraft client
2. Seamless switching/adapting multiple versions
3. Full control of the packet layer

## WClient modules

`app/src/main/java/dev/sora/protohax/relay/modules` holds 29 modules ported from
[WClient](https://github.com/Alextheplayer919/WClient) (GPLv3), which ships a much larger set of
anarchy-server modules than ProtoHax does on its own. WClient has its own session and entity model,
so each module was rewritten against `dev.sora.relay`: WClient's `beforePacketBound()` became
`handle<EventPacketInbound>` / `handle<EventPacketOutbound>`, `intercept()` became `cancel()`,
`session.clientBound()` became `session.sendPacketToClient()`.

| Category | Modules |
|---|---|
| Combat | AntiKnockback, AutoTotem, Hitbox, HotbarSwitcher, InfiniteAura, TriggerBot |
| Movement | MotionFly, AntiAFK, AutoWalk, Bhop, Boost, HighJump, JetPack, MultiJump, NoClip, Spider, Sprint |
| Visual | Fullbright, TimeShift, Zoom |
| Misc | AntiDebuff, AutoDisconnect, ChatSpam, ChatSuffix, Desync, FakeDeath, NoChat, PositionLogger |

Three names differ from WClient's because the ProtoHax library already uses them:
`Boost` (`speed`), `MultiJump` (`air_jump`) and `ChatSpam` (`Spammer`).

Ports that deliberately differ from the original:

* **MotionFly replaces fly.** The old fly modules - WClient's ability fly (this app's former
  `AbilityFly`) and the bundled library's `Fly` (removed from the module list in
  `MinecraftRelay.registerAdditionalModules`) - both tell the game it has the fly ability via
  `UpdateAbilitiesPacket`. Servers, anarchy servers included, patch exactly that: the server
  never granted the ability, so the flight that follows gets flagged and rubberbanded.
  `MotionFly` (a port of the LeHu/Testfly style) never touches abilities: it reads the move
  vector, yaw and jump/sneak inputs from the outgoing `PlayerAuthInputPacket`, turns them
  into a per-tick velocity (speeds in BPS / 20, horizontal capped at sqrt(5.99) blocks/tick)
  and sends it to the game as a client-bound `SetEntityMotionPacket`. It keeps the original's
  anti-rubberband distance check (a >4.5 block position jump is treated as a setback and
  skipped) and a collision guard fed by the client's own `HORIZONTAL_COLLISION` /
  `VERTICAL_COLLISION` input flags. Hold jump to climb, sneak to descend, glide -0.02 by default.
* **NoClip** keeps WClient's approach - the server never grants anything, the relay
  drops the abilities the server sends down and the ability requests the game sends up, and feeds
  the game its own `UpdateAbilitiesPacket`. The shared plumbing is in
  `modules/util/Abilities.kt` and `modules/motion/ModuleAbilityBase.kt`.
* **Zoom** uses the protocol's own `CameraInstructionPacket` field-of-view instruction instead of
  WClient's walk-speed trick, which this protocol version has no equivalent for.

Shared helpers live next to them in `modules/util`: `MobList` (ported verbatim), `Abilities`, and
`ModuleCombat` (attack, swing, silent aim, targeting, motion). All of them are registered in
`MinecraftRelay.registerAdditionalModules`, and they show up in the overlay menu's existing
category tabs - no UI change was needed.

WClient is licensed under the GNU GPLv3 and so is this repository; see
[License](#license).

## Building the APK

No HWID / device-whitelist check is included - the app runs on any device.

### GitHub Actions (easiest - no local setup)

1. Push to this repository (or open **Actions -> Build APK -> Run workflow**).
2. Wait for the **Build APK** workflow to finish.
3. Open the finished run and download the **`ProtoHax-APK`** artifact
   (it contains `app-release.apk`, signed with the debug key so it installs directly).

### Local build

Requires JDK 21 and the Android SDK (API 34):

```sh
./gradlew app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
./gradlew app:assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
```

The vendored ProtoHax library (`app/libs/ProtoHax-1.4.0.jar`, Minecraft Bedrock
1.21.111 / protocol 844) is committed to this repository, so neither build needs
any other repository. See [JAVA21_BUILD.md](JAVA21_BUILD.md) for how that jar was made.
For a real release key, add a `keystore.properties` in the repository root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) - without it the
release APK is signed with the Android debug keystore (see `app/build.gradle`).

## Issues
- If you notice any bugs or missing features in the Android-specific code, you can let us know by opening an issue [here](https://github.com/SkidderMC/ProtoHax-Android/issues).
- If you're having issues with the cheat features themselves, please send the issue to the main repository [here](https://github.com/SkidderMC/ProtoHax/issues).

Please notice that this is an **English-only** repository, so all issues and pull requests must be in English, if you can't speak English, please use a [translator](https://translate.google.com/).

## License
This project is subject to the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.en.html). This does only apply for source code located directly in this clean repository. During the development and compilation process, additional source code may be used to which we have obtained no rights. Such code is not covered by the GPL license.

For those who are unfamiliar with the license, here is a summary of its main points. This is by no means legal advice nor legally binding.

*Actions that you are allowed to do:*

- Use
- Share
- Modify

*If you do decide to use ANY code from the source:*

- **You must disclose the source code of your modified work and the source code you took from this project. This means you are not allowed to use code from this project (even partially) in a closed-source (or even obfuscated) application.**
- **Your modified application must also be licensed under the GPL**

## Installation
We have used the [local maven repository](https://docs.gradle.org/current/userguide/declaring_repositories.html#sec:case-for-maven-local), you need to build and publish [ProtoHax](https://github.com/SkidderMC/ProtoHax) to the repository before you can build, sorry for inconvenience.

### With Android Studio
1. Clone the repository: `git clone https://github.com/SkidderMC/ProtoHax-Android.git`
2. Import the project into Android Studio
3. Connect your Android device to your computer
4. Build and run the project on your device

### With Gradle
1. Clone the repository: `git clone https://github.com/SkidderMC/ProtoHax-Android.git`
2. CD into the local repository.
3. Connect your Android device to your computer
3. Run `gradlew app:assembleDebug`

## Contributing
We welcome contributions to ProtoHax! If you would like to contribute, please fork the repository and make changes as you'd like. Pull requests are welcome.

## Disclaimer
Please use ProtoHax at your own risk. We **DO NOT** take responsibility for any bans or punishments that may occur as a result of using this cheat.
