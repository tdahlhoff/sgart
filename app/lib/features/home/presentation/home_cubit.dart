import 'package:flutter_bloc/flutter_bloc.dart';

/// Minimal placeholder Cubit that proves the BLoC wiring and testing setup.
///
/// It counts how many times the scaffold has been probed — no domain meaning. Real state
/// management (offline queue, live sync, list/trip state) is introduced by later stories; this
/// exists only to establish the per-screen BLoC pattern the codebase will follow.
class HomeCubit extends Cubit<int> {
  HomeCubit() : super(0);

  void registerProbe() => emit(state + 1);
}
