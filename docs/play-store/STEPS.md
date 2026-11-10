# Publishing Local Assistant on Google Play, step by step

The app is `com.taslim.localassistant`, version 0.3.0 (5). The texts to paste are in
[listing.md](listing.md) and [console-answers.md](console-answers.md), the images in
[graphics/](graphics/), and the privacy policy in [privacy-policy.md](privacy-policy.md).

Builds from Android Studio or `assembleDebug` now install as a separate app,
`com.taslim.localassistant.dev`, so they never clash with the copy from Play. The old
`com.local.assistant` on your phone stays as it is, with your chats; remove it yourself when
you've moved over (nothing carries across between them).

## A. On the Mac (once)

1. **Push the code.** The privacy policy's link only works once it's on GitHub:
   https://github.com/taslimmuhammed/local_assistant_v2/blob/main/docs/play-store/privacy-policy.md
2. **Make the form that "Report this reply" sends to.** Signed in as developertaslim@gmail.com,
   so reports land with your other user mail:
   1. Go to forms.google.com → Blank form. Title: "Local Assistant reply reports".
   2. Add five questions, each of type **Paragraph**, none required: Reason, Question, Reply,
      Note, Version.
   3. Settings → Responses: **Collect email addresses: Do not collect**. Leave "Limit to 1
      response" off. Nothing should ask people to sign in.
   4. Publish the form (button at the top right), so anyone with the link can answer.
   5. Responses tab → Link to Sheets, so reports collect in a spreadsheet. Then Responses tab →
      ⋮ → **Get email notifications for new responses**, so each report also arrives in
      developertaslim@gmail.com's inbox.
   6. ⋮ menu → **Get pre-filled link**. In the five boxes type exactly `reason`, `question`,
      `reply`, `note`, `version`, then Get link → Copy link.
   7. Send that link to Claude, or paste it into `FORM_LINK` in
      `app/src/main/java/com/local/assistant/chat/ReplyReporter.kt`. Until then the report sheet
      says reporting isn't set up, and Play would reject the app for it.
3. **Make the upload key**, in the project folder. Choose the passwords yourself:

   ```
   keytool -genkeypair -v -keystore upload-key.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```

   Copy `keystore.properties.example` to `keystore.properties` and fill in the passwords. Keep a
   copy of `upload-key.jks` and the passwords somewhere safe outside this folder (a password
   manager). Git ignores both files. If the key is lost, Play support can reset it, but it takes
   days.
4. **Build the bundle:** `./gradlew :app:bundleRelease`. The file to upload is
   `app/build/outputs/bundle/release/app-release.aab` (about 113 MB; phones download about 59 MB).
5. **Tag the commit** you built from: `git tag v0.3.0 && git push origin v0.3.0`. The app is
   under the GPL, so the source of every version people install has to stay public.

## B. Play Console, signed in as taslimmuhammed67@gmail.com

6. **Create app:** name "Local Assistant", default language English, App, Free. Tick the
   declarations.
7. **Dashboard → Set up your app.** Each item, from [console-answers.md](console-answers.md):
   - Privacy policy: the link in step 1.
   - App access: "All or some functionality is restricted", paste the instructions.
   - Ads: No.
   - Content rating: the questionnaire, as written there.
   - Target audience: 18 and over.
   - Data safety: the table there. Advertising ID: No (the app doesn't use it).
   - Government apps: No. Financial features: none. Health apps: Activity and fitness, Sleep.
   - News app: No.
8. **Store listing** (Grow → Store presence → Main store listing): name, short and full
   description from [listing.md](listing.md); the icon, feature graphic and screenshots from
   [graphics/](graphics/); category Productivity; contact email developertaslim@gmail.com (also
   set it in Settings → Developer account → Account details as the email users see).
9. **Who can install it** (Release → Device catalog → Device exclusion rules): exclude phones
   with **less than 7 GB of RAM**. Phones sold as 8 GB report about 7.3 to 7.7 GB, and the
   model doesn't fit in less. 32-bit phones are already left out (the app has 64-bit code only).
10. **Internal testing first** (Test and release → Testing → Internal testing):
    1. Create a release. Accept **Play App Signing** (Google keeps the key that signs what
       people install; yours only signs uploads).
    2. Upload `app-release.aab`. Release notes: "First test release."
    3. Testers: add your own Gmail. Open the opt-in link on your phone and install from Play.
    4. On the phone: open it, let it download the model, and choose it as the digital assistant
       (Settings → Calling the assistant). Try a chat, the flag under a reply (send a test
       report and check it reaches the Sheet), a place reminder and "Hey Luna".
11. **Permission forms.** Uploading the bundle makes Play ask for these (App content →
    Sensitive permissions and APIs). Texts are in [console-answers.md](console-answers.md):
    - Health Connect: one reason per data type.
    - Photo and video permissions.
    - Foreground service: Location and Microphone, each with a video link (step 12).
    - Background location: text plus a video link (step 12).
    - Full-screen intent: Alarm.
12. **Record the three videos** (steps in section C), upload each to YouTube as **Unlisted**,
    and paste the links into the forms in step 11.
13. **Closed testing, 14 days.** A personal developer account made after 13 November 2023 has to
    run a closed test with at least **12 testers who stay opted in for 14 days in a row** before
    it can publish:
    1. Test and release → Testing → Closed testing → Create track, upload the same bundle (or
       promote the internal release).
    2. Add testers: a Google Group (easiest) or a list of Gmail addresses. Countries: India and
       any others you want.
    3. Share the opt-in link. Each tester taps "Become a tester", installs from Play, and keeps
       it installed. Their phones need 8 GB of RAM, or Play won't let them install (step 9).
    4. Ask them to use it a few times and send feedback; Play asks about this later.
14. **Apply for production** (Dashboard → Apply for production) once the 14 days are up. It asks
    how testing went, what feedback you got and what you changed.
15. **Production release:** create it from the tested bundle, choose countries, send for review.
    The first review can take up to a week. Answer any policy email from Play within the time it
    gives.

## C. The three videos

Record each on the phone with `docs/play-store/videos/record.sh <name>` (up to 3 minutes each)
while doing the steps. First turn on Do Not Disturb and clear the notification shade, so no one's
messages show. Use the Play-installed app (or the `.dev` one; its name on screen is the same).

1. **location** (foreground service: location). Make the app *not* the digital assistant for
   this one (the place service only runs when it isn't), then:
   in a chat say "Remind me to buy milk when I reach DMart" → the app's "Ring when you arrive"
   explanation → Allow → "Allow all the time" in Android's screen → back in the app the
   reminder is set → pull down the shade to show the app's "Watching for your place reminders"
   notification.
2. **background-location**: the same recording can serve, since it shows the explanation, the
   "Allow all the time" choice and the feature using it. Play wants to see the prominent
   disclosure before the permission prompt.
3. **microphone** (foreground service: microphone): Settings → Calling the assistant → turn on
   "Hey Luna" → allow the microphone → pull down the shade to show "Say “Hey Luna” to talk"
   with its "Stop listening" button → close the shade, say "Hey Luna", and ask something.

## D. Later versions

- Raise `versionCode` (and `versionName`) in `app/build.gradle.kts` each time.
- Build with `./gradlew :app:bundleRelease`, tag the commit, push the tag, upload to the
  internal track, then promote it.
