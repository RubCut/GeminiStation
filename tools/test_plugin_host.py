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

# Config is a fresh dictionary per invocation; logs are separate from Gemini results.
def run(source, config='{}'):
    return json.loads(host.invoke_with_config(source, 'test', '{}', config, None))
r = run('def test(args):\n print("hello")\n return config["volume"]', '{"volume": 5}')
assert r == {'ok': True, 'result': 5, 'log': 'hello\n'}
r = run('import sys\ndef test(args):\n print("err", file=sys.stderr)\n raise ValueError("private")')
assert not r['ok'] and r['error_type'] == 'ValueError' and 'err' in r['log'] and 'private' not in json.dumps(r)
r = run('def test(args):\n print("x" * 100000)\n return True')
assert r['ok'] and len(r['log']) < 4200 and 'обрезан' in r['log']
assert not run('def test(args):\n return 1', '[]')['ok']
assert not run('def test(args):\n return "x" * 17000')['ok']
assert run('def test(args):\n return config', '{}')['result'] == {}
print('Plugin configuration and log tests passed')
