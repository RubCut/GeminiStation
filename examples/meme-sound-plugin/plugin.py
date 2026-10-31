"""Original synthesized sad-trombone effect, no audio file or ADB required."""
import math
import struct
import time

RATE = 44100
# Four downward notes; each is (start Hz, end Hz, duration seconds).
NOTES = ((247, 235, 0.27), (220, 210, 0.27), (196, 186, 0.27), (164, 131, 0.66))


def make_pcm():
    """Generate signed 16-bit mono PCM, including short fades to avoid clicks."""
    samples = bytearray()
    phase = 0.0
    for start, end, duration in NOTES:
        count = int(RATE * duration)
        attack = int(RATE * 0.035)
        release = int(RATE * min(0.12, duration / 2))
        for i in range(count):
            t = i / RATE
            frequency = start + (end - start) * i / max(count - 1, 1)
            frequency *= 1.0 + 0.012 * math.sin(2 * math.pi * 5.2 * t)
            phase += 2 * math.pi * frequency / RATE
            envelope = min(1.0, i / attack, (count - i - 1) / release)
            # A few harmonics and a slow "wah" make a comic brass-like tone.
            wah = 0.5 + 0.5 * math.sin(2 * math.pi * 3.0 * t)
            wave = (math.sin(phase) + 0.30 * math.sin(2 * phase) * wah
                    + 0.16 * math.sin(3 * phase) * wah)
            value = int(11500 * envelope * wave / 1.46)
            samples.extend(struct.pack('<h', value))
    return bytes(samples)


def sad_trombone(args):
    # Imported lazily so the code can be checked outside Android. Chaquopy bridges
    # to Android's AudioTrack; this plugin never calls adb or accesses the network.
    from java import jarray, jbyte
    from android.media import AudioFormat, AudioManager, AudioTrack

    pcm = make_pcm()
    frames = len(pcm) // 2
    minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                                          AudioFormat.ENCODING_PCM_16BIT)
    if minimum <= 0:
        return {"ok": False, "message": "Аудиовыход не поддерживает нужный формат."}
    track = None
    try:
        size = max(len(pcm), minimum + minimum % 2)
        track = AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                           AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STATIC)
        data = jarray(jbyte)(pcm)
        written = 0
        while written < len(pcm):
            count = track.write(data, written, len(pcm) - written)
            if count <= 0:
                return {"ok": False, "message": "Не удалось подготовить звук."}
            written += count
        if track.getState() != AudioTrack.STATE_INITIALIZED:
            return {"ok": False, "message": "Аудиовыход не инициализировался."}
        track.play()
        deadline = time.monotonic() + 5.0
        while track.getPlaybackHeadPosition() < frames:
            if time.monotonic() >= deadline:
                return {"ok": False, "message": "Не удалось подтвердить воспроизведение звука."}
            time.sleep(0.02)
        return {"ok": True, "message": "Мемный звук отправлен на аудиовыход станции."}
    except Exception:
        return {"ok": False, "message": "Не удалось воспроизвести звук на станции."}
    finally:
        if track is not None:
            try:
                track.stop()
            except Exception:
                pass
            try:
                track.release()
            except Exception:
                pass
