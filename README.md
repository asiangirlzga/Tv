# Hindi TV (lightweight Android TV app)

Plays the iptv-org Hindi playlist (`https://iptv-org.github.io/iptv/languages/hin.m3u`).

## Why it is light
- Only two small libraries: ExoPlayer core + HLS (no UI module). Handles live streams, buffering and reconnects.
- No images or XML layouts; UI is built in code. Buffer is capped (~8 MB) and video capped at 480p.
- Playlist is downloaded on launch and cached (~100 KB). It falls back to the cache when offline.
- Decoder is released whenever the app leaves the screen.
- Release build is minified and resource-shrunk. Expect an APK of roughly 1-2 MB.

## Layout
Video on top. The playing channel and the channel list are always shown at the bottom.

## Remote control
- Up / Down: move through the channel list
- OK: play the highlighted channel
- CH+ / CH-: previous / next channel directly
- Menu: hide / show the bottom panel (full-screen video)
- Back: exit

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
