import '../../../shared/errors/app_error.dart';

enum NicknameStatus { idle, submitting, success, failure }

/// State of [NicknameCubit] — the nickname form's own submit lifecycle (onboarding step or Profile
/// edit). Mirrors `CreateHouseholdState`: [failure] carries the inline error (client-side
/// `nickname.required`/`nickname.tooLong`, or a server rejection) shown on the form.
class NicknameState {
  const NicknameState.idle() : this._(NicknameStatus.idle);

  const NicknameState.submitting() : this._(NicknameStatus.submitting);

  const NicknameState.success(String nickname) : this._(NicknameStatus.success, nickname: nickname);

  const NicknameState.failure(AppError error) : this._(NicknameStatus.failure, error: error);

  const NicknameState._(this.status, {this.nickname, this.error});

  final NicknameStatus status;
  final String? nickname;
  final AppError? error;

  @override
  bool operator ==(Object other) =>
      other is NicknameState && other.status == status && other.nickname == nickname && other.error == error;

  @override
  int get hashCode => Object.hash(status, nickname, error);
}
