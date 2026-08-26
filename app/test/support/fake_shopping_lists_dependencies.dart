import 'package:sgart/features/lists/data/shopping_list_summary.dart';
import 'package:sgart/features/lists/data/shopping_lists_api.dart';

/// Test double for [ShoppingListsApi] — no real network in tests (CLAUDE.md §6). Mirrors
/// `FakeStoresApi`.
class FakeShoppingListsApi implements ShoppingListsApi {
  List<ShoppingListSummary> listsToReturn = const [];
  Object? listError;
  Object? createError;
  Object? renameError;

  String? lastCreatedName;
  String? lastCreatedListId;
  final List<String> createCommandIds = [];
  final List<String> createListIds = [];
  int createCallCount = 0;

  String? lastRenamedListId;
  String? lastRenamedName;
  final List<String> renameCommandIds = [];
  int renameCallCount = 0;

  @override
  Future<List<ShoppingListSummary>> listOpenLists(String householdId) async {
    if (listError != null) throw listError!;
    return listsToReturn;
  }

  @override
  Future<void> createList(
    String householdId, {
    String? name,
    required String listId,
    required String commandId,
  }) async {
    lastCreatedName = name;
    lastCreatedListId = listId;
    createCommandIds.add(commandId);
    createListIds.add(listId);
    createCallCount++;
    if (createError != null) throw createError!;
  }

  @override
  Future<void> renameList(String householdId, String listId, String name, {required String commandId}) async {
    lastRenamedListId = listId;
    lastRenamedName = name;
    renameCommandIds.add(commandId);
    renameCallCount++;
    if (renameError != null) throw renameError!;
  }
}
