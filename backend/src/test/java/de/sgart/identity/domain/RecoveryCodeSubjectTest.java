package de.sgart.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecoveryCodeSubjectTest {

    @Test
    void anAccountSubjectAndAnAddressSubjectWithTheSameRawValueAreNotEqual() {
        RecoveryCodeSubject accountSubject = RecoveryCodeSubject.forAccount(new KeycloakUserId("same-raw-value"));
        RecoveryCodeSubject addressSubject = RecoveryCodeSubject.forAddress(new RecoveryEmailDigest("same-raw-value"));

        assertThat(accountSubject).isNotEqualTo(addressSubject);
    }

    @Test
    void twoAccountSubjectsForTheSameAccountAreEqual() {
        assertThat(RecoveryCodeSubject.forAccount(new KeycloakUserId("account-1")))
                .isEqualTo(RecoveryCodeSubject.forAccount(new KeycloakUserId("account-1")));
    }
}
