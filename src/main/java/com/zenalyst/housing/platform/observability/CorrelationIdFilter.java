package com.zenalyst.housing.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id on every request, in the logs and in the response.
 *
 * <p>The point is a specific conversation. An applicant rings up and says the site gave them an
 * error; the only thing they have is the identifier the error told them to quote. Without this,
 * finding the corresponding log line among a day's traffic means guessing at timestamps.
 *
 * <p>A caller-supplied {@code X-Correlation-Id} is honoured so that a trace begun elsewhere — a
 * gateway, a batch job, another service — stays one trace rather than becoming several. It is
 * length-limited and stripped of anything but safe characters, because it ends up in log output and
 * a header the caller controls is a caller-controlled log entry otherwise.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = sanitise(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled. Leaving this behind attaches one request's identifier to the next
            // request that happens to land on the same thread, which is worse than having none.
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitise(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String cleaned = supplied.replaceAll("[^A-Za-z0-9_.:-]", "");
        if (cleaned.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }
}
