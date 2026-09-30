plugins {
    alias(libs.plugins.android.asset.pack)
}

// EmbeddingGemma for memory search, shipped with the app instead of downloaded from our own host.
// Fast-follow: Play fetches it by itself right after install, and unpacks it to internal storage,
// where LiteRT-LM can open it by path (an install-time pack stays inside the APK and would have
// to be copied out, storing it twice).
assetPack {
    packName.set("embedder_pack")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}
