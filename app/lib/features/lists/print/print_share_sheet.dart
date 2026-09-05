import 'dart:developer' as developer;
import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../stores/data/store_chain.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/store_summary.dart';
import '../../stores/data/stores_api.dart';
import '../data/item.dart';
import 'list_print_document.dart';
import 'list_print_grouping.dart';
import 'list_print_service.dart';

/// Opens the print/share bottom sheet (Story 3.5, AC1, AC2, AC3, AC5, UX-DR19) — the entry point for
/// printing or sharing an Open list, mirroring the [showStorePickerSheet] `showModalBottomSheet`
/// pattern. [items]/[stores] are the caller's already-loaded list state (an Open list's items are all
/// `OPEN`, so no tick-box status question, Cl. 1); [referenceCache]/[storesApi] resolve the group
/// headers' chain labels, degrading to no chain labels on a load failure rather than failing the
/// sheet (mirrors `ListDetailCubit.bootstrap`'s degrade-and-log posture).
Future<void> showPrintShareSheet(
  BuildContext context, {
  required String title,
  required List<Item> items,
  required List<StoreSummary> stores,
  required StoresApi storesApi,
  required StoreChainReferenceCache referenceCache,
  ListPrintService service = const PluginListPrintService(),
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => _PrintShareSheetBody(
      title: title,
      items: items,
      stores: stores,
      storesApi: storesApi,
      referenceCache: referenceCache,
      service: service,
    ),
  );
}

class _PrintShareSheetBody extends StatefulWidget {
  const _PrintShareSheetBody({
    required this.title,
    required this.items,
    required this.stores,
    required this.storesApi,
    required this.referenceCache,
    required this.service,
  });

  final String title;
  final List<Item> items;
  final List<StoreSummary> stores;
  final StoresApi storesApi;
  final StoreChainReferenceCache referenceCache;
  final ListPrintService service;

  @override
  State<_PrintShareSheetBody> createState() => _PrintShareSheetBodyState();
}

class _PrintShareSheetBodyState extends State<_PrintShareSheetBody> {
  List<StoreChain> _chainReference = const [];

  /// The in-flight chain-reference load, awaited before a build so a member who taps
  /// „Drucken"/„Als PDF teilen" before it settles still gets the chain labels rather than a document
  /// built against an empty reference (Story 3.5 review). Never completes with an error — failures are
  /// swallowed in [_loadChainReference], leaving [_chainReference] empty (the accepted degrade path).
  late final Future<void> _chainReferenceLoad;

  /// Guards against a double build/dispatch on rapid taps — either option's handler checks this
  /// first, so a second tap while a build is already in flight is a plain no-op.
  bool _isBusy = false;

  @override
  void initState() {
    super.initState();
    _chainReferenceLoad = _loadChainReference();
  }

  /// Best-effort — the sheet still works with no chain reference (offline first load, no cache);
  /// group headers simply render with no chain label (mirrors `ListDetailCubit.bootstrap`).
  Future<void> _loadChainReference() async {
    try {
      final chains = await widget.referenceCache.load(widget.storesApi);
      if (!mounted) {
        return;
      }
      setState(() => _chainReference = chains);
    } on Object catch (error) {
      developer.log('Loading chain reference failed — print/share degrades to no chain labels',
          name: 'sgart.lists', error: error);
    }
  }

  Future<void> _handlePrint() async {
    if (_isBusy) {
      return;
    }
    setState(() => _isBusy = true);
    try {
      final bytes = await _buildDocumentBytes();
      await widget.service.printDocument(bytes);
    } on Object {
      _handleGenuineFailure();
      return;
    }
    _handleSuccess();
  }

  Future<void> _handleShare() async {
    if (_isBusy) {
      return;
    }
    setState(() => _isBusy = true);
    try {
      final bytes = await _buildDocumentBytes();
      await widget.service.shareDocument(bytes, filename: _filename());
    } on Object {
      _handleGenuineFailure();
      return;
    }
    _handleSuccess();
  }

  Future<Uint8List> _buildDocumentBytes() async {
    final localizations = AppLocalizations.of(context);
    await _chainReferenceLoad;
    final grouping = ListPrintGrouping.from(items: widget.items, stores: widget.stores);
    return const ListPrintDocument().build(
      listTitle: widget.title,
      now: DateTime.now().toUtc(),
      grouping: grouping,
      chainReference: _chainReference,
      localizations: localizations,
    );
  }

  /// A sanitized `"<list title>.pdf"` — a share hint only, never a save path (AC3, Task 4). A blank
  /// title falls back to a generic name rather than handing the OS share sheet an empty filename.
  String _filename() {
    final sanitized = widget.title.trim().replaceAll(RegExp(r'[\\/:*?"<>|]'), '_');
    final base = sanitized.isEmpty ? AppLocalizations.of(context).printShareFilename : sanitized;
    return '$base.pdf';
  }

  void _handleSuccess() {
    if (!mounted) {
      return;
    }
    Navigator.of(context).pop();
  }

  /// A genuine plugin failure (a thrown exception) surfaces a brief generic error; a member
  /// cancelling the OS print dialog/share sheet never throws, so it never reaches here (AC — no
  /// message on cancel).
  void _handleGenuineFailure() {
    if (!mounted) {
      return;
    }
    setState(() => _isBusy = false);
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(AppLocalizations.of(context).errorGenericFallback)));
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return SafeArea(
      child: Padding(
        key: const Key('print-share-sheet'),
        padding: EdgeInsets.only(
          left: SgartShapes.cardPadding,
          right: SgartShapes.cardPadding,
          bottom: MediaQuery.of(context).viewInsets.bottom + SgartShapes.cardPadding,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(widget.title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: SgartShapes.space2),
            _PrintShareOptionRow(
              key: const Key('print-share-print-option'),
              label: localizations.printOptionLabel,
              subtitle: localizations.printOptionSubtitle,
              onTap: _isBusy ? null : _handlePrint,
            ),
            _PrintShareOptionRow(
              key: const Key('print-share-share-option'),
              label: localizations.shareOptionLabel,
              subtitle: localizations.shareOptionSubtitle,
              onTap: _isBusy ? null : _handleShare,
            ),
            const SizedBox(height: SgartShapes.space3),
            Text(
              localizations.printShareNoFileSaved,
              key: const Key('print-share-no-file-saved'),
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }
}

/// One print/share option row (mockup frame 1's „Drucken"/„Als PDF teilen" rows) — a ≥48px tap
/// target with a [Semantics] button label (NFR10/UX-DR5), mirroring `_StoreChip`'s tappable-area
/// pattern. A `null` [onTap] renders the row inert without hiding it (the busy-guard state, not a
/// permanent unavailability).
class _PrintShareOptionRow extends StatelessWidget {
  const _PrintShareOptionRow({
    super.key,
    required this.label,
    required this.subtitle,
    required this.onTap,
  });

  final String label;
  final String subtitle;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: label,
      excludeSemantics: true,
      child: InkWell(
        onTap: onTap,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: SgartShapes.minTapTarget),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: SgartShapes.space2),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: Theme.of(context).textTheme.titleSmall),
                Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
