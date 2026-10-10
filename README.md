# Hindi TV (lightweight Android TV app)

Plays the iptv-org Hindi playlist (`https://iptv-org.github.io/iptv/languages/hin.m3u`).

## Why it is light
- Zero dependencies (no AndroidX, no ExoPlayer). Uses the built-in `VideoView`, which plays HLS natively.
- No images or XML layouts; UI is built in code. The channel list is plain text.
- Playlist is downloaded on launch and cached (~100 KB). It falls back to the cache when offline.
- Decoder is released whenever the app leaves the screen.
- Release build is minified and resource-shrunk. Expect an APK of well under 100 KB.

## Remote control
- OK / Menu: show channel list
- Up / Down (list hidden) or CH+ / CH-: previous / next channel
- Back: hide list, or exit when already hidden

## Build
1. Install Android Studio (or JDK 17 + Android SDK 34).
2. Open this folder and let Gradle sync, or run `gradle assembleRelease`.
3. APK: `app/build/outputs/apk/release/app-release.apk`

## Install on the TV
```
adb connect <tv-ip>:5555
adb install app/build/outputs/apk/release/app-release.apk
```
Or copy the APK to a USB drive and open it with a file manager on the TV.

## Notes
- Many free IPTV streams go offline; if one fails, the app says so and reopens the list.
- Some streams are plain `http`; cleartext traffic is enabled for that reason.
- Release is signed with the debug key so it can be sideloaded. Use your own key for Play Store.
