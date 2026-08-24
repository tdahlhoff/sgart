import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/presentation/rename_household_cubit.dart';
import 'package:sgart/features/households/presentation/rename_household_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_households_dependencies.dart';

void main() {
  group('RenameHouseholdCubit', () {
    late FakeHouseholdsApi householdsApi;

    setUp(() {
      householdsApi = FakeHouseholdsApi();
    });

    RenameHouseholdCubit buildCubit() =>
        RenameHouseholdCubit(householdsApi: householdsApi, householdId: 'id-1');

    blocTest<RenameHouseholdCubit, RenameHouseholdState>(
      'submit_emitsSuccessWithTheTrimmedNewName',
      build: buildCubit,
      act: (cubit) => cubit.submit('  Familie Beispiel  '),
      expect: () => [
        const RenameHouseholdState.submitting(),
        const RenameHouseholdState.success('Familie Beispiel'),
      ],
      verify: (_) {
        expect(householdsApi.lastRenamedHouseholdId, 'id-1');
        expect(householdsApi.lastRenamedName, 'Familie Beispiel');
      },
    );

    test('renameReusesOneCommandIdAcrossRetries', () async {
      final cubit = buildCubit();
      // First attempt fails transiently, the retry succeeds — both must carry the same commandId.
      householdsApi.renameErrorToThrow =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.submit('Familie Beispiel');
      householdsApi.renameErrorToThrow = null;
      await cubit.submit('Familie Beispiel');

      expect(householdsApi.renameCommandIds, hasLength(2));
      expect(householdsApi.renameCommandIds.first, householdsApi.renameCommandIds.last);
      await cubit.close();
    });

    test('renameUsesAFreshCommandIdWhenTheNameChangesBetweenAttempts', () async {
      final cubit = buildCubit();
      // An edited retry is a different intent — it must not reuse the id of an earlier attempt that
      // may already have landed server-side (which would dedupe the new name into a no-op).
      await cubit.submit('Familie Beispiel');
      await cubit.submit('WG Sonnenallee');

      expect(householdsApi.renameCommandIds, hasLength(2));
      expect(householdsApi.renameCommandIds.first, isNot(householdsApi.renameCommandIds.last));
      await cubit.close();
    });

    blocTest<RenameHouseholdCubit, RenameHouseholdState>(
      'submit_emitsFailureWithTheMappedCodeOnANonAdminRejection',
      build: () {
        householdsApi.renameErrorToThrow =
            const AppException(AppError(code: 'household.renameNotPermitted', message: 'debug'));
        return buildCubit();
      },
      act: (cubit) => cubit.submit('Familie Beispiel'),
      expect: () => [
        const RenameHouseholdState.submitting(),
        const RenameHouseholdState.failure(AppError(code: 'household.renameNotPermitted', message: 'debug')),
      ],
    );
  });
}
