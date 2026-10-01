import '../../../shared/http/authenticated_http_client.dart';
import 'recovery_confirmation.dart';

/// Calls the backend's Story 7.3 "recover by email" (opt-in) endpoints — attach/confirm/detach an
/// email on the caller's own (real) account, and request/confirm a recovery code on a fresh
/// device's throwaway account. Abstracted so the cubits/pages that drive these flows never touch a
/// real HTTP client in tests (CLAUDE.md §6), mirroring [AccountProvisioningApi]'s shape.
abstract interface class AccountEmailApi {
  /// `POST /api/v1/account/email` (authenticated as the real account) — sets the email unverified
  /// and sends a 6-digit confirmation code.
  Future<void> attach(String email);

  /// `POST /api/v1/account/email/confirm` — marks the just-attached email verified.
  Future<void> confirm(String code);

  /// `GET /api/v1/account/email` — the masked hint (for example `t***@e***.test`) of the caller's
  /// *confirmed* recovery email, or `null` when none is confirmed. A pending attach is never
  /// reported.
  Future<String?> fetchStatus();

  /// `DELETE /api/v1/account/email` — clears the email and any pending code.
  Future<void> detach();

  /// `POST /api/v1/account/recovery/email` (authenticated as the device's throwaway account) —
  /// requests a recovery code; the server never reveals whether `email` is registered (D-H).
  Future<void> requestRecoveryCode(String email);

  /// `POST /api/v1/account/recovery/email/confirm` — verifies the code and performs the R1 rebind
  /// (design §1.1): on success, the caller's device credential now authenticates into the
  /// recovered account ([RecoveryConfirmationRebound]). When the mailbox is bound to several
  /// accounts the first call answers with the candidates instead and rebinds nothing; the second
  /// call repeats the code with the chosen [accountId].
  Future<RecoveryConfirmation> confirmRecovery(String email, String code, {String? accountId});
}

/// The real, backend-[AuthenticatedHttpClient]-backed [AccountEmailApi].
class HttpAccountEmailApi implements AccountEmailApi {
  const HttpAccountEmailApi(this._httpClient);

  final AuthenticatedHttpClient _httpClient;

  @override
  Future<void> attach(String email) => _httpClient.postJson('/api/v1/account/email', {'email': email});

  @override
  Future<void> confirm(String code) => _httpClient.postJson('/api/v1/account/email/confirm', {'code': code});

  @override
  Future<String?> fetchStatus() async {
    final response = await _httpClient.getJson('/api/v1/account/email');
    return response['addressHint'] as String?;
  }

  @override
  Future<void> detach() => _httpClient.deleteJson('/api/v1/account/email', const {});

  @override
  Future<void> requestRecoveryCode(String email) =>
      _httpClient.postJson('/api/v1/account/recovery/email', {'email': email});

  @override
  Future<RecoveryConfirmation> confirmRecovery(String email, String code, {String? accountId}) async {
    final response = await _httpClient.postJson('/api/v1/account/recovery/email/confirm', {
      'email': email,
      'code': code,
      'accountId': ?accountId,
    });
    return RecoveryConfirmation.fromJson(response);
  }
}
