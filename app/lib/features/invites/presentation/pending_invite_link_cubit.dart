import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/invite_link.dart';

/// Holds the most recently offered OS deep-link/cold-start invite link (Story 4.6, AC1/AC3) until
/// something routes on it. Lives above [AuthGate] (wired once in `main.dart`) so a link that
/// arrives before sign-in survives the sign-in→accept transition — [FirstRunRouterBody] is the one
/// place with the `InvitesApi`/`HouseholdsCubit` needed to open the accept screen, and it only
/// mounts once authenticated.
///
/// [consume] is the re-entrancy guard (Epic-2 Action 3 lesson, AC1): reading [state] never clears
/// it, so a rebuild alone cannot re-trigger routing — only an explicit `consume()` call does, and
/// it returns the link exactly once.
class PendingInviteLinkCubit extends Cubit<InviteLink?> {
  PendingInviteLinkCubit() : super(null);

  /// Records a newly received invite link, overwriting any not-yet-consumed one (the latest link
  /// wins — mirrors the OS deep-link/web-fallback "same outcome" invariant, AC3).
  void offer(InviteLink link) => emit(link);

  /// Returns the pending link and clears it, or `null` if none is pending.
  InviteLink? consume() {
    final link = state;
    if (link != null) {
      emit(null);
    }
    return link;
  }
}
