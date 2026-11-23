package app.aikobrowser;

import java.util.ArrayList;
import java.util.List;

/** Validate an editor draft with the same parser used by bundled filters. */
final class CustomFilterList {
    static final int MAX_CHARS = 128_000;
    static final int MAX_RULES = 2000;
    final String rules;
    final int count;

    private CustomFilterList(String rules, int count) {
        this.rules = rules;
        this.count = count;
    }

    static CustomFilterList parse(String source) {
        if (source.length() > MAX_CHARS) throw new IllegalArgumentException("规则文本超过 128,000 字符");
        StringBuilder output = new StringBuilder();
        List<String> badLines = new ArrayList<>();
        FilterRules.Builder validation = new FilterRules.Builder();
        String draft = source.startsWith("\ufeff") ? source.substring(1) : source;
        String[] lines = draft.split("\\r\\n|[\\n\\r]", -1);
        int valid = 0, invalid = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("!") || line.equalsIgnoreCase("[Adblock Plus 2.0]")) continue;
            String target = FilterRules.Builder.disabledRule(line);
            if (target == null) target = line;
            boolean cosmetic = target.contains("##") || target.contains("#@#");
            if (line.length() > 1500 || (!cosmetic && containsWhitespace(target)) || !validation.addRule(target)) {
                invalid++;
                if (badLines.size() < 8) badLines.add(Integer.toString(i + 1));
                continue;
            }
            if (++valid > MAX_RULES) throw new IllegalArgumentException("最多保存 2,000 条自定义规则");
            output.append(line).append('\n');
        }
        if (invalid > 0) {
            throw new IllegalArgumentException("无法识别第 " + String.join("、", badLines)
                    + " 行" + (invalid > badLines.size() ? "等，共 " + invalid + " 行" : "") + "。");
        }
        return new CustomFilterList(output.toString(), valid);
    }

    private static boolean containsWhitespace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) return true;
        }
        return false;
    }
}
