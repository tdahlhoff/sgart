import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../households/presentation/create_or_await_choice_page.dart';
import '../data/consent_api.dart';
import 'consent_cubit.dart';
import 'consent_gate_page.dart';
import 'consent_gate_status_pages.dart';
import 'consent_state.dart';

/// Gates the 0-household gateway on recorded consent (Story 7.4, AC1/AC4, design §3): on entry
/// reads the caller's consent status and, iff not accepted or the accepted version is stale, shows
/// [ConsentGatePage] — blocking both "create household" and "accept invite" until accepted. Once
/// accepted (this load or a fresh one), shows the existing [CreateOrAwaitChoicePage] unchanged, so
/// that page's own behavior/tests stay untouched (SRP — this widget owns only the gate).
class ConsentGatedChoicePage extends StatelessWidget {
  const ConsentGatedChoicePage({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocProvider(
      create: (context) => ConsentCubit(consentApi: context.read<ConsentApi>())..load(),
      child: const _ConsentGatedChoiceBody(),
    );
  }
}

class _ConsentGatedChoiceBody extends StatelessWidget {
  const _ConsentGatedChoiceBody();

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<ConsentCubit, ConsentState>(
      builder: (context, state) {
        return switch (state.status) {
          ConsentGateStatus.loading => const ConsentLoadingPage(),
          ConsentGateStatus.needsConsent => const ConsentGatePage(),
          ConsentGateStatus.accepted => const CreateOrAwaitChoicePage(),
          ConsentGateStatus.failure => ConsentFailurePage(error: state.error),
        };
      },
    );
  }
}
