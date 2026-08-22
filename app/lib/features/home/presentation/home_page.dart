import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../shared/widgets/status_label.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../../theme/tokens/sgart_typography.dart';
import 'home_cubit.dart';

/// Placeholder landing screen demonstrating the feature-first + BLoC pattern and the shared
/// design-system components (SgartButton, StatusLabel) rendering through the themed tokens.
class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocProvider(
      create: (_) => HomeCubit(),
      // TODO(Story 1.3 localization): these placeholder strings must move behind the
      // locale-driven layer; no user-facing string is hard-coded in the shipped app.
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
                    const Text('Scaffold ready'),
                    const SizedBox(height: SgartShapes.headingGap),
                    const StatusLabel(text: 'Admin', variant: StatusLabelVariant.admin),
                    const SizedBox(height: SgartShapes.space4),
                    Text(
                      'probes: $probeCount',
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
                      label: 'Probe',
                      onPressed: () => context.read<HomeCubit>().registerProbe(),
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
