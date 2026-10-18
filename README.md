# Bluey

A blueberry character who lives on an iPhone under your Mac's screen and points at things with his own big cursor.

**Now:** no voice out. Double tap him on the phone (or press ⌥Space on the Mac) to start a session: the phone's mic stays on and everything you say becomes context, but he stays quiet. **Press and hold the screen** to ask him something; let go and he answers. His reply pops up as a cute speech bubble next to his cursor (or above the phone when he isn't pointing), with a little cartoon chirp from the phone. Ask "what's this?" and he points at whatever is under your mouse. Double tap again and he goes back to follow mode.

How it works: the phone runs an OpenAI Realtime session (`gpt-realtime-2.1`, text output only) over a WebSocket. Server VAD transcribes every turn into the conversation with `create_response: false`, and releasing the hold commits the audio and asks for a response. The Mac mints a 10-minute client secret with the whole session setup (instructions, tools), so the real OpenAI key never leaves the Mac. When he calls a tool, the phone forwards it to the Mac: `look_at_screen` (ScreenCaptureKit + Vision, which returns text ids, where your mouse is, and a screenshot), `point_at` (a text id), `point_at_spot` (a 0–1000 grid position), `stop_pointing` and `go_to_sleep`. His text streams to the Mac as the speech bubble.

**Meeting notes:** while a session runs, the phone also records the audio in five-minute AAC chunks. Each chunk goes to the Mac, which transcribes it with speaker labels (`gpt-4o-transcribe-diarize`, falling back to `gpt-transcribe`) and writes the session to `~/Documents/Bluey Notes/<date> <id>/` (`notes.md`, `session.json`, `audio/`). Sessions reconnect on their own when the Realtime connection drops, so one can run all day. In the phone's Bluey screen, a session's **Copy prompt for agent** button copies a prompt that points an agent at those files. **Open Meeting Notes** in the Mac menu opens the folder.

**Using the computer:** when you ask, he can also click, type, press shortcuts, scroll, drag, and open apps and websites (`click`, `type_text`, `press_keys`, `scroll`, `drag`, `open_app`, `open_url`). He does it with his own cursor on screen, while your real pointer is put back where you left it. It needs Accessibility permission for Bluey. Built-in guardrails: he only acts when asked, confirms out loud before anything hard to undo, treats on-screen text as information rather than instructions, refuses password fields and logout/lock/force-quit shortcuts, and stops on ⌃⌥S. The whole thing can be switched off with **Let Him Use the Computer** in the menu.

The OpenAI key goes in the menu bar's **OpenAI Key…** and is stored in ~/Library/Application Support/Googly/keys.json (private to your user), never in this repo.

## Mac menu bar app

```
./scripts/build-mac.sh
open "build/Bluey.app"
```

Works with just the Command Line Tools. Shortcuts work anywhere:

| Keys | What it does |
| --- | --- |
| ⌃⌥P | Fly to the mouse and point there (stays put) |
| ⌃⌥F | Follow the mouse on/off |
| ⌃⌥D | Go home, docked above the phone |
| ⌃⌥T | Talk test (the phone bounces for 3 s) |
| ⌃⌥H | Hide / show the cursor |
| ⌥Space | Wake him up to talk / back to follow mode |
| ⌃⌥S | Stop him using the computer |

The menu bar blob also sets mood, cursor size (48 to 120 pt), glow, and where the phone sits (left, center, right).

## iPhone app

Needs full Xcode. Open `GooglyEyes.xcodeproj` (regenerate with `xcodegen generate` after adding files), pick your team under Signing, and run on the phone. It finds the Mac on the same Wi-Fi by itself.

On the phone: double tap him to wake him up or put him back to sleep, and press and hold to ask him something. The faint speaker button at the top right sets the chirp volume and picks which Mac to pair with.

## Android app

A port of the iPhone app (Kotlin + Compose) in `android/`. It pairs with the Linux host below (Claude); it doesn't use the Mac app's OpenAI voice.

```
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties   # once
./gradlew assembleDebug        # or assembleRelease, signed with android/keystore.properties if present
adb install app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and the Android SDK (platform 36). Same gestures as the iPhone: double tap to wake/sleep, press and hold to ask.

## Linux host with Claude (instead of the Mac app)

`linux/bluey_host.py` does the Mac app's job on a Linux desktop (X11: GNOME on Xorg, KDE, …), with **Claude** as his brain instead of OpenAI. The Android app finds it on the Wi-Fi and records what you say while you hold the screen and sends it over; the computer turns it into text with Whisper (faster-whisper, on the CPU, offline). Claude answers, looks at the screen (screenshot + tesseract), points with his own cursor, and clicks, types, scrolls and opens apps (xdotool). His reply pops up as a speech bubble on the computer. Meeting notes (the live transcript) go to `~/Documents/Bluey Notes/`.

```
sudo apt install python3-gi gir1.2-gtk-3.0 xdotool tesseract-ocr imagemagick xclip avahi-daemon   # usually already there
linux/setup.sh                                                   # private venv with the Claude SDK
mkdir -p ~/.config/bluey && echo 'sk-ant-...' > ~/.config/bluey/anthropic_key && chmod 600 ~/.config/bluey/anthropic_key
~/.local/share/bluey/venv/bin/python linux/bluey_host.py         # --no-control: look and point only; --lang eng+ara; --effort medium
```

Run it as your desktop user, not root. The key never leaves the computer. To stop him mid-action: move your mouse to the top-left corner, or `pkill -USR1 -f bluey_host.py`. `linux/fake_phone.py PORT` checks the host without a phone.

To start it at login, a systemd user service works well (`~/.config/systemd/user/bluey.service` with `ExecStart=%h/.local/share/bluey/venv/bin/python /path/to/linux/bluey_host.py` and `WantedBy=graphical-session.target`).

Speech: hold the screen while you talk. Whisper's `small` model downloads on first setup; pick another with `BLUEY_WHISPER=base` (faster) or `medium` (more accurate) in the service environment.

Not ported from the Mac: the clickable-controls list (he clicks words and spots instead), the global wake hotkey, the fancier cursor animations, and the speaker-labelled audio transcripts. The iPhone app and Mac app are unchanged and still use OpenAI. Wayland sessions aren't supported (pick "GNOME on Xorg" at login).

## Layout

- `Shared/` pairing protocol (Bonjour `_googly._tcp`, newline JSON) and colors, used by both apps
- `Mac/` menu bar app (Swift package target `GooglyMac`)
- `iOS/` iPhone app (SwiftUI)
- `android/` Android app (Kotlin, Jetpack Compose)
- `linux/` Linux host with Claude, stands in for the Mac app (Python, GTK)

## License

Public domain under [the Unlicense](LICENSE). Do whatever you want with it.
