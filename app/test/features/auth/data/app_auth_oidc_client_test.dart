import 'package:flutter_appauth/flutter_appauth.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/app_auth_oidc_client.dart';
import 'package:sgart/features/auth/data/keycloak_config.dart';

/// Captures the requests `AppAuthOidcClient` builds instead of talking to the platform channel —
/// the regression this guards is AppAuth-Android's hard `only https connections are permitted`
/// crash against the dev-only plain-HTTP Keycloak issuer (see `KeycloakConfig.issuer`).
class _RecordingAppAuth extends FlutterAppAuth {
  AuthorizationTokenRequest? authorizeAndExchangeCodeRequest;
  TokenRequest? tokenRequest;
  EndSessionRequest? endSessionRequest;

  @override
  Future<AuthorizationTokenResponse> authorizeAndExchangeCode(AuthorizationTokenRequest request) async {
    authorizeAndExchangeCodeRequest = request;
    return AuthorizationTokenResponse('access-token', 'refresh-token', null, null, null, null, null, {});
  }

  @override
  Future<TokenResponse> token(TokenRequest request) async {
    tokenRequest = request;
    return TokenResponse('access-token', 'refresh-token', null, null, null, null, {});
  }

  @override
  Future<EndSessionResponse> endSession(EndSessionRequest request) async {
    endSessionRequest = request;
    return EndSessionResponse(null);
  }
}

void main() {
  late _RecordingAppAuth appAuth;
  late AppAuthOidcClient client;

  setUp(() {
    appAuth = _RecordingAppAuth();
    client = AppAuthOidcClient(appAuth);
  });

  group('AppAuthOidcClient', () {
    test('signInAllowsInsecureConnectionsSoTheDevOnlyPlainHttpIssuerDoesNotCrashAppAuth', () async {
      await client.signIn();

      expect(appAuth.authorizeAndExchangeCodeRequest!.allowInsecureConnections, isTrue);
    });

    test('refreshAllowsInsecureConnectionsSoTheDevOnlyPlainHttpIssuerDoesNotCrashAppAuth', () async {
      await client.refresh('refresh-token');

      expect(appAuth.tokenRequest!.allowInsecureConnections, isTrue);
    });

    test('endSessionAllowsInsecureConnectionsSoTheDevOnlyPlainHttpIssuerDoesNotCrashAppAuth', () async {
      await client.endSession(idToken: 'id-token');

      expect(appAuth.endSessionRequest!.allowInsecureConnections, isTrue);
    });

    // Regression guard: flutter_appauth 12.1.0's Android plugin reads allowInsecureConnections
    // into a local variable for endSession and never applies it before its issuer-discovery HTTP
    // call, so passing allowInsecureConnections alone still crashes when sign-out is the first
    // AppAuth call in the process. Supplying serviceConfiguration explicitly skips discovery
    // (and that buggy connection builder) entirely — see KeycloakConfig's doc comment.
    test('endSessionSuppliesExplicitEndpointsSoItNeverTriggersIssuerDiscovery', () async {
      await client.endSession(idToken: 'id-token');

      final serviceConfiguration = appAuth.endSessionRequest!.serviceConfiguration;
      expect(serviceConfiguration, isNotNull);
      expect(serviceConfiguration!.authorizationEndpoint, KeycloakConfig.authorizationEndpoint);
      expect(serviceConfiguration.tokenEndpoint, KeycloakConfig.tokenEndpoint);
      expect(serviceConfiguration.endSessionEndpoint, KeycloakConfig.endSessionEndpoint);
      expect(appAuth.endSessionRequest!.issuer, isNull);
    });
  });
}
