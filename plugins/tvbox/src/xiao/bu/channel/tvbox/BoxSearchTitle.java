package xiao.bu.channel.tvbox;

import java.text.Normalizer;
import java.util.Locale;

final class BoxSearchTitle {
    static String normalize(String title) {
        String text = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.US);
        StringBuilder letters = new StringBuilder(text.length());
        for (int offset = 0; offset < text.length();) {
            int letter = text.codePointAt(offset);
            if (Character.isLetterOrDigit(letter)) letters.appendCodePoint(letter);
            offset += Character.charCount(letter);
        }
        return letters.toString();
    }

    static int rank(String normalizedQuery, String title) {
        if (normalizedQuery.isEmpty()) return 3;
        String normalizedTitle = normalize(title);
        if (normalizedTitle.equals(normalizedQuery)) return 0;
        if (normalizedTitle.startsWith(normalizedQuery)) return 1;
        if (normalizedTitle.contains(normalizedQuery)) return 2;
        return 3;
    }
}
