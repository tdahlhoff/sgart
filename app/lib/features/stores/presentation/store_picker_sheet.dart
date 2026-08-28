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
/// realization of the reusable UX-DR22 component, consumed here by list-detail and later by Story
/// 3.1 (trip start) / 3.2 (in-trip reroute). Lists [stores] (the household's already-loaded active
/// stores — the sheet never re-fetches them, so a caller controls freshness, Cl. 9) plus a
/// persistent „+ Neues Geschäft" inline-create row with the live advisory chain suggestion
/// (reusing the Story 1.8 [StoreChainMatcher] + [StoreChainReferenceCache] pieces — no duplicated
/// matching logic). Tapping an existing store, or successfully adding a new one, pops the chosen
/// [StoreSummary]; a dismiss pops `null`.
///
/// Kept focused (Cl. 8): single-select, returns one store — no trip multi-select (Story 3.1's
/// extension, not built here, YAGNI).
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
    Navigator.of(context).pop(StoreSummary(storeId: storeId, name: trimmedName, chainId: chainId));
  }

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
