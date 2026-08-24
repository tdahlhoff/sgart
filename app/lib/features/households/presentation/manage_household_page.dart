import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/stores_api.dart';
import '../../stores/presentation/manage_stores_page.dart';
import '../data/household_summary.dart';

/// The thin „Haushalt verwalten" hub (Story 1.8): today it hosts a single „Geschäfte" row that
/// opens [ManageStoresPage]. Epic 4 grows the same hub with members/invites/roles (EXPERIENCE §3),
/// which is why store management lives behind a hub rather than a bare switcher entry.
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
          ],
        ),
      ),
    );
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
