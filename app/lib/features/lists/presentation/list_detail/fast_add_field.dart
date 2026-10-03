import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../l10n/formatting/quantity_formatter.dart' as formatting;
import '../../../../l10n/gen/app_localizations.dart';
import '../../../../shared/widgets/inline_action_error_text.dart';
import '../../../../theme/sgart_theme_access.dart';
import '../../../../theme/tokens/sgart_colors.dart';
import '../../../../theme/tokens/sgart_shapes.dart';
import '../../data/fast_add_entry.dart';
import '../../data/item_suggestion.dart';
import 'list_detail_cubit.dart';
import 'list_detail_state.dart';

/// The persistent fast-add field (Story 2.5, AC2/AC3/AC4, Cl. 3) — the *only* add surface on an
/// Open list detail screen, replacing the Story 2.3 „+ Artikel hinzufügen" button and its
/// `showItemFormSheet` add path (the sheet remains, but only for editing an existing item). Holds
/// one line of text (Cl. 3 — fast capture is the hero): the name, optionally led by a quantity and
/// unit (`5 Milch`, `0,5 l Milch`); the note is still overridden via the just-added row's edit
/// sheet. Pinned at the **bottom** of the list-detail
/// screen — thumb reach, proximity to the keyboard, and a newly added item (appended to the end of
/// the list, closest to the field) staying in view are all reasons this stays put rather than
/// moving to the top (considered and reverted, 2026-09-13). On focus + non-empty text, an upward
/// suggestion panel lists matching household suggestions (AC1) plus the always-present "add as
/// new" row (AC3, mirroring `screen-list-detail.html` State B — there is no room below the field
/// for the panel).
class FastAddField extends StatefulWidget {
  const FastAddField({super.key, required this.cubit});

  final ListDetailCubit cubit;

  @override
  State<FastAddField> createState() => _FastAddFieldState();
}

class _FastAddFieldState extends State<FastAddField> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  final OverlayPortalController _panelController = OverlayPortalController();
  final LayerLink _footerLink = LayerLink();
  String _lastTypedText = '';

  @override
  void initState() {
    super.initState();
    // The panel's visibility depends on both focus and text — rebuild on either change.
    _focusNode.addListener(() => setState(() {}));
    _controller.addListener(_onControllerChanged);
  }

  /// A rejection concerns the text that was just sent: once the member edits it, the message has
  /// done its job. Cursor/selection moves also notify this listener, so only a changed text counts.
  void _onControllerChanged() {
    final typedText = _controller.text;
    if (typedText != _lastTypedText) {
      _lastTypedText = typedText;
      widget.cubit.dismissActionError();
    }
    setState(() {});
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
    final entry = widget.cubit.fastAddEntryForSuggestion(suggestion, _controller.text);
    final succeeded = await widget.cubit.addFastAddEntry(entry);
    _clearOnSuccess(succeeded);
  }

  /// The "add as new"/keyboard-submit path (AC3) — one action, no sheet. The typed text decides the
  /// quantity (`5 Milch`); whatever it leaves open comes from the household's remembered article, else
  /// 1 Stück. Works even when the suggestion set is empty or still loading.
  Future<void> _addAsNew() async {
    if (widget.cubit.state.isSubmitting) {
      return;
    }
    final text = _controller.text.trim();
    if (text.isEmpty) {
      return;
    }
    final succeeded = await widget.cubit.addFastAddEntry(widget.cubit.fastAddEntryFor(text));
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
        final colors = context.sgartColors;
        _syncPanelVisibility();
        // The suggestions float over the list, anchored to the top of this footer: the footer keeps its
        // height however many suggestions there are, so the panel never squeezes the list it adds to.
        return CompositedTransformTarget(
          link: _footerLink,
          child: OverlayPortal(
            controller: _panelController,
            overlayChildBuilder: (_) => _buildFloatingPanel(),
            // Flat-forward (DESIGN §3): a top hairline separates the add bar from the list, not a shadow.
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: colors.surface,
                border: Border(
                  top: BorderSide(color: colors.border, width: SgartShapes.hairline),
                ),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // Pinned right above the field — where the member's eyes already are — instead of at
                  // the end of a list that may be scrolled out of view; one small line, so it costs
                  // little space.
                  if (state.actionError != null)
                    Padding(
                      // The panel dips into the footer, so its overlap counts towards the top gap: the
                      // message then sits centred between the suggestions and the input.
                      padding: const EdgeInsets.only(top: _panelOverlapIntoFooter + _errorTextGap),
                      child: InlineActionErrorText(error: state.actionError),
                    ),
                  Padding(
                    padding: EdgeInsets.fromLTRB(
                      SgartShapes.cardPadding,
                      state.actionError == null ? SgartShapes.cardPadding : _errorTextGapToField,
                      SgartShapes.cardPadding,
                      SgartShapes.cardPadding,
                    ),
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
            ),
          ),
        );
      },
    );
  }

  /// Shows or hides the overlay after the frame — an overlay cannot be toggled while building.
  void _syncPanelVisibility() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _panelController.isShowing == _showPanel) {
        return;
      }
      _showPanel ? _panelController.show() : _panelController.hide();
    });
  }

  Widget _buildFloatingPanel() {
    final query = _controller.text;
    // The overlay hands its children the full screen as a tight constraint; `Align` loosens it so the
    // panel is only as tall as its rows, and the follower then pins that box onto the footer.
    return Align(
      alignment: Alignment.topLeft,
      child: CompositedTransformFollower(
        link: _footerLink,
        targetAnchor: Alignment.topLeft,
        followerAnchor: Alignment.bottomLeft,
        offset: const Offset(0, _panelOverlapIntoFooter),
        child: SizedBox(
          width: _footerLink.leaderSize?.width,
          child: _SuggestionPanel(
            suggestions: _visibleSuggestions(widget.cubit.suggestionsMatching(query)),
            newItemEntry: widget.cubit.fastAddEntryFor(query),
            onSuggestionTap: _addSuggestion,
            onAddAsNew: _addAsNew,
            storeNameFor: (storeId) => widget.cubit.storeFor(storeId)?.name,
          ),
        ),
      ),
    );
  }

  /// The panel shows at most [_maxVisibleSuggestions] rows (plus the always-present "add as new"
  /// row) — a taller panel would bury the list it is adding to. Truncating the tail is safe for the
  /// name the member is actually typing: every match shares the typed prefix, so an exact match is
  /// the shortest of them and alphabetical order always puts it first (Cl. 6).
  static List<ItemSuggestion> _visibleSuggestions(List<ItemSuggestion> matches) =>
      matches.take(_maxVisibleSuggestions).toList();
}

/// How far the floating panel dips into the footer, so it reads as sitting on it rather than hovering
/// above it. Small enough to stay within the error text's top padding.
const double _panelOverlapIntoFooter = 6;

/// The space above the error message, under the suggestions.
const double _errorTextGap = SgartShapes.space3;

/// The space between the error message and the input it belongs to — tighter than the gap above, so
/// the message reads as attached to the field.
const double _errorTextGapToField = 6;

/// How many suggestion rows the upward panel shows at once.
const int _maxVisibleSuggestions = 6;

class _SuggestionPanel extends StatelessWidget {
  const _SuggestionPanel({
    required this.suggestions,
    required this.newItemEntry,
    required this.onSuggestionTap,
    required this.onAddAsNew,
    required this.storeNameFor,
  });

  final List<ItemSuggestion> suggestions;

  /// What the "add as new" row would add — shown before the member commits, so a wrong reading of
  /// `5 Milch` is visible while it can still be corrected.
  final FastAddEntry newItemEntry;
  final ValueChanged<ItemSuggestion> onSuggestionTap;
  final VoidCallback onAddAsNew;

  /// Resolves a suggestion's `defaultStoreId` to its active store name, or `null` when unassigned
  /// or archived (Story 2.6, AC6) — mirrors `ListDetailCubit.storeFor`.
  final String? Function(String? storeId) storeNameFor;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    final colors = context.sgartColors;
    final rows = <Widget>[
      for (final suggestion in suggestions) ...[
        _SuggestionRow(
          suggestion: suggestion,
          onTap: () => onSuggestionTap(suggestion),
          lastUsedStoreName: storeNameFor(suggestion.defaultStoreId),
        ),
        _PanelDivider(color: colors.border),
      ],
      ListTile(
        key: const Key('fast-add-new-row'),
        minTileHeight: SgartShapes.minTapTarget,
        tileColor: colors.primary.withValues(alpha: SgartColors.tintAlpha),
        leading: Icon(Icons.add, color: colors.onPrimaryTint),
        title: Text(
          localizations.fastAddNewItemAction(newItemEntry.name),
          style: TextStyle(color: colors.onPrimaryTint, fontWeight: FontWeight.w600),
        ),
        trailing: Text(
          const formatting.QuantityFormatter().format(
            double.tryParse(newItemEntry.amount) ?? 0,
            formatting.unitFromServerName(newItemEntry.unit) ?? formatting.Unit.piece,
            localizations,
          ),
          key: const Key('fast-add-new-row-quantity'),
          style: TextStyle(color: colors.onPrimaryTint),
        ),
        onTap: onAddAsNew,
      ),
    ];

    return Padding(
      padding: const EdgeInsets.fromLTRB(SgartShapes.space3, SgartShapes.space3, SgartShapes.space3, 0),
      child: DecoratedBox(
        decoration: BoxDecoration(borderRadius: SgartShapes.card, boxShadow: SgartShapes.elevatedShadow(colors.shadow)),
        // A real Material so the press ripple is clipped to the rounded corners.
        child: Material(
          key: const Key('fast-add-panel-surface'),
          color: colors.surface,
          clipBehavior: Clip.antiAlias,
          shape: RoundedRectangleBorder(
            borderRadius: SgartShapes.card,
            side: BorderSide(color: colors.border, width: SgartShapes.hairline),
          ),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 280),
            child: SingleChildScrollView(
              reverse: true,
              child: Column(mainAxisSize: MainAxisSize.min, children: rows),
            ),
          ),
        ),
      ),
    );
  }
}

class _PanelDivider extends StatelessWidget {
  const _PanelDivider({required this.color});

  final Color color;

  @override
  Widget build(BuildContext context) =>
      Divider(height: SgartShapes.hairline, thickness: SgartShapes.hairline, color: color);
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
    final storeChipText = lastUsedStoreName == null ? null : localizations.suggestionLastUsedStore(lastUsedStoreName!);

    // One semantics node reading „<name>, <quantity>[, zuletzt <store>]" as a button — without it a
    // screen reader announces the name and the prefill hint as unrelated fragments (UX-DR5).
    final semanticsLabel = storeChipText == null
        ? '${suggestion.name}, $quantityText'
        : '${suggestion.name}, $quantityText, $storeChipText';

    return Semantics(
      button: true,
      label: semanticsLabel,
      child: ListTile(
        key: Key('fast-add-suggestion-$normalizedName'),
        minTileHeight: SgartShapes.minTapTarget,
        leading: Icon(Icons.history, color: context.sgartColors.textSecondary),
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
