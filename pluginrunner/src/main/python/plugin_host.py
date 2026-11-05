"""Fresh runner process per job. Plugin metadata is parsed by the main app, never executed here."""
import json

class _Adb:
    def __init__(self, bridge):
        self._bridge = bridge
    def run(self, command, timeout=15):
        if not isinstance(command, str) or not isinstance(timeout, int):
            raise TypeError("adb.run expects a string and integer timeout")
        return json.loads(str(self._bridge.run(command, timeout)))

def invoke(source, handler, args_json, bridge):
    namespace = {"__name__": "gemini_plugin", "adb": _Adb(bridge) if bridge is not None else None}
    exec(compile(source, "<gemini-plugin>", "exec"), namespace)
    function = namespace.get(handler)
    if not callable(function):
        raise ValueError("Plugin handler is missing")
    result = function(json.loads(args_json))
    result_json = json.dumps(result, ensure_ascii=False, allow_nan=False)
    if len(result_json.encode("utf-8")) > 16384:
        raise ValueError("Plugin result is too large")
    return result_json
