import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import 'consent_cubit.dart';
import 'consent_state.dart';

/// The one native consent screen (Story 7.4, AC1, design §3): a short privacy notice/terms with a
/// link to the hosted notice (design §8 F3) and an explicit accept action. Reads its [ConsentCubit]
/// from an ancestor [BlocProvider] — [ConsentGatedChoicePage] is the production wiring; tests
/// provide their own.
class ConsentGatePage extends StatelessWidget {
  const ConsentGatePage({super.key});

  /// The hosted privacy notice (design §8 F3) — the legal copy itself is a separate task before
  /// beta; this screen only points at it (as plain text, not a live in-app browser launch — no
  /// URL-launching dependency exists yet in the app, and adding one is out of this story's scope,
  /// YAGNI/KISS), never inlines it.
  static const String noticeUrl = 'sgart.example/privacy';

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: const SgartAppBar(title: 'SGART'),
      body: SafeArea(
        // Scroll on a short viewport / large OS text scale, still center on a tall one: the canonical
        // SingleChildScrollView + ConstrainedBox(minHeight) + IntrinsicHeight recipe, so the Spacers
        // keep working without overflowing this keystone a11y screen (Story 7.4 review).
        child: LayoutBuilder(
          builder: (context, constraints) => SingleChildScrollView(
            child: ConstrainedBox(
              constraints: BoxConstraints(minHeight: constraints.maxHeight),
              child: IntrinsicHeight(
                child: Padding(
                  padding: const EdgeInsets.all(SgartShapes.cardPadding),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const Spacer(),
                      Semantics(
                        header: true,
                        child: Text(
                          localizations.consentGateHeading,
                          key: const Key('consent-gate-heading'),
                          style: Theme.of(context).textTheme.headlineSmall,
                          textAlign: TextAlign.center,
                        ),
                      ),
                      const SizedBox(height: SgartShapes.headingGap),
                      Text(
                        localizations.consentGateBody,
                        key: const Key('consent-gate-body'),
                        style: Theme.of(context).textTheme.bodyMedium,
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: SgartShapes.space2),
                      Center(
                        child: Semantics(
                          label: '${localizations.consentGateNoticeLinkLabel}: $noticeUrl',
                          child: Text(
                            '${localizations.consentGateNoticeLinkLabel}: $noticeUrl',
                            key: const Key('consent-gate-notice-link'),
                            style: Theme.of(context).textTheme.bodySmall,
                            textAlign: TextAlign.center,
                          ),
                        ),
                      ),
                      const Spacer(),
                      BlocBuilder<ConsentCubit, ConsentState>(
                        builder: (context, state) {
                          final isSubmitting = state.status == ConsentGateStatus.loading;
                          return SgartButton(
                            key: const Key('consent-gate-accept-button'),
                            label: localizations.consentGateAcceptButtonLabel,
                            onPressed: isSubmitting ? null : () => context.read<ConsentCubit>().accept(),
                          );
                        },
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
