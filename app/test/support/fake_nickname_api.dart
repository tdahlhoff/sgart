import 'package:sgart/features/settings/data/nickname_api.dart';

/// Test double for [NicknameApi] (Story 8.3) — no real HTTP client in tests (CLAUDE.md §6).
class FakeNicknameApi implements NicknameApi {
  Object? setNicknameErrorToThrow;

  final List<(String householdId, String nickname)> setCalls = [];

  @override
  Future<void> setNickname(String householdId, String nickname) async {
    setCalls.add((householdId, nickname));
    if (setNicknameErrorToThrow != null) throw setNicknameErrorToThrow!;
  }
}
