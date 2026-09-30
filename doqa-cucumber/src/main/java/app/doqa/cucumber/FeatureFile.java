package app.doqa.cucumber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The text of one {@code .feature} file, read line by line (1-based, as in Cucumber). */
final class FeatureFile {

    private final String[] lines;

    private FeatureFile(String[] lines) {
        this.lines = lines;
    }

    static FeatureFile of(String source) {
        String text = source == null ? "" : source;
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        return new FeatureFile(text.split("\\r\\n|\\r|\\n", -1));
    }

    String line(int number) {
        return number >= 1 && number <= lines.length ? lines[number - 1] : null;
    }

    int featureLine() {
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (!t.isEmpty() && !isComment(t) && !isTagLine(t)) {
                return i + 1;
            }
        }
        return 0;
    }

    String nameAt(int number) {
        String line = line(number);
        if (line == null) {
            return "";
        }
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).trim();
    }

    List<String> tagsAbove(int number) {
        List<List<String>> blocks = new ArrayList<>();
        for (int i = number - 1; i >= 1; i--) {
            String t = lines[i - 1].trim();
            if (t.isEmpty() || isComment(t)) {
                continue;
            }
            if (!isTagLine(t)) {
                break;
            }
            blocks.add(tagsOf(t));
        }
        Collections.reverse(blocks);
        List<String> tags = new ArrayList<>();
        for (List<String> block : blocks) {
            tags.addAll(block);
        }
        return tags;
    }

    List<String> examplesHeader(int number) {
        for (int i = number + 1; i <= lines.length; i++) {
            String t = lines[i - 1].trim();
            if (t.startsWith("|")) {
                return cells(t);
            }
        }
        return Collections.emptyList();
    }

    List<String> rowCells(int number) {
        String line = line(number);
        if (line == null || !line.trim().startsWith("|")) {
            return Collections.emptyList();
        }
        return cells(line.trim());
    }

    String stepText(int number, String keyword) {
        String line = line(number);
        if (line == null || keyword == null) {
            return null;
        }
        String t = line.trim();
        String kw = keyword.trim();
        if (kw.isEmpty() || !t.startsWith(kw)) {
            return null;
        }
        return t.substring(kw.length()).trim();
    }

    private static boolean isComment(String trimmed) {
        return trimmed.startsWith("#");
    }

    private static boolean isTagLine(String trimmed) {
        return trimmed.startsWith("@");
    }

    private static List<String> tagsOf(String trimmed) {
        String uncommented = trimmed.split("\\s#", 2)[0];
        List<String> tags = new ArrayList<>();
        for (String part : uncommented.split("@")) {
            String tag = part.trim();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    static List<String> cells(String row) {
        List<String> cells = new ArrayList<>();
        int start = row.indexOf('|');
        if (start < 0) {
            return cells;
        }
        StringBuilder raw = new StringBuilder();
        for (int i = start + 1; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '\\' && i + 1 < row.length()) {
                raw.append(c).append(row.charAt(++i));
            } else if (c == '|') {
                cells.add(unescape(raw.toString().trim()));
                raw.setLength(0);
            } else {
                raw.append(c);
            }
        }
        return cells;
    }

    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                if (next == 'n') {
                    out.append('\n');
                } else if (next == '|' || next == '\\') {
                    out.append(next);
                } else {
                    out.append(c).append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
