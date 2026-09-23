package com.hasan.budget.ingestion.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

/**
 * Executors that make "this never happens on the request thread" an assertion rather than a hope.
 *
 * <p>A real thread pool would make the same test either flaky or full of waiting. Holding the work
 * in a queue instead lets a test look at the world at the exact moment the caller returned, which is
 * the thing actually worth proving, and then run the work deliberately.
 */
public final class TestExecutors {

    private TestExecutors() {}

    /** Runs work immediately, for tests about what a sync does rather than about when it runs. */
    public static Executor immediate() {
        return Runnable::run;
    }

    public static Queueing queueing() {
        return new Queueing();
    }

    /** Accepts work and holds it until a test asks for it to run. */
    public static final class Queueing implements Executor {

        private final Deque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        public int pending() {
            return queued.size();
        }

        /** Runs everything queued, including anything queued while running it. */
        public void runQueuedWork() {
            while (!queued.isEmpty()) {
                queued.poll().run();
            }
        }
    }
}
