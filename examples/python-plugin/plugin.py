# Global `adb` is present only for a plugin approved with adb_shell.
def shell_uid(args):
    if adb is None:
        return {"error": "ADB shell не разрешён"}
    return adb.run("id -u", timeout=3)
