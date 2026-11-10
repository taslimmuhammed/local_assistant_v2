# Play Console answers

Drafts for Policy and programs → App content, and for the permission forms Play shows when the
bundle is uploaded. Checked against the 0.3.0 (5) release manifest. Anything in [brackets] is
yours to fill in or decide.

## Privacy policy

The public URL of `privacy-policy.md`, once it is online (Google Sites, or a public GitHub repo
with Pages). It has to be reachable without signing in, and not a PDF.

## Ads

No, the app doesn't contain ads.

## App access

"All or some functionality is restricted" → add instructions:

> No account or login. On first launch the app asks a few optional questions, then downloads its
> AI model (Gemma 4 E4B, 3.7 GB) from Hugging Face; please use Wi-Fi. It needs a 64-bit phone
> with 8 GB of RAM or more. After about 15 seconds of loading it answers in the chat.
> To use it from the power button: Settings → Calling the assistant → Power button, and choose
> Local Assistant as the digital assistant app. Web search needs a free Tavily key
> (tavily.com), entered in Settings → Web search; everything else works without one.

## Content rating

Category: All other app types (utility, productivity). Answer No to violence, sexual content,
language, controlled substances, gambling and purchases. Users don't interact with each other
and the app shares no location with other users. If asked about AI-generated content, say yes:
it writes text replies with an AI model that runs on the phone.

## Target audience

18 and over. A younger audience brings in the Families policy.

## Data safety

Play counts data as "collected" when it leaves the phone, even if it goes straight to someone
else and the developer never sees it. Data read and kept only on the phone is not collected.

- Does your app collect or share any of the required user data types? **Yes**
- Is all of the user data collected by your app encrypted in transit? **Yes** (HTTPS only)
- Do you provide a way for users to request that their data is deleted? **No**: nothing is
  kept off the phone; deleting chats or uninstalling deletes everything.

| Data type | Collected | Shared | Ephemeral | Required | Why |
|---|---|---|---|---|---|
| Location → Approximate location | Yes | No | Yes | Optional | App functionality: a place reminder sends the place's name and the rough area around you to OpenStreetMap's Nominatim, and the map loads its tiles from OpenFreeMap |
| App activity → In-app search history | Yes | No | Yes | Optional | App functionality: web searches go to Tavily, with the user's own key |

"Shared" is No for both: the user asks for each search, which Play treats as a user-initiated
transfer rather than sharing.

Not collected (read and kept on the phone only): precise location, contacts, photos and videos,
audio, messages (notifications), health and fitness, calendar (the app's own reminders), files.

## Health apps declaration

Features: Activity and fitness, Sleep management. The app reads Health Connect data only to
answer the user's own questions on the phone.

## Health Connect access (per data type)

| Permission | Why the app needs it |
|---|---|
| Steps, Distance | Answers "how many steps / how far did I walk today?" |
| Total and active calories burned | Answers "how many calories did I burn?" |
| Heart rate, Resting heart rate | Answers "what was my heart rate on my run / my resting heart rate this week?" |
| Sleep | Answers "how did I sleep last night?" |
| Exercise | Answers "what workouts did I do this week?" |

All read only, on request, answered on the phone, never sent anywhere, not used for ads or to
train models. Ask only for the types you keep in version 1.

## Photo and video permissions

READ_MEDIA_IMAGES:

> Photo search is a core feature: the user asks in chat for a photo ("find my driving licence",
> "photos from Munnar", "pictures of Amma") and the app finds it among all their photos. That
> needs persistent access to the whole library, kept indexed on the phone by an on-device model,
> which the one-time photo picker can't provide. Photos never leave the phone.

READ_MEDIA_VIDEO: [same wording if video search stays; otherwise remove the permission from the
manifest and PhotoLibrary, and skip this field]

## Foreground service permissions

| Type | Task | Description for Play |
|---|---|---|
| Location | Place reminders | While a place reminder is set and the app is not the phone's assistant, a foreground service with a visible notification asks Android for location fixes so the reminder rings on arrival with the screen off. It stops when the last place reminder is done. |
| Microphone | Wake word | When the user turns on "Hey Luna" (off by default), a foreground service with a "Listening" notification and a Stop button listens for the phrase on the phone. Audio is never stored or sent. |
| Data sync | [see below] | Comes from Google's LiteRT library (`ExtractionForegroundService`, for its NPU runtime), not from the app. Either declare it as "used by the LiteRT library to unpack downloaded components", or remove it from the manifest and check People in photo search still works. |

Video: one screen recording per type, showing the notification while the feature runs.

## Background location

> Place reminders: the user asks for a reminder at a place ("remind me to buy milk near DMart").
> To ring on arrival when the app is closed, the app needs location all the time. Before asking,
> the app explains this ("Ring when you arrive"). Location is checked on the phone; only the
> place's name and the rough area are sent to OpenStreetMap to find the place.

Video: make a place reminder in the chat, show the explanation and Android's "Allow all the
time" screen, then the reminder ringing.

## Full-screen intent

Choose: alarm functionality. "A place reminder rings like an alarm on a locked phone so the user
notices it on arrival." If Play doesn't grant it, Android 14+ shows a normal notification instead;
the app already handles that.

## Other declarations

- Government app: No. Financial features: None. News app: No.
- Exact alarms: the app uses SCHEDULE_EXACT_ALARM, which the user grants; no declaration (that's
  only for USE_EXACT_ALARM).
