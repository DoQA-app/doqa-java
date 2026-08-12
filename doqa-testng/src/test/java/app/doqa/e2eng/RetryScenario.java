package app.doqa.e2eng;

import app.doqa.annotations.DoqaId;
import org.testng.Assert;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;
import org.testng.annotations.Test;

/**
 * Retry E2E fixture: TestNG delivers a failed retry attempt as a SKIP with {@code wasRetried()},
 * which a naive adapter reports as "skipped" and so hides the real failure. Both attempts must reach
 * DoQA under one externalId, the failed one with the outcome of its throwable.
 */
public class RetryScenario {

    /** One retry, i.e. two attempts in total. */
    public static final class RetryOnce implements IRetryAnalyzer {
        private int retries;

        @Override
        public boolean retry(ITestResult result) {
            return retries++ < 1;
        }
    }

    public static int attempts;

    @Test(retryAnalyzer = RetryOnce.class)
    @DoqaId("E2ENG-RETRY-1")
    public void flakyPassesOnSecondAttempt() {
        if (++attempts == 1) {
            Assert.fail("flaky boom");
        }
    }
}
