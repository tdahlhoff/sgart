import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../theme/sgart_theme_access.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/device_credential_store.dart';

/// Pushes the shared [RecoveryPhraseRevealPage], re-providing [DeviceCredentialStore] across the
/// root-Navigator push boundary — the provider-escape lesson `CreateOrAwaitChoicePage._openOnboarding`
/// documents: the root Navigator sits above the caller's own ancestor providers, so a pushed route
/// would otherwise miss them entirely. One shared helper for both entry points — the choice screen's
/// "save" action (AC1) and Profil's re-view row (AC2) — mirroring [openAwaitInvitePage] (DRY,
/// CLAUDE.md §1/§8).
void openRecoveryPhraseRevealPage(BuildContext context) {
  final deviceCredentialStore = context.read<DeviceCredentialStore>();
  Navigator.of(context).push(
    MaterialPageRoute<void>(
      builder: (_) => RepositoryProvider<DeviceCredentialStore>.value(
        value: deviceCredentialStore,
        child: const RecoveryPhraseRevealPage(),
      ),
    ),
  );
}

/// Shows the device's 24-word BIP39 recovery phrase (Story 7.2, AC1/AC2, D-B) — the single shared
/// reveal surface reached from two entry points: the choice screen's "save" action (the first
/// moment a person can see it, D-A) and Profil's re-view row (any time after, unlimited, D-C).
///
/// The phrase is read straight from [DeviceCredentialStore.recoveryPhrase] — secure local storage
/// only, never fetched from or sent to the server (AC1/AC2/AC4) — and this page makes no network
/// call of its own. No copy action (D-G): the numbered words are for manual transcription only.
class RecoveryPhraseRevealPage extends StatefulWidget {
  const RecoveryPhraseRevealPage({super.key});

  @override
  State<RecoveryPhraseRevealPage> createState() => _RecoveryPhraseRevealPageState();
}

class _RecoveryPhraseRevealPageState extends State<RecoveryPhraseRevealPage> {
  late final Future<List<String>> _wordsFuture;

  @override
  void initState() {
    super.initState();
    _wordsFuture = context.read<DeviceCredentialStore>().recoveryPhrase();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: SgartAppBar(title: localizations.recoveryPhraseRevealTitle),
      body: SafeArea(
        child: FutureBuilder<List<String>>(
          future: _wordsFuture,
          builder: (context, snapshot) {
            // Surface a read failure instead of hiding it behind a perpetual spinner (fail fast,
            // CLAUDE.md §1) — a secure-storage read can fail even though the entropy exists.
            if (snapshot.hasError) {
              return Center(
                child: Padding(
                  padding: const EdgeInsets.all(SgartShapes.cardPadding),
                  child: Text(
                    localizations.recoveryPhraseRevealLoadError,
                    key: const Key('recovery-phrase-load-error'),
                    textAlign: TextAlign.center,
                  ),
                ),
              );
            }
            final words = snapshot.data;
            if (words == null) {
              return const Center(
                child: CircularProgressIndicator(key: Key('recovery-phrase-loading-indicator')),
              );
            }
            return ListView(
              padding: const EdgeInsets.all(SgartShapes.cardPadding),
              children: [
                Text(
                  localizations.recoveryPhraseRevealWarning,
                  key: const Key('recovery-phrase-warning'),
                ),
                const SizedBox(height: SgartShapes.space4),
                Wrap(
                  key: const Key('recovery-phrase-words'),
                  spacing: SgartShapes.space2,
                  runSpacing: SgartShapes.space2,
                  children: [
                    for (var index = 0; index < words.length; index++)
                      _NumberedWord(number: index + 1, word: words[index]),
                  ],
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

/// One "n. word" chip — sized to its own content (not a fixed grid cell) so a large
/// [MediaQuery.textScaler] simply grows the chip instead of clipping or overflowing (UX-DR5). Carries
/// an explicit a11y label ("Wort n: word") so a screen reader announces the position clearly rather
/// than reading the bare "n. word" glyph.
class _NumberedWord extends StatelessWidget {
  const _NumberedWord({required this.number, required this.word});

  final int number;
  final String word;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    return Container(
      key: Key('recovery-phrase-word-$number'),
      padding: const EdgeInsets.symmetric(horizontal: SgartShapes.space3, vertical: SgartShapes.space2),
      decoration: BoxDecoration(
        borderRadius: SgartShapes.control,
        border: Border.all(color: context.sgartColors.border),
      ),
      child: Text(
        '$number. $word',
        semanticsLabel: localizations.recoveryPhraseWordSemanticLabel(number, word),
      ),
    );
  }
}
