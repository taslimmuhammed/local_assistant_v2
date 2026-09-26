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
| Free storage | ~4.2 GB, plus ~0.35 GB for the search model |
| RAM | 8 GB+ recommended (GPU backend peaks around 0.7–1 GB, CPU around 3.3 GB) |
| JDK (to build) | 17 |
| NDK and CMake (to build) | NDK 28.2.13676358, CMake 3.22.1 (SDK Manager), for sqlite-vec |

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
    │   ├── MemoryRepository      facts, tombstones, agenda
    │   └── ArchiveRepository     every exchange as a chunk: keyword index, vectors, backlog
    ├── ModelManager (×2)         download / import / delete the chat model, and the embedder
    ├── LlmService                owns the LiteRT-LM Engine
    ├── LiteRtLmBackend           LlmBackend: conversations, streaming, one-shot completions
    ├── LiteRtEmbedder            the embedding model (LiteRT-LM EmbeddingEngine, CPU)
    ├── ModelScheduler            one model job at a time; the user always first
    ├── EmbeddingQueue            embeds the archive's backlog while the user is idle
    ├── Retriever                 per-turn recall: facts, then keyword + vector search, fused
    ├── ConversationManager       the live conversation, when to rebuild it, rolling compaction
    ├── TurnRunner                one user turn: prepare, send, stream, archive
    ├── SessionSummaries          ends sessions and summarises them (session-end job)
    ├── Consolidation             the nightly job: backlog, summaries, extraction, aliases, upkeep
    └── MemoryControls            what the "What I know about you" screen can see and change
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

### Recall and the archive

Every exchange — the user's message and the first 300 characters of the reply — is stored as a
chunk in `chunks`, keyword-indexed (FTS4) at once and embedded later by `EmbeddingQueue`, only
while the user is neither generating nor typing. Voice notes are never archived; greetings and
"ok thanks" are stored and keyword-searchable but not embedded. Existing chats are archived on the
first start (`ArchiveRepository.backfill`).

Before each turn, `Retriever` recalls, across all chats:

1. nothing at all for trivial messages (fewer than three words, acknowledgements);
2. non-core facts whose subject, alias or text matches the message (`[Memory: …]`);
3. archived exchanges: the keyword top 20 (BM25 from FTS4's `matchinfo`) and the vector top 20,
   leaving out exchanges already in the conversation, fused by reciprocal rank (k = 60, newer wins
   ties). One goes in only if its cosine similarity reaches the model's threshold (0.6 to start)
   or it contains one of the message's rare words — a name, an acronym, a number. They appear
   dated, as `[Memory (3 Sep 2026): …]`, and the instructions say newer beats older and facts beat
   both.

Vectors are the embedder's first 256 dimensions (Matryoshka), L2-normalised and stored as int8 —
256 bytes a chunk. `chunks.embedding` is the source of truth; the search index is derived and
rebuilt whenever it might disagree. That index is [sqlite-vec](https://github.com/asg017/sqlite-vec)
v0.1.9, compiled from source in `src/main/cpp` and loaded into the bundled SQLite as an extension;
on a device where it fails to load, `KotlinVectorIndex` scores the same bytes with the same
arithmetic (`VectorParityTest` checks both return identical neighbours). Vectors from different
models are never compared: changing the embedder sends the archive back to the backlog.

The embedder is optional and installed from the Model screen ("Memory search"). The one to use
is **EmbeddingGemma 300M**: on public per-language benchmarks it retrieves clearly better than the
alternatives in English, Hindi, romanized Hindi, Malayalam, Tamil and Telugu. Google does not
publish it in a form LiteRT-LM's `EmbeddingEngine` loads, so it is built from the original weights
with `tools/embedder` (see its README; about 15 minutes on a Mac) and loaded from storage. As a
no-account alternative the screen downloads
[Granite Embedding 311M multilingual](https://huggingface.co/litert-community/granite-embedding-311m-multilingual-r2)
(332 MB, Apache-2.0). Without either, recall still works on keywords and rare words.

EmbeddingGemma's similarities run lower than the usual 0.6 rule of thumb: calibrated on 60
labelled pairs (`tools/embedder/calibrate_threshold.py`), its inject threshold is 0.42 — 73% of
related pairs recalled at 95% precision. Measured on the target phone:

| | |
|---|---|
| EmbeddingGemma load (once, on CPU) | 2.7 s |
| Embedding the user's message before a reply | 20 ms median, 25 ms p95 |
| Embedding an archived exchange | 38 ms |
| Recall without vectors (FTS, fusion, facts) | 44 ms |
| KNN over 55,000 chunks, k = 40 | 18–31 ms sqlite-vec, 30–51 ms Kotlin |

### Summaries, compaction and the nightly job

Background model work never shares the model with a chat: it asks `ConversationManager` to
close the live conversation first (`withModelFree`), runs one short call at temperature 0.2 with
thinking off, and the next turn rebuilds. Every such job goes through the `ModelScheduler` below
the user's own turns and stops the moment they start typing.

- **Rolling compaction.** After a reply, once the chat has been quiet for 30 seconds, a
  conversation past `compactionTrigger` (4,000 tokens on the 8K profile) — or one rebuilt without
  turns that no summary covers, like a long chat reopened after a restart — has its older turns
  folded into the chat's "Earlier in this chat" summary (≤ 300 tokens), keeping the newest turns
  that land the rebuilt prompt near `compactionTarget`. If a send would overflow before that
  happened, it compacts first and the chat shows "Tidying up…".
- **Session summaries.** Ten minutes after the app goes to the background, a WorkManager job
  ends the sessions that went quiet and summarises them (topics, decisions, open loops, in the
  user's language) if the engine is still loaded; otherwise they wait for the nightly job. The
  newest summary opens later conversations as "Last time you talked". Summaries are context only:
  nothing reads facts out of them.
- **The nightly job** runs on the charger with the phone idle, eight minutes at a time with a
  continuation for the rest, every step resuming where it stopped: embed the backlog, fill pending
  summaries, extract, merge names, SQLite and FTS upkeep, and delete history past the retention
  setting. It skips warm nights.
- **Extraction** reads user messages after a watermark, a batch at a time, and asks for a
  constrained JSON array of facts, tasks and events. Messages whose turn already made a tool call,
  trivial ones, voice notes and paused ones are skipped. The examples in the prompt include what
  must yield nothing (questions, hypotheticals, other people's opinions, moods, sarcasm), and every
  item is checked in code (`ExtractionRouter`): it must point at a message in its batch; facts go
  through the usual upsert rules with the message's own time, so tombstones and the user's edits
  win; only reply preferences may be core; tasks and events need a time still ahead and are not
  added twice.
- **Names.** "amma", "mummy" and "mom" are filed under `mother` when written; the nightly job
  merges any keys that normalise together, and anything that only looks alike ("priya" and
  "priya_sharma") becomes a question on the memory screen rather than a merge.

### Your profile

The first time the app opens it asks, before anything else, for a few optional details: name,
age, work, city, languages and interests (`memory/core/UserProfile.kt`). They are ordinary facts
about the user, the user's own edits and pinned to "Always in mind", so they open every
conversation's system prompt ("About the user (always keep in mind): …") and stay in step with
chat: "I'm 31 now" updates the same row, and "occupation" or "profession" said in a chat files under
Work. Editable any time from "Your profile" in the drawer; clearing a field forgets it.

### Saved images

"Remember this" with a photo — a visiting card, a bill, a Wi-Fi card, a whiteboard — goes to the
`remember_image` tool. It keeps what the model saw in it (a title and details, in its own words) and
a copy of the image in `files/saved_images/`, apart from the chat's attachments, so it outlives the
chat (`memory/notes/SavedImages.kt`, table `notes` with an FTS4 index). The chip's Undo deletes both.

Later questions find it the way recall finds past exchanges: the details' embedding at τ, or one of
the message's rare words. The turn then carries a line — `[Saved image (21 Sep 2026) “Wifi card”:
… It is attached to this message.]` — and the image itself, so the model looks again and can answer
what the details never said. At most one per turn, only when the user attached nothing themselves,
and never one already in front of the model: an image sent in this conversation's window is left
out, and one recalled earlier in the same live conversation is pointed to ("attached earlier in
this chat; look at it again") rather than attached twice. Measured on the phone, a second copy of
the same image garbled long numbers (`1800-209-4455` read back as `18000-29-45555`); one copy read
them right every time. A rebuilt conversation starts over and attaches it again. `search_memory`
lists saved images too.

On the real model (`MemoryEvalTest.aSavedImageIsLookedAtAgainWhenAskedAbout`): a card is saved in
one chat, then a new chat asks for its PIN, its support number and its background colour — 9/9
right over three runs, the colour coming from the image alone.

### What I know about you

From the drawer. Facts grouped as the core memory ("Always in mind", with its token budget), then
by category, each with where it came from (tap for the source message). Tap to edit (it then
belongs to the user, and extraction never overwrites it), pin or unpin, swipe to delete with an
undo. Deleting also empties the archived exchanges that state the fact — its value, alongside the
person's name when it is about someone else — and leaves a tombstone so it is not learned again.
A second tab lists reminders and events, a third the saved images (tap for the full image and
details, swipe to delete with an undo; the file itself goes in the nightly tidy). Possible
duplicates are asked about, never merged silently.

Controls: **Pause memory** (chats go on; nothing new is archived, recalled from, summarised,
extracted or saved by the model — reminders still work), **Keep chat history** (forever, 1 year,
90 or 30 days; applied at once and nightly), **Export as JSON** (saved images as their titles and details, not the files), and **Forget
everything** (asks twice; chats stay, but everything learned from them goes, saved images included,
and they are never re-read).

### Tools and reminders

The model can call up to sixteen tools — `add_task`, `update_task`, `add_event`, `save_fact`,
`get_upcoming`, `search_memory`, `forget`, `set_alarm`, `remember_image`, and the everyday ones
and web search below — declared in `memory/tools/ToolCatalog.kt` with short
routing-style descriptions. Tool calling is manual (`automaticToolCalling = false`): the runtime
reports a call, and `ToolLoop` hands it to `ToolExecutor`, which validates the arguments, ignores
a repeat of the same call within two minutes, applies it and its TOOL-row record in one
transaction, and answers the model with compact JSON (`{"ok":true,"id":42,"due":"Tue 22 Sep 11:00"}`
or `{"ok":false,"error":"…"}` so it asks the user). At most three tool rounds per message.

The model never does date arithmetic. It copies the user's own words ("tomorrow at 11",
"kal subah 11 baje") and `WhenResolver` turns them into a time, deterministically, in the
device's zone. A day with no time ("remind me on Friday") means 8:00 AM that day; a request with no
time words at all is an undated to-do with no alarm. Repeats are an RRULE subset (`RepeatRule`);
finishing a repeating reminder moves it to its next occurrence.

Every change shows as a chip under the reply ("Saved · Dentist: Dr. Rao · Undo"). The TOOL row
holds the state before the change, so Undo works after a restart without a separate table; Edit
moves a reminder or event. Reminders fire through AlarmManager — exact if the user allows exact
alarms, otherwise `setAndAllowWhileIdle` and the chip says it may be a few minutes late — with
Done and Snooze 1 h on the notification, and are set again after a reboot, a clock or time-zone
change, or an update. Events notify 30 minutes before they start (all-day ones at 8:00 AM on the
day), and a repeating event's next alert is set as each one fires. Reminders and event alerts play
the phone's alarm tone, on the notification stream so silent mode is respected; the tone can be
changed in the system's settings for the "Reminders" channel. Notifications are asked for the
first time a timed reminder is made.

Alarms ("wake me up at 5:30", "subah 6 baje ka alarm laga do") are different: `set_alarm` hands
them to the phone's own clock app (`AlarmClock.ACTION_SET_ALARM`, without opening it), where they
ring like any other alarm and are changed or deleted. The clock app only knows times of day, so an
alarm is either one-off within the next 24 hours or repeating on days of the week; anything else
is refused and the model offers a reminder. For alarms a bare hour means whichever comes first —
"5:30" set at night is 5:30 AM — rather than the daytime rule reminders use.

Routing is measured, not assumed: `RoutingEvalTest` sends 62 requests — reminders, reschedules,
facts, events, calls, messages, timers, apps, settings, sums, web lookups, and negatives that must
not call anything — through the real model with the real instructions and all 16 declarations,
three times each with seeds 0–2 (`-e samples 3`). On 26 Sep 2026 it scored 171/186 counting the
calls `ToolCallRepair` fixes (165 without): positives 144/147, negatives 27/39. The misses: "I live
in Bengaluru" answered as a bracketed note instead of `save_fact`, and, with web search on, four
general or personal questions ("what is WhatsApp?") looked up online — harmless but a credit
each. The declarations cost 1,371 tokens and the instructions 627 (809 and 520 with 9 tools).

### Everyday tools

`phone_call`, `send_message`, `set_timer`, `open_app`, `phone_setting` and `calculate`
(`memory/tools/DeviceTools.kt`, Android side in `device/`). The first is not called `call`:
Gemma writes a tool call as `call:<name>{…}`, and the runtime could not parse `call:call`.

- **Calls and messages are prepared, never placed or sent.** "Call amma" opens the dialer with her
  number; "WhatsApp Priya I'm late" opens WhatsApp with the text written (SMS and email the same).
  The user presses the button. A 4B model does misroute now and then, and a call to the wrong
  person can't be taken back — so neither `CALL_PHONE` nor `SEND_SMS` is requested.
- **People are found memory first.** A number saved for someone ("amma's number is …") is used
  as it is, with no contacts access at all. Otherwise the contacts are searched with the words the
  user said, then what memory knows: a name ("my mother's name is Lakshmi"), the names filed on
  the user's own facts ("my dentist" → "Dr. Rao"), merged aliases, and every word for the relation
  ("mom" finds a contact saved as "Amma"). A whole-name match beats a partial one; two people who
  fit equally are asked about ("Priya Sharma (…2222) or Priya Nair (…3333)?"). Contacts access is
  asked for the first time it's needed, through `PermissionBroker`, which shows the system dialog
  from the chat screen and lets the tool wait for the answer.
- **Timers** go to the clock app like alarms (`ACTION_SET_TIMER`, up to 24 h). The length comes
  from the user's own words via `DurationParser` — "1h30m", "an hour and a half", "dus minute",
  "dedh ghanta" — never from the model's arithmetic.
- **open_app** opens an app by its launcher label, or searches in the kind of app that answers:
  directions (Maps), songs (any music app, through the standard play-from-search intent), YouTube,
  the web, the Play Store.
- **phone_setting** switches the flashlight, ringer (silent falls back to vibrate until the user
  allows Do Not Disturb access), media volume and Do Not Disturb. Wi-Fi, Bluetooth, mobile data,
  airplane mode, hotspot and location can't be switched by apps on Android 10+, so their settings
  panel opens and the model says so.
- **calculate** is a small exact evaluator (`Calculator.kt`): `+ - * / ^`, brackets, `18% of
  2450`, `sqrt`, and Indian digit grouping. A 4B model's multi-digit arithmetic is not reliable.
  It also converts units (`Units.kt`): "5 miles in km", "98.6 f to c", "1200 sq ft in sq m",
  "2 cups in ml" — length, weight, volume, area (acres, hectares, cents), speed, time, data and
  temperature.

### Web search

`web_lookup` looks things up through Tavily with the user's own API key, pasted in under "Web
search" in the drawer (free plan: 1,000 searches a month; a basic search is one credit). The key
is stored encrypted with a key held in the Android Keystore (`SecretStore`). Until there is a key
the tool isn't declared at all and costs no context; adding or removing it rebuilds the
conversation when the chat is next idle.

Only the search words the model writes leave the phone. The answer and the top three results
come back cut to about 350 tokens, inside what the budget keeps for tool rounds; the model
answers from them and names the site, and the chip under the reply opens the top source. Being
offline, a refused key and a used-up plan each come back as a plain error the model passes on.

Opening another app's screen only works while this app is in front, so each tool checks and
tells the model when it isn't. A repeat of the same device call within 15 seconds is ignored (a
model that repeats itself mustn't open the dialer twice); a minute later it is a real request.

Now and then the model slips in the call format — a key left out
(`call:add_task{title:<|"|>Renew passport<|"|>, next month<|"|>}`) or quoted with its value
(`<|"|>to:landlord<|"|>`) — and the runtime rejects the call ("Failed to parse tool calls from code
block: …"). The rejected text is in the error, so `ToolCallRepair` reads it leniently, into a
declared tool with declared parameters only (a value with no key goes to the first parameter still
unset). A repaired call to a tool that acts — a reminder, fact, message, timer, setting — is carried
out as usual, chip and undo included, and confirmed with "Done."; the live conversation is then
dropped, since the runtime never recorded that turn, and the next message rebuilds it from what is
stored. Anything else unreadable — a lookup, or a repair that fails validation — is sent once more
with a new seed, before anything is shown.
Constrained decoding (`enableConversationConstrainedDecoding`) would prevent unreadable calls
outright, but measured on the phone it made the first token 2.7× slower and the model stopped
filling optional arguments like `repeat`, so it is off.

Note that the model file reports `supportsFunctionCalling = false`; native tool calls work
regardless (measured in `EngineProbeTest.toolCalling`), so the flag is not trusted.

### Data

One database, `assistant.db`, on Room's driver API with the bundled SQLite, which loads
sqlite-vec for the archive's vector index (`vec_chunks`, created on open where the extension
loads and never referenced by a trigger). Version 5 adds `messages.offRecord` for Pause memory; version 6 adds `notes` (saved images) and
its FTS4 index. `chats` and `messages`, with `messages.chatId`
cascading on delete; a message carries an `incomplete` flag so a stopped reply is stored and shown
as what it is. Memory lives alongside: `sessions` (one foreground period within a chat), `facts`
with an FTS4 keyword index, `forgotten` tombstones, `tasks`, `events`, and `chunks` (with their
own FTS4 index) for the archive. Nothing in it is included in backup or device-to-device transfer.

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
| A new tool | `memory/tools/ToolCatalog.kt` (declaration + routing rule) and `ToolExecutor` |
| Time words the resolver misses | `memory/tools/WhenResolver.kt`, with a row in `WhenResolverTest` |
| Another embedding model | `memory/embed/EmbedderCatalog.kt` (prompts, threshold); `tools/embedder/calibrate_threshold.py`, then `EmbedderProbeTest` on the phone |
| What counts as trivial, or a rare word | `memory/retrieval/QueryText.kt` |
| What extraction looks for | `memory/extract/Extraction.kt` (prompt and examples), `ExtractionRouter` (what may be written) |
| When compaction runs | `MemoryBudget` (`compactionTrigger`, `compactionTarget`, `rollingSummaryCap`) |
| What the nightly job does | `memory/work/BackgroundJobs.kt` (`Consolidation`) |
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
- No editing or regenerating messages, and no search or export of chats (memory can be exported)
- Images come from the gallery only; no camera capture
- One attachment per message
- Light theme only
