#!/bin/bash
# Trains one microWakeWord MixedNet run and converts it to the quantized streaming TFLite model.
# Usage: train.sh RUN_NAME STEPS
W="${WAKEWORD_WORK:?Set WAKEWORD_WORK to the working folder (see README.md)}"
T=$(cd "$(dirname "$0")" && pwd)
RUN=$1; STEPS=${2:-20000}
cd "$W" || exit 1
train-venv/bin/python "$T/make_config.py" "$RUN" "$STEPS"
# The architecture of the published v2 models (the notebook's settings).
train-venv/bin/python -m microwakeword.model_train_eval \
  --training_config="training_parameters-$RUN.yaml" \
  --train 1 --restore_checkpoint 1 \
  --test_tf_nonstreaming 0 --test_tflite_nonstreaming 0 --test_tflite_nonstreaming_quantized 0 \
  --test_tflite_streaming 0 --test_tflite_streaming_quantized 1 \
  --use_weights "best_weights" \
  mixednet \
  --pointwise_filters "64,64,64,64" --repeat_in_block "1, 1, 1, 1" \
  --mixconv_kernel_sizes '[5], [7,11], [9,15], [23]' --residual_connection "0,0,0,0" \
  --first_conv_filters 32 --first_conv_kernel_size 5 --stride 3 \
  > "$W/train-$RUN.log" 2>&1
echo "exit $?"
ls -la "$W/trained/$RUN/tflite_stream_state_internal_quant/" 2>/dev/null
