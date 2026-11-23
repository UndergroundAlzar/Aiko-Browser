package app.aikobrowser;

import java.util.LinkedHashMap;
import java.util.Map;

/** A tab can visit the same URL twice at different positions. Keep the navigation index too. */
final class ScrollPositions {
    static final int URL_ONLY = -1;
    private final Map<Integer, Position> positions = new LinkedHashMap<>(16, 0.75f, true);

    void remember(int index, String url, int x, int y) {
        if (url == null || index < URL_ONLY) return;
        positions.put(index, new Position(url, Math.max(0, x), Math.max(0, y)));
        if (positions.size() > 24) positions.remove(positions.keySet().iterator().next());
    }

    Position get(int index, String url) {
        Position saved = positions.get(index);
        return saved != null && saved.url.equals(url) ? saved : null;
    }

    void discardForward(int index) {
        positions.keySet().removeIf(key -> key > index);
    }

    static final class Position {
        final String url;
        final int x, y;

        Position(String url, int x, int y) {
            this.url = url;
            this.x = x;
            this.y = y;
        }
    }
}
