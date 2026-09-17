package de.sgart.identity.adapter.out;

import org.junit.jupiter.api.Test;

/**
 * Fast unit test — proves the no-op default sends nothing and throws nothing (Story 7.3, AC6). No
 * assertion library needed: a thrown exception would fail the test on its own.
 */
class DeferredSendRecoveryCodeEmailTest {

    @Test
    void deferredSendRecoveryCodeEmail_sendsNothing() {
        new DeferredSendRecoveryCodeEmail().send("person@example.com", "042817");
    }
}
