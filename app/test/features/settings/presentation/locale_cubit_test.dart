import 'package:bloc_test/bloc_test.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/settings/presentation/locale_cubit.dart';
import 'package:sgart/features/settings/presentation/locale_state.dart';

import '../../../support/fake_settings_dependencies.dart';

void main() {
  group('LocaleCubit', () {
    late FakeLocalePreferenceStore store;

    setUp(() => store = FakeLocalePreferenceStore());

    LocaleCubit buildCubit() => LocaleCubit(store);

    test('startsFollowingTheDeviceLocale', () {
      expect(buildCubit().state, const SystemLocale());
    });

    blocTest<LocaleCubit, LocaleState>(
      'applyForUserRestoresAStoredRegionLocaleOnSignIn',
      build: () {
        store.seed('user-1', 'de-CH');
        return buildCubit();
      },
      act: (cubit) => cubit.applyForUser('user-1'),
      expect: () => [const ExplicitLocale(Locale('de', 'CH'))],
    );

    blocTest<LocaleCubit, LocaleState>(
      'applyForUserStaysOnSystemWhenTheUserHasNoStoredLocale',
      build: buildCubit,
      act: (cubit) => cubit.applyForUser('user-without-a-choice'),
      expect: () => [const SystemLocale()],
    );

    blocTest<LocaleCubit, LocaleState>(
      'selectAppliesAndPersistsTheChosenRegionForTheCurrentUser',
      build: buildCubit,
      act: (cubit) async {
        await cubit.applyForUser('user-1'); // no stored choice → stays on the device default
        await cubit.select(const ExplicitLocale(Locale('de', 'CH')));
      },
      expect: () => [const SystemLocale(), const ExplicitLocale(Locale('de', 'CH'))],
      verify: (_) => expect(store.storedTagFor('user-1'), 'de-CH'),
    );

    blocTest<LocaleCubit, LocaleState>(
      'selectingSystemClearsTheStoredTag',
      build: () {
        store.seed('user-1', 'de-CH');
        return buildCubit();
      },
      act: (cubit) async {
        await cubit.applyForUser('user-1');
        await cubit.select(const SystemLocale());
      },
      expect: () => [const ExplicitLocale(Locale('de', 'CH')), const SystemLocale()],
      verify: (_) => expect(store.storedTagFor('user-1'), isNull),
    );

    blocTest<LocaleCubit, LocaleState>(
      'resetForSignOutReturnsToTheDeviceDefaultAndErasesTheStoredLocale',
      build: () {
        store.seed('user-1', 'de-CH');
        return buildCubit();
      },
      act: (cubit) async {
        await cubit.applyForUser('user-1');
        await cubit.resetForSignOut();
      },
      expect: () => [const ExplicitLocale(Locale('de', 'CH')), const SystemLocale()],
      verify: (_) {
        expect(store.clearedUsers, contains('user-1'));
        expect(store.storedTagFor('user-1'), isNull);
      },
    );

    test('degradesToSystemWhenTheStoreThrowsOnSignIn', () async {
      store.throwOnAccess = true;
      final cubit = buildCubit();

      // A throwing store must not crash the app boot; it falls back to the device default.
      await cubit.applyForUser('user-1');

      expect(cubit.state, const SystemLocale());
    });

    test('selectKeepsTheAppliedSelectionEvenWhenPersistenceThrows', () async {
      final cubit = buildCubit();
      await cubit.applyForUser('user-1');
      store.throwOnAccess = true;

      await cubit.select(const ExplicitLocale(Locale('de', 'CH')));

      // The selection is applied in memory even though the write failed — no crash, no revert.
      expect(cubit.state, const ExplicitLocale(Locale('de', 'CH')));
    });

    test('doesNotThrowWhenAnAwaitedStoreCallResolvesAfterTheCubitIsClosed', () async {
      store.seed('user-1', 'de-CH');
      final cubit = buildCubit();
      await cubit.close();

      // Emitting after close throws StateError; the holder may be torn down mid sign-in while a store
      // call is in flight, so every entry point must tolerate a closed cubit rather than crash.
      await cubit.applyForUser('user-1');
      await cubit.select(const ExplicitLocale(Locale('de', 'AT')));
      await cubit.resetForSignOut();
    });
  });
}
