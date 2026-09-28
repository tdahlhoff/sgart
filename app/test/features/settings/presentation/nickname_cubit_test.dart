import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/settings/data/nickname_api.dart';
import 'package:sgart/features/settings/presentation/nickname_cubit.dart';
import 'package:sgart/features/settings/presentation/nickname_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_nickname_api.dart';

/// Holds every call open until [release] — lets a test observe the in-flight `submitting` state.
class _PendingNicknameApi implements NicknameApi {
  final Completer<void> _completer = Completer<void>();
  int callCount = 0;

  void release() => _completer.complete();

  @override
  Future<void> setNickname(String householdId, String nickname) {
    callCount++;
    return _completer.future;
  }
}

void main() {
  group('NicknameCubit', () {
    late FakeNicknameApi nicknameApi;
    late NicknameCubit cubit;

    setUp(() {
      nicknameApi = FakeNicknameApi();
      cubit = NicknameCubit(nicknameApi: nicknameApi);
    });

    tearDown(() => cubit.close());

    test('submit_setsTheTrimmedNicknameAndEmitsSuccess', () async {
      await cubit.submit('household-1', '  Papa  ');

      expect(nicknameApi.setCalls, [('household-1', 'Papa')]);
      expect(cubit.state, const NicknameState.success('Papa'));
    });

    test('submit_rejectsAWhitespaceOnlyNicknameWithoutCallingTheApi', () async {
      await cubit.submit('household-1', '   ');

      expect(nicknameApi.setCalls, isEmpty);
      expect(cubit.state.error?.code, 'nickname.required');
    });

    test('submit_rejectsANicknameLongerThanTheMaximumWithoutCallingTheApi', () async {
      await cubit.submit('household-1', 'a' * (nicknameMaxLength + 1));

      expect(nicknameApi.setCalls, isEmpty);
      expect(cubit.state.error?.code, 'nickname.tooLong');
    });

    test('submit_acceptsANicknameOfExactlyTheMaximumLength', () async {
      await cubit.submit('household-1', 'a' * nicknameMaxLength);

      expect(cubit.state.status, NicknameStatus.success);
    });

    test('submit_surfacesAServerRejectionAsItsErrorCode', () async {
      nicknameApi.setNicknameErrorToThrow =
          const AppException(AppError(code: 'identity.notAMember', message: 'debug only'));

      await cubit.submit('household-1', 'Papa');

      expect(cubit.state.status, NicknameStatus.failure);
      expect(cubit.state.error?.code, 'identity.notAMember');
    });

    test('submit_mapsAnUnexpectedErrorToTheUnknownNicknameCode', () async {
      nicknameApi.setNicknameErrorToThrow = StateError('socket closed');

      await cubit.submit('household-1', 'Papa');

      expect(cubit.state.error?.code, 'nickname.unknown');
    });

    test('submit_ignoresASecondSubmitWhileTheFirstIsInFlight', () async {
      final pendingApi = _PendingNicknameApi();
      final pendingCubit = NicknameCubit(nicknameApi: pendingApi);
      addTearDown(pendingCubit.close);

      final firstSubmit = pendingCubit.submit('household-1', 'Papa');
      expect(pendingCubit.state.status, NicknameStatus.submitting);
      await pendingCubit.submit('household-1', 'Timo');
      pendingApi.release();
      await firstSubmit;

      expect(pendingApi.callCount, 1);
      expect(pendingCubit.state, const NicknameState.success('Papa'));
    });
  });
}
