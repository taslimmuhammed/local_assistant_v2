TensorFlow Lite Micro's audio frontend (`tensorflow/lite/experimental/microfrontend/lib/`) and
the 16-bit fixed-point kissfft it uses (`kissfft/`), copied unmodified from the pymicro-features
2.0.2 source package (https://pypi.org/project/pymicro-features/2.0.2/, by Michael Hansen). Only
the files the frontend needs are here: no tests, file I/O or command-line tools.

pymicro-features is what microWakeWord computes its training features with, so the wake word
model (`assets/wake/hey_luna.tflite`) hears these exact numbers when the app builds them from the
same files with the same flags (`FIXED_POINT=16`) and settings (`../wake_features.cpp`).

- The frontend: Copyright The TensorFlow Authors, Apache-2.0 (`LICENSE`).
- kissfft: Copyright Mark Borgerding, BSD-3-Clause (in each file's header). The lines marked
  `/* Patched. */` in `kiss_fft.h` are TensorFlow's, as shipped.
