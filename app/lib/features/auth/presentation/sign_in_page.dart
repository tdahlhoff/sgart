import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import 'auth_cubit.dart';
import 'auth_state.dart';

/// The unauthenticated entry point: starts the Keycloak Authorization Code + PKCE flow. Shown
/// again (with an error) if a previous sign-in or session-resume attempt failed.
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
                      ],
                      SgartButton(
                        key: const Key('sign-in-button'),
                        label: localizations.authSignInButtonLabel,
                        onPressed: state.status == AuthStatus.inProgress
                            ? null
                            : () => context.read<AuthCubit>().signIn(),
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
