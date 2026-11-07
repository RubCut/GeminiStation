package ru.geministation.pluginipc;
import ru.geministation.pluginipc.IPluginShell;
import ru.geministation.pluginipc.IPluginResult;
interface IPluginRunner {
    void execute(String source, String handler, String argsJson, IPluginShell shell, IPluginResult result);
    void cancel();
    void executeWithConfig(String source, String handler, String argsJson, String configJson, IPluginShell shell, IPluginResult result);
}
