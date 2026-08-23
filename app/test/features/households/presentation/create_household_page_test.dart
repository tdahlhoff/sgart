import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/data/households_api.dart';
import 'package:sgart/features/households/presentation/create_household_page.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_households_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('CreateHouseholdPage', () {
    late FakeHouseholdsApi householdsApi;
    late HouseholdsCubit householdsCubit;

    setUp(() {
      householdsApi = FakeHouseholdsApi();
      householdsCubit = HouseholdsCubit(householdsApi: householdsApi);
    });

    tearDown(() => householdsCubit.close());

    Widget buildSubject() => wrapForTesting(
          RepositoryProvider<HouseholdsApi>.value(
            value: householdsApi,
            child: BlocProvider<HouseholdsCubit>.value(
              value: householdsCubit,
              child: const CreateHouseholdPage(),
            ),
          ),
        );

    testWidgets('submittingARejectedNameShowsTheLocalizedErrorInline', (tester) async {
      householdsApi.createErrorToThrow =
          const AppException(AppError(code: 'household.nameRequired', message: 'debug'));
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('create-household-submit-button')));
      await tester.pump();
      await tester.pump();

      expect(find.byKey(const Key('create-household-error')), findsOneWidget);
    });

    testWidgets('submittingASuccessfulNameRoutesTheHouseholdsCubitIntoTheNewHousehold', (tester) async {
      householdsApi.createdHouseholdIdToReturn = 'id-1';
      await tester.pumpWidget(buildSubject());

      await tester.enterText(find.byKey(const Key('household-name-field')), 'Familie Muster');
      await tester.tap(find.byKey(const Key('create-household-submit-button')));
      await tester.pump();
      await tester.pump();

      expect(householdsCubit.state.status.name, 'home');
      expect(householdsCubit.state.currentHousehold!.householdId, 'id-1');
      expect(householdsCubit.state.currentHousehold!.name, 'Familie Muster');
    });
  });
}
