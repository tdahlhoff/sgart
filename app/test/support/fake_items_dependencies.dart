import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/data/items_api.dart';

/// Test double for [ItemsApi] — no real network in tests (CLAUDE.md §6). Mirrors
/// `FakeShoppingListsApi`.
class FakeItemsApi implements ItemsApi {
  List<Item> itemsToReturn = const [];

  /// Per-list override for [listItems] — keyed by `listId`, falling back to [itemsToReturn] when a
  /// list has no entry. Lets a test give a *different* list (e.g. a move's target) its own items
  /// without disturbing [itemsToReturn], which the cubit's own list keeps using.
  final Map<String, List<Item>> itemsByListId = {};

  Object? listError;
  Object? addError;
  Object? updateError;
  Object? removeError;
  Object? moveError;
  Object? assignStoreError;
  Object? rerouteError;

  String? lastAddedItemId;
  String? lastAddedName;
  String? lastAddedNote;
  String? lastAddedAmount;
  String? lastAddedUnit;
  final List<String> addCommandIds = [];
  int addCallCount = 0;

  String? lastUpdatedItemId;
  String? lastUpdatedName;
  String? lastUpdatedNote;
  String? lastUpdatedAmount;
  String? lastUpdatedUnit;
  final List<String> updateCommandIds = [];
  int updateCallCount = 0;

  String? lastRemovedItemId;
  final List<String> removeCommandIds = [];
  int removeCallCount = 0;

  String? lastMovedItemId;
  String? lastMovedTargetListId;
  final List<String> moveCommandIds = [];
  int moveCallCount = 0;

  String? lastAssignedItemId;
  String? lastAssignedStoreId;
  final List<String> assignStoreCommandIds = [];
  int assignStoreCallCount = 0;

  String? lastReroutedItemId;
  String? lastReroutedStoreId;
  final List<String> rerouteCommandIds = [];
  int rerouteCallCount = 0;

  @override
  Future<List<Item>> listItems(String householdId, String listId) async {
    if (listError != null) throw listError!;
    return itemsByListId[listId] ?? itemsToReturn;
  }

  @override
  Future<void> addItem(
    String householdId,
    String listId, {
    required String itemId,
    required String name,
    String? note,
    required String amount,
    required String unit,
    required String commandId,
  }) async {
    lastAddedItemId = itemId;
    lastAddedName = name;
    lastAddedNote = note;
    lastAddedAmount = amount;
    lastAddedUnit = unit;
    addCommandIds.add(commandId);
    addCallCount++;
    if (addError != null) throw addError!;
  }

  @override
  Future<void> updateItem(
    String householdId,
    String listId,
    String itemId, {
    required String name,
    String? note,
    required String amount,
    required String unit,
    required String commandId,
  }) async {
    lastUpdatedItemId = itemId;
    lastUpdatedName = name;
    lastUpdatedNote = note;
    lastUpdatedAmount = amount;
    lastUpdatedUnit = unit;
    updateCommandIds.add(commandId);
    updateCallCount++;
    if (updateError != null) throw updateError!;
  }

  @override
  Future<void> removeItem(String householdId, String listId, String itemId, {required String commandId}) async {
    lastRemovedItemId = itemId;
    removeCommandIds.add(commandId);
    removeCallCount++;
    if (removeError != null) throw removeError!;
  }

  @override
  Future<void> moveItem(
    String householdId,
    String listId,
    String itemId, {
    required String targetListId,
    required String commandId,
  }) async {
    lastMovedItemId = itemId;
    lastMovedTargetListId = targetListId;
    moveCommandIds.add(commandId);
    moveCallCount++;
    if (moveError != null) throw moveError!;
  }

  @override
  Future<void> assignStore(
    String householdId,
    String listId,
    String itemId, {
    required String storeId,
    required String commandId,
  }) async {
    lastAssignedItemId = itemId;
    lastAssignedStoreId = storeId;
    assignStoreCommandIds.add(commandId);
    assignStoreCallCount++;
    if (assignStoreError != null) throw assignStoreError!;
  }

  @override
  Future<void> rerouteItem(
    String householdId,
    String listId,
    String itemId, {
    required String storeId,
    required String commandId,
  }) async {
    lastReroutedItemId = itemId;
    lastReroutedStoreId = storeId;
    rerouteCommandIds.add(commandId);
    rerouteCallCount++;
    if (rerouteError != null) throw rerouteError!;
  }
}
