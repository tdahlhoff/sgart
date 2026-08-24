import 'package:flutter/widgets.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../auth/presentation/auth_cubit.dart';
import '../../auth/presentation/auth_state.dart';
import 'locale_cubit.dart';

/// Bridges the auth lifecycle to the locale holder (Story 1.10, AC1). `AuthCubit` is provided
/// **below** `MaterialApp` (it lives inside `AuthGate`), while [LocaleCubit] must sit **above**
/// `MaterialApp` to drive `MaterialApp.locale`; so the coupling runs upward from here — this listener
/// lives in the authenticated subtree and reaches the ancestor [LocaleCubit] via `context.read`.
///
/// On sign-in it restores the person's stored locale; on sign-out it resets to the device default and
/// erases their stored locale so a later sign-in on the same device never inherits it (DSGVO / AD-7).
class LocaleAuthBridge extends StatelessWidget {
  const LocaleAuthBridge({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return BlocListener<AuthCubit, AuthState>(
      // React to a status change and to a same-status account switch (a different `sub` under
      // `authenticated`), so the incoming user's locale is always re-applied — never inherited.
      listenWhen: (previous, current) =>
          previous.status != current.status || previous.keycloakUserId != current.keycloakUserId,
      listener: (context, state) {
        final localeCubit = context.read<LocaleCubit>();
        switch (state.status) {
          case AuthStatus.authenticated:
            final userId = state.keycloakUserId;
            if (userId != null) {
              localeCubit.applyForUser(userId);
            }
          case AuthStatus.unauthenticated:
            localeCubit.resetForSignOut();
          case AuthStatus.inProgress:
          case AuthStatus.failure:
            break;
        }
      },
      child: child,
    );
  }
}
