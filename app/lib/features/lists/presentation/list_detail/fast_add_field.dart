import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../l10n/formatting/quantity_formatter.dart' as formatting;
import '../../../../l10n/gen/app_localizations.dart';
import '../../../../theme/tokens/sgart_shapes.dart';
import '../../data/item_suggestion.dart';
import 'list_detail_cubit.dart';
import 'list_detail_state.dart';

/// The persistent fast-add field (Story 2.5, AC2/AC3/AC4, Cl. 3) — the *only* add surface on an
/// Open list detail screen, replacing the Story 2.3 „+ Artikel hinzufügen" button and its
/// `showItemFormSheet` add path (the sheet remains, but only for editing an existing item). Holds
/// only the name (Cl. 3 — fast capture is the hero; quantity/note override happens via the
/// just-added row's existing edit sheet, not here). On focus + non-empty text, an upward suggestion
/// panel lists matching household suggestions (AC1) plus the always-present "add as new" row (AC3,
/// mirroring `screen-list-detail.html` State B — there is no room below the field for the panel).
class FastAddField extends StatefulWidget {
  const FastAddField({super.key, required this.cubit});

  final ListDetailCubit cubit;

  @override
  State<FastAddField> createState() => _FastAddFieldState();
}

class _FastAddFieldState extends State<FastAddField> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    // The panel's visibility depends on both focus and text — rebuild on either change.
    _focusNode.addListener(() => setState(() {}));
    _controller.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  bool get _showPanel => _focusNode.hasFocus && _controller.text.trim().isNotEmpty;

  /// Tapping a suggestion adds immediately with its last-used quantity/note prefilled (AC2) — no
  /// pre-commit editing; overriding happens via the just-added row's edit sheet (Cl. 3). When the
  /// suggestion carries a still-active last-used store, the cubit also assigns it in the same call
  /// (add-then-assign, Story 2.6, AC6).
  Future<void> _addSuggestion(ItemSuggestion suggestion) async {
    if (widget.cubit.state.isSubmitting) {
      return;
    }
    final succeeded = await widget.cubit.addItemFromSuggestion(suggestion);
    _clearOnSuccess(succeeded);
  }

  /// The "add as new"/keyboard-submit path (AC3) — the Story 2.3 defaults (1 Stück, no note), one
  /// action, no sheet. Works even when the suggestion set is empty or still loading.
  Future<void> _addAsNew() async {
    if (widget.cubit.state.isSubmitting) {
      return;
    }
    final text = _controller.text.trim();
    if (text.isEmpty) {
      return;
    }
    final succeeded = await widget.cubit.addItem(name: text, note: null, amount: '1', unit: 'PIECE');
    _clearOnSuccess(succeeded);
  }

  /// Clears the field after a successful add — and *keeps the focus and the keyboard*, so the next
  /// article is one keystroke away (Cl. 3, fast capture is the hero; the panel hides itself once the
  /// text is empty, so dismissing it needs no unfocus). A failure keeps the typed text so the member
  /// can retry; the rejection shows as the existing inline `actionError`. Guarded by `mounted`: the
  /// route can be popped while the add is still in flight, which disposes the controller.
  void _clearOnSuccess(bool succeeded) {
    if (!mounted || !succeeded) {
      return;
    }
    _controller.clear();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocBuilder<ListDetailCubit, ListDetailState>(
      bloc: widget.cubit,
      builder: (context, state) {
        final query = _controller.text;
        final suggestions = _visibleSuggestions(widget.cubit.suggestionsMatching(query));
        return Material(
          elevation: 4,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_showPanel)
                _SuggestionPanel(
                  suggestions: suggestions,
                  query: query.trim(),
                  onSuggestionTap: _addSuggestion,
                  onAddAsNew: _addAsNew,
                  storeNameFor: (storeId) => widget.cubit.storeFor(storeId)?.name,
                ),
              Padding(
                padding: const EdgeInsets.all(SgartShapes.cardPadding),
                child: Semantics(
                  label: localizations.fastAddFieldPlaceholder,
                  textField: true,
                  child: TextField(
                    key: const Key('fast-add-field'),
                    controller: _controller,
                    focusNode: _focusNode,
                    // `readOnly` rather than `enabled: false` while a submit is in flight: disabling
                    // the field would drop its focus and dismiss the keyboard on every add, which is
                    // exactly what fast capture must not do (Cl. 3). The cubit ignores re-entrant
                    // submits anyway, and both add paths guard on `isSubmitting`.
                    readOnly: state.isSubmitting,
                    textInputAction: TextInputAction.done,
                    decoration: InputDecoration(hintText: localizations.fastAddFieldPlaceholder),
                    // The default `done` handler unfocuses the field and drops the keyboard, which
                    // would cost a re-tap per article; the add itself runs from onSubmitted.
                    onEditingComplete: () {},
                    onSubmitted: (_) => _addAsNew(),
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  /// The panel shows at most [_maxVisibleSuggestions] rows (plus the always-present "add as new"
  /// row) — a taller panel would bury the list it is adding to. Truncating the tail is safe for the
  /// name the member is actually typing: every match shares the typed prefix, so an exact match is
  /// the shortest of them and alphabetical order always puts it first (Cl. 6).
  static List<ItemSuggestion> _visibleSuggestions(List<ItemSuggestion> matches) =>
      matches.take(_maxVisibleSuggestions).toList();
}

/// How many suggestion rows the upward panel shows at once.
const int _maxVisibleSuggestions = 6;

class _SuggestionPanel extends StatelessWidget {
  const _SuggestionPanel({
    required this.suggestions,
    required this.query,
    required this.onSuggestionTap,
    required this.onAddAsNew,
    required this.storeNameFor,
  });

  final List<ItemSuggestion> suggestions;
  final String query;
  final ValueChanged<ItemSuggestion> onSuggestionTap;
  final VoidCallback onAddAsNew;

  /// Resolves a suggestion's `defaultStoreId` to its active store name, or `null` when unassigned
  /// or archived (Story 2.6, AC6) — mirrors `ListDetailCubit.storeFor`.
  final String? Function(String? storeId) storeNameFor;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return ConstrainedBox(
      constraints: const BoxConstraints(maxHeight: 280),
      child: SingleChildScrollView(
        reverse: true,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final suggestion in suggestions)
              _SuggestionRow(
                suggestion: suggestion,
                onTap: () => onSuggestionTap(suggestion),
                lastUsedStoreName: storeNameFor(suggestion.defaultStoreId),
              ),
            ListTile(
              key: const Key('fast-add-new-row'),
              dense: true,
              title: Text(localizations.fastAddNewItemAction(query)),
              onTap: onAddAsNew,
            ),
          ],
        ),
      ),
    );
  }
}

class _SuggestionRow extends StatelessWidget {
  const _SuggestionRow({required this.suggestion, required this.onTap, required this.lastUsedStoreName});

  final ItemSuggestion suggestion;
  final VoidCallback onTap;

  /// The suggestion's last-used store name, resolved against the active store list — `null` when
  /// the name has no last-used store, or it is no longer active (Story 2.6, AC6/AC4).
  final String? lastUsedStoreName;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final normalizedName = suggestion.name.trim().toLowerCase();
    final amount = double.tryParse(suggestion.amount) ?? 0;
    final unit = formatting.unitFromServerName(suggestion.unit) ?? formatting.Unit.piece;
    final quantityText = const formatting.QuantityFormatter().format(amount, unit, localizations);
    final storeChipText =
        lastUsedStoreName == null ? null : localizations.suggestionLastUsedStore(lastUsedStoreName!);

    // One semantics node reading „<name>, <quantity>[, zuletzt <store>]" as a button — without it a
    // screen reader announces the name and the prefill hint as unrelated fragments (UX-DR5).
    final semanticsLabel =
        storeChipText == null ? '${suggestion.name}, $quantityText' : '${suggestion.name}, $quantityText, $storeChipText';

    return Semantics(
      button: true,
      label: semanticsLabel,
      child: ListTile(
        key: Key('fast-add-suggestion-$normalizedName'),
        dense: true,
        title: Text(suggestion.name),
        subtitle: storeChipText == null
            ? null
            : Text(storeChipText, key: Key('fast-add-suggestion-store-$normalizedName')),
        trailing: Text(quantityText),
        onTap: onTap,
      ),
    );
  }
}
