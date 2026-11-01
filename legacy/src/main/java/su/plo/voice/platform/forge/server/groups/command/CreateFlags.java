package su.plo.voice.platform.forge.server.groups.command;
// Adapted from pv-addon-groups v1.1 (https://github.com/plasmoapp/pv-addon-groups), MIT License, Copyright (c) 2022 Plasmo. See META-INF/licenses/pv-addon-groups-MIT.txt.

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Upstream CreateCommand argument parser. A token containing ':' starts a flag; the tokens up to the next such token
 * are joined with single spaces and split on the first ':'. Tokens before the first flag and unknown keys are ignored,
 * keys are case-sensitive, the last duplicate wins.
 */
final class CreateFlags {
    static final List<String> KNOWN = java.util.Arrays.asList("name", "password", "permissions", "persistent");

    private final Map<String, String> values;

    private CreateFlags(Map<String, String> values) {
        this.values = values;
    }

    /** @param args the command arguments including the subcommand name at index 0 */
    static CreateFlags parse(String[] args) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].indexOf(':') >= 0) starts.add(i);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int s = 0; s < starts.size(); s++) {
            int from = starts.get(s);
            int to = s + 1 < starts.size() ? starts.get(s + 1) : args.length;
            StringBuilder segment = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (i > from) segment.append(' ');
                segment.append(args[i]);
            }
            String text = segment.toString();
            int colon = text.indexOf(':');
            values.put(text.substring(0, colon).trim(), text.substring(colon + 1).trim());
        }
        return new CreateFlags(values);
    }

    /** @return whether the flag was given at all (even with an empty value) */
    boolean has(String flag) {
        return values.containsKey(flag);
    }

    /** @return the whole value of "name", or null */
    String name() {
        return values.get("name");
    }

    /** @return the whole value of "password", or null when absent; the action keeps its first word */
    String passwordRaw() {
        return values.get("password");
    }

    /** @return the first word of "password"; null when absent or empty (an empty password means no password) */
    String password() {
        String value = values.get("password");
        if (value == null) return null;
        int space = value.indexOf(' ');
        String word = space < 0 ? value : value.substring(0, space);
        return word.isEmpty() ? null : word;
    }

    /** @return the comma separated nodes of "permissions" (trimmed, empty entries dropped), or null when absent */
    Set<String> permissions() {
        String value = values.get("permissions");
        return value == null ? null : splitNodes(value);
    }

    /** @return the raw value of "persistent", or null when absent */
    String persistentRaw() {
        return values.get("persistent");
    }

    /** @return the parsed "persistent", or null when absent or neither "true" nor "false" */
    Boolean persistent() {
        return parseBoolean(values.get("persistent"));
    }

    /** Flags a tab completion should not offer again. */
    List<String> usedFlags() {
        List<String> used = new ArrayList<>();
        for (String flag : KNOWN) {
            if (!values.containsKey(flag)) continue;
            if (flag.equals("persistent") && persistent() == null) continue;
            used.add(flag);
        }
        return used;
    }

    static Set<String> splitNodes(String value) {
        Set<String> nodes = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String node = part.trim();
            if (!node.isEmpty()) nodes.add(node);
        }
        return nodes;
    }

    static Boolean parseBoolean(String value) {
        if ("true".equals(value)) return Boolean.TRUE;
        if ("false".equals(value)) return Boolean.FALSE;
        return null;
    }
}
