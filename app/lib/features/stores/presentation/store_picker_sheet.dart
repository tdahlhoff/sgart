import 'package:collection/collection.dart';
import 'package:flutter/material.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/commands/command_intent.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/http/app_exception.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/store_chain.dart';
import '../data/store_chain_matcher.dart';
import '../data/store_chain_reference_cache.dart';
import '../data/store_summary.dart';
import '../data/stores_api.dart';

/// Opens the reusable store picker sheet (Story 2.6, AC1, AC2, UX-DR22, Cl. 8) — the first
/// realization of the reusable UX-DR22 component, consumed here by list-detail and by the Story
/// 3.1 trip-start multi-select ([showTripStoreSelectionSheet], which extends the same inline-create
/// row rather than duplicating it, Cl. 4). Lists [stores] (the household's already-loaded active
/// stores — the sheet never re-fetches them, so a caller controls freshness, Cl. 9) plus a
/// persistent „+ Neues Geschäft" inline-create row with the live advisory chain suggestion
/// (reusing the Story 1.8 [StoreChainMatcher] + [StoreChainReferenceCache] pieces — no duplicated
/// matching logic). Tapping an existing store, or successfully adding a new one, pops the chosen
/// [StoreSummary]; a dismiss pops `null`.
///
/// Single-select, returns one store — kept working unchanged (Cl. 4) since list-detail's existing
/// assignment flow and Story 3.2's in-trip reroute both reuse it as-is.
Future<StoreSummary?> showStorePickerSheet(
  BuildContext context, {
  required List<StoreSummary> stores,
  required StoresApi storesApi,
  required StoreChainReferenceCache referenceCache,
  required String householdId,
  StoreChainMatcher matcher = const StoreChainMatcher(),
}) {
  return showModalBottomSheet<StoreSummary>(
    context: context,
    isScrollControlled: true,
    builder: (_) => _StorePickerSheetBody(
      stores: stores,
      storesApi: storesApi,
      referenceCache: referenceCache,
      householdId: householdId,
      matcher: matcher,
    ),
  );
}

/// Opens the trip store-selection sheet — the Story 3.1 multi-select extension of the reusable
/// store picker (AC1, AC3, AC4, UX-DR22, Cl. 4). Checkbox rows over [stores] plus the same
/// persistent inline-create row (a newly created store is added to the selection, AC4 — resolves
/// the zero-stores household). The confirm button is disabled until at least one store is selected
/// (AC3, UX-DR17); confirming pops the selected [StoreSummary]s. A dismiss pops `null`.
Future<List<StoreSummary>?> showTripStoreSelectionSheet(
  BuildContext context, {
  required List<StoreSummary> stores,
  required StoresApi storesApi,
  required StoreChainReferenceCache referenceCache,
  required String householdId,
  StoreChainMatcher matcher = const StoreChainMatcher(),
}) {
  return showModalBottomSheet<List<StoreSummary>>(
    context: context,
    isScrollControlled: true,
    builder: (_) => _TripStoreSelectionSheetBody(
      stores: stores,
      storesApi: storesApi,
      referenceCache: referenceCache,
      householdId: householdId,
      matcher: matcher,
    ),
  );
}

class _StorePickerSheetBody extends StatefulWidget {
  const _StorePickerSheetBody({
    required this.stores,
    required this.storesApi,
    required this.referenceCache,
    required this.householdId,
    required this.matcher,
  });

  final List<StoreSummary> stores;
  final StoresApi storesApi;
  final StoreChainReferenceCache referenceCache;
  final String householdId;
  final StoreChainMatcher matcher;

  @override
  State<_StorePickerSheetBody> createState() => _StorePickerSheetBodyState();
}

class _StorePickerSheetBodyState extends State<_StorePickerSheetBody> {
  List<StoreChain> _chains = const [];

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return SafeArea(
      child: Padding(
        key: const Key('store-picker-sheet'),
        padding: EdgeInsets.only(
          left: SgartShapes.cardPadding,
          right: SgartShapes.cardPadding,
          top: SgartShapes.cardPadding,
          bottom: MediaQuery.of(context).viewInsets.bottom + SgartShapes.cardPadding,
        ),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(localizations.storePickerTitle, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: SgartShapes.space4),
              for (final store in widget.stores)
                ListTile(
                  key: Key('store-picker-option-${store.storeId}'),
                  contentPadding: EdgeInsets.zero,
                  minVerticalPadding: SgartShapes.space3,
                  title: Text(store.name),
                  subtitle: _chainNameFor(store) == null ? null : Text(_chainNameFor(store)!),
                  onTap: () => Navigator.of(context).pop(store),
                ),
              const Divider(height: SgartShapes.space4),
              InlineCreateStoreRow(
                storesApi: widget.storesApi,
                referenceCache: widget.referenceCache,
                householdId: widget.householdId,
                matcher: widget.matcher,
                onChainsLoaded: (chains) => setState(() => _chains = chains),
                onCreated: (store) => Navigator.of(context).pop(store),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Resolves a store's chain id to its display name from the cached reference list (single source
  /// of chain names, DRY — mirrors `stores_management_view._chainNameFor`).
  String? _chainNameFor(StoreSummary store) {
    if (store.chainId == null) {
      return null;
    }
    return _chains.firstWhereOrNull((chain) => chain.chainId == store.chainId)?.name;
  }
}

class _TripStoreSelectionSheetBody extends StatefulWidget {
  const _TripStoreSelectionSheetBody({
    required this.stores,
    required this.storesApi,
    required this.referenceCache,
    required this.householdId,
    required this.matcher,
  });

  final List<StoreSummary> stores;
  final StoresApi storesApi;
  final StoreChainReferenceCache referenceCache;
  final String householdId;
  final StoreChainMatcher matcher;

  @override
  State<_TripStoreSelectionSheetBody> createState() => _TripStoreSelectionSheetBodyState();
}

class _TripStoreSelectionSheetBodyState extends State<_TripStoreSelectionSheetBody> {
  late List<StoreSummary> _stores = widget.stores;
  final Set<String> _selectedStoreIds = {};
  List<StoreChain> _chains = const [];

  void _toggle(String storeId, bool? selected) {
    setState(() {
      if (selected ?? false) {
        _selectedStoreIds.add(storeId);
      } else {
        _selectedStoreIds.remove(storeId);
      }
    });
  }

  void _onCreated(StoreSummary store) {
    // Inline creation adds the new store to the selection (AC4) — the zero-stores household case.
    setState(() {
      _stores = [..._stores, store];
      _selectedStoreIds.add(store.storeId);
    });
  }

  void _confirm() {
    final selected = _stores.where((store) => _selectedStoreIds.contains(store.storeId)).toList();
    Navigator.of(context).pop(selected);
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return SafeArea(
      child: Padding(
        key: const Key('trip-store-selection-sheet'),
        padding: EdgeInsets.only(
          left: SgartShapes.cardPadding,
          right: SgartShapes.cardPadding,
          top: SgartShapes.cardPadding,
          bottom: MediaQuery.of(context).viewInsets.bottom + SgartShapes.cardPadding,
        ),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(localizations.tripStoreSelectionTitle, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: SgartShapes.space2),
              Text(localizations.tripStoreSelectionHelper, style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: SgartShapes.space4),
              for (final store in _stores)
                CheckboxListTile(
                  key: Key('trip-store-option-${store.storeId}'),
                  contentPadding: EdgeInsets.zero,
                  controlAffinity: ListTileControlAffinity.leading,
                  title: Text(store.name),
                  subtitle: _chainNameFor(store) == null ? null : Text(_chainNameFor(store)!),
                  value: _selectedStoreIds.contains(store.storeId),
                  onChanged: (selected) => _toggle(store.storeId, selected),
                ),
              const Divider(height: SgartShapes.space4),
              InlineCreateStoreRow(
                storesApi: widget.storesApi,
                referenceCache: widget.referenceCache,
                householdId: widget.householdId,
                matcher: widget.matcher,
                onChainsLoaded: (chains) => setState(() => _chains = chains),
                onCreated: _onCreated,
              ),
              const SizedBox(height: SgartShapes.space4),
              SgartButton(
                key: const Key('trip-store-selection-confirm'),
                label: localizations.tripStoreSelectionConfirm,
                onPressed: _selectedStoreIds.isEmpty ? null : _confirm,
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Resolves a store's chain id to its display name from the cached reference list (single source
  /// of chain names, DRY — mirrors `stores_management_view._chainNameFor`).
  String? _chainNameFor(StoreSummary store) {
    if (store.chainId == null) {
      return null;
    }
    return _chains.firstWhereOrNull((chain) => chain.chainId == store.chainId)?.name;
  }
}

/// The shared „+ Neues Geschäft" inline-create-store row (Story 1.8/2.6/3.1) — the live advisory
/// chain suggestion + submit, factored out so the single-select ([_StorePickerSheetBody]) and
/// multi-select ([_TripStoreSelectionSheetBody]) picker bodies stay DRY (Cl. 4) without duplicating
/// [StoreChainMatcher] matching logic. [onCreated] receives the newly created store; the caller
/// decides what that means (pop it, or add it to a selection). [onChainsLoaded] hands the parent
/// the cached chain reference list so it can resolve existing stores' chain names too.
class InlineCreateStoreRow extends StatefulWidget {
  const InlineCreateStoreRow({
    super.key,
    required this.storesApi,
    required this.referenceCache,
    required this.householdId,
    required this.matcher,
    required this.onCreated,
    required this.onChainsLoaded,
  });

  final StoresApi storesApi;
  final StoreChainReferenceCache referenceCache;
  final String householdId;
  final StoreChainMatcher matcher;
  final ValueChanged<StoreSummary> onCreated;
  final ValueChanged<List<StoreChain>> onChainsLoaded;

  @override
  State<InlineCreateStoreRow> createState() => _InlineCreateStoreRowState();
}

class _InlineCreateStoreRowState extends State<InlineCreateStoreRow> {
  final TextEditingController _nameController = TextEditingController();
  final CommandIntent _addIntent = CommandIntent(hasResourceId: true);

  List<StoreChain> _chains = const [];
  StoreChain? _chainSuggestion;
  bool _chainCleared = false;
  bool _isSubmitting = false;
  String? _errorCode;

  @override
  void initState() {
    super.initState();
    _nameController.addListener(_onNameChanged);
    _loadChains();
  }

  @override
  void dispose() {
    _nameController.removeListener(_onNameChanged);
    _nameController.dispose();
    super.dispose();
  }

  /// Best-effort — the store list still renders even with no chain reference (offline first load,
  /// no cache); chain suggestions are simply unavailable (mirrors `StoresCubit._loadChains`).
  Future<void> _loadChains() async {
    try {
      final chains = await widget.referenceCache.load(widget.storesApi);
      if (!mounted) {
        return;
      }
      setState(() => _chains = chains);
      widget.onChainsLoaded(chains);
    } on Object {
      // Matching degrades to unavailable — the picker still works for existing stores + a plain,
      // unlinked new store.
    }
  }

  void _onNameChanged() {
    final suggestion = widget.matcher.suggestFor(_nameController.text, _chains);
    setState(() {
      _chainSuggestion = suggestion;
      if (suggestion != null) {
        _chainCleared = false;
      }
    });
  }

  Future<void> _submitNewStore() async {
    final trimmedName = _nameController.text.trim();
    if (trimmedName.isEmpty || _isSubmitting) {
      return;
    }
    final chainId = _chainCleared ? null : _chainSuggestion?.chainId;
    _addIntent.beginAttempt(trimmedName);
    final commandId = _addIntent.commandId;
    final storeId = _addIntent.resourceId();
    setState(() {
      _isSubmitting = true;
      _errorCode = null;
    });
    try {
      await widget.storesApi.addStore(
        widget.householdId,
        trimmedName,
        storeId: storeId,
        chainId: chainId,
        commandId: commandId,
      );
    } on Object catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _isSubmitting = false;
        _errorCode = error is AppException ? error.error.code : 'stores.unknown';
      });
      return;
    }
    _addIntent.complete();
    if (!mounted) {
      return;
    }
    final createdStore = StoreSummary(storeId: storeId, name: trimmedName, chainId: chainId);
    setState(() {
      _isSubmitting = false;
      _nameController.clear();
      _chainSuggestion = null;
      _chainCleared = false;
    });
    widget.onCreated(createdStore);
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(localizations.storePickerAddNewAction, style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: SgartShapes.space2),
        TextField(
          key: const Key('store-picker-new-name-field'),
          controller: _nameController,
          decoration: InputDecoration(labelText: localizations.storePickerNewStoreNameFieldLabel),
        ),
        if (_chainSuggestion != null && !_chainCleared) ...[
          const SizedBox(height: SgartShapes.space2),
          Row(
            children: [
              Expanded(
                child: Text(
                  localizations.storesChainSuggestionLabel(_chainSuggestion!.name),
                  key: const Key('store-picker-chain-suggestion'),
                ),
              ),
              TextButton(
                key: const Key('store-picker-chain-clear-button'),
                onPressed: () => setState(() => _chainCleared = true),
                child: Text(localizations.storesChainClearButtonLabel),
              ),
            ],
          ),
        ],
        if (_errorCode != null) ...[
          const SizedBox(height: SgartShapes.space2),
          Text(
            localizedMessageForErrorCode(localizations, _errorCode!),
            key: const Key('store-picker-error'),
          ),
        ],
        const SizedBox(height: SgartShapes.space4),
        ValueListenableBuilder<TextEditingValue>(
          valueListenable: _nameController,
          builder: (context, value, _) {
            final isBlank = value.text.trim().isEmpty;
            return SgartButton(
              key: const Key('store-picker-add-new'),
              label: localizations.storePickerAddSubmitButtonLabel,
              onPressed: _isSubmitting || isBlank ? null : _submitNewStore,
            );
          },
        ),
      ],
    );
  }
}
