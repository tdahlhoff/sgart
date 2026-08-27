import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/data/items_api.dart';

/// Test double for [ItemsApi] — no real network in tests (CLAUDE.md §6). Mirrors
/// `FakeShoppingListsApi`.
class FakeItemsApi implements ItemsApi {
  List<Item> itemsToReturn = const [];
  Object? listError;
  Object? addError;
  Object? updateError;
  Object? removeError;

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

  @override
  Future<List<Item>> listItems(String householdId, String listId) async {
    if (listError != null) throw listError!;
    return itemsToReturn;
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
}
