package de.sgart.identity.application;

import de.sgart.identity.domain.DevicePlatform;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.ProvisionedAccountRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Silently provisions the Keycloak account a first-launch device credential is bound to (Story
 * 7.1, AC1/AC3) — a flat verb-phrase application service, not a CQRS command+handler (F1: the
 * identity ACL's own convention, {@link IssueMemberIdentity}/{@link RegisterDeviceToken}, overrides
 * the sprint-change proposal's "command + handler" wording; CLAUDE.md §4 "do not force CQRS onto
 * trivial CRUD").
 *
 * <p><strong>Idempotent on the derived username</strong> (a retry after a dropped response never
 * creates a second Keycloak account or a second {@code ProvisionedAccount} row, AC3): {@link
 * CreateAccount#create} itself no-ops into the existing account, and {@link
 * ProvisionedAccountRepository#recordIfAbsent} no-ops the row write. Emits no domain event —
 * identity is delegated to Keycloak (AD-5/AD-6/AD-7 untouched).
 */
public final class ProvisionAccount {

    /** The exact byte length of an Ed25519 public key (RFC 8032) — the crux the app and this
     * validation must agree on so the derived username stays stable across client and server. */
    private static final int ED25519_PUBLIC_KEY_LENGTH_BYTES = 32;

    private final CreateAccount createAccount;
    private final ProvisionedAccountRepository provisionedAccountRepository;
    private final Clock clock;

    public ProvisionAccount(
            CreateAccount createAccount, ProvisionedAccountRepository provisionedAccountRepository, Clock clock) {
        this.createAccount = Objects.requireNonNull(createAccount, "createAccount must not be null");
        this.provisionedAccountRepository =
                Objects.requireNonNull(provisionedAccountRepository, "provisionedAccountRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @param publicKey the base64url encoding of the device's 32-byte Ed25519 public key — used
     *     verbatim as the Keycloak username (D-E: "deterministic encoding of the public key"), so
     *     recovery needs no server lookup.
     * @param platform a {@link DevicePlatform} name, reserved for future device-attestation/
     *     telemetry (not stored — no {@code ProvisionedAccount} field for it, YAGNI); validated
     *     only so a malformed request still fails fast rather than silently passing through.
     * @throws InvalidAccountProvisioningException if {@code publicKey} is not a base64url-encoded
     *     32-byte value (400) or {@code platform} is missing/unrecognized (400) — never a 500.
     */
    public void provision(String publicKey, String platform) {
        String username = validatedUsername(publicKey);
        validatedPlatform(platform);

        KeycloakUserId keycloakUserId = createAccount.create(username, publicKey);
        provisionedAccountRepository.recordIfAbsent(keycloakUserId, clock.instant());
    }

    private static String validatedUsername(String publicKey) {
        if (publicKey == null || publicKey.isBlank()) {
            throw new InvalidAccountProvisioningException("account.publicKeyRequired", "publicKey must be provided");
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(publicKey);
        } catch (IllegalArgumentException notBase64Url) {
            throw new InvalidAccountProvisioningException(
                    "account.publicKeyInvalid", "publicKey must be base64url-encoded");
        }
        if (decoded.length != ED25519_PUBLIC_KEY_LENGTH_BYTES) {
            throw new InvalidAccountProvisioningException(
                    "account.publicKeyInvalid", "publicKey must decode to a 32-byte Ed25519 key");
        }
        return publicKey;
    }

    private static void validatedPlatform(String rawPlatform) {
        if (rawPlatform == null || rawPlatform.isBlank()) {
            throw new InvalidAccountProvisioningException("account.platformRequired", "platform must be provided");
        }
        try {
            DevicePlatform.valueOf(rawPlatform);
        } catch (IllegalArgumentException notAPlatform) {
            throw new InvalidAccountProvisioningException(
                    "account.platformInvalid", "platform must be one of " + Arrays.toString(DevicePlatform.values()));
        }
    }
}
