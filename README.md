# SeePath (Android)

Golf swing camera with persistent overlay lines.

**v0.2:** everything from v0.1 (drawing tools, setups, grid, camera report)
plus high-speed slow-motion recording (240/120fps via Camera2 constrained
high-speed sessions, speed picker in the top bar) and an instant in-app replay
that opens when recording stops - scrubbing, ⅛/¼/1x playback, true
frame-by-frame stepping at the recorded frame rate, drawing over the replay,
and a share button.

## Building

Pushing to `main` triggers GitHub Actions, which compiles the app and attaches
the APK to a release (see the *Releases* section of the repo). Open that
release link on an Android phone, download the APK, tap it, and allow
installation when prompted.

To build locally instead: open the project in Android Studio and run
*Build → Build APK(s)*.

## Roadmap

- v0.2 - high-speed (120/240fps) capture via Camera2 constrained high-speed sessions
- v0.3 - impact-detection auto record (rolling buffer + mic trigger), instant replay
- v0.4 - clip library, frame-accurate scrubbing, split-screen compare
- later - live remote coaching (WebRTC)
