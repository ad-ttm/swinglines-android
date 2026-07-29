# SwingLines (Android)

Golf swing camera with persistent overlay lines.

**v0.1 skeleton:** live camera preview, drawing tools (line / freehand / circle,
5 colours, draggable endpoints), rule-of-thirds grid, undo/clear, named line
setups that persist between sessions, standard-fps video recording to
`Movies/SwingLines`, and a "Camera info" report showing the phone's supported
high-speed (slo-mo) modes - which shapes the 240fps work in v0.2.

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
