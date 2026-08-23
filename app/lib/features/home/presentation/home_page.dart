import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/formatting/quantity_formatter.dart';
import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../shared/widgets/status_label.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../../theme/tokens/sgart_typography.dart';
import 'home_cubit.dart';

/// Placeholder landing screen demonstrating the feature-first + BLoC pattern, the shared
/// design-system components (SgartButton, StatusLabel), and the locale-driven localization +
/// formatting layer (Story 1.3) rendering through the themed tokens.
class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocProvider(
      create: (_) => HomeCubit(),
      // "SGART" is the app's brand name, not translatable copy — it stays as-is per locale.
      child: Scaffold(
        appBar: const SgartAppBar(title: 'SGART'),
        body: SafeArea(
          child: BlocBuilder<HomeCubit, int>(
            builder: (context, probeCount) {
              // Scrollable so the screen reflows instead of overflowing in landscape,
              // split-screen, or at large OS text scales (DESIGN §5).
              return SingleChildScrollView(
                padding: const EdgeInsets.all(SgartShapes.cardPadding),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    Text(localizations.homeScaffoldReadyLabel),
                    const SizedBox(height: SgartShapes.headingGap),
                    StatusLabel(
                      text: localizations.homeAdminStatusLabel,
                      variant: StatusLabelVariant.admin,
                    ),
                    const SizedBox(height: SgartShapes.space4),
                    Text(
                      // The generated accessor already formats {count} through `intl`
                      // (de-DE comma decimal), keeping the tabular-figures demo meaningful.
                      localizations.homeProbeCountLabel(probeCount),
                      key: const Key('probe-count'),
                      // A live-updating count: tabular figures keep the digits from
                      // shifting as it changes (DESIGN §2).
                      style: SgartTypography.withTabularFigures(
                        Theme.of(context).textTheme.labelLarge ?? const TextStyle(),
                      ),
                    ),
                    const SizedBox(height: SgartShapes.space4),
                    SgartButton(
                      key: const Key('probe-button'),
                      label: localizations.homeProbeButtonLabel,
                      onPressed: () => context.read<HomeCubit>().registerProbe(),
                    ),
                    const SizedBox(height: SgartShapes.space4),
                    Text(
                      '${localizations.homeFormattingDemoLabel}: '
                      '${const QuantityFormatter().format(0.5, Unit.kilogram, localizations)}',
                      key: const Key('formatting-demo'),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}
