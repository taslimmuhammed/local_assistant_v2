# Local Assistant

A small, white-themed, ChatGPT-style Android chat app that runs **Gemma 4 E4B** entirely on
device via [LiteRT-LM](https://ai.google.dev/edge/litert-lm/android). No account, no server, no
network at inference time.

This is intentionally a **base** to build on: one model, one screen, persistent chats, nothing else.

## What it does

- Streams replies token-by-token from a local `.litertlm` model
- Multiple chats — create, switch, resume, delete; everything persists across restarts
- Gets the model either by **downloading** it (resumable) or **importing** one already on the device
- Falls back GPU + MTP → GPU → CPU if a backend fails to start
- Stop button that interrupts generation and keeps the partial reply

## Requirements

| | |
|---|---|
| Android | 8.0 (API 26) or newer, `arm64-v8a` |
| Free storage | ~4.2 GB |
| RAM | 8 GB+ recommended (GPU backend peaks around 0.7–1 GB, CPU around 3.3 GB) |
| JDK (to build) | 17 |

The LiteRT-LM AAR only ships `arm64-v8a` and `x86_64` native libraries, so `abiFilters` is set to
match. There is no 32-bit build.

## Build

```bash
./gradlew :app:assembleDebug
```

`local.properties` needs `sdk.dir` pointing at your Android SDK (already generated locally).

## Getting the model

On first launch the app shows the model screen. Two ways in:

**Download** — pulls `gemma-4-E4B-it.litertlm` (3.66 GB) from the LiteRT community repo on
Hugging Face. The file is public, so no token is needed. Downloads resume from wherever they
stopped, which matters at this size.

> **Use the unsuffixed file.** The same repo also has `-gpu.litertlm` and `-web.litertlm`. Despite
> the name, `-gpu` is the **WebGPU** build — it is the same size as `-web` (2.97 GB) and matches the
> "Web" row of the model card. On Android it loads without error and then emits raw vocab tokens
> (`<unused30>`, `[multimodal]`, `<mask>`) instead of text. The backend is chosen at runtime in
> `EngineConfig`, not by the filename; one file serves both CPU and GPU.

**Load from device storage** — pick a `.litertlm` file you already have. LiteRT-LM needs a real
filesystem path, and a `content://` URI from the picker is not one, so the file is **copied** into
the app's private storage. Budget room for both copies while the copy runs.

Sideloading is usually faster than downloading on the phone:

```bash
adb push gemma-4-E4B-it.litertlm /sdcard/Download/
```

Then pick it from the file picker.

## Architecture

```
AssistantApplication
└── AppContainer                  manual DI; one instance per process
    ├── SettingsStore             model path, backend prefs, system prompt
    ├── AppDatabase (Room)        chats + messages
    │   └── ChatRepository
    ├── ModelManager              download / import / delete the model file
    └── LlmService                owns the LiteRT-LM Engine
```

### LlmService

The piece worth understanding before extending anything.

- **One `Engine` per process.** Loading costs seconds and gigabytes, so it is shared across all
  chats and kept alive until the model is deleted.
- **One `Conversation` per chat.** The `Conversation` is what holds the KV cache for a thread.
- **The active conversation is cached.** Continuing the current chat only prefills the new turn.
  Switching chats, or resuming one after a process restart, rebuilds the conversation from stored
  history through `ConversationConfig.initialMessages`.
- **Interrupted turns invalidate the cache.** If generation is stopped or fails, the native cache
  no longer matches what was persisted, so it is dropped and rebuilt from the database next turn.

Prompt templating, BOS tokens and turn markers are handled inside LiteRT-LM — the app passes plain
strings and roles, never raw template text.

### Data

`chats` and `messages`, with `messages.chatId` cascading on delete. A message carries an
`incomplete` flag so a stopped reply is stored and shown as what it is.

## Images and voice

Both are gated on what the model file itself reports via `Capabilities(modelPath).inputModalities()`
— read from the file, not assumed — so the image and mic buttons only appear if the installed model
actually has those encoders. `EngineConfig` only declares `visionBackend` / `audioBackend` when they
are supported; the encoders load lazily, so declaring them costs nothing until an attachment is sent.

**Images** come from the system photo picker, which needs no permission. On import they are rotated
upright from EXIF and scaled so the longest edge is 768 px (`MediaLimits.MAX_IMAGE_EDGE_PX`) — a full
camera photo would spend memory and vision tokens without showing the model more.

**Voice** follows the WhatsApp convention: the button is a mic while the composer is empty and turns
into send as soon as there is text or an attachment. Recording replaces the composer with elapsed
time, remaining seconds, discard and finish.

Audio format is dictated by the runtime, not chosen:

| Constraint | Value | Where it comes from |
|---|---|---|
| Channels | mono | native lib: *"Only mono audio is supported."* |
| Sample rate | 16 kHz | conventional for speech; the runtime decodes via miniaudio, which resamples |
| Encoding | 16-bit PCM WAV | miniaudio decodes WAV directly |
| Max length | 30 s (`MediaLimits.MAX_RECORDING_SECONDS`) | see below |

Recording uses `AudioRecord` rather than `MediaRecorder` so the sample rate and channel count are
exactly what we declare, and writes the WAV header itself (`media/Wav.kt`, unit-tested — a wrong
field there fails silently at decode time).

**On the 30 s cap:** the model enforces its own ceiling
(`valid_audio_length <= max_audio_seq_length`), but that value lives in the model file rather than
the library, so it cannot be read ahead of time. 30 s is the conservative side of it. The recorder
stops hard at the cap rather than letting the runtime reject the clip.

## Tuning generation

All four live in `SettingsStore` and are read on the next engine load / message:

| Setting | Default | Notes |
|---|---|---|
| `maxContextTokens` | 4096 | Context window. Sizes the KV cache, so it costs memory. The model supports up to 32k. **Changing it needs an engine reload.** |
| `maxOutputTokens` | 2048 | Ceiling on one reply, so a runaway answer cannot eat the whole context. |
| `repetitionPenalty` | 1.1 | Damps degenerate loops. 1.0 disables. Keep it low — generated code and markup legitimately repeat. |
| `repetitionWindow` | 256 | How many recent tokens the penalty looks at. |

Leaving `maxNumTokens` unset in `EngineConfig` falls back to the runtime's own small default, which
is why it is now set explicitly.

The line above the composer shows real context consumption from `Conversation.getTokenCount()` —
not an estimate. If a chat misbehaves, look there first to tell a genuine context overflow apart
from a sampling problem.

### Degenerate repetition

If output collapses into a repeating fragment (`G4wG4wG4w…`), that is usually **not** context
exhaustion. It happens in high-entropy stretches where the model has no real signal — hallucinated
URLs, hashes, base64, long random IDs — and the loop is self-reinforcing once started. Raise
`repetitionPenalty` toward 1.2, and check the token meter to rule out context before assuming it.

## Where to extend

| Want to add | Start at |
|---|---|
| A different or second model | `model/ModelCatalog.kt` |
| Sampling, system prompt, thinking budget | `LlmService.conversationFor` + `SettingsStore` |
| Sliding-window or summarised context | `LlmService.conversationFor` — it currently replays full history |
| Tool / function calling | `ConversationConfig(tools = ...)` — LiteRT-LM has first-class support |
| Camera capture | `ui/chat/ChatScreen.kt` — only the photo picker is wired up; camera needs a FileProvider |
| Longer voice notes | `media/MediaLimits.kt`, once you know the model's real audio ceiling |
| More Markdown (tables, images, nested quotes) | `ui/chat/Markdown.kt` — parser; `MarkdownText.kt` — renderer |
| Download surviving process death | `ModelManager` runs on an app-scoped coroutine; promote to a foreground service |

## Markdown rendering

Model replies render through a small hand-written renderer in `ui/chat/Markdown.kt` (parser) and
`ui/chat/MarkdownText.kt` (composables). Supported: `#`–`######` headings, `**bold**`, `*italic*`,
`***both***`, `~~strikethrough~~`, `` `inline code` ``, fenced code blocks with a language label
and a copy button, bullet and numbered lists with nesting, blockquotes, `---` rules, and
`[links](url)`.

It is hand-written rather than a library for one reason: it renders text that is **still
arriving**. Half of a `**bold**` span is a normal intermediate state, so every unmatched delimiter
falls back to literal text instead of swallowing the rest of the message, and an unclosed code
fence renders as an open code block. `app/src/test/.../MarkdownTest.kt` covers those cases,
including parsing every prefix of a rich message.

Two deliberate choices worth knowing:

- **Single `_` is never emphasis.** Only `__double__` is. This keeps `max_output_token` and
  `snake_case` intact, which matters when the model is writing code.
- **Single newlines are preserved** rather than collapsed into spaces as strict Markdown would,
  because chat models use them to mean a line break.

## Known limitations

These are deliberate omissions, not bugs:

- Markdown covers the common subset; no tables, images or footnotes
- The renderer re-parses the whole message on every streamed token — fine at chat length, but it
  is the first thing to optimise if very long replies feel sluggish
- Downloads stop if the process is killed (they resume on the next attempt)
- No editing or regenerating messages, no search, no export
- Images come from the gallery only; no camera capture
- One attachment per message
- Light theme only
