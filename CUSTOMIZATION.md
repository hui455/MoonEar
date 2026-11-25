# MoonEar Custom: earbud button controls

Based on upstream MoonEar 3.4.2. Distributed under the upstream GPL-3.0 license.
The fork uses application ID `com.hui455.moonear`, its own signing key and update
repository, so it can be installed alongside the original MoonEar.

## What changed

- A connected-device dashboard and an earbud button settings page, both following
  the existing system/light/dark preference.
- Read-only discovery of TOUCHV2 (feature 22, five bytes) and TOUCHV3 (feature 26,
  seven bytes). Unknown layouts remain read-only. TOUCHV3's additional two bytes
  are preserved unchanged by the five-gesture editor.
- Read the headset's **current** configuration using command 2. Read the headset's
  **factory default** configuration separately using command 1. These are never
  substituted for each other. A failed defaults query leaves factory restore disabled.
- Disable single-tap independently for each ear, or disable both in one operation.
  Moving play/pause to double-tap is a separate option. Other values are preserved.
- Show every changed action before writing, re-read to detect changes made elsewhere,
  persist a first-write backup per paired device, then use command 3 to write.
- Re-read the complete payload and require byte-for-byte equality before reporting
  success. Missing ACKs trigger a delayed readback; an ACK alone is never success.
- Restore either the factory defaults returned by this headset or the saved first-write
  backup. Both routes use the same change preview, validation and readback.

Action IDs 4/5 (volume) are not offered because available evidence only infers them.
Unknown action IDs are displayed numerically and preserved. The first-write backup
is retained in private app preferences; uninstalling/clearing app data removes it.

## Protocol evidence

The official MOONDROP APK's `V3TouchV2Plugin` and `V3TouchV3Plugin` each declare
GET_DEFAULT_ACTION=1, GET_CURRENT_ACTION=2, SET_CURRENT_ACTION=3 and publish current
and default responses separately. `TouchNewInfo` packs left/right action IDs into
the high/low nibbles of five bytes. `TouchNewV3Info` extends that to seven bytes.
No vendor code or extracted assets are included in this fork.

## Validation

Run `gradlew.bat :app:testDebugUnitTest` for the upstream regressions and new
TouchSession tests. Run `gradlew.bat :app:recordRoborazziDebug` to render production
view builders with fixture state. Screenshots are in `app/screenshots/custom/`.

The screenshots use clearly documented test fixtures, not live readings. They cover
light/dark device and button pages and the unavailable configuration state.
Mobbin MCP was unavailable in the authoring session, so no Mobbin reference was used.

Space Travel 2 Ultra configuration reads, exact action semantics, persistence after
reconnection and restoration still require testing against the user's headset. A
successful build or screenshot is not evidence that a hardware write succeeded.

## Build

The upstream Gradle wrapper and JDK 21+ are used. Install SDK platform 37.
Release signing accepts `MOONEAR_KEYSTORE`, `MOONEAR_KEY_ALIAS`, and `FXXK_KEYPASS`
as process environment variables. Never commit the private keystore or password.
