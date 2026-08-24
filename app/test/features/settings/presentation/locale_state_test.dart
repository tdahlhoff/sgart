import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/settings/presentation/locale_state.dart';

void main() {
  group('LocaleState', () {
    test('systemLocaleFollowsTheDeviceAndPersistsNothing', () {
      const state = SystemLocale();

      expect(state.effectiveLocale, isNull);
      expect(state.persistableTag, isNull);
    });

    test('explicitLocalePinsTheLocaleAndPersistsItsCanonicalTag', () {
      const state = ExplicitLocale(Locale('de', 'CH'));

      expect(state.effectiveLocale, const Locale('de', 'CH'));
      expect(state.persistableTag, 'de-CH');
    });

    test('rebuildsSystemLocaleFromAnAbsentStoredTag', () {
      expect(LocaleState.fromStoredTag(null), const SystemLocale());
    });

    test('rebuildsAnExplicitRegionLocaleFromAStoredTag', () {
      expect(LocaleState.fromStoredTag('de-CH'), const ExplicitLocale(Locale('de', 'CH')));
    });

    test('twoExplicitLocalesForTheSameRegionAreEqual', () {
      expect(const ExplicitLocale(Locale('de', 'AT')), const ExplicitLocale(Locale('de', 'AT')));
      expect(const ExplicitLocale(Locale('de', 'AT')), isNot(const ExplicitLocale(Locale('de', 'CH'))));
    });
  });
}
