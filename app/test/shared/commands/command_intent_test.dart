import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/commands/command_intent.dart';

void main() {
  group('CommandIntent', () {
    /// A deterministic id factory — sequential ids so regeneration is crisply assertable.
    IdFactory countingMintId() {
      var next = 0;
      return () => 'id-${next++}';
    }

    test('mintsACommandIdOnConstruction', () {
      final intent = CommandIntent(mintId: countingMintId());
      expect(intent.commandId, 'id-0');
    });

    test('reusesTheCommandIdAcrossRetriesOfTheSamePayload', () {
      final intent = CommandIntent(mintId: countingMintId());

      intent.beginAttempt('Milch');
      final first = intent.commandId;
      intent.beginAttempt('Milch'); // a retry of the same intent
      final retry = intent.commandId;

      expect(retry, first, reason: 'an idempotent retry must reuse the command id so the backend converges');
    });

    test('regeneratesTheCommandIdWhenThePayloadChanges', () {
      final intent = CommandIntent(mintId: countingMintId());

      intent.beginAttempt('Milch');
      final first = intent.commandId;
      intent.beginAttempt('Brot'); // an edited retry — a new intent
      final edited = intent.commandId;

      expect(edited, isNot(first),
          reason: 'an edited retry must not dedupe as a silent no-op against an earlier append (Story 1.7)');
    });

    test('regeneratesTheCommandIdAfterACompletedIntent', () {
      final intent = CommandIntent(mintId: countingMintId());

      intent.beginAttempt('Milch');
      final first = intent.commandId;
      intent.complete(); // the append succeeded
      intent.beginAttempt('Milch'); // a second, distinct add of the same-named store
      final next = intent.commandId;

      expect(next, isNot(first),
          reason: 'a new command must not reuse an id the server already applied (Story 1.8)');
    });

    test('keepsTheFreshenedIdsForTheFirstAttemptAfterCompletion', () {
      final intent = CommandIntent(mintId: countingMintId());

      intent.beginAttempt('Milch');
      intent.complete();
      final afterComplete = intent.commandId;
      intent.beginAttempt('Milch'); // first attempt of the next intent — keeps the freshened id
      expect(intent.commandId, afterComplete);
    });

    group('paired resource id', () {
      test('mintsAResourceIdWhenRequested', () {
        final intent = CommandIntent(hasResourceId: true, mintId: countingMintId());
        expect(intent.commandId, 'id-0');
        expect(intent.resourceId(), 'id-1');
      });

      test('reusesTheResourceIdAcrossRetriesOfTheSamePayload', () {
        final intent = CommandIntent(hasResourceId: true, mintId: countingMintId());

        intent.beginAttempt('Milch');
        final first = intent.resourceId();
        intent.beginAttempt('Milch');
        expect(intent.resourceId(), first,
            reason: 'the reused store id must match what the server persisted on the first attempt');
      });

      test('regeneratesTheResourceIdInLockstepWithTheCommandIdWhenThePayloadChanges', () {
        final intent = CommandIntent(hasResourceId: true, mintId: countingMintId());

        intent.beginAttempt('Milch');
        final firstCommandId = intent.commandId;
        final firstResourceId = intent.resourceId();
        intent.beginAttempt('Brot');

        expect(intent.commandId, isNot(firstCommandId));
        expect(intent.resourceId(), isNot(firstResourceId));
      });

      test('throwsWhenNoResourceIdWasRequested', () {
        final intent = CommandIntent(mintId: countingMintId());
        expect(() => intent.resourceId(), throwsStateError);
      });
    });
  });
}
