import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../data/invites_api.dart';
import 'invites_cubit.dart';
import 'invites_view.dart';

/// The invite screen opened from the manage-household hub (Story 4.1, AC7, „Einladen"): sends an
/// invite by email and lists the household's pending invites. Creates its own [InvitesCubit] over
/// the [InvitesApi] provided up the tree, then renders the shared [InvitesView] body (mirrors
/// `ManageStoresPage`).
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
