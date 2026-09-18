import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:share_plus_platform_interface/share_plus_platform_interface.dart';
import 'package:sgart/features/invites/presentation/invites_cubit.dart';
import 'package:sgart/features/invites/presentation/invites_view.dart';

import '../../../support/fake_invites_dependencies.dart';
import '../../../support/widget_test_harness.dart';

/// Records every [ShareParams] the view hands to the OS share sheet, so a test can assert *what*
/// was shared without a real platform channel (CLAUDE.md §6 — isolate external systems).
class _RecordingSharePlatform extends SharePlatform {
  final List<ShareParams> sharedParams = [];

  @override
  Future<ShareResult> share(ShareParams params) async {
    sharedParams.add(params);
    return ShareResult('', ShareResultStatus.success);
  }
}

void main() {
  group('InvitesView (Story 7.5, AC1)', () {
    late FakeInvitesApi invitesApi;
    late _RecordingSharePlatform sharePlatform;

    setUp(() {
      invitesApi = FakeInvitesApi();
      sharePlatform = _RecordingSharePlatform();
      SharePlatform.instance = sharePlatform;
    });

    Widget buildSubject() => wrapForTesting(
          RepositoryProvider.value(
            value: invitesApi,
            child: BlocProvider(
              create: (_) => InvitesCubit(invitesApi: invitesApi, householdId: 'household-1')..bootstrap(),
              child: const Scaffold(body: InvitesView()),
            ),
          ),
        );

    testWidgets('invitePage_createInvite_showsCodeAndLink_andSharesViaShareSheet', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('invite-create-button')));
      await tester.pumpAndSettle();

      expect(invitesApi.createCallCount, 1);
      final inviteId = invitesApi.lastCreatedInviteId!;
      expect(find.byKey(const Key('invite-created-card')), findsOneWidget);
      expect(find.text('household-1:$inviteId'), findsOneWidget);

      await tester.tap(find.byKey(const Key('invite-code-share-button')));
      await tester.pumpAndSettle();

      expect(sharePlatform.sharedParams, hasLength(1));
      expect(sharePlatform.sharedParams.single.text, 'household-1:$inviteId');
    });

    testWidgets('copyingTheLinkPutsItOnTheClipboardAndShowsAConfirmation', (tester) async {
      final copiedTexts = <String>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') {
          copiedTexts.add((call.arguments as Map)['text'] as String);
        }
        return null;
      });
      addTearDown(() =>
          tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('invite-create-button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('invite-link-copy-button')));
      await tester.pumpAndSettle();

      final inviteId = invitesApi.lastCreatedInviteId!;
      expect(copiedTexts, ['http://localhost:8081/invite?h=household-1&i=$inviteId']);
      expect(find.byKey(const Key('invite-link-row')), findsOneWidget);
    });

    testWidgets('creatingTwoInvitesInARowShowsTwoIndependentPendingRows', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('invite-create-button')));
      await tester.pumpAndSettle();
      final firstInviteId = invitesApi.lastCreatedInviteId;

      await tester.tap(find.byKey(const Key('invite-create-button')));
      await tester.pumpAndSettle();
      final secondInviteId = invitesApi.lastCreatedInviteId;

      expect(firstInviteId, isNot(secondInviteId));
      expect(find.byKey(Key('invite-row-$firstInviteId')), findsOneWidget);
      expect(find.byKey(Key('invite-row-$secondInviteId')), findsOneWidget);
    });
  });
}
