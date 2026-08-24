import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../shared/widgets/status_label.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/store_summary.dart';
import 'stores_cubit.dart';
import 'stores_state.dart';

/// The reusable store-management body (Story 1.8): the household's active stores each with their
/// chain badge, a free-form „Geschäft hinzufügen" field showing the advisory chain suggestion inline
/// (accept / ändern / löschen), the archive helper copy, and a per-row remove that **archives**.
///
/// It reads its [StoresCubit] from the enclosing provider, so any host that provides one can embed
/// it: the „Haushalt verwalten → Geschäfte" screen ([ManageStoresPage]) and the first-run onboarding
/// wizard's stores step both mount this same view (AC4 of 1.8 — the store-creation path is reusable,
/// not screen-bound; DRY). The host supplies the surrounding chrome (app bar, stepper, buttons).
class StoresManagementView extends StatefulWidget {
  const StoresManagementView({super.key});

  @override
  State<StoresManagementView> createState() => _StoresManagementViewState();
}

class _StoresManagementViewState extends State<StoresManagementView> {
  final TextEditingController _nameController = TextEditingController();

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final cubit = context.read<StoresCubit>();
    await cubit.addStore(_nameController.text);
    if (!mounted) {
      return;
    }
    // Clear the field only when the add actually succeeded (no inline error).
    if (cubit.state.actionError == null && !cubit.state.isSubmitting) {
      _nameController.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<StoresCubit, StoresState>(
      builder: (context, state) {
        return switch (state.status) {
          StoresStatus.loading =>
            const Center(child: CircularProgressIndicator(key: Key('stores-loading'))),
          StoresStatus.failure => const _FailureBody(),
          StoresStatus.ready => _ReadyBody(
              state: state,
              nameController: _nameController,
              onSubmit: _submit,
            ),
        };
      },
    );
  }
}

class _ReadyBody extends StatelessWidget {
  const _ReadyBody({required this.state, required this.nameController, required this.onSubmit});

  final StoresState state;
  final TextEditingController nameController;
  final Future<void> Function() onSubmit;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return SingleChildScrollView(
      padding: const EdgeInsets.all(SgartShapes.cardPadding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (state.stores.isEmpty)
            Text(localizations.storesEmptyStateLabel, key: const Key('stores-empty-state'))
          else
            for (final store in state.stores) _StoreRow(store: store, chainName: _chainNameFor(store)),
          const SizedBox(height: SgartShapes.space2),
          Text(
            localizations.storesArchiveHelperText,
            key: const Key('stores-archive-helper'),
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const Divider(height: SgartShapes.space4),
          TextField(
            key: const Key('store-name-field'),
            controller: nameController,
            decoration: InputDecoration(labelText: localizations.storesAddFieldLabel),
            onChanged: (value) => context.read<StoresCubit>().onNameChanged(value),
          ),
          if (state.chainSuggestion != null && !state.chainCleared) ...[
            const SizedBox(height: SgartShapes.space2),
            Row(
              children: [
                Expanded(
                  child: Text(
                    localizations.storesChainSuggestionLabel(state.chainSuggestion!.name),
                    key: const Key('store-chain-suggestion'),
                  ),
                ),
                if (state.chains.isNotEmpty)
                  TextButton(
                    key: const Key('store-chain-change-button'),
                    onPressed: () => _showChainPicker(context),
                    child: Text(localizations.storesChainChangeButtonLabel),
                  ),
                TextButton(
                  key: const Key('store-chain-clear-button'),
                  onPressed: () => context.read<StoresCubit>().clearSuggestion(),
                  child: Text(localizations.storesChainClearButtonLabel),
                ),
              ],
            ),
          ],
          if (state.actionError != null) ...[
            const SizedBox(height: SgartShapes.space4),
            Text(
              localizedMessageForErrorCode(localizations, state.actionError!.code),
              key: const Key('stores-action-error'),
            ),
          ],
          const SizedBox(height: SgartShapes.space4),
          // Disabled while an add is in flight or the field is blank (nothing to add) — rebuilds on
          // every keystroke via the controller so the blank guard tracks the live text.
          ValueListenableBuilder<TextEditingValue>(
            valueListenable: nameController,
            builder: (context, value, _) {
              final isBlank = value.text.trim().isEmpty;
              return SgartButton(
                key: const Key('store-add-button'),
                label: localizations.storesAddSubmitButtonLabel,
                onPressed: state.isSubmitting || isBlank ? null : () => onSubmit(),
              );
            },
          ),
        ],
      ),
    );
  }

  /// Opens the chain-override picker (AC2 „ändern"): the member chooses a different chain than the
  /// auto-matched suggestion from the cached reference list. Selecting one records it on the cubit;
  /// the suggestion can still be cleared afterwards.
  void _showChainPicker(BuildContext context) {
    final cubit = context.read<StoresCubit>();
    final localizations = AppLocalizations.of(context);
    showModalBottomSheet<void>(
      context: context,
      builder: (sheetContext) {
        return SafeArea(
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Padding(
                  padding: const EdgeInsets.all(SgartShapes.cardPadding),
                  child: Text(
                    localizations.storesChainPickerTitle,
                    style: Theme.of(sheetContext).textTheme.titleMedium,
                  ),
                ),
                for (final chain in state.chains)
                  ListTile(
                    key: Key('store-chain-option-${chain.chainId}'),
                    title: Text(chain.name),
                    onTap: () {
                      cubit.selectChain(chain);
                      Navigator.of(sheetContext).pop();
                    },
                  ),
              ],
            ),
          ),
        );
      },
    );
  }

  /// Resolves a store's chain id to its display name from the cached reference list (single source
  /// of chain names, DRY). Returns `null` for an unlinked store or an id not in the reference list.
  String? _chainNameFor(StoreSummary store) {
    if (store.chainId == null) {
      return null;
    }
    return state.chains.firstWhereOrNull((chain) => chain.chainId == store.chainId)?.name;
  }
}

class _StoreRow extends StatelessWidget {
  const _StoreRow({required this.store, required this.chainName});

  final StoreSummary store;
  final String? chainName;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return ListTile(
      key: Key('store-row-${store.storeId}'),
      contentPadding: EdgeInsets.zero,
      title: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(store.name),
          if (chainName != null) ...[
            const SizedBox(height: SgartShapes.spaceHalfUnit),
            StatusLabel(
              key: Key('store-chain-badge-${store.storeId}'),
              text: chainName!,
              variant: StatusLabelVariant.storeChain,
            ),
          ],
        ],
      ),
      trailing: TextButton(
        key: Key('store-remove-button-${store.storeId}'),
        onPressed: () => context.read<StoresCubit>().archiveStore(store.storeId),
        child: Text(localizations.storesRemoveButtonLabel),
      ),
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
            Text(localizations.errorGenericFallback, key: const Key('stores-load-error')),
            const SizedBox(height: SgartShapes.space4),
            SgartButton(
              key: const Key('stores-retry-button'),
              label: localizations.householdsRetryButtonLabel,
              onPressed: () => context.read<StoresCubit>().bootstrap(),
            ),
          ],
        ),
      ),
    );
  }
}
