import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/households_api.dart';
import 'create_household_cubit.dart';
import 'create_household_state.dart';
import 'households_cubit.dart';

/// The minimal create-household form (AC1): a name field and a submit button. On success, routes
/// the caller straight into the new household (read-your-writes, AC3) and pops back to the
/// first-run router.
class CreateHouseholdPage extends StatelessWidget {
  const CreateHouseholdPage({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocProvider(
      create: (_) => CreateHouseholdCubit(householdsApi: context.read<HouseholdsApi>()),
      child: const _CreateHouseholdView(),
    );
  }
}

class _CreateHouseholdView extends StatefulWidget {
  const _CreateHouseholdView();

  @override
  State<_CreateHouseholdView> createState() => _CreateHouseholdViewState();
}

class _CreateHouseholdViewState extends State<_CreateHouseholdView> {
  final _nameController = TextEditingController();

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocListener<CreateHouseholdCubit, CreateHouseholdState>(
      listener: (context, state) {
        if (state.status == CreateHouseholdStatus.success) {
          context.read<HouseholdsCubit>().selectHousehold(state.household!);
          Navigator.of(context).popUntil((route) => route.isFirst);
        }
      },
      child: Scaffold(
        appBar: const SgartAppBar(title: 'SGART'),
        body: SafeArea(
          child: BlocBuilder<CreateHouseholdCubit, CreateHouseholdState>(
            builder: (context, state) {
              final isSubmitting = state.status == CreateHouseholdStatus.submitting;
              return SingleChildScrollView(
                padding: const EdgeInsets.all(SgartShapes.cardPadding),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    Text(localizations.householdsCreateHeading),
                    const SizedBox(height: SgartShapes.space4),
                    TextField(
                      key: const Key('household-name-field'),
                      controller: _nameController,
                      decoration: InputDecoration(labelText: localizations.householdsCreateNameFieldLabel),
                    ),
                    if (state.status == CreateHouseholdStatus.failure && state.error != null) ...[
                      const SizedBox(height: SgartShapes.space4),
                      Text(
                        localizedMessageForErrorCode(localizations, state.error!.code),
                        key: const Key('create-household-error'),
                      ),
                    ],
                    const SizedBox(height: SgartShapes.space4),
                    SgartButton(
                      key: const Key('create-household-submit-button'),
                      label: localizations.householdsCreateSubmitButtonLabel,
                      onPressed: isSubmitting
                          ? null
                          : () => context.read<CreateHouseholdCubit>().submit(_nameController.text),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}
