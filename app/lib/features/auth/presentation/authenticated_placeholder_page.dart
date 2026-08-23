import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import 'auth_cubit.dart';

/// Minimal authenticated screen proving the sign-in → token → authenticated call → sign-out flow
/// end to end (Story 1.4). The app shell, routing, and Profil screen are later stories (1.6, 1.11)
/// — this shows only the live display name from `GET /identity/me` and a sign-out action.
class AuthenticatedPlaceholderPage extends StatelessWidget {
  const AuthenticatedPlaceholderPage({super.key, required this.displayName});

  final String displayName;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: const SgartAppBar(title: 'SGART'),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(SgartShapes.cardPadding),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Text(
                displayName.trim().isEmpty
                    ? localizations.authSignedInLabel
                    : localizations.authSignedInAsLabel(displayName),
                key: const Key('signed-in-as'),
              ),
              const SizedBox(height: SgartShapes.space4),
              SgartButton(
                key: const Key('sign-out-button'),
                label: localizations.authSignOutButtonLabel,
                onPressed: () => context.read<AuthCubit>().signOut(),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
