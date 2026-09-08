package com.zenalyst.housing.draw;

import java.util.UUID;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The background entry point for executing a draw.
 *
 * <h2>Why a job and not an HTTP request</h2>
 *
 * <p>A draw over four thousand candidates should not depend on a load balancer's patience, and a
 * process that dies mid-draw should leave a durable record of unfinished work rather than a
 * question about what happened. JobRunr persists the job alongside the data it operates on, so
 * both survive a restart together.
 *
 * <h2>Why retries are switched off</h2>
 *
 * <p>JobRunr would happily retry ten times by default. That is right for a bank statement sync and
 * wrong here.
 *
 * <p>The allocation is deterministic: the same frozen register, rule version and seed produce the
 * same result every time. So a failure is either transient — in which case it will be a database
 * or infrastructure fault that somebody needs to look at — or it is a defect, in which case
 * retrying reproduces it. Neither case is improved by nine more silent attempts against a public
 * lottery.
 *
 * <p>Instead the failure is recorded on the draw with its reason, the draw becomes {@code FAILED},
 * and a person decides whether to run it again. {@code POST /execute} accepts a failed draw, so
 * retrying is one deliberate call rather than something that happened at three in the morning.
 */
@Component
public class DrawExecutionJob {

    private static final Logger log = LoggerFactory.getLogger(DrawExecutionJob.class);

    private final DrawExecutionService execution;

    public DrawExecutionJob(DrawExecutionService execution) {
        this.execution = execution;
    }

    @Job(name = "Execute housing draw %0", retries = 0)
    public void run(UUID drawId, String actor) {
        try {
            execution.execute(drawId, actor);
        } catch (RuntimeException e) {
            log.error("Draw {} failed to execute", drawId, e);
            execution.recordFailure(drawId, describe(e));
            throw e;
        }
    }

    /**
     * A reason an operator can act on, without the stack trace that a public status endpoint has no
     * business carrying.
     */
    private static String describe(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
