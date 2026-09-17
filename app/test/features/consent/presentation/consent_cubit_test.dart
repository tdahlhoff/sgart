import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/consent/data/consent_api.dart';
import 'package:sgart/features/consent/presentation/consent_cubit.dart';
import 'package:sgart/features/consent/presentation/consent_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_consent_dependencies.dart';

void main() {
  group('ConsentCubit', () {
    late FakeConsentApi consentApi;

    setUp(() {
      consentApi = FakeConsentApi();
    });

    ConsentCubit buildCubit() => ConsentCubit(consentApi: consentApi);

    blocTest<ConsentCubit, ConsentState>(
      'load_notYetAccepted_emitsNeedsConsentWithTheCurrentVersion',
      build: () {
        consentApi.statusToReturn =
            const ConsentStatus(accepted: false, acceptedVersion: null, currentVersion: '2026-beta-1');
        return buildCubit();
      },
      act: (cubit) => cubit.load(),
      expect: () => [
        const ConsentState.loading(),
        const ConsentState.needsConsent('2026-beta-1'),
      ],
    );

    blocTest<ConsentCubit, ConsentState>(
      'load_alreadyAcceptedTheCurrentVersion_emitsAccepted',
      build: () {
        consentApi.statusToReturn = const ConsentStatus(
          accepted: true,
          acceptedVersion: '2026-beta-1',
          currentVersion: '2026-beta-1',
        );
        return buildCubit();
      },
      act: (cubit) => cubit.load(),
      expect: () => [
        const ConsentState.loading(),
        const ConsentState.accepted(),
      ],
    );

    blocTest<ConsentCubit, ConsentState>(
      'load_acceptedAnOlderVersion_emitsNeedsConsentAgain',
      build: () {
        consentApi.statusToReturn = const ConsentStatus(
          accepted: true,
          acceptedVersion: '2026-beta-1',
          currentVersion: '2026-beta-2',
        );
        return buildCubit();
      },
      act: (cubit) => cubit.load(),
      expect: () => [
        const ConsentState.loading(),
        const ConsentState.needsConsent('2026-beta-2'),
      ],
    );

    blocTest<ConsentCubit, ConsentState>(
      'accept_recordsTheCurrentVersionAndEmitsAccepted',
      build: buildCubit,
      seed: () => const ConsentState.needsConsent('2026-beta-1'),
      act: (cubit) => cubit.accept(),
      expect: () => [
        const ConsentState.loading(),
        const ConsentState.accepted(),
      ],
      verify: (_) {
        expect(consentApi.lastAcceptedVersion, '2026-beta-1');
        expect(consentApi.acceptCallCount, 1);
      },
    );

    blocTest<ConsentCubit, ConsentState>(
      'accept_serverRejectsWith409ConsentRequired_emitsFailureWithTheMappedCode',
      build: () {
        consentApi.acceptError =
            const AppException(AppError(code: 'consent.required', message: 'debug'));
        return buildCubit();
      },
      seed: () => const ConsentState.needsConsent('2026-beta-1'),
      act: (cubit) => cubit.accept(),
      expect: () => [
        const ConsentState.loading(),
        const ConsentState.failure(AppError(code: 'consent.required', message: 'debug')),
      ],
    );

    blocTest<ConsentCubit, ConsentState>(
      'load_networkFailure_emitsFailure',
      build: () {
        consentApi.getStatusError =
            const AppException(AppError(code: 'network.unreachable', message: 'debug'));
        return buildCubit();
      },
      act: (cubit) => cubit.load(),
      expect: () => [
        const ConsentState.loading(),
        const ConsentState.failure(AppError(code: 'network.unreachable', message: 'debug')),
      ],
    );
  });
}
