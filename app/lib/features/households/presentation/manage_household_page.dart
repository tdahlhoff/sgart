import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../invites/data/invites_api.dart';
import '../../invites/presentation/invite_page.dart' show buildInvitePageRoute;
import '../../members/data/members_api.dart';
import '../../members/presentation/members_page.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/stores_api.dart';
import '../../stores/presentation/manage_stores_page.dart';
import '../data/household_summary.dart';
import '../data/households_api.dart';
import 'households_cubit.dart';

/// Pushes [ManageHouseholdPage] with the full dependency set the hub subtree needs re-provided
/// across the pushed root-navigator route boundary — `StoresApi`/`StoreChainReferenceCache` for
/// „Geschäfte", `InvitesApi` for „Einladen", `MembersApi`/`HouseholdsApi`/`HouseholdsCubit` for
/// „Mitglieder" (the Story 1.6 `ProviderNotFoundException` lesson; `HouseholdsCubit` itself is read
/// here because `MembersPage`'s exit listener reads it on leave/delete). The single call site the
/// household switcher uses (Story 8.1), mirroring `openAwaitInvitePage`'s precedent, so the hub's
/// re-provide list lives in exactly one place and a future row cannot silently miss a dependency.
void openManageHouseholdPage(BuildContext context, HouseholdSummary household) {
  Navigator.of(context).push(buildManageHouseholdPageRoute(context, household));
}

/// The read half of [openManageHouseholdPage], split out so a caller that must close another route
/// first (the switcher sheet's „Haushalt verwalten" button, which pops itself) can do the
/// `context.read`s — which need the still-mounted calling context — before popping, then push the
/// already-built route through a navigator reference captured ahead of time. Popping before reading
/// would risk reading through a context Flutter is already tearing down; pushing before popping
/// would land the new route underneath the one being popped instead of on top of it.
MaterialPageRoute<void> buildManageHouseholdPageRoute(BuildContext context, HouseholdSummary household) {
  final storesApi = context.read<StoresApi>();
  final referenceCache = context.read<StoreChainReferenceCache>();
  final invitesApi = context.read<InvitesApi>();
  final membersApi = context.read<MembersApi>();
  final householdsApi = context.read<HouseholdsApi>();
  final householdsCubit = context.read<HouseholdsCubit>();
  return MaterialPageRoute(
    builder: (_) => MultiRepositoryProvider(
      providers: [
        RepositoryProvider<StoresApi>.value(value: storesApi),
        RepositoryProvider<StoreChainReferenceCache>.value(value: referenceCache),
        RepositoryProvider<InvitesApi>.value(value: invitesApi),
        RepositoryProvider<MembersApi>.value(value: membersApi),
        RepositoryProvider<HouseholdsApi>.value(value: householdsApi),
      ],
      child: BlocProvider<HouseholdsCubit>.value(
        value: householdsCubit,
        child: ManageHouseholdPage(household: household),
      ),
    ),
  );
}

/// The thin „Haushalt verwalten" hub (Story 1.8, grown in Story 4.1): hosts the „Geschäfte" row
/// that opens [ManageStoresPage] and the „Einladen" row that opens the invite screen (also reachable
/// directly from the household switcher's promoted row, Story 8.4, F6). Epic 4 continues growing the
/// same hub with members/roles (EXPERIENCE §3), which is why management lives behind a hub rather
/// than bare switcher entries.
class ManageHouseholdPage extends StatelessWidget {
  const ManageHouseholdPage({super.key, required this.household});

  final HouseholdSummary household;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: SgartAppBar(title: localizations.householdsManageHeading),
      body: SafeArea(
        child: ListView(
          children: [
            ListTile(
              key: const Key('manage-stores-row'),
              leading: const Icon(Icons.storefront_outlined),
              title: Text(localizations.storesManageRowLabel),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _openManageStores(context),
            ),
            ListTile(
              key: const Key('manage-invites-row'),
              leading: const Icon(Icons.person_add_outlined),
              title: Text(localizations.invitesManageRowLabel),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _openInvites(context),
            ),
            ListTile(
              key: const Key('manage-members-row'),
              leading: const Icon(Icons.group_outlined),
              title: Text(localizations.membersManageRowLabel),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _openMembers(context),
            ),
          ],
        ),
      ),
    );
  }

  void _openInvites(BuildContext context) {
    Navigator.of(context).push(buildInvitePageRoute(context, household.householdId));
  }

  void _openMembers(BuildContext context) {
    // Re-provide the member-management dependencies across the root-navigator route boundary, the
    // same way stores/invites do (the Story 1.6 ProviderNotFoundException lesson). HouseholdsCubit
    // is re-provided too — this route (and the hub above it) sits on the root Navigator, above
    // FirstRunRouter's BlocProvider<HouseholdsCubit>, not beneath it, so the screen's exit signal
    // (context.read to re-bootstrap after a leave/delete, AC3/AC7) would otherwise crash.
    final membersApi = context.read<MembersApi>();
    final householdsApi = context.read<HouseholdsApi>();
    final householdsCubit = context.read<HouseholdsCubit>();
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => MultiRepositoryProvider(
        providers: [
          RepositoryProvider<MembersApi>.value(value: membersApi),
          RepositoryProvider<HouseholdsApi>.value(value: householdsApi),
        ],
        child: BlocProvider<HouseholdsCubit>.value(
          value: householdsCubit,
          child: MembersPage(household: household),
        ),
      ),
    ));
  }

  void _openManageStores(BuildContext context) {
    // Re-provide the stores dependencies across the root-navigator route boundary, the same way the
    // switcher re-provides its own (the Story 1.6 ProviderNotFoundException lesson).
    final storesApi = context.read<StoresApi>();
    final referenceCache = context.read<StoreChainReferenceCache>();
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => MultiRepositoryProvider(
        providers: [
          RepositoryProvider<StoresApi>.value(value: storesApi),
          RepositoryProvider<StoreChainReferenceCache>.value(value: referenceCache),
        ],
        child: ManageStoresPage(householdId: household.householdId),
      ),
    ));
  }
}
