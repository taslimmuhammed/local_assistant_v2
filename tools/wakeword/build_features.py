"""Spectrogram features for microWakeWord training, one stage at a time.
Usage: build_features.py STAGE   (pos | adv | user | speech | ambient)

pos/adv/user: clips augmented with computed room echoes, LibriSpeech speech behind them, coloured
noise, gain and a little distortion, 3.2 s each with the phrase at the end. speech: LibriSpeech
train-clean-100 in 3 s pieces, as heard. ambient: LibriSpeech dev-clean whole, for estimating
false wakes per hour while training (the 10.75 h test set stays unseen for the final check)."""
import glob, os, sys
import numpy as np
from mmap_ninja.ragged import RaggedMmap
from microwakeword.audio.augmentation import Augmentation
from microwakeword.audio.clips import Clips
from microwakeword.audio.spectrograms import SpectrogramGeneration

W = os.environ.get("WAKEWORD_WORK") or sys.exit("Set WAKEWORD_WORK to the working folder (see README.md).")
D = W + "/data"
F = W + "/features"
STAGE = sys.argv[1]
LIBRI = D + "/libri/LibriSpeech"
# Negative speech and the speech behind the phrase: train-clean-100 once downloaded, else the
# LibriSpeech test sets in data/libri-test (SPEECH=test). The final false-wake check is then on
# dev-other, which training never sees.
SPEECH = os.environ.get("SPEECH", "train")
OLD_LIBRI = D + "/libri-test/LibriSpeech"
SPEECH_FILES = (sorted(glob.glob(LIBRI + "/train-clean-100/**/*.flac", recursive=True)) if SPEECH == "train"
                else sorted(glob.glob(OLD_LIBRI + "/test-*/**/*.flac", recursive=True)))


def augmenter(background):
    return Augmentation(
        augmentation_duration_s=3.2,
        augmentation_probabilities={
            "SevenBandParametricEQ": 0.1, "TanhDistortion": 0.05, "PitchShift": 0.1, "BandStopFilter": 0.1,
            "AddColorNoise": 0.25, "AddBackgroundNoise": 0.6, "Gain": 1.0, "GainTransition": 0.1, "RIR": 0.6,
        },
        impulse_paths=[D + "/rirs"], background_paths=background,
        background_min_snr_db=0, background_max_snr_db=15, min_gain_db=-25, max_gain_db=0,
        min_jitter_s=0.195, max_jitter_s=0.205,
    )


def write(out, generator):
    os.makedirs(os.path.dirname(out), exist_ok=True)
    if os.path.exists(out):
        print("exists:", out); return
    # As 16-bit values, half the size of float32 (the Mac was short of disk): the trainer scales
    # uint16 spectrograms by 0.0390625 itself, the inverse of this.
    def as_uint16(gen):
        for spec in gen:
            yield np.clip(np.rint(spec / 0.0390625), 0, 65535).astype(np.uint16)
    RaggedMmap.from_generator(out_dir=out, sample_generator=as_uint16(generator), batch_size=100, verbose=True)


def augmented_sets(name, input_dir, pattern, background, train_repeat=2):
    clips = Clips(input_directory=input_dir, file_pattern=pattern, max_clip_duration_s=None, remove_silence=False,
                  random_split_seed=10, split_count=0.1)
    aug = augmenter(background)
    for split, split_name, repeat, slide in [("training", "train", train_repeat, 10), ("validation", "validation", 1, 10), ("testing", "test", 1, 1)]:
        spec = SpectrogramGeneration(clips=clips, augmenter=aug, slide_frames=slide, step_ms=10)
        write(f"{F}/{name}/{split}/{name}_mmap", spec.spectrogram_generator(split=split_name, repeat=repeat))


# Speech behind the phrase: a few hundred LibriSpeech training files (not the test or dev sets).
background = [D + "/background"]
if not os.path.isdir(D + "/background"):
    os.makedirs(D + "/background")
    for i, f in enumerate(SPEECH_FILES[::12 if SPEECH == "test" else 60]):
        os.symlink(f, f"{D}/background/{i:05d}.flac")
# The user's noisy-room sessions, once there are any, as real background.
if glob.glob(D + "/user/noise/*.wav"):
    background.append(D + "/user/noise")

if STAGE == "pos":
    augmented_sets("pos", D + "/pos", "**/*.wav", background)
elif STAGE == "adv":
    augmented_sets("adv", D + "/adv", "**/*.wav", background)
elif STAGE == "user":
    # The user's own phrases, repeated more: few of them, and the ones that matter most.
    augmented_sets("user", D + "/user/train", "*.wav", background, train_repeat=50)
    if glob.glob(D + "/user/talk/*.wav"):
        clips = Clips(input_directory=D + "/user/talk", file_pattern="*.wav", max_clip_duration_s=None, random_split_seed=None)
        write(f"{F}/user_talk/training/user_talk_mmap",
              SpectrogramGeneration(clips=clips, step_ms=10, split_spectrogram_duration_s=3.0).spectrogram_generator())
elif STAGE == "speech":
    tmp = f"{D}/speech-{SPEECH}"
    if not os.path.isdir(tmp):
        os.makedirs(tmp)
        for i, f in enumerate(SPEECH_FILES): os.symlink(f, f"{tmp}/{i:05d}.flac")
    clips = Clips(input_directory=tmp, file_pattern="*.flac", max_clip_duration_s=None, random_split_seed=None)
    write(f"{F}/speech/training/speech_mmap", SpectrogramGeneration(clips=clips, step_ms=10, split_spectrogram_duration_s=3.0).spectrogram_generator())
elif STAGE == "ambient":
    files = sorted(glob.glob(LIBRI + "/dev-clean/**/*.flac", recursive=True))
    half = len(files) // 2
    for split, part in [("validation_ambient", files[:half]), ("testing_ambient", files[half:])]:
        tmp = f"{D}/ambient-{split}"
        if not os.path.isdir(tmp):
            os.makedirs(tmp)
            for i, f in enumerate(part): os.symlink(f, f"{tmp}/{i:05d}.flac")
        clips = Clips(input_directory=tmp, file_pattern="*.flac", max_clip_duration_s=None, random_split_seed=None)
        write(f"{F}/ambient/{split}/ambient_mmap", SpectrogramGeneration(clips=clips, step_ms=10).spectrogram_generator())
else:
    raise SystemExit("unknown stage " + STAGE)
print("done", STAGE)
