import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../invites/data/invites_api.dart';
import '../../invites/presentation/invite_page.dart';
import '../../members/data/members_api.dart';
import '../../members/presentation/members_page.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/stores_api.dart';
import '../../stores/presentation/manage_stores_page.dart';
import '../data/household_summary.dart';
import '../data/households_api.dart';

/// The thin „Haushalt verwalten" hub (Story 1.8, grown in Story 4.1): hosts the „Geschäfte" row
/// that opens [ManageStoresPage] and the „Einladen" row that opens [InvitePage]. Epic 4 continues
/// growing the same hub with members/roles (EXPERIENCE §3), which is why management lives behind a
/// hub rather than bare switcher entries.
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
    // Re-provide InvitesApi across the root-navigator route boundary, the same way stores does
    // (the Story 1.6 ProviderNotFoundException lesson).
    final invitesApi = context.read<InvitesApi>();
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => RepositoryProvider<InvitesApi>.value(
        value: invitesApi,
        child: InvitePage(householdId: household.householdId),
      ),
    ));
  }

  void _openMembers(BuildContext context) {
    // Re-provide the member-management dependencies across the root-navigator route boundary, the
    // same way stores/invites do (the Story 1.6 ProviderNotFoundException lesson). HouseholdsCubit
    // itself is not re-provided — it is already an ancestor of this route (the shell), and the
    // screen's exit signal reads it via context.read to re-bootstrap after a leave/delete (AC3/AC7).
    final membersApi = context.read<MembersApi>();
    final householdsApi = context.read<HouseholdsApi>();
    final invitesApi = context.read<InvitesApi>();
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => MultiRepositoryProvider(
        providers: [
          RepositoryProvider<MembersApi>.value(value: membersApi),
          RepositoryProvider<HouseholdsApi>.value(value: householdsApi),
          RepositoryProvider<InvitesApi>.value(value: invitesApi),
        ],
        child: MembersPage(household: household),
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
