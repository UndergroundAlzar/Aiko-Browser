package app.aikobrowser;

public final class ScrollPositionsCheck {
    public static void main(String[] args) {
        ScrollPositions positions = new ScrollPositions();
        String link = "https://forum.example/t/topic";
        positions.remember(0, link, 0, 1200);
        positions.remember(3, link, 0, 4200);
        positions.remember(ScrollPositions.URL_ONLY, link, 0, 4200);
        check(positions.get(0, link).y == 1200 && positions.get(3, link).y == 4200);
        check(positions.get(3, "https://forum.example/t/different") == null);
        positions.discardForward(0);
        check(positions.get(3, link) == null && positions.get(0, link).y == 1200);
        check(positions.get(ScrollPositions.URL_ONLY, link).y == 4200);
        System.out.println("PASS same URL at different history entries and discarded forward branch");
    }

    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("Scroll position state did not match navigation history");
    }
}
