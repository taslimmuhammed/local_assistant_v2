# Store listing

Paste into Play Console → Grow → Store presence → Main store listing. Lengths are Play's limits.

## App name (30)

Local Assistant

## Short description (80)

A private AI assistant that runs on your phone. No account, no cloud.

## Full description (4,000)

Local Assistant is an AI assistant that lives on your phone. The AI model runs on the phone itself, so your chats, what it remembers and your voice stay with you. No account, no ads, no tracking.

ANSWERS FROM THE POWER BUTTON
Make it your phone's digital assistant and hold the power button over any app. It listens, sends when you pause, and answers out loud. Turn on "Hey Luna" to call it hands-free; the wake word is heard on the phone and nothing it hears is kept.

REMEMBERS YOU
Tell it about yourself once. It remembers facts you mention ("my dentist is Dr. Rao"), notes and lists, and what you talked about in earlier chats, and brings them up when they matter.

GETS THINGS DONE
• Reminders and events, alarms and timers
• Place reminders that ring when you arrive ("remind me to buy milk near the supermarket")
• Calls and messages: it opens your dialer or messaging app ready to send
• Opens apps, turns on the flashlight or silent mode, and does sums
• Notes and lists you can add to by voice

KNOWS WHAT YOU ASK ABOUT
• Your photos: find them by what's in them, by place, or by who's in them
• Your screen: open it over an app and ask about what you see
• Your health: steps, sleep, heart rate and workouts from Health Connect
• The web, once you add your own free Tavily key

WHILE YOU WERE AWAY
Optional: after you unlock, a short summary of the messages you missed, with replies you confirm before they're sent.

PRIVATE BY DESIGN
• The model runs on your phone, and what it learns about you stays there
• The internet is used only to download the model and voice, for web searches you ask for, to look up a place's name for a place reminder, and to send a reply you choose to report
• It asks before reading your contacts, photos, health data or location, and the wake word and message summaries are off until you turn them on

WHAT IT NEEDS
• A 64-bit phone with 8 GB of RAM or more, Android 8 or newer
• About 5 GB free: the model is a one-time 3.7 GB download on first run (use Wi-Fi)

## Category and contact

- App category: Productivity
- Tags: Personal assistant, Productivity, AI
- Email: developertaslim@gmail.com (shown publicly on the listing)
- Website: optional
- Privacy policy: https://github.com/taslimmuhammed/local_assistant_v2/blob/main/docs/play-store/privacy-policy.md (works once pushed)

## Graphics

All in `graphics/`, made by `graphics/make.py` from docs/screens:

- App icon: `icon-512.png`
- Feature graphic: `feature-graphic.png` (1024 × 500)
- Phone screenshots: `screenshot-1-home.png` … `screenshot-8-drawer.png` (1080 × 1920, eight is
  Play's most), in that order. The phone's own screenshots are too tall for Play (more than
  twice as tall as wide), so each sits on a card under a caption; three are welcome tour pages,
  which have their own headings.
- Tablet screenshots, taken as they are (not made by make.py): `tablet-7in/` (1080 × 1920) and
  `tablet-10in/` (1440 × 2560), the eight welcome tour pages on 16:9 tablet emulators held
  upright, with no model on them. Upload each folder to its own slot, 1 to 8.
- Video: optional. `promo/LocalAssistant_Promo_720p.mp4`, uploaded to YouTube (public or
  unlisted, ads off), can go here.
