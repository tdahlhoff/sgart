import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../households/presentation/await_invite_page.dart';
import '../../households/presentation/households_cubit.dart';
import '../../invites/data/invite_link.dart';
import '../../invites/data/invites_api.dart';
import '../data/consent_api.dart';
import 'consent_cubit.dart';
import 'consent_gate_page.dart';
import 'consent_gate_status_pages.dart';
import 'consent_state.dart';

/// Gates a deep-link/cold-start invite accept on recorded consent (Story 7.4 review, AC1/AC3
/// completeness) — mirrors [ConsentGatedChoicePage], but for the one accept path that reaches
/// [AwaitInvitePage] without ever passing through the 0-household gateway: `FirstRunRouterBody`'s
/// `_PendingInviteLinkRouter`, which an OS deep link and a "link offered while signed out" link
/// both use (Story 4.6). Without this, an unconsented caller could join a household — and start
/// household-personal-data processing — through the one accept path the gate never fronted.
///
/// Pushed as its own route (mirrors [openAwaitInvitePage]) so it can swap its own body from the
/// gate to the accept screen once consent is recorded, with no second navigation.
void openConsentGatedAwaitInvitePage(BuildContext context, {required InviteLink link}) {
  final consentApi = context.read<ConsentApi>();
  final invitesApi = context.read<InvitesApi>();
  final householdsCubit = context.read<HouseholdsCubit>();
  Navigator.of(context).push(
    MaterialPageRoute(
      builder: (_) => MultiRepositoryProvider(
        providers: [
          RepositoryProvider<ConsentApi>.value(value: consentApi),
          RepositoryProvider<InvitesApi>.value(value: invitesApi),
        ],
        child: BlocProvider<HouseholdsCubit>.value(
          value: householdsCubit,
          child: BlocProvider(
            create: (context) => ConsentCubit(consentApi: context.read<ConsentApi>())..load(),
            child: _ConsentGatedAwaitInviteBody(link: link),
          ),
        ),
      ),
    ),
  );
}

class _ConsentGatedAwaitInviteBody extends StatelessWidget {
  const _ConsentGatedAwaitInviteBody({required this.link});

  final InviteLink link;

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<ConsentCubit, ConsentState>(
      builder: (context, state) {
        return switch (state.status) {
          ConsentGateStatus.loading => const ConsentLoadingPage(),
          ConsentGateStatus.needsConsent => const ConsentGatePage(),
          ConsentGateStatus.accepted => AwaitInvitePage(initialLink: link),
          ConsentGateStatus.failure => ConsentFailurePage(error: state.error),
        };
      },
    );
  }
}
