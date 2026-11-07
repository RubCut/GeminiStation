# `config` comes from the panel; validate values before using them.
# `adb` is present only for a plugin approved with adb_shell.
def shell_uid(args):
    if adb is None:
        return {"error": "ADB shell не разрешён"}
    timeout = config.get("timeout_seconds", 3)
    if type(timeout) is not int or not 1 <= timeout <= 30:
        return {"error": "timeout_seconds должен быть целым числом от 1 до 30"}
    print("Проверка UID через ADB Helper")  # Visible in this plugin's log after completion.
    return adb.run("id -u", timeout=timeout)
