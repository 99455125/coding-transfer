package dev.codingtransfer.plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Compact, session-scoped five-entry UI history. Callers apply changes on the EDT. */
final class TransferStatusHistory {
    static final int LIMIT = 5;
    record Entry(String session, String eventKey, Instant time, String text) {}
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final ArrayList<Entry> entries = new ArrayList<>(LIMIT);
    private String session;

    void begin(String sessionId) { session = sessionId; entries.clear(); }
    String session() { return session; }

    boolean update(String sessionId, String eventKey, String text, Instant time) {
        if (session == null) session = sessionId;
        if (!session.equals(sessionId)) return false;
        if (!entries.isEmpty() && entries.getFirst().eventKey().equals(eventKey))
            entries.set(0, new Entry(sessionId, eventKey, time, text));
        else {
            entries.addFirst(new Entry(sessionId, eventKey, time, text));
            while (entries.size() > LIMIT) entries.removeLast();
        }
        return true;
    }

    void complete(String sessionId, String eventKey, String text, Instant time) {
        if (session == null) session = sessionId;
        if (!session.equals(sessionId)) return;
        entries.clear();
        entries.add(new Entry(sessionId, eventKey, time, text));
    }

    List<Entry> entries() { return List.copyOf(entries); }
    String display() {
        return entries.stream().map(entry -> TIME.format(entry.time()) + "  " + entry.text())
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
