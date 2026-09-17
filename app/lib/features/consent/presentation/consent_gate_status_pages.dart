import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/app_error.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import 'consent_cubit.dart';

/// The consent-gate loading placeholder — shared by every screen that gates on [ConsentCubit]'s
/// status (Story 7.4, DRY): the 0-household gateway's gate and the deep-link invite accept's gate
/// both show this while the initial `GET /api/v1/consent` is in flight.
class ConsentLoadingPage extends StatelessWidget {
  const ConsentLoadingPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: const SgartAppBar(title: 'SGART'),
      body: const Center(child: CircularProgressIndicator(key: Key('consent-gate-loading'))),
    );
  }
}

/// The consent-gate load-failure page — shared for the same reason as [ConsentLoadingPage].
/// Resolves [error] to its own localized copy rather than a hard-coded generic fallback, so a
/// specific failure (e.g. a network error while checking consent) is not misreported (Story 7.4
/// review).
class ConsentFailurePage extends StatelessWidget {
  const ConsentFailurePage({super.key, required this.error});

  final AppError? error;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final message = error == null
        ? localizations.errorGenericFallback
        : localizedMessageForErrorCode(localizations, error!.code);

    return Scaffold(
      appBar: const SgartAppBar(title: 'SGART'),
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(SgartShapes.cardPadding),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(message, key: const Key('consent-gate-load-error')),
                const SizedBox(height: SgartShapes.space4),
                SgartButton(
                  key: const Key('consent-gate-retry-button'),
                  label: localizations.householdsRetryButtonLabel,
                  onPressed: () => context.read<ConsentCubit>().load(),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
