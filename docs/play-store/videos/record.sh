#!/bin/zsh
# Records the phone's screen for one of Play's declaration videos and copies it here.
#
#     docs/play-store/videos/record.sh <name> [seconds, at most 180]
#
# Before recording: turn on Do Not Disturb and clear the notification shade, so no one's messages
# end up in a video on YouTube. Stop early with Ctrl-C; what was recorded is kept.
# Set ANDROID_SERIAL when more than one phone is listed by `adb devices`.
set -u
name=${1:?"name the video, e.g. location"}
secs=${2:-120}
adb=${ADB:-adb}
here=${0:A:h}
remote=/sdcard/play-$name.mp4

echo "Recording for up to $secs s. Do the steps on the phone now; Ctrl-C to stop."
trap 'true' INT
$adb shell screenrecord --time-limit "$secs" --bit-rate 8000000 "$remote"
trap - INT
sleep 2  # screenrecord finishes writing the file after it's stopped
$adb pull "$remote" "$here/$name.mp4" && $adb shell rm "$remote"
echo "Saved $here/$name.mp4"
