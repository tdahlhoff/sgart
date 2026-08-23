import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/households/presentation/households_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_households_dependencies.dart';

void main() {
  group('HouseholdsCubit', () {
    late FakeHouseholdsApi householdsApi;

    setUp(() {
      householdsApi = FakeHouseholdsApi();
    });

    HouseholdsCubit buildCubit() => HouseholdsCubit(householdsApi: householdsApi);

    test('startsLoading', () {
      expect(buildCubit().state.status, HouseholdsStatus.loading);
      buildCubit().close();
    });

    blocTest<HouseholdsCubit, HouseholdsState>(
      'bootstrap_routesToTheCreateOrAwaitChoiceForACallerWithZeroHouseholds',
      build: () {
        householdsApi.householdsToReturn = const [];
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [const HouseholdsState.needsChoice()],
    );

    blocTest<HouseholdsCubit, HouseholdsState>(
      'bootstrap_routesStraightInForACallerWithExactlyOneHousehold',
      build: () {
        householdsApi.householdsToReturn = const [
          HouseholdSummary(householdId: 'id-1', name: 'Familie Muster'),
        ];
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [
        const HouseholdsState.home(HouseholdSummary(householdId: 'id-1', name: 'Familie Muster')),
      ],
    );

    blocTest<HouseholdsCubit, HouseholdsState>(
      'bootstrap_routesToSelectionForACallerWithSeveralHouseholds',
      build: () {
        householdsApi.householdsToReturn = const [
          HouseholdSummary(householdId: 'id-1', name: 'Familie Muster'),
          HouseholdSummary(householdId: 'id-2', name: 'WG Sonnenallee'),
        ];
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [
        const HouseholdsState.selection([
          HouseholdSummary(householdId: 'id-1', name: 'Familie Muster'),
          HouseholdSummary(householdId: 'id-2', name: 'WG Sonnenallee'),
        ]),
      ],
    );

    blocTest<HouseholdsCubit, HouseholdsState>(
      'bootstrap_emitsAFailureWhenLoadingTheHouseholdsFails',
      build: () {
        householdsApi.listErrorToThrow =
            const AppException(AppError(code: 'network.unreachable', message: 'debug'));
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [
        const HouseholdsState.failure(AppError(code: 'network.unreachable', message: 'debug')),
      ],
    );

    blocTest<HouseholdsCubit, HouseholdsState>(
      'selectHousehold_routesStraightIntoTheGivenHousehold',
      build: buildCubit,
      act: (cubit) =>
          cubit.selectHousehold(const HouseholdSummary(householdId: 'id-9', name: 'Neuer Haushalt')),
      expect: () => [
        const HouseholdsState.home(HouseholdSummary(householdId: 'id-9', name: 'Neuer Haushalt')),
      ],
    );

    test('doesNotEmitAfterTheCubitIsClosedMidBootstrap', () async {
      householdsApi.householdsToReturn = const [];
      final cubit = buildCubit();

      final bootstrapFuture = cubit.bootstrap();
      await cubit.close();

      await bootstrapFuture;
    });
  });
}
