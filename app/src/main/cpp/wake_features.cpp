// The spectrogram the "Hey Luna" model listens to: TensorFlow Lite Micro's audio frontend, set up
// exactly as pymicro-features 2.0.2 sets it up (src/micro_features.cpp there), which is what
// microWakeWord computed the training features with. Same code, same settings, so the app's
// features are the training ones bit for bit. Called from wake/MicroFrontend.kt.
#include <jni.h>

#include <cstdlib>
#include <cstring>

extern "C" {
#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
#include "tensorflow/lite/experimental/microfrontend/lib/frontend_util.h"
}

namespace {

constexpr int kSampleRate = 16000;

struct Frontend {
    FrontendConfig config;
    FrontendState state;
};

void configure(FrontendConfig *c) {
    c->window.size_ms = 30;
    c->window.step_size_ms = 10;
    c->filterbank.num_channels = 40;
    c->filterbank.lower_band_limit = 125.0f;
    c->filterbank.upper_band_limit = 7500.0f;
    c->noise_reduction.smoothing_bits = 10;
    c->noise_reduction.even_smoothing = 0.025f;
    c->noise_reduction.odd_smoothing = 0.06f;
    c->noise_reduction.min_signal_remaining = 0.05f;
    c->pcan_gain_control.enable_pcan = 1;
    c->pcan_gain_control.strength = 0.95f;
    c->pcan_gain_control.offset = 80.0f;
    c->pcan_gain_control.gain_bits = 21;
    c->log_scale.enable_log = 1;
    c->log_scale.scale_shift = 6;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_local_assistant_wake_MicroFrontend_nativeCreate(JNIEnv *, jobject) {
    auto *f = static_cast<Frontend *>(calloc(1, sizeof(Frontend)));
    if (f == nullptr) return 0;
    configure(&f->config);
    if (!FrontendPopulateState(&f->config, &f->state, kSampleRate)) {
        free(f);
        return 0;
    }
    return reinterpret_cast<jlong>(f);
}

// Feeds `count` samples; writes each finished frame's 40 values (0..65535) into `out`, one frame
// after another, and returns how many frames it wrote. `out` has room for count / 160 + 1 frames.
extern "C" JNIEXPORT jint JNICALL
Java_com_local_assistant_wake_MicroFrontend_nativeProcess(
        JNIEnv *env, jobject, jlong handle, jshortArray samples, jint count, jintArray out) {
    auto *f = reinterpret_cast<Frontend *>(handle);
    jshort *pcm = env->GetShortArrayElements(samples, nullptr);
    jint *values = env->GetIntArrayElements(out, nullptr);
    const jsize room = env->GetArrayLength(out);
    const int16_t *next = pcm;
    size_t left = static_cast<size_t>(count);
    jint frames = 0;
    while (left > 0) {
        size_t read = 0;
        FrontendOutput o = FrontendProcessSamples(&f->state, next, left, &read);
        next += read;
        left -= read;
        if (o.size > 0 && (frames + 1) * static_cast<jsize>(o.size) <= room) {
            for (size_t i = 0; i < o.size; ++i) values[frames * o.size + i] = o.values[i];
            ++frames;
        }
        if (read == 0) break;
    }
    env->ReleaseIntArrayElements(out, values, 0);
    env->ReleaseShortArrayElements(samples, pcm, JNI_ABORT);
    return frames;
}

extern "C" JNIEXPORT void JNICALL
Java_com_local_assistant_wake_MicroFrontend_nativeReset(JNIEnv *, jobject, jlong handle) {
    FrontendReset(&reinterpret_cast<Frontend *>(handle)->state);
}

extern "C" JNIEXPORT void JNICALL
Java_com_local_assistant_wake_MicroFrontend_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto *f = reinterpret_cast<Frontend *>(handle);
    FrontendFreeStateContents(&f->state);
    free(f);
}
