import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/formatting/date_formatter.dart';
import '../../../l10n/formatting/number_formatter.dart';
import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import 'locale_cubit.dart';
import 'locale_state.dart';

/// The „Sprache & Region" screen (Story 1.10): a member views their effective locale and picks one
/// of Systemstandard / `de-DE` / `de-AT` / `de-CH`. Selecting a row applies the choice through the
/// ancestor [LocaleCubit] (above `MaterialApp`) — the whole app re-renders immediately — and confirms
/// with a SnackBar. A live „Vorschau" reformats a sample number and date under the *effective* locale,
/// the only place a locale change is visible in this story (AC2 proof surface).
///
/// Reachable by route only in this story; the visible entry (the Profil screen) is Story 1.11.
class LocaleSettingsPage extends StatelessWidget {
  const LocaleSettingsPage({super.key});

  /// A fixed UTC instant for the date preview — noon avoids a day shift across device time zones.
  static final DateTime _previewInstant = DateTime.utc(2026, 6, 15, 12);

  /// A sample value chosen to expose the region-specific grouping and decimal separators.
  static const num _previewNumber = 1234.5;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: SgartAppBar(title: localizations.localeSettingsHeading),
      body: SafeArea(
        child: BlocBuilder<LocaleCubit, LocaleState>(
          builder: (context, selection) {
            return ListView(
              padding: const EdgeInsets.symmetric(vertical: 8),
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                  child: Text(
                    localizations.localeSettingsIntro,
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                ),
                for (final option in _localeOptions(localizations))
                  _LocaleOptionTile(
                    label: option.label,
                    isSelected: option.state == selection,
                    onTap: () => _select(context, option.state, selection, localizations),
                  ),
                const Divider(height: 32),
                _PreviewSection(
                  sectionLabel: localizations.localePreviewSectionLabel,
                  // The *effective* locale Flutter resolved (device default under Systemstandard, or
                  // the pinned region) — reading it here makes the preview flip as the selection does.
                  localeName: Localizations.localeOf(context).toString(),
                ),
              ],
            );
          },
        ),
      ),
    );
  }

  void _select(
      BuildContext context, LocaleState state, LocaleState current, AppLocalizations localizations) {
    // Tapping the already-active option changes nothing — skip the apply and the confirmation so the
    // SnackBar only ever confirms a real change.
    if (state == current) {
      return;
    }
    context.read<LocaleCubit>().select(state);
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text(localizations.localeChangeConfirmation, key: const Key('locale-change-confirmation')),
      ));
  }

  List<_LocaleOption> _localeOptions(AppLocalizations localizations) => [
        _LocaleOption(localizations.localeOptionSystemLabel, const SystemLocale()),
        _LocaleOption(localizations.localeOptionGermanyLabel, const ExplicitLocale(Locale('de', 'DE'))),
        _LocaleOption(localizations.localeOptionAustriaLabel, const ExplicitLocale(Locale('de', 'AT'))),
        _LocaleOption(
            localizations.localeOptionSwitzerlandLabel, const ExplicitLocale(Locale('de', 'CH'))),
      ];
}

/// A pairing of an already-localized label with the [LocaleState] it selects.
class _LocaleOption {
  const _LocaleOption(this.label, this.state);

  final String label;
  final LocaleState state;
}

/// A single-select, radio-style row: the active one shows a filled radio icon and announces itself as
/// selected. A [ListTile] guarantees the ≥48px interactive target and honors OS text scaling (NFR10 /
/// UX-DR5); its text is plain-language German from the catalog.
class _LocaleOptionTile extends StatelessWidget {
  const _LocaleOptionTile({required this.label, required this.isSelected, required this.onTap});

  final String label;
  final bool isSelected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: Icon(isSelected ? Icons.radio_button_checked : Icons.radio_button_unchecked),
      title: Text(label),
      selected: isSelected,
      onTap: onTap,
    );
  }
}

/// The live formatting preview — a sample number and date formatted through the existing display
/// formatters under [localeName], rebuilt whenever the effective locale changes (AC2).
class _PreviewSection extends StatelessWidget {
  const _PreviewSection({required this.sectionLabel, required this.localeName});

  final String sectionLabel;
  final String localeName;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final numberSample = NumberFormatter(localeName: localeName).format(LocaleSettingsPage._previewNumber);
    final dateSample = DateFormatter(localeName: localeName).formatDate(LocaleSettingsPage._previewInstant);

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(sectionLabel, style: theme.textTheme.labelLarge),
          const SizedBox(height: 8),
          Text(numberSample, key: const Key('locale-preview-number'), style: theme.textTheme.bodyLarge),
          Text(dateSample, key: const Key('locale-preview-date'), style: theme.textTheme.bodyLarge),
        ],
      ),
    );
  }
}
