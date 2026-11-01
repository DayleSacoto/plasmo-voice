package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See LICENSE-pv-addon-groups.txt.

/** Translates a message key into the language of one receiver; the result already carries the colour codes. */
public interface Translator {
    String translate(String key, Object... args);
}
