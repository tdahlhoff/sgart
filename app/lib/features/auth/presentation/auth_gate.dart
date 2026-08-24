import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/http/authenticated_http_client.dart';
import '../../../shared/http/backend_config.dart';
import '../../households/data/active_household_store.dart';
import '../../households/presentation/first_run_router.dart';
import '../data/app_auth_oidc_client.dart';
import '../data/flutter_secure_token_storage.dart';
import '../data/identity_api.dart';
import '../data/secure_token_storage.dart';
import 'auth_cubit.dart';
import 'auth_state.dart';
import 'sign_in_page.dart';

/// The app's single entry path: an unauthenticated sign-in gate that switches to first-run
/// routing (Story 1.6) once signed in. Replaces the Story 1.1/1.3 `HomePage` placeholder — one
/// entry screen, not two competing ones.
class AuthGate extends StatelessWidget {
  const AuthGate({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocProvider(
      create: (_) => _buildAuthCubit()..bootstrap(),
      child: const AuthGateBody(),
    );
  }

  AuthCubit _buildAuthCubit() {
    const SecureTokenStorage tokenStorage = FlutterSecureTokenStorage();
    final dio = Dio(BaseOptions(baseUrl: BackendConfig.baseUrl));
    // The cubit owns the live session, so the bearer interceptor reads its in-memory token rather
    // than decrypting secure storage on every request. The closure runs only during a request,
    // by which point `cubit` is assigned.
    late final AuthCubit cubit;
    final httpClient = AuthenticatedHttpClient(
      dio: dio,
      accessTokenProvider: () async => cubit.currentAccessToken,
    );
    cubit = AuthCubit(
      oidcClient: const AppAuthOidcClient(),
      tokenStorage: tokenStorage,
      identityApi: HttpIdentityApi(httpClient),
      activeHouseholdStore: const SharedPreferencesActiveHouseholdStore(),
    );
    return cubit;
  }
}

/// Switches between [SignInPage] and [FirstRunRouter] on [AuthCubit]'s state. Separated from
/// [AuthGate] so tests can drive it with a fake [AuthCubit] instead of the real
/// OIDC/secure-storage/HTTP dependencies (CLAUDE.md §6).
///
/// [authenticatedBuilder] defaults to the real [FirstRunRouter] — which itself builds a real
/// HTTP client — so a test that only cares about the sign-in/authenticated *switch* can override
/// it with a network-free placeholder instead (CLAUDE.md §6, "isolate external systems").
class AuthGateBody extends StatelessWidget {
  const AuthGateBody({super.key, this.authenticatedBuilder = _defaultAuthenticatedBuilder});

  final WidgetBuilder authenticatedBuilder;

  static Widget _defaultAuthenticatedBuilder(BuildContext context) => const FirstRunRouter();

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<AuthCubit, AuthState>(
      builder: (context, state) {
        if (state.status == AuthStatus.authenticated) {
          return Builder(builder: authenticatedBuilder);
        }
        return const SignInPage();
      },
    );
  }
}
