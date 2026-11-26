package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded input-stream identity from this service's pinned FFmpeg process.
 * DVD->DVB conversion erases physical SPU IDs. Do not infer them from languages
 * or output ordinals. Unknown/incomplete probe syntax is unavailable, not a
 * fabricated dense map. Input headers precede FFmpeg's first output bytes.
 */
final class DvdSpuInventory {
    private static final Pattern SPU = Pattern.compile(
            "^\\s*Stream #0:[^\\r\\n]*\\[0x([0-9a-fA-F]{1,8})\\][^\\r\\n]*: Subtitle: (?:dvd_subtitle|dvdsub)\\b.*");
    private final TreeSet<Integer> ids = new TreeSet<Integer>();
    private final StringBuilder line = new StringBuilder();
    private boolean inInput, ready, invalid, discard;

    synchronized void accept(byte[] data, int offset, int length) {
        String text = new String(data, offset, length, StandardCharsets.UTF_8);
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '\n' || value == '\r') {
                if (!discard) acceptLine(line.toString());
                line.setLength(0); discard = false;
            } else if (!discard && line.length() < 4096) line.append(value);
            else {
                // A truncated input header cannot prove a complete inventory.
                if (inInput && !ready) invalid = true;
                line.setLength(0); discard = true;
            }
        }
    }
    private void acceptLine(String text) {
        if (ready) return;
        if (text.startsWith("Input #0,")) { inInput = true; return; }
        if (inInput && (text.startsWith("Stream mapping:") || text.startsWith("Output #0,"))) {
            ready = true; return;
        }
        if (!inInput) return;
        Matcher match = SPU.matcher(text);
        if (!match.matches()) {
            if (text.contains("Stream #0:") && text.contains("Subtitle:")) invalid = true;
            return;
        }
        long id = Long.parseLong(match.group(1), 16);
        if (id < 0x20 || id > 0x3f) { invalid = true; return; }
        ids.add((int) id);
    }
    synchronized int[] snapshot() throws IOException {
        if (invalid) throw new IOException("Invalid DVD input subpicture identity");
        if (!ready) return null;
        int[] result = new int[ids.size()]; int index = 0;
        for (Integer id : ids) result[index++] = id;
        return result;
    }
}
