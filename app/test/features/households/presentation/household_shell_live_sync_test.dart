import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/presentation/household_shell.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/lists/data/shopping_lists_api.dart';
import 'package:sgart/shared/sync/household_event_stream.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_shopping_lists_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('HouseholdShell live-sync status indicator (Story 4.4, T10)', () {
    late AuthCubit authCubit;
    late FakeShoppingListsApi shoppingListsApi;
    late StreamController<List<int>> byteController;
    late HouseholdEventStream eventStream;

    const household = HouseholdSummary(householdId: 'id-1', name: 'Familie Muster');

    setUp(() async {
      authCubit = await buildAuthenticatedAuthCubit();
      shoppingListsApi = FakeShoppingListsApi();
      byteController = StreamController<List<int>>();
      eventStream = HouseholdEventStream(connect: () async => byteController.stream);
    });

    tearDown(() async {
      await authCubit.close();
      await eventStream.dispose();
    });

    Widget buildSubject() => wrapForTesting(
          BlocProvider<AuthCubit>.value(
            value: authCubit,
            child: RepositoryProvider<ShoppingListsApi>.value(
              value: shoppingListsApi,
              child: HouseholdShell(
                activeHousehold: household,
                households: const [household],
                eventStreamFactory: (context, householdId) => eventStream,
              ),
            ),
          ),
        );

    testWidgets('showsTheSyncingIconWhileConnecting', (tester) async {
      // A connector that never resolves — the connection stays in `connecting` for the whole test,
      // unlike the always-instantly-resolving fake the other tests use.
      final neverConnects = HouseholdEventStream(connect: () => Completer<Stream<List<int>>>().future);
      addTearDown(neverConnects.dispose);

      await tester.pumpWidget(wrapForTesting(
        BlocProvider<AuthCubit>.value(
          value: authCubit,
          child: RepositoryProvider<ShoppingListsApi>.value(
            value: shoppingListsApi,
            child: HouseholdShell(
              activeHousehold: household,
              households: const [household],
              eventStreamFactory: (context, householdId) => neverConnects,
            ),
          ),
        ),
      ));
      await tester.pump();

      expect(find.byKey(const Key('sync-status-indicator')), findsOneWidget);
      final icon = tester.widget<Icon>(find.byKey(const Key('sync-status-indicator')));
      expect(icon.icon, Icons.cloud_sync_outlined);
    });

    testWidgets('swapsToTheLiveIconOnceConnected', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 10));

      final icon = tester.widget<Icon>(find.byKey(const Key('sync-status-indicator')));
      expect(icon.icon, Icons.cloud_done_outlined);
      expect(find.byTooltip('Synchronisiert'), findsOneWidget);
    });

    testWidgets('swapsToTheOfflineIconAfterAStreamRejection', (tester) async {
      // This test never starts the shared `eventStream`/`byteController` from setUp — it builds
      // its own rejecting stream below. (Closing an unlistened single-subscription
      // `StreamController` would itself hang: `close()`'s future waits for a subscriber to
      // deliver the done event to, which would never arrive.)
      final rejectingEventStream = HouseholdEventStream(
        connect: () async => throw const HouseholdStreamRejected(403),
      );
      addTearDown(rejectingEventStream.dispose);

      await tester.pumpWidget(wrapForTesting(
        BlocProvider<AuthCubit>.value(
          value: authCubit,
          child: RepositoryProvider<ShoppingListsApi>.value(
            value: shoppingListsApi,
            child: HouseholdShell(
              activeHousehold: household,
              households: const [household],
              eventStreamFactory: (context, householdId) => rejectingEventStream,
            ),
          ),
        ),
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 10));

      final icon = tester.widget<Icon>(find.byKey(const Key('sync-status-indicator')));
      expect(icon.icon, Icons.cloud_off_outlined);
    });

    testWidgets('rebootstrapsHouseholdsCubitOnceTheStreamIsRevoked', (tester) async {
      // Regression (Review P1/decision ①1): a 403 must drive HouseholdsCubit to re-fetch and
      // re-route so the removed member's household drops out of view, not just paint an icon.
      final householdsApi = FakeHouseholdsApi()..householdsToReturn = const [household];
      final householdsCubit = HouseholdsCubit(
        householdsApi: householdsApi,
        activeHouseholdStore: FakeActiveHouseholdStore(activeId: household.householdId),
      );
      await householdsCubit.bootstrap();
      addTearDown(householdsCubit.close);
      final rejectingEventStream =
          HouseholdEventStream(connect: () async => throw const HouseholdStreamRejected(403));
      addTearDown(rejectingEventStream.dispose);
      final listCallCountBeforeRevocation = householdsApi.listCallCount;

      await tester.pumpWidget(wrapForTesting(
        BlocProvider<AuthCubit>.value(
          value: authCubit,
          child: RepositoryProvider<ShoppingListsApi>.value(
            value: shoppingListsApi,
            child: BlocProvider<HouseholdsCubit>.value(
              value: householdsCubit,
              child: HouseholdShell(
                activeHousehold: household,
                households: const [household],
                eventStreamFactory: (context, householdId) => rejectingEventStream,
              ),
            ),
          ),
        ),
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 10));

      expect(householdsApi.listCallCount, listCallCountBeforeRevocation + 1);
    });

    testWidgets('rebootstrapsHouseholdsCubitOnAHouseholdResourceNudge', (tester) async {
      // Regression (Review P8): a rename by another member must refresh the switcher chip.
      final householdsApi = FakeHouseholdsApi()..householdsToReturn = const [household];
      final householdsCubit = HouseholdsCubit(
        householdsApi: householdsApi,
        activeHouseholdStore: FakeActiveHouseholdStore(activeId: household.householdId),
      );
      await householdsCubit.bootstrap();
      addTearDown(householdsCubit.close);
      final listCallCountBeforeNudge = householdsApi.listCallCount;

      await tester.pumpWidget(wrapForTesting(
        BlocProvider<AuthCubit>.value(
          value: authCubit,
          child: RepositoryProvider<ShoppingListsApi>.value(
            value: shoppingListsApi,
            child: BlocProvider<HouseholdsCubit>.value(
              value: householdsCubit,
              child: HouseholdShell(
                activeHousehold: household,
                households: const [household],
                eventStreamFactory: (context, householdId) => eventStream,
              ),
            ),
          ),
        ),
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 10));

      byteController.add(utf8.encode('event: changed\ndata: {"householdId":"id-1","resource":"household"}\n\n'));
      await tester.pump(const Duration(milliseconds: 350)); // past the controller's 300ms coalesce window

      expect(householdsApi.listCallCount, listCallCountBeforeNudge + 1);
    });
  });
}
