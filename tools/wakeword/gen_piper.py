"""Runs piper_sample_generator with piper-phonemize's espeak in place of the piper package's,
whose macOS build looks for its data at a path baked in on the build machine. Same arguments as
`python -m piper_sample_generator`."""
import os, runpy, sys

# piper_train sits beside piper_sample_generator in its repository, not installed.
sys.path.insert(0, os.path.join(os.environ["WAKEWORD_WORK"], "piper-sample-generator"))
import piper_phonemize
import piper.phonemize_espeak as pe


class Phonemizer:
    def __init__(self, *args, **kwargs):
        pass

    def phonemize(self, voice, text):
        return piper_phonemize.phonemize_espeak(text, voice)


pe.EspeakPhonemizer = Phonemizer
sys.argv = ["piper_sample_generator"] + sys.argv[1:]
runpy.run_module("piper_sample_generator", run_name="__main__")
