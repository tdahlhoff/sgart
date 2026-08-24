import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/settings/data/locale_preference_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('SharedPreferencesLocalePreferenceStore', () {
    const store = SharedPreferencesLocalePreferenceStore();

    setUp(() => SharedPreferences.setMockInitialValues({}));

    test('writesAndReadsBackAUsersLocaleTag', () async {
      await store.write('user-1', 'de-CH');

      expect(await store.read('user-1'), 'de-CH');
    });

    test('returnsNullWhenTheUserHasNoStoredLocale', () async {
      expect(await store.read('user-without-a-choice'), isNull);
    });

    test('keepsEachUsersLocaleIsolatedFromOthersOnTheSameDevice', () async {
      await store.write('user-1', 'de-CH');
      await store.write('user-2', 'de-AT');

      expect(await store.read('user-1'), 'de-CH');
      expect(await store.read('user-2'), 'de-AT');
    });

    test('clearRemovesOnlyThatUsersLocale', () async {
      await store.write('user-1', 'de-CH');
      await store.write('user-2', 'de-AT');

      await store.clear('user-1');

      expect(await store.read('user-1'), isNull);
      expect(await store.read('user-2'), 'de-AT');
    });
  });
}
