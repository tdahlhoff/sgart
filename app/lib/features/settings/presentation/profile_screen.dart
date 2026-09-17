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
import '../../auth/presentation/recovery_phrase_reveal_page.dart';
import 'locale_settings_page.dart';

/// The Profil tab body (Story 1.11, AC2): a member's personal-only settings — no household
/// management here (that stays in the persistent header switcher, Story 1.7). Renders as a tab
/// body inside [HouseholdShell]'s `IndexedStack`, so it owns no `Scaffold`/`AppBar` of its own —
/// the shell's persistent header stays visible above it.
///
/// Stateful so the Story 7.3 [AccountEmailCubit] is built exactly once (over the ambient
/// [AuthenticatedHttpClient], seeded from the live `AuthState.email`) and disposed with the
/// widget, mirroring [FirstRunRouter]'s "build API clients once" rationale.
class ProfileScreen extends StatefulWidget {
  const ProfileScreen({super.key});

  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  late final AccountEmailCubit _accountEmailCubit;

  @override
  void initState() {
    super.initState();
    final authState = context.read<AuthCubit>().state;
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

  @override
  void dispose() {
    _accountEmailCubit.close();
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
            _IdentityHeader(displayName: authState.displayName, email: authState.email),
            const SizedBox(height: SgartShapes.space4),
            Text(localizations.profileAccountSectionLabel, style: Theme.of(context).textTheme.labelLarge),
            ListTile(
              key: const Key('profile-recovery-phrase-row'),
              leading: const Icon(Icons.key_outlined),
              title: Text(localizations.profileRecoveryPhraseRowLabel),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => openRecoveryPhraseRevealPage(context),
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

/// Display-only identity block (AD-6, never persisted): an avatar showing the display name's
/// initial, the display name, and the email — all read live from [AuthState].
class _IdentityHeader extends StatelessWidget {
  const _IdentityHeader({required this.displayName, required this.email});

  final String? displayName;
  final String? email;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final resolvedName = displayName ?? '';

    return Row(
      children: [
        CircleAvatar(
          radius: SgartShapes.minTapTarget / 2,
          child: Text(_initial(resolvedName)),
        ),
        const SizedBox(width: SgartShapes.space4),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(resolvedName, key: const Key('profile-display-name'), style: theme.textTheme.titleMedium),
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
