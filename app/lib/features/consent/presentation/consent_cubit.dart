import 'package:flutter/widgets.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/consent_api.dart';
import 'consent_state.dart';

/// Drives the consent gate (Story 7.4, AC1/AC4): loads the caller's status on entry and, iff not
/// accepted or the accepted version is stale, blocks until [accept] records the current version.
/// Depends only on [ConsentApi] so tests never touch the network (CLAUDE.md §6); guards every
/// `emit` with `isClosed`.
class ConsentCubit extends Cubit<ConsentState> {
  ConsentCubit({required this._consentApi}) : super(const ConsentState.loading());

  final ConsentApi _consentApi;

  Future<void> load() async {
    _safeEmit(const ConsentState.loading());
    try {
      final status = await _consentApi.getStatus();
      _safeEmit(status.needsConsent
          ? ConsentState.needsConsent(status.currentVersion)
          : const ConsentState.accepted());
    } on Object catch (error) {
      _safeEmit(ConsentState.failure(_toAppError(error)));
    }
  }

  /// Accepts the version the last [load] reported as current — a no-op guard if called before a
  /// version is known (the accept button is only reachable once [ConsentGateStatus.needsConsent]).
  Future<void> accept() async {
    final version = state.currentVersion;
    if (version == null) {
      return;
    }
    _safeEmit(const ConsentState.loading());
    try {
      await _consentApi.accept();
      _safeEmit(const ConsentState.accepted());
    } on Object catch (error) {
      _safeEmit(ConsentState.failure(_toAppError(error)));
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'consent.unknown', message: error.toString());
  }

  void _safeEmit(ConsentState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}

/// Guarded-optional read of the ancestor [ConsentCubit] (mirrors `FirstRunRouterBody`'s
/// `PendingInviteLinkCubitResolver`) — a standalone test harness that pushes
/// `CreateOrAwaitChoicePage`/`OnboardingWizardPage` directly has no [ConsentCubit] ancestor, and
/// must not crash. In production, `ConsentGatedChoicePage` always provides one, and
/// `CreateOrAwaitChoicePage._openOnboarding` re-provides it by value across the push boundary
/// (Story 7.4, AC3) so the onboarding wizard can reload the gate's status on a stale-client
/// `409 consent.required`.
ConsentCubit? tryReadConsentCubit(BuildContext context) {
  try {
    return context.read<ConsentCubit>();
  } on Object {
    return null;
  }
}
