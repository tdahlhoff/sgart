import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../auth/presentation/auth_cubit.dart';
import '../../auth/presentation/auth_state.dart';
import 'locale_settings_page.dart';

/// The Profil tab body (Story 1.11, AC2): a member's personal-only settings — no household
/// management here (that stays in the persistent header switcher, Story 1.7). Renders as a tab
/// body inside [HouseholdShell]'s `IndexedStack`, so it owns no `Scaffold`/`AppBar` of its own —
/// the shell's persistent header stays visible above it.
class ProfileScreen extends StatelessWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final authState = context.watch<AuthCubit>().state;

    return SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(SgartShapes.cardPadding),
        children: [
          _IdentityHeader(displayName: authState.displayName, email: authState.email),
          const SizedBox(height: SgartShapes.space4),
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
          const Divider(height: SgartShapes.space4 * 2),
          Text(localizations.profileAccountSectionLabel, style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: SgartShapes.space2),
          SgartButton(
            key: const Key('sign-out-button'),
            label: localizations.authSignOutButtonLabel,
            variant: SgartButtonVariant.secondary,
            onPressed: () => context.read<AuthCubit>().signOut(),
          ),
        ],
      ),
    );
  }
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
