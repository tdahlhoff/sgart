import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/data/households_api.dart';
import 'package:sgart/features/households/presentation/create_or_await_choice_page.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';

import '../../../support/fake_households_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('CreateOrAwaitChoicePage', () {
    late FakeHouseholdsApi householdsApi;
    late HouseholdsCubit householdsCubit;

    setUp(() {
      householdsApi = FakeHouseholdsApi();
      householdsCubit =
          HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: FakeActiveHouseholdStore());
    });

    tearDown(() => householdsCubit.close());

    // The providers sit *below* the MaterialApp's Navigator (as they do in production, created
    // inside FirstRunRouter under the root Navigator). Pushing the create page must still reach
    // them — a regression guard for the ProviderNotFoundException that crashed the primary AC1
    // path when the pushed route escaped the FirstRunRouter providers.
    Widget buildSubject() => wrapForTesting(
          RepositoryProvider<HouseholdsApi>.value(
            value: householdsApi,
            child: BlocProvider<HouseholdsCubit>.value(
              value: householdsCubit,
              child: const CreateOrAwaitChoicePage(),
            ),
          ),
        );

    testWidgets('openingTheCreateHouseholdFormDoesNotEscapeItsProviders', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('create-household-choice-button')));
      await tester.pumpAndSettle();

      // The form built without a ProviderNotFoundException — it could read HouseholdsApi (for its
      // CreateHouseholdCubit) and HouseholdsCubit (for the success listener).
      expect(find.byKey(const Key('household-name-field')), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });
}
