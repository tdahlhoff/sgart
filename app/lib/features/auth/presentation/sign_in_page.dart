import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import 'auth_cubit.dart';
import 'auth_state.dart';

/// The pre-authentication gate (Story 7.1, AC1): a brief loading moment while the app silently
/// provisions the device's account and signs in — no email, username, or password to enter, and no
/// browser surface, so there is deliberately no "sign in" button here. Shown again (with an error
/// and a retry action) only if that silent attempt fails, e.g. the backend is unreachable.
class SignInPage extends StatelessWidget {
  const SignInPage({super.key});

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      // "SGART" is the app's brand name, not translatable copy (Story 1.3 decision).
      appBar: const SgartAppBar(title: 'SGART'),
      body: SafeArea(
        child: BlocBuilder<AuthCubit, AuthState>(
          builder: (context, state) {
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Padding(
                  padding: SgartShapes.screenHeaderPadding,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        localizations.authSignInHeading,
                        style: Theme.of(context).textTheme.headlineSmall,
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: SgartShapes.headingGap),
                      Text(
                        localizations.authSignInSubtitle,
                        key: const Key('sign-in-subtitle'),
                        style: Theme.of(context).textTheme.bodyMedium,
                        textAlign: TextAlign.center,
                      ),
                    ],
                  ),
                ),
                const Spacer(),
                Padding(
                  padding: const EdgeInsets.all(SgartShapes.cardPadding),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (state.status == AuthStatus.failure && state.error != null) ...[
                        Text(
                          localizedMessageForErrorCode(localizations, state.error!.code),
                          key: const Key('sign-in-error'),
                          textAlign: TextAlign.center,
                        ),
                        const SizedBox(height: SgartShapes.space4),
                        SgartButton(
                          key: const Key('sign-in-retry-button'),
                          label: localizations.authRetryButtonLabel,
                          onPressed: () => context.read<AuthCubit>().signIn(),
                        ),
                      ] else
                        const Padding(
                          padding: EdgeInsets.symmetric(vertical: SgartShapes.space4),
                          child: Center(
                            child: CircularProgressIndicator(key: Key('sign-in-progress-indicator')),
                          ),
                        ),
                    ],
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}
