# Hindi TV (lightweight Android TV app)

Plays the iptv-org Hindi playlist (`https://iptv-org.github.io/iptv/languages/hin.m3u`).

## Why it is light
- Only two small libraries: ExoPlayer core + HLS (no UI module). Handles live streams, buffering and reconnects.
- No images or XML layouts; UI is built in code. Buffer is capped (~8 MB) and video capped at 480p.
- Playlist is downloaded on launch and cached (~100 KB). It falls back to the cache when offline.
- Decoder is released whenever the app leaves the screen.
- Release build is minified and resource-shrunk. Expect an APK of roughly 1-2 MB.

## Layout
Video is always full screen. A transparent channel bar (playing channel + channel list) sits at the bottom,
hides itself after 5 seconds, and comes back when you press any navigation key.

## Remote control
- Up / Down / OK / Left / Right (bar hidden): show the channel bar
- Up / Down (bar visible): move through the channel list
- OK: play the highlighted channel
- CH+ / CH-: previous / next channel directly
- Menu: show / hide the bar
- Back: hide the bar, or exit when it is already hidden

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

## Automatic builds and updates (GitHub Actions)
Every push to `main` builds a signed APK and publishes it as a new GitHub Release (`v1.1.<run number>`).
Each build has a higher version code, so it installs over the previous one without uninstalling.

One-time setup (the signing key must stay the same forever, or Android refuses updates):
1. Create a key (any computer with Java):
   `keytool -genkeypair -v -keystore release.jks -alias hinditv -keyalg RSA -keysize 2048 -validity 10000`
2. Convert it to text: `base64 -w0 release.jks` (macOS: `base64 -i release.jks`)
3. In GitHub > Settings > Secrets and variables > Actions, add:
   `KEYSTORE_BASE64` (the text from step 2), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (hinditv), `KEY_PASSWORD`
4. Keep a private backup of `release.jks`. Never commit it.

If you previously installed an APK signed with the temporary debug key, uninstall it once before installing the first properly signed one.
