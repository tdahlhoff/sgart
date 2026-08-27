import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item_suggestion.dart';
import 'package:sgart/shared/http/app_exception.dart';

void main() {
  group('ItemSuggestion.fromJson', () {
    test('parsesAllFieldsIncludingANote', () {
      final suggestion = ItemSuggestion.fromJson(const {
        'name': 'Milch',
        'note': 'Bio',
        'amount': '2',
        'unit': 'LITRE',
      });

      expect(suggestion.name, 'Milch');
      expect(suggestion.note, 'Bio');
      expect(suggestion.amount, '2');
      expect(suggestion.unit, 'LITRE');
    });

    test('parsesAMissingNoteAsNull', () {
      final suggestion = ItemSuggestion.fromJson(const {'name': 'Brot', 'amount': '1', 'unit': 'PIECE'});

      expect(suggestion.note, isNull);
    });

    test('throwsAMappedAppExceptionOnAMalformedShape', () {
      expect(
        () => ItemSuggestion.fromJson(const {'name': 'Milch'}),
        throwsA(
          isA<AppException>().having((e) => e.error.code, 'code', 'itemSuggestions.malformedResponse'),
        ),
      );
    });

    test('throwsWhenNoteIsPresentButNotAString', () {
      expect(
        () => ItemSuggestion.fromJson(const {'name': 'Milch', 'note': 7, 'amount': '1', 'unit': 'LITRE'}),
        throwsA(isA<AppException>()),
      );
    });

    test('treatsTheSameAttributesAsEqual', () {
      const first = ItemSuggestion(name: 'Milch', note: 'Bio', amount: '2', unit: 'LITRE');
      const second = ItemSuggestion(name: 'Milch', note: 'Bio', amount: '2', unit: 'LITRE');

      expect(first, second);
      expect(first.hashCode, second.hashCode);
    });
  });
}
