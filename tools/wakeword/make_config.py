"""Writes training_parameters.yaml from whichever feature sets exist. Usage: make_config.py RUN_NAME STEPS"""
import os, sys, yaml
W = os.environ.get("WAKEWORD_WORK") or sys.exit("Set WAKEWORD_WORK to the working folder (see README.md).")
F = W + "/features"
run, steps = sys.argv[1], int(sys.argv[2])
sets = [
    # name, truth, sampling weight, penalty weight, truncation
    ("pos", True, 2.0, 1.0, "truncate_start"),
    ("user", True, 4.0, 1.0, "truncate_start"),       # the user's own phrases, twice as often as all the synthetic ones
    ("adv", False, 3.0, 1.0, "truncate_start"),       # sound-alikes
    ("speech", False, 5.0, 1.0, "random"),            # LibriSpeech test sets, 10.75 h
    ("speech100", False, 12.0, 1.0, "random"),        # LibriSpeech train-clean-100
    ("user_talk", False, 3.0, 1.0, "random"),         # the user talking normally
    ("ambient", False, 0.0, 1.0, "split"),            # validation/testing only: false wakes per hour
]
features = [{"features_dir": f"{F}/{n}", "truth": t, "sampling_weight": s, "penalty_weight": p,
             "truncation_strategy": tr, "type": "mmap"} for n, t, s, p, tr in sets if os.path.isdir(f"{F}/{n}")]
config = {
    "window_step_ms": 10,
    "train_dir": f"{W}/trained/{run}",
    "features": features,
    # Two stages: the second at a tenth of the rate, to settle the swings of the first run.
    "training_steps": [steps, steps],
    "positive_class_weight": [1, 1],
    "negative_class_weight": [20, 20],
    "learning_rates": [0.001, 0.0001],
    "batch_size": 128,
    "time_mask_max_size": [5, 5], "time_mask_count": [2, 2],
    "freq_mask_max_size": [5, 5], "freq_mask_count": [2, 2],
    "eval_step_interval": 500,
    "clip_duration_ms": 1500,
    # The user would rather it woke too often than missed: keep false wakes under 2 an hour of
    # speech, then hear as much as possible.
    "target_minimization": 2.0,
    "minimization_metric": "ambient_false_positives_per_hour",
    "maximization_metric": "average_viable_recall",
}
yaml.dump(config, open(f"{W}/training_parameters-{run}.yaml", "w"))
print("sets:", [f["features_dir"].split("/")[-1] for f in features])
