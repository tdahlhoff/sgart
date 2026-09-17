import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/presentation/account_email_cubit.dart';
import 'package:sgart/features/auth/presentation/account_email_state.dart';
import 'package:sgart/features/auth/presentation/add_recovery_email_page.dart';
import 'package:sgart/features/auth/presentation/confirm_email_code_page.dart';
import 'package:sgart/l10n/gen/app_localizations.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';
import 'package:sgart/theme/sgart_theme.dart';

import '../../../support/fake_account_email_api.dart';

/// Widget test for [AddRecoveryEmailPage]'s `listenWhen` gate (Story 7.3 review finding, round 2):
/// the page must re-open [ConfirmEmailCodePage] on *every* successful `attach()` completion, not
/// only the first status transition — otherwise re-requesting a code after backing out of the
/// confirm step dead-ends the person on the email screen. The cubit test covers the state machine;
/// this file proves the page's listener/navigation, which no test previously exercised.
void main() {
  group('AddRecoveryEmailPage', () {
    late FakeAccountEmailApi accountEmailApi;

    setUp(() => accountEmailApi = FakeAccountEmailApi());

    // Seeds the cubit already at `pendingConfirmation` — the exact state left behind after a first
    // attach followed by backing out of ConfirmEmailCodePage (a second code was sent).
    AccountEmailCubit pendingConfirmationCubit() => AccountEmailCubit(
          accountEmailApi,
          initialState: const AccountEmailState(
            status: AccountEmailStatus.pendingConfirmation,
            email: 'anna@example.test',
          ),
        );

    Widget buildSubject(AccountEmailCubit cubit) => BlocProvider<AccountEmailCubit>.value(
          value: cubit,
          child: MaterialApp(
            theme: SgartTheme.light(),
            localizationsDelegates: const [
              AppLocalizations.delegate,
              GlobalMaterialLocalizations.delegate,
              GlobalWidgetsLocalizations.delegate,
              GlobalCupertinoLocalizations.delegate,
            ],
            supportedLocales: AppLocalizations.supportedLocales,
            home: const AddRecoveryEmailPage(),
          ),
        );

    testWidgets('reRequestingACodeWhileAlreadyPendingReopensTheConfirmCodePage', (tester) async {
      final cubit = pendingConfirmationCubit();
      addTearDown(cubit.close);
      await tester.pumpWidget(buildSubject(cubit));
      expect(find.byType(ConfirmEmailCodePage), findsNothing);

      await tester.enterText(find.byKey(const Key('add-recovery-email-field')), 'anna@example.test');
      await tester.tap(find.byKey(const Key('add-recovery-email-submit-button')));
      await tester.pumpAndSettle();

      // The re-request landed on `pendingConfirmation` again with no status transition; the old
      // transition-gated listener would never have re-fired, leaving the person stuck here.
      expect(find.byType(ConfirmEmailCodePage), findsOneWidget);
      expect(accountEmailApi.attachedEmails, ['anna@example.test']);
    });

    testWidgets('aFailedReRequestKeepsTheConfirmCodePageClosedAndSurfacesTheError', (tester) async {
      final cubit = pendingConfirmationCubit();
      addTearDown(cubit.close);
      accountEmailApi.attachErrorToThrow =
          const AppException(AppError(code: 'account.recoveryEmailInvalid', message: 'bad email'));
      await tester.pumpWidget(buildSubject(cubit));

      await tester.enterText(find.byKey(const Key('add-recovery-email-field')), 'not-an-email');
      await tester.tap(find.byKey(const Key('add-recovery-email-submit-button')));
      await tester.pumpAndSettle();

      // The attach failed: state stays `pendingConfirmation` but carries an error, so the
      // `error == null` arm of the gate must keep the confirm page closed and show the error inline.
      expect(find.byType(ConfirmEmailCodePage), findsNothing);
      expect(find.byKey(const Key('add-recovery-email-error')), findsOneWidget);
    });
  });
}
