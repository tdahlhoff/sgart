import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/home/presentation/home_cubit.dart';

void main() {
  group('HomeCubit', () {
    test('starts at zero probes', () {
      expect(HomeCubit().state, 0);
    });

    test('registerProbe increments the probe count', () {
      final cubit = HomeCubit();

      cubit.registerProbe();
      cubit.registerProbe();

      expect(cubit.state, 2);
      cubit.close();
    });
  });
}
