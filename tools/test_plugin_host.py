"""Host-side smoke test for the Python bridge; does not substitute for an Android test."""
import importlib.util
import json
from pathlib import Path
spec = importlib.util.spec_from_file_location('plugin_host', Path('pluginrunner/src/main/python/plugin_host.py'))
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)
class Bridge:
    def run(self, cmd, timeout):
        return json.dumps({'output': cmd, 'exit_code': 0, 'timeout': timeout})
assert json.loads(host.invoke('def test(args):\n return adb.run(args["cmd"], 3)', 'test', '{"cmd":"id"}', Bridge()))['output'] == 'id'
assert json.loads(host.invoke('def test(args):\n return adb is None', 'test', '{}', None)) is True
try:
    host.invoke('def test(args):\n return "x" * 17000', 'test', '{}', None)
    raise AssertionError('oversized result accepted')
except ValueError:
    pass
print('Python plugin host smoke test passed')
