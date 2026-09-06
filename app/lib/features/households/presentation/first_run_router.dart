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
import '../../invites/data/invites_api.dart';
import '../../lists/data/item_suggestions_api.dart';
import '../../lists/data/items_api.dart';
import '../../lists/data/shopping_lists_api.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/stores_api.dart';
import '../../trips/data/trips_api.dart';
import '../data/active_household_store.dart';
import '../data/households_api.dart';
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
  late final HouseholdsApi _householdsApi;
  late final StoresApi _storesApi;
  late final InvitesApi _invitesApi;
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
    final httpClient = AuthenticatedHttpClient(
      dio: _dio,
      accessTokenProvider: () async => authCubit.currentAccessToken,
    );
    _householdsApi = HttpHouseholdsApi(httpClient);
    _storesApi = HttpStoresApi(httpClient);
    _invitesApi = HttpInvitesApi(httpClient);
    _shoppingListsApi = HttpShoppingListsApi(httpClient);
    _itemsApi = HttpItemsApi(httpClient);
    _itemSuggestionsApi = HttpItemSuggestionsApi(httpClient);
    _tripsApi = HttpTripsApi(httpClient);
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
        RepositoryProvider<HouseholdsApi>.value(value: _householdsApi),
        // Stores management + every future inline store picker reads these; provided here (where
        // HouseholdsApi is) so the manage screen and pickers can `context.read` them (Story 1.8).
        RepositoryProvider<StoresApi>.value(value: _storesApi),
        RepositoryProvider<StoreChainReferenceCache>.value(value: _storeChainReferenceCache),
        // The onboarding wizard's invite step + the manage-household hub's invite page read this
        // (Story 4.1).
        RepositoryProvider<InvitesApi>.value(value: _invitesApi),
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

/// Switches on [HouseholdsState] once fetched. Separated from [FirstRunRouter] so tests can drive
/// it with a fake [HouseholdsCubit] instead of the real HTTP dependency (CLAUDE.md §6).
class FirstRunRouterBody extends StatelessWidget {
  const FirstRunRouterBody({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<HouseholdsCubit, HouseholdsState>(
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
