import 'package:flutter/material.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';

/// Informational dead-end (AC1 — "no invite flow, just the branch"): invite acceptance ships in
/// Epic 4. This screen only explains what will eventually happen and lets the caller go back.
class AwaitInvitePage extends StatelessWidget {
  const AwaitInvitePage({super.key});

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
              Text(localizations.householdsAwaitInviteHeading),
              const SizedBox(height: SgartShapes.headingGap),
              Text(localizations.householdsAwaitInviteBody, textAlign: TextAlign.center),
              const SizedBox(height: SgartShapes.space4),
              SgartButton(
                key: const Key('await-invite-back-button'),
                label: localizations.householdsBackButtonLabel,
                variant: SgartButtonVariant.secondary,
                onPressed: () => Navigator.of(context).pop(),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
