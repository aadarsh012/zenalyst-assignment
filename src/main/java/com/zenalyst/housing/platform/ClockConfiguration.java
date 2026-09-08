package com.zenalyst.housing.platform;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time is injected, never read from a static.
 *
 * <p>A system that has to prove an application arrived before a deadline cannot have its notion
 * of "now" scattered through the code as {@code Instant.now()}. Injecting a {@link Clock} makes
 * every time-dependent decision testable at a chosen instant — including the ones that matter
 * here, like a paper form submitted an hour before the deadline and typed in a week after it.
 *
 * <p>UTC, always. Local time is a presentation concern; storing or comparing it is how deadlines
 * end up an hour out twice a year.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
