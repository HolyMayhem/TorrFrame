"""Original quiet TorrFrame startup chord and air textures; no sampled recordings."""
from pathlib import Path
import array, json, math, random, sys, wave
out = Path(sys.argv[1] if len(sys.argv) > 1 else 'artifacts/startup-sound/welcome.wav')
out.parent.mkdir(parents=True, exist_ok=True)
sr, count = 48000, 128000  # exactly 8/3 seconds, matching the supplied 160-frame 60 fps clip
rng = random.Random(20261020)
noise = [0.0, 0.0]
channels = [[], []]

def soft_window(t, start, end, attack=.08, release=.22):
    if not start < t < end: return 0.0
    a = math.sin(min(1, (t-start)/attack)*math.pi/2)**2
    r = math.sin(min(1, (end-t)/release)*math.pi/2)**2
    return a*r

for i in range(count):
    t = i/sr
    for c in range(2):
        # A warm, original A major/add9 voicing. Small stereo differences, no hard transient.
        chord = 0.0
        for j, (hz, level) in enumerate([(220,.7),(329.63,.30),(440,.24),(554.37,.20),(659.25,.15),(987.77,.055)]):
            u = t - .10 - j*.017
            if u > 0:
                chord += level * soft_window(t, .10+j*.017, 1.82, .11, .36) * math.exp(-1.9*u) * (
                    math.sin(2*math.pi*(hz+(c*2-1)*.07)*u) + .025*math.sin(2*math.pi*hz*2*u))
        noise[c] += .075 * (rng.uniform(-1,1)-noise[c])
        air = noise[c]*(soft_window(t,.015,.65,.16,.27)*.075 + soft_window(t,2.12,2.57,.12,.23)*.06)
        tail = .055*soft_window(t,2.13,2.57,.05,.26)*math.sin(2*math.pi*440*(t-2.13))
        value = chord + air + tail
        # Silence around the two-second freeze makes pause/resume inaudible, even on slow hardware.
        if 1.88 <= t <= 2.12: value = 0.0
        channels[c].append(value)
peak = max(abs(x) for channel in channels for x in channel)
scale = .10 / peak  # -20 dBFS peak; deliberately quiet, never normalized by the app
pcm = array.array('h')
for i in range(count):
    for c in range(2): pcm.append(round(channels[c][i]*scale*32767))
if sys.byteorder != 'little': pcm.byteswap()
with wave.open(str(out),'wb') as w:
    w.setparams((2,2,sr,0,'NONE','not compressed'));w.writeframes(pcm.tobytes())
print(json.dumps({'file':str(out),'seconds':count/sr,'peak_dbfs':-20,'freeze_silence':[1.88,2.12],'original_synthesis':True}))
