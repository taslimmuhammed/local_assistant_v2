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
    ├── AppDatabase (Room)        chats, messages and memory, one file (assistant.db)
    │   ├── ChatRepository        every message written with its session and token estimate
    │   └── MemoryRepository      facts, tombstones, agenda
    ├── ModelManager              download / import / delete the model file
    ├── LlmService                owns the LiteRT-LM Engine
    ├── LiteRtLmBackend           LlmBackend: conversations, streaming, one-shot completions
    ├── ModelScheduler            one model job at a time; the user always first
    ├── ConversationManager       the live conversation, and when to rebuild it
    └── TurnRunner                one user turn: prepare, send, stream
```

### LlmService and the backend

The piece worth understanding before extending anything.

- **One `Engine` per process.** Loading costs seconds and gigabytes, so it is shared across all
  chats. It is kept alive while the app is in use, and released under memory pressure once the
  app is in the background (`onTrimMemory`); coming back loads it again.
- **Everything above the engine talks to `LlmBackend`**, not LiteRT-LM, so another runtime can be
  added later. `LiteRtLmBackend` is the only implementation.
- **One live `Conversation`, for the chat in use.** It holds the KV cache, so continuing the chat
  only prefills the new turn. `ConversationManager` rebuilds it — system prefix plus the last few
  turns through `ConversationConfig.initialMessages` — only at natural boundaries: switching
  chats, after a restart, when a turn was interrupted, or when something outside the chat changed
  what the prefix would say.
- **Interrupted turns invalidate the cache.** If generation is stopped or fails, the native cache
  no longer matches what was persisted, so it is dropped and rebuilt from the database next turn.

Prompt templating, BOS tokens and turn markers are handled inside LiteRT-LM — the app passes plain
strings and roles, never raw template text.

### Prompt layout

The system instruction is byte-stable for a conversation's life: instructions, the "always keep
in mind" block rendered from core facts, a summary carried over from earlier, and an agenda
snapshot dated to the day. Anything that changes per turn — the current time, facts recalled for
this message — goes in front of the user's words in the user turn, as `[Now: …]` and
`[Memory: …]` lines. Only the user's own words are stored.

Every token limit lives in `memory/prompt/MemoryBudget.kt`, with a 16K profile and an 8K one,
chosen from the window calibration measured. The prompt is planned to stay well under the window
(12,000 of 16,384 at most) and shed in a fixed order when it would not: recalled snippets, then
recalled facts, then the summary, then the oldest turns, then core facts by priority — never the
user's response preferences.

### Data

One database, `assistant.db`, on Room's driver API with the bundled SQLite (it can load
extensions, which the vector index will need). `chats` and `messages`, with `messages.chatId`
cascading on delete; a message carries an `incomplete` flag so a stopped reply is stored and shown
as what it is. Memory lives alongside: `sessions` (one foreground period within a chat), `facts`
with an FTS4 keyword index, `forgotten` tombstones, `tasks`, `events`, and `chunks` for the
archive. Nothing in it is included in backup or device-to-device transfer.

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

## Per-reply speed

Each model reply carries a small footnote: `19.4 tok/s · 0.5s to first token`.

The numbers come from the runtime's own `Conversation.getBenchmarkInfo()`, not from timing the
stream from outside. That matters because multi-token prediction emits several tokens per
callback, so counting stream emissions would undercount the decode rate. Enabling it costs only
timing instrumentation (`ExperimentalFlags.enableBenchmark`).

They are stored on the message (`tokensPerSecond`, `timeToFirstTokenMs`), so they survive a
restart, and they are read before the conversation can be recycled — including after a stopped
generation, which still reports what it managed.

## Tuning generation

All four live in `SettingsStore` and are read on the next engine load / message:

| Setting | Default | Notes |
|---|---|---|
| `manualContextTokens` | 0 (auto) | Override the measured window. 0 means calibrate. **Changing it needs an engine reload.** |
| `maxOutputTokens` | 2048 | Ceiling on one reply, also capped by the memory budget's reply reserve. |
| `contextCeilingTokens` | 8192 | Largest window the engine runs at, even where calibration confirmed more. **Needs an engine reload.** |
| `repetitionPenalty` | 1.1 | Damps degenerate loops. 1.0 disables. Keep it low — generated code and markup legitimately repeat. |
| `repetitionWindow` | 256 | How many recent tokens the penalty looks at. |

Leaving `maxNumTokens` unset in `EngineConfig` falls back to the runtime's own small default, which
is why calibration sets it explicitly.

## Startup

`ui/startup/LoadingScreen.kt` sits between "model installed" and the chat. The first load can
genuinely take minutes because measuring the context window means filling it for real, and a bare
spinner is indistinguishable from a hang — so the screen names the current stage, counts elapsed
time, and after 20 seconds offers **Skip — use 4096 tokens** to stop measuring and start chatting.
Failures land here too, with *Try again* and *Measure again from scratch*.

The chat is only composed once the engine reports `Ready`, so nobody waits at an idle composer.

## Context window calibration

Nothing in the runtime reports how large a context the device can hold. `Capabilities` has
`maxVisionTokenBudget()` but no context equivalent, and the real limit depends on device RAM, the
backend, and whether the model's KV cache is preallocated or grown lazily. So the app measures it.

`llm/ContextCalibrator.kt` walks a descending ladder (32768 → 2048, `llm/ContextLadder.kt`),
starting at a rung narrowed by free RAM. A size is only accepted once it has **survived a real
generation that fills the window** — a token or two of warm-up proves nothing if the cache grows
as it goes. The result is cached per model-and-backend and never measured again unless either
changes, or you press **Recalibrate** on the model screen.

Two shortcuts make this cheaper than a blind search:

- Before filling anything, calibration sends a deliberately over-long prompt. The runtime rejects
  it on a length check — cheap — and names its own ceiling in the error
  (`"Exceeding the maximum number of tokens allowed: N"`). That is parsed and the search jumps
  straight there, so the usual path is one load, one cheap question and one confirmation rather
  than a walk down the whole ladder.
- If the confirmation prompt fills far less of the window than we asked for, the runtime silently
  clamped us, and the measured value is used instead of the requested one.

**Measured on a real device.** A 15.5 GB phone confirms **16384** and is killed while filling
24576. Init succeeded at 24576 and the process still died during the fill, which settles the open
question from the design: this model's KV cache grows as the window fills, so `initialize()`
returning is no evidence at all. The confirmation generation is what actually finds the ceiling.

The starting rung is chosen from **total** RAM, not free RAM — Android reclaims cached pages on
demand, so a 15.5 GB phone can report under 3 GB available purely because other apps are warm, and
keying off that made the result a lottery decided by whatever else was open.

**The crash guard.** A native out-of-memory kills the process outright: no exception, no
`finally`. So the size being attempted is written to preferences *before* each attempt — with
`commit`, not `apply`, since an async write may not reach disk before the process dies — and
cleared afterwards. A marker still present at the next launch is the only evidence that a size was
fatal, and the ladder resumes strictly below it. The same guard wraps ordinary generation, so a
real chat that exhausts memory also steps the window down rather than looping on a crash.

An init-time death is unambiguous and rules the size out at once. A death *mid-generation* is
weaker evidence: swiping the app away or force-stopping it while it is replying leaves exactly the
same trace, so the first one only earns a re-check at the same size, and it takes a repeat to
shrink the window. Otherwise closing the app mid-reply would quietly shrink it every time.

This state machine is unit-tested (`CalibrationPlannerTest`) precisely because it exists for the
case where no code of ours gets to run.

**The ceiling.** Calibration finds the most the device can hold; the app deliberately runs below
it, at `contextCeilingTokens` (8192). The LLM shares memory with the embedding model and a voice
model, and the KV cache is sized by the window the engine is loaded with rather than by how full
it gets — measured on the 15.5 GB phone, 16K costs about 200 MB more than 8K and prefills 10–25%
slower. Calibration never probes above the ceiling, and a device already confirmed higher simply
loads at the ceiling.

**Token estimates.** There is no tokenizer API, so prompts are planned from an estimate. Latin
text starts at a pessimistic 3.5 characters per token; after each rebuild the runtime's own count
is compared with what went in, and the Latin rate is learned from it (`MeasuredTokenEstimator`,
bounded between 3.5 and 6, quick to tighten and slow to relax). Indic scripts keep a dense 1.8. If
the runtime ever rejects a prompt as too long, the learned rate is discarded and the turn is
replanned and retried once.

### Overflow

A rebuilt conversation carries only the newest whole turns that fit the budget (at most 12 on the
16K profile); images are charged at the model's own `maxVisionTokenBudget()` and audio by its
length (`llm/ContextWindow.kt`). If a live conversation would pass the prompt ceiling, it is
rebuilt smaller before the turn is sent rather than letting the runtime reject the input. Dropping
is silent by nature, so the chat shows a quiet "N earlier messages no longer fit the context
window" marker.

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
| Sampling, thinking | `llm/LlmBackend.kt` (`Sampling`) + `LiteRtLmBackend.openChat` |
| What goes into the prompt, and its limits | `memory/prompt/PromptAssembler.kt` + `MemoryBudget.kt` |
| Summarising dropped turns instead of discarding | `memory/prompt/ConversationManager.kt` |
| Tool / function calling | `memory/prompt/TurnRunner.kt`; declarations go through `ChatSpec.toolDeclarations` |
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
