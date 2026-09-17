import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/presentation/account_email_cubit.dart';
import 'package:sgart/features/auth/presentation/account_email_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_account_email_api.dart';

/// Fast unit test — pure cubit + fake API, no widgets, no network (CLAUDE.md §6). Covers Story
/// 7.3's attach → confirm → detach lifecycle (AC1/AC4).
///
/// Test Manifest: profileRecoveryEmailSection_attachConfirmDetach_updatesState (the widget-level
/// counterpart lives in profile_screen_test.dart; this file proves the state machine itself).
void main() {
  group('AccountEmailCubit', () {
    late FakeAccountEmailApi accountEmailApi;
    late AccountEmailCubit cubit;

    setUp(() {
      accountEmailApi = FakeAccountEmailApi();
      cubit = AccountEmailCubit(accountEmailApi);
    });

    tearDown(() => cubit.close());

    test('attach_success_movesToPendingConfirmationAndCallsTheApiWithTheEmail', () async {
      await cubit.attach('anna@example.test');

      expect(cubit.state.status, AccountEmailStatus.pendingConfirmation);
      expect(cubit.state.email, 'anna@example.test');
      expect(accountEmailApi.attachedEmails, ['anna@example.test']);
    });

    test('attach_serverRejection_surfacesTheErrorAndLeavesStatusUnchanged', () async {
      // The test name previously claimed "stays not-attached", but this cubit's seed (set up
      // above with no `initialState`) is `unknown`, not `notAttached` — the assertion below always
      // checked the seed, never the `notAttached` status (Story 7.3 review finding: fixed the name
      // to describe what the test actually proves, a rejected attach changes nothing).
      accountEmailApi.attachErrorToThrow =
          const AppException(AppError(code: 'account.recoveryEmailInvalid', message: 'bad email'));

      await cubit.attach('not-an-email');

      expect(cubit.state.status, AccountEmailStatus.unknown);
      expect(cubit.state.error?.code, 'account.recoveryEmailInvalid');
      expect(cubit.state.isBusy, isFalse);
    });

    test('confirm_success_movesToConfirmedAndClearsTheCode', () async {
      await cubit.attach('anna@example.test');

      await cubit.confirm('042817');

      expect(cubit.state.status, AccountEmailStatus.confirmed);
      expect(cubit.state.email, 'anna@example.test');
      expect(accountEmailApi.confirmedCodes, ['042817']);
    });

    test('confirm_wrongCode_surfacesTheErrorAndStaysPending', () async {
      await cubit.attach('anna@example.test');
      accountEmailApi.confirmErrorToThrow =
          const AppException(AppError(code: 'account.recoveryCodeInvalid', message: 'wrong code'));

      await cubit.confirm('000000');

      expect(cubit.state.status, AccountEmailStatus.pendingConfirmation);
      expect(cubit.state.error?.code, 'account.recoveryCodeInvalid');
    });

    test('detach_success_returnsToNotAttached', () async {
      await cubit.attach('anna@example.test');
      await cubit.confirm('042817');

      await cubit.detach();

      expect(cubit.state.status, AccountEmailStatus.notAttached);
      expect(accountEmailApi.detachCallCount, 1);
    });
  });
}
