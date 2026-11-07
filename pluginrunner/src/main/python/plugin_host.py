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


def invoke_with_config(source, handler, args_json, config_json, bridge):
    """Return bounded, explicitly unsafe plugin output separately from the tool result."""
    import contextlib
    import io

    class BoundedLog(io.TextIOBase):
        def __init__(self):
            self.parts = []
            self.length = 0
            self.truncated = False
        def write(self, text):
            if not isinstance(text, str):
                raise TypeError("expected str")
            remaining = 4096 - self.length
            self.parts.append(text[:max(0, remaining)]) if remaining > 0 else None
            self.length += min(len(text), max(0, remaining))
            self.truncated |= len(text) > remaining
            return len(text)
        def value(self):
            return ''.join(self.parts) + ('\n[вывод обрезан]' if self.truncated else '')

    log = BoundedLog()
    try:
        config = json.loads(config_json)
        if not isinstance(config, dict):
            raise ValueError("invalid config")
        with contextlib.redirect_stdout(log), contextlib.redirect_stderr(log):
            namespace = {"__name__": "gemini_plugin", "adb": _Adb(bridge) if bridge is not None else None,
                         "config": config}
            exec(compile(source, "<gemini-plugin>", "exec"), namespace)
            function = namespace.get(handler)
            if not callable(function):
                raise ValueError("missing handler")
            value = function(json.loads(args_json))
            encoded = json.dumps(value, ensure_ascii=False, allow_nan=False)
            if len(encoded.encode("utf-8")) > 16384:
                raise ValueError("result too large")
        return json.dumps({"ok": True, "result": value, "log": log.value()}, ensure_ascii=False, allow_nan=False)
    except BaseException as error:
        # No exception text or traceback: these may contain arguments or secrets.
        return json.dumps({"ok": False, "log": log.value(), "error_type": type(error).__name__[:80]}, ensure_ascii=False)
