import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../lists/data/shopping_list_summary.dart';
import '../../lists/presentation/list_overview/shopping_lists_cubit.dart';
import 'active_trips_cubit.dart';
import 'active_trips_state.dart';
import 'trip_screen.dart';

/// The „Einkauf" tab's active-trips index (Story 3.2, AC4, Cl. 3): one row per In-Trip list — name
/// + „Im Einkauf" + item count — each opening its trip screen, with a calm empty state when no list
/// is In-Trip (a household can hold several at once, since at-most-one-trip is *per list*). Reads
/// its [ActiveTripsCubit] from the enclosing provider (scoped to the active household by the shell).
class ActiveTripsView extends StatelessWidget {
  const ActiveTripsView({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<ActiveTripsCubit, ActiveTripsState>(
      builder: (context, state) {
        return switch (state.status) {
          ActiveTripsStatus.loading =>
            const Center(child: CircularProgressIndicator(key: Key('active-trips-loading'))),
          ActiveTripsStatus.failure => const _FailureBody(),
          ActiveTripsStatus.ready => _ReadyBody(state: state),
        };
      },
    );
  }
}

class _ReadyBody extends StatelessWidget {
  const _ReadyBody({required this.state});

  final ActiveTripsState state;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final householdId = context.read<ActiveTripsCubit>().householdId;

    if (state.entries.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(SgartShapes.cardPadding),
          child: Text(
            localizations.tripsIndexEmptyState,
            key: const Key('active-trips-empty-state'),
            textAlign: TextAlign.center,
          ),
        ),
      );
    }
    return SingleChildScrollView(
      padding: const EdgeInsets.all(SgartShapes.cardPadding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(localizations.shellTabShoppingActiveTitle, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: SgartShapes.space4),
          for (final entry in state.entries)
            _ActiveTripRow(
              list: entry.summary,
              // The „Liste N" ordinal derived from the full open-lists sequence (Cl. 3) — matches the
              // overview so an unnamed In-Trip list carries the same number on both surfaces.
              displayName: entry.summary.name ?? localizations.listsDefaultName(entry.ordinal),
              onTap: () async {
                final completed = await TripScreen.push(
                  context,
                  householdId: householdId,
                  listId: entry.summary.listId,
                  listTitle: entry.summary.name ?? localizations.listsDefaultName(entry.ordinal),
                );
                if (completed == true && context.mounted) {
                  // Story 3.4, AC7 — completed trip removes the row from the Einkauf tab and
                  // invalidates the Done archive so the "Erledigt" tab shows the completed list.
                  context.read<ActiveTripsCubit>().refresh();
                  context.read<ShoppingListsCubit>().invalidateArchive();
                }
              },
            ),
        ],
      ),
    );
  }
}

class _ActiveTripRow extends StatelessWidget {
  const _ActiveTripRow({required this.list, required this.displayName, required this.onTap});

  final ShoppingListSummary list;
  final String displayName;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return ListTile(
      key: Key('active-trip-row-${list.listId}'),
      contentPadding: EdgeInsets.zero,
      onTap: onTap,
      title: Text(displayName),
      subtitle: Text('${localizations.listStatusInTrip} · ${localizations.tripsIndexRowItemCount(list.itemCount)}'),
    );
  }
}

class _FailureBody extends StatelessWidget {
  const _FailureBody();

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Center(
      child: Padding(
        padding: const EdgeInsets.all(SgartShapes.cardPadding),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(localizations.errorGenericFallback, key: const Key('active-trips-load-error')),
            const SizedBox(height: SgartShapes.space4),
            SgartButton(
              key: const Key('active-trips-retry-button'),
              label: localizations.householdsRetryButtonLabel,
              onPressed: () => context.read<ActiveTripsCubit>().refresh(),
            ),
          ],
        ),
      ),
    );
  }
}
