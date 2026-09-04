import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// An item's in-trip lifecycle (Stories 3.3/3.4) — mirrors the backend `ItemStatus` enum. `open` is
/// the default / birth state, `done` once checked off during a trip, `discarded` when thrown away
/// (a terminal "not bought, stays dimmed" status). Mirrors the `unitFromServerName`/`serverName`
/// mapping pattern so the wire vocabulary (`"OPEN"`, `"DONE"`, `"DISCARDED"`) has a single,
/// type-safe representation on the client.
enum ItemStatus {
  open,
  done,
  discarded;

  /// The backend enum name (`"OPEN"`, `"DONE"`, `"DISCARDED"`) — the wire representation.
  String get serverName => name.toUpperCase();

  /// Maps a backend status name onto its [ItemStatus], or `null` when the two vocabularies disagree
  /// (server enum drift) — the caller decides its own fallback (see [Item.fromJson], which fails
  /// fast).
  static ItemStatus? fromServerName(String serverName) {
    for (final status in ItemStatus.values) {
      if (status.serverName == serverName) {
        return status;
      }
    }
    return null;
  }
}

/// An item on a shopping list as seen by the caller — id, name, optional note, quantity, assigned
/// store, and in-trip status (Story 3.3, AC1; Story 2.3, AC1/AC6; Story 2.6, AC1). `note`/`storeId`
/// are `null` for an item with no note / unassigned. `amount` is carried as the server's decimal
/// string (never parsed to a `double` here — display formatting owns that, and a round-trip through
/// `double` could lose precision); `unit` is the server's enum name (e.g. `"PIECE"`). `status` is
/// the item's in-trip lifecycle — [ItemStatus.open] is the default / birth state; the server always
/// sends it once Story 3.3 is live.
class Item {
  const Item({
    required this.itemId,
    required this.name,
    required this.note,
    required this.amount,
    required this.unit,
    this.storeId,
    this.status = ItemStatus.open,
  });

  final String itemId;
  final String name;
  final String? note;
  final String amount;
  final String unit;
  final String? storeId;
  final ItemStatus status;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape, so callers resolve it through [AppError.code]. A
  /// missing/null `note`/`storeId` is valid (no note / unassigned) — only a non-string, non-null
  /// value is malformed. A missing `status` defaults to [ItemStatus.open]; a non-string value, or a
  /// string outside the known set (`"OPEN"`, `"DONE"`, `"DISCARDED"`, server enum drift), is
  /// malformed.
  factory Item.fromJson(Map<String, dynamic> json) {
    final itemId = json['itemId'];
    final name = json['name'];
    final note = json['note'];
    final amount = json['amount'];
    final unit = json['unit'];
    final storeId = json['storeId'];
    final rawStatus = json['status'];
    if (itemId is! String ||
        name is! String ||
        (note != null && note is! String) ||
        amount is! String ||
        unit is! String ||
        (storeId != null && storeId is! String) ||
        (rawStatus != null && rawStatus is! String)) {
      throw const AppException(AppError(
        code: 'items.malformedResponse',
        message: 'GET items returned an unexpected shape',
      ));
    }
    final status = rawStatus == null ? ItemStatus.open : ItemStatus.fromServerName(rawStatus as String);
    if (status == null) {
      throw const AppException(AppError(
        code: 'items.malformedResponse',
        message: 'GET items returned an unexpected shape',
      ));
    }
    return Item(
      itemId: itemId,
      name: name,
      note: note as String?,
      amount: amount,
      unit: unit,
      storeId: storeId as String?,
      status: status,
    );
  }

  /// Returns a copy with the given fields replaced — the single, typo-proof way to derive a new
  /// [Item] (e.g. an optimistic reroute or status change) without hand-rebuilding every field, which
  /// silently resets any field a caller forgets. `storeId` uses a sentinel so it can be set back to
  /// `null` (unassign); omitting it keeps the current value.
  Item copyWith({
    String? name,
    String? note,
    String? amount,
    String? unit,
    Object? storeId = _unchanged,
    ItemStatus? status,
  }) {
    return Item(
      itemId: itemId,
      name: name ?? this.name,
      note: note ?? this.note,
      amount: amount ?? this.amount,
      unit: unit ?? this.unit,
      storeId: identical(storeId, _unchanged) ? this.storeId : storeId as String?,
      status: status ?? this.status,
    );
  }

  static const Object _unchanged = Object();

  @override
  bool operator ==(Object other) =>
      other is Item &&
      other.itemId == itemId &&
      other.name == name &&
      other.note == note &&
      other.amount == amount &&
      other.unit == unit &&
      other.storeId == storeId &&
      other.status == status;

  @override
  int get hashCode => Object.hash(itemId, name, note, amount, unit, storeId, status);
}
