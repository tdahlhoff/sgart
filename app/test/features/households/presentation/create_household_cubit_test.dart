import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/presentation/create_household_cubit.dart';
import 'package:sgart/features/households/presentation/create_household_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_households_dependencies.dart';

void main() {
  group('CreateHouseholdCubit', () {
    late FakeHouseholdsApi householdsApi;

    setUp(() {
      householdsApi = FakeHouseholdsApi();
    });

    CreateHouseholdCubit buildCubit() => CreateHouseholdCubit(householdsApi: householdsApi);

    test('startsIdle', () {
      expect(buildCubit().state.status, CreateHouseholdStatus.idle);
      buildCubit().close();
    });

    blocTest<CreateHouseholdCubit, CreateHouseholdState>(
      'submit_createsTheHouseholdAndEmitsSuccessWithItsIdAndName',
      build: () {
        householdsApi.createdHouseholdIdToReturn = 'id-1';
        return buildCubit();
      },
      act: (cubit) => cubit.submit('Familie Muster'),
      expect: () => [
        const CreateHouseholdState.submitting(),
        const CreateHouseholdState.success(HouseholdSummary(householdId: 'id-1', name: 'Familie Muster')),
      ],
      verify: (_) => expect(householdsApi.lastCreatedName, 'Familie Muster'),
    );

    blocTest<CreateHouseholdCubit, CreateHouseholdState>(
      'submit_emitsAFailureWithTheLocalizableCodeWhenTheNameIsRejected',
      build: () {
        householdsApi.createErrorToThrow =
            const AppException(AppError(code: 'household.nameRequired', message: 'debug'));
        return buildCubit();
      },
      act: (cubit) => cubit.submit('   '),
      expect: () => [
        const CreateHouseholdState.submitting(),
        const CreateHouseholdState.failure(AppError(code: 'household.nameRequired', message: 'debug')),
      ],
    );

    blocTest<CreateHouseholdCubit, CreateHouseholdState>(
      'submit_trimsTheNameSoTheRoutedHouseholdMatchesTheServerPersistedValue',
      build: () {
        householdsApi.createdHouseholdIdToReturn = 'id-1';
        return buildCubit();
      },
      act: (cubit) => cubit.submit('  Familie Muster  '),
      expect: () => [
        const CreateHouseholdState.submitting(),
        const CreateHouseholdState.success(HouseholdSummary(householdId: 'id-1', name: 'Familie Muster')),
      ],
      verify: (_) => expect(householdsApi.lastCreatedName, 'Familie Muster'),
    );

    test('submit_reusesTheSameCommandIdAcrossRetriesOfTheSameIntent', () async {
      householdsApi.createErrorToThrow =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      final cubit = buildCubit();

      await cubit.submit('Familie Muster');
      final commandIdOnFirstAttempt = householdsApi.lastCommandId;
      householdsApi
        ..createErrorToThrow = null
        ..createdHouseholdIdToReturn = 'id-1';
      await cubit.submit('Familie Muster');

      expect(householdsApi.createCallCount, 2);
      expect(householdsApi.lastCommandId, commandIdOnFirstAttempt);
      await cubit.close();
    });

    test('twoSeparateCreateIntentsUseDifferentCommandIds', () async {
      householdsApi.createdHouseholdIdToReturn = 'id-1';
      final firstIntent = buildCubit();
      final secondIntent = buildCubit();

      await firstIntent.submit('Familie Muster');
      final firstCommandId = householdsApi.lastCommandId;
      await secondIntent.submit('WG Sonnenallee');
      final secondCommandId = householdsApi.lastCommandId;

      expect(firstCommandId, isNot(secondCommandId));
      await firstIntent.close();
      await secondIntent.close();
    });

    test('doesNotEmitAfterTheCubitIsClosedMidSubmit', () async {
      householdsApi.createdHouseholdIdToReturn = 'id-1';
      final cubit = buildCubit();

      final submitFuture = cubit.submit('Familie Muster');
      await cubit.close();

      await submitFuture;
    });
  });
}
