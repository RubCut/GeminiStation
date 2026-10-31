"""Contract and playback-bridge tests; Android hardware playback is separate."""
import importlib.util
import json
import struct
import sys
import types
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
source = root / 'examples/meme-sound-plugin'
with ZipFile(root / 'examples/meme-sound-plugin.zip') as archive:
    assert archive.namelist() == ['plugin.json', 'plugin.py']
    for name in archive.namelist():
        assert archive.read(name) == (source / name).read_bytes()
    manifest = json.loads(archive.read('plugin.json'))
    assert manifest['permissions'] == []
    assert len(manifest['tools']) == 1
    assert manifest['tools'][0]['handler'] == 'sad_trombone'
    assert manifest['tools'][0]['parameters']['properties'] == {}

spec = importlib.util.spec_from_file_location('meme_plugin', source / 'plugin.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
pcm = module.make_pcm()
values = struct.unpack('<' + 'h' * (len(pcm) // 2), pcm)
assert 1.4 < len(values) / module.RATE < 1.6
assert values[0] == values[-1] == 0
assert max(values) > 1000 and min(values) < -1000
assert max(map(abs, values)) < 15000

# Stand in for the Chaquopy/Android APIs to exercise construction, short writes,
# playback completion and release. It does not validate real-device audio.
class Format:
    CHANNEL_OUT_MONO = 4
    ENCODING_PCM_16BIT = 2

class Manager:
    STREAM_MUSIC = 3

class Track:
    MODE_STATIC = 0
    STATE_INITIALIZED = 1
    current = None
    fail_write = False
    @staticmethod
    def getMinBufferSize(rate, channel, encoding):
        assert rate == 44100 and channel == 4 and encoding == 2
        return 4096
    def __init__(self, stream, rate, channel, encoding, size, mode):
        assert stream == 3 and mode == 0 and size >= len(pcm)
        self.written = 0
        self.played = self.stopped = self.released = False
        Track.current = self
    def write(self, data, offset, length):
        if self.fail_write:
            return -1
        assert offset == self.written
        count = min(length, 8192)
        self.written += count
        return count
    def getState(self):
        return self.STATE_INITIALIZED
    def play(self):
        assert self.written == len(pcm)
        self.played = True
    def getPlaybackHeadPosition(self):
        return self.written // 2
    def stop(self):
        self.stopped = True
    def release(self):
        self.released = True

java = types.ModuleType('java')
java.jbyte = object()
java.jarray = lambda kind: lambda data: data
android = types.ModuleType('android')
media = types.ModuleType('android.media')
media.AudioFormat, media.AudioManager, media.AudioTrack = Format, Manager, Track
sys.modules.update({'java': java, 'android': android, 'android.media': media})
assert module.sad_trombone({})['ok'] is True
assert Track.current.played and Track.current.stopped and Track.current.released
Track.fail_write = True
assert module.sad_trombone({})['ok'] is False
assert not Track.current.played and Track.current.released

# Verify that the actual ZIP runs through the same host dispatcher as plugins.
spec = importlib.util.spec_from_file_location('plugin_host', root / 'pluginrunner/src/main/python/plugin_host.py')
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)
Track.fail_write = False
with ZipFile(root / 'examples/meme-sound-plugin.zip') as archive:
    result = json.loads(host.invoke(archive.read('plugin.py').decode('utf-8'), 'sad_trombone', '{}', None))
assert result['ok'] is True
print('Meme plugin ZIP, audio generation and mocked Android playback passed')
