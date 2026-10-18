#!/bin/bash
# Original generated media only. Host FFmpeg is a test tool, not an app dependency.
set -euo pipefail
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TASK_VIDEO_DIR="$TASK_ROOT/.tools/fixture/player"
TASK_FFMPEG=${PLAYER_FIXTURE_FFMPEG:-ffmpeg}
mkdir -p "$TASK_VIDEO_DIR"
xcrun swift "$TASK_ROOT/scripts/test-fixture/generate-video.swift" "$TASK_VIDEO_DIR/video.mp4"
python3 - "$TASK_VIDEO_DIR" <<'PY'
import wave, math, struct, sys
from pathlib import Path
folder = Path(sys.argv[1])
for name, frequency in [('english',440),('russian',660)]:
    with wave.open(str(folder/(name+'.wav')), 'wb') as audio:
        audio.setnchannels(1); audio.setsampwidth(2); audio.setframerate(48000)
        tone = b''.join(struct.pack('<h', int(1000*math.sin(2*math.pi*frequency*i/48000))) for i in range(48000))
        for _ in range(30): audio.writeframes(tone)
(folder/'subtitles.srt').write_text('1\n00:00:00,000 --> 00:00:29,000\nTorrFrame test subtitles\n', encoding='utf8')
PY
"$TASK_FFMPEG" -y -i "$TASK_VIDEO_DIR/video.mp4" -i "$TASK_VIDEO_DIR/english.wav" \
 -i "$TASK_VIDEO_DIR/russian.wav" -i "$TASK_VIDEO_DIR/subtitles.srt" \
 -map 0:v -map 1:a -map 2:a -map 3:s -c:v copy -c:a ac3 -c:s copy \
 -metadata:s:a:0 language=eng -metadata:s:a:0 title=English \
 -metadata:s:a:1 language=rus -metadata:s:a:1 title=Russian \
 -metadata:s:s:0 language=eng "$TASK_ROOT/android-tv/app/src/androidTest/assets/player-multitrack.mkv"
