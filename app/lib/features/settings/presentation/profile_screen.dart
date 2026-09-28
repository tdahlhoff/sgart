import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/app_error.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/http/app_exception.dart';
import '../../../shared/http/authenticated_http_client.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../auth/data/account_email_api.dart';
import '../../auth/presentation/account_email_cubit.dart';
import '../../auth/presentation/account_email_state.dart';
import '../../auth/presentation/add_recovery_email_page.dart';
import '../../auth/presentation/auth_cubit.dart';
import '../../auth/presentation/auth_state.dart';
import '../../auth/presentation/recovery_token_reveal_page.dart';
import '../../households/data/household_summary.dart';
import '../../members/data/members_api.dart';
import '../data/nickname_api.dart';
import 'locale_settings_page.dart';
import 'nickname_cubit.dart';
import 'nickname_field.dart';
import 'nickname_state.dart';

/// The Profil tab body (Story 1.11, AC2): a member's personal-only settings — no household
/// management here (that stays in the persistent header switcher, Story 1.7). Renders as a tab
/// body inside [HouseholdShell]'s `IndexedStack`, so it owns no `Scaffold`/`AppBar` of its own —
/// the shell's persistent header stays visible above it.
///
/// Stateful so the Story 7.3 [AccountEmailCubit] is built exactly once (over the ambient
/// [AuthenticatedHttpClient], seeded from the live `AuthState.email`) and disposed with the
/// widget, mirroring [FirstRunRouter]'s "build API clients once" rationale.
class ProfileScreen extends StatefulWidget {
  const ProfileScreen({super.key, required this.activeHousehold});

  /// The household whose nickname this screen shows/edits (Story 8.3) — the Profile header shows
  /// this household's resolved nickname, never the JWT `displayName` (for a silently-provisioned
  /// account that is the raw device-credential id).
  final HouseholdSummary activeHousehold;

  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  late final AccountEmailCubit _accountEmailCubit;
  late final NicknameCubit _nicknameCubit;

  /// The active household's resolved nickname for the caller (Story 8.3) — fetched once via the
  /// member roster (the same published resolution path the roster itself uses; no dedicated
  /// "my own nickname" read endpoint exists, avoiding a duplicate contract, YAGNI). `null` while
  /// loading or when genuinely unset — header and row then show the neutral fallback.
  String? _nickname;

  @override
  void initState() {
    super.initState();
    final authState = context.read<AuthCubit>().state;
    _nicknameCubit = NicknameCubit(nicknameApi: _resolveNicknameApi(context));
    _loadNickname();
    // Seed from the live `/me` claims (Story 7.3, review finding): `emailVerified` now travels
    // with `AuthState.email`, so an attached-but-unconfirmed address seeds `pendingConfirmation`
    // (offering "confirm", not "detach") instead of misreading as `confirmed` on relaunch. Every
    // in-session attach/confirm/detach action refines it precisely from there.
    final hasEmail = authState.email != null && authState.email!.isNotEmpty;
    final seededStatus = !hasEmail
        ? AccountEmailStatus.notAttached
        : (authState.emailVerified ? AccountEmailStatus.confirmed : AccountEmailStatus.pendingConfirmation);
    _accountEmailCubit = AccountEmailCubit(
      _resolveAccountEmailApi(context),
      initialState: AccountEmailState(status: seededStatus, email: authState.email),
    );
  }

  /// Prefers an explicitly-provided [AccountEmailApi] ancestor (the test seam: a widget test
  /// drives attach/confirm/detach with a fake, no real HTTP client, CLAUDE.md §6) over building the
  /// real one from the ambient [AuthenticatedHttpClient]. Guarded exactly like `HouseholdShell`'s
  /// `PushNotificationsResolver`/`FirstRunRouterBody`'s `PendingInviteLinkCubitResolver`: a harness
  /// with neither ancestor (most widget tests that render `ProfileScreen` only incidentally, e.g.
  /// via an `IndexedStack` alongside other tabs) gets an inert API instead of a
  /// `ProviderNotFoundException` — the section still renders, and only an actual attach/confirm/
  /// detach action would ever need it.
  AccountEmailApi _resolveAccountEmailApi(BuildContext context) {
    try {
      return context.read<AccountEmailApi>();
    } on Object {
      // No injected fake — fall through to the real, HTTP-backed API.
    }
    try {
      return HttpAccountEmailApi(context.read<AuthenticatedHttpClient>());
    } on Object {
      return const _UnavailableAccountEmailApi();
    }
  }

  /// Mirrors [_resolveAccountEmailApi]'s guarded-optional resolution (Story 8.3): a widget test
  /// that injects its own fake gets it, production builds the real HTTP-backed one, and a harness
  /// with neither ancestor gets an inert stand-in rather than a `ProviderNotFoundException`.
  NicknameApi _resolveNicknameApi(BuildContext context) {
    try {
      return context.read<NicknameApi>();
    } on Object {
      // No injected fake — fall through to the real, HTTP-backed API.
    }
    try {
      return HttpNicknameApi(context.read<AuthenticatedHttpClient>());
    } on Object {
      return const _UnavailableNicknameApi();
    }
  }

  /// Same guarded-optional pattern for [MembersApi] — only used to look up the caller's own
  /// already-resolved nickname (see [_nickname]'s doc); a harness with no ancestor simply never
  /// resolves one rather than crashing.
  MembersApi? _resolveMembersApi(BuildContext context) {
    try {
      return context.read<MembersApi>();
    } on Object {
      return null;
    }
  }

  /// The shell keeps this screen alive across a household switch (its `IndexedStack` is not
  /// re-keyed per household), so a switch must drop the previous household's nickname and reload.
  @override
  void didUpdateWidget(covariant ProfileScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.activeHousehold.householdId != widget.activeHousehold.householdId) {
      setState(() => _nickname = null);
      _loadNickname();
    }
  }

  void _loadNickname() {
    final membersApi = _resolveMembersApi(context);
    if (membersApi == null) {
      return;
    }
    final householdId = widget.activeHousehold.householdId;
    membersApi.listMembers(householdId).then((members) {
      // A response for a household that is no longer active must not overwrite the current one.
      if (!mounted || householdId != widget.activeHousehold.householdId) {
        return;
      }
      final self = members.where((member) => member.isSelf).firstOrNull;
      if (self?.nickname != null) {
        setState(() => _nickname = self!.nickname);
      }
    }).catchError((Object _) {
      // Best-effort: the header simply keeps the neutral fallback (I/O matrix — never crashes,
      // never shows the credential id).
    });
  }

  Future<void> _openEditNicknameDialog(BuildContext context) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => BlocProvider<NicknameCubit>.value(
        value: _nicknameCubit,
        child: _EditNicknameDialog(
          initialNickname: _nickname,
          householdId: widget.activeHousehold.householdId,
        ),
      ),
    );
    if (saved == true && mounted) {
      setState(() => _nickname = _nicknameCubit.state.nickname);
    }
  }

  @override
  void dispose() {
    _accountEmailCubit.close();
    _nicknameCubit.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final authState = context.watch<AuthCubit>().state;

    return BlocProvider<AccountEmailCubit>.value(
      value: _accountEmailCubit,
      child: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(SgartShapes.cardPadding),
          children: [
            // Story 8.3: the header shows the active household's resolved nickname, else the neutral
            // fallback — never the JWT displayName (the raw device-credential id for a
            // silently-provisioned account, F3).
            _IdentityHeader(
              displayName: _nickname ?? localizations.membersNicknameFallback,
              email: authState.email,
            ),
            const SizedBox(height: SgartShapes.space4),
            Text(
              localizations.profileNicknameSectionLabel(widget.activeHousehold.name),
              style: Theme.of(context).textTheme.labelLarge,
            ),
            ListTile(
              key: const Key('profile-nickname-row'),
              leading: const Icon(Icons.badge_outlined),
              title: Text(_nickname ?? localizations.membersNicknameFallback, key: const Key('profile-nickname-value')),
              trailing: TextButton(
                key: const Key('profile-nickname-edit-button'),
                onPressed: () => _openEditNicknameDialog(context),
                child: Text(localizations.profileNicknameEditAction),
              ),
            ),
            const Divider(height: SgartShapes.space4 * 2),
            Text(localizations.profileAccountSectionLabel, style: Theme.of(context).textTheme.labelLarge),
            ListTile(
              key: const Key('profile-recovery-token-row'),
              leading: const Icon(Icons.key_outlined),
              title: Text(localizations.profileRecoveryTokenRowLabel),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => openRecoveryTokenRevealPage(context),
            ),
            const Divider(height: SgartShapes.space4 * 2),
            Text(localizations.profileRecoveryEmailSectionLabel, style: Theme.of(context).textTheme.labelLarge),
            const _RecoveryEmailSection(),
            const Divider(height: SgartShapes.space4 * 2),
            Text(localizations.profileDisplaySectionLabel, style: Theme.of(context).textTheme.labelLarge),
            ListTile(
              key: const Key('profile-locale-row'),
              leading: const Icon(Icons.translate_outlined),
              title: Text(localizations.profileLocaleRowLabel),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).push<void>(
                MaterialPageRoute(builder: (_) => const LocaleSettingsPage()),
              ),
            ),
            const Divider(height: SgartShapes.space4 * 2),
            Text(localizations.profileNotificationsSectionLabel,
                style: Theme.of(context).textTheme.labelLarge),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: SgartShapes.space2),
              child: Text(localizations.profileNotificationsInfo, key: const Key('profile-notifications-info')),
            ),
          ],
        ),
      ),
    );
  }
}

/// The "E-Mail-Wiederherstellung" section body (Story 7.3, AC1): not-attached / pending-
/// confirmation / confirmed state, an attach action, and a detach action — reads the ambient
/// [AccountEmailCubit] [_ProfileScreenState] provides.
class _RecoveryEmailSection extends StatelessWidget {
  const _RecoveryEmailSection();

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocBuilder<AccountEmailCubit, AccountEmailState>(
      builder: (context, state) {
        final subtitle = switch (state.status) {
          // Deliberately not the raw email itself (the identity header above already shows it live
          // from AuthState — repeating it here would duplicate the same text node and, at the
          // wider a11y text scale, read as redundant rather than informative).
          AccountEmailStatus.confirmed => localizations.profileRecoveryEmailConfirmedLabel,
          AccountEmailStatus.pendingConfirmation => localizations.profileRecoveryEmailPendingLabel,
          AccountEmailStatus.notAttached ||
          AccountEmailStatus.unknown =>
            localizations.profileRecoveryEmailNotAttachedLabel,
        };
        final isAttached =
            state.status == AccountEmailStatus.confirmed || state.status == AccountEmailStatus.pendingConfirmation;

        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            ListTile(
              key: const Key('profile-recovery-email-row'),
              leading: const Icon(Icons.email_outlined),
              title: Text(localizations.profileRecoveryEmailSectionLabel),
              subtitle: Text(subtitle, key: const Key('profile-recovery-email-status')),
              trailing: isAttached
                  ? IconButton(
                      key: const Key('profile-recovery-email-detach-button'),
                      icon: const Icon(Icons.delete_outline),
                      tooltip: localizations.profileRecoveryEmailDetachAction,
                      onPressed: () => _confirmDetach(context, localizations),
                    )
                  : IconButton(
                      key: const Key('profile-recovery-email-add-button'),
                      icon: const Icon(Icons.add),
                      tooltip: localizations.profileRecoveryEmailAddAction,
                      onPressed: () => openAddRecoveryEmailPage(context, context.read<AccountEmailCubit>()),
                    ),
            ),
            if (state.error != null)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: SgartShapes.space4),
                child: Text(
                  localizedMessageForErrorCode(localizations, state.error!.code),
                  key: const Key('profile-recovery-email-error'),
                ),
              ),
          ],
        );
      },
    );
  }

  Future<void> _confirmDetach(BuildContext context, AppLocalizations localizations) async {
    final accountEmailCubit = context.read<AccountEmailCubit>();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(localizations.profileRecoveryEmailDetachConfirmTitle),
        content: Text(localizations.profileRecoveryEmailDetachConfirmMessage),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(MaterialLocalizations.of(dialogContext).cancelButtonLabel),
          ),
          TextButton(
            key: const Key('profile-recovery-email-detach-confirm-button'),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(localizations.profileRecoveryEmailDetachAction),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      await accountEmailCubit.detach();
    }
  }
}

/// Only ever constructed when no [AuthenticatedHttpClient] ancestor exists (a test harness
/// rendering [ProfileScreen] incidentally) — every method fails fast rather than the section
/// silently pretending success, so a stray real action in such a harness surfaces immediately.
class _UnavailableAccountEmailApi implements AccountEmailApi {
  const _UnavailableAccountEmailApi();

  static const _error = AppException(
    AppError(code: 'account.unknown', message: 'AccountEmailApi unavailable — no AuthenticatedHttpClient ancestor'),
  );

  @override
  Future<void> attach(String email) => throw _error;

  @override
  Future<void> confirm(String code) => throw _error;

  @override
  Future<void> detach() => throw _error;

  @override
  Future<void> requestRecoveryCode(String email) => throw _error;

  @override
  Future<void> confirmRecovery(String email, String code) => throw _error;
}

/// The Profile screen's nickname-edit dialog (Story 8.3) — its own [StatefulWidget] so the
/// [TextEditingController] it owns is disposed by the normal widget lifecycle (on the dialog
/// route's own removal), never manually right after `showDialog` returns, which races the dialog's
/// still-animating exit transition and throws "used after being disposed" (mirrors
/// `_DeleteHouseholdConfirmDialog` in `members_page.dart`).
class _EditNicknameDialog extends StatefulWidget {
  const _EditNicknameDialog({required this.initialNickname, required this.householdId});

  final String? initialNickname;
  final String householdId;

  @override
  State<_EditNicknameDialog> createState() => _EditNicknameDialogState();
}

class _EditNicknameDialogState extends State<_EditNicknameDialog> {
  late final TextEditingController _controller = TextEditingController(text: widget.initialNickname ?? '');

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocListener<NicknameCubit, NicknameState>(
      listenWhen: (previous, current) => current.status == NicknameStatus.success,
      listener: (context, state) => Navigator.of(context).pop(true),
      child: AlertDialog(
        title: Text(localizations.profileNicknameEditDialogTitle),
        content: NicknameField(
          controller: _controller,
          fieldKey: const Key('profile-nickname-field'),
          errorKey: const Key('profile-nickname-error'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(localizations.membersCancelButtonLabel),
          ),
          TextButton(
            key: const Key('profile-nickname-save-button'),
            onPressed: () => context.read<NicknameCubit>().submit(widget.householdId, _controller.text),
            child: Text(localizations.membersConfirmButtonLabel),
          ),
        ],
      ),
    );
  }
}

/// Only ever constructed when no [AuthenticatedHttpClient] ancestor exists — mirrors
/// [_UnavailableAccountEmailApi].
class _UnavailableNicknameApi implements NicknameApi {
  const _UnavailableNicknameApi();

  @override
  Future<void> setNickname(String householdId, String nickname) => throw const AppException(
        AppError(code: 'nickname.unknown', message: 'NicknameApi unavailable — no AuthenticatedHttpClient ancestor'),
      );
}

/// Display-only identity block: an avatar showing the display name's initial, the display name
/// (the active household's nickname or its neutral fallback, Story 8.3), and the email read live
/// from [AuthState].
class _IdentityHeader extends StatelessWidget {
  const _IdentityHeader({required this.displayName, required this.email});

  final String displayName;
  final String? email;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Row(
      children: [
        CircleAvatar(
          radius: SgartShapes.minTapTarget / 2,
          child: Text(_initial(displayName)),
        ),
        const SizedBox(width: SgartShapes.space4),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(displayName, key: const Key('profile-display-name'), style: theme.textTheme.titleMedium),
              Text(email ?? '', key: const Key('profile-email'), style: theme.textTheme.bodyMedium),
            ],
          ),
        ),
      ],
    );
  }

  /// The display name's first grapheme cluster, uppercased, as the avatar glyph — a generic „?"
  /// when the name is empty rather than an empty/crashing avatar. Uses grapheme clusters (not
  /// UTF-16 code units) so an emoji, astral, or combining first character is not split into a
  /// broken half-glyph.
  String _initial(String name) {
    final characters = name.trim().characters;
    return characters.isEmpty ? '?' : characters.first.toUpperCase();
  }
}
