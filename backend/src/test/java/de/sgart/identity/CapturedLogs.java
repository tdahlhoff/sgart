package de.sgart.identity;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

/**
 * Captures what a class logs, message and stack trace included, so a test can prove that personal
 * data (an address, a code) never reaches the log output. Detaches itself on {@link #close()}.
 */
public final class CapturedLogs implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private CapturedLogs(Logger logger) {
        this.logger = logger;
        appender.start();
        logger.addAppender(appender);
    }

    public static CapturedLogs ofLoggerOf(Class<?> loggingClass) {
        return new CapturedLogs((Logger) LoggerFactory.getLogger(loggingClass));
    }

    /** Every captured event as text: formatted message plus the full stack trace, if any. */
    public String allOutput() {
        return appender.list.stream()
                .map(event -> event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                                ? ""
                                : "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .collect(Collectors.joining("\n"));
    }

    public boolean hasLoggedAnything() {
        return !appender.list.isEmpty();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
