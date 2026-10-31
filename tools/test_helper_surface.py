"""Guard against accidentally exposing the internal shell bridge to model or web inputs."""
from pathlib import Path

app = Path('app/src/main/java/ru/geministation')
server = (app / 'WebServerService.java').read_text()
client = (app / 'AdbHelper.java').read_text()
tools = (app / 'DeviceToolSpec.java').read_text()
manifest = Path('app/src/main/AndroidManifest.xml').read_text()
assert 'MediaAccessService' not in manifest
assert 'media_seek' not in tools
assert 'AdbHelper.run(' not in server
assert server.count('AdbHelper.probe(context)') == 1
assert 'if (!"{}".equals(files.get("postData")))' in server
assert 'run(context, "id -u", 3)' in client
for path in app.glob('*.java'):
    if path.name != 'AdbHelper.java':
        assert 'AdbHelper.run(' not in path.read_text(), path
print('Helper command surface: internal-only; fixed probe')
