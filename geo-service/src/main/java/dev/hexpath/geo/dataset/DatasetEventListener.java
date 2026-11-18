package dev.hexpath.geo.dataset;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Keeps the snapshot current. One dedicated connection LISTENs on dataset_events; every wake-up
 * (a notification, or the 10-second timeout) compares max(dataset_event.id) with the snapshot's
 * lastEventId, so a missed notification is caught by the next poll. Reconnects with backoff.
 */
@Component
public class DatasetEventListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DatasetEventListener.class);
    static final int POLL_MILLIS = 10_000;
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    private final SnapshotHolder holder;
    private final SnapshotLoader loader;
    private final String url;
    private final String user;
    private final String password;
    private volatile boolean running;
    private Thread thread;

    public DatasetEventListener(SnapshotHolder holder, SnapshotLoader loader,
                                @Value("${spring.datasource.url}") String url,
                                @Value("${spring.datasource.username}") String user,
                                @Value("${spring.datasource.password}") String password) {
        this.holder = holder;
        this.loader = loader;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().daemon().name("dataset-event-listener").start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        long backoff = 1_000;
        while (running) {
            try (Connection c = DriverManager.getConnection(url, listenerProperties())) {
                try (Statement s = c.createStatement()) {
                    s.execute("LISTEN dataset_events");
                }
                PGConnection pg = c.unwrap(PGConnection.class);
                backoff = 1_000;
                refreshIfNeeded(c);
                while (running) {
                    pg.getNotifications(POLL_MILLIS);
                    refreshIfNeeded(c);
                }
            } catch (SQLException | RuntimeException e) {
                // RuntimeException covers Spring's DataAccessException from the loader (pool exhausted,
                // transient query error): the thread must survive it, or the service stops following releases.
                if (!running) {
                    return;
                }
                log.warn("dataset listener connection failed, retrying in {} ms: {}", backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
            }
        }
    }

    /** Keepalive and a socket timeout above the poll interval, so a half-open connection cannot hang the poll. */
    private java.util.Properties listenerProperties() {
        var p = new java.util.Properties();
        p.setProperty("user", user);
        p.setProperty("password", password);
        p.setProperty("tcpKeepAlive", "true");
        p.setProperty("socketTimeout", Integer.toString(POLL_MILLIS / 1000 * 3));
        return p;
    }

    private void refreshIfNeeded(Connection c) throws SQLException {
        long latest;
        try (Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT coalesce(max(id), 0) FROM dataset_event")) {
            rs.next();
            latest = rs.getLong(1);
        }
        boolean stale = holder.current().map(snap -> latest > snap.lastEventId()).orElse(true);
        if (stale) {
            loader.loadActive().ifPresent(next -> {
                holder.swap(next);
                log.info("now serving release {} (event {})", next.releaseId(), next.lastEventId());
            });
        }
    }
}
