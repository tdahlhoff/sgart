import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../data/invites_api.dart';
import 'invites_cubit.dart';
import 'invites_view.dart';

/// The read half of [InvitePage]'s wiring, split out so a caller that must close another route
/// first (the household switcher sheet's promoted „Mitglieder einladen" row, which pops itself) can
/// read the still-mounted calling context's [InvitesApi] before popping, then push the already-built
/// route through a navigator reference captured ahead of time — the same read-pop-push shape as
/// `buildManageHouseholdPageRoute` (Story 8.1). The manage-household hub's own „Einladen" row uses
/// this too, so the invite screen's dependency re-provide lives in exactly one place (Story 8.4).
MaterialPageRoute<void> buildInvitePageRoute(BuildContext context, String householdId) {
  final invitesApi = context.read<InvitesApi>();
  return MaterialPageRoute(
    builder: (_) => RepositoryProvider<InvitesApi>.value(
      value: invitesApi,
      child: InvitePage(householdId: householdId),
    ),
  );
}

/// The invite screen (Story 8.4, F6/F7): shows the household's single active invite code + link
/// (share/copy) and, for an Admin, „Code ersetzen". Opened from the manage-household hub's
/// „Einladen" row and the household switcher's promoted „Mitglieder einladen" row (both via
/// [buildInvitePageRoute]). Creates its own [InvitesCubit] over the [InvitesApi] provided up the
/// tree, then renders the shared [InvitesView] body (mirrors `ManageStoresPage`).
class InvitePage extends StatelessWidget {
  const InvitePage({super.key, required this.householdId});

  final String householdId;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocProvider(
      create: (_) => InvitesCubit(invitesApi: context.read<InvitesApi>(), householdId: householdId)..bootstrap(),
      child: Scaffold(
        appBar: SgartAppBar(title: localizations.invitesHeading),
        body: const SafeArea(child: InvitesView()),
      ),
    );
  }
}
