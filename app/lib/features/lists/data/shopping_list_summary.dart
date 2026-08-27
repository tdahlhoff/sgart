import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// A shopping list as seen by the caller — id, the optional display name, its lifecycle status, and
/// its item count (Story 2.1, AC1/AC2; `itemCount` added Story 2.3, AC7). `name` is `null` for an
/// unnamed list; the client derives „Liste N" from the list's position in the creation-ordered
/// array `GET` returns (AC2 — the ordinal is never sent by the server). `status` is carried for
/// 2.2's Offen/Erledigt split; 2.1 only ever sees `"OPEN"`. `itemCount` is 0 for an empty list.
class ShoppingListSummary {
  const ShoppingListSummary({
    required this.listId,
    required this.name,
    required this.status,
    this.itemCount = 0,
  });

  final String listId;
  final String? name;
  final String status;
  final int itemCount;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape, so callers resolve it through [AppError.code]. A
  /// missing/null `name` is valid (an unnamed list) — only a non-string, non-null value is malformed.
  /// A missing `itemCount` defaults to 0 (an optimistically-created list has no items yet).
  factory ShoppingListSummary.fromJson(Map<String, dynamic> json) {
    final listId = json['listId'];
    final name = json['name'];
    final status = json['status'];
    final itemCount = json['itemCount'];
    if (listId is! String ||
        (name != null && name is! String) ||
        status is! String ||
        (itemCount != null && itemCount is! int)) {
      throw const AppException(AppError(
        code: 'lists.malformedResponse',
        message: 'GET lists returned an unexpected shape',
      ));
    }
    return ShoppingListSummary(
      listId: listId,
      name: name as String?,
      status: status,
      itemCount: (itemCount as int?) ?? 0,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is ShoppingListSummary &&
      other.listId == listId &&
      other.name == name &&
      other.status == status &&
      other.itemCount == itemCount;

  @override
  int get hashCode => Object.hash(listId, name, status, itemCount);
}
