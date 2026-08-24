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
    late FakeActiveHouseholdStore activeHouseholdStore;

    setUp(() {
      householdsApi = FakeHouseholdsApi();
      activeHouseholdStore = FakeActiveHouseholdStore();
    });

    HouseholdsCubit buildCubit() =>
        HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: activeHouseholdStore);

    const familie = HouseholdSummary(householdId: 'id-1', name: 'Familie Muster');
    const wg = HouseholdSummary(householdId: 'id-2', name: 'WG Sonnenallee');

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
      'bootstrap_routesStraightIntoTheShellForACallerWithExactlyOneHousehold',
      build: () {
        householdsApi.householdsToReturn = const [familie];
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [const HouseholdsState.shell(activeHousehold: familie, households: [familie])],
      verify: (_) => expect(activeHouseholdStore.writes, ['id-1']),
    );

    blocTest<HouseholdsCubit, HouseholdsState>(
      'bootstrap_routesToSelectionForACallerWithSeveralHouseholdsAndNoStoredActive',
      build: () {
        householdsApi.householdsToReturn = const [familie, wg];
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [const HouseholdsState.selection([familie, wg])],
    );

    blocTest<HouseholdsCubit, HouseholdsState>(
      'aStoredLastActiveHouseholdIsRestoredOnLaunchSkippingSelection',
      build: () {
        householdsApi.householdsToReturn = const [familie, wg];
        activeHouseholdStore.activeId = 'id-2';
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [const HouseholdsState.shell(activeHousehold: wg, households: [familie, wg])],
    );

    blocTest<HouseholdsCubit, HouseholdsState>(
      'aStoredHouseholdNoLongerInTheListFallsBackToRouting',
      build: () {
        householdsApi.householdsToReturn = const [familie, wg];
        activeHouseholdStore.activeId = 'id-gone';
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [const HouseholdsState.selection([familie, wg])],
    );

    blocTest<HouseholdsCubit, HouseholdsState>(
      'bootstrap_emitsAFailureWhenLoadingTheHouseholdsFails',
      build: () {
        householdsApi.listErrorToThrow =
            const AppException(AppError(code: 'network.unreachable', message: 'debug'));
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [const HouseholdsState.failure(AppError(code: 'network.unreachable', message: 'debug'))],
    );

    blocTest<HouseholdsCubit, HouseholdsState>(
      'selectHousehold_entersTheShellWithTheGivenHousehold',
      build: buildCubit,
      act: (cubit) => cubit.selectHousehold(wg),
      expect: () => [const HouseholdsState.shell(activeHousehold: wg, households: [wg])],
    );

    blocTest<HouseholdsCubit, HouseholdsState>(
      'switchingWritesTheNewActiveHouseholdToTheStoreAndUpdatesTheActiveOne',
      build: () {
        householdsApi.householdsToReturn = const [familie, wg];
        activeHouseholdStore.activeId = 'id-1';
        return buildCubit();
      },
      act: (cubit) async {
        await cubit.bootstrap();
        await cubit.switchActive(wg);
      },
      expect: () => [
        const HouseholdsState.shell(activeHousehold: familie, households: [familie, wg]),
        const HouseholdsState.shell(activeHousehold: wg, households: [familie, wg]),
      ],
      verify: (_) => expect(activeHouseholdStore.writes.last, 'id-2'),
    );

    blocTest<HouseholdsCubit, HouseholdsState>(
      'applyHouseholdRename_updatesTheNameInTheHeaderAndTheSwitcherList',
      build: () {
        householdsApi.householdsToReturn = const [familie, wg];
        activeHouseholdStore.activeId = 'id-1';
        return buildCubit();
      },
      act: (cubit) async {
        await cubit.bootstrap();
        cubit.applyHouseholdRename('id-1', 'Familie Beispiel');
      },
      expect: () => [
        const HouseholdsState.shell(activeHousehold: familie, households: [familie, wg]),
        const HouseholdsState.shell(
          activeHousehold: HouseholdSummary(householdId: 'id-1', name: 'Familie Beispiel'),
          households: [HouseholdSummary(householdId: 'id-1', name: 'Familie Beispiel'), wg],
        ),
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
