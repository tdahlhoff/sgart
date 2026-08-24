import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/data/households_api.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/households/presentation/rename_household_page.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_households_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('RenameHouseholdPage', () {
    late FakeHouseholdsApi householdsApi;
    late FakeActiveHouseholdStore activeHouseholdStore;
    late HouseholdsCubit householdsCubit;

    const familie = HouseholdSummary(householdId: 'id-1', name: 'Familie Muster');
    const wg = HouseholdSummary(householdId: 'id-2', name: 'WG Sonnenallee');

    setUp(() async {
      householdsApi = FakeHouseholdsApi()..householdsToReturn = const [familie, wg];
      activeHouseholdStore = FakeActiveHouseholdStore(activeId: 'id-1');
      householdsCubit = HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: activeHouseholdStore);
      await householdsCubit.bootstrap(); // → shell with id-1 active
    });

    tearDown(() => householdsCubit.close());

    Widget buildSubject() => wrapForTesting(
          RepositoryProvider<HouseholdsApi>.value(
            value: householdsApi,
            child: BlocProvider<HouseholdsCubit>.value(
              value: householdsCubit,
              child: const RenameHouseholdPage(household: familie),
            ),
          ),
        );

    testWidgets('theFieldIsPrefilledWithTheCurrentName', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(find.widgetWithText(TextField, 'Familie Muster'), findsOneWidget);
    });

    testWidgets('renamingUpdatesTheNameEverywhereItIsShown', (tester) async {
      // Mirror the real flow: the rename page is pushed over a host route (the shell), re-providing
      // the api/cubit as the switcher does; on success it pops back to that host, which shows the
      // confirmation.
      await tester.pumpWidget(wrapForTesting(
        Builder(
          builder: (context) => Scaffold(
            body: ElevatedButton(
              key: const Key('open-rename'),
              onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                builder: (_) => RepositoryProvider<HouseholdsApi>.value(
                  value: householdsApi,
                  child: BlocProvider<HouseholdsCubit>.value(
                    value: householdsCubit,
                    child: const RenameHouseholdPage(household: familie),
                  ),
                ),
              )),
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.byKey(const Key('open-rename')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('rename-household-name-field')), 'Familie Beispiel');
      await tester.tap(find.byKey(const Key('rename-household-submit-button')));
      await tester.pumpAndSettle();

      // The active household and its entry in the retained list both carry the new name (AC3)…
      expect(householdsCubit.state.activeHousehold!.name, 'Familie Beispiel');
      expect(householdsCubit.state.households!.firstWhere((h) => h.householdId == 'id-1').name,
          'Familie Beispiel');
      // …the page has popped back to the host…
      expect(find.byKey(const Key('rename-household-name-field')), findsNothing);
      // …and a brief confirmation is shown.
      expect(find.byKey(const Key('rename-confirmation')), findsOneWidget);
    });

    testWidgets('aRenameNotPermittedErrorShowsTheAdminOnlyMessage', (tester) async {
      householdsApi.renameErrorToThrow =
          const AppException(AppError(code: 'household.renameNotPermitted', message: 'debug'));
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('rename-household-submit-button')));
      await tester.pump();
      await tester.pump();

      expect(find.byKey(const Key('rename-household-error')), findsOneWidget);
      expect(find.text('Nur Administratoren können den Haushalt umbenennen.'), findsOneWidget);
    });
  });
}
