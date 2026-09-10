import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/http/authenticated_http_client.dart';
import '../../../shared/http/backend_config.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../auth/presentation/auth_cubit.dart';
import '../../invites/data/invite_link.dart';
import '../../invites/data/invites_api.dart';
import '../../invites/presentation/pending_invite_link_cubit.dart';
import '../../lists/data/item_suggestions_api.dart';
import '../../lists/data/items_api.dart';
import '../../lists/data/shopping_lists_api.dart';
import '../../members/data/members_api.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/stores_api.dart';
import '../../trips/data/trips_api.dart';
import '../data/active_household_store.dart';
import '../data/households_api.dart';
import 'await_invite_page.dart';
import 'create_or_await_choice_page.dart';
import 'household_selection_page.dart';
import 'household_shell.dart';
import 'households_cubit.dart';
import 'households_state.dart';

/// The post-sign-in entry point (replaces `AuthenticatedPlaceholderPage`, Story 1.4's explicit
/// placeholder — one entry path). Fetches the caller's households and branches: 0 → create/await
/// choice · 1 → the household shell · ≥2 → selection (AC1, AC2, AC3).
///
/// Stateful so the `Dio` transport (and the `HouseholdsApi` over it) is built exactly once and
/// disposed with the widget — a `StatelessWidget` would allocate a fresh, undisposed HTTP client
/// on every rebuild.
class FirstRunRouter extends StatefulWidget {
  const FirstRunRouter({super.key});

  @override
  State<FirstRunRouter> createState() => _FirstRunRouterState();
}

class _FirstRunRouterState extends State<FirstRunRouter> {
  late final Dio _dio;
  late final AuthenticatedHttpClient _httpClient;
  late final HouseholdsApi _householdsApi;
  late final StoresApi _storesApi;
  late final InvitesApi _invitesApi;
  late final MembersApi _membersApi;
  late final ShoppingListsApi _shoppingListsApi;
  late final ItemsApi _itemsApi;
  late final ItemSuggestionsApi _itemSuggestionsApi;
  late final TripsApi _tripsApi;
  static const ActiveHouseholdStore _activeHouseholdStore = SharedPreferencesActiveHouseholdStore();
  static const StoreChainReferenceCache _storeChainReferenceCache =
      SharedPreferencesStoreChainReferenceCache();

  @override
  void initState() {
    super.initState();
    final authCubit = context.read<AuthCubit>();
    _dio = Dio(BaseOptions(baseUrl: BackendConfig.baseUrl));
    _httpClient = AuthenticatedHttpClient(
      dio: _dio,
      accessTokenProvider: () async => authCubit.currentAccessToken,
    );
    _householdsApi = HttpHouseholdsApi(_httpClient);
    _storesApi = HttpStoresApi(_httpClient);
    _invitesApi = HttpInvitesApi(_httpClient);
    _membersApi = HttpMembersApi(_httpClient);
    _shoppingListsApi = HttpShoppingListsApi(_httpClient);
    _itemsApi = HttpItemsApi(_httpClient);
    _itemSuggestionsApi = HttpItemSuggestionsApi(_httpClient);
    _tripsApi = HttpTripsApi(_httpClient);
  }

  @override
  void dispose() {
    _dio.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MultiRepositoryProvider(
      providers: [
        // The household shell reads this to open its per-household live-sync SSE stream (Story 4.4).
        RepositoryProvider<AuthenticatedHttpClient>.value(value: _httpClient),
        RepositoryProvider<HouseholdsApi>.value(value: _householdsApi),
        // Stores management + every future inline store picker reads these; provided here (where
        // HouseholdsApi is) so the manage screen and pickers can `context.read` them (Story 1.8).
        RepositoryProvider<StoresApi>.value(value: _storesApi),
        RepositoryProvider<StoreChainReferenceCache>.value(value: _storeChainReferenceCache),
        // The onboarding wizard's invite step + the manage-household hub's invite page read this
        // (Story 4.1).
        RepositoryProvider<InvitesApi>.value(value: _invitesApi),
        // The manage-household hub's member-management page reads this (Story 4.3).
        RepositoryProvider<MembersApi>.value(value: _membersApi),
        // The Listen tab reads this to build its household-scoped ShoppingListsCubit (Story 2.1).
        RepositoryProvider<ShoppingListsApi>.value(value: _shoppingListsApi),
        // The list detail screen reads this to build its list-scoped ListDetailCubit (Story 2.3).
        RepositoryProvider<ItemsApi>.value(value: _itemsApi),
        // The list detail screen's fast-add field reads this for autocomplete (Story 2.5).
        RepositoryProvider<ItemSuggestionsApi>.value(value: _itemSuggestionsApi),
        // The list detail screen's "Einkauf starten" action reads this to start a trip (Story 3.1).
        RepositoryProvider<TripsApi>.value(value: _tripsApi),
      ],
      child: BlocProvider(
        create: (_) => HouseholdsCubit(
          householdsApi: _householdsApi,
          activeHouseholdStore: _activeHouseholdStore,
        )..bootstrap(),
        child: const FirstRunRouterBody(),
      ),
    );
  }
}

/// Resolves the app-wide [PendingInviteLinkCubit], if any (Story 4.6, AC1/AC3) — mirrors
/// [HouseholdShell]'s `PushNotificationsResolver` guarded-optional shape. Overridden in tests that
/// want deterministic control over deep-link routing without a real DI ancestor.
typedef PendingInviteLinkCubitResolver = PendingInviteLinkCubit? Function(BuildContext context);

/// Switches on [HouseholdsState] once fetched. Separated from [FirstRunRouter] so tests can drive
/// it with a fake [HouseholdsCubit] instead of the real HTTP dependency (CLAUDE.md §6).
///
/// Also the Story 4.6 (AC1/AC3) routing point for a pending OS deep-link/cold-start invite link:
/// [FirstRunRouter] only mounts once authenticated, so this is where a link offered to
/// [PendingInviteLinkCubit] while signed out finally gets consumed — this class already owns the
/// `InvitesApi`/`HouseholdsCubit` [openAwaitInvitePage] needs, so no second accept path is built
/// (DRY, mirrors [CreateOrAwaitChoicePage]'s "I have an invite" choice).
class FirstRunRouterBody extends StatelessWidget {
  const FirstRunRouterBody({super.key, this.pendingInviteLinkCubitResolver = _defaultPendingInviteLinkCubitResolver});

  final PendingInviteLinkCubitResolver pendingInviteLinkCubitResolver;

  /// Guarded exactly like [HouseholdShell]'s push resolver — a harness with no
  /// [PendingInviteLinkCubit] ancestor (most widget tests) simply gets no deep-link routing rather
  /// than a crash.
  static PendingInviteLinkCubit? _defaultPendingInviteLinkCubitResolver(BuildContext context) {
    try {
      return context.read<PendingInviteLinkCubit>();
    } on Object {
      return null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final content = BlocBuilder<HouseholdsCubit, HouseholdsState>(
      builder: (context, state) {
        return switch (state.status) {
          HouseholdsStatus.loading => const _LoadingPage(),
          HouseholdsStatus.needsChoice => const CreateOrAwaitChoicePage(),
          HouseholdsStatus.shell => HouseholdShell(
              activeHousehold: state.activeHousehold!,
              households: state.households!,
            ),
          HouseholdsStatus.selection => HouseholdSelectionPage(households: state.households!),
          HouseholdsStatus.failure => const _FailurePage(),
        };
      },
    );

    final pendingInviteLinkCubit = pendingInviteLinkCubitResolver(context);
    if (pendingInviteLinkCubit == null) {
      return content;
    }
    return _PendingInviteLinkRouter(cubit: pendingInviteLinkCubit, child: content);
  }
}

/// Routes a [PendingInviteLinkCubit] link into [openAwaitInvitePage] exactly once (AC1/AC3): once
/// for a link that was already pending the moment this widget mounts (the signed-out→sign-in case
/// — [FirstRunRouterBody] only exists post-auth, so a link offered before sign-in sits in the
/// cubit's current state with no new emission for a plain [BlocListener] to catch), and once more
/// for any link offered later while this subtree is already alive. [PendingInviteLinkCubit.consume]
/// is what keeps the two paths from double-routing the same link.
class _PendingInviteLinkRouter extends StatefulWidget {
  const _PendingInviteLinkRouter({required this.cubit, required this.child});

  final PendingInviteLinkCubit cubit;
  final Widget child;

  @override
  State<_PendingInviteLinkRouter> createState() => _PendingInviteLinkRouterState();
}

class _PendingInviteLinkRouterState extends State<_PendingInviteLinkRouter> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _consumeAndRoute());
  }

  void _consumeAndRoute() {
    if (!mounted) {
      return;
    }
    final link = widget.cubit.consume();
    if (link != null) {
      openAwaitInvitePage(context, initialLink: link);
    }
  }

  @override
  Widget build(BuildContext context) {
    return BlocListener<PendingInviteLinkCubit, InviteLink?>(
      bloc: widget.cubit,
      listenWhen: (previous, current) => current != null,
      listener: (context, link) => _consumeAndRoute(),
      child: widget.child,
    );
  }
}

class _LoadingPage extends StatelessWidget {
  const _LoadingPage();

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: const SgartAppBar(title: 'SGART'),
      body: Center(
        child: Text(localizations.householdsLoadingLabel, key: const Key('households-loading-label')),
      ),
    );
  }
}

class _FailurePage extends StatelessWidget {
  const _FailurePage();

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: const SgartAppBar(title: 'SGART'),
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(SgartShapes.cardPadding),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(localizations.householdsLoadFailedError, key: const Key('households-load-error')),
                const SizedBox(height: SgartShapes.space4),
                SgartButton(
                  key: const Key('households-retry-button'),
                  label: localizations.householdsRetryButtonLabel,
                  onPressed: () => context.read<HouseholdsCubit>().bootstrap(),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
