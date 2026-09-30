# embedder_pack

EmbeddingGemma 300M for memory search, shipped with the app on Google Play as a **fast-follow
asset pack**. Play downloads it by itself right after the app installs and unpacks it to the app's
internal storage. The app finds it there (`memory/embed/BundledEmbedder`) and loads it by path,
since LiteRT-LM can only open a model by path. There is nothing to host and no download link.

The chat model (Gemma 4 E4B, 3.66 GB) stays a download on first run. A single asset pack is
limited to 1.5 GB.

## The model file

`src/main/assets/embedders/embeddinggemma-300m_wi8.litertlm` isn't in git (`*.litertlm` is
ignored), so it has to be put there before building a release. Build it from Google's weights
with `tools/embedder` (see its README), or copy it off a phone that has it:

```
adb pull /sdcard/embeddinggemma-300m_wi8.litertlm embedder_pack/src/main/assets/embedders/
```

The name must stay `embeddinggemma-300m_wi8.litertlm`: the app looks for exactly that
(`BundledEmbedder.FILE_NAME`) and treats it as `EmbedderCatalog.EMBEDDING_GEMMA`.

## Licence (Gemma Terms of Use)

EmbeddingGemma comes under the Gemma Terms of Use, and shipping it counts as distributing a
modified Gemma model. Before each release:

- [ ] `NOTICE.txt` sits next to the model in the pack. It holds the required notice line and says
      the file was converted.
- [ ] Add a copy of the Gemma Terms of Use next to it as `GEMMA_TERMS_OF_USE.txt`. Save it from
      https://ai.google.dev/gemma/terms; it isn't checked in here.
- [ ] Put the Gemma Prohibited Use Policy (https://ai.google.dev/gemma/prohibited_use_policy)
      into the app's terms of use as a condition of using it.
- [ ] Settings → Models and licences shows the notice and links in the app.

## Building and testing

Asset packs exist only in an app bundle. `assembleDebug` / `adb install` give an APK without
the pack, and the app then behaves as it always has: keyword-only recall, with the import and
Granite-download options in Settings.

- Release: `./gradlew :app:bundleRelease`, then upload the `.aab`.
- On a connected phone, the way Play would deliver it: `./gradlew :app:installBundleDebug`
  (bundletool's local testing mode, via the bundletool AGP already depends on).
- Before release, on real Play delivery: upload to the internal testing track.
