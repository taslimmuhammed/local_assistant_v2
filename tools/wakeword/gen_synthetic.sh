#!/bin/bash
# Synthetic training audio for the "Hey Luna" model, and the LibriSpeech downloads, in parallel.
W="${WAKEWORD_WORK:?Set WAKEWORD_WORK to the working folder (see README.md)}"
T=$(cd "$(dirname "$0")" && pwd)
PY="$W/train-venv/bin/python"
cd "$W/piper-sample-generator" || exit 1

# LibriSpeech (CC BY 4.0): 100 h of training negatives, and the dev set for choosing the best weights.
mkdir -p "$W/data/libri"
[ -n "$SKIP_DOWNLOAD" ] || ( cd "$W/data/libri" && for n in dev-clean train-clean-100; do
    [ -f "$n.done" ] || { curl -sL -o "$n.tar.gz" "https://www.openslr.org/resources/12/$n.tar.gz" && tar xzf "$n.tar.gz" && rm "$n.tar.gz" && touch "$n.done"; }
  done; echo "librispeech done" ) &

gen() {  # name count [--phoneme-input] text
  local name=$1 n=$2; shift 2
  [ -d "$W/data/$name" ] && [ "$(ls "$W/data/$name" | wc -l)" -ge "$n" ] && return
  mkdir -p "$W/data/$(dirname "$name")"
  "$PY" "$T/gen_piper.py" "$@" --model models/en_US-libritts_r-medium.pt --max-samples "$n" --batch-size 50 \
    --max-speakers 800 --length-scales 0.8 0.95 1.1 1.3 --noise-scales 0.5 0.667 0.8 \
    --output-dir "$W/data/$name" > "$W/data/$name.log" 2>&1
  echo "$name: $(ls "$W/data/$name" | wc -l)"
}
mkdir -p "$W/data"
# Positives: the text two ways, and spelled in sounds the way the user's voice came out.
gen pos/text 3000 "hey luna"
gen pos/text-comma 1000 "hey, luna"
i=0
for p in "hˈeɪ lˈuːnə" "hˈeɪ lˈuːnɑː" "hˈe lˈuːnaː" "hˈeɪ lˈʊnə" "hˈeɪ lˈʊŋnɑ" "hˈeɪ lˈunɑ" "ˈeɪ lˈuːnə" "hˈeɪ lˈuːnɐ" "hˈɛ lˈuːnə"; do
  i=$((i + 1)); gen "pos/ipa$i" 1000 --phoneme-input "$p"
done
# Sound-alikes: what it must not wake on.
for t in "hey lorna" "hey lena" "hey nora" "hey lunar" "hey luke" "hey lou" "hey long" "hey lunch" "hey june" "hey luca" \
         "hey laura" "hey lily" "hey you" "hey there" "hey google" "hey lona" "hey logan" "hey lungi" "lunar eclipse" "hey look now" \
         "hey loan me" "hey, long time" "hey hold on" "hey lady" "a luna moth"; do
  gen "adv/$(echo "$t" | tr -cd 'a-z ' | tr ' ' '_')" 250 "$t"
done
wait
echo "ALL DONE"
