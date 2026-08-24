import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../data/store_chain_reference_cache.dart';
import '../data/stores_api.dart';
import 'stores_cubit.dart';
import 'stores_management_view.dart';

/// The manage-stores screen (Story 1.8, „Geschäfte" frame): the household's active stores each with
/// their chain badge, a free-form „Geschäft hinzufügen" field that shows the advisory chain
/// suggestion inline (accept / ändern / löschen), and a per-row remove that **archives** (with
/// helper copy explaining past purchases are kept). Creates its own [StoresCubit] over the
/// [StoresApi] and [StoreChainReferenceCache] provided up the tree, then renders the shared
/// [StoresManagementView] body (AC4: the same reusable creation path the onboarding wizard and
/// later pickers mount).
class ManageStoresPage extends StatelessWidget {
  const ManageStoresPage({super.key, required this.householdId});

  final String householdId;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocProvider(
      create: (_) => StoresCubit(
        storesApi: context.read<StoresApi>(),
        referenceCache: context.read<StoreChainReferenceCache>(),
        householdId: householdId,
      )..bootstrap(),
      child: Scaffold(
        appBar: SgartAppBar(title: localizations.storesHeading),
        body: const SafeArea(child: StoresManagementView()),
      ),
    );
  }
}
