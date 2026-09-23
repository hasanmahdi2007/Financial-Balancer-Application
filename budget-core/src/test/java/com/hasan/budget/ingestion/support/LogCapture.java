package com.hasan.budget.ingestion.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Everything written to the log while something runs.
 *
 * <p>Two things need this. A missing category has to be <em>logged</em> as well as counted, and
 * an access token has to appear in no log line at all - and "it is not logged" is only a real claim
 * if something checks it. Logs are where secrets escape, because the line that prints one is usually
 * printing something else.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
    }

    public static LogCapture start() {
        return new LogCapture();
    }

    /** Every message logged, formatted as it would be written. */
    public List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    public String everything() {
        return String.join("\n", lines());
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }
}
