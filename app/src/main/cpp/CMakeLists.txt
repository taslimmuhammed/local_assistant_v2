cmake_minimum_required(VERSION 3.22.1)
project(local_assistant_native C CXX)

# 16 KB pages (Android 15+ devices may use them).
set(PAGE_SIZE_OPTION "-Wl,-z,max-page-size=16384")

# sqlite-vec as a loadable SQLite extension, for BundledSQLiteDriver.addExtension.
#
# Built as its own shared library rather than into SQLite: the bundled driver ships its own
# SQLite, and the extension reaches it through the sqlite3_api_routines table it is handed when
# loaded, so the headers here only need to be no older than the functions the extension calls.
add_library(sqlite_vec SHARED sqlite-vec/sqlite-vec.c)
target_include_directories(sqlite_vec PRIVATE sqlite sqlite-vec)
target_compile_options(sqlite_vec PRIVATE -O3)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(sqlite_vec PRIVATE SQLITE_VEC_ENABLE_NEON)
endif()
target_link_libraries(sqlite_vec m)
target_link_options(sqlite_vec PRIVATE ${PAGE_SIZE_OPTION})

# The wake word model's spectrogram (wake/MicroFrontend.kt): TensorFlow Lite Micro's audio
# frontend with 16-bit fixed-point kissfft, the files and flags pymicro-features builds it from.
set(MICROFRONTEND microfrontend/tensorflow/lite/experimental/microfrontend/lib)
add_library(wake_features SHARED
    wake_features.cpp
    ${MICROFRONTEND}/kiss_fft_int16.cc
    ${MICROFRONTEND}/fft.cc
    ${MICROFRONTEND}/fft_util.cc
    ${MICROFRONTEND}/filterbank.cc
    ${MICROFRONTEND}/filterbank_util.cc
    ${MICROFRONTEND}/frontend.cc
    ${MICROFRONTEND}/frontend_util.cc
    ${MICROFRONTEND}/log_lut.cc
    ${MICROFRONTEND}/log_scale.cc
    ${MICROFRONTEND}/log_scale_util.cc
    ${MICROFRONTEND}/noise_reduction.cc
    ${MICROFRONTEND}/noise_reduction_util.cc
    ${MICROFRONTEND}/pcan_gain_control.cc
    ${MICROFRONTEND}/pcan_gain_control_util.cc
    ${MICROFRONTEND}/window.cc
    ${MICROFRONTEND}/window_util.cc
)
target_include_directories(wake_features PRIVATE microfrontend microfrontend/kissfft)
target_compile_definitions(wake_features PRIVATE FIXED_POINT=16)
target_compile_options(wake_features PRIVATE -O2)
target_link_libraries(wake_features m)
target_link_options(wake_features PRIVATE ${PAGE_SIZE_OPTION})
