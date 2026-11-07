package ru.geministation;

import android.view.KeyEvent;

/** Only these simulated media buttons may be sent; Android chooses the target media session. */
final class MediaCommands {
    enum Command {
        PLAY("play", KeyEvent.KEYCODE_MEDIA_PLAY, "воспроизведение"),
        PAUSE("pause", KeyEvent.KEYCODE_MEDIA_PAUSE, "пауза"),
        STOP("stop", KeyEvent.KEYCODE_MEDIA_STOP, "остановка"),
        NEXT("next", KeyEvent.KEYCODE_MEDIA_NEXT, "следующий трек"),
        PREVIOUS("previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS, "предыдущий трек"),
        FAST_FORWARD("fast_forward", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "перемотка вперёд"),
        REWIND("rewind", KeyEvent.KEYCODE_MEDIA_REWIND, "перемотка назад");
        final String id, description;
        final int keyCode;
        Command(String id, int keyCode, String description) {
            this.id = id; this.keyCode = keyCode; this.description = description;
        }
    }
    interface Sink { void send(int code, boolean down); }
    static Command parse(String id) {
        if (id == null) return null;
        for (Command command : Command.values()) if (command.id.equals(id)) return command;
        return null;
    }
    static boolean send(Command command, Sink sink) {
        if (command == null || sink == null) return false;
        boolean downOk = false, upOk = false;
        try { sink.send(command.keyCode, true); downOk = true; }
        catch (RuntimeException ignored) { }
        finally {
            // Even a failed dispatch may have reached the system before it threw.
            try { sink.send(command.keyCode, false); upOk = true; }
            catch (RuntimeException ignored) {
                // Best effort only: the platform may reject this attempt too.
                try { sink.send(command.keyCode, false); } catch (RuntimeException ignoredAgain) { }
            }
        }
        return downOk && upOk;
    }
    private MediaCommands() { }
}
