import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/nickname_api.dart';
import 'nickname_state.dart';

/// The nickname's own fail-fast bound (Story 8.3) — mirrors the server's
/// `MembershipNickname.MAX_LENGTH` so a client-side rejection never diverges from the server's.
const int nicknameMaxLength = 60;

/// Drives nickname capture/edit for one household (Story 8.3): the required onboarding step (both
/// entry points) and the Profile edit affordance all share this cubit. Client-validates
/// non-blank/whitespace and length before ever reaching the network (mirrors
/// `AcceptInviteCubit`'s client-side fail-fast); the server remains the validation authority for
/// anything this client check might miss. Depends only on [NicknameApi] so tests never touch the
/// network (CLAUDE.md §6).
class NicknameCubit extends Cubit<NicknameState> {
  NicknameCubit({required this._nicknameApi}) : super(const NicknameState.idle());

  final NicknameApi _nicknameApi;

  Future<void> submit(String householdId, String rawNickname) async {
    if (state.status == NicknameStatus.submitting) {
      return;
    }
    final trimmed = rawNickname.trim();
    if (trimmed.isEmpty) {
      _safeEmit(const NicknameState.failure(
        AppError(code: 'nickname.required', message: 'client-side fail-fast'),
      ));
      return;
    }
    if (trimmed.length > nicknameMaxLength) {
      _safeEmit(const NicknameState.failure(
        AppError(code: 'nickname.tooLong', message: 'client-side fail-fast'),
      ));
      return;
    }
    _safeEmit(const NicknameState.submitting());
    try {
      await _nicknameApi.setNickname(householdId, trimmed);
      _safeEmit(NicknameState.success(trimmed));
    } on Object catch (error) {
      _safeEmit(NicknameState.failure(_toAppError(error)));
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'nickname.unknown', message: error.toString());
  }

  void _safeEmit(NicknameState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
