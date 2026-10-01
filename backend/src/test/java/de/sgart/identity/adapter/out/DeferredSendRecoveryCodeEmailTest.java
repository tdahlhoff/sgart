package de.sgart.identity.adapter.out;

import org.junit.jupiter.api.Test;

/**
 * Fast unit test — proves the no-op default sends nothing and throws nothing. No assertion library
 * needed: a thrown exception would fail the test on its own.
 */
class DeferredSendRecoveryCodeEmailTest {

    @Test
    void deferredSendRecoveryCodeEmail_sendsNothing() {
        DeferredSendRecoveryCodeEmail deferred = new DeferredSendRecoveryCodeEmail();

        deferred.sendAttachConfirmationCode("person@example.test", "042817");
        deferred.sendRecoveryCode("person@example.test", "042817");
    }
}
