import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/shared/http/app_exception.dart';

void main() {
  group('Item.fromJson', () {
    test('parsesAllFieldsIncludingANote', () {
      final item = Item.fromJson(const {
        'itemId': 'i1',
        'name': 'Milch',
        'note': 'Bio',
        'amount': '1',
        'unit': 'PIECE',
      });

      expect(item.itemId, 'i1');
      expect(item.name, 'Milch');
      expect(item.note, 'Bio');
      expect(item.amount, '1');
      expect(item.unit, 'PIECE');
    });

    test('parsesAMissingNoteAsNull', () {
      final item = Item.fromJson(const {'itemId': 'i1', 'name': 'Milch', 'amount': '1', 'unit': 'PIECE'});

      expect(item.note, isNull);
    });

    test('parsesAnAssignedStoreId', () {
      final item = Item.fromJson(const {
        'itemId': 'i1',
        'name': 'Milch',
        'amount': '1',
        'unit': 'PIECE',
        'storeId': 's1',
      });

      expect(item.storeId, 's1');
    });

    test('parsesAMissingStoreIdAsNull', () {
      final item = Item.fromJson(const {'itemId': 'i1', 'name': 'Milch', 'amount': '1', 'unit': 'PIECE'});

      expect(item.storeId, isNull);
    });

    test('throwsWhenStoreIdIsPresentButNotAString', () {
      expect(
        () => Item.fromJson(
            const {'itemId': 'i1', 'name': 'Milch', 'amount': '1', 'unit': 'PIECE', 'storeId': 7}),
        throwsA(isA<AppException>()),
      );
    });

    test('throwsAMappedAppExceptionOnAMalformedShape', () {
      expect(
        () => Item.fromJson(const {'itemId': 'i1', 'name': 'Milch'}),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'items.malformedResponse')),
      );
    });

    test('throwsWhenNoteIsPresentButNotAString', () {
      expect(
        () => Item.fromJson(const {'itemId': 'i1', 'name': 'Milch', 'note': 7, 'amount': '1', 'unit': 'PIECE'}),
        throwsA(isA<AppException>()),
      );
    });
  });
}
